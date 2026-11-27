package io.github.deserthouse.opticon.engine

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * AnipReleaseChannel — 上游官方发布通道（纯 JVM）。
 *
 * 上游自 2026-09 起每次图标变更发一个 release，附件是精简的资源包（约 1.8MB，只有 app / game /
 * system 三个目录）和一份名字固定的清单：
 *
 *   …/releases/latest/download/anip-release.json
 *   { "schemaVersion": 1, "tag": "e98c906", "timestamp": 1795697528000,
 *     "assetName": "anip-bundle-e98c906.zip", "downloadUrl": "anip-bundle-e98c906.zip",
 *     "size": 1795929, "sha256": "…" }
 *
 * 先取清单（260 字节）就知道有没有新版本；有才下载包，并按清单核对大小与 SHA-256。
 * 校验规则跟上游 SDK 的 ResourceReleaseClient 一致，只有一处更严：下载地址必须落在清单
 * 所在的目录里（上游 SDK 允许任意绝对地址）。
 *
 * 取不到清单、清单不合法、包下载不了或核对不上，都只说明「官方通道这次用不了」——
 * 调用方（AnipSync）退回整库 zipball，不影响可用性。
 */
internal object AnipReleaseChannel {

    const val REPO = "BetterAndroid/android-notification-icon-project"
    const val MANIFEST_FILE = "anip-release.json"
    const val MANIFEST_URL = "https://github.com/$REPO/releases/latest/download/$MANIFEST_FILE"

    /** 清单格式的版本。上游改格式时会升这个数；不认识的一律当「用不了」。 */
    const val SCHEMA_VERSION = 1

    /** 清单本身的上限（上游 SDK 也是 64KB）。 */
    const val MAX_MANIFEST_BYTES = 64L * 1024

    private val TAG_PATTERN = Regex("^[0-9a-fA-F]{7}$")
    private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    private const val HEX = "0123456789abcdef"

    /** 清单里描述的一个版本。 */
    data class Release(
        val tag: String,
        val timestamp: Long,
        val size: Long,
        val sha256: String,
        val downloadUrl: String
    )

    sealed class Fetched {
        data class Ok(val release: Release) : Fetched()

        /** [reason] 供日志。 */
        data class Unavailable(val reason: String) : Fetched()
    }

    /** 取字节的两个动作。生产实现走 NetworkExecutor；单测注入。 */
    interface Access {
        /** 取小文件。取不到、不是 2xx、超过 [maxBytes] → null。 */
        fun fetchSmall(url: String, maxBytes: Long): ByteArray?

        /** 取到文件。落盘字节数超过 [maxBytes] 即中止并删掉半截文件，返回 false。 */
        fun fetchToFile(url: String, dest: File, maxBytes: Long): Boolean
    }

    /** 取最新版本的清单并校验。任何失败都是 [Fetched.Unavailable]，不抛。
     *  [manifestUrl] 生产恒为 [MANIFEST_URL]；调试包的设备验证套件把它指到本机测试源（见 SubscriptionManager）。 */
    fun fetchLatest(access: Access, manifestUrl: String = MANIFEST_URL): Fetched {
        val bytes = try {
            access.fetchSmall(manifestUrl, MAX_MANIFEST_BYTES)
        } catch (e: Exception) {
            return Fetched.Unavailable("manifest request failed (${e.javaClass.simpleName}: ${e.message})")
        } ?: return Fetched.Unavailable("manifest request failed")
        return parse(bytes.toString(Charsets.UTF_8), manifestUrl)
    }

    fun parse(text: String, manifestUrl: String = MANIFEST_URL): Fetched {
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            return Fetched.Unavailable("manifest is not JSON (${e.javaClass.simpleName})")
        }
        fun field(name: String): String? {
            val value = obj.opt(name) ?: return null
            if (value == JSONObject.NULL) return null
            return value.toString().trim().ifEmpty { null }
        }
        fun bad(what: String) = Fetched.Unavailable(what)

        val schema = obj.optInt("schemaVersion", -1)
        if (schema != SCHEMA_VERSION) return bad("unsupported manifest schema $schema")

        val tag = field("tag") ?: return bad("manifest tag is missing")
        if (!TAG_PATTERN.matches(tag)) return bad("invalid manifest tag '$tag'")

        val asset = field("assetName") ?: return bad("manifest assetName is missing")
        if (asset != "anip-bundle-$tag.zip") return bad("unexpected manifest asset name '$asset'")

        val timestamp = obj.optLong("timestamp", 0L)
        if (timestamp <= 0L) return bad("invalid manifest timestamp")

        val size = obj.optLong("size", -1L)
        if (size !in 1..AnipSnapshotBuilder.DEFAULT_LIMITS.archiveBytes) return bad("manifest size $size is out of range")

        val sha256 = field("sha256") ?: return bad("manifest sha256 is missing")
        if (!SHA256_PATTERN.matches(sha256)) return bad("invalid manifest sha256")

        val target = field("downloadUrl") ?: return bad("manifest downloadUrl is missing")
        val url = resolve(manifestUrl, target) ?: return bad("manifest download url is outside the release directory")

        return Fetched.Ok(Release(tag.lowercase(Locale.ROOT), timestamp, size, sha256.lowercase(Locale.ROOT), url))
    }

    /** 清单里的下载地址：相对名按清单所在目录解析；绝对地址也必须落在这个目录里。
     *  对外一律 https；只有清单自己落在回环地址上时才允许明文（调试包的设备验证用本机测试源）。 */
    fun resolve(manifestUrl: String, downloadUrl: String): String? {
        if (downloadUrl.isEmpty() || downloadUrl.contains("..") || downloadUrl.contains('?') || downloadUrl.contains('#')) return null
        val base = manifestUrl.substringBeforeLast('/', "") + "/"
        val url = if (downloadUrl.startsWith("https://") || downloadUrl.startsWith("http://")) downloadUrl else base + downloadUrl
        val secure = url.startsWith("https://") ||
            (LOOPBACK_HTTP.any { base.startsWith(it) } && url.startsWith("http://"))
        if (!secure || !url.startsWith(base)) return null
        return url
    }

    /** 回环地址上的明文清单：设备自己（127.0.0.1），或模拟器里的宿主机（10.0.2.2）。 */
    private val LOOPBACK_HTTP = listOf("http://127.0.0.1:", "http://[::1]:", "http://10.0.2.2:")

    /** 按清单核对下载下来的包：大小与 SHA-256 都对才算。读不出 → false。 */
    fun verify(file: File, release: Release): Boolean {
        if (!file.isFile || file.length() != release.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
        } catch (_: Exception) {
            return false
        }
        val hex = StringBuilder()
        for (b in digest.digest()) {
            val v = b.toInt() and 0xFF
            hex.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return hex.toString() == release.sha256
    }
}
