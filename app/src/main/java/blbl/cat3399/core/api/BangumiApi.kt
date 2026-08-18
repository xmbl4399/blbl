package blbl.cat3399.core.api

import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.BangumiCalendarDay
import blbl.cat3399.core.model.BangumiCalendarItem
import blbl.cat3399.core.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Bangumi (bgm.tv) 数据源:星期视图时间表(业界标准,Animeko/AnimeFun 同源)。
 *
 * 端点:GET https://api.bgm.tv/calendar(注意无 v0 前缀)
 * - 无需鉴权,仅需合法 User-Agent
 * - 返回 7 个按星期分组的数组,每项含 weekday{id,cn} + items[]
 * - 随季度自动更新,反映当季放送番剧
 *
 * 备用源:B站番剧时间表 API(pgc schedule),留作后续扩展。
 */
object BangumiApi {
    private const val TAG = "BangumiApi"
    private const val BASE = "https://api.bgm.tv"
    private const val USER_AGENT = "blbl/0.1 (https://github.com/cat3399/blbl; bangumi calendar)"

    // Bangumi 与 B站风控体系无关,使用独立客户端(不带 B站 UA/Referer/Origin 拦截器)。
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 星期视图:GET /calendar,解析为按周一~周日分组的列表(过滤空天)。 */
    suspend fun calendar(): List<BangumiCalendarDay> {
        val raw =
            try {
                val req =
                    Request.Builder()
                        .url("$BASE/calendar")
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .build()
                client.newCall(req).await().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} ${r.message}")
                    r.body?.string().orEmpty()
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "calendar request failed", t)
                throw t
            }
        return withContext(Dispatchers.Default) { parseCalendar(raw) }
    }

    private fun parseCalendar(raw: String): List<BangumiCalendarDay> {
        val arr = JSONArray(raw)
        val days = ArrayList<BangumiCalendarDay>(arr.length())
        for (i in 0 until arr.length()) {
            val day = arr.getJSONObject(i)
            val weekday = day.optJSONObject("weekday")
            val weekdayId = weekday?.optInt("id", 0) ?: 0
            val weekdayCn = weekday?.optString("cn").orEmpty().ifBlank { "周$weekdayId" }
            val itemsArr = day.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<BangumiCalendarItem>(itemsArr.length())
            for (j in 0 until itemsArr.length()) {
                val it = itemsArr.getJSONObject(j)
                items += parseItem(it)
            }
            if (items.isNotEmpty()) {
                days += BangumiCalendarDay(weekdayId = weekdayId, weekdayCn = weekdayCn, items = items)
            }
        }
        return days
    }

    private fun parseItem(it: JSONObject): BangumiCalendarItem {
        val images = it.optJSONObject("images")
        val cover =
            images?.optString("medium").orEmpty().takeIf { it.isNotBlank() }
                ?: images?.optString("large").orEmpty().takeIf { it.isNotBlank() }
        val rating = it.optJSONObject("rating")
        val scoreRaw = rating?.optDouble("score", Double.NaN)
        return BangumiCalendarItem(
            id = it.optLong("id", 0L),
            name = it.optString("name").orEmpty(),
            nameCn = it.optString("name_cn").orEmpty(),
            coverUrl = cover,
            airDate = it.optString("air_date").orEmpty().takeIf { d -> d.isNotBlank() },
            score = scoreRaw?.takeIf { s -> !s.isNaN() && s > 0.0 },
            rank = it.optInt("rank", -1).takeIf { r -> r > 0 },
            summary = it.optString("summary").orEmpty().takeIf { s -> s.isNotBlank() },
        )
    }
}
