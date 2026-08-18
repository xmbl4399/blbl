package blbl.cat3399.core.api

import android.content.Context
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.BangumiCalendarDay
import blbl.cat3399.core.model.BangumiCalendarItem
import blbl.cat3399.core.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Bangumi (bgm.tv) 数据源:季度番剧列表(统一当年/历史季度,不再按星期分组)。
 *
 * - 端点:GET /v0/subjects?type=2&year=&month=&limit=100&sort=rank
 *   —— 与网页 bangumi.tv/anime/browser/airtime/yyyy-m 同语义:返回该月开播的新番
 *   (季度首月 1/4/7/10;2025-4=25春、2025-7=25夏),实测单月 68-80 条
 *   —— 响应为 {data:[...]} 包裹结构,无星期字段
 * - browse 自带 tags(白名单过滤)与 eps 总话数,无需二次合并
 * - 每季度 12h 文件缓存(按 year_month 命名)
 *
 * 无需鉴权(匿名访问),B站番剧时间表 API 留作备源。
 */
object BangumiApi {
    private const val TAG = "BangumiApi"
    private const val BASE = "https://api.bgm.tv"
    private const val USER_AGENT = "blbl/0.1 (https://github.com/xmbl4399/blbl; bangumi calendar)"
    private const val QUARTER_CACHE_AGE_MS = 12 * 60 * 60 * 1000L
    private const val QUARTER_CACHE_AGE_MS_HISTORY = 30L * 24 * 60 * 60 * 1000L // 历史季度 30 天
    private const val PROGRESS_CACHE_AGE_MS = 24 * 60 * 60 * 1000L // 当季进度每日刷新

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

    /** 季度规格:year + 季度首月(1/4/7/10),对应网页 airtime/yyyy-m */
    data class SeasonSpec(
        val year: Int,
        val month: Int, // 1/4/7/10
    ) {
        /** 紧凑标签,如 "25夏" */
        val label: String
            get() = "${year % 100}${seasonCn}"

        val seasonCn: String
            get() =
                when (month) {
                    1 -> "冬"
                    4 -> "春"
                    7 -> "夏"
                    else -> "秋"
                }

        companion object {
            /** 当前公历日所在的放送季(1月=冬,4月=春,7月=夏,10月=秋) */
            fun current(): SeasonSpec {
                val now = Calendar.getInstance()
                val year = now.get(Calendar.YEAR)
                val m = now.get(Calendar.MONTH) + 1 // 1-based
                val quarterMonth =
                    when (m) {
                        in 1..3 -> 1
                        in 4..6 -> 4
                        in 7..9 -> 7
                        else -> 10
                    }
                return SeasonSpec(year, quarterMonth)
            }

            /** 从当前季往前推 count 个季度,最新在前(如 [26夏, 26春, 25冬, ...]) */
            fun recentQuarters(count: Int): List<SeasonSpec> {
                val cur = current()
                val out = ArrayList<SeasonSpec>(count)
                var y = cur.year
                var m = cur.month
                for (i in 0 until count) {
                    out += SeasonSpec(y, m)
                    m =
                        when (m) {
                            1 -> 10
                            4 -> 1
                            7 -> 4
                            else -> 7 // 10 -> 7
                        }
                    if (m == 10) y--
                }
                return out
            }

            /** 从 [startYear] 冬 到当前季的季度总数(用于生成"全部季度"列表) */
            fun quarterCountSince(startYear: Int): Int {
                val cur = current()
                val idx = (cur.year - startYear) * 4 + (cur.month - 1) / 3
                return (idx + 1).coerceAtLeast(1)
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
     * 季度番剧:分页拉完整个季度(首月 + 次月,当月开播 + 次月补档),按 total 翻页去重。
     * 缓存:当季 12h(每周看一次),历史季度 30 天(放送进度固定不变)。
     */
    suspend fun browseQuarter(year: Int, quarterMonth: Int): List<BangumiCalendarItem> {
        // v4:cat=1 只拉 TV 番剧(排除剧场版/WEB/OVA);只拉季度首月(1/4/7/10)
        val cacheName = "browse_${year}_${quarterMonth}_v4.json"
        val isCurrent = SeasonSpec.current().let { it.year == year && it.month == quarterMonth }
        val cacheAge = if (isCurrent) QUARTER_CACHE_AGE_MS else QUARTER_CACHE_AGE_MS_HISTORY
        val cachedRaw = readCache(cacheName)
        if (cachedRaw != null && cacheAgeMs(cacheName) < cacheAge) {
            AppLog.i(TAG, "browseQuarter $year-$quarterMonth served from cache")
            return runCatching { withContext(Dispatchers.Default) { parseItems(JSONArray(cachedRaw!!)) } }
                .getOrElse { fetchQuarter(year, quarterMonth, cacheName, fallbackRaw = cachedRaw) }
        }
        return fetchQuarter(year, quarterMonth, cacheName, fallbackRaw = cachedRaw)
    }

    private suspend fun fetchQuarter(
        year: Int,
        quarterMonth: Int,
        cacheName: String,
        fallbackRaw: String?,
    ): List<BangumiCalendarItem> {
        return try {
            // 缓存原始 items JSON(保留 images/rating/tags 原始结构,与在线解析一致),
            // 这样封面/评分/tag 从缓存解析与在线结果相同
            val rawItems = JSONArray()
            val seen = HashSet<Long>()
            // 只拉季度首月(1/4/7/10);cat=1 仅 TV 番剧,过滤剧场版/WEB/OVA
            // (实测 2026-07 68 -> 60 条;不再合并次月,避免剧场版混入)
            var offset = 0
            while (true) {
                val root = JSONObject(fetch("$BASE/v0/subjects?type=2&cat=1&year=$year&month=$quarterMonth&limit=100&sort=rank&offset=$offset"))
                val data = root.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val obj = data.getJSONObject(i)
                    val id = obj.optLong("id", 0L)
                    if (seen.add(id)) rawItems.put(obj)
                }
                if (data.length() < 100) break
                offset += 100
                if (offset > 500) break // 防死循环
            }
            writeCache(cacheName, rawItems.toString())
            withContext(Dispatchers.Default) { parseItems(rawItems) }
        } catch (t: Throwable) {
            // 网络失败:降级用旧缓存(即使已过期)
            if (fallbackRaw != null) {
                AppLog.w(TAG, "browseQuarter $year-$quarterMonth fetch failed, fallback to stale cache", t)
                return withContext(Dispatchers.Default) { parseItems(JSONArray(fallbackRaw)) }
            }
            throw t
        }
    }

    /**
     * 当季剧集进度:对当季每部番 GET /v0/episodes?subject_id=&type=0,
     * 统计 airdate <= 今天的话数(已放送话数)。每日缓存刷新。
     * 历史季度无需(放送结束,进度固定)。
     */
    suspend fun currentSeasonProgress(): Map<Long, Int> {
        val spec = SeasonSpec.current()
        // 缓存按季度键隔离(换季后不会用到旧季度进度)
        val cacheName = "progress_${spec.year}_${spec.month}.json"
        val cached = readCache(cacheName)
        if (cached != null && cacheAgeMs(cacheName) < PROGRESS_CACHE_AGE_MS) {
            AppLog.i(TAG, "progress served from cache")
            return parseProgressMap(cached)
        }
        val ids = runCatching { browseQuarter(spec.year, spec.month).map { it.id } }.getOrDefault(emptyList())
        AppLog.i(TAG, "fetch progress for ${ids.size} subjects (current quarter)")
        val map = HashMap<Long, Int>(ids.size)
        // 分批并发,避免瞬时打满接口
        coroutineScope {
            for (chunk in ids.chunked(6)) {
                val results = chunk.map { id -> async { id to runCatching { episodeAiredCount(id) }.getOrDefault(0) } }
                for (d in results) {
                    val (id, n) = d.await()
                    if (n > 0) map[id] = n
                }
            }
        }
        writeCache(cacheName, serializeProgressMap(map))
        return map
    }

    /** 单部番剧已放送话数:airdate <= 今天 */
    private suspend fun episodeAiredCount(subjectId: Long): Int {
        val raw = fetch("$BASE/v0/episodes?subject_id=$subjectId&type=0&limit=100")
        return withContext(Dispatchers.Default) {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("data") ?: JSONArray()
            val today = java.time.LocalDate.now()
            var count = 0
            for (i in 0 until arr.length()) {
                val air = arr.getJSONObject(i).optString("airdate").orEmpty()
                if (air.isNotBlank()) {
                    runCatching { if (java.time.LocalDate.parse(air) <= today) count++ }
                }
            }
            count
        }
    }

    private fun serializeProgressMap(map: Map<Long, Int>): String {
        val root = JSONObject()
        for ((id, n) in map) root.put(id.toString(), n)
        return root.toString()
    }

    private fun parseProgressMap(raw: String): Map<Long, Int> {
        return runCatching {
            val root = JSONObject(raw)
            val map = HashMap<Long, Int>(root.length())
            for (key in root.keys()) {
                key.toLongOrNull()?.let { id -> map[id] = root.optInt(key, 0) }
            }
            map
        }.getOrDefault(emptyMap())
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

    private fun parseItems(arr: JSONArray): List<BangumiCalendarItem> {
        val items = ArrayList<BangumiCalendarItem>(arr.length())
        for (i in 0 until arr.length()) {
            items += parseItem(arr.getJSONObject(i))
        }
        return items
    }

    private fun parseItem(it: JSONObject): BangumiCalendarItem {
        val images = it.optJSONObject("images")
        // 封面优先 common(400px,约 100-200KB);medium 是 800px 原图(300-500KB)加载慢
        val cover =
            images?.optString("common").orEmpty().takeIf { c -> c.isNotBlank() }
                ?: images?.optString("medium").orEmpty().takeIf { c -> c.isNotBlank() }
                ?: images?.optString("large").orEmpty().takeIf { c -> c.isNotBlank() }
        val rating = it.optJSONObject("rating")
        val scoreRaw = rating?.optDouble("score", Double.NaN)
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
            totalEpisodes = it.optInt("eps", 0).takeIf { e -> e > 0 },
            airedEpisodes = it.optInt("aired", 0).takeIf { a -> a > 0 }, // 缓存序列化用
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
