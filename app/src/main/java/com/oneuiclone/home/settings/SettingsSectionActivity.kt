package com.oneuiclone.home.settings

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.oneuiclone.home.AppBubbles
import com.oneuiclone.home.AppUpdate
import com.oneuiclone.home.BadgeService
import com.oneuiclone.home.R
import com.oneuiclone.home.WallpaperActivity
import com.oneuiclone.home.icons.IconShape
import com.oneuiclone.home.ui.Fonts
import com.oneuiclone.home.ui.FolderIconView

/**
 * The Settings hub's sections (0.263, the approved "Settings hub" mockup): Layout, Gestures, Search, Folders & widgets,
 * Badges & bubbles, Permissions & setup, Backup & advanced and About, one per [EXTRA_SECTION]. The rows and what they do
 * are the old Settings page's, moved here (Motion is [OpeningAnimationActivity], Wallpaper [WallpaperStyleActivity] and
 * Icons [IconThemeActivity]). Rebuilt on every resume, at the same scroll position.
 */
class SettingsSectionActivity : OneUiPage() {

    companion object {
        const val EXTRA_SECTION = "app.launchii.settings.SECTION"
        const val LAYOUT = "layout"
        const val GESTURES = "gestures"
        const val SEARCH = "search"
        const val FOLDERS = "folders"
        const val BADGES = "badges"
        const val PERMISSIONS = "permissions"
        const val BACKUP = "backup"
        const val ABOUT = "about"
        private const val REQUEST_PHOTO = 61

        fun intent(context: Context, section: String): Intent =
            Intent(context, SettingsSectionActivity::class.java).putExtra(EXTRA_SECTION, section)

        fun title(context: Context, section: String) = when (section) {
            LAYOUT -> "Layout"
            GESTURES -> "Gestures"
            SEARCH -> "Search"
            FOLDERS -> "Folders & widgets"
            BADGES -> "Badges & bubbles"
            PERMISSIONS -> "Permissions & setup"
            BACKUP -> "Backup & advanced"
            else -> "About"
        }

        /** The six setup steps' state, in setup's order (Home app, wallpaper, bubbles, notification access, usage, contacts). */
        fun permissions(context: Context): List<Boolean> = listOf(
            SetupActivity.isHome(context),
            com.oneuiclone.home.ui.WallpaperLayer.hasOwnCopy(context),
            AppBubbles.ready(context),
            BadgeService.isEnabled(context),
            com.oneuiclone.home.model.AppUsage(context).systemAllowed,
            SetupActivity.contactsAllowed(context),
        )
    }

    private val section get() = intent.getStringExtra(EXTRA_SECTION) ?: ABOUT
    private val appName get() = getString(R.string.app_name)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUpdate.init(this)
    }

    override fun onResume() {
        super.onResume()
        if (section == ABOUT) AppUpdate.onResumed(this)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    override fun build() {
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(24f)) }
        appBar(title(this, section))
        when (section) {
            LAYOUT -> layoutPage()
            GESTURES -> gesturesPage()
            SEARCH -> searchPage()
            FOLDERS -> foldersPage()
            BADGES -> badgesPage()
            PERMISSIONS -> permissionsPage()
            BACKUP -> backupPage()
            else -> aboutPage()
        }
        setPage()
    }

    private fun open(section: String, highlight: String? = null) =
        startActivity(intent(this, section).apply { highlight?.let { putExtra(EXTRA_HIGHLIGHT, it) } })

    /** Starts [intent], else [fallback], quietly if neither opens. */
    private fun tryStart(intent: Intent, fallback: Intent? = null) {
        try { startActivity(intent) } catch (_: Exception) { fallback?.let { try { startActivity(it) } catch (_: Exception) {} } }
    }

    /** A tick in the accent, for a status row that's done. */
    private fun tick() = ImageView(this).apply {
        setImageDrawable(GlyphDrawable(Glyphs.CHECK, primary, dp(24f)))
        layoutParams = LinearLayout.LayoutParams(dp(28f), dp(28f)).apply { marginStart = dp(12f) }
    }

    /** A status row: a tick when [ok], else an Allow pill that runs [fix]; tapping the row runs [onTap] (or [fix]). */
    private fun statusRow(title: String, summary: String, ok: Boolean, action: String = "Allow", fix: () -> Unit, onTap: (() -> Unit)? = null): LinearLayout {
        val trailing = if (ok) tick() else pill(action, ghost = true)
        return withIcon(row(title, summary, sub, trailing, if (ok) onTap else { { fix() } }), if (ok) Glyphs.CHECK else Glyphs.INFO)
    }

    // ---------------------------------------------------------------- Layout

    private val grids = listOf(4 to 5, 4 to 6, 5 to 5, 5 to 6)

    /** A phone outline with a [cols]×[rows] grid of app spots. */
    private fun drawGrid(c: Canvas, w: Float, h: Float, cols: Int, rows: Int, on: Boolean) {
        val ink = if (on) primary else sub
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d; color = ink }
        val inset = p.strokeWidth
        c.drawRoundRect(RectF(inset, inset, w - inset, h - inset), 9f * d, 9f * d, p)
        p.style = Paint.Style.FILL
        p.alpha = if (on) 255 else 150
        val padX = w * 0.16f; val top = h * 0.12f; val bottom = h * 0.12f
        val cw = (w - padX * 2) / cols; val ch = (h - top - bottom) / rows
        val s = minOf(cw, ch) * 0.56f
        for (y in 0 until rows) for (x in 0 until cols) {
            val cx = padX + cw * (x + 0.5f); val cy = top + ch * (y + 0.5f)
            c.drawRoundRect(RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2), s * 0.32f, s * 0.32f, p)
        }
    }

    private fun gridTiles(title: String, cols: Int, rows: Int, onPick: (Pair<Int, Int>) -> Unit) {
        groupHeader(title, "$cols×$rows")
        card {
            pictureTiles(grids.map { "${it.first}×${it.second}" }, grids.indexOf(cols to rows), 46f, 78f,
                draw = { i, c, w, h, on -> drawGrid(c, w, h, grids[i].first, grids[i].second, on) }) { i -> onPick(grids[i]); build() }
            current?.let { register(title, it) }
        }
    }

    private fun layoutPage() {
        gridTiles("Home screen grid", prefs.homeCols, prefs.homeRows) { (c, r) -> prefs.homeCols = c; prefs.homeRows = r }
        gridTiles("Apps screen grid", prefs.appsCols, prefs.appsRows) { (c, r) -> prefs.appsCols = c; prefs.appsRows = r }
        groupHeader("Home screen")
        card {
            toggle("Hide search bar and page indicators", "Remove the Search button and page dots from the Home screen.", prefs.hideSearchRow) { prefs.hideSearchRow = it }
            toggle("Add new apps to Home screen", null, prefs.addNewApps) { prefs.addNewApps = it }
            toggle("Lock Home screen layout", "Prevent items on the Home screen from being removed or repositioned.", prefs.lockLayout) { prefs.lockLayout = it }
        }
        groupHeader("Apps screen")
        card {
            toggle("Letter rail", "Slide along the letters at the lower right of the Apps screen to jump to apps.", prefs.appsLetterRail) { prefs.appsLetterRail = it }
        }
        card { withIcon(linkRow("Restore an earlier layout", "In Backup & advanced") { open(BACKUP, "Restore Home screen layout") }, Glyphs.RESTORE) }
    }

    // ---------------------------------------------------------------- Gestures

    /**
     * Swipe down: Notification panel, Search or Nothing; swipe up: Apps screen or Search. The same five combinations
     * as the three old switches (Swipe down for search took over both others, so up = Search is greyed then), saved
     * to the same keys as those switches did.
     */
    private fun gesturesPage() {
        list.addView(TextView(this).apply {
            text = "What swiping on an empty part of your Home screen does."
            setTextColor(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Fonts.ui(this@SettingsSectionActivity, 400)
            setPadding(dp(20f), 0, dp(20f), dp(14f))
        })
        val down = when { prefs.swipeDownSearch -> 1; prefs.swipeDownNotifications -> 0; else -> 2 }
        val up = if (!prefs.swipeDownSearch && prefs.swipeUpSearch) 1 else 0
        groupHeader("Swipe down")
        card {
            val glyphs = listOf(Glyphs.BELL, Glyphs.SEARCH, Glyphs.BLOCK)
            pictureTiles(listOf("Notification panel", "Search", "Nothing"), down, 52f, 92f,
                draw = { i, c, w, h, on -> drawSwipe(c, w, h, glyphs[i], down = true, on = on) }) { i ->
                when (i) {
                    0 -> { prefs.swipeDownNotifications = true; prefs.swipeDownSearch = false }
                    // as the old switch: search on swipe down turns the other two off
                    1 -> { prefs.swipeDownSearch = true; prefs.swipeDownNotifications = false; prefs.swipeUpSearch = false }
                    else -> { prefs.swipeDownNotifications = false; prefs.swipeDownSearch = false }
                }
                build()
            }
            current?.let { register("Swipe down", it) }
        }
        groupHeader("Swipe up")
        card {
            val glyphs = listOf(Glyphs.APPS, Glyphs.SEARCH)
            // Search is offered only while swipe down doesn't open search (then swipe up always opens the Apps screen)
            val strip = pictureTiles(listOf("Apps screen", "Search"), up, 52f, 92f,
                draw = { i, c, w, h, on -> drawSwipe(c, w, h, glyphs[i], down = false, on = on) }) { i ->
                prefs.swipeUpSearch = i == 1
                if (i == 1) prefs.swipeDownSearch = false
                build()
            }
            if (prefs.swipeDownSearch) strip.getChildAt(1).apply { alpha = 0.4f; isEnabled = false; setOnClickListener(null); isClickable = false }
            current?.let { register("Swipe up", it) }
            current?.addView(TextView(this).apply {
                text = "When swipe down opens search, swipe up always opens the Apps screen."
                setTextColor(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Fonts.ui(this@SettingsSectionActivity, 400)
                setPadding(dp(20f), 0, dp(20f), dp(14f))
            })
        }
    }

    /** A little phone with [glyph] in it and an arrow for the swipe, above it (down) or below it (up). */
    private fun drawSwipe(c: Canvas, w: Float, h: Float, glyph: String, down: Boolean, on: Boolean) {
        val ink = if (on) primary else sub
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d; color = ink; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val arrow = h * 0.2f
        val top = if (down) arrow else p.strokeWidth
        val bottom = if (down) h - p.strokeWidth else h - arrow
        c.drawRoundRect(RectF(p.strokeWidth, top, w - p.strokeWidth, bottom), 9f * d, 9f * d, p)
        val g = GlyphDrawable(glyph, ink, dp(24f))
        g.setBounds(0, top.toInt(), w.toInt(), bottom.toInt())
        g.draw(c)
        val cx = w / 2f
        val a0 = if (down) 0f else h
        val a1 = if (down) arrow * 0.85f else h - arrow * 0.85f
        c.drawLine(cx, a0 + (if (down) 1 else -1) * p.strokeWidth, cx, a1, p)
        val head = 4f * d
        val dir = if (down) -1 else 1
        c.drawLine(cx, a1, cx - head, a1 + dir * head, p)
        c.drawLine(cx, a1, cx + head, a1 + dir * head, p)
    }

    // ---------------------------------------------------------------- Search

    private fun searchPage() {
        groupHeader("Results")
        card {
            toggle("Web suggestions", "Show Google suggestions as you type. What you type is sent to Google.", prefs.webSuggestions) { prefs.webSuggestions = it }
            val usage = com.oneuiclone.home.model.AppUsage(this).systemAllowed
            linkRow("Most used apps first", if (usage) "Allowed · ranked by how often you open each app, wherever you open them from"
                else "Allow usage access so search knows the apps you open most, wherever you open them from, and split-screen apps close cleanly") {
                openUsageAccess()
            }
            val contacts = SetupActivity.contactsAllowed(this)
            row("Contacts in search", if (contacts) "Allowed · find people from search" else "Not allowed yet · find people from search", primary,
                if (contacts) tick() else pill("Allow", ghost = true), {
                if (contacts) openAppDetails() else SetupActivity.askContacts(this)
            })
        }
        groupHeader("Keyboard")
        card {
            // the search bar's learned keyboard spot (0.230): shown, and re-learned on request
            val spot = com.oneuiclone.home.ui.KeyboardSpot(this)
            val kb = spot.describe()
            val relearn = {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Re-learn keyboard height?")
                    .setMessage("Search forgets where your keyboard sits and learns it again the next time you open it.")
                    .setPositiveButton("Re-learn") { _, _ -> spot.relearn(); build() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                Unit
            }
            row("Search keyboard height", kb?.let { (name, dp, seen) -> "Learned from $name · $dp dp · seen $seen ${if (seen == 1) "time" else "times"}" }
                ?: "Learned the next time you open search", primary,
                if (kb != null) pill("Re-learn", ghost = true) { relearn() } else null, { if (kb != null) relearn() })
        }
        groupHeader("Ways to open it")
        card {
            val swipe = when {
                prefs.swipeDownSearch -> "Swipe down"
                prefs.swipeUpSearch -> "Swipe up"
                else -> "Off"
            }
            linkRow("Swipe to search", "$swipe · in Gestures") { open(GESTURES, if (prefs.swipeUpSearch && !prefs.swipeDownSearch) "Swipe up" else "Swipe down") }
            linkRow("Search button on Home", "${if (prefs.hideSearchRow) "Hidden" else "Shown"} · in Layout") { open(LAYOUT, "Hide search bar and page indicators") }
        }
    }

    private fun openUsageAccess() = tryStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

    private fun openAppDetails() = tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    // ---------------------------------------------------------------- Folders & widgets

    private fun foldersPage() {
        val shape = prefs.iconShape
        groupHeader("Folders")
        card {
            toggle("Pop-up folders", "Open folders in a small window over the Home screen instead of full screen.", prefs.popupFolders) { prefs.popupFolders = it }
            // Folder icons: round folders only (a Circle icon shape, not kept squircle), greyed with the reason otherwise
            val round = shape == IconShape.CIRCLE && !prefs.keepSquircle
            val styles = listOf(FolderIconView.INSIDE, FolderIconView.OUTSIDE, FolderIconView.PIXEL)
            val chosen = styles.indexOf(prefs.folderIcons).coerceAtLeast(0)
            val why = when {
                shape != IconShape.CIRCLE -> "Only with round icons. Your icons are ${IconShape.name(shape)}."
                prefs.keepSquircle -> "Off while Keep folders and widgets squircle is on."
                else -> listOf("Apps sit inside the folder's circle", "Apps poke out past the folder's edge",
                    "Four apps, sized like Pixel Launcher. They open into the full folder")[chosen]
            }
            val box = added {
                row("Folder icons", why, sub, null, null).apply { setPadding(paddingLeft, paddingTop, paddingRight, dp(2f)); if (!round) alpha = 0.4f }
                pictureTiles(listOf("Inside", "Outside", "Pixel"), chosen, 52f, 52f, enabled = round,
                    draw = { i, c, w, h, on -> drawFolder(c, w, h, i, on) }) { i -> prefs.folderIcons = styles[i]; build() }
            }
            register("Folder icons", box.first())
            val keep = toggle("Keep folders and widgets squircle", if (shape == IconShape.SQUIRCLE) "For any icon shape but Squircle. Your icons are Squircle."
                else "Folders and Launchii's widgets stay One UI's shape. Only app icons change.", prefs.keepSquircle) {
                prefs.keepSquircle = it; build()
            }
            if (shape == IconShape.SQUIRCLE) { keep.alpha = 0.4f; keep.isEnabled = false; keep.isClickable = false; keep.getChildAt(keep.childCount - 1).isEnabled = false }
        }
        groupHeader("Widgets")
        card {
            withIcon(row("Add a widget", "Opens the widget list on your Home screen", sub, null, {
                // Home opens its widget list (Launcher.onNewIntent)
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(com.oneuiclone.home.Launcher.EXTRA_OPEN_WIDGETS, true))
            }), Glyphs.WIDGETS)
        }
        groupHeader("On the item itself")
        card {
            withIcon(row("Touch and hold a folder", "Rename, Add apps, Color, Style", sub, null, null), Glyphs.TOUCH, sub)
            withIcon(row("Touch and hold a widget", "Style (blur and transparency), Align with icons or Centre in row, Settings or Configure", sub, null, null), Glyphs.TOUCH, sub)
        }
        caption("Choices for one folder or widget stay on its touch and hold menu, where they act on that item.")
    }

    /** Folder icons' three looks: apps inside the circle, poking out past it, or four big ones (Pixel). */
    private fun drawFolder(c: Canvas, w: Float, h: Float, kind: Int, on: Boolean) {
        val ink = if (on) primary else sub
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; alpha = 70 }
        val cx = w / 2f; val cy = h / 2f; val r = minOf(w, h) * 0.4f
        c.drawCircle(cx, cy, r, p)
        p.alpha = 255
        val (s, gap) = when (kind) { 0 -> r * 0.5f to r * 0.12f; 1 -> r * 0.78f to r * 0.1f; else -> r * 0.66f to r * 0.1f }
        for (j in 0 until 4) {
            val x = cx + (if (j % 2 == 0) -1 else 1) * (gap / 2 + s / 2)
            val y = cy + (if (j < 2) -1 else 1) * (gap / 2 + s / 2)
            if (kind == 2) c.drawCircle(x, y, s / 2f, p)
            else c.drawRoundRect(RectF(x - s / 2, y - s / 2, x + s / 2, y + s / 2), s * 0.3f, s * 0.3f, p)
        }
    }

    // ---------------------------------------------------------------- Badges & bubbles

    private val badgeStyles = listOf(Prefs.BADGE_ORANGE, Prefs.BADGE_MATCHED, Prefs.BADGE_QUIET)

    private fun badgesPage() {
        val granted = BadgeService.isEnabled(this)
        val quiet = prefs.badgeStyle == Prefs.BADGE_QUIET
        val askAccess = {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, BadgeService::class.java).flattenToString()))
        }
        groupHeader("Badges")
        card {
            toggle("App icon badges", when {
                !granted -> "Allow notification access to show badges"
                quiet -> "Show as a dot"
                prefs.badgeNumbers -> "Show with number"
                else -> "Show without number"
            }, prefs.badges && granted) {
                prefs.badges = it
                if (it && !granted) askAccess()
            }
            val chosen = badgeStyles.indexOf(prefs.badgeStyle).coerceAtLeast(1)
            val box = added {
                row("Badge style", listOf("One UI's orange badge", "A cream badge, the number in a deep shade of your Icon colour",
                    "A cream dot, no number")[chosen], sub, null, null).setPadding(dp(24f), dp(14f), dp(20f), dp(2f))
                pictureTiles(listOf("One UI orange", "Matched", "Quiet dot"), chosen, 56f, 56f,
                    draw = { i, c, w, h, _ -> drawBadge(c, w, h, badgeStyles[i]) }) { i -> prefs.badgeStyle = badgeStyles[i]; build() }
            }
            register("Badge style", box.first())
            val numbers = toggle("Show number on badges", if (quiet) "Quiet dots have no number" else null, prefs.badgeNumbers) { prefs.badgeNumbers = it; build() }
            if (quiet) { numbers.alpha = 0.4f; numbers.isClickable = false; (numbers.getChildAt(numbers.childCount - 1) as Switch).isEnabled = false }
            statusRow("Notification access", if (granted) "Allowed. Badges need it." else "Not allowed yet. Badges need it.", granted,
                fix = { prefs.badges = true; askAccess() }, onTap = { askAccess() })
        }
        groupHeader("Bubbles")
        card {
            val bubblesMissing = AppBubbles.missing(this)
            toggle("App bubbles", if (prefs.appBubbles && bubblesMissing != null) bubblesMissing
                else "Touch and hold an app, then tap Bubble to open it in a floating window.", prefs.appBubbles) {
                prefs.appBubbles = it
                if (it && bubblesMissing != null) AppBubbles.setUp(this)
            }
            val ready = AppBubbles.ready(this)
            statusRow("Notifications and bubbles", if (ready) "Allowed. Bubbles need them." else "Not allowed yet. Bubbles need them.", ready,
                fix = { prefs.appBubbles = true; AppBubbles.setUp(this) },
                onTap = { tryStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) })
        }
    }

    /** An app plate (in the accent) with [style]'s badge at its top right, as Home draws it (IconView.drawBadge). */
    private fun drawBadge(c: Canvas, w: Float, h: Float, style: String) {
        val s = minOf(w, h) * 0.78f
        val left = (w - s) / 2f; val top = h - s - (h - s) * 0.2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = getColor(if (night) android.R.color.system_accent1_600 else android.R.color.system_accent1_300) }
        c.drawRoundRect(RectF(left, top, left + s, top + s), s * 0.3f, s * 0.3f, p)
        val orange = style == Prefs.BADGE_ORANGE
        val deep = com.oneuiclone.home.ui.IconView.deepShade(this)
        p.color = if (orange) 0xFFF54D13.toInt() else 0xFFFFF7F0.toInt()
        p.setShadowLayer(2f * d, 0f, 0.8f * d, 0x4D000000)
        if (style == Prefs.BADGE_QUIET) {
            val r = s * 0.13f
            val cx = left + s * 0.85f; val cy = top + s * 0.15f
            c.drawCircle(cx, cy, r, p)
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE; color = deep; strokeWidth = s * 0.035f }
            c.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)
            return
        }
        val dd = s * 0.42f
        val cx = left + s * 0.88f; val cy = top + s * 0.12f
        c.drawRoundRect(RectF(cx - dd / 2, cy - dd / 2, cx + dd / 2, cy + dd / 2), dd / 2, dd / 2, p)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (orange) 0xFFFCFCFC.toInt() else deep; textAlign = Paint.Align.CENTER
            textSize = dd * 0.62f; typeface = Fonts.ui(this@SettingsSectionActivity, if (orange) 600 else 700)
        }
        val fm = t.fontMetrics
        c.drawText("3", cx, cy - (fm.ascent + fm.descent) / 2f, t)
    }

    // ---------------------------------------------------------------- Permissions & setup

    private fun permissionsPage() {
        val state = permissions(this)
        val done = state.count { it }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), 0, dp(20f), dp(18f))
        }
        head.addView(Sketch(this) { c, w, h ->
            val stroke = 6f * d
            val rect = RectF(stroke, stroke, w - stroke, h - stroke)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; color = primarySoft }
            c.drawArc(rect, 0f, 360f, false, p)
            p.color = primary
            if (done > 0) c.drawArc(rect, -90f, 360f * done / state.size, false, p)
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = text; textAlign = Paint.Align.CENTER; textSize = 18f * d; typeface = Fonts.ui(this@SettingsSectionActivity, 700) }
            val fm = t.fontMetrics
            c.drawText("$done/${state.size}", w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, t)
        }, LinearLayout.LayoutParams(dp(68f), dp(68f)))
        head.addView(TextView(this).apply {
            text = "Everything $appName can do, and what each one needs. The same steps as first-run setup."
            setTextColor(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Fonts.ui(this@SettingsSectionActivity, 400)
            setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16f) })
        list.addView(head)

        val listener = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(this, BadgeService::class.java).flattenToString())
        card {
            // as Settings' Default Home app row did: the system's Home app page
            step("Home app", if (state[0]) "$appName is your Home app" else "Make $appName your Home app", GLYPH_HOME, 0xFF7C4DFF.toInt(), state[0], "Set") {
                tryStart(Intent(Settings.ACTION_HOME_SETTINGS))
            }
            step("Your wallpaper", if (state[1]) "Set in $appName, so blur and zoom work" else "Blur and zoom need $appName to set your wallpaper. Choose your photo once more.",
                GLYPH_WALLPAPER, 0xFFE8507D.toInt(), state[1], "Choose",
                onDone = { startActivity(Intent(this, WallpaperStyleActivity::class.java)) }) { pickPhoto() }
            step("Notifications and bubbles", "Open apps in floating bubbles", GLYPH_BUBBLE, 0xFFF5923E.toInt(), state[2], "Allow",
                onDone = { tryStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }) {
                prefs.appBubbles = true
                AppBubbles.setUp(this)
            }
            step("Notification access", "Notification badges on app icons", GLYPH_BADGE, 0xFFEF5350.toInt(), state[3], "Allow",
                onDone = { tryStart(listener, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                prefs.badges = true
                tryStart(listener, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            step("Usage access", "Most used apps first in search, and split screen closes cleanly", GLYPH_USAGE, 0xFF34B36B.toInt(), state[4], "Allow") {
                openUsageAccess()
            }
            step("Contacts", "Find people from search", GLYPH_CONTACTS, 0xFF3E91FF.toInt(), state[5], "Allow",
                onDone = { openAppDetails() }) { SetupActivity.askContacts(this) }
        }
        val again = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if (night) 0x33FFFFFF else 0x1A000000),
                GradientDrawable().apply { cornerRadius = 100f * d; setColor(primarySoft) }, null)
            contentDescription = "Run setup again"
            setOnClickListener { startActivity(Intent(this@SettingsSectionActivity, SetupActivity::class.java)) }
        }
        again.addView(ImageView(this).apply { setImageDrawable(GlyphDrawable(Glyphs.WAND, primary, dp(20f))) }, LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { marginEnd = dp(10f) })
        again.addView(TextView(this).apply {
            text = "Run setup again"; setTextColor(primary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Fonts.ui(this@SettingsSectionActivity, 600)
        })
        list.addView(again, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54f)).apply { bottomMargin = dp(16f); marginStart = dp(4f); marginEnd = dp(4f) })
        register("Run setup again", again)
        caption("Tapping a tick opens that permission in Android Settings, so it can be turned off too. Setup still opens by itself the first time, and whenever $appName is opened while it isn't your Home app.")
    }

    /** A setup step: its coloured glyph, then a tick (tap: [onDone], else [run]) or a pill that runs [run]. */
    private fun step(title: String, summary: String, glyph: String, color: Int, done: Boolean, action: String,
                     onDone: (() -> Unit)? = null, run: () -> Unit) {
        val r = row(title, summary, sub, if (done) tick() else pill(action, ghost = true), if (done) (onDone ?: run) else run, titleWeight = 500)
        withIcon(r, glyph, color, solid = true)
    }

    private fun pickPhoto() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), REQUEST_PHOTO)
        } catch (_: Exception) {
            Toast.makeText(this, "No photo picker available", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_PHOTO || resultCode != RESULT_OK || uri == null) return
        // positioned and set there, then back here (build runs on resume and ticks the row)
        startActivity(Intent(this, WallpaperActivity::class.java).setData(uri)
            .putExtra(WallpaperActivity.EXTRA_STAY, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AppBubbles.REQUEST_NOTIFICATIONS)
            AppBubbles.onPermissionResult(this, grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
        build()
    }

    // ---------------------------------------------------------------- Backup & advanced

    /** Earlier layouts (WorkspaceModel snapshots), newest first; read with a model of their own, let go at once. */
    private fun snapshots(): List<com.oneuiclone.home.model.WorkspaceModel.Snapshot> {
        val repo = com.oneuiclone.home.model.AppRepository(this, com.oneuiclone.home.icons.IconFactory(this), 1)
        return try { com.oneuiclone.home.model.WorkspaceModel(this, repo).snapshots() } catch (_: Exception) { emptyList() } finally { repo.destroy() }
    }

    private fun describe(s: com.oneuiclone.home.model.WorkspaceModel.Snapshot): Pair<String, String> {
        val d = java.util.Date(s.time)
        val fmt = android.text.format.DateFormat.getMediumDateFormat(this)
        val time = android.text.format.DateFormat.getTimeFormat(this)
        fun n(k: Int, one: String) = if (k == 1) "1 $one" else "$k ${one}s"
        val parts = listOfNotNull(n(s.apps, "app"), s.folders.takeIf { it > 0 }?.let { n(it, "folder") }, s.widgets.takeIf { it > 0 }?.let { n(it, "widget") })
        return "${fmt.format(d)}, ${time.format(d)}" to parts.joinToString(" · ")
    }

    /** Puts [snap] back on Home: through the running Home, else straight into the saved layout (as the old dialog did). */
    private fun restore(snap: com.oneuiclone.home.model.WorkspaceModel.Snapshot) {
        val home = com.oneuiclone.home.Launcher.live?.get()
        val ok = home?.restoreLayout(snap) ?: run {
            val repo = com.oneuiclone.home.model.AppRepository(this, com.oneuiclone.home.icons.IconFactory(this), 1)
            try {
                val model = com.oneuiclone.home.model.WorkspaceModel(this, repo)
                model.load()
                model.restore(snap)
            } finally { repo.destroy() }
        }
        Toast.makeText(this, if (ok) "Home screen layout restored" else "Couldn't restore that layout", Toast.LENGTH_SHORT).show()
        build()
    }

    /** Earlier layouts (WorkspaceModel snapshots): pick one to put back on Home (Settings' dialog before 0.263). */
    private fun restoreDialog(snaps: List<com.oneuiclone.home.model.WorkspaceModel.Snapshot>) {
        val rows = snaps.map { s -> describe(s).let { (t, p) -> "$t\n$p" } }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Restore Home screen layout")
            .setItems(rows.toTypedArray()) { _, i -> restore(snaps[i]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun backupPage() {
        val snaps = snapshots()
        groupHeader("Restore Home screen layout")
        card {
            current?.let { register("Restore Home screen layout", it) }
            if (snaps.isEmpty()) {
                row("No earlier layouts yet", null, sub, null, null)
            } else {
                snaps.take(4).forEachIndexed { i, s ->
                    val (t, p) = describe(s)
                    val confirm = {
                        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Restore this layout?")
                            .setMessage("$t\n$p\n\nYour Home screen goes back to how it was then. The layout you have now is saved first, so you can come back to it.")
                            .setPositiveButton("Restore") { _, _ -> restore(s) }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                        Unit
                    }
                    row(t, p, sub, pill("Restore", ghost = i > 0) { confirm() }, { confirm() })
                }
                linkRow("All saved layouts", "${snaps.size} kept") { restoreDialog(snaps) }
            }
        }
        caption("One is saved every hour while you change your Home screen, and whenever most of it disappears at once.")
        groupHeader("Diagnostics")
        card {
            // temporary (0.168): the white-corner bug
            withIcon(linkRow("Icon check", "Temporary: shows which icons get white corners, and why") {
                startActivity(Intent(this, IconCheckActivity::class.java))
            }, Glyphs.WRENCH)
        }
    }

    // ---------------------------------------------------------------- About

    private fun aboutPage() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(8f), 0, dp(24f)) }
        head.addView(ImageView(this).apply { setImageDrawable(getDrawable(R.drawable.ic_launcher)) }, LinearLayout.LayoutParams(dp(84f), dp(84f)))
        head.addView(TextView(this).apply {
            text = appName; setTextColor(this@SettingsSectionActivity.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Fonts.ui(this@SettingsSectionActivity, 700); setPadding(0, dp(14f), 0, 0)
        })
        head.addView(TextView(this).apply {
            text = "Version $version"; setTextColor(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Fonts.ui(this@SettingsSectionActivity, 400)
        })
        list.addView(head)
        register("About $appName", head)
        card {
            updateRow()
            linkRow("Open source licences", "Inter typeface") {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Inter typeface")
                    .setMessage(assets.open("licenses/Inter-OFL.txt").bufferedReader().use { it.readText() })
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
        developerCard()
    }

    /**
     * Software update: mirrors Home's Update pill (AppUpdate, which stays on Home as it is). With nothing newer it
     * says Up to date; while an update downloads or installs it follows along.
     */
    private fun updateRow() {
        val button = pill("Update", glyph = Glyphs.DOWNLOAD) { AppUpdate.install(this); handler.post(updateTicker) }
        val r = row("Software update", "", primary, button, null)
        val summary = (r.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
        updateShown = {
            val rel = AppUpdate.available
            val busy = AppUpdate.phase == AppUpdate.Phase.Downloading || AppUpdate.phase == AppUpdate.Phase.Installing
            summary.text = when {
                rel == null -> "Up to date"
                AppUpdate.phase == AppUpdate.Phase.Downloading -> "Downloading version ${rel.version}… ${(AppUpdate.progress * 100).toInt()}%"
                AppUpdate.phase == AppUpdate.Phase.Installing -> "Installing version ${rel.version}…"
                AppUpdate.phase == AppUpdate.Phase.Failed -> "Couldn't update to version ${rel.version}. Tap Update to try again."
                else -> "Version ${rel.version} is ready. The Update pill on Home does the same."
            }
            button.visibility = if (rel != null && !busy) View.VISIBLE else View.GONE
            busy
        }
        handler.post(updateTicker)
    }

    /** Shows the update's state in the Software update row; true while it's downloading or installing. */
    private var updateShown: (() -> Boolean)? = null
    private val updateTicker = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            if (updateShown?.invoke() == true) handler.postDelayed(this, 500)
        }
    }
}
