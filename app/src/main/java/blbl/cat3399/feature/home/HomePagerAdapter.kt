package blbl.cat3399.feature.home

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import blbl.cat3399.R
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.feature.video.VideoGridFragment

data class HomeTabSpec(
    val key: String,
    @StringRes val titleRes: Int,
    val createFragment: () -> Fragment,
)

object HomeTabs {
    const val KEY_RECOMMEND = "recommend"
    const val KEY_POPULAR = "popular"
    const val KEY_BANGUMI = "bangumi"
    const val KEY_CINEMA = "cinema"
    const val KEY_BANGUMI_CALENDAR = "bangumi_calendar"
    const val KEY_ANIME_MOVIE = "anime_movie"
    const val KEY_DRAMA = "drama"
    const val KEY_MOVIE = "movie"
    const val KEY_WESTERN_DRAMA = "western_drama"
    const val KEY_CHINESE_DRAMA = "chinese_drama"
    const val KEY_KOREAN_DRAMA = "korean_drama"

    val all: List<HomeTabSpec> =
        listOf(
            HomeTabSpec(KEY_RECOMMEND, R.string.tab_recommend) { VideoGridFragment.newRecommend() },
            HomeTabSpec(KEY_POPULAR, R.string.tab_popular) { VideoGridFragment.newPopular() },
            HomeTabSpec(KEY_BANGUMI_CALENDAR, R.string.tab_bangumi_calendar) { BangumiCalendarFragment.newInstance(BangumiCalendarMode.QUARTER_ANIME) },
            HomeTabSpec(KEY_ANIME_MOVIE, R.string.tab_anime_movie) { BangumiCalendarFragment.newInstance(BangumiCalendarMode.ANIME_MOVIE) },
            HomeTabSpec(KEY_MOVIE, R.string.tab_movie) { BangumiCalendarFragment.newInstance(BangumiCalendarMode.MOVIE) },
            HomeTabSpec(KEY_DRAMA, R.string.tab_drama) { BangumiCalendarFragment.newInstance(BangumiCalendarMode.DRAMA) },
            HomeTabSpec(KEY_KOREAN_DRAMA, R.string.tab_korean_drama) { BangumiCalendarFragment.newInstance(BangumiCalendarMode.KOREAN_DRAMA) },
            HomeTabSpec(KEY_WESTERN_DRAMA, R.string.tab_western_drama) { BangumiCalendarFragment.newInstance(BangumiCalendarMode.WESTERN_DRAMA) },
            HomeTabSpec(KEY_CHINESE_DRAMA, R.string.tab_chinese_drama) { BangumiCalendarFragment.newInstance(BangumiCalendarMode.CHINESE_DRAMA) },
            // 源 app 官方页(默认隐藏,可在设置勾回)
            HomeTabSpec(KEY_BANGUMI, R.string.tab_bangumi) { PgcRecommendGridFragment.newBangumi() },
            HomeTabSpec(KEY_CINEMA, R.string.tab_cinema) { PgcRecommendGridFragment.newCinema() },
        )

    fun visibleTabs(prefs: AppPrefs): List<HomeTabSpec> = filterVisible(all, prefs.mainHomeVisibleTabs)

    private fun filterVisible(allTabs: List<HomeTabSpec>, selectedKeys: List<String>): List<HomeTabSpec> {
        if (selectedKeys.isEmpty()) return allTabs
        val selected = selectedKeys.toSet()
        return allTabs.filter { it.key in selected }.ifEmpty { allTabs }
    }
}

class HomePagerAdapter(
    fragment: Fragment,
    private val tabs: List<HomeTabSpec>,
) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = tabs.size

    override fun createFragment(position: Int): Fragment {
        return tabs[position].createFragment()
    }

    override fun getItemId(position: Int): Long = tabs[position].key.hashCode().toLong()

    override fun containsItem(itemId: Long): Boolean = tabs.any { it.key.hashCode().toLong() == itemId }
}
