package blbl.cat3399.feature.home

import android.os.Bundle
import androidx.core.view.isVisible
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.tabs.TabLayout
import blbl.cat3399.R
import blbl.cat3399.core.api.BangumiApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.BangumiCalendarDay
import blbl.cat3399.core.model.BangumiCalendarItem
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.TabSwitchFocusTarget
import blbl.cat3399.core.ui.enableDpadTabFocus
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.postIfAttached
import blbl.cat3399.core.ui.requestFocusAdapterPositionReliable
import blbl.cat3399.databinding.FragmentBangumiCalendarBinding
import blbl.cat3399.ui.MainActivity
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 首页「新番表」栏:各季度番剧星期视图(按周一~周日分组的时间表)。
 *
 * - 顶部季度切换栏(年份 + 冬/春/夏/秋):
 *   - 当季 → Bangumi calendar API(bgm.tv)星期视图(12h 缓存)
 *   - 历史季度 → Bangumi browse API 按热度列表(单 header,无星期分组)
 * - 点击番剧卡片 → 跳转自带搜索的"该番剧名"搜索结果页(复用 SearchFragment)。
 *
 * 布局:GridLayoutManager + spanSizeLookup(分组标题跨整行,番剧卡片网格排列),
 * 与首页其他 tab 共用 DpadGridController 焦点体系。
 */
class BangumiCalendarFragment : Fragment(), RefreshKeyHandler, TabSwitchFocusTarget {
    private var _binding: FragmentBangumiCalendarBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: BangumiCalendarAdapter
    private var requestToken: Int = 0
    private var initialLoadTriggered: Boolean = false

    /** 浏览模式(由 HomeTabs 传入):TV动画 / 剧场动画 / 日剧 / 电影 */
    private val mode: BangumiCalendarMode
        get() = BangumiCalendarMode.valueOf(requireArguments().getString(ARG_MODE, BangumiCalendarMode.QUARTER_ANIME.name))

    private var selectedYear: Int = Calendar.getInstance().get(Calendar.YEAR)
    private var seasonBarReady: Boolean = false

    private var pendingFocusFirstCardAfterRefresh: Boolean = false

    private var pendingFocusFirstCardFromTab: Boolean = false
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false
    private var pendingFocusFirstCardFromBackToTab0: Boolean = false
    private var lastFocusedAdapterPosition: Int? = null
    private var pendingRestorePosition: Int? = null
    private var dpadGridController: DpadGridController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBangumiCalendarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            adapter =
                BangumiCalendarAdapter(
                    onClick = { position, item -> openSearchFor(position, item) },
                    // 长按复制源标题(原始 name/name_cn,非截断显示标题)
                    onLongClick = { item -> copySourceTitle(item) },
                    // 集数:仅 TV动画显示(含进度);剧场动画/日剧/电影不显示
                    showEpisodeText = mode == BangumiCalendarMode.QUARTER_ANIME,
                    // tag:仅动画(TV动画/剧场动画)显示;日剧/电影不显示
                    showTags = mode != BangumiCalendarMode.DRAMA && mode != BangumiCalendarMode.MOVIE &&
                        mode != BangumiCalendarMode.WESTERN_DRAMA && mode != BangumiCalendarMode.CHINESE_DRAMA &&
                        mode != BangumiCalendarMode.KOREAN_DRAMA,
                )
        }

        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = buildGridLayoutManager()
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recycler.clearOnScrollListeners()
        dpadGridController?.release()
        dpadGridController =
            DpadGridController(
                recyclerView = binding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            // 卡片区上边缘 -> 聚焦季度切换栏
                            focusQuarterBar()
                            return true
                        }

                        override fun onLeftEdge(): Boolean {
                            return switchToPrevTabFromContentEdge()
                        }

                        override fun onRightEdge() {
                            switchToNextTabFromContentEdge()
                        }

                        override fun canLoadMore(): Boolean = false

                        override fun loadMore() = Unit
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                        // 列表顶部有跨列 header(group 0),第一行卡片在 group 1,视作顶部行
                        topHeaderGroups = 1,
                    ),
            ).also { it.install() }

        binding.swipeRefresh.setOnRefreshListener { resetAndLoad(fromUserRefresh = true) }
        setupSeasonBar()
    }

    override fun onResume() {
        super.onResume()
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForCalendar()
        maybeTriggerInitialLoad()
        restoreFocusIfNeeded()
        maybeConsumePendingFocusFirstCard()
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
        b.swipeRefresh.isRefreshing = true
        resetAndLoad(fromUserRefresh = true)
        return true
    }

    // ---- 年份切换栏(单排:最新在前,如 2026 2025 2024 ... 2006) ----
    // D-pad:方向键移动焦点(不切换),OK/点击才加载;鼠标/触控点击直接切换。

    // 年份:当前年 ~ 2006
    private val years: List<Int> by lazy {
        (Calendar.getInstance().get(Calendar.YEAR) downTo QUARTER_START_YEAR).toList()
    }

    private fun setupSeasonBar() {
        binding.tabQuarter.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (!seasonBarReady) return
                    val year = years.getOrNull(tab.position) ?: return
                    selectYearIfChanged(year)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            },
        )

        years.forEach { year -> binding.tabQuarter.addTab(binding.tabQuarter.newTab().setText(year.toString())) }

        // 焦点移动不选中(selectOnFocus=false),仅 OK/点击时才 select
        binding.tabQuarter.enableDpadTabFocus(selectOnFocus = false) { position ->
            AppLog.d("BangumiCalendar", "year tab focus pos=$position")
        }

        // 年份 tab:DOWN -> 卡片区;UP -> 首页 tab 栏
        val tabStrip = binding.tabQuarter.getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until tabStrip.childCount) {
            tabStrip.getChildAt(i).setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        pendingFocusFirstCardFromTab = true
                        pendingFocusFirstCardFromContentSwitch = false
                        pendingFocusFirstCardFromBackToTab0 = false
                        maybeConsumePendingFocusFirstCard()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        focusSelectedTabIfAvailable()
                        true
                    }
                    else -> false
                }
            }
        }

        // 默认选中当前年(第一个);seasonBarReady=false 期间回调被忽略
        binding.tabQuarter.getTabAt(0)?.select()
        seasonBarReady = true
    }

    private fun selectYearIfChanged(year: Int) {
        if (year == selectedYear) return
        AppLog.d("BangumiCalendar", "switch year $selectedYear -> $year")
        selectedYear = year
        pendingFocusFirstCardFromTab = true
        pendingFocusFirstCardFromContentSwitch = false
        pendingFocusFirstCardFromBackToTab0 = false
        resetAndLoad(fromUserRefresh = false)
    }

    /** 卡片区 UP -> 聚焦季度栏当前选中项 */
    private fun focusQuarterBar(): Boolean {
        val tabStrip = binding.tabQuarter.getChildAt(0) as? ViewGroup ?: return false
        val pos = binding.tabQuarter.selectedTabPosition.takeIf { it >= 0 } ?: 0
        return tabStrip.getChildAt(pos)?.requestFocus() ?: false
    }

    private fun buildGridLayoutManager(): GridLayoutManager {
        val spanCount = spanCountForCalendar()
        val lm = GridLayoutManager(requireContext(), spanCount)
        lm.spanSizeLookup =
            object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    // 分组标题占满整行;番剧卡片占 1 格(动态读 spanCount,适配设置变化)
                    return if (adapter.isHeaderPosition(position)) lm.spanCount else 1
                }
            }
        return lm
    }

    private fun spanCountForCalendar(): Int = BiliClient.prefs.pgcGridSpanCount.coerceIn(1, 6)

    private fun maybeTriggerInitialLoad() {
        if (initialLoadTriggered) return
        if (!::adapter.isInitialized) return
        if (adapter.itemCount != 0) {
            initialLoadTriggered = true
            return
        }
        if (binding.swipeRefresh.isRefreshing) return
        binding.swipeRefresh.isRefreshing = true
        resetAndLoad(fromUserRefresh = false)
        initialLoadTriggered = true
    }

    private fun resetAndLoad(fromUserRefresh: Boolean = false) {
        requestToken++
        dpadGridController?.clearPendingFocusAfterLoadMore()
        if (fromUserRefresh) {
            pendingFocusFirstCardAfterRefresh = true
            clearPendingFocusFlags()
            dpadGridController?.parkFocusForDataSetReset()
        }
        // 切换季度/首次加载也显示加载动画(SwipeRefresh 转圈)
        if (isResumed && _binding != null && !_binding!!.swipeRefresh.isRefreshing) {
            _binding!!.swipeRefresh.isRefreshing = true
        }
        adapter.submit(emptyList())
        loadSeason(isRefresh = true)
    }

    private fun loadSeason(isRefresh: Boolean = false) {
        val token = requestToken
        // 全部模式(TV动画/非TV动画/日剧/电影):年份流式按月加载(从当前月往前逐月追加)
        loadYearMonths(token, selectedYear, isRefresh)
    }

    /** 按模式取指定月的缓存数据(纯缓存读):TV动画=纯 cat=1;非TV动画=cat=5∪2∪3 合并 */
    private fun cachedMonth(month: Int): List<BangumiCalendarItem>? {
        return when (mode) {
            BangumiCalendarMode.QUARTER_ANIME -> BangumiApi.cachedYearMonth(2, 1, selectedYear, month)
            BangumiCalendarMode.ANIME_MOVIE -> BangumiApi.cachedAnimeMovieMonth(selectedYear, month)
            else -> BangumiApi.cachedYearMonth(mode.type, mode.cat, selectedYear, month, korean = mode.korean)
        }
    }

    /** 按模式拉取指定月数据(网络,带缓存):TV动画=纯 cat=1;非TV动画=cat=5∪2∪3 合并。
     *  force=true(下拉刷新)时跳过新鲜缓存强制重新拉取 bgm。 */
    private suspend fun fetchMonth(month: Int, force: Boolean = false): List<BangumiCalendarItem>? {
        return when (mode) {
            BangumiCalendarMode.QUARTER_ANIME -> runCatching { BangumiApi.browseYearMonth(2, 1, selectedYear, month, force = force) }.getOrNull()
            BangumiCalendarMode.ANIME_MOVIE -> runCatching { BangumiApi.browseAnimeMovieMonth(selectedYear, month, force = force) }.getOrNull()
            else -> runCatching { BangumiApi.browseYearMonth(mode.type, mode.cat, selectedYear, month, korean = mode.korean, force = force) }.getOrNull()
        }
    }

    /**
     * 年份模式流式加载:从当前月(当年)或 12 月(历史年)往前逐月追加。
     * 1) 已缓存月先显示(秒出) 2) 缺失月后台逐月拉取,每批追加 3) 当年日剧后台补进度。
     * 月份倒序(最新在上),每组 header "M月 · N 部",月内按热度。
     */
    private fun loadYearMonths(token: Int, year: Int, isRefresh: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val now = Calendar.getInstance()
                val startMonth = if (year == now.get(Calendar.YEAR)) now.get(Calendar.MONTH) + 1 else 12
                val months = (startMonth downTo 1).toList()
                val days = ArrayList<BangumiCalendarDay>()

                fun submitCurrent() {
                    if (days.isEmpty()) return
                    adapter.submit(ArrayList(days))
                    // 拿到第一批数据(缓存月或首个网络月)即停刷新转圈,后续月份静默追加;
                    // 但下拉刷新(isRefresh)时保持转圈,直到全部月份强制重拉完成(见下方统一停止)
                    if (token == requestToken && !isRefresh) _binding?.swipeRefresh?.isRefreshing = false
                }

                // 1) 已缓存月先显示(纯缓存读,秒出;不触发网络)
                for (m in months) {
                    if (token != requestToken) return@launch
                    val cached = cachedMonth(m)
                    if (cached != null && cached.isNotEmpty()) {
                        days += BangumiCalendarDay(m, "${m}月", cached, "${m}月 · ${cached.size} 部")
                    }
                }
                if (days.isNotEmpty()) submitCurrent()

                // 2) 缺失月后台逐月拉取追加(保持月份倒序);下拉刷新(isRefresh)时全部月份强制重拉,
                //    但转圈只在"当月(最新月)完整显示"后停止,后续月份静默追加(不拖长转圈)
                for (m in months) {
                    if (token != requestToken) return@launch
                    if (!isRefresh && days.any { it.weekdayId == m }) continue
                    val items = fetchMonth(m, force = isRefresh)
                    if (token != requestToken) return@launch
                    // 下拉刷新:当月(months.first)数据返回后即视为刷新完成,停转圈
                    if (isRefresh && m == months.first() && token == requestToken) {
                        _binding?.swipeRefresh?.isRefreshing = false
                    }
                    if (items.isNullOrEmpty()) continue
                    if (items.isNotEmpty()) {
                        // 刷新时该月可能已有缓存数据,先移除旧行再插入新数据(避免重复)
                        if (isRefresh) days.removeAll { it.weekdayId == m }
                        val day = BangumiCalendarDay(m, "${m}月", items, "${m}月 · ${items.size} 部")
                        val idx = days.indexOfFirst { it.weekdayId < m }
                        if (idx >= 0) days.add(idx, day) else days.add(day)
                        submitCurrent()
                        AppLog.i("BangumiCalendar", "stream month=$m add=${items.size} total=${days.sumOf { it.items.size }}")
                    }
                }

                // 3) 空态提示:整年无任何数据;下拉刷新完成后统一停止转圈
                if (token == requestToken) {
                    _binding?.swipeRefresh?.isRefreshing = false
                    if (days.isEmpty()) {
                        binding.tvEmpty.isVisible = true
                    } else {
                        binding.tvEmpty.isVisible = false
                    }
                }

                // 3) 焦点恢复(下拉刷新后聚焦第一卡)
                if (token == requestToken) {
                    _binding?.let { b ->
                        b.recycler.postIfAlive(isAlive = { _binding === b && isResumed }) {
                            if (isRefresh && pendingFocusFirstCardAfterRefresh) {
                                pendingFocusFirstCardAfterRefresh = false
                                clearPendingFocusFlags()
                                lastFocusedAdapterPosition = firstCardPosition() ?: 0
                                val target = firstCardPosition()
                                if (target != null) {
                                    b.recycler.requestFocusAdapterPositionReliable(
                                        position = target,
                                        smoothScroll = false,
                                        isAlive = { _binding === b && isResumed },
                                        onFocused = {
                                            lastFocusedAdapterPosition = target
                                            dpadGridController?.unparkFocusAfterDataSetReset()
                                        },
                                    )
                                } else {
                                    b.recycler.requestFocus()
                                    dpadGridController?.unparkFocusAfterDataSetReset()
                                }
                                return@postIfAlive
                            }
                            maybeConsumePendingFocusFirstCard()
                            dpadGridController?.consumePendingFocusAfterLoadMore()
                        }
                    }
                    restoreFocusIfNeeded()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("BangumiCalendar", "stream load failed mode=${mode.name}", t)
                context?.let { AppToast.show(it, "加载失败，可查看 Logcat(标签 BLBL)") }
            } finally {
                if (token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
            }
        }
    }

    private fun openSearchFor(position: Int, item: BangumiCalendarItem) {
        if (!isAdded) return
        val keyword = item.searchKeyword
        if (keyword.isEmpty()) return
        // 记录点击卡片位置,搜索页返回时恢复焦点
        if (position != RecyclerView.NO_POSITION) pendingRestorePosition = position
        (activity as? MainActivity)?.navigateToSearch(keyword, returnToBangumi = true)
    }

    /** 长按复制源标题(原始 name/name_cn,非截断显示标题) */
    private fun copySourceTitle(item: BangumiCalendarItem) {
        if (!isAdded) return
        val text = item.searchKeyword
        if (text.isEmpty()) return
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("bangumi_title", text))
        AppToast.show(requireContext(), "已复制：$text")
    }

    // ---- 焦点管理(与首页其他 tab 保持一致) ----

    override fun requestFocusFirstCardFromTab(): Boolean {
        pendingFocusFirstCardFromTab = true
        pendingFocusFirstCardFromContentSwitch = false
        pendingFocusFirstCardFromBackToTab0 = false
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard()
    }

    override fun requestFocusFirstCardFromContentSwitch(): Boolean {
        pendingFocusFirstCardFromContentSwitch = true
        pendingFocusFirstCardFromTab = false
        pendingFocusFirstCardFromBackToTab0 = false
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard()
    }

    override fun requestFocusFirstCardFromBackToTab0(): Boolean {
        pendingFocusFirstCardFromBackToTab0 = true
        pendingFocusFirstCardFromTab = false
        pendingFocusFirstCardFromContentSwitch = false
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard()
    }

    private fun maybeConsumePendingFocusFirstCard(): Boolean {
        if (!pendingFocusFirstCardFromTab && !pendingFocusFirstCardFromContentSwitch && !pendingFocusFirstCardFromBackToTab0) return false
        if (!isAdded || _binding == null) return false
        if (!isResumed) return false
        if (!this::adapter.isInitialized) return false

        val focused = activity?.currentFocus
        if (focused != null && focused != binding.recycler && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
            val holder = binding.recycler.findContainingViewHolder(focused)
            val pos = holder?.bindingAdapterPosition?.takeIf { it != RecyclerView.NO_POSITION }
            if (pendingFocusFirstCardFromBackToTab0) {
                if (pos == firstCardPosition()) {
                    lastFocusedAdapterPosition = pos
                    clearPendingFocusFlags()
                    return false
                }
            } else {
                rememberFocusedAdapterPositionFromView(focused)
                clearPendingFocusFlags()
                return false
            }
        }

        val parentView = parentFragment?.view
        val tabLayout =
            parentView?.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout)
        if (pendingFocusFirstCardFromTab) {
            if (focused == null || tabLayout == null || !FocusTreeUtils.isDescendantOf(focused, tabLayout)) {
                pendingFocusFirstCardFromTab = false
            }
        }

        val target = resolvePendingFocusTarget() ?: run {
            binding.recycler.requestFocus()
            clearPendingFocusFlags()
            return true
        }
        val recycler = binding.recycler
        recycler.requestFocusAdapterPositionReliable(
            position = target,
            smoothScroll = false,
            isAlive = { _binding != null && isResumed },
            onFocused = {
                lastFocusedAdapterPosition = target
                clearPendingFocusFlags()
            },
        )
        return true
    }

    private fun resolvePendingFocusTarget(): Int? {
        if (pendingFocusFirstCardFromTab || pendingFocusFirstCardFromBackToTab0) return firstCardPosition()
        if (!pendingFocusFirstCardFromContentSwitch) return firstCardPosition()
        val saved = lastFocusedAdapterPosition
        val firstCard = firstCardPosition() ?: return null
        val lastCard = lastCardPosition() ?: firstCard
        return (saved ?: firstCard).coerceIn(firstCard, lastCard)
    }

    private fun clearPendingFocusFlags() {
        pendingFocusFirstCardFromTab = false
        pendingFocusFirstCardFromContentSwitch = false
        pendingFocusFirstCardFromBackToTab0 = false
    }

    private fun captureCurrentFocusedAdapterPosition() {
        val recycler = _binding?.recycler ?: return
        val focused = activity?.currentFocus ?: return
        rememberFocusedAdapterPositionFromView(focused, recycler)
    }

    private fun rememberFocusedAdapterPositionFromView(
        focusedView: View,
        recycler: RecyclerView = binding.recycler,
    ) {
        val holder = recycler.findContainingViewHolder(focusedView) ?: return
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        lastFocusedAdapterPosition = position
    }

    private fun firstCardPosition(): Int? = adapter.firstCardPosition()

    private fun lastCardPosition(): Int? {
        var last: Int? = null
        for (i in adapter.itemCount - 1 downTo 0) {
            if (!adapter.isHeaderPosition(i)) {
                last = i
                break
            }
        }
        return last
    }

    private fun restoreFocusIfNeeded() {
        val recycler = _binding?.recycler ?: return
        if (recycler.hasFocus()) return
        // 优先恢复"点击卡片跳搜索前"的位置(搜索页返回场景)
        val pending = pendingRestorePosition
        val pos = pending ?: lastFocusedAdapterPosition ?: return
        if (pos < 0 || pos >= adapter.itemCount) return
        if (adapter.isHeaderPosition(pos)) return
        recycler.postIfAlive(isAlive = { _binding != null && isResumed }) {
            recycler.scrollToPosition(pos)
            recycler.postIfAlive(isAlive = { _binding != null && isResumed }) {
                recycler.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                if (pending != null) pendingRestorePosition = null
            }
        }
    }

    private fun focusSelectedTabIfAvailable(): Boolean {
        val parentView = parentFragment?.view ?: return false
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout) ?: return false
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val pos = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        tabStrip.getChildAt(pos)?.requestFocus() ?: return false
        return true
    }

    private fun switchToNextTabFromContentEdge() {
        val parentView = parentFragment?.view ?: return
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout) ?: return
        if (tabLayout.tabCount <= 1) return
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return
        val cur = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        val next = cur + 1
        if (next >= tabLayout.tabCount) return
        captureCurrentFocusedAdapterPosition()
        tabLayout.getTabAt(next)?.select() ?: return
        tabLayout.postIfAttached {
            (parentFragment as? blbl.cat3399.core.ui.TabContentSwitchFocusHost)?.requestFocusCurrentPagePrimaryItemFromContentSwitch()
                ?: tabStrip.getChildAt(next)?.requestFocus()
        }
    }

    private fun switchToPrevTabFromContentEdge(): Boolean {
        val parentView = parentFragment?.view ?: return false
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout) ?: return false
        if (tabLayout.tabCount <= 1) return false
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val cur = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        val prev = cur - 1
        if (prev < 0) return false
        captureCurrentFocusedAdapterPosition()
        tabLayout.getTabAt(prev)?.select() ?: return false
        tabLayout.postIfAttached {
            (parentFragment as? blbl.cat3399.core.ui.TabContentSwitchFocusHost)?.requestFocusCurrentPagePrimaryItemFromContentSwitch()
                ?: tabStrip.getChildAt(prev)?.requestFocus()
        }
        return true
    }

    override fun onDestroyView() {
        initialLoadTriggered = false
        seasonBarReady = false
        pendingFocusFirstCardFromTab = false
        pendingFocusFirstCardFromContentSwitch = false
        pendingFocusFirstCardFromBackToTab0 = false
        dpadGridController?.release()
        dpadGridController = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        // 季度起点:2006 冬(用户确认,更早的季度意义不大)
        private const val QUARTER_START_YEAR = 2006
        private const val ARG_MODE = "mode"

        fun newInstance(mode: BangumiCalendarMode = BangumiCalendarMode.QUARTER_ANIME): BangumiCalendarFragment =
            BangumiCalendarFragment().apply {
                arguments = Bundle().apply { putString(ARG_MODE, mode.name) }
            }
    }
}

/** 数据浏览模式:全部为年份粒度流式加载(TV动画/非TV动画/日剧/电影/欧美剧/华语剧/韩剧) */
enum class BangumiCalendarMode(
    val type: Int,
    val cat: Int,
    val withProgress: Boolean,
    /** 韩剧:无官方分类,从 cat=6001(电视剧)中按 meta_tags 含"韩国"过滤 */
    val korean: Boolean = false,
) {
    /** TV动画:纯 cat=1(TV),WEB 已拆出到非TV动画页 */
    QUARTER_ANIME(2, 1, true),
    /** 非TV动画:cat=5(WEB) ∪ cat=2(OVA) ∪ cat=3(剧场版) 合并(见 BangumiApi.browseAnimeMovieMonth) */
    ANIME_MOVIE(2, 3, false),
    DRAMA(6, 1, false),
    MOVIE(6, 6002, false),
    WESTERN_DRAMA(6, 2, false),
    CHINESE_DRAMA(6, 3, false),
    KOREAN_DRAMA(6, 6001, false, korean = true),
    ;
}
