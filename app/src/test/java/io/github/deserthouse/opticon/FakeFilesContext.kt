package io.github.deserthouse.opticon

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/**
 * JVM 测试用的上下文：只提供文件目录，其余一概不实现（android.jar 的桩里 getString 之类的
 * 方法永远返回 null，文案一律经各引擎的 strings 接缝注入）。
 * 让引擎外壳（PicpEngine / SubscriptionManager / IconLibEngine / HookLibSync 的入口）
 * 能在未改动的生产代码上被直接执行。
 */
internal class FakeFilesContext(
    private val files: File,
    private val cache: File = File(files.parentFile, files.name + "-cache")
) : ContextWrapper(null) {

    override fun getFilesDir(): File = files.apply { mkdirs() }

    override fun getCacheDir(): File = cache.apply { mkdirs() }

    override fun getApplicationContext(): Context = this
}
