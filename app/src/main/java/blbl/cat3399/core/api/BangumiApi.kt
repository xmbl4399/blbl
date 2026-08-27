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
     * 指定年份 + 分类的条目(全年拉取,按热度排序)。
     * 用于"剧场动画(type=2 cat=3)/日剧(type=6 cat=1)/电影(type=6 cat=6002)"年份页面。
     *
     * 注意:API 的 year 单参数语义不可靠(实测日剧 2026 仅回 7 部,而逐月求和 305 部),
     * 因此采用**并集策略**:year 单参数 ∪ 12 个月逐月(year+month,当月开播语义已验证可靠),
     * 按 id 去重,保证当年条目完整。
     * 缓存:当年 12h,历史年份 30 天。
     */
    suspend fun browseYear(type: Int, cat: Int, year: Int): List<BangumiCalendarItem> {
        // v2:并集策略(year 单参数 + 逐月)修复数量不全,缓存键加后缀强制重建
        val cacheName = "browse_${type}_${cat}_${year}_v2.json"
        val isCurrentYear = Calendar.getInstance().get(Calendar.YEAR) == year
        val cacheAge = if (isCurrentYear) QUARTER_CACHE_AGE_MS else QUARTER_CACHE_AGE_MS_HISTORY
        val cachedRaw = readCache(cacheName)
        if (cachedRaw != null && cacheAgeMs(cacheName) < cacheAge) {
            AppLog.i(TAG, "browseYear type=$type cat=$cat $year served from cache")
            return runCatching { withContext(Dispatchers.Default) { parseItems(JSONArray(cachedRaw!!)) } }
                .getOrElse { fetchYear(type, cat, year, cacheName, fallbackRaw = cachedRaw) }
        }
        return fetchYear(type, cat, year, cacheName, fallbackRaw = cachedRaw)
    }

    private suspend fun fetchYear(
        type: Int,
        cat: Int,
        year: Int,
        cacheName: String,
        fallbackRaw: String?,
    ): List<BangumiCalendarItem> {
        return try {
            val rawItems = JSONArray()
            val seen = HashSet<Long>()

            // 1) year 单参数(电影等分类可返回全年全集)
            var offset = 0
            while (true) {
                val url = "$BASE/v0/subjects?type=$type&cat=$cat&year=$year&limit=100&sort=rank&offset=$offset"
                val root = JSONObject(fetch(url))
                val data = root.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val obj = data.getJSONObject(i)
                    val id = obj.optLong("id", 0L)
                    if (seen.add(id)) rawItems.put(obj)
                }
                if (data.length() < 100) break
                offset += 100
                if (offset > 1000) break
            }
            // 2) 逐月 1-12 月(year+month 语义可靠,日剧等分类的完整数据来源)
            // ⚠️ 不能带 sort=rank:type=6(三次元)下 rank+month 组合有 API bug(实测只回 1 条),
            //    动画 type=2 不受影响;这里用默认 date 排序拿全数据,最后统一按 rank 重排
            for (month in 1..12) {
                var mOffset = 0
                while (true) {
                    val url = "$BASE/v0/subjects?type=$type&cat=$cat&year=$year&month=$month&limit=100&offset=$mOffset"
                    val root = JSONObject(fetch(url))
                    val data = root.optJSONArray("data") ?: JSONArray()
                    for (i in 0 until data.length()) {
                        val obj = data.getJSONObject(i)
                        val id = obj.optLong("id", 0L)
                        if (seen.add(id)) rawItems.put(obj)
                    }
                    if (data.length() < 100) break
                    mOffset += 100
                    if (mOffset > 1000) break
                }
            }
            // 合并后按热度(rank)排序:rank 升序在前,无排名(rank=-1)排最后
            val sorted = JSONArray().apply {
                (0 until rawItems.length())
                    .map { rawItems.getJSONObject(it) }
                    .sortedBy { obj -> obj.optInt("rank", Int.MAX_VALUE) }
                    .forEach { put(it) }
            }
            writeCache(cacheName, sorted.toString())
            withContext(Dispatchers.Default) { parseItems(sorted) }
        } catch (t: Throwable) {
            if (fallbackRaw != null) {
                AppLog.w(TAG, "browseYear type=$type cat=$cat $year fetch failed, fallback to stale cache", t)
                return withContext(Dispatchers.Default) { parseItems(JSONArray(fallbackRaw)) }
            }
            throw t
        }
    }

    /**
     * 当季剧集进度:对当季每部番 GET /v0/episodes?subject_id=&type=0,
     * 统计 airdate <= 今天的话数(已放送话数)。每日缓存刷新。
     * 历史季度无需(放送结束,进度固定)。
     *
     * 拆分两个方法支持"先显缓存、后台刷新"策略:
     * - [cachedSeasonProgress]:同步读缓存,不过期检查,立即返回(无缓存返回空 map)
     * - [refreshSeasonProgress]:强制网络拉取 + 写缓存(suspend,约 10s)
     */
    fun cachedSeasonProgress(): Map<Long, Int> {
        val spec = SeasonSpec.current()
        return cachedProgressFor("${spec.year}_${spec.month}")
    }

    suspend fun refreshSeasonProgress(): Map<Long, Int> {
        val spec = SeasonSpec.current()
        val ids = runCatching { browseQuarter(spec.year, spec.month).map { it.id } }.getOrDefault(emptyList())
        return refreshProgressFor("${spec.year}_${spec.month}", ids)
    }

    /** 日剧年份页进度(仅当年有效):已放送话数,缓存键按年隔离 */
    fun cachedDramaProgress(year: Int): Map<Long, Int> = cachedProgressFor("drama_$year")

    suspend fun refreshDramaProgress(year: Int): Map<Long, Int> {
        val ids = runCatching { browseYear(6, 1, year).map { it.id } }.getOrDefault(emptyList())
        return refreshProgressFor("drama_$year", ids)
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
