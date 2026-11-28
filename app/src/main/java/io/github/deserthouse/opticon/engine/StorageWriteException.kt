package io.github.deserthouse.opticon.engine

import java.io.IOException

/**
 * 本地写不进去：磁盘满、配额用尽、I/O 错误、建不了目录。
 *
 * 和「归档无效」「下载失败」分开报（批 MT M3）。磁盘满的时候，下载落盘或解压写不进，此前被归成
 * 「下载失败」或「归档无效」——提示用户查网络、换源，其实该做的是清理空间。
 * 只用于**本地写**这一侧；从归档里读不出来仍然是归档的问题。
 */
class StorageWriteException(message: String? = null, cause: Throwable? = null) : IOException(message, cause)
