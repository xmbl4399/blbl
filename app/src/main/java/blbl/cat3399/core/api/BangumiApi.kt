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

    private lateinit var cacheDir: File

    /**
     * 流派白名单(命中即作为卡片左上角标签,取前 2 个)。
     * 仅动画向(日剧/电影不显示 tag,见 BangumiCalendarAdapter.showTags)。
     */
    private val TAG_WHITELIST =
        setOf(
            "奇幻", "冒险", "战斗", "校园", "日常", "科幻", "恋爱", "喜剧", "热血", "悬疑",
            "推理", "机战", "运动", "音乐", "美食", "治愈", "恐怖", "历史", "偶像", "百合",
            "耽美", "竞技", "动作", "剧情", "搞笑", "催泪", "致郁", "魔法", "机甲", "战争",
            "体育", "侦探", "后宫", "穿越", "异世界", "职场", "青春", "家庭", "童话", "歌舞",
            "泡面番", "群像", "智斗", "犯罪", "末世", "科幻悬疑", "恋爱喜剧",
        )

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
     * 纯缓存读:指定年月的条目(不触发网络,无缓存或已过期也返回缓存值,由调用方决定是否刷新)。
     * 用于年份页面流式加载第一步"先显缓存月"(秒出)。
     */
    fun cachedYearMonth(type: Int, cat: Int, year: Int, month: Int): List<BangumiCalendarItem>? {
        val cacheName = "browse_${type}_${cat}_${year}_${month}.json"
        val cached = readCache(cacheName) ?: return null
        // 单月缓存 JSON 小(几十 KB),同步解析可接受
        return runCatching { parseItems(JSONArray(cached)) }.getOrNull()
    }

    /**
     * 指定年份 + 月份 + 分类的条目(当月开播,单月独立缓存)。
     * 用于 TV动画/剧场动画(type=2)与日剧/电影(type=6)的**年份流式按月加载**。
     *
     * - type=2(动画):带 sort=rank(热度排序,rank+month 无 bug,实测正常)
     * - type=6(三次元):**不能带 sort=rank**(rank+month 组合有 API bug,实测只回 1 条),
     *   用默认 date 排序;两种模式最终统一按 rank 重排,缓存内容一致
     * 缓存:12h。
     */
    suspend fun browseYearMonth(type: Int, cat: Int, year: Int, month: Int): List<BangumiCalendarItem> {
        val cacheName = "browse_${type}_${cat}_${year}_${month}.json"
        val cachedRaw = readCache(cacheName)
        if (cachedRaw != null && cacheAgeMs(cacheName) < QUARTER_CACHE_AGE_MS) {
            AppLog.i(TAG, "browseYearMonth type=$type cat=$cat $year-$month served from cache")
            return runCatching { withContext(Dispatchers.Default) { parseItems(JSONArray(cachedRaw!!)) } }
                .getOrElse { fetchYearMonth(type, cat, year, month, cacheName, fallbackRaw = cachedRaw) }
        }
        return fetchYearMonth(type, cat, year, month, cacheName, fallbackRaw = cachedRaw)
    }

    private suspend fun fetchYearMonth(
        type: Int,
        cat: Int,
        year: Int,
        month: Int,
        cacheName: String,
        fallbackRaw: String?,
    ): List<BangumiCalendarItem> {
        return try {
            val rawItems = JSONArray()
            val seen = HashSet<Long>()
            // type=2 动画可用 rank 排序;type=6 三次元 rank+month 有 bug(只回 1 条),不带 sort
            val sort = if (type == 2) "&sort=rank" else ""
            var offset = 0
            while (true) {
                val url = "$BASE/v0/subjects?type=$type&cat=$cat&year=$year&month=$month&limit=100$sort&offset=$offset"
                val root = JSONObject(fetch(url))
                val data = root.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val obj = data.getJSONObject(i)
                    val id = obj.optLong("id", 0L)
                    if (seen.add(id)) rawItems.put(obj)
                }
                if (data.length() < 100) break
                offset += 100
                if (offset > 500) break
            }
            // 排序:
            // - type=2(动画):按 rank(热度排名,77% 条目有排名,语义正确)
            // - type=6(三次元):rank 缺失率高(日剧仅 4% 有排名)→ 改按评分排序,
            //   评分人数 >=10 的按 score 降序(避免 1 人评分噪音),其余(无评分/人数少)按日期垫底
            val sorted = JSONArray().apply {
                val list = (0 until rawItems.length()).map { rawItems.getJSONObject(it) }
                val ordered =
                    if (type == 2) {
                        list.sortedBy { obj -> obj.optInt("rank", Int.MAX_VALUE) }
                    } else {
                        list.sortedWith(
                            compareByDescending<JSONObject> { obj ->
                                val rating = obj.optJSONObject("rating")
                                val total = rating?.optInt("total", 0) ?: 0
                                val score = rating?.optDouble("score", 0.0) ?: 0.0
                                if (total >= 10 && score > 0) score else -1.0
                            }.thenBy { obj -> obj.optString("date").orEmpty() },
                        )
                    }
                ordered.forEach { put(it) }
            }
            writeCache(cacheName, sorted.toString())
            withContext(Dispatchers.Default) { parseItems(sorted) }
        } catch (t: Throwable) {
            if (fallbackRaw != null) {
                AppLog.w(TAG, "browseYearMonth type=$type cat=$cat $year-$month fetch failed, fallback to stale cache", t)
                return withContext(Dispatchers.Default) { parseItems(JSONArray(fallbackRaw)) }
            }
            throw t
        }
    }

    /**
     * TV动画年份页进度(仅当年有效):已放送话数,缓存键按年隔离。
     * 先显缓存、后台刷新策略:
     * - [cachedTvProgress]:同步读缓存,不过期检查,立即返回(无缓存返回空 map)
     * - [refreshTvProgress]:强制网络拉取 + 写缓存(suspend,约 10s+)
     */
    fun cachedTvProgress(year: Int): Map<Long, Int> = cachedProgressFor("tv_$year")

    suspend fun refreshTvProgress(year: Int): Map<Long, Int> {
        // 逐月合并 ids(与年份页数据源一致)
        val ids = HashSet<Long>()
        for (m in 1..12) {
            runCatching { browseYearMonth(2, 1, year, m) }.getOrNull()?.forEach { ids.add(it.id) }
        }
        return refreshProgressFor("tv_$year", ids.toList())
    }

    /** 日剧年份页进度(仅当年有效):已放送话数,缓存键按年隔离 */
    fun cachedDramaProgress(year: Int): Map<Long, Int> = cachedProgressFor("drama_$year")

    suspend fun refreshDramaProgress(year: Int): Map<Long, Int> {
        // 逐月合并 ids(与年份页数据源一致)
        val ids = HashSet<Long>()
        for (m in 1..12) {
            runCatching { browseYearMonth(6, 1, year, m) }.getOrNull()?.forEach { ids.add(it.id) }
        }
        return refreshProgressFor("drama_$year", ids.toList())
    }

    private fun cachedProgressFor(keyPrefix: String): Map<Long, Int> {
        val cacheName = "progress_$keyPrefix.json"
        val cached = readCache(cacheName) ?: return emptyMap()
        return parseProgressMap(cached)
    }

    private suspend fun refreshProgressFor(keyPrefix: String, ids: List<Long>): Map<Long, Int> {
        val cacheName = "progress_$keyPrefix.json"
        AppLog.i(TAG, "refresh progress $keyPrefix for ${ids.size} subjects")
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
            metaTags = parseStringArray(it.optJSONArray("meta_tags")),
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

    /** 解析字符串数组(meta_tags 等原始文本数组) */
    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).orEmpty().trim()
            if (s.isNotEmpty()) out += s
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
