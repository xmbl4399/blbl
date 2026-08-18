package blbl.cat3399.core.api

import android.content.Context
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
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Bangumi (bgm.tv) 数据源:星期视图时间表 + 历史季度浏览(业界标准)。
 *
 * - 当季:GET /calendar —— 按周一~周日分组,随季度自动更新(12h 文件缓存)
 * - 历史季度:GET /v0/subjects?type=2&year=&season=&limit=100&sort=rank —— 按热度列表
 *   (注意:browse 返回 {data:[...]} 包裹结构,且无星期字段,只能列表展示)
 *
 * 无需鉴权,仅需合法 User-Agent。备用源:B站番剧时间表 API(pgc schedule),留作后续扩展。
 */
object BangumiApi {
    private const val TAG = "BangumiApi"
    private const val BASE = "https://api.bgm.tv"
    private const val USER_AGENT = "blbl/0.1 (https://github.com/cat3399/blbl; bangumi calendar)"
    private const val CALENDAR_CACHE_AGE_MS = 12 * 60 * 60 * 1000L
    private const val CALENDAR_CACHE_FILE = "calendar.json"
    private const val TAGS_CACHE_FILE = "browse_tags.json"

    private lateinit var cacheDir: File

    /** 常见番剧分类白名单:命中即作为卡片流派标签(取前 2 个) */
    private val TAG_WHITELIST =
        setOf(
            "奇幻", "冒险", "战斗", "校园", "日常", "科幻", "恋爱", "喜剧", "热血", "悬疑",
            "推理", "机战", "运动", "音乐", "美食", "治愈", "恐怖", "历史", "偶像", "百合",
            "耽美", "竞技", "动作", "剧情", "搞笑", "催泪", "致郁", "魔法", "机甲", "战争",
            "体育", "侦探", "后宫", "穿越", "异世界", "职场", "青春", "家庭", "童话", "歌舞",
            "泡面番", "群像", "智斗", "犯罪", "末世", "科幻悬疑", "恋爱喜剧",
        )

    data class SeasonSpec(
        val year: Int,
        val season: String, // winter/spring/summer/autumn
    ) {
        val label: String
            get() = "${year}${seasonLabel(season)}"

        companion object {
            fun seasonLabel(season: String): String =
                when (season) {
                    "winter" -> "冬"
                    "spring" -> "春"
                    "summer" -> "夏"
                    "autumn" -> "秋"
                    else -> season
                }

            /** 当前公历日所在的放送季(1月=冬,4月=春,7月=夏,10月=秋) */
            fun current(): SeasonSpec {
                val now = Calendar.getInstance()
                val year = now.get(Calendar.YEAR)
                val month = now.get(Calendar.MONTH) // 0-based
                val season =
                    when (month) {
                        0, 1, 2 -> "winter"
                        3, 4, 5 -> "spring"
                        6, 7, 8 -> "summer"
                        else -> "autumn"
                    }
                return SeasonSpec(year, season)
            }
        }
    }

    // Bangumi 与 B站风控体系无关,使用独立客户端(不带 B站 UA/Referer/Origin 拦截器)。
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, "bangumi").apply { mkdirs() }
    }

    /**
     * 星期视图:GET /calendar。
     * 优先返回未过期(12h)缓存;网络失败时降级用旧缓存。
     */
    suspend fun calendar(): List<BangumiCalendarDay> {
        val cachedRaw = readCache(CALENDAR_CACHE_FILE)
        val cacheFresh = cachedRaw != null && cacheAgeMs(CALENDAR_CACHE_FILE) < CALENDAR_CACHE_AGE_MS
        if (cacheFresh) {
            AppLog.i(TAG, "calendar served from cache")
            return runCatching { withContext(Dispatchers.Default) { parseCalendar(cachedRaw!!) } }
                .getOrElse { fetchCalendarAndCache() }
        }
        return fetchCalendarAndCache()
    }

    /**
     * 星期视图(带流派标签):calendar(12h 缓存) + 当季 browse 按 id 合并 tags。
     * calendar 接口本身不含 tags;browse 分页拉全当季番剧,按热度覆盖约 6 成卡片。
     * 合并失败时降级为无标签(不影响星期视图)。
     */
    suspend fun calendarWithTags(): List<BangumiCalendarDay> {
        val days = calendar()
        val tagMap = runCatching { browseTagsForCurrentSeason() }.getOrDefault(emptyMap())
        if (tagMap.isEmpty()) return days
        return days.map { day ->
            day.copy(
                items = day.items.map { item ->
                    val tags = tagMap[item.id]
                    if (tags.isNullOrEmpty()) item else item.copy(tags = tags)
                },
            )
        }
    }

    /** 历史季度:GET /v0/subjects,按 rank 排序的列表(无星期分组,自带 tags)。 */
    suspend fun browse(year: Int, season: String, offset: Int = 0): List<BangumiCalendarItem> {
        val raw =
            fetch("$BASE/v0/subjects?type=2&year=$year&season=$season&limit=100&sort=rank&offset=$offset")
        return withContext(Dispatchers.Default) { parseBrowse(raw) }
    }

    /** 当季 browse 分页拉全(约 3 页),收集 id -> 白名单 tags,12h 缓存 */
    private suspend fun browseTagsForCurrentSeason(): Map<Long, List<String>> {
        val cached = readCache(TAGS_CACHE_FILE)
        if (cached != null && cacheAgeMs(TAGS_CACHE_FILE) < CALENDAR_CACHE_AGE_MS) {
            return parseTagMap(cached)
        }
        val spec = SeasonSpec.current()
        val map = HashMap<Long, List<String>>(320)
        for (offset in 0..200 step 100) {
            try {
                browse(spec.year, spec.season, offset = offset).forEach { map[it.id] = it.tags }
            } catch (t: Throwable) {
                AppLog.w(TAG, "browse tags page offset=$offset failed", t)
                break
            }
        }
        writeCache(TAGS_CACHE_FILE, serializeTagMap(map))
        return map
    }

    private fun serializeTagMap(map: Map<Long, List<String>>): String {
        val root = JSONObject()
        for ((id, tags) in map) root.put(id.toString(), JSONArray(tags))
        return root.toString()
    }

    private fun parseTagMap(raw: String): Map<Long, List<String>> {
        return runCatching {
            val root = JSONObject(raw)
            val map = HashMap<Long, List<String>>(root.length())
            for (key in root.keys()) {
                val arr = root.optJSONArray(key) ?: continue
                val tags = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) tags += arr.optString(i).orEmpty()
                key.toLongOrNull()?.let { map[it] = tags }
            }
            map
        }.getOrDefault(emptyMap())
    }

    private suspend fun fetchCalendarAndCache(): List<BangumiCalendarDay> {
        val cachedRaw = readCache(CALENDAR_CACHE_FILE)
        return try {
            val raw = fetch("$BASE/calendar")
            writeCache(CALENDAR_CACHE_FILE, raw)
            withContext(Dispatchers.Default) { parseCalendar(raw) }
        } catch (t: Throwable) {
            // 网络失败:降级用旧缓存(即使已过期)
            if (cachedRaw != null) {
                AppLog.w(TAG, "calendar fetch failed, fallback to stale cache", t)
                return withContext(Dispatchers.Default) { parseCalendar(cachedRaw) }
            }
            throw t
        }
    }

    private suspend fun fetch(url: String): String {
        val req =
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
        return client.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} ${r.message}")
            r.body?.string().orEmpty()
        }
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
                items += parseItem(itemsArr.getJSONObject(j))
            }
            if (items.isNotEmpty()) {
                days += BangumiCalendarDay(weekdayId = weekdayId, weekdayCn = weekdayCn, items = items)
            }
        }
        return days
    }

    private fun parseBrowse(raw: String): List<BangumiCalendarItem> {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("data") ?: JSONArray()
        val items = ArrayList<BangumiCalendarItem>(arr.length())
        for (i in 0 until arr.length()) {
            items += parseItem(arr.getJSONObject(i))
        }
        return items
    }

    private fun parseItem(it: JSONObject): BangumiCalendarItem {
        val images = it.optJSONObject("images")
        val cover =
            images?.optString("medium").orEmpty().takeIf { c -> c.isNotBlank() }
                ?: images?.optString("large").orEmpty().takeIf { c -> c.isNotBlank() }
        val rating = it.optJSONObject("rating")
        val scoreRaw = rating?.optDouble("score", Double.NaN)
        // calendar 用 air_date;browse 用 date
        val airDate =
            it.optString("air_date").orEmpty().takeIf { d -> d.isNotBlank() }
                ?: it.optString("date").orEmpty().takeIf { d -> d.isNotBlank() }
        return BangumiCalendarItem(
            id = it.optLong("id", 0L),
            name = it.optString("name").orEmpty(),
            nameCn = it.optString("name_cn").orEmpty(),
            coverUrl = cover,
            airDate = airDate,
            score = scoreRaw?.takeIf { s -> !s.isNaN() && s > 0.0 },
            rank = it.optInt("rank", -1).takeIf { r -> r > 0 },
            summary = it.optString("summary").orEmpty().takeIf { s -> s.isNotBlank() },
            tags = parseTags(it.optJSONArray("tags")),
        )
    }

    /** 从 tags 数组按白名单过滤,取前 2 个(保持 tags 原热度顺序) */
    private fun parseTags(tags: JSONArray?): List<String> {
        if (tags == null) return emptyList()
        val out = ArrayList<String>(2)
        for (i in 0 until tags.length()) {
            val name = tags.optJSONObject(i)?.optString("name").orEmpty().trim()
            if (name in TAG_WHITELIST) out += name
            if (out.size >= 2) break
        }
        return out
    }

    private fun readCache(name: String): String? {
        if (!::cacheDir.isInitialized) return null
        val f = File(cacheDir, name)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()
    }

    private fun writeCache(name: String, raw: String) {
        if (!::cacheDir.isInitialized) return
        runCatching { File(cacheDir, name).writeText(raw) }
            .onFailure { AppLog.w(TAG, "write cache failed $name", it) }
    }

    private fun cacheAgeMs(name: String): Long {
        if (!::cacheDir.isInitialized) return Long.MAX_VALUE
        val f = File(cacheDir, name)
        return if (f.exists()) System.currentTimeMillis() - f.lastModified() else Long.MAX_VALUE
    }
}
