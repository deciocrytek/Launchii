package com.oneuiclone.home.widgets

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Process
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.Toast
import com.oneuiclone.home.AppBubbles
import com.oneuiclone.home.R
import com.oneuiclone.home.icons.IconFactory
import com.oneuiclone.home.model.AppInfo
import com.oneuiclone.home.model.AppUsage
import com.oneuiclone.home.model.QuickOpenRecents
import com.oneuiclone.home.settings.Prefs
import com.oneuiclone.home.ui.BubbleGlyph
import com.oneuiclone.home.ui.MultiBubbleGlyph
import com.oneuiclone.home.ui.QuickOpenPanel
import com.oneuiclone.home.ui.SplitGlyph
import kotlin.math.roundToInt

/**
 * The Bubble and Split widgets (1×1, 0.101): a tap opens [QuickOpenPanel], where you type an app (Bubble: it opens in a
 * bubble) or two (Split: they open in split screen, the first on top). Multi Bubble (0.145) keeps adding apps until
 * Done, then opens them all in bubbles. On Launchii's Home each is an icon among icons ([tile] with its label); other
 * launchers get the same tile, and a tap opens the panel in [QuickOpenActivity].
 */
object QuickOpenWidget {
    enum class Kind { BUBBLE, SPLIT, MULTI }

    fun provider(context: Context, kind: Kind) = ComponentName(context, when (kind) {
        Kind.BUBBLE -> BubbleWidgetProvider::class.java
        Kind.SPLIT -> SplitWidgetProvider::class.java
        Kind.MULTI -> MultiBubbleWidgetProvider::class.java
    })

    fun kindOf(context: Context, provider: ComponentName): Kind? = Kind.entries.firstOrNull { provider(context, it) == provider }

    /** The 2×1 widget with both: Bubble on the left, Split on the right. */
    fun pairProvider(context: Context) = ComponentName(context, QuickPairWidgetProvider::class.java)

    fun label(context: Context, kind: Kind): String =
        context.getString(when (kind) { Kind.BUBBLE -> R.string.bubble_widget; Kind.SPLIT -> R.string.split_widget; Kind.MULTI -> R.string.multi_bubble_widget })

    /** The widget's glyph in [color]. */
    fun glyph(kind: Kind, color: Int): android.graphics.drawable.Drawable = when (kind) {
        Kind.BUBBLE -> BubbleGlyph(color)
        Kind.SPLIT -> SplitGlyph(color)
        Kind.MULTI -> MultiBubbleGlyph(color)
    }

    /** What the panel offers: apps of our own profile (bubbles and the split's adjacent launch can't reach a work profile). */
    fun candidates(apps: List<AppInfo>) = apps.filter { it.user == Process.myUserHandle() }

    /**
     * The widget's icon in the icon shape with the widget's glyph (no ring, no name). On Launchii's
     * Home it's frosted like Frosted icons: the plate is [frost] (see-through), with the blurred wallpaper drawn under
     * it by the IconView (plateGlass). Without [frost] (other launchers, where there's no glass) the plate is dark.
     * Other launchers give their own icon shape: [mask], covering [scale] of the tile.
     */
    fun tile(kind: Kind, size: Int, frost: Int? = null, mask: Path = IconFactory.maskPath(size.toFloat()), scale: Float = 1f): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = size.toFloat()
        c.drawPath(mask, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            if (frost != null) color = frost
            else shader = android.graphics.LinearGradient(0f, 0f, s, s, 0xF2262633.toInt(), 0xF2141419.toInt(), android.graphics.Shader.TileMode.CLAMP)
        })
        val glyph = glyph(kind, 0xFFFFFFFF.toInt())
        val g = (s * scale * 0.27f).roundToInt()
        glyph.setBounds(size / 2 - g, size / 2 - g, size / 2 + g, size / 2 + g)
        glyph.draw(c)
        return out
    }

    /**
     * The panel's frosted tint, as Frosted icons' plates: the theme accent (IconFactory.createThemed's) at the icons'
     * [plateAlpha] when they're see-through, otherwise at [FROST_ALPHA] so the glass still shows.
     */
    fun frost(context: Context, night: Boolean, plateAlpha: Float): Int {
        com.oneuiclone.home.settings.Prefs(context)   // sets the picked icon colour for this process
        val accent = com.oneuiclone.home.icons.IconFactory.themeAccent(context)
        val a = if (plateAlpha < 1f) plateAlpha.coerceIn(0.2f, 0.7f) else FROST_ALPHA
        return (accent and 0x00FFFFFF) or ((a * 255).roundToInt() shl 24)
    }

    /** The frosted tint's opacity when the icons aren't see-through themselves. */
    private const val FROST_ALPHA = 0.45f

    /**
     * The 2×1 widget's pill ([w] × [h] px): fully rounded, Bubble's glyph
     * on the left half and Split's on the right, a hairline between them. Frosted ([frost], over glass the view draws
     * under it) on Launchii's Home; dark elsewhere.
     */
    fun pairTile(w: Int, h: Int, frost: Int? = null): Bitmap {
        val out = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val fw = w.toFloat(); val fh = h.toFloat()
        val box = android.graphics.RectF(0f, 0f, fw, fh)
        c.drawRoundRect(box, fh / 2f, fh / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            if (frost != null) color = frost
            else shader = android.graphics.LinearGradient(0f, 0f, fw, fh, 0xF2262633.toInt(), 0xF2141419.toInt(), android.graphics.Shader.TileMode.CLAMP)
        })
        // the hairline between the halves
        c.drawLine(fw / 2, fh * 0.3f, fw / 2, fh * 0.7f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x4DFFFFFF; strokeWidth = (fh * 0.016f).coerceAtLeast(1f)
        })
        val g = fh * 0.35f
        for (kind in listOf(Kind.BUBBLE, Kind.SPLIT)) {
            val half = fw / 2
            // centred in the half, nudged toward the middle away from the round end
            val x = (if (kind == Kind.BUBBLE) 0f else half) + (half - g) / 2 + (if (kind == Kind.BUBBLE) fh * 0.06f else -fh * 0.06f)
            val glyph = if (kind == Kind.BUBBLE) BubbleGlyph(0xFFFFFFFF.toInt()) else SplitGlyph(0xFFFFFFFF.toInt())
            glyph.setBounds(x.roundToInt(), ((fh - g) / 2).roundToInt(), (x + g).roundToInt(), ((fh + g) / 2).roundToInt())
            glyph.draw(c)
        }
        return out
    }

    /** Whether the 2×1 widget [appWidgetId] sits centred in its row (true, the default) or on the icons' centre line. */
    fun pairCentred(context: Context, appWidgetId: Int) =
        context.getSharedPreferences(PAIR_PREFS, Context.MODE_PRIVATE).getBoolean("centred_$appWidgetId", true)

    fun setPairCentred(context: Context, appWidgetId: Int, centred: Boolean) =
        context.getSharedPreferences(PAIR_PREFS, Context.MODE_PRIVATE).edit().putBoolean("centred_$appWidgetId", centred).apply()

    private const val PAIR_PREFS = "quick_pair_widget"

    /**
     * The 2×1 widget in other launchers: its pill, each half opening its panel through [QuickOpenActivity]. As on
     * Home, the pill's ends are at the two cells' icons' edges and it's 1.1× their height, centred in the row.
     */
    fun updatePairRemote(context: Context, appWidgetId: Int, options: Bundle? = null) {
        fun tap(kind: Kind) = PendingIntent.getActivity(context, kind.ordinal,
            Intent(context, QuickOpenActivity::class.java).putExtra(QuickOpenActivity.EXTRA_KIND, kind.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val d = context.resources.displayMetrics.density
        val views = HostLook.responsive(HostLook.sizes(context, appWidgetId, spanX = 2, options)) { size ->
            val look = HostLook.of(context, size, spanX = 2)
            val w = (size.width * d - (look.cellPx - look.shapePx)).roundToInt()
            val h = (look.shapePx * PAIR_HEIGHT).roundToInt()
            RemoteViews(context.packageName, R.layout.quick_pair_widget).apply {
                setImageViewBitmap(R.id.quick_pair_tile, pairTile(w, h))
                setViewLayoutWidth(R.id.quick_pair_tile, w.toFloat(), TypedValue.COMPLEX_UNIT_PX)
                setViewLayoutHeight(R.id.quick_pair_tile, h.toFloat(), TypedValue.COMPLEX_UNIT_PX)
                setOnClickPendingIntent(R.id.quick_pair_bubble, tap(Kind.BUBBLE))
                setOnClickPendingIntent(R.id.quick_pair_split, tap(Kind.SPLIT))
            }
        }
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, views)
    }

    /** The 2×1's pill height as a share of the icons' (QuickOpenPairView.HEIGHT on Home). */
    private const val PAIR_HEIGHT = 1.1f

    /** Bubble: the app in a bubble, or (if the system turns it down) opened as usual. */
    fun openBubble(activity: Activity, app: AppInfo, open: (AppInfo) -> Unit) {
        AppBubbles.show(activity, app) {
            Toast.makeText(activity, "${app.label} can't open in a bubble", Toast.LENGTH_SHORT).show()
            open(app)
        }
    }

    /** Bubbles need setting up first: says what's missing and starts the setup (true while it isn't done). */
    fun needsBubbleSetup(activity: Activity): Boolean {
        if (AppBubbles.ready(activity)) return false
        AppBubbles.missing(activity)?.let { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() }
        AppBubbles.setUp(activity)
        return true
    }

    /**
     * The widget as other launchers show it: the tile alone, centred, sized and shaped like the icons around it
     * ([HostLook]); a tap opens the panel in [QuickOpenActivity].
     */
    fun updateRemote(context: Context, kind: Kind, appWidgetId: Int, options: Bundle? = null) {
        val tap = PendingIntent.getActivity(context, kind.ordinal,
            Intent(context, QuickOpenActivity::class.java).putExtra(QuickOpenActivity.EXTRA_KIND, kind.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val views = HostLook.responsive(HostLook.sizes(context, appWidgetId, options = options)) { size ->
            val look = HostLook.of(context, size)
            RemoteViews(context.packageName, R.layout.quick_open_widget).apply {
                setImageViewBitmap(R.id.quick_open_tile, tile(kind, look.iconPx, mask = look.mask(look.iconPx), scale = look.shapeScale))
                setContentDescription(R.id.quick_open_root, label(context, kind))
                look.applyIcon(this, R.id.quick_open_tile)
                setOnClickPendingIntent(R.id.quick_open_root, tap)
            }
        }
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, views)
    }
}

/** Redrawn whenever the host says how big the widget is (it's placed, the grid changes, the screen turns). */
class BubbleWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { QuickOpenWidget.updateRemote(context, QuickOpenWidget.Kind.BUBBLE, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) =
        QuickOpenWidget.updateRemote(context, QuickOpenWidget.Kind.BUBBLE, appWidgetId, newOptions)
}

/** The 2×1 Bubble & Split widget (Bubble on the left). */
class QuickPairWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { QuickOpenWidget.updatePairRemote(context, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) =
        QuickOpenWidget.updatePairRemote(context, appWidgetId, newOptions)
}

/** Multi Bubble: apps added one by one, all opened in bubbles at Done. */
class MultiBubbleWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { QuickOpenWidget.updateRemote(context, QuickOpenWidget.Kind.MULTI, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) =
        QuickOpenWidget.updateRemote(context, QuickOpenWidget.Kind.MULTI, appWidgetId, newOptions)
}

class SplitWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { QuickOpenWidget.updateRemote(context, QuickOpenWidget.Kind.SPLIT, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) =
        QuickOpenWidget.updateRemote(context, QuickOpenWidget.Kind.SPLIT, appWidgetId, newOptions)
}

/**
 * A tap on the Bubble or Split widget in another launcher (on Launchii's Home the panel opens over Home): the panel in
 * a see-through window that blurs what's behind it, the blur coming and going with the panel ([applyBlur]).
 *
 * Launchii's apps aren't loaded here, so it lists them itself, fast: labels first, then only the icons on show
 * ([Apps]: the most used before the panel opens, the rest in the background or as a search first shows them). The
 * list is kept for the next tap while the process lives.
 */
class QuickOpenActivity : Activity() {
    private var panel: QuickOpenPanel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = QuickOpenWidget.Kind.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_KIND) } ?: QuickOpenWidget.Kind.BUBBLE
        if (kind != QuickOpenWidget.Kind.SPLIT && QuickOpenWidget.needsBubbleSetup(this)) {
            // asking for notifications: finish after the answer; otherwise the setup went to Settings
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) finish()
            return
        }
        // Back closes the panel with its animation (from Android 16, onBackPressed isn't called)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            panel?.takeIf { it.isOpen }?.close() ?: finish()
        }
        // the theme's blur and dim are applied as the decor is made: make it first, then start them at nothing
        window.decorView
        applyBlur(0f)
        val look = Apps.look(this)
        val kept = Apps.kept?.takeIf { it.look == look }
        if (kept != null) {
            show(kind, kept)
            Thread { Apps.load(applicationContext, look, kept) }.start()
        } else Thread {
            val apps = Apps.load(applicationContext, look, null)
            runOnUiThread { if (!isFinishing && panel == null) show(kind, apps) }
            apps.drawRest()
        }.start()
    }

    /** The window's blur and dim behind it at [v] (0..1) of the theme's, following the panel's backdrop. */
    private fun applyBlur(v: Float) {
        val radius = (BLUR_DP * resources.displayMetrics.density * v).roundToInt()
        val attrs = window.attributes
        if (attrs.blurBehindRadius == radius && blurLevel >= 0f && kotlin.math.abs(blurLevel - v) < 0.02f) return
        blurLevel = v
        attrs.blurBehindRadius = radius
        attrs.dimAmount = DIM * v
        window.attributes = attrs
    }
    private var blurLevel = -1f

    private fun show(kind: QuickOpenWidget.Kind, apps: Apps) {
        val usage = AppUsage(this)
        val recents = QuickOpenRecents(this)
        Thread { recents.scan(usage) }.start()                         // for next time
        val open = { app: AppInfo -> getSystemService(LauncherApps::class.java).startMainActivity(app.key.component, app.user, null, null) }
        lateinit var p: QuickOpenPanel
        p = QuickOpenPanel(this, kind == QuickOpenWidget.Kind.SPLIT, apps.list, apps.scores, null, null, null,
            QuickOpenWidget.frost(this, (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES, 1f),
            onBubble = { app -> usage.launched(app.key); p.close(animate = false); QuickOpenWidget.openBubble(this, app, open) },
            onSplit = { top, bottom ->
                usage.launched(top.key); usage.launched(bottom.key)
                recents.split(top, bottom)
                startSplit(top, bottom)
            },
            onClosed = { _ -> finish(); @Suppress("DEPRECATION") overridePendingTransition(0, 0) },
            iconOf = apps::icon,
            onBackdrop = ::applyBlur,
            multi = kind == QuickOpenWidget.Kind.MULTI,
            onBubbles = { list -> list.forEach { usage.launched(it.key) }; openBubbles(list) },
            recent = if (kind == QuickOpenWidget.Kind.SPLIT) recents.splits() else recents.bubbles())
        panel = p
        setContentView(p, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        p.requestApplyInsets()
        p.post { p.open() }
    }

    /**
     * The panel's apps (our own profile's, A–Z), their [scores], and their icons in Launchii's style ([look]), each
     * drawn the first time it's asked for ([icon]) unless [drawRest] got to it first.
     */
    class Apps(val look: List<Any>, val list: List<AppInfo>, val scores: Map<com.oneuiclone.home.model.AppKey, Double>,
               private val context: Context, private val drawn: java.util.concurrent.ConcurrentHashMap<com.oneuiclone.home.model.AppKey, Bitmap>) {
        private val size = (context.resources.displayMetrics.density * ICON_DP).roundToInt()
        private val prefs = Prefs(context)
        private val night = look[0] as Boolean
        /** One per thread: an IconFactory's paints aren't shared across threads. */
        private val factories = ThreadLocal.withInitial { IconFactory(context) }

        fun icon(app: AppInfo): Bitmap = drawn[app.key] ?: draw(app).let { drawn.putIfAbsent(app.key, it) ?: it }

        private fun draw(app: AppInfo): Bitmap {
            val f = factories.get()!!
            val d = app.activityInfo.getIcon(context.resources.displayMetrics.densityDpi)
            // the apps' own icons unless Icons › Apply to Apps screen and search is on, so they're quick to tell apart
            val plain = { f.homeIcon(d, size, night, false, false, 1f, pkg = app.key.component.packageName).first }
            if (!((prefs.themedIcons || prefs.darkIcons) && prefs.themeAppsScreen)) return plain()
            return runCatching {
                f.homeIcon(d, size, night, prefs.themedIcons, prefs.themeAllApps, prefs.iconPlateAlpha, prefs.darkIcons,
                    app.key.component.packageName, prefs.minimalIcons, prefs.iconLook, prefs.iconMode).first
            }.getOrElse { plain() }
        }

        /** The most used first (what the panel opens on), then everything else, for searches to find drawn. */
        fun drawRest() = list.sortedByDescending { scores[it.key] ?: 0.0 }.forEach { icon(it) }

        companion object {
            /** What the last tap loaded, for the next one. */
            @Volatile var kept: Apps? = null
            private const val ICON_DP = 56
            /** Icons drawn before the panel opens: its most-used grid, and one more for Split's second pick. */
            private const val FIRST = 9

            /** Everything the icons depend on: when it changes, the kept list's icons are redrawn. */
            fun look(context: Context): List<Any> {
                val prefs = Prefs(context)
                val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                return listOf(night, prefs.themedIcons, prefs.themeAllApps, prefs.iconPlateAlpha, prefs.darkIcons,
                    prefs.themeAppsScreen, prefs.minimalIcons, prefs.iconLook, prefs.iconMode, prefs.iconShape, prefs.comicBorder, prefs.iconShine, prefs.themeColor, context.resources.displayMetrics.density)
            }

            /** Lists the apps and scores them (reusing [old]'s icons), draws the most used, and keeps the result. */
            fun load(context: Context, look: List<Any>, old: Apps?): Apps {
                val me = Process.myUserHandle()
                val serial = context.getSystemService(android.os.UserManager::class.java).getSerialNumberForUser(me)
                val blank = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
                val collator = java.text.Collator.getInstance()
                val list = context.getSystemService(LauncherApps::class.java).getActivityList(null, me)
                    .filter { it.componentName.packageName != context.packageName }
                    .map { AppInfo(com.oneuiclone.home.model.AppKey(it.componentName, serial), me, it.label.toString(), blank, it) }
                    .sortedWith { a, b -> collator.compare(a.label, b.label) }
                val drawn = java.util.concurrent.ConcurrentHashMap<com.oneuiclone.home.model.AppKey, Bitmap>()
                if (old != null) list.forEach { a -> old.drawn[a.key]?.let { drawn[a.key] = it } }
                val apps = Apps(look, list, AppUsage(context).scores(list), context, drawn)
                // drawn side by side: they're what holds up the panel
                val first = list.sortedByDescending { apps.scores[it.key] ?: 0.0 }.take(FIRST).filter { !drawn.containsKey(it.key) }
                val pool = java.util.concurrent.Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
                pool.invokeAll(first.map { a -> java.util.concurrent.Callable { apps.icon(a) } })
                pool.shutdown()
                kept = apps
                return apps
            }
        }
    }


    /**
     * Split from another launcher (0.143). From Android 17 nothing hidden may start an activity (only Home is let off),
     * and this window is hidden as soon as an app opens over it, so it can't open one app and then the other beside
     * it, as Home does. Instead one app goes adjacent first, into the side stage beside this window, which System UI
     * puts in the main stage. Once that transition has finished (the split has formed) this activity opens the other
     * app into its own half ([fillMain]) and finishes once that covers it ([onStop]). Which app goes to the side stage
     * depends on its half, as this activity saw it last time ([SplitLaunch.learnSide]), so the top app ends up on top.
     * Two fix-ups cover apps whose tasks are still in an old split pair ([onMultiWindowModeChanged], [fillMain]).
     *
     * 0.139–0.142 opened the second app as soon as this activity was in the split (onMultiWindowModeChanged), which
     * comes while System UI is still building it. The app then joined that transition or queued behind it depending
     * on timing, so the result was different every time. Starting both at once fails too: an app whose task is still
     * in an old split pair is moved out of it while the new split forms, and doesn't land in the half.
     */
    private fun startSplit(top: AppInfo, bottom: AppInfo) {
        window.insetsController?.hide(android.view.WindowInsets.Type.ime())
        panel?.visibility = android.view.View.GONE
        applyBlur(0f)
        splitting = true
        val sideTop = SplitLaunch.sideOnTop(this)
        val side = if (sideTop) top else bottom
        splitSide = side
        splitMain = if (sideTop) bottom else top
        if (!SplitLaunch.openAdjacent(this, side, whenDone = ::fillMain)) { finish(); return }
        window.decorView.postDelayed(::fillMain, SPLIT_MAIN_MS)
        window.decorView.postDelayed({ if (!isFinishing) finish() }, SPLIT_HOLD_MS)
    }

    /**
     * Multi Bubble's apps in bubbles. Only an app in front may expand a bubble, and the first app's (expanded) is
     * posted last, so this window stays, hidden and untouchable, until that one is up ([BubblesWidget.open]).
     */
    private fun openBubbles(list: List<AppInfo>) {
        window.insetsController?.hide(android.view.WindowInsets.Type.ime())
        panel?.visibility = android.view.View.GONE
        applyBlur(0f)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        if (!BubblesWidget.open(this, list) { index, _ -> if (index == 0 && !isFinishing) finish() }) { finish(); return }
        window.decorView.postDelayed({ if (!isFinishing) finish() }, BUBBLES_HOLD_MS)
    }

    private var splitting = false
    /** The app for the split's main stage, until this activity (by then in that stage) opens it; the side one, until it's checked. */
    private var splitMain: AppInfo? = null
    private var splitSide: AppInfo? = null

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (!isInMultiWindowMode || splitMain == null) return
        // System UI clears the old main stage as a split forms, decided when the split is asked for. If the side app's
        // task was still there (it was the main app of an earlier split), it may have been put back out just after
        // landing in the side stage, depending on which of System UI's messages came first. That's done by the time
        // this activity is in the main stage, so this puts it back; if it's in place, this changes nothing. It has to
        // be now: once the split's animation starts, System UI turns adjacent starts from inside off for a second.
        splitSide?.let { SplitLaunch.openAdjacent(this, it) }
        splitSide = null
    }

    private fun fillMain() {
        val main = splitMain ?: return
        splitMain = null
        if (isFinishing) return
        // If its task was left hidden in the side stage (it was the side app of an earlier split), System UI only
        // moves it out of there, behind the split. Then nothing has opened over this activity, which is still resumed
        // (in a split both halves are) once that's done, and started again the app comes into this half.
        SplitLaunch.openInHalf(this, main) { if (!isFinishing && resumed) SplitLaunch.openInHalf(this, main) }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (splitting) SplitLaunch.learnSide(this)                         // put in its half
    }

    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { super.onPause(); resumed = false }
    private var resumed = false

    override fun onStop() {
        super.onStop()
        // in a split, stay until the main stage's app has been opened over this activity
        if (!isFinishing && splitMain == null) finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AppBubbles.REQUEST_NOTIFICATIONS) {
            AppBubbles.onPermissionResult(this, grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
            finish()
        }
    }

    companion object {
        const val EXTRA_KIND = "kind"
        /** The theme's blur and dim behind the window (Theme.OneUiHome.QuickOpen), which the panel fades in and out. */
        private const val BLUR_DP = 40f
        private const val DIM = 0.35f
        /** Opens the main stage's app anyway if the split's transition hasn't reported its end by then. */
        private const val SPLIT_MAIN_MS = 1500L
        /** The longest this activity stays after starting a split: past the second app's launch. */
        private const val SPLIT_HOLD_MS = 3000L
        /** The longest it stays for Multi Bubble's bubbles: past the last post and its check, if the first never reports. */
        private const val BUBBLES_HOLD_MS = 4000L
    }
}
