package io.github.deserthouse.opticon.network

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * NetworkExecutor — OkHttp-based sync network client
 *
 *   - OkHttp (not HttpURLConnection)
 *   - System-default TLS verification (M1 fix: trust-all SSL removed)
 *   - Proper Android User-Agent (not Windows desktop UA)
 */
object NetworkExecutor {

    private const val TAG = "OptIcon_Net"

    /** Default timeouts */
    const val CONNECT_TIMEOUT_SEC = 10L
    const val READ_TIMEOUT_SEC = 15L
    const val LARGE_READ_TIMEOUT_SEC = 120L

    /** Core OkHttpClient — system-default SSL, timeouts */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    /** Client with longer read timeout for large API responses (Git Trees API) */
    private val largeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(LARGE_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    // ━━━ Async fetch (OkHttp enqueue) ━━━

    /**
     * Asynchronously fetch URL content as String.
     * Callback is called on OkHttp's thread — caller routes to main thread if needed.
     */
    fun fetchStringAsync(url: String, onSuccess: (String) -> Unit, onFailure: (String) -> Unit) {
        client.newCall(
            Request.Builder().url(url).get().build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onFailure(e.toString())
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string() ?: ""
                    onSuccess(body)
                } catch (e: Exception) {
                    onFailure(e.toString())
                } finally {
                    response.close()
                }
            }
        })
    }

    /**
     * Synchronous fetch — use only on background thread (Dispatchers.IO).
     * Returns body string or null on failure. Optional byte-level progress
     * callback (downloaded, total; total=-1 when server omits length).
     */
    fun fetchStringSync(url: String, onProgress: ((Long, Long) -> Unit)? = null): String? {
        return try {
            val response = client.newCall(
                Request.Builder().url(url).get().build()
            ).execute()
            if (!response.isSuccessful) { response.close(); return null }
            val body = response.body ?: run { response.close(); return null }
            val text = if (onProgress != null) {
                val total = body.contentLength()
                val bos = java.io.ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    var downloaded = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        bos.write(buffer, 0, read)
                        downloaded += read
                        onProgress.invoke(downloaded, total)
                    }
                }
                bos.toString("UTF-8")
            } else body.string()
            response.close()
            text
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Quick connectivity check — GET GitHub Raw README, callback with true/false.
     */
    fun checkConnectivity(onResult: (Boolean) -> Unit) {
        fetchStringAsync(CONNECTIVITY_PROBE_URL,
            onSuccess = { onResult(true) },
            onFailure = { onResult(false) }
        )
    }

    private const val CONNECTIVITY_PROBE_URL =
        "https://raw.githubusercontent.com/BetterAndroid/android-notification-icon-project/main/README.md"

    /** [checkConnectivity] 的同步版本——给已经在后台线程上的调用方用。 */
    fun checkConnectivitySync(): Boolean = try {
        client.newCall(Request.Builder().url(CONNECTIVITY_PROBE_URL).get().build())
            .execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }

    /**
     * Synchronous bytes fetch — use only on background thread.
     */
    fun fetchBytesSync(url: String): ByteArray? {
        return try {
            val response = client.newCall(
                Request.Builder().url(url).get().build()
            ).execute()
            if (!response.isSuccessful) {
                android.util.Log.w("OptIcon/Net", "fetchBytesSync HTTP ${response.code} $url")
                response.close(); return null
            }
            val bytes = response.body?.bytes()
            response.close()
            bytes
        } catch (e: Exception) {
            android.util.Log.w("OptIcon/Net", "fetchBytesSync failed $url: ${e.message}")
            null
        }
    }

    /**
     * Stream a large download directly to disk without buffering in memory.
     * For downloading the PICP zipball. Returns true on success.
     */
    fun fetchToFile(url: String, destFile: java.io.File, onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null): Boolean =
        fetchToFileBounded(url, destFile, onProgress = onProgress) == DownloadOutcome.OK

    /** Bounded download result — distinguishes user cancel / budget breach from network failure. */
    enum class DownloadOutcome { OK, CANCELLED, TOO_LARGE, FAILED }

    /**
     * 可中断的取消信号：[isCancelled] 供下载循环轮询；[cancel] 还会直接取消
     * 正在进行的 HTTP 调用。
     *
     * 修复批（X-19）之前取消只靠轮询——源停住不发数据时，两个数据块之间
     * 没有轮询点，要等到读超时（大文件 120 秒）才返回「已取消」。
     */
    class CancelToken {
        private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        @Volatile
        private var inFlight: Call? = null

        val isCancelled: Boolean get() = cancelled.get()

        fun cancel() {
            cancelled.set(true)
            inFlight?.cancel()
        }

        internal fun track(call: Call) {
            inFlight = call
            // 登记之前就已经取消：补一次，不让这次调用跑下去
            if (cancelled.get()) call.cancel()
        }

        internal fun untrack() {
            inFlight = null
        }
    }

    /**
     * fetchToFile with a byte budget and a cooperative cancel hook (batch SYNC V4):
     *  - [maxBytes]: hard ceiling on accumulated bytes — a malicious/hostile
     *    source can no longer fill the disk with a giant or endless body.
     *  - [shouldCancel]: polled between chunks; true aborts with CANCELLED.
     * Non-OK outcomes leave [destFile] partial — the caller owns cleanup.
     */
    fun fetchToFileBounded(
        url: String,
        destFile: java.io.File,
        maxBytes: Long = Long.MAX_VALUE,
        shouldCancel: (() -> Boolean)? = null,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): DownloadOutcome = fetchToFileCore(url, destFile, maxBytes, shouldCancel, null, onProgress)

    /**
     * [fetchToFileBounded] 的可中断版本（修复批 X-19）：[token] 取消时直接掐断
     * 正在进行的网络调用，不必等轮询点或读超时。其余语义相同。
     */
    fun fetchToFileCancellable(
        url: String,
        destFile: java.io.File,
        token: CancelToken,
        maxBytes: Long = Long.MAX_VALUE,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): DownloadOutcome = fetchToFileCore(url, destFile, maxBytes, token::isCancelled, token, onProgress)

    private fun fetchToFileCore(
        url: String,
        destFile: java.io.File,
        maxBytes: Long,
        shouldCancel: (() -> Boolean)?,
        token: CancelToken?,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)?
    ): DownloadOutcome {
        val call: Call
        val response: Response
        try {
            call = largeClient.newCall(Request.Builder().url(url).get().build())
            token?.track(call)
            response = call.execute()
        } catch (e: Exception) {
            // 被掐断的调用以 IOException 冒出来——按取消归位，不冒充网络故障
            token?.untrack()
            return if (token?.isCancelled == true) DownloadOutcome.CANCELLED else DownloadOutcome.FAILED
        }
        return try {
            if (!response.isSuccessful) return DownloadOutcome.FAILED
            val body = response.body ?: return DownloadOutcome.FAILED
            val totalBytes = body.contentLength()
            destFile.outputStream().use { fos ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    var downloaded = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        if (shouldCancel?.invoke() == true) return DownloadOutcome.CANCELLED
                        downloaded += read
                        if (downloaded > maxBytes) return DownloadOutcome.TOO_LARGE
                        fos.write(buffer, 0, read)
                        onProgress?.invoke(downloaded, totalBytes)
                    }
                }
            }
            DownloadOutcome.OK
        } catch (e: Exception) {
            if (token?.isCancelled == true) return DownloadOutcome.CANCELLED
            DownloadOutcome.FAILED
        } finally {
            response.close()
            token?.untrack()
        }
    }
}
