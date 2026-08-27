package blbl.cat3399.core.model

/**
 * Bangumi (bgm.tv) 季度番剧数据模型。
 * 数据源:GET /v0/subjects?type=2&year=&month=&sort=rank(网页 airtime/yyyy-m 语义)。
 */
data class BangumiCalendarDay(
    /** 分组 id;统一列表视图时为 0 */
    val weekdayId: Int,
    /** 分组标题(如 "26夏"、"星期一") */
    val weekdayCn: String,
    val items: List<BangumiCalendarItem>,
    /** 自定义 header 文案(如 "2026 年 12 部"),为空则用默认统计 */
    val headerTextOverride: String? = null,
)

data class BangumiCalendarItem(
    /** Bangumi 条目 id */
    val id: Long,
    /** 原始标题(通常为日文) */
    val name: String,
    /** 中文译名,可能为空 */
    val nameCn: String,
    val coverUrl: String?,
    /** 放送日期 yyyy-MM-dd */
    val airDate: String?,
    /** 评分,无评分时为 null */
    val score: Double?,
    /** 排名,无排名时为 null */
    val rank: Int?,
    val summary: String?,
    /** 流派标签(已按常见番剧分类白名单过滤,最多 2 个) */
    val tags: List<String>,
    /** 元标签(meta_tags 原始数组,含地区词 日本/欧美/美国/中国/华语/香港/台湾 等,用于地区筛选) */
    val metaTags: List<String> = emptyList(),
    /** 总话数(eps,Bangumi 无"已播话数"概念) */
    val totalEpisodes: Int?,
    /** 已放送话数(当季由 /v0/episodes 按 airdate 计算,每日刷新;历史季度为 null) */
    val airedEpisodes: Int?,
) {
    /** 分集信息文案:有进度显示 "6/12集",仅总话数显示 "12集" */
    val episodeText: String?
        get() {
            val total = totalEpisodes ?: return null
            val aired = airedEpisodes
            return if (aired != null && aired > 0 && aired < total) "${aired}/${total}集" else "${total}集"
        }

    /** 用于 B站搜索的名称:优先中文译名,无则用原始标题 */
    val searchKeyword: String
        get() = nameCn.trim().takeIf { it.isNotEmpty() } ?: name.trim()
}
