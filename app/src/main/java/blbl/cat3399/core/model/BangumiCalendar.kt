package blbl.cat3399.core.model

/**
 * Bangumi (bgm.tv) 星期视图时间表数据模型。
 * 数据源:GET https://api.bgm.tv/calendar,按周一~周日分组返回当季放送番剧。
 */
data class BangumiCalendarDay(
    /** 星期编号 1=周一 ... 7=周日 */
    val weekdayId: Int,
    /** 中文星期名(如"周一") */
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
) {
    /** 用于 B站搜索的名称:优先中文译名,无则用原始标题 */
    val searchKeyword: String
        get() = nameCn.trim().takeIf { it.isNotEmpty() } ?: name.trim()
}
