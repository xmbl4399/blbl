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
    /** 总话数(eps,Bangumi 无"已播话数"概念) */
    val totalEpisodes: Int?,
) {
    /** 用于 B站搜索的名称:优先中文译名,无则用原始标题 */
    val searchKeyword: String
        get() = nameCn.trim().takeIf { it.isNotEmpty() } ?: name.trim()
}
