package io.github.deserthouse.opticon.data

import android.content.Context
import io.github.deserthouse.opticon.engine.publish.PublishCore
import io.github.deserthouse.opticon.util.PreferenceManager
import io.github.deserthouse.opticon.util.TraceLogger
import java.io.File

/**
 * AssetRepository — 上传素材的最小资产仓（RB-2，方案 B 阶段 1 素材引用）。
 *
 * 职责：内容寻址导入（同字节复用/不同字节永不覆写，放弃零副作用）＋
 * 孤儿回收（不被任何已提交配置引用 且 超过宽限期才删——防误删活跃草稿）。
 * 核心逻辑以 File 目录为参数（JVM 可测）；Context 包装仅做 filesDir 解析。
 */
object AssetRepository {
    private const val TAG = "OptIcon/AssetRepo"
    private const val GRACE_MS = 24 * 3600_000L

    data class AssetRef(val assetId: String, val path: String)

    fun assetsDir(context: Context): File =
        File(context.applicationContext.filesDir, "assets").apply { mkdirs() }

    /** 内容寻址导入：tmp+rename 原子落盘；同字节复用（长度校验防半写）。失败返回 null。 */
    fun importAsset(context: Context, bytes: ByteArray): AssetRef? =
        importInto(assetsDir(context), bytes)

    internal fun importInto(dir: File, bytes: ByteArray): AssetRef? {
        val sha = PublishCore.sha256(bytes)
        val stable = File(dir, "upload-$sha.png")
        if (stable.exists() && stable.length() == bytes.size.toLong()) {
            return AssetRef(sha, stable.absolutePath) // 同内容复用
        }
        val tmp = File(dir, "upload-$sha.png.tmp")
        return try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(stable)) {
                tmp.delete()
                null
            } else AssetRef(sha, stable.absolutePath)
        } catch (_: Exception) {
            tmp.delete()
            null
        }
    }

    fun resolve(path: String): ByteArray? = try {
        val f = File(path)
        if (f.isFile) f.readBytes() else null
    } catch (_: Exception) {
        null
    }

    /** 已提交配置引用的全部素材路径（custom_path_* 值快照，IO 线程调用）。 */
    fun referencedPaths(context: Context): Set<String> = PreferenceManager.allCustomIconPaths()

    /**
     * 孤儿判定：assets/ 下的 upload-$sha.png 与 legacy $pkg.upload.png，
     * 不被引用且 mtime 超宽限期（默认 24h——活跃草稿引用不入 prefs，宽限防误删）。
     */
    internal fun collectOrphans(
        dir: File,
        referenced: Set<String>,
        now: Long = System.currentTimeMillis(),
        graceMs: Long = GRACE_MS
    ): List<File> = dir.listFiles { f ->
        val n = f.name
        val isAsset = (n.startsWith("upload-") && n.endsWith(".png")) || n.endsWith(".upload.png")
        isAsset && f.absolutePath !in referenced && now - f.lastModified() > graceMs
    }?.toList() ?: emptyList()

    /** 全量清扫：返回删除数。挂启动恢复线程末尾（legacy 清扫同规则）。 */
    fun sweepOrphans(context: Context): Int {
        val ctx = context.applicationContext
        val removed = collectOrphans(assetsDir(ctx), referencedPaths(ctx)).count { it.delete() }
        if (removed > 0) TraceLogger.i(TAG, "sweepOrphans: removed $removed unreferenced asset(s)")
        return removed
    }

    /** 单文件 GC：保存切换后旧素材不再被任何已提交配置引用即删（调用方须先排除草稿引用）。 */
    fun sweepIfUnreferenced(context: Context, path: String): Boolean {
        if (!path.contains("/assets/")) return false
        if (referencedPaths(context).contains(path)) return false
        return try {
            File(path).delete().also { ok -> if (ok) TraceLogger.i(TAG, "swept old asset $path") }
        } catch (_: Exception) {
            false
        }
    }
}
