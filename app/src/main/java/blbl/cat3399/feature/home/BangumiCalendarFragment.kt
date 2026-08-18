package blbl.cat3399.feature.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.R
import blbl.cat3399.core.api.BangumiApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.BangumiCalendarItem
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.TabSwitchFocusTarget
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.postIfAttached
import blbl.cat3399.core.ui.requestFocusAdapterPositionReliable
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.ui.MainActivity
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 首页 bangumi 栏:各季度番剧星期视图(按周一~周日分组的时间表)。
 *
 * 数据源:Bangumi calendar API(bgm.tv,业界标准);B站番剧时间表 API 留作备源。
 * 点击番剧卡片 → 跳转自带搜索的"该番剧名"搜索结果页(复用 SearchFragment)。
 *
 * 布局:GridLayoutManager + spanSizeLookup(分组标题跨整行,番剧卡片网格排列),
 * 与首页其他 tab 共用 DpadGridController 焦点体系。
 */
class BangumiCalendarFragment : Fragment(), RefreshKeyHandler, TabSwitchFocusTarget {
    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: BangumiCalendarAdapter
    private var requestToken: Int = 0
    private var initialLoadTriggered: Boolean = false

    private var pendingFocusFirstCardAfterRefresh: Boolean = false

    private var pendingFocusFirstCardFromTab: Boolean = false
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false
    private var pendingFocusFirstCardFromBackToTab0: Boolean = false
    private var lastFocusedAdapterPosition: Int? = null
    private var dpadGridController: DpadGridController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            adapter =
                BangumiCalendarAdapter { item ->
                    openSearchFor(item)
                }
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
                            focusSelectedTabIfAvailable()
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
                    ),
            ).also { it.install() }

        binding.swipeRefresh.setOnRefreshListener { resetAndLoad(fromUserRefresh = true) }
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
        adapter.submit(emptyList())
        loadCalendar(isRefresh = true)
    }

    private fun loadCalendar(isRefresh: Boolean = false) {
        val token = requestToken
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val days = BangumiApi.calendar()
                if (token != requestToken) return@launch
                adapter.submit(days)

                _binding?.let { b ->
                    b.recycler.postIfAlive(isAlive = { _binding === b && isResumed }) {
                        if (isRefresh && pendingFocusFirstCardAfterRefresh) {
                            pendingFocusFirstCardAfterRefresh = false
                            clearPendingFocusFlags()
                            lastFocusedAdapterPosition = firstCardPosition() ?: 0

                            val recycler = b.recycler
                            val isUiAlive = { _binding === b && isResumed }
                            val target = firstCardPosition()
                            if (target != null) {
                                recycler.requestFocusAdapterPositionReliable(
                                    position = target,
                                    smoothScroll = false,
                                    isAlive = isUiAlive,
                                    onFocused = {
                                        lastFocusedAdapterPosition = target
                                        dpadGridController?.unparkFocusAfterDataSetReset()
                                    },
                                )
                            } else {
                                recycler.requestFocus()
                                dpadGridController?.unparkFocusAfterDataSetReset()
                            }
                            return@postIfAlive
                        }
                        maybeConsumePendingFocusFirstCard()
                        dpadGridController?.consumePendingFocusAfterLoadMore()
                    }
                }
                restoreFocusIfNeeded()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("BangumiCalendar", "load failed", t)
                context?.let { AppToast.show(it, "加载失败，可查看 Logcat(标签 BLBL)") }
            } finally {
                if (token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
            }
        }
    }

    private fun openSearchFor(item: BangumiCalendarItem) {
        if (!isAdded) return
        val keyword = item.searchKeyword
        if (keyword.isEmpty()) return
        (activity as? MainActivity)?.navigateToSearch(keyword)
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
        val pos = lastFocusedAdapterPosition ?: return
        if (pos < 0 || pos >= adapter.itemCount) return
        if (adapter.isHeaderPosition(pos)) return
        recycler.postIfAlive(isAlive = { _binding != null && isResumed }) {
            recycler.scrollToPosition(pos)
            recycler.postIfAlive(isAlive = { _binding != null && isResumed }) {
                recycler.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
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
        pendingFocusFirstCardFromTab = false
        pendingFocusFirstCardFromContentSwitch = false
        pendingFocusFirstCardFromBackToTab0 = false
        dpadGridController?.release()
        dpadGridController = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(): BangumiCalendarFragment = BangumiCalendarFragment()
    }
}
