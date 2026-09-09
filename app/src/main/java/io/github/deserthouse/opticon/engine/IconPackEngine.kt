package io.github.deserthouse.opticon.engine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import io.github.deserthouse.opticon.util.TraceLogger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * IconPackEngine — 第三方分层图标包提取 (方案2)
 *
 * 探测系统已安装的图标包（如"完美图标计划"pzcn），解析 appfilter.xml 匹配
 * 目标应用，提取 AdaptiveIconDrawable 的 foreground 前景层，Tint 纯白后缩放。
 *
 * 图标包识别：通过 intent-filter com.novalauncher.THEME 与 org.adw.launcher.THEMES
 */
object IconPackEngine {

    private const val TAG = "OptIcon/IconPack"

    data class IconPackInfo(
        val packageName: String,
        val label: String,
        val icon: Bitmap?
    )

    /**
     * Single icon entry from an icon pack's appfilter.xml.
     * componentRaw: raw "ComponentInfo{...}" string from XML
     * drawableName: drawable resource name in the icon pack
     */
    data class PackIconEntry(
        val componentRaw: String,
        val componentPackage: String?,
        val drawableName: String
    )

    /** 查询已安装的第三方图标包 */
    fun listInstalledIconPacks(context: Context): List<IconPackInfo> {
        val pm = context.packageManager
        val intents = listOf(
            "com.novalauncher.THEME",
            "org.adw.launcher.THEMES"
        )
        val result = mutableListOf<IconPackInfo>()
        for (action in intents) {
            val intent = android.content.Intent(action)
            pm.queryIntentActivities(intent, 0).forEach { ri ->
                val pkg = ri.activityInfo.packageName
                val label = ri.loadLabel(pm).toString()
                val icon = try {
                    drawableToBitmap(ri.loadIcon(pm), 48)
                } catch (_: Exception) { null }
                if (result.none { it.packageName == pkg }) {
                    result.add(IconPackInfo(pkg, label, icon))
                }
            }
        }
        TraceLogger.i(TAG, "Found ${result.size} icon packs")
        return result
    }

    /**
     * 列出指定图标包中的全部图标条目（来自 appfilter.xml 的 <item> 节点），
     * 供 UI 呈现手动选择/浏览图标资产。
     */
    fun listAllIcons(context: Context, iconPackPkg: String): List<PackIconEntry> {
        val result = mutableListOf<PackIconEntry>()
        try {
            val packRes = context.packageManager.getResourcesForApplication(iconPackPkg)
            val xmlId = packRes.getIdentifier("appfilter", "xml", iconPackPkg)
            if (xmlId == 0) return result

            val xml = packRes.getXml(xmlId)
            var event = xml.next()
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "item") {
                    val comp = xml.getAttributeValue(null, "component")
                    val drawable = xml.getAttributeValue(null, "drawable")
                    if (drawable != null) {
                        val pkg = if (comp != null) extractComponentPackage(comp) else null
                        result.add(PackIconEntry(
                            componentRaw = comp ?: "",
                            componentPackage = pkg,
                            drawableName = drawable
                        ))
                    }
                }
                event = xml.next()
            }
        } catch (e: Exception) {
            TraceLogger.w(TAG, "listAllIcons failed: ${e.message}")
        }
        return result
    }

    /**
     * 从图标包中按指定 drawable 名渲染到位图（用于浏览预览），
     * 支持自适应模式直接返回原图，预览失败时返回 null。
     */
    fun loadIconPreview(context: Context, iconPackPkg: String, drawableName: String, size: Int = 48): Bitmap? {
        try {
            val packRes = context.packageManager.getResourcesForApplication(iconPackPkg)
            val drawableId = packRes.getIdentifier(drawableName, "drawable", iconPackPkg)
            if (drawableId == 0) return null
            val drawable = packRes.getDrawable(drawableId, null) ?: return null
            return drawableToBitmap(drawable, size)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 从指定图标包中提取目标应用的通知图标。
     *
     * 流程：appfilter.xml 匹配 → 图标包 drawable → AdaptiveIcon.foreground → Tint → scale
     * 若指定图标包不支持分层图标，降级返回 null（调用方走方案3）。
     *
     * @param iconPackPkg 图标包包名
     * @param targetPkg   目标应用包名（当前正在配置的应用）
     * @return 纯白合规通知图标，失败返回 null
     */
    fun extractForPackage(
        context: Context,
        iconPackPkg: String,
        targetPkg: String
    ): Bitmap? {
        try {
            val drawableName = findMatchingDrawable(context, iconPackPkg, targetPkg)
                ?: return null

            val packRes = context.packageManager.getResourcesForApplication(iconPackPkg)
            val drawableId = packRes.getIdentifier(
                drawableName, "drawable", iconPackPkg
            )
            if (drawableId == 0) return null

            val drawable = packRes.getDrawable(drawableId, null) ?: return null

            // AdaptiveIcon → 取 foreground
            val foreground = if (drawable is AdaptiveIconDrawable) {
                drawable.foreground
            } else {
                // Non-adaptive icon pack entry —— fallback to launcher icon, skip self-filter here
                TraceLogger.d(TAG, "Icon pack $iconPackPkg has non-adaptive drawable for $targetPkg, fallback")
                return null
            }

            val bitmap = drawableToBitmap(foreground, 96)
            val white = IconTint.toWhite(bitmap) ?: return null
            if (bitmap !== white) bitmap.recycle()

            // 默认缩放 0.60~0.70
            return IconTint.scaleAndTintWhite(white, 0.65f, 96)
        } catch (e: Exception) {
            TraceLogger.w(TAG, "extractForPackage failed: ${e.message}")
            return null
        }
    }

    // ━━ appfilter.xml 匹配 ━━

    private fun findMatchingDrawable(
        context: Context,
        iconPackPkg: String,
        targetPkg: String
    ): String? {
        try {
            val packRes = context.packageManager.getResourcesForApplication(iconPackPkg)
            val xmlId = packRes.getIdentifier("appfilter", "xml", iconPackPkg)
            if (xmlId == 0) return null

            val xml = packRes.getXml(xmlId)
            var event = xml.next()
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "item") {
                    val comp = xml.getAttributeValue(null, "component")
                    val drawable = xml.getAttributeValue(null, "drawable")
                    if (comp != null && drawable != null) {
                        // component = "ComponentInfo{targetPkg/targetPkg.SplashActivity}"
                        val pkg = extractComponentPackage(comp)
                        if (pkg == targetPkg) {
                            return drawable
                        }
                    }
                }
                event = xml.next()
            }
        } catch (e: Exception) {
            TraceLogger.w(TAG, "appfilter parse failed: ${e.message}")
        }
        return null
    }

    /**
     * 从 ComponentInfo{…} 字符串提取包名。
     * 例如 "ComponentInfo{com.example/com.example.MainActivity}" → "com.example"
     */
    private fun extractComponentPackage(component: String): String? {
        val start = component.indexOf('{').takeIf { it >= 0 } ?: return null
        val end = component.indexOf('/').takeIf { it >= 0 } ?: component.indexOf('}')
        if (end < 0) return null
        return component.substring(start + 1, end)
    }

    // ━━ 工具 ━━

    fun hasLaunchableActivity(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getLaunchIntentForPackage(pkg) != null
        } catch (_: Exception) { false }
    }

    private fun drawableToBitmap(
        drawable: android.graphics.drawable.Drawable,
        size: Int
    ): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }
}
