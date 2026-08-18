package blbl.cat3399.feature.search

import android.os.Bundle
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.BangumiSeason
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.databinding.FragmentSearchBinding
import blbl.cat3399.feature.my.BangumiDetailActivity
import blbl.cat3399.feature.video.removeVideoCardAndRestoreFocus
import blbl.cat3399.ui.BackPressHandler
import blbl.cat3399.ui.MainActivity
import blbl.cat3399.ui.RefreshKeyHandler
import blbl.cat3399.ui.SidebarFocusHost

class SearchFragment : Fragment(), BackPressHandler, RefreshKeyHandler {
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val state = SearchState()
    private lateinit var adapters: SearchAdapters
    private var renderer: SearchRenderer? = null
    private var interactor: SearchInteractor? = null

    /** 外部(如 bangumi 栏)跳转携带的待搜索关键词;fragment 初始化完成后自动执行 */
    private var pendingAutoSearchKeyword: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ensureAdapters()
        val interactor = SearchInteractor(fragment = this, state = state)
        val renderer = SearchRenderer(fragment = this, binding = binding, state = state, interactor = interactor, adapters = adapters)
        interactor.renderer = renderer

        this.interactor = interactor
        this.renderer = renderer

        interactor.reloadHistory()
        renderer.setupInput()
        renderer.setupResults()
        renderer.applyUiScale()
        interactor.loadHotAndDefault()
        interactor.scheduleMiddleList(state.query)

        if (state.resultsVisible) {
            renderer.showResults()
        } else {
            renderer.showInput()
        }
        if (savedInstanceState == null) {
            renderer.focusFirstKey()
        }
        consumePendingAutoSearchKeyword()
    }

    /**
     * 外部入口(首页 bangumi 栏):直接以指定关键词发起搜索。
     * 若 fragment 尚未完成初始化(首次创建),关键词先缓存,onViewCreated 后自动执行。
     */
    fun searchKeyword(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        val it = interactor
        if (it != null) {
            it.onKeywordClicked(kw)
        } else {
            pendingAutoSearchKeyword = kw
        }
    }

    private fun consumePendingAutoSearchKeyword() {
        val kw = pendingAutoSearchKeyword ?: return
        pendingAutoSearchKeyword = null
        val it = interactor ?: return
        it.onKeywordClicked(kw)
    }

    override fun handleBackPressed(): Boolean {
        val b = _binding ?: return false
        val r = renderer ?: return false
        val resultsVisible = b.panelResults.visibility == View.VISIBLE
        val focused = activity?.currentFocus
        if (focused == null || !FocusTreeUtils.isDescendantOf(focused, b.root)) return false
        AppLog.d("Back", "SearchFragment handleBackPressed resultsVisible=$resultsVisible")
        // 从新番表跳转而来:返回键直接回到新番表并恢复点击卡片焦点
        if (resultsVisible && (activity as? MainActivity)?.consumeSearchReturnToBangumi() == true) {
            (activity as? MainActivity)?.returnToBangumiFromSearch()
            return true
        }
        return if (resultsVisible) {
            r.showInput()
            r.focusFirstKey()
            true
        } else {
            // Search (input panel) behaves like Dynamic: Back in page content returns to the sidebar.
            (activity as? SidebarFocusHost)?.requestFocusSidebarSelectedNav() == true
        }
    }

    override fun onResume() {
        super.onResume()
        renderer?.onResume()
        interactor?.renderInputPanelFromState()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // Returning from Bangumi/Media detail uses add+hide, so SearchFragment won't get onResume().
            // Re-run the minimal "shown again" hooks to restore focus and other pending UI actions.
            renderer?.onShown()
        }
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.panelResults.visibility != View.VISIBLE) return false
        if (b.swipeRefresh.isRefreshing) return true
        interactor?.resetAndLoad()
        return true
    }

    override fun onDestroyView() {
        interactor?.release()
        interactor = null
        renderer?.release()
        renderer = null
        state.lastAppliedUiScale = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = SearchFragment()
    }

    private fun ensureAdapters() {
        if (::adapters.isInitialized) return
        adapters = SearchAdapters(this, state)
    }

    internal fun onSearchKeyClicked(key: String) {
        interactor?.onKeyClicked(key)
    }

    internal fun onSearchKeywordClicked(keyword: String) {
        interactor?.onKeywordClicked(keyword)
    }

    internal fun removeSearchHistoryAndRestoreFocus(keyword: String, position: Int) {
        interactor?.removeHistory(keyword, position)
    }

    internal fun openSearchVideoAt(position: Int) {
        renderer?.openVideoAt(position)
    }

    internal fun openSearchVideoDetailAt(position: Int) {
        renderer?.openDetailAt(position)
    }

    internal fun removeSearchVideoCardAndRestoreFocus(stableKey: String) {
        if (!::adapters.isInitialized) return
        _binding?.recyclerResults?.removeVideoCardAndRestoreFocus(
            adapter = adapters.videoAdapter,
            stableKey = stableKey,
            isAlive = { _binding != null && isResumed },
        )
    }

    internal fun openBangumiDetail(season: BangumiSeason, isDrama: Boolean) {
        if (!isAdded) return
        startActivity(
            Intent(requireContext(), BangumiDetailActivity::class.java)
                .putExtra(BangumiDetailActivity.EXTRA_SEASON_ID, season.seasonId)
                .putExtra(BangumiDetailActivity.EXTRA_IS_DRAMA, isDrama),
        )
    }
}
