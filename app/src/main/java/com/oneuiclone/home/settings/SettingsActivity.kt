package com.oneuiclone.home.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.oneuiclone.home.AppBubbles
import com.oneuiclone.home.BadgeService
import com.oneuiclone.home.ui.Fonts

/**
 * One UI-style "Home screen settings": extended title, rounded cards (26dp), 17sp titles with
 * blue functional summaries, switches tinted with the One UI primary colour.
 */
class SettingsActivity : OneUiPage() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toSetupIfNotHome()
    }

    override fun onResume() {
        super.onResume()
        toSetupIfNotHome()   // the drawer can bring this task back without a new onCreate
        if (rebuildOnResume) { rebuildOnResume = false; build() }
    }

    /** Set when leaving for the live wallpaper preview, whose outcome only shows once we're back. */
    private var rebuildOnResume = false

    /**
     * Opened from another launcher's app list while Launchii isn't Home: setup instead, which asks for Home along with
     * everything else, every time (not just the first).
     */
    private fun toSetupIfNotHome() {
        if (isFinishing || !intent.hasCategory(Intent.CATEGORY_LAUNCHER) || SetupActivity.isHome(this)) return
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    override fun build() {
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(24f)) }
        // extended app bar (≈ 30 % of the screen, title centred)
        list.addView(TextView(this).apply {
            text = "Home screen"
            setTextColor(this@SettingsActivity.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Fonts.ui(this@SettingsActivity, 400)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.30f).toInt()))

        card {
            item("Home screen grid", "${prefs.homeCols}×${prefs.homeRows}") {
                choose("Home screen grid", listOf(4 to 5, 4 to 6, 5 to 5, 5 to 6)) { (c, r) -> prefs.homeCols = c; prefs.homeRows = r }
            }
            item("Apps screen grid", "${prefs.appsCols}×${prefs.appsRows}") {
                choose("Apps screen grid", listOf(4 to 5, 4 to 6, 5 to 5, 5 to 6)) { (c, r) -> prefs.appsCols = c; prefs.appsRows = r }
            }
            item("Wallpaper and style", "Wallpaper, blur and colour palette, with a preview") {
                startActivity(Intent(this@SettingsActivity, WallpaperStyleActivity::class.java))
            }
            item("Icons", "Icon shape, theme, size and labels") {
                startActivity(Intent(this@SettingsActivity, IconThemeActivity::class.java))
            }
            toggle("Hide search bar and page indicators", "Remove the Search button and page dots from the Home screen.", prefs.hideSearchRow) { prefs.hideSearchRow = it }
            toggle("Pop-up folders", "Open folders in a small window over the Home screen instead of full screen.", prefs.popupFolders) { prefs.popupFolders = it }
            val bubblesMissing = AppBubbles.missing(this@SettingsActivity)
            toggle("App bubbles", if (prefs.appBubbles && bubblesMissing != null) bubblesMissing
                else "Touch and hold an app, then tap Bubble to open it in a floating window.", prefs.appBubbles) {
                prefs.appBubbles = it
                if (it && bubblesMissing != null) AppBubbles.setUp(this@SettingsActivity)
            }
        }
        card {
            toggle("Lock Home screen layout", "Prevent items on the Home screen from being removed or repositioned.", prefs.lockLayout) { prefs.lockLayout = it }
            toggle("Add new apps to Home screen", null, prefs.addNewApps) { prefs.addNewApps = it }
            // Swipe down for search takes over both other swipes, so turning it on turns them off (and either of them, it)
            lateinit var downNotif: Switch
            lateinit var downSearch: Switch
            lateinit var upSearch: Switch
            downNotif = toggle("Swipe down for notification panel", "Open the notification panel by swiping down anywhere on the Home screen.",
                prefs.swipeDownNotifications && !prefs.swipeDownSearch) {
                prefs.swipeDownNotifications = it
                if (it) downSearch.isChecked = false
            }.switch()
            downSearch = toggle("Swipe down for search", "Open search by swiping down anywhere on the Home screen. Swiping up always opens the Apps screen.",
                prefs.swipeDownSearch) {
                prefs.swipeDownSearch = it
                if (it) { downNotif.isChecked = false; upSearch.isChecked = false }
            }.switch()
            toggle("Web suggestions in search", "Show Google suggestions as you type. What you type is sent to Google.", prefs.webSuggestions) { prefs.webSuggestions = it }
            upSearch = toggle("Swipe up for search", "Open search instead of the Apps screen by swiping up on the Home screen.",
                prefs.swipeUpSearch && !prefs.swipeDownSearch) {
                prefs.swipeUpSearch = it
                if (it) downSearch.isChecked = false
            }.switch()
            val openStyles = listOf(Prefs.OPEN_SMOOTH to "Smooth", Prefs.OPEN_ZOOM_IN to "Zoom In", Prefs.OPEN_AURA to "Aura",
                Prefs.OPEN_SHUTTER to "Shutter", Prefs.OPEN_ORBIT to "Orbit", Prefs.OPEN_ANTICIPATE to "Anticipate", Prefs.OPEN_SCAN to "Scan", Prefs.OPEN_MIST to "Mist")
            item("App opening animation", openStyles.first { it.first == prefs.openAnimation }.second) {
                AlertDialog.Builder(this@SettingsActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("App opening animation")
                    .setSingleChoiceItems(openStyles.map { it.second }.toTypedArray(), openStyles.indexOfFirst { it.first == prefs.openAnimation }) { d, i ->
                        prefs.openAnimation = openStyles[i].first
                        d.dismiss()
                        build()
                    }
                    .show()
            }
        }
        card {
            val granted = BadgeService.isEnabled(this@SettingsActivity)
            toggle("App icon badges", if (granted) (if (prefs.badgeNumbers) "Show with number" else "Show without number") else "Allow notification access to show badges", prefs.badges && granted) {
                prefs.badges = it
                if (it && !granted) startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this@SettingsActivity, BadgeService::class.java).flattenToString()))
            }
            toggle("Show number on badges", null, prefs.badgeNumbers) { prefs.badgeNumbers = it }
        }
        card {
            item("Set up ${getString(com.oneuiclone.home.R.string.app_name)}", "Permissions and your wallpaper, all in one place") {
                startActivity(Intent(this@SettingsActivity, SetupActivity::class.java))
            }
            val ok = com.oneuiclone.home.ui.WallpaperLayer.hasOwnCopy(this@SettingsActivity)
            item("Wallpaper effects", if (ok) "Blur and zoom use your wallpaper"
                else "For blur and zoom, set your wallpaper with Set as wallpaper in your photos app, or from Home screen edit mode › Wallpaper and style › Choose a photo") {}
            liveWallpaperItem(ok)
            val usage = com.oneuiclone.home.model.AppUsage(this@SettingsActivity).systemAllowed
            item("Most used apps in search", if (usage) "Ranked by how often you open each app"
                else "Allow usage access so search knows the apps you open most, wherever you open them from, and split-screen apps close cleanly") {
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
            item("Default Home app", "Choose ${getString(com.oneuiclone.home.R.string.app_name)} as your Home app") {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            }
        }
        card {
            item("About Home screen", "${getString(com.oneuiclone.home.R.string.app_name)} · ${packageManager.getPackageInfo(packageName, 0).versionName}") {}
            item("Open source licences", "Inter typeface") {
                AlertDialog.Builder(this@SettingsActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Inter typeface")
                    .setMessage(assets.open("licenses/Inter-OFL.txt").bufferedReader().use { it.readText() })
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
        developerCard()
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(list); isVerticalScrollBarEnabled = false })
    }

    /**
     * Launchii's live wallpaper: the same picture, drawn by Launchii so Home can zoom the real wallpaper on app opens
     * and closes. Wallpapers set in Launchii go on as it until it's turned off here, which puts the picture back as a
     * normal image wallpaper.
     */
    private fun liveWallpaperItem(hasCopy: Boolean) {
        val lw = com.oneuiclone.home.ui.LiveWallpaper
        val live = lw.isActive(this)
        item("Live wallpaper", when {
            live -> "On: your wallpaper zooms when apps open and close. Tap to turn off"
            hasCopy -> "Off. Tap to turn on, so your wallpaper zooms when apps open and close"
            else -> "Set a wallpaper from ${getString(com.oneuiclone.home.R.string.app_name)}, as above, and it zooms when apps open and close"
        }) {
            when {
                live -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Turn off live wallpaper?")
                    .setMessage("Your wallpaper stays the same picture, as a normal wallpaper, and it won't zoom when apps open and close.")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Turn off") { _, _ ->
                        val app = applicationContext
                        lw.setWanted(app, false)
                        Thread {
                            val ok = try { lw.turnOff(app) } catch (_: Exception) { false }
                            runOnUiThread {
                                if (!ok) android.widget.Toast.makeText(app, "Couldn't turn it off", android.widget.Toast.LENGTH_SHORT).show()
                                if (!isDestroyed) build()
                            }
                        }.start()
                    }
                    .show()
                hasCopy -> try {
                    lw.setWanted(this, true)
                    rebuildOnResume = true
                    startActivity(lw.pickerIntent(this))
                } catch (_: android.content.ActivityNotFoundException) {
                    rebuildOnResume = false
                    android.widget.Toast.makeText(this, "This phone has no live wallpaper picker", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** A [toggle] row's switch (its last child). */
    private fun LinearLayout.switch() = getChildAt(childCount - 1) as Switch

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AppBubbles.REQUEST_NOTIFICATIONS) {
            AppBubbles.onPermissionResult(this, grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
            build()
        }
    }
}
