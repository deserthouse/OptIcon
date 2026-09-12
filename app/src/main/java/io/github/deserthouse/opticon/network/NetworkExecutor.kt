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
        fetchStringAsync("https://raw.githubusercontent.com/fankes/AndroidNotifyIconAdapt/main/README.md",
            onSuccess = { onResult(true) },
            onFailure = { onResult(false) }
        )
    }

    /**
     * Synchronous bytes fetch — use only on background thread.
     */
    fun fetchBytesSync(url: String): ByteArray? {
        return try {
            val response = client.newCall(
                Request.Builder().url(url).get().build()
            ).execute()
            if (!response.isSuccessful) { response.close(); return null }
            val bytes = response.body?.bytes()
            response.close()
            bytes
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Stream a large download directly to disk without buffering in memory.
     * For downloading the PICP zipball (~50MB). Returns true on success.
     */
    fun fetchToFile(url: String, destFile: java.io.File, onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null): Boolean {
        val response: okhttp3.Response?
        try {
            response = largeClient.newCall(
                Request.Builder().url(url).get().build()
            ).execute()
        } catch (e: Exception) {
            return false
        }
        return try {
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            val totalBytes = body.contentLength()
            destFile.outputStream().use { fos ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    var downloaded = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        fos.write(buffer, 0, read)
                        downloaded += read
                        onProgress?.invoke(downloaded, totalBytes)
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            response.close()
        }
    }
}
