package io.github.deserthouse.opticon.engine

import org.json.JSONObject
import java.io.File

/**
 * RuleLibrary — 规则库目录（应用侧 fankes_cache / hook 侧 opticon_hook_icons）的
 * 读取入口与同步入口（纯 JVM）。
 *
 * 规则库与应用图标库共用 [FileSnapshotSwitch]，也就有同样的中间态。修复批之前
 * 恢复只在同步入口做（X-07）：切换中途被杀后、下一次同步之前，读取方按文件
 * 存在性查询，会把整库规则当成「都不存在」。
 *
 * 「先收敛、再建目录」是硬约束：live 缺失时 prev 里是唯一完好的旧版，这时建一个
 * 空的 live，下一次收敛就会把 prev 当成「已提交版本的旧备份」删掉——整库规则
 * 没了。所以收敛失败（旧版放不回去）时，下面每个入口都不建目录。
 */
internal object RuleLibrary {

    const val META_FILE = "meta.json"

    /** 同步时归档的临时文件名后缀：`{目录名}.zip.tmp`（AnipSync 写在目录旁边）。 */
    const val ARCHIVE_TMP_SUFFIX = ".zip.tmp"

    /** 快照来自哪里的旁车文件（批 UP）：随快照目录一起切换。旧快照没有它 = 没有版本记录。
     *  只认 .png 的读取方（[packages]、hook 取图）不受影响。 */
    const val SOURCE_FILE = "source.json"

    /**
     * 读取路径入口：应用侧启动加载（IconLibEngine）、hook 侧首次取目录（HookLibSync.dir）。
     *
     * 空闲时（此刻没有同步在跑）顺带清掉没建完的暂存目录和上次中断留下的临时归档；
     * 有同步在跑时只收敛中间态——暂存目录和归档可能正被构建/下载。
     *
     * @return false = 旧版放不回去，此刻没有可读的库；调用方不得创建空目录。
     */
    fun prepareForRead(
        dir: File,
        ops: FileSnapshotSwitch.FsOps = FileSnapshotSwitch.FsOps.Default
    ): Boolean {
        val parent = dir.parentFile ?: return dir.isDirectory
        val name = dir.name
        return SyncGate.ifIdle(
            whenIdle = {
                File(parent, name + ARCHIVE_TMP_SUFFIX).delete()
                FileSnapshotSwitch.recoverIfInterrupted(parent, name, ops = ops)
            },
            whenBusy = {
                FileSnapshotSwitch.recoverIfInterrupted(parent, name, clearStaging = false, ops = ops)
            }
        )
    }

    /** 读取 + 确保目录存在（写入方的读取入口）。收敛失败时不创建目录。 */
    fun ensureDir(
        dir: File,
        ops: FileSnapshotSwitch.FsOps = FileSnapshotSwitch.FsOps.Default
    ): Boolean {
        if (!prepareForRead(dir, ops)) return false
        return dir.isDirectory || dir.mkdirs()
    }

    /**
     * 同步入口：收敛中间态、清掉没建完的暂存目录、确保目录存在。
     *
     * @return false = 不能同步（旧版放不回去）。调用方必须放弃，不得建目录。
     */
    fun prepareForSync(
        dir: File,
        ops: FileSnapshotSwitch.FsOps = FileSnapshotSwitch.FsOps.Default
    ): Boolean {
        val parent = dir.parentFile ?: return false
        if (!FileSnapshotSwitch.recoverIfInterrupted(parent, dir.name, ops = ops)) return false
        return dir.isDirectory || dir.mkdirs()
    }

    /** 目录里的规则包集合（{pkg}.png 的文件名部分）。 */
    fun packages(dir: File): Set<String> =
        dir.list()?.filter { it.endsWith(".png") }
            ?.map { it.removeSuffix(".png") }
            ?.toSet()
            ?: emptySet()

    /** 当前这份快照的来历。 */
    data class Source(
        /** "release" = 上游官方发布通道；"archive" = 整库 zipball（兜底）或自定义归档。 */
        val channel: String,
        /** 官方通道的版本标签；整库通道没有。 */
        val tag: String?,
        /** 快照对应的版本时间（官方通道取清单里的；整库通道取下载时刻），毫秒。 */
        val timestamp: Long,
        val sha256: String?,
        /** 这份快照里的图标数（{pkg}.png 的个数）。下一次同步据此核实快照还完不完整：少了一个就不算
         *  「已经是这个版本」。旧记录没有这一项时为 0 = 没记，同样不能据此判「没有变化」。 */
        val icons: Int = 0
    ) {
        fun encode(): String = JSONObject()
            .put("channel", channel)
            .put("tag", tag ?: JSONObject.NULL)
            .put("timestamp", timestamp)
            .put("sha256", sha256 ?: JSONObject.NULL)
            .put("icons", icons)
            .toString()

        companion object {
            fun decode(text: String): Source? = try {
                val o = JSONObject(text)
                val channel = o.optString("channel")
                if (channel.isEmpty()) null
                else Source(
                    channel = channel,
                    tag = if (o.isNull("tag")) null else o.optString("tag"),
                    timestamp = o.optLong("timestamp", 0L),
                    sha256 = if (o.isNull("sha256")) null else o.optString("sha256"),
                    icons = o.optInt("icons", 0).coerceAtLeast(0)
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    /** 快照的来历；文件不存在、读不出、内容不合法 → null。 */
    fun readSource(dir: File): Source? {
        val file = File(dir, SOURCE_FILE)
        if (!file.isFile) return null
        return try {
            Source.decode(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    /** 把来历写进（暂存）目录。 */
    fun writeSource(dir: File, source: Source): Boolean = try {
        val file = File(dir, SOURCE_FILE)
        file.writeText(source.encode(), Charsets.UTF_8)
        file.setReadable(true, false)
        true
    } catch (_: Exception) {
        false
    }
}
