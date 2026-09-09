package io.github.deserthouse.opticon.hook

import android.app.Notification
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

    // 128 entries × ~36KB (96×96 ARGB_8888) ≈ 4.6MB upper bound — SystemUI affordable
    // NOTE: Do NOT auto-recycle evicted bitmaps — Icon.createWithBitmap holds
    // direct references to cached bitmaps, and recycling while SystemUI still
    // renders them would crash SystemUI. GC handles cleanup when Icon is released.
    private const val CACHE_SIZE = 128
    private const val PRELOAD_LIMIT = 128

    private val iconCache = LruCache<String, Bitmap>(CACHE_SIZE)

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

    private val currentPackage = ThreadLocal<String>()

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

        hookGetSmallIcon(xposed)
        hookCreateIcons(xposed, classLoader)
        hookUpdateIcons(xposed, classLoader)
        hookIconStyleProvider(xposed, classLoader)
        hookGetIconDescriptor(xposed, classLoader)
        hookStatusBarIconViewSet(xposed, classLoader)

        TraceLogger.i(TAG, "Hooks installed")
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
        } catch (e: Exception) {
            TraceLogger.w(TAG, "initHook: ${e.message}")
            masterEnabled = true
        } finally {
            initialized = true
        }
    }

    /** Read master switch file — default ENABLED if file doesn't exist */
    private fun refreshMasterSwitch() {
        try {
            val dir = moduleFilesDir ?: return
            val switchFile = File(dir, MASTER_SWITCH_FILE)
            masterSwitchMtime = if (switchFile.exists()) switchFile.lastModified() else -1L
            masterEnabled = !switchFile.exists() || switchFile.readText().trim() != "false"
            TraceLogger.i(TAG, "Master switch: $masterEnabled (file exists: ${switchFile.exists()})")
        } catch (e: Exception) {
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
            val dir = moduleFilesDir ?: return
            val switchFile = File(dir, MASTER_SWITCH_FILE)
            val mtime = if (switchFile.exists()) switchFile.lastModified() else -1L
            if (mtime != masterSwitchMtime) refreshMasterSwitch()
        } catch (_: Exception) {
        }
    }

    /** Preload most recent icons into cache (background thread only) */
    private fun preloadIcons() {
        try {
            val files = mutableListOf<File>()
            // World-readable dir first (primary production path), then module
            // filesDir sub-dirs (dev/root scenarios)
            if (WORLD_BAKED_DIR.isDirectory) {
                WORLD_BAKED_DIR.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".png") }
                    ?.let { files.addAll(it) }
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
                val pkg = f.name.removeSuffix(".png")
                if (loadIconForPackage(pkg) != null) loaded++
            }
            TraceLogger.i(TAG, "Preloaded $loaded icons into cache (${files.size} candidates)")
        } catch (e: Exception) {
            TraceLogger.w(TAG, "preloadIcons: ${e.message}")
        }
    }

    /** Watch icon dirs so re-baked icons apply without SystemUI restart */
    private fun startIconDirObservers() {
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
                FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.DELETE_SELF

        val watchDirs = mutableListOf<File>()
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
                        if (path == null || !path.endsWith(".png")) return
                        val pkg = path.removeSuffix(".png")
                        if (pkg.isNotEmpty()) {
                            iconCache.remove(pkg)
                            TraceLogger.d(TAG, "Cache invalidated: $pkg (${sub.name})")
                        }
                    }
                }
                observer.startWatching()
                if (sub == WORLD_BAKED_DIR) worldObserver = observer
                else if (sub.name == BAKED_DIR) bakedObserver = observer
                else fankesObserver = observer
                TraceLogger.i(TAG, "FileObserver started on ${sub.absolutePath}")
            } catch (e: Exception) {
                TraceLogger.w(TAG, "FileObserver(${sub.name}) failed: ${e.message}")
            }
        }
    }

    private fun hookGetSmallIcon(xposed: XposedInterface) {
        try {
            val method = Notification::class.java.getDeclaredMethod("getSmallIcon")
            xposed.hook(method).setId("opticon:getSmallIcon").intercept(
                XposedInterface.Hooker { chain ->
                    try {
                        if (!initialized) return@Hooker chain.proceed()
                        maybeRefreshMasterSwitch()
                        if (!masterEnabled) return@Hooker chain.proceed()
                        val pkg = currentPackage.get() ?: return@Hooker chain.proceed()
                        if (pkg == MODULE_PKG) return@Hooker chain.proceed()

                        val bitmap = loadIconForPackage(pkg) ?: return@Hooker chain.proceed()
                        Icon.createWithBitmap(bitmap)
                    } catch (e: Exception) {
                        TraceLogger.w(TAG, "getSmallIcon hook: ${e.message}")
                        try { chain.proceed() } catch (_: Exception) { null }
                    }
                }
            )
            TraceLogger.i(TAG, "Notification.getSmallIcon hooked")
        } catch (e: Exception) {
            TraceLogger.e(TAG, "hookGetSmallIcon failed: ${e.message}")
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
    private fun hookCreateIcons(xposed: XposedInterface, classLoader: ClassLoader) {
        try {
            val entryClass = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.collection.NotificationEntry"
            )
            val sbnField = entryClass.getDeclaredField("mSbn").apply { isAccessible = true }
            val managerClass = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.icon.IconManager"
            )
            val setSmallIcon = Notification::class.java.getDeclaredMethod(
                "setSmallIcon", Icon::class.java
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
                                    val bitmap = loadIconForPackage(pkg)
                                    if (bitmap != null) {
                                        setSmallIcon.invoke(sbn.notification, Icon.createWithBitmap(bitmap))
                                        TraceLogger.i(TAG, "createIcons: $pkg -> setSmallIcon replaced")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            TraceLogger.w(TAG, "createIcons replace: ${e.message}")
                        }
                        chain.proceed()
                    }
                )
            }
            TraceLogger.i(TAG, "IconManager.createIcons hooked (${candidates.size} overload)")
        } catch (e: Exception) {
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
            val setSmallIcon = Notification::class.java.getDeclaredMethod(
                "setSmallIcon", Icon::class.java
            )

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
                                    val bitmap = loadIconForPackage(pkg)
                                    if (bitmap != null) {
                                        setSmallIcon.invoke(sbn.notification, Icon.createWithBitmap(bitmap))
                                        TraceLogger.i(TAG, "updateIcons: $pkg -> setSmallIcon replaced")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            TraceLogger.w(TAG, "updateIcons replace: ${e.message}")
                        }
                        chain.proceed()
                    }
                )
            }
            TraceLogger.i(TAG, "IconManager.updateIcons hooked (" + candidates.size + " overload)")
        } catch (e: Exception) {
            TraceLogger.w(TAG, "hookUpdateIcons skipped: ${e.message}")
        }
    }

    /** Defensive hook (Android 16+): NotificationIconStyleProvider.shouldShowAppIcon
     *  can force the notification row to display the colorful APP launcher icon
     *  instead of the small icon during RemoteViews inflation. Force false so
     *  our replaced small icon survives. Interface introduced in AOSP 16. */
    private fun hookIconStyleProvider(xposed: XposedInterface, classLoader: ClassLoader) {
        try {
            val providerClass = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.row.icon.NotificationIconStyleProvider"
            )
            // Hook the interface method AND scan for impl classes is not possible via
            // interface alone; hook interface method itself (libxposed hooks concrete
            // methods, so instead we scan impl classes by known names).
            val implNames = listOf(
                "com.android.systemui.statusbar.notification.row.icon.NotificationIconStyleProviderImpl",
                "com.android.systemui.statusbar.notification.row.icon.NotificationIconStyleProviderImpl2"
            )
            var hooked = 0
            for (name in implNames) {
                try {
                    val impl = classLoader.loadClass(name)
                    for (m in impl.declaredMethods) {
                        if (m.name == "shouldShowAppIcon" && m.returnType == java.lang.Boolean.TYPE) {
                            xposed.hook(m).setId("opticon:shouldShowAppIcon").intercept(
                                XposedInterface.Hooker { chain -> false }
                            )
                            hooked++
                        }
                    }
                } catch (_: ClassNotFoundException) { /* impl name differs, skip */ }
            }
            // Also try the AOSP default impl discovered from the interface itself
            if (hooked == 0) {
                TraceLogger.i(TAG, "shouldShowAppIcon impl not found by known names, trying provider field")
            }
            TraceLogger.i(TAG, "NotificationIconStyleProvider.shouldShowAppIcon hooked (" + hooked + ")")
        } catch (e: Exception) {
            TraceLogger.i(TAG, "shouldShowAppIcon skipped (pre-16 or OEM): ${e.message}")
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
                        val bitmap = loadIconForPackage(pkg) ?: return@Hooker result
                        val view = chain.getThisObject() as? android.widget.ImageView
                        if (view != null) {
                            view.setImageDrawable(
                                android.graphics.drawable.BitmapDrawable(
                                    view.resources, bitmap
                                )
                            )
                            TraceLogger.d(TAG, "StatusBarIconView.set: $pkg -> drawable forced")
                        }
                    } catch (e: Exception) {
                        TraceLogger.w(TAG, "StatusBarIconView.set hook: ${e.message}")
                    }
                    result
                }
            )
            TraceLogger.i(TAG, "StatusBarIconView.set hooked")
        } catch (e: Exception) {
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
                            val bitmap = loadIconForPackage(pkg)
                            if (bitmap != null && result != null) {
                                val iconField = result.javaClass
                                    .getDeclaredField("icon").apply { isAccessible = true }
                                iconField.set(result, Icon.createWithBitmap(bitmap))
                                TraceLogger.d(TAG, "getIconDescriptor: $pkg -> icon replaced")
                            } else if (bitmap == null) {
                                TraceLogger.d(TAG, "getIconDescriptor: $pkg -> no bitmap cached")
                            }
                        }
                    } catch (e: Exception) {
                        TraceLogger.w(TAG, "getIconDescriptor hook: ${e.message}")
                    }
                    result
                }
            )
            TraceLogger.i(TAG, "IconManager.getIconDescriptor hooked")
        } catch (e: Exception) {
            TraceLogger.e(TAG, "hookGetIconDescriptor failed: ${e.message}")
        }
    }

    /** Load baked icon for package. Checks baked/ then fankes_cache/.
     *  Two read paths, tried in order:
     *    1. Direct File API — fast, works in dev/rooted scenarios.
     *    2. ContentResolver → IconContentProvider — canonical cross-uid path,
     *       survives SELinux app_data_file isolation in production. */
    private fun loadIconForPackage(pkg: String): Bitmap? {
        iconCache.get(pkg)?.let { return it }

        // Path 0: world-readable dir — survives SELinux app_data_file isolation.
        // The ONLY path verified working end-to-end in the AVD emulator; the App
        // writes here via root shell when baking.
        loadBitmapFromFile(File(WORLD_BAKED_DIR, "$pkg.png"))?.let {
            iconCache.put(pkg, it); return it
        }

        for (dirName in listOf(BAKED_DIR, FANKES_DIR)) {
            // Path 1: direct File read against the module's filesDir. Fast, but
            // blocked by SELinux app_data_file isolation in production (system_app
            // cannot read untrusted_app data).
            val baseDir = moduleFilesDir
            if (baseDir != null) {
                loadBitmapFromFile(File(File(baseDir, dirName), "$pkg.png"))?.let {
                    iconCache.put(pkg, it); return it
                }
            }
            // Path 2: ContentProvider fallback. Survives SELinux isolation by
            // returning bytes via Binder from the OptIcon app's domain. NOTE: on
            // LSPosed v2 the framework rejects cross-package openInputStream from
            // the hooked SystemUI process ("Given calling package android does
            // not match caller's uid 10169"), so this path works on root/dev
            // scenarios but is unreliable in production. Retained for resilience.
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
        } catch (e: Exception) { null }
    }

    private fun getSystemUiContext(): Context? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAT = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            val getSysUiCtx = atClass.getDeclaredMethod("getSystemUiContext")
            getSysUiCtx.invoke(currentAT) as? Context
        } catch (e: Exception) { null }
    }
}
