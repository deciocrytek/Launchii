package com.oneuiclone.home.model

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import com.oneuiclone.home.icons.IconFactory
import java.text.Collator
import java.util.concurrent.Executors

/** Stable key for an app entry: component + user serial. */
data class AppKey(val component: ComponentName, val userSerial: Long) {
    fun flatten() = component.flattenToString() + "#" + userSerial

    companion object {
        fun parse(s: String): AppKey? {
            val i = s.lastIndexOf('#')
            if (i < 0) return null
            val cn = ComponentName.unflattenFromString(s.substring(0, i)) ?: return null
            return AppKey(cn, s.substring(i + 1).toLongOrNull() ?: return null)
        }
    }
}

class AppInfo(
    val key: AppKey,
    val user: UserHandle,
    val label: String,
    /** Home screen / folder icon (themed when theme icons are on). */
    var icon: Bitmap,
    val activityInfo: LauncherActivityInfo,
    /** Apps screen icon: always the app's own colours, even with theme icons on. */
    val drawerIcon: Bitmap = icon,
    /**
     * In a closed folder's preview: [icon], but for the see-through themes (Frosted, Clear Colour) the symbol alone
     * (Minimal, Minimal Colour), so the folder's own glass is the only plate there (user, 0.238), with the icons'
     * faint contour along each app's edge when Icon contour is on (user, 0.250).
     */
    val previewIcon: Bitmap = icon,
)

/**
 * Loads all launchable activities for every profile, renders One UI icons off the main thread, and
 * keeps them in memory (icons are pre-baked so drawing never re-applies mask/emboss).
 */
class AppRepository(private val context: Context, private val iconFactory: IconFactory, var iconSizePx: Int) {

    /** One UI "theme icons" (colour palette icons). */
    @Volatile var themedIcons = false
    /** Also theme apps without a monochrome layer (tonal recolour, see IconFactory.createTonal). */
    @Volatile var themeAllApps = true
    /** Dark icons for every app (IconFactory.createDark). */
    @Volatile var darkIcons = false
    /** Minimal icons: the themed glyph alone (IconFactory.homeIcon). */
    @Volatile var minimalIcons = false
    /** Dot, Prism or Stencil icons (IconFactory.homeIcon's look), or empty. */
    @Volatile var iconLook = ""
    /** Light or dark for Dot and Prism (IconFactory.MODE_*). */
    @Volatile var iconMode = com.oneuiclone.home.icons.IconFactory.MODE_AUTO
    /** The icon shape the icons were last cut to (IconFactory reads IconShape.current as it draws). */
    @Volatile var iconShape = com.oneuiclone.home.icons.IconShape.SQUIRCLE
    /** Whether Comic icons were last drawn with their ink border (IconFactory reads IconFactory.comicBorder as it draws). */
    @Volatile var comicBorder = true
    /** Whether the app's own icons were last drawn with their shine (IconFactory reads IconFactory.iconShine as it draws). */
    @Volatile var iconShine = true
    /** The Themed / Frosted colour the icons were last drawn in (IconFactory reads IconFactory.themeColor as it draws; 0 = palette). */
    @Volatile var themeColor = 0
    /** Glass icons: how opaque a themed icon's plate is (1 = solid). */
    @Volatile var plateAlpha = 1f

    interface Listener { fun onAppsChanged(apps: List<AppInfo>) }

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "app-loader").apply { priority = Thread.NORM_PRIORITY } }
    private val listeners = mutableListOf<Listener>()

    @Volatile var apps: List<AppInfo> = emptyList(); private set
    private val byKey = HashMap<AppKey, AppInfo>()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = reload()
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = reload()
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = reload()
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reload()
    }

    init {
        launcherApps.registerCallback(callback, main)
    }

    fun addListener(l: Listener) { listeners += l; if (apps.isNotEmpty()) l.onAppsChanged(apps) }

    fun get(key: AppKey): AppInfo? = synchronized(byKey) { byKey[key] }

    fun serialOf(user: UserHandle) = userManager.getSerialNumberForUser(user)

    fun findByPackage(pkg: String, user: UserHandle = Process.myUserHandle()): AppInfo? =
        apps.firstOrNull { it.key.component.packageName == pkg && it.user == user }

    /** Where an app missing from [apps] is (see [whereIs]). */
    sealed class Missing
    /** Uninstalled, or its profile is gone. */
    object Gone : Missing()
    /** Still installed, under another activity (an update renamed it). */
    class Moved(val key: AppKey) : Missing()
    /** Still installed but not listed for now: being updated, disabled, or its profile paused or locked. */
    object Away : Missing()

    fun whereIs(key: AppKey): Missing {
        val user = userManager.getUserForSerialNumber(key.userSerial) ?: return Gone
        if (!userManager.isUserUnlocked(user) || userManager.isQuietModeEnabled(user)) return Away
        val pkg = key.component.packageName
        val installed = try { launcherApps.getApplicationInfo(pkg, 0, user) != null }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
            catch (_: Exception) { return Away }
        if (!installed) return Gone
        return findByPackage(pkg, user)?.let { Moved(it.key) } ?: Away
    }

    /**
     * [force]: re-render every icon. It belongs to this reload, so one already running (some of its icons drawn with the
     * old settings) can't use up a re-render queued behind it, which would then keep those icons.
     */
    fun reload(night: Boolean = iconFactory.isNight(), force: Boolean = false) {
        val size = iconSizePx
        worker.execute {
            val collator = Collator.getInstance()
            val result = ArrayList<AppInfo>()
            val old = synchronized(byKey) { HashMap(byKey) }
            for (user in userManager.userProfiles) {
                val serial = userManager.getSerialNumberForUser(user)
                for (info in launcherApps.getActivityList(null, user)) {
                    if (info.componentName.packageName == context.packageName) continue
                    val key = AppKey(info.componentName, serial)
                    val prev = old[key]
                    val pkg = info.componentName.packageName
                    val kept = prev != null && prev.icon.width == size && prev.label == info.label.toString() && !force
                    var preview: Bitmap? = null
                    val (icon, plain) = if (kept) {
                        preview = prev!!.previewIcon
                        prev.icon to prev.drawerIcon
                    } else {
                        val raw = info.getIcon(context.resources.displayMetrics.densityDpi)
                        val d = if (user == Process.myUserHandle()) raw else context.packageManager.getUserBadgedIcon(raw, user)
                        // the see-through themes' folder previews: the symbol alone (see AppInfo.previewIcon)
                        preview = when {
                            !themedIcons || darkIcons || minimalIcons -> null
                            iconLook == com.oneuiclone.home.icons.IconFactory.LOOK_CLEAR_COLOUR -> iconFactory.homeIcon(d, size, night, true, themeAllApps, 1f,
                                false, pkg, false, com.oneuiclone.home.icons.IconFactory.LOOK_MINIMAL_COLOUR, iconMode).first
                            iconLook.isEmpty() && plateAlpha < 1f -> iconFactory.homeIcon(d, size, night, true, themeAllApps, 1f, false, pkg, true, "", iconMode).first
                            else -> null
                        }?.let { iconFactory.withContour(it) }      // a faint edge round each app in the folder, no plate
                        iconFactory.homeIcon(d, size, night, themedIcons, themeAllApps, plateAlpha, darkIcons, pkg, minimalIcons, iconLook, iconMode)
                    }
                    result += AppInfo(key, user, info.label.toString(), icon, info, plain, preview ?: icon)
                }
            }
            result.sortWith { a, b -> collator.compare(a.label, b.label) }
            synchronized(byKey) {
                byKey.clear()
                result.forEach { byKey[it.key] = it }
            }
            main.post {
                apps = result
                listeners.forEach { it.onAppsChanged(result) }
            }
        }
    }

    /** Re-render all icons (size, dark mode change). */
    fun invalidateIcons(newSize: Int) {
        iconSizePx = newSize
        reload(force = true)
    }

    fun destroy() {
        launcherApps.unregisterCallback(callback)
        worker.shutdownNow()
    }
}
