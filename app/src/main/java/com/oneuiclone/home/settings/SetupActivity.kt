package com.oneuiclone.home.settings

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.PathParser
import androidx.core.view.doOnLayout
import com.oneuiclone.home.AppBubbles
import com.oneuiclone.home.BadgeService
import com.oneuiclone.home.R
import com.oneuiclone.home.WallpaperActivity
import com.oneuiclone.home.model.AppUsage
import com.oneuiclone.home.ui.Fonts
import com.oneuiclone.home.ui.WallpaperLayer

/**
 * First-run setup: every permission the launcher's features need and setting the wallpaper again (blur and zoom
 * only work on a wallpaper the launcher set itself, see WallpaperLayer.hasOwnCopy), each a row with its own Allow
 * button that turns into a tick once done. Shown once on the first Home, on every open from another launcher's app
 * list while Launchii isn't the Home app, and from Settings › Set up Launchii.
 */
class SetupActivity : OneUiPage() {

    companion object {
        private const val REQUEST_HOME_ROLE = 51
        private const val REQUEST_PHOTO = 52
        const val REQUEST_CONTACTS = 53

        /**
         * Set while the Home role dialog is up: granting it starts Home straight away (the permission controller
         * does), so Home's [resumeAfterHome] brings setup back over it.
         */
        private var awaitingHome = false

        /** Called by each Home resume: setup back in front if it was waiting on the Home role. */
        fun resumeAfterHome(context: Context) {
            if (!awaitingHome) return
            awaitingHome = false
            context.startActivity(Intent(context, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }

        /** Launchii is the default Home app. */
        fun isHome(context: Context): Boolean {
            val roles = context.getSystemService(RoleManager::class.java)
            return roles.isRoleAvailable(RoleManager.ROLE_HOME) && roles.isRoleHeld(RoleManager.ROLE_HOME)
        }

        fun contactsAllowed(context: Context) =
            context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        /**
         * The Contacts permission: the system dialog until it's been turned down for good, then the app's settings
         * page (setup, and Settings › Search and Permissions & setup). The result comes back as [REQUEST_CONTACTS].
         */
        fun askContacts(activity: android.app.Activity) {
            val permission = android.Manifest.permission.READ_CONTACTS
            val sp = Prefs(activity).sp
            if (!sp.getBoolean("asked_contacts", false) || activity.shouldShowRequestPermissionRationale(permission)) {
                sp.edit().putBoolean("asked_contacts", true).apply()
                activity.requestPermissions(arrayOf(permission), REQUEST_CONTACTS)
            } else {
                try {
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
                } catch (_: Exception) {}
            }
        }

        /** Opens setup the first time only; true if it did. */
        fun openOnce(context: Context): Boolean {
            if (Prefs(context).setupShown) return false
            context.startActivity(Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
    }

    private class Step(val title: String, val summary: String, val glyph: String, val color: Int,
                       val done: () -> Boolean, val action: String, val run: () -> Unit)

    private val appName get() = getString(R.string.app_name)

    private val steps by lazy {
        listOf(
            Step("Make $appName your Home app", "So Home and the home gesture bring you here.", GLYPH_HOME, 0xFF7C4DFF.toInt(),
                ::isHome, "Set") { askHome() },
            Step("Set your wallpaper again", "Blur and zoom need $appName to set your wallpaper. Choose your photo once more.",
                GLYPH_WALLPAPER, 0xFFE8507D.toInt(), { WallpaperLayer.hasOwnCopy(this) }, "Choose") { pickPhoto() },
            Step("Notifications and bubbles", "Open apps in floating bubbles from their touch and hold menu.",
                GLYPH_BUBBLE, 0xFFF5923E.toInt(), { AppBubbles.ready(this) }, "Allow") {
                prefs.appBubbles = true
                AppBubbles.setUp(this)
            },
            Step("Notification access", "Shows notification badges on app icons.", GLYPH_BADGE, 0xFFEF5350.toInt(),
                { BadgeService.isEnabled(this) }, "Allow") {
                prefs.badges = true
                open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(this, BadgeService::class.java).flattenToString()), Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            Step("Usage access", "Search puts the apps you open most first, and split screen closes cleanly.", GLYPH_USAGE,
                0xFF34B36B.toInt(), { AppUsage(this).systemAllowed }, "Allow") {
                open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            },
            Step("Contacts", "Find people from search.", GLYPH_CONTACTS, 0xFF3E91FF.toInt(),
                { contactsAllowed(this) }, "Allow") { askContacts() },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs.setupShown = true
    }

    /** Rebuilt on every resume (back from each system page): kept at the same spot. */
    private var scroll: ScrollView? = null
    /** Where the last build has yet to scroll to (two builds can run before the first lays out). */
    private var pendingScroll: Int? = null

    override fun build() {
        val scrolledTo = pendingScroll ?: scroll?.scrollY ?: 0
        pendingScroll = scrolledTo
        val done = steps.count { it.done() }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), dp(40f), dp(12f), dp(24f)) }

        list.addView(ImageView(this).apply { setImageDrawable(getDrawable(R.drawable.ic_launcher)) },
            LinearLayout.LayoutParams(dp(76f), dp(76f)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        list.addView(TextView(this).apply {
            text = "Welcome to $appName"
            setTextColor(this@SetupActivity.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Fonts.ui(this@SetupActivity, 600)
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(20f), dp(12f), 0)
        })
        list.addView(TextView(this).apply {
            text = "Allow these so every feature works. You can change them later in Settings."
            setTextColor(sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Fonts.ui(this@SetupActivity, 400)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setPadding(dp(28f), dp(10f), dp(28f), dp(28f))
        })

        card {
            header("Get started")
            steps.take(2).forEach { stepRow(it) }
        }
        card {
            header("Permissions")
            steps.drop(2).forEach { stepRow(it) }
        }
        list.addView(TextView(this).apply {
            text = "$done of ${steps.size} done"
            setTextColor(sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Fonts.ui(this@SetupActivity, 500)
            gravity = Gravity.CENTER
        })

        // the button bar stays at the bottom over the scrolling list
        val next = steps.firstOrNull { !it.done() }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(24f), dp(12f), dp(24f), dp(12f))
        }
        bar.addView(TextView(this).apply {
            text = if (next == null) "Done" else "Continue"
            setTextColor(onPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Fonts.ui(this@SetupActivity, 600)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply { cornerRadius = 100f * d; setColor(primary) }, null)
            setOnClickListener { next?.run?.invoke() ?: finishSetup() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54f)))
        if (next != null) bar.addView(TextView(this).apply {
            text = "Not now"
            setTextColor(primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Fonts.ui(this@SetupActivity, 600)
            gravity = Gravity.CENTER
            background = ripple()
            setOnClickListener { finishSetup() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46f)).apply { topMargin = dp(4f) })

        val scroll = ScrollView(this).apply { addView(list); isVerticalScrollBarEnabled = false; clipToPadding = false }
        this.scroll = scroll
        scroll.doOnLayout {
            scroll.scrollTo(0, scrolledTo)
            if (this.scroll === scroll) pendingScroll = null
        }
        // the list scrolls under the status bar; this keeps it out from behind the clock
        val statusScrim = View(this).apply { setBackgroundColor(bg) }
        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        root.addView(scroll)
        root.addView(statusScrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            statusScrim.layoutParams.height = bars.top
            bar.setPadding(dp(24f), dp(12f), dp(24f), dp(12f) + bars.bottom)
            bar.measure(View.MeasureSpec.makeMeasureSpec(root.width.coerceAtLeast(1), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            scroll.setPadding(bars.left, bars.top, bars.right, bar.measuredHeight)
            insets
        }
        setContentView(root)
    }

    /** A step: its coloured glyph, title and why, then its button, or a tick once done. */
    private fun stepRow(step: Step) {
        val done = step.done()
        val icon = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(step.color) }
            foreground = Glyph(step.glyph, Color.WHITE, dp(22f))
        }
        val trailing: View = if (done) View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF34B36B.toInt()) }
            foreground = Glyph(GLYPH_CHECK, Color.WHITE, dp(18f))
            layoutParams = LinearLayout.LayoutParams(dp(28f), dp(28f)).apply { marginStart = dp(12f); marginEnd = dp(8f) }
        } else TextView(this).apply {
            text = step.action
            setTextColor(primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Fonts.ui(this@SetupActivity, 600)
            gravity = Gravity.CENTER
            setPadding(dp(16f), 0, dp(16f), 0)
            background = GradientDrawable().apply { cornerRadius = 100f * d; setColor(primarySoft) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34f)).apply { marginStart = dp(12f) }
            minWidth = dp(84f)
            isClickable = false
        }
        val r = row(step.title, step.summary, sub, trailing, if (done) null else step.run, titleWeight = 500)
        r.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        r.addView(icon, 0, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { marginEnd = dp(16f) })
    }

    private fun isHome() = isHome(this)

    private fun askHome() {
        val roles = getSystemService(RoleManager::class.java)
        @Suppress("DEPRECATION")
        if (roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
            awaitingHome = true
            startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME_ROLE)
        } else open(Intent(Settings.ACTION_HOME_SETTINGS), null)
    }

    private fun pickPhoto() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), REQUEST_PHOTO)
        } catch (_: Exception) {
            Toast.makeText(this, "No photo picker available", Toast.LENGTH_SHORT).show()
        }
    }

    /** The system dialog until it's been turned down for good, then the app's settings page. */
    private fun askContacts() = askContacts(this)

    private fun open(intent: Intent, fallback: Intent?) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            fallback?.let { try { startActivity(it) } catch (_: Exception) {} }
        }
    }

    /** Home if it's ours (so setup ends where the user starts), else back to wherever they came from. */
    private fun finishSetup() {
        if (isHome()) startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_HOME_ROLE && resultCode != RESULT_OK) awaitingHome = false
        val uri = data?.data
        // positioned and set there, then back here (build runs on resume and ticks the row)
        if (requestCode == REQUEST_PHOTO && resultCode == RESULT_OK && uri != null) {
            startActivity(Intent(this, WallpaperActivity::class.java).setData(uri)
                .putExtra(WallpaperActivity.EXTRA_STAY, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AppBubbles.REQUEST_NOTIFICATIONS)
            AppBubbles.onPermissionResult(this, grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
        build()
    }

    /** A 24-unit Material-style path, filled and centred at [sizePx] in its view. */
    private class Glyph(pathData: String, color: Int, private val sizePx: Int) : Drawable() {
        private val src: Path = PathParser.createPathFromPathData(pathData)
        private val path = Path()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        override fun onBoundsChange(bounds: android.graphics.Rect) {
            val s = sizePx / 24f
            path.set(src)
            path.transform(Matrix().apply { setScale(s, s); postTranslate(bounds.exactCenterX() - sizePx / 2f, bounds.exactCenterY() - sizePx / 2f) })
        }
        override fun draw(canvas: Canvas) = canvas.drawPath(path, p)
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

internal const val GLYPH_HOME = "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z"
internal const val GLYPH_WALLPAPER = "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z"
internal const val GLYPH_BUBBLE = "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z"
/** An app tile with a badge dot nestled in its corner. */
internal const val GLYPH_BADGE = "M18.5,1.5a4,4 0,1 1,0 8a4,4 0,1 1,0 -8zM13,5H6c-1.1,0 -2,0.9 -2,2v11c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2v-7A6,6 0,0 1,13 5z"
internal const val GLYPH_USAGE = "M5,9.2h3V19H5zM10.6,5h2.8v14h-2.8zM16.2,13H19v6h-2.8z"
internal const val GLYPH_CONTACTS = "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z"
internal const val GLYPH_CHECK = "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z"
