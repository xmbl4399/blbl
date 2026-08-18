package blbl.cat3399.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import androidx.collection.LruCache
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.await
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

object ImageLoader {
    private const val TAG = "ImageLoader"
    private val placeholder = ColorDrawable(0xFF2A2A2A.toInt())
    private val inFlight = WeakHashMap<ImageView, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private lateinit var diskCacheDir: File

    // 非 B站 CDN(如 lain.bgm.tv)走独立客户端,不带 B站 UA/Referer/Origin(避免 CDN 策略干扰)
    private val plainOkHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        diskCacheDir = File(context.cacheDir, "images").apply { mkdirs() }
    }

    fun loadInto(view: ImageView, url: String?) {
        val normalized = normalizeImageUrl(url)

        if (normalized == null) {
            view.setTag(R.id.tag_image_loader_url, null)
            inFlight.remove(view)?.cancel()
            if (view.drawable !== placeholder) view.setImageDrawable(placeholder)
            return
        }

        val lastUrl = view.getTag(R.id.tag_image_loader_url) as? String
        if (lastUrl == normalized) {
            // If we already have a non-placeholder image for the same URL, keep it to prevent
            // flicker on rebind (e.g. switching tabs triggers notifyItemRangeChanged).
            val drawable = view.drawable
            if (drawable != null && drawable !== placeholder) {
                inFlight.remove(view)?.cancel()
                return
            }
            // If the same URL is already loading, keep the current placeholder.
            val inFlightJob = inFlight[view]
            if (inFlightJob != null && inFlightJob.isActive) return
        } else {
            view.setTag(R.id.tag_image_loader_url, normalized)
            inFlight.remove(view)?.cancel()
        }

        val cached = cache.get(normalized)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }

        if (view.drawable !== placeholder) view.setImageDrawable(placeholder)
        val job = scope.launch {
            try {
                val bytes =
                    withContext(Dispatchers.IO) {
                        loadBytesWithDiskCache(normalized)
                    }
                val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                if (bmp != null) {
                    cache.put(normalized, bmp)
                    if ((view.getTag(R.id.tag_image_loader_url) as? String) == normalized) {
                        view.setImageBitmap(bmp)
                    }
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "load failed url=$normalized", t)
            }
        }
        inFlight[view] = job
    }

    /** 磁盘缓存优先:命中直接读文件,否则网络下载并写盘 */
    private suspend fun loadBytesWithDiskCache(url: String): ByteArray {
        if (::diskCacheDir.isInitialized) {
            val disk = diskPath(url)
            if (disk.exists()) {
                val cached = runCatching { disk.readBytes() }.getOrNull()
                if (cached != null && cached.isNotEmpty()) return cached
            }
            val bytes = fetchBytes(url)
            runCatching { disk.writeBytes(bytes) }
                .onFailure { AppLog.w(TAG, "disk cache write failed", it) }
            return bytes
        }
        return fetchBytes(url)
    }

    private suspend fun fetchBytes(url: String): ByteArray {
        val host = url.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        val isBili =
            host == "hdslb.com" ||
                host.endsWith(".hdslb.com") ||
                host == "bilibili.com" ||
                host.endsWith(".bilibili.com") ||
                host == "bilivideo.com" ||
                host.endsWith(".bilivideo.com") ||
                host == "bilivideo.cn" ||
                host.endsWith(".bilivideo.cn")
        return if (isBili) {
            BiliClient.getBytes(url)
        } else {
            plainOkHttp.newCall(Request.Builder().url(url).build()).await().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                if (bytes.isEmpty() && !r.isSuccessful) throw java.io.IOException("HTTP ${r.code} ${r.message}")
                bytes
            }
        }
    }

    private fun diskPath(url: String): File = File(diskCacheDir, md5(url) + ".img")

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun normalizeImageUrl(url: String?): String? {
        val raw = url?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        if (raw.startsWith("//")) return "https:$raw"
        if (!raw.startsWith("http://")) return raw

        val host = raw.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        val isBiliCdn =
            host == "hdslb.com" ||
                host.endsWith(".hdslb.com") ||
                host == "bilibili.com" ||
                host.endsWith(".bilibili.com") ||
                host == "bilivideo.com" ||
                host.endsWith(".bilivideo.com") ||
                host == "bilivideo.cn" ||
                host.endsWith(".bilivideo.cn")
        return if (isBiliCdn) raw.replaceFirst("http://", "https://") else raw
    }

    private fun maxCacheBytes(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory().toInt()
        return maxMemory / 16
    }
}
