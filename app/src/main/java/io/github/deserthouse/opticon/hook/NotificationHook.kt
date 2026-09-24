package io.github.deserthouse.opticon.hook

import android.app.Notification
import android.graphics.drawable.Drawable
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.FileObserver
import android.service.notification.StatusBarNotification
import android.util.LruCache
import io.github.deserthouse.opticon.util.TraceLogger
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * NotificationHook — per-package baked icon interception
 *
 * Strategy: hook Notification.getSmallIcon() and return a replacement icon
 * read from the module's filesDir. NO bulk JSON loading, NO Binder IPC.
 *
 * Icon sources (checked in order):
 *   1. baked/{pkg}.png  — user's per-app custom icon (any strategy)
 *   2. fankes_cache/{pkg}.png — Fankes rules library icon
 *
 * File permissions: UI sets baked/ and fankes_cache/ dirs to world-traversable
 * + world-readable, individual PNGs world-readable. SystemUI (UID 1000) can
 * read them directly via createPackageContext.
 *
 * Master switch: reads filesDir/master_switch file (world-readable).
 *
 * Hot reload of settings:
 *   - master_switch: re-checked via rate-limited mtime stat (every 3s max)
 *   - icon PNGs: FileObserver on baked/ and fankes_cache/ invalidates the
 *     cache entry for that package, so re-baked icons apply without
 *     restarting SystemUI (new notifications pick up the new bitmap)
 */
object NotificationHook {

    private const val TAG = "OptIcon/NotifHook"
    private const val MODULE_PKG = "io.github.deserthouse.opticon"
    private const val BAKED_DIR = "baked"
    private const val FANKES_DIR = "fankes_cache"
    private const val MASTER_SWITCH_FILE = "master_switch"
    private const val MASTER_SWITCH_RECHECK_MS = 3000L
    /** World-readable icon dir that survives SELinux app_data_file isolation.
     *  The App bakes PNGs here via root shell (su -c cp) when available. */
    private val WORLD_BAKED_DIR = File("/data/local/tmp/opticon_baked")
    /** PRODUCTION channel: public Downloads/OptIcon/ dir (Iconify-proven).
     *  App writes via MediaStore; SystemUI reads via plain File API — both
     *  sides agree on this path. Survives SELinux, no root, no IPC. */
    private val SHARED_ICON_DIR = File(
        android.os.Environment
            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
        "OptIcon"
    )
    private const val SHARED_ICON_EXT = ".opticon"

    // 128 entries × ~36KB (96×96 ARGB_8888) ≈ 4.6MB upper bound — SystemUI affordable
    // NOTE: Do NOT auto-recycle evicted bitmaps — Icon.createWithBitmap holds
    // direct references to cached bitmaps, and recycling while SystemUI still
    // renders them would crash SystemUI. GC handles cleanup when Icon is released.
    private const val CACHE_SIZE = 128
    private const val PRELOAD_LIMIT = 128

    private val iconCache = LruCache<String, Bitmap>(CACHE_SIZE)

    /** Single worker for compliance/archive side jobs (B5): serializes file
     *  writes and keeps the bitmap render + PNG compress off the main thread. */
    private val complianceExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    @Volatile
    private var initialized = false

    @Volatile
    private var masterEnabled = true

    @Volatile
    private var moduleFilesDir: File? = null

    /** ContentResolver for cross-uid file reads via exported IconContentProvider.
     *  Direct file reads through createPackageContext are blocked by SELinux
     *  app_data_file isolation (system_app cannot read untrusted_app data),
     *  so we fall back to ContentProvider.openFile which runs in the OptIcon
     *  app's domain and returns the bytes via Binder. */
    @Volatile
    private var moduleResolver: ContentResolver? = null

    private const val PROVIDER_AUTHORITY = "io.github.deserthouse.opticon.icons"

    /** mtime of master_switch when last read; -1 = file absent */
    @Volatile
    private var masterSwitchMtime: Long = -1L

    /** rate-limit guard for master_switch stat checks */
    private val lastSwitchCheck = AtomicLong(0L)

    /** Shade row icon mode: "app" (force app icon) / "notif" (force small
     *  icon) / "pref" (respect the app's preferSmallIcon extra). */
    @Volatile
    private var shadeIconMode = "app"

    /** mtime of shade_icon_mode when last read; -1 = file absent */
    @Volatile
    private var shadeIconModeMtime: Long = -1L

    /** rate-limit guard for shade_icon_mode stat checks */
    private val lastShadeModeCheck = AtomicLong(0L)


    /** FileObservers watching icon dirs; strong refs keep them alive */
    @Volatile
    private var bakedObserver: FileObserver? = null

    @Volatile
    private var fankesObserver: FileObserver? = null

    @Volatile
    private var worldObserver: FileObserver? = null

    fun install(xposed: XposedInterface, classLoader: ClassLoader) {
        TraceLogger.i(TAG, "Installing notification icon hooks...")

        Thread({ initHook() }, "OptIconNotifHook-Init").start()

        hookCreateIcons(xposed, classLoader)
        hookUpdateIcons(xposed, classLoader)
        hookCachingIconView(xposed)
        hookRecoverBuilder(xposed)
        hookGetIconDescriptor(xposed, classLoader)
        hookStatusBarIconViewSet(xposed, classLoader)
        hookShadeAppIcon(xposed, classLoader)

        TraceLogger.i(TAG, "Hooks installed")
        Thread({ reportHookAlive() }, "OptIconHeartbeat-Initial").start()

        // Heartbeat: re-report every 5 min so the App's freshness check
        // (10 min window) knows this SystemUI instance still has the hook.
        // A crash/deactivation stops the heartbeat → status flips to inactive.
        // A FAILED report (e.g. provider not yet registered early in boot)
        // retries after 30s instead of waiting a full cycle, so the App UI
        // doesn't show a false "inactive" for up to 5 minutes after boot.
        val heartbeatThread = android.os.HandlerThread("OptIconHeartbeat")
        heartbeatThread.start()
        val heartbeat = android.os.Handler(heartbeatThread.looper)
        val beat = object : Runnable {
            override fun run() {
                if (!initialized) return
                val ok = reportHookAlive()
                heartbeat.postDelayed(this, if (ok) 5 * 60 * 1000L else 30 * 1000L)
            }
        }
        heartbeat.postDelayed(beat, 30 * 1000L)
    }

    /** Report liveness to the module's ContentProvider so the App UI can show
     *  an accurate LSPosed-active status. Runs on EVERY SystemUI start — the
     *  provider file stores THIS SystemUI's PID, and the App validates it is
     *  still alive, so a stale report self-corrects to "inactive".
     *  BLOCKING — must run off the main thread.
     *  @return true if the provider accepted the report */
    private fun reportHookAlive(): Boolean {
        // Attribution matters here: the provider rejects callers whose package
        // doesn't match their uid. ActivityThread.getSystemUiContext() and
        // createPackageContext() both keep the "android" op package → rejected.
        // Only the real SystemUI Application context carries the correct
        // package identity. It may not exist yet during early hook init, so
        // poll for it with a generous timeout.
        var app: Context? = null
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val current = atClass.getDeclaredMethod("currentApplication")
            for (i in 1..20) {
                app = current.invoke(null) as? Context
                if (app != null) break
                Thread.sleep(2500)
            }
            if (app == null) {
                TraceLogger.w(TAG, "reportHookAlive: SystemUI Application never ready")
                return false
            }
            val bundle = android.os.Bundle().apply {
                putBoolean("hook_installed", true)
                putInt("hook_pid", android.os.Process.myPid())
            }
            app.contentResolver.call(
                Uri.parse("content://io.github.deserthouse.opticon.icons/__flag__"),
                "set_flag", null, bundle
            )
            TraceLogger.i(TAG, "Hook alive reported (pid=${android.os.Process.myPid()})")
            true
        } catch (e: Throwable) {
            TraceLogger.w(TAG, "reportHookAlive: ${e.message}")
            false
        }
    }

    private fun initHook() {
        try {
            val moduleCtx = getModuleContext()
            moduleFilesDir = moduleCtx?.filesDir
            TraceLogger.i(TAG, "Module filesDir: ${moduleFilesDir?.absolutePath}")

            // Resolve ContentResolver from systemui context — used as fallback to
            // bypass SELinux app_data_file isolation when reading baked/ icons.
            moduleResolver = try {
                getSystemUiContext()?.contentResolver
            } catch (_: Exception) { null }
            TraceLogger.i(TAG, "ContentProvider fallback resolver: " +
                "${if (moduleResolver != null) "ready" else "UNAVAILABLE"}")

            refreshMasterSwitch()

            // Warm the icon cache off the main thread — decode the most recently
            // baked/updated PNGs so the first notifications after boot don't hit
            // the disk on SystemUI main thread.
            if (masterEnabled) preloadIcons()

            // Watch icon dirs and drop stale cache entries on change
            startIconDirObservers()

            // Plan B: ANIP-style in-process rules sync — the hook process
            // downloads ANIA icons itself into its own cacheDir (no IPC).
            // Needs the REAL SystemUI Application context: getSystemUiContext()
            // keeps the "android" package whose cacheDir is inaccessible.
            Thread({
                try {
                    val atClass = Class.forName("android.app.ActivityThread")
                    val current = atClass.getDeclaredMethod("currentApplication")
                    var app: Context? = null
                    for (i in 1..20) {
                        app = current.invoke(null) as? Context
                        if (app != null) break
                        Thread.sleep(2500)
                    }
                    app?.let { HookLibSync.start(it) }
                        ?: TraceLogger.w(TAG, "HookLibSync skipped: SystemUI Application never ready")
                } catch (e: Throwable) {
                    TraceLogger.w(TAG, "HookLibSync boot: ${e.message}")
                }
            }, "OptIconHookSyncBoot").apply { isDaemon = true }.start()
        } catch (e: Throwable) {
            TraceLogger.w(TAG, "initHook: ${e.message}")
            masterEnabled = true
        } finally {
            initialized = true
        }
    }

    /** Read master switch file — default ENABLED if file doesn't exist.
     *  Checks the production shared dir first (always readable), then the
     *  legacy filesDir copy (dev/root scenarios). */
    private fun refreshMasterSwitch() {
        try {
            val shared = File(SHARED_ICON_DIR, "master_switch.opticon")
            val dir = moduleFilesDir
            val legacy = if (dir != null) File(dir, MASTER_SWITCH_FILE) else null
            val source = when {
                shared.exists() -> shared
                legacy != null && legacy.exists() -> legacy
                else -> null
            }
            masterSwitchMtime = source?.lastModified() ?: -1L
            masterEnabled = source == null || source.readText().trim() != "false"
            TraceLogger.i(TAG, "Master switch: $masterEnabled (source: ${source?.absolutePath ?: "absent, default on"})")
        } catch (e: Throwable) {
            TraceLogger.w(TAG, "refreshMasterSwitch: ${e.message}")
        }
    }

    /** Rate-limited hot re-check of master_switch (max one stat per 3s) */
    private fun maybeRefreshMasterSwitch() {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastSwitchCheck.get()
        if (now - last < MASTER_SWITCH_RECHECK_MS) return
        if (!lastSwitchCheck.compareAndSet(last, now)) return
        try {
            val shared = File(SHARED_ICON_DIR, "master_switch.opticon")
            val dir = moduleFilesDir
            val legacy = if (dir != null) File(dir, MASTER_SWITCH_FILE) else null
            val mtime = when {
                shared.exists() -> shared.lastModified()
                legacy != null && legacy.exists() -> legacy.lastModified()
                else -> -1L
            }
            if (mtime != masterSwitchMtime) refreshMasterSwitch()
        } catch (_: Exception) {
        }
    }

    /** Preload most recent icons into cache (background thread only) */
    private fun preloadIcons() {
        try {
            val files = mutableListOf<File>()
            // Priority order mirrors loadIconForPackage: production shared dir
            // first, then tmp dir, then module filesDir (dev/root scenarios)
            fun isIconFile(f: File) =
                f.isFile && (f.name.endsWith(".png") || f.name.endsWith(SHARED_ICON_EXT))
            if (SHARED_ICON_DIR.isDirectory) {
                SHARED_ICON_DIR.listFiles()?.filter(::isIconFile)?.let { files.addAll(it) }
            }
            if (WORLD_BAKED_DIR.isDirectory) {
                WORLD_BAKED_DIR.listFiles()?.filter(::isIconFile)?.let { files.addAll(it) }
            }
            val dir = moduleFilesDir
            if (dir != null) {
                for (dirName in listOf(BAKED_DIR, FANKES_DIR)) {
                    val sub = File(dir, dirName)
                    if (sub.isDirectory) {
                        sub.listFiles()?.filter { it.isFile && it.name.endsWith(".png") }?.let { files.addAll(it) }
                    }
                }
            }
            files.sortByDescending { it.lastModified() }
            var loaded = 0
            for (f in files) {
                if (loaded >= PRELOAD_LIMIT) break
                val pkg = f.name.removeSuffix(SHARED_ICON_EXT).ifEmpty { f.name.removeSuffix(".png") }
                if (loadIconForPackage(pkg) != null) loaded++
            }
            TraceLogger.i(TAG, "Preloaded $loaded icons into cache (${files.size} candidates)")
        } catch (e: Throwable) {
            TraceLogger.w(TAG, "preloadIcons: ${e.message}")
        }
    }

    /** Watch icon dirs so re-baked icons apply without SystemUI restart */
    private fun startIconDirObservers() {
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
                FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.DELETE_SELF

        val watchDirs = mutableListOf<File>()
        if (SHARED_ICON_DIR.isDirectory) watchDirs.add(SHARED_ICON_DIR)
        if (WORLD_BAKED_DIR.isDirectory) watchDirs.add(WORLD_BAKED_DIR)
        val dir = moduleFilesDir
        if (dir != null) {
            for (dirName in listOf(BAKED_DIR, FANKES_DIR)) {
                val sub = File(dir, dirName)
                if (sub.isDirectory) watchDirs.add(sub)
            }
        }

        for (sub in watchDirs) {
            try {
                val observer = object : FileObserver(sub.absolutePath, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        // Production channel files use .opticon; dev channels
                        // use .png — both must invalidate the cache.
                        if (path == null) return
                        // App→hook sync wakeup: fresh meta.json means the App
                        // just published new ANIP rules — re-sync the local
                        // cache instead of waiting out the 12h cycle.
                        if (sub.name == FANKES_DIR && path == "meta.json") {
                            HookLibSync.requestSync()
                            return
                        }
                        val isIconFile = path.endsWith(SHARED_ICON_EXT) || path.endsWith(".png")
                        if (!isIconFile) return
                        val pkg = path.removeSuffix(SHARED_ICON_EXT).removeSuffix(".png")
                        if (pkg.isNotEmpty()) {
                            iconCache.remove(pkg)
                            TraceLogger.d(TAG, "Cache invalidated: $pkg (${sub.name})")
                        }
                    }
                }
                observer.startWatching()
                if (sub == SHARED_ICON_DIR) worldObserver = observer  // reuse slot: production dir
                else if (sub == WORLD_BAKED_DIR) worldObserver = observer
                else if (sub.name == BAKED_DIR) bakedObserver = observer
                else fankesObserver = observer
                TraceLogger.i(TAG, "FileObserver started on ${sub.absolutePath}")
            } catch (e: Throwable) {
                TraceLogger.w(TAG, "FileObserver(${sub.name}) failed: ${e.message}")
            }
        }
    }

    /** Main hook: IconManager.createIcons(NotificationEntry) BEFORE.
     *  This signature has been stable from API 23 through AOSP main (37).
     *  Strategy (same as NotificationIconFix's AOSP path): before the
     *  system builds icon descriptors, call Notification.setSmallIcon()
     *  via reflection so every downstream reader (getSmallIcon /
     *  getIconDescriptor / StatusBarIconView.set) sees the replaced icon.
     *  Method lookup uses semantic matching (name + param type + return
     *  void) with silent fallback, so signature drift across Android
     *  versions never breaks hook installation. */
    // ━━ #13 色彩策略矩阵 ━━
    // color_mode.opticon: "off" | "force_mono"（全局，默认 off）
    // color_override/<pkg>.opticon: "mono" | "color"（每应用覆盖，优先于全局）
    private var colorModeStamp: Pair<Long, String>? = null
    private val colorOverrideStamps = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    private fun readColorMode(): String {
        val f = java.io.File(SHARED_ICON_DIR, "color_mode.opticon")
        val m = try { f.lastModified() } catch (_: Exception) { 0L }
        colorModeStamp?.let { (stamp, value) -> if (stamp == m) return value }
        val value = try { f.takeIf { it.isFile }?.readText()?.trim() } catch (_: Exception) { null } ?: "off"
        colorModeStamp = m to value
        monoIconCache.clear()  // policy file changed: stale grayscale results
        return value
    }

    private fun readColorOverride(pkg: String): String? {
        val f = java.io.File(java.io.File(SHARED_ICON_DIR, "color_override"), "$pkg.opticon")
        val m = try { f.lastModified() } catch (_: Exception) { 0L }
        colorOverrideStamps[pkg]?.let { (stamp, value) -> if (stamp == m) return value }
        val value = try { f.takeIf { it.isFile }?.readText()?.trim() } catch (_: Exception) { null }
        if (value != null) {
            colorOverrideStamps[pkg] = m to value
            monoIconCache.remove(pkg)
        }
        return value
    }

    /** Effective #13 policy: "mono" when this notification's icon must be
     *  forced grayscale, "color" when a keep-color override wins. */
    private fun colorPolicyWantsMono(pkg: String): Boolean = when (readColorOverride(pkg)) {
        "mono" -> true
        "color" -> false
        else -> readColorMode() == "force_mono"
    }

    /** #19 single replacement entry for every hook site: baked icon first,
     *  else (#13) grayscale of the original when the color policy demands
     *  mono, else null = leave the original untouched. All 7 replacement
     *  paths (createIcons/updateIcons/getSmallIcon/CachingIconView/
     *  recoverBuilder/StatusBarIconView.set/getIconDescriptor) route through
     *  here so a policy change can never be applied by some paths and
     *  missed by others (the exact bug class this consolidation removes). */
    private fun resolveReplacementIcon(pkg: String, original: Drawable?): Icon? {
        loadIconForPackage(pkg)?.let { return Icon.createWithBitmap(it) }
        if (!colorPolicyWantsMono(pkg)) return null
        // readColorOverride's mtime stamp re-reads whenever the override file
        // changes (including deletion, which changes the dir scan), so cache
        // entries can't outlive their policy — no explicit invalidation needed.
        monoIconCache[pkg]?.let { return it }
        val gray = original?.let { ComplianceDetector.toGrayscaleBitmap(it) } ?: return null
        val icon = Icon.createWithBitmap(gray)
        monoIconCache[pkg] = icon
        return icon
    }

    // Mono-path result cache: getSmallIcon fires on every row bind, so the
    // grayscale must not re-render per call. Invalidated by the policy-file
    // stamp checks above; a fresh bake wins anyway (checked before cache).
    private val monoIconCache = java.util.concurrent.ConcurrentHashMap<String, Icon>()

    private val setSmallIconMethod: java.lang.reflect.Method by lazy {
        Notification::class.java.getDeclaredMethod("setSmallIcon", Icon::class.java)
    }

    private fun iconDrawableOf(icon: Icon?): Drawable? = try {
        // Must use the REAL SystemUI Application context: the SystemContext
        // ("android" package) cannot resolve other apps' resource icons.
        val atClass = Class.forName("android.app.ActivityThread")
        val app = atClass.getDeclaredMethod("currentApplication").invoke(null) as? Context
        icon?.loadDrawable(app)
    } catch (_: Exception) { null }

    private fun iconDrawableOf(n: android.app.Notification?): Drawable? = iconDrawableOf(n?.smallIcon)

    private fun hookCreateIcons(xposed: XposedInterface, classLoader: ClassLoader) {
        try {
            val entryClass = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.collection.NotificationEntry"
            )
            val sbnField = entryClass.getDeclaredField("mSbn").apply { isAccessible = true }
            val managerClass = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.icon.IconManager"
            )

            // Semantic match: name=createIcons, single NotificationEntry param.
            // Falls back across future signature drift instead of crashing.
            val candidates = managerClass.declaredMethods.filter {
                it.name == "createIcons" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == entryClass
            }
            if (candidates.isEmpty()) {
                TraceLogger.w(TAG, "createIcons: no matching overload found")
                return
            }

            for (method in candidates) {
                xposed.hook(method).setId("opticon:createIcons:${method.toGenericString().hashCode()}").intercept(
                    XposedInterface.Hooker { chain ->
                        try {
                            if (initialized && masterEnabled) {
                                maybeRefreshMasterSwitch()
                                val entry = chain.getArg(0)
                                val sbn = sbnField.get(entry) as? StatusBarNotification
                                val pkg = sbn?.packageName
                                if (!pkg.isNullOrEmpty() && pkg != MODULE_PKG) {
                                    // Disk IO + bitmap render — never on the
                                    // SystemUI main thread (B5). The Icon object
                                    // stays valid; its bitmap is read on the worker.
                                    complianceExecutor.execute {
                                        try {
                                            ComplianceDetector.evaluateAndReport(SHARED_ICON_DIR, sbn.notification, pkg)
                                        } catch (e: Throwable) {
                                            TraceLogger.w(TAG, "compliance: ${e.message}")
                                        }
                                    }
                                    val replacement = resolveReplacementIcon(pkg, iconDrawableOf(sbn.notification))
                                    if (replacement != null) {
                                        setSmallIconMethod.invoke(sbn.notification, replacement)
                                        val t = sbn.notification.smallIcon?.type
                                        TraceLogger.i(TAG, "createIcons: $pkg -> setSmallIcon replaced (icon.type=$t)")
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            TraceLogger.w(TAG, "createIcons replace: ${e.message}")
                        }
                        chain.proceed()
                    }
                )
            }
            TraceLogger.i(TAG, "IconManager.createIcons hooked (${candidates.size} overload)")
        } catch (e: Throwable) {
            TraceLogger.e(TAG, "hookCreateIcons failed: ${e.message}")
        }
    }

    /** Aux hook: all overloads named "updateIcons" (NIF-style name-based
     *  matching). Silently skipped when the method does not exist on a
     *  given Android version (removed in Android 16+). */
    private fun hookUpdateIcons(xposed: XposedInterface, classLoader: ClassLoader) {
        try {
            val managerClass = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.icon.IconManager"
            )
            val entryClass = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.collection.NotificationEntry"
            )
            val sbnField = entryClass.getDeclaredField("mSbn").apply { isAccessible = true }

            // Name-based matching across ALL overloads (NIF g() approach)
            val candidates = managerClass.declaredMethods.filter { it.name == "updateIcons" }
            if (candidates.isEmpty()) {
                TraceLogger.i(TAG, "updateIcons: not present on this Android version, skipped")
                return
            }

            for (method in candidates) {
                xposed.hook(method).setId("opticon:updateIcons:" + method.toGenericString().hashCode()).intercept(
                    XposedInterface.Hooker { chain ->
                        try {
                            if (initialized && masterEnabled) {
                                val sbn = when {
                                    chain.args.size == 1 && entryClass.isInstance(chain.getArg(0)) ->
                                        sbnField.get(chain.getArg(0)) as? StatusBarNotification
                                    else -> null
                                }
                                val pkg = sbn?.packageName
                                if (!pkg.isNullOrEmpty() && pkg != MODULE_PKG) {
                                    val replacement = resolveReplacementIcon(pkg, iconDrawableOf(sbn.notification))
                                    if (replacement != null) {
                                        setSmallIconMethod.invoke(sbn.notification, replacement)
                                        TraceLogger.i(TAG, "updateIcons: $pkg -> setSmallIcon replaced")
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            TraceLogger.w(TAG, "updateIcons replace: ${e.message}")
                        }
                        chain.proceed()
                    }
                )
            }
            TraceLogger.i(TAG, "IconManager.updateIcons hooked (" + candidates.size + " overload)")
        } catch (e: Throwable) {
            TraceLogger.w(TAG, "hookUpdateIcons skipped: ${e.message}")
        }
    }

    /** Heads-up / first-inflation hook: CachingIconView.setImageIcon(Icon).
     *
     *  ROOT CAUSE this fixes: heads-up banners and freshly-inflated rows get
     *  their header icon from the RemoteViews action stream — the smallIcon
     *  was serialized by the APP's Notification.Builder inside the app
     *  process, so mutating the SystemUI-side sbn object (our createIcons
     *  path) does NOT reach the already-serialized RemoteViews payload.
     *  CachingIconView (com.android.internal.widget, stable API 23+) is the
     *  concrete view bound to android.R.id.icon in the standard template.
     *  Intercepting setImageIcon here rewrites the icon at the LAST possible
     *  moment before the view renders — covers heads-up, list rows, and
     *  content-update rebinds in one place.
     *
     *  Package attribution: the view itself has no notification identity, so
     *  we look up the nearest ExpandableNotificationRow ancestor via
     *  getContext() → row tag fallback. Simpler robust approach: replace
     *  unconditionally when the icon's resPackage resolves to a package we
     *  have a baked icon for. */
    private fun hookCachingIconView(xposed: XposedInterface) {
        try {
            val viewClass = Class.forName("com.android.internal.widget.CachingIconView")
            val methods = viewClass.declaredMethods.filter {
                it.name == "setImageIcon" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == Icon::class.java
            }
            if (methods.isEmpty()) {
                TraceLogger.i(TAG, "CachingIconView.setImageIcon not found, skipped")
                return
            }
            for (m in methods) {
                xposed.hook(m).setId("opticon:CachingIconView.setImageIcon").intercept(
                    XposedInterface.Hooker { chain ->
                        try {
                            if (initialized && masterEnabled) {
                                val icon = chain.getArg(0) as? Icon
                                if (icon != null) {
                                    val pkg = icon.resPackage?.takeIf { it.isNotEmpty() && it != "android" }
                                    if (pkg != null && pkg != MODULE_PKG) {
                                        val replacement = resolveReplacementIcon(pkg, iconDrawableOf(icon))
                                        if (replacement != null) {
                                            chain.args[0] = replacement
                                            TraceLogger.i(TAG, "CachingIconView: $pkg -> icon swapped")
                                        }
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            TraceLogger.w(TAG, "CachingIconView hook: ${e.message}")
                        }
                        chain.proceed()
                    }
                )
            }
            TraceLogger.i(TAG, "CachingIconView.setImageIcon hooked (${methods.size})")
        } catch (e: Throwable) {
            TraceLogger.w(TAG, "hookCachingIconView skipped: ${e.message}")
        }
    }

    /** Race-fix hook: Notification.Builder.recoverBuilder AFTER.
     *
     *  SystemUI inflates notification rows via recoverBuilder(rowCtx, sbn.notification)
     *  → Builder adopts the SAME Notification object (this.mN = toAdopt) →
     *  build() re-runs bindSmallIcon which serializes mN.mSmallIcon into the
     *  template RemoteViews (setImageViewIcon(android.R.id.icon, ...)).
     *
     *  RACE: inflation (NotifInflaterImpl) can run BEFORE IconManager.createIcons
     *  fires, so the builder snapshots the ORIGINAL icon. Replacing mSmallIcon
     *  here — after recoverBuilder returns, before build() is called — closes
     *  the race for heads-up banners, list rows, and single-line views alike,
     *  because they all rebuild content from this recovered builder. */
    private fun hookRecoverBuilder(xposed: XposedInterface) {
        try {
            val builderClass = Notification.Builder::class.java
            val method = builderClass.getDeclaredMethod(
                "recoverBuilder", Context::class.java, Notification::class.java
            )
            val mNField = builderClass.getDeclaredField("mN").apply { isAccessible = true }

            xposed.hook(method).setId("opticon:recoverBuilder").intercept(
                XposedInterface.Hooker { chain ->
                    val builder = chain.proceed()
                    try {
                        if (initialized && masterEnabled && builder != null) {
                            val n = chain.getArg(1) as? Notification ?: return@Hooker builder
                            // Self-contained attribution: the builder AppInfo stashed
                            // in extras carries the originating package — no timing
                            // dependency on createIcons.
                            val appInfo = try {
                                n.extras?.getParcelable(
                                    "android.app.extra.BUILDER_APPLICATION_INFO"
                                ) as? android.content.pm.ApplicationInfo
                            } catch (_: Exception) { null }
                            // Fallback to icon resPackage attribution (RESOURCE icons only;
                            // BITMAP icons are already-replaced payloads with no resPackage)
                            val pkg = appInfo?.packageName
                                ?: runCatching {
                                    n.smallIcon?.takeIf { it.type == 2 }?.resPackage
                                }.getOrNull()?.takeIf { it.isNotEmpty() && it != "android" }
                            if (!pkg.isNullOrEmpty() && pkg != MODULE_PKG) {
                                val replacement = resolveReplacementIcon(pkg, iconDrawableOf(n))
                                if (replacement != null) {
                                    setSmallIconMethod.invoke(n, replacement)
                                    TraceLogger.i(TAG, "recoverBuilder: $pkg -> mSmallIcon replaced")
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        TraceLogger.w(TAG, "recoverBuilder hook: ${e.message}")
                    }
                    builder
                }
            )
            TraceLogger.i(TAG, "Notification.Builder.recoverBuilder hooked")
        } catch (e: Throwable) {
            TraceLogger.w(TAG, "hookRecoverBuilder skipped: ${e.message}")
        }
    }

    /** Hook StatusBarIconView.set(StatusBarIcon) AFTER, then force the view to
     *  display our baked bitmap via setImageDrawable. This is the "belt and
     *  suspenders" override: regardless of whatever StatusBarIconView's
     *  internal pipeline does with the StatusBarIcon it received, our
     *  drawable is what ends up drawn on screen.
     *
     *  Rationale: even when IconManager.getIconDescriptor's icon field is
     *  successfully mutated, downstream icon loading (loadDrawableAsUser)
     *  or icon tinting can still replace the drawable on some Android
     *  builds. Directly setting setImageDrawable here guarantees the
     *  baked icon renders. StatusBarIconView extends ImageView, so
     *  setImageDrawable is inherited and safe. */
    private fun hookStatusBarIconViewSet(xposed: XposedInterface, classLoader: ClassLoader) {
        try {
            val sbiViewClass = classLoader.loadClass(
                "com.android.systemui.statusbar.StatusBarIconView"
            )
            val sbiClass = classLoader.loadClass(
                "com.android.internal.statusbar.StatusBarIcon"
            )
            val setMethod = sbiViewClass.getDeclaredMethod("set", sbiClass)
            val pkgField = sbiClass.getDeclaredField("pkg").apply { isAccessible = true }

            xposed.hook(setMethod).setId("opticon:StatusBarIconView.set").intercept(
                XposedInterface.Hooker { chain ->
                    val result = chain.proceed()
                    try {
                        if (!initialized || !masterEnabled) return@Hooker result
                        val sbi = chain.getArg(0)
                        val pkg = pkgField.get(sbi) as? String
                        if (pkg.isNullOrEmpty() || pkg == MODULE_PKG) return@Hooker result
                        // Mono source: the Icon this StatusBarIcon carries (the
                        // pre-set original), read reflectively like pkg above.
                        val sbiIcon = try {
                            sbi.javaClass.getDeclaredField("icon").apply { isAccessible = true }
                                .get(sbi) as? Icon
                        } catch (_: Exception) { null }
                        val replacement = resolveReplacementIcon(pkg, iconDrawableOf(sbiIcon))
                            ?: return@Hooker result
                        val view = chain.getThisObject() as? android.widget.ImageView
                        // Icon.getBitmap() is non-public on this API level — load
                        // the drawable through the helper instead of unwrapping.
                        val drawable = iconDrawableOf(replacement)
                        if (view != null && drawable != null) {
                            view.setImageDrawable(drawable)
                            TraceLogger.d(TAG, "StatusBarIconView.set: $pkg -> drawable forced")
                        }
                    } catch (e: Throwable) {
                        TraceLogger.w(TAG, "StatusBarIconView.set hook: ${e.message}")
                    }
                    result
                }
            )
            TraceLogger.i(TAG, "StatusBarIconView.set hooked")
        } catch (e: Throwable) {
            TraceLogger.e(TAG, "hookStatusBarIconViewSet failed: ${e.message}")
        }
    }

    /** Hook IconManager.getIconDescriptor(entry, redact) AFTER the original
     *  method returns, then mutate the returned StatusBarIcon's `icon` field.
     *
     *  This is the proven replacement strategy used by ColorOSNotifyIcon
     *  (fankes): the returned StatusBarIcon object IS the instance cached by
     *  IconManager (cacheIconDescriptor) and later passed to
     *  StatusBarIconView.set(), which renders `mIcon.icon.loadDrawableAsUser`.
     *  Mutating the field guarantees the replaced bitmap reaches the view,
     *  unlike a getSmallIcon() return-value swap which the render pipeline
     *  can drop on some builds. */
    private fun hookGetIconDescriptor(xposed: XposedInterface, classLoader: ClassLoader) {
        try {
            val entryClass = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.collection.NotificationEntry"
            )
            val managerClass = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.icon.IconManager"
            )
            val method = managerClass.getDeclaredMethod(
                "getIconDescriptor", entryClass, Boolean::class.javaPrimitiveType
            )
            val sbnField = entryClass.getDeclaredField("mSbn").apply { isAccessible = true }

            xposed.hook(method).setId("opticon:getIconDescriptor").intercept(
                XposedInterface.Hooker { chain ->
                    val result = chain.proceed()
                    try {
                        val entry = chain.getArg(0)
                        val sbn = sbnField.get(entry) as? StatusBarNotification
                        val pkg = sbn?.packageName
                        if (initialized && masterEnabled && pkg != null && pkg != MODULE_PKG) {
                            val replacement = resolveReplacementIcon(pkg, iconDrawableOf(sbn.notification))
                            if (replacement != null && result != null) {
                                val iconField = result.javaClass
                                    .getDeclaredField("icon").apply { isAccessible = true }
                                iconField.set(result, replacement)
                                TraceLogger.d(TAG, "getIconDescriptor: $pkg -> icon replaced")
                            }
                        }
                    } catch (e: Throwable) {
                        TraceLogger.w(TAG, "getIconDescriptor hook: ${e.message}")
                    }
                    result
                }
            )
            TraceLogger.i(TAG, "IconManager.getIconDescriptor hooked")
        } catch (e: Throwable) {
            TraceLogger.e(TAG, "hookGetIconDescriptor failed: ${e.message}")
        }
    }

    /** Android 17+: hook NotificationIconStyleProviderImpl.shouldShowAppIcon —
     *  the system's switch for "show app icon vs small icon" in the shade row
     *  (exists on 17, absent on 36 — silently skipped there). Mode comes from
     *  the shared shade_icon_mode file (user setting, default "app" = AOSP
     *  behavior): "notif" forces the (replaced) small icon, "pref" lets the
     *  app's own preferSmallIcon extra decide via the original method. */
    private fun hookShadeAppIcon(xposed: XposedInterface, classLoader: ClassLoader) {
        try {
            val impl = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.row.icon.NotificationIconStyleProviderImpl"
            )
            val methods = impl.declaredMethods.filter {
                it.name == "shouldShowAppIcon" && it.returnType == java.lang.Boolean.TYPE
            }
            if (methods.isEmpty()) {
                TraceLogger.i(TAG, "shouldShowAppIcon not found (pre-17), skipped")
                return
            }
            for (m in methods) {
                xposed.hook(m).setId("opticon:shouldShowAppIcon").intercept(
                    XposedInterface.Hooker { chain ->
                        try {
                            if (!initialized) return@Hooker chain.proceed()
                            maybeRefreshShadeMode()
                            if (!masterEnabled || shadeIconMode == "pref") return@Hooker chain.proceed()
                            shadeIconMode == "app"
                        } catch (e: Throwable) {
                            TraceLogger.w(TAG, "shouldShowAppIcon hook: ${e.message}")
                            try { chain.proceed() } catch (_: Exception) { false }
                        }
                    }
                )
            }
            refreshShadeMode()
            TraceLogger.i(TAG, "shouldShowAppIcon hooked (${methods.size}) — shade icon mode active")
        } catch (e: Throwable) {
            TraceLogger.w(TAG, "hookShadeAppIcon skipped: ${e.message}")
        }
    }

    /** Blocking read of the shade icon mode (shared dir first, legacy
     *  filesDir fallback). MUST run off the main thread. */
    private fun refreshShadeMode() {
        try {
            val shared = File(SHARED_ICON_DIR, "shade_icon_mode.opticon")
            val dir = moduleFilesDir
            val legacy = if (dir != null) File(dir, "shade_icon_mode") else null
            val source = when {
                shared.exists() -> shared
                legacy != null && legacy.exists() -> legacy
                else -> null
            }
            shadeIconModeMtime = source?.lastModified() ?: -1L
            if (source != null) {
                shadeIconMode = source.readText().trim().ifEmpty { "app" }
            }
        } catch (e: Throwable) {
            TraceLogger.w(TAG, "refreshShadeMode: ${e.message}")
        }
    }

    /** Rate-limited hot re-check of shade_icon_mode (max one stat per 3s) */
    private fun maybeRefreshShadeMode() {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastShadeModeCheck.get()
        if (now - last < MASTER_SWITCH_RECHECK_MS) return
        if (!lastShadeModeCheck.compareAndSet(last, now)) return
        try {
            val shared = File(SHARED_ICON_DIR, "shade_icon_mode.opticon")
            val dir = moduleFilesDir
            val legacy = if (dir != null) File(dir, "shade_icon_mode") else null
            val mtime = when {
                shared.exists() -> shared.lastModified()
                legacy != null && legacy.exists() -> legacy.lastModified()
                else -> -1L
            }
            if (mtime != shadeIconModeMtime) refreshShadeMode()
        } catch (_: Exception) {
        }
    }

    /** Load baked icon for package. Read paths, tried in order:
     *    0. SHARED_ICON_DIR (Download/OptIcon/{pkg}.opticon) — PRODUCTION
     *       channel, Iconify-proven, SELinux-safe, no root needed.
     *    1. /data/local/tmp/opticon_baked — root/test channel (dev AVD).
     *    2. module filesDir direct read — dev scenarios only (SELinux blocks
     *       this in production).
     *    3. ContentProvider fallback — unreliable under LSPosed v2, kept for
     *       resilience. */
    private fun loadIconForPackage(pkg: String): Bitmap? {
        iconCache.get(pkg)?.let { return it }

        // Path 0: production shared dir
        loadBitmapFromFile(File(SHARED_ICON_DIR, "$pkg$SHARED_ICON_EXT"))?.let {
            iconCache.put(pkg, it); return it
        }

        // Path 0.5: hook-local rules cache (Plan B, ANIP-style self-sync)
        getSystemUiContext()?.let { HookLibSync.bitmapFor(it, pkg) }?.let {
            iconCache.put(pkg, it); return it
        }

        // Path 1: world-readable tmp dir (root/test)
        loadBitmapFromFile(File(WORLD_BAKED_DIR, "$pkg.png"))?.let {
            iconCache.put(pkg, it); return it
        }

        for (dirName in listOf(BAKED_DIR, FANKES_DIR)) {
            // Path 2: direct File read against the module's filesDir.
            val baseDir = moduleFilesDir
            if (baseDir != null) {
                loadBitmapFromFile(File(File(baseDir, dirName), "$pkg.png"))?.let {
                    iconCache.put(pkg, it); return it
                }
            }
            // Path 3: ContentProvider fallback (unreliable on LSPosed v2)
            loadBitmapFromProvider(dirName, pkg)?.let {
                iconCache.put(pkg, it); return it
            }
        }
        return null
    }

    private fun loadBitmapFromFile(file: File): Bitmap? {
        return try {
            if (!file.exists()) return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            val realOpts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                if (opts.outWidth > 256 || opts.outHeight > 256) {
                    inSampleSize = Integer.highestOneBit(maxOf(opts.outWidth, opts.outHeight) / 256)
                }
            }
            BitmapFactory.decodeFile(file.absolutePath, realOpts)
        } catch (_: Exception) { null }
    }

    private fun loadBitmapFromProvider(dirName: String, pkg: String): Bitmap? {
        val resolver = moduleResolver ?: return null
        // PKG_NAME regex already enforced inside IconContentProvider.openFile; URI
        // path segment cannot escape the authority because we only embed `pkg`.
        val uri = Uri.parse("content://$PROVIDER_AUTHORITY/$dirName/$pkg.png")
        return try {
            resolver.openInputStream(uri)?.use { stream ->
                // Two-pass decode to apply same OOM guards as direct path
                val tmp = stream.readBytes()
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(tmp, 0, tmp.size, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
                val realOpts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    if (opts.outWidth > 256 || opts.outHeight > 256) {
                        inSampleSize = Integer.highestOneBit(
                            maxOf(opts.outWidth, opts.outHeight) / 256)
                    }
                }
                BitmapFactory.decodeByteArray(tmp, 0, tmp.size, realOpts)
            }
        } catch (_: Exception) { null }
    }

    private fun getModuleContext(): Context? {
        return try {
            val sysUiCtx = getSystemUiContext() ?: return null
            sysUiCtx.createPackageContext(MODULE_PKG, Context.CONTEXT_IGNORE_SECURITY)
        } catch (e: Throwable) { null }
    }

    private fun getSystemUiContext(): Context? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAT = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            val getSysUiCtx = atClass.getDeclaredMethod("getSystemUiContext")
            getSysUiCtx.invoke(currentAT) as? Context
        } catch (e: Throwable) { null }
    }
}
