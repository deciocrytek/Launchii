package com.oneuiclone.home

import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.graphics.RectF
import android.os.Process
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.Toast
import com.oneuiclone.home.debug.AnimLog
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.icons.IconFactory
import com.oneuiclone.home.model.AppInfo
import com.oneuiclone.home.model.AppRepository
import com.oneuiclone.home.model.Container
import com.oneuiclone.home.model.HomeItem
import com.oneuiclone.home.model.WorkspaceModel
import com.oneuiclone.home.motion.Easing
import com.oneuiclone.home.ui.BackgroundController
import com.oneuiclone.home.ui.CellLayout
import com.oneuiclone.home.ui.FolderIconView
import com.oneuiclone.home.ui.HomeSearchButton
import com.oneuiclone.home.ui.IconView
import com.oneuiclone.home.ui.LargeFolderIconView
import com.oneuiclone.home.ui.PageIndicator
import com.oneuiclone.home.ui.PagedView
import com.oneuiclone.home.ui.ScrimView
import com.oneuiclone.home.ui.SearchPill
import com.oneuiclone.home.ui.StateController
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.exp

/**
 * Whether app closes zoom the launcher's wallpaper copy out. Off: they leave the real wallpaper to the system, as they
 * always did for a wallpaper set from another app (no copy). The copy is drawn in the launcher window, which the
 * system fades in during the close, so it cross-dissolved with the real wallpaper at another zoom (a double image).
 * The open zoom is kept (the user likes it). Set to true for the 0.139 close; 0.158 tried that again and the user
 * chose to leave the close to the Pixel (0.159), even though the Pixel draws no wallpaper zoom of its own.
 */
private const val CLOSE_ZOOMS_WALLPAPER = false
/**
 * Whether Home also plays the close zoom for apps it didn't open (Recents, a quick switch, a notification), as in
 * 0.156. 0.160 switched it off as a test: the Pixel still drew no wallpaper zoom on closes, so it wasn't the cause.
 */
private const val RECENTS_CLOSE_ZOOM = true
/** Home shrink + wallpaper zoom when an app is opened. */
private const val OPEN_ZOOM_MS = 280L
private const val OPEN_SCALE = 0.85f
/** How far Home shrinks while a launch is held for a close to finish (half the open zoom). */
private const val OPEN_HOLD_SCALE = 0.925f
/** Longest a system close into Home is assumed to run, and longest a launch tapped during one waits for it. */
private const val CLOSE_MAX_MS = 1200L
private const val LAUNCH_WAIT_MS = 400L
/** Longest the close zoom waits for Home to be put back on screen, on phones that never send a gesture contract. */
private const val RETURN_WAIT_MS = 2000L
private const val KEY_CONTRACT_SEEN = "contract_seen"
/** Zoom In open: the card's spring (≈ 95 % open by 240 ms, ~1 % overshoot) and Home zooming toward the icon. */
private const val ZOOM_IN_STIFFNESS = 300f
private const val ZOOM_IN_DAMPING = 0.84f
private const val ZOOM_IN_MS = 650L
private const val ZOOM_IN_SCALE = 1.15f
/**
 * The card aims past the screen's edges, so they're covered most of the way through the spring instead of only
 * at its slow tail (recording 6024: a thin strip of Home showed at the edges for ~100 ms). 0.9 (0.45/0.46) was
 * rejected.
 */
private const val ZOOM_IN_CARD_SCALE = 1.2f
/**
 * Opens drawn by the live wallpaper (Aura 0.177; Shutter, Orbit, Anticipate, Scan, Mist 0.178), from the 2026-11-06
 * mockups: Home moves and fades out over them as [effectHome] says. Aura's wallpaper zooms to the open zoom over its
 * glow's 420 ms.
 */
private const val AURA_MS = 420L
/** Anticipate: Home bulges to 1.04 over the wind-up, then flies back to 0.8. */
private val ANTICIPATE_HOME = android.view.animation.Interpolator { t ->
    val m = t * 410f
    val wind = 1f - (1f - (m / 110f).coerceIn(0f, 1f)).let { it * it * it }
    val go = Easing.STANDARD.getInterpolation(((m - 110f) / 300f).coerceIn(0f, 1f))
    (1f - (1f + 0.04f * wind + (0.8f - 1.04f) * go)) / 0.2f
}
/**
 * The card goes when the app covers Home (onStop). Translucent apps never do and get Smooth instead; this only
 * catches anything else that leaves Home showing. Slow starts can stop Home seconds after the start.
 */
private const val CARD_TIMEOUT_MS = 8000L
/**
 * Home back before the app covered it (a swipe home or Back mid-open, or an app that closed at once): the card
 * shrinks back into its icon on the close's curve and length, in step with Home zooming back (playReturnAnimation).
 */
private const val CARD_RETRACT_MS = 450L

class Launcher(val activity: LauncherActivity) : AppRepository.Listener {

    companion object {
        /** The running Home, for windows that preview it (Wallpaper and style draws its widgets). */
        @Volatile var live: java.lang.ref.WeakReference<Launcher>? = null

        /** Lift-Off: let go past 30% and it opens, unless heading back faster than 0.0015 progress/ms; a flick
         *  toward it faster than 0.0012/ms (~340 dp/s) opens from anywhere. The spring is critically damped. */
        private const val LIFT_COMMIT = 0.3f
        private const val LIFT_FLICK = 0.0012f
        private const val LIFT_BACK_FLICK = 0.0015f
        private const val LIFT_STIFFNESS = 420f
        /** The bar's landing bounce: none below 0.0015 progress/ms, the full 8 dp from 0.01/ms. */
        private const val LIFT_BOUNCE_FROM = 0.0015f
        private const val LIFT_BOUNCE_RANGE = 0.0085f
        private const val LIFT_BOUNCE_DP = 8f
    }

    val iconFactory = IconFactory(activity)
    val prefs = com.oneuiclone.home.settings.Prefs(activity)
    private var prefsSnapshot = ""
    /** Settings that need the views rebuilt (live ones, like the wallpaper blur, are applied in place). */
    private fun snapshot() = prefs.sp.all.filterKeys { !com.oneuiclone.home.settings.Prefs.isLive(it) }.toSortedMap().toString()
    lateinit var dp: DeviceProfile; private set
    var repo: AppRepository? = null; private set
    var model: WorkspaceModel? = null; private set
    private val launcherApps = activity.getSystemService(LauncherApps::class.java)
    private val usage = com.oneuiclone.home.model.AppUsage(activity)
    private val quickOpenRecents = com.oneuiclone.home.model.QuickOpenRecents(activity)
    /** Search suggestions, worked out while search isn't on screen (see [SearchSources.refresh]). */
    private val searchSources = com.oneuiclone.home.ui.SearchSources(activity, usage) { repo?.apps ?: emptyList() }

    val root = RootLayout()
    val scrim = ScrimView(activity)
    val wallpaper = com.oneuiclone.home.ui.WallpaperLayer(activity).apply { drivesLive = true }
    /** Blurred wallpaper behind widgets set to Style › Blur. */
    private val widgetGlass = com.oneuiclone.home.ui.WidgetGlass().also { g -> wallpaper.onLoaded = { g.rebuild(wallpaper); syncBlurredWallpaper() } }
    val background = BackgroundController(activity, scrim, wallpaper)
    val homeLayer = FrameLayout(activity)
    val appsLayer = FrameLayout(activity)
    val overlay = FrameLayout(activity)

    lateinit var workspace: PagedView; private set
    lateinit var indicator: PageIndicator; private set
    lateinit var hotseat: CellLayout; private set
    /** Apps screen: one alphabetical grid that scrolls vertically. */
    lateinit var appsScroll: android.widget.ScrollView; private set
    private lateinit var appsGrid: CellLayout
    lateinit var searchPill: SearchPill; private set
    /** One UI 9 Finder-style Search button left of the page dots. */
    lateinit var homeSearch: HomeSearchButton; private set
    /** "Update" left of [homeSearch] while GitHub has a newer release ([AppUpdate]); gone otherwise. */
    private lateinit var updatePill: HomeSearchButton
    /** It pops in only the first time it shows in this process. */
    private var updateAnnounced = false
    private lateinit var moreButton: SearchPill

    val state = StateController(homeLayer, appsLayer, background) { dp.swipeTransitionY }
    val folders = com.oneuiclone.home.ui.FolderController(
        activity, overlay, { dp }, background,
        { if (state.state == StateController.State.APPS) appsLayer else homeLayer },
        { v, app -> launch(v, app) },
        { folder, title -> folder.title = title; model?.save(); bindWorkspace() },
        { v, item, app ->
            attachItem(v, item, app, false) { folderSiblings(v) }
            applyBadge(v, listOf(app.key.component.packageName to app.user))   // folder contents are built on open
        },
        { folder -> addAppsToFolder(folder) },
        { folder -> pickFolderColor(folder) },
        { prefs.popupFolders },
        { rect, done -> snapshotBehind(rect, done) },
        { folder -> com.oneuiclone.home.widgets.WidgetStyles.folderTransparency(activity, folder.id) },
        onReorder = { folder, ordered ->
            model?.let { m ->
                val rest = m.appsInFolder(folder.id).filter { a -> ordered.none { it === a } }
                (ordered + rest).forEachIndexed { i, a -> a.rank = i }
                m.save(); bindWorkspace()
            }
        },
        onOpenness = { h ->
            // all of Home shrinks behind a pop-up, dock and page dots too (unless an app opening or closing has it:
            // one tapped while a folder closes)
            val v = if (state.state == StateController.State.APPS) appsLayer else homeLayer
            if (contentAnim?.isRunning != true) {
                centrePivot(v)
                val s = 1f - (1f - com.oneuiclone.home.ui.FolderController.POPUP_HOME_SCALE) * h
                v.scaleX = s; v.scaleY = s
            }
        },
    )

    /** Folder "+" (folder_add_apps): multi-select app picker. */
    private fun addAppsToFolder(folder: HomeItem.Folder) {
        val m = model ?: return
        val r = repo ?: return
        val inFolder = m.appsInFolder(folder.id).map { it.key }.toSet()
        val apps = r.apps
        val checked = BooleanArray(apps.size) { apps[it].key in inFolder }
        android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Add apps")
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Done") { _, _ ->
                m.items.removeAll { it is HomeItem.App && it.folderId == folder.id && it.container == Container.FOLDER &&
                    apps.indexOfFirst { a -> a.key == it.key }.let { i -> i >= 0 && !checked[i] } }
                apps.forEachIndexed { i, a ->
                    if (checked[i] && a.key !in inFolder) {
                        m.items += HomeItem.App(m.newId(), a.key, Container.FOLDER, 0, 0, 0, folder.id, m.appsInFolder(folder.id).size)
                    }
                }
                m.appsInFolder(folder.id).forEachIndexed { i, a -> a.rank = i }
                m.save(); bindWorkspace()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun folderSiblings(v: View): List<View> {
        val g = v.parent as? ViewGroup ?: return emptyList()
        return (0 until g.childCount).map { g.getChildAt(it) }
    }

    private var ripple = com.oneuiclone.home.ui.TouchRipple(1f)
    val drag = com.oneuiclone.home.ui.DragController(
        activity, overlay, { dp }, { model }, { workspace }, { hotseat }, background,
        { bindWorkspace() }, { findView(it) }, { widgets.delete(it.appWidgetId) }, { findItemView(it) },
        openFolderForDrop = { view, folder, app, dragged ->
            val m = model; val r = repo
            if (m == null || r == null) null else {
                // the folder's apps as it opens normally, then an empty cell for the one being dragged in
                val its = m.appsInFolder(folder.id).filter { it !== dragged && r.get(it.key) != null }
                folders.openForDrop(view, folder, its.mapNotNull { r.get(it.key) } + app, its + dragged)
            }
        },
    )
    private var popup: com.oneuiclone.home.ui.QuickOptionPopup? = null
    private val shortcutLoader = com.oneuiclone.home.ui.ShortcutLoader(activity, (52 * activity.resources.displayMetrics.density).toInt())
    val widgets = com.oneuiclone.home.widgets.WidgetManager(activity).also {
        it.onLaunch = { appWidgetId, sender, send -> launchFromWidget(appWidgetId, sender, send) }
    }
    private var picker: com.oneuiclone.home.widgets.WidgetPicker? = null
    private var firstRun = false
    /** The first run's search bar is waiting to be added (its bind dialog may follow). */
    private var seeding = false
    /** Home is loaded and no system dialog of its own (widget bind or configure) is coming or up: setup may show. */
    val readyForSetup get() = bound && !seeding && widgets.pending == null
    val edit = com.oneuiclone.home.ui.EditModeController(
        activity, { dp }, { workspace }, { hotseat }, { indicator }, homeLayer, background,
        { model?.let { it.pageCount++; it.save() }; bindWorkspace() },
        listOf(
            "Wallpaper and style" to { onStyleButton() },
            "Icons" to { onIconsButton() },
            "Widgets" to { onWidgetsButton() },
            "Settings" to { onSettingsButton() },
        ),
        { deletePage(it) },
        {
            if (!::homeSearch.isInitialized) emptyList()
            else listOfNotNull(homeSearch.takeIf { !searchRowHidden }, updatePill.takeIf { AppUpdate.available != null })
        },
    )

    /** Page edit bin: an empty page goes straight away, otherwise confirm (apps stay on the Apps screen). */
    private fun deletePage(page: Int) {
        val m = model ?: return
        if (m.pageCount <= 1) return
        val onPage = m.items.filter { it.container == Container.WORKSPACE && it.screen == page }
        val remove = {
            for (it in onPage) {
                m.items.remove(it)
                if (it is HomeItem.Folder) m.items.removeAll { a -> a is HomeItem.App && a.folderId == it.id }
                if (it is HomeItem.Widget) widgets.delete(it.appWidgetId)
            }
            m.items.filter { it.container == Container.WORKSPACE && it.screen > page }.forEach { it.screen-- }
            m.pageCount--
            m.save()
            bindWorkspace()
            workspace.post { workspace.snapToPage(page.coerceAtMost(m.pageCount - 1)) }
        }
        if (onPage.isEmpty()) { remove(); return }
        android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Remove page?")
            .setMessage("The apps, folders, and widgets on this page will be removed from the Home screen. Apps stay on the Apps screen.")
            .setPositiveButton("Remove") { _, _ -> remove() }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private var dragArmed: View? = null
    private var armX = 0f
    private var armY = 0f
    private var lastLaunchedApp: AppInfo? = null
    /** The widget the last app was opened from, and the packages its window may report (Launcher3's launch cookie). */
    private class WidgetLaunch(val appWidgetId: Int, val packages: Set<String>)
    private var lastWidgetLaunch: WidgetLaunch? = null
    private var insets = Rect()
    private var bound = false
    private var pendingReturnAnim = false
    private var contentAnim: ValueAnimator? = null
    /**
     * Process-wide, so removed in [destroy]: a new wallpaper changes the dynamic colours, which recreates the
     * activity, and a stale listener would reload this instance's shut-down wallpaper copy.
     */
    private val wallpaperColorsListener = android.app.WallpaperManager.OnColorsChangedListener { _, _ ->
        wallpaper.reload(); if (::indicator.isInitialized) updateWallpaperColors(); syncBlurredWallpaper()
    }

    /** A Bubbles widget's apps changed (its configuration saves every pick): redraw it. */
    private val bubblesListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> if (bound) bindWorkspace() }

    init {
        live = java.lang.ref.WeakReference(this)
        com.oneuiclone.home.widgets.BubblesWidget.prefs(activity).registerOnSharedPreferenceChangeListener(bubblesListener)
        root.clipChildren = false
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = centerWallpaper()
            override fun onViewDetachedFromWindow(v: View) {}
        })
        // Home's window is hidden once an app's open has finished, before onStop (~300 ms later on the emulator)
        root.viewTreeObserver.addOnWindowVisibilityChangeListener {
            AnimLog.log("window visibility=$it")
            if (it != View.VISIBLE) homeHidden()
        }
        root.addView(wallpaper, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(homeLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(appsLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        appsLayer.visibility = View.INVISIBLE
        appsLayer.alpha = 0f
        homeLayer.clipChildren = false
        appsLayer.clipChildren = false
        root.setOnApplyWindowInsetsListener { _, wi ->
            val sb = wi.getInsets(android.view.WindowInsets.Type.systemBars())
            val r = Rect(sb.left, sb.top, sb.right, sb.bottom)
            if (r != insets) {
                insets = r
                if (root.width > 0) setupProfile(root.width, root.height)
            }
            wi
        }
        android.app.WallpaperManager.getInstance(activity).addOnColorsChangedListener(
            wallpaperColorsListener, android.os.Handler(android.os.Looper.getMainLooper()))
        state.onStateChanged = { s ->
            applyTextMode()
            if (s == StateController.State.HOME) {
                appsScroll.scrollTo(0, 0)
                // Home was reached without the search closing first (a result dragged out, an app's return):
                // the bar isn't part of either screen, so it goes at once
                search?.takeIf { it.isOpen }?.let { it.visibility = View.INVISIBLE; it.close() }
                if (search == null) restoreAppsChrome()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // profile + binding
    // ---------------------------------------------------------------------------------------------

    private fun setupProfile(w: Int, h: Int) {
        prefsSnapshot = snapshot()
        val newDp = DeviceProfile(w, h, activity.resources.displayMetrics.density, insets,
            homeCols = prefs.homeCols, homeRows = prefs.homeRows, appsCols = prefs.appsCols, appsRows = prefs.appsRows,
            iconScale = prefs.iconScale / 100f)
        ripple = com.oneuiclone.home.ui.TouchRipple(newDp.samsungPxScale)
        val iconChanged = !::dp.isInitialized || dp.iconSizePx != newDp.iconSizePx
        dp = newDp
        buildViews()
        val r = repo
        if (r == null) {
            val nr = AppRepository(activity, iconFactory, dp.iconSizePx).also {
                it.themedIcons = prefs.themedIcons; it.themeAllApps = prefs.themeAllApps; it.plateAlpha = prefs.iconPlateAlpha
                it.darkIcons = prefs.darkIcons; it.minimalIcons = prefs.minimalIcons; it.iconLook = prefs.iconLook; it.iconMode = prefs.iconMode
                it.iconShape = prefs.iconShape; it.comicBorder = prefs.comicBorder; it.iconShine = prefs.iconShine
            }
            repo = nr
            model = WorkspaceModel(activity, nr)
            nr.addListener(this)
            nr.reload()
        } else {
            val themeChanged = r.themedIcons != prefs.themedIcons || r.themeAllApps != prefs.themeAllApps ||
                r.plateAlpha != prefs.iconPlateAlpha || r.darkIcons != prefs.darkIcons ||
                r.minimalIcons != prefs.minimalIcons || r.iconLook != prefs.iconLook || r.iconMode != prefs.iconMode ||
                r.iconShape != prefs.iconShape || r.comicBorder != prefs.comicBorder || r.iconShine != prefs.iconShine
            r.themedIcons = prefs.themedIcons
            r.iconShape = prefs.iconShape
            r.comicBorder = prefs.comicBorder
            r.iconShine = prefs.iconShine
            r.iconLook = prefs.iconLook
            r.iconMode = prefs.iconMode
            r.darkIcons = prefs.darkIcons
            r.minimalIcons = prefs.minimalIcons
            r.themeAllApps = prefs.themeAllApps
            r.plateAlpha = prefs.iconPlateAlpha
            if (iconChanged || themeChanged) r.invalidateIcons(dp.iconSizePx) else bindAll()
        }
    }

    private fun buildViews() {
        homeLayer.removeAllViews()
        appsLayer.removeAllViews()

        workspace = PagedView(activity)
        homeLayer.addView(workspace, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // beside the Search button the dots sit closer together and a little fainter (One UI 9 Finder row)
        indicator = PageIndicator(activity, dp.indicatorSlotPx * 0.7f, dp.indicatorDotPx).apply { opacity = 0.8f }
        homeLayer.addView(indicator, FrameLayout.LayoutParams(MATCH_PARENT, dp.indicatorRect.height()).apply {
            topMargin = dp.indicatorRect.top
        })
        homeSearch = HomeSearchButton(activity, dp, activity.getString(R.string.search_hint))
        homeSearch.setOnClickListener { openSearch(fromHome = true) }
        // the folders' glass at the folders' default transparency
        homeSearch.glassTransparency = com.oneuiclone.home.widgets.WidgetStyles.FOLDER_DEFAULT_TRANSPARENCY / 100f
        homeSearch.glass = widgetGlass
        homeLayer.addView(homeSearch, FrameLayout.LayoutParams(homeSearch.pillWidth, homeSearch.pillHeight).apply {
            topMargin = dp.indicatorRect.top + (dp.indicatorRect.height() - homeSearch.pillHeight) / 2
        })
        // not part of the centred Search + dots row: it hangs off the Search pill's left, so nothing moves for it
        updatePill = HomeSearchButton(activity, dp, "Update", icon = null)
        updatePill.setOnClickListener {
            if (state.state == StateController.State.HOME && !state.isTransitioning && !edit.isActive && !folders.isOpen)
                AppUpdate.install(activity)
        }
        updatePill.glassTransparency = homeSearch.glassTransparency
        updatePill.glass = widgetGlass
        homeLayer.addView(updatePill, FrameLayout.LayoutParams(updatePill.pillWidth, updatePill.pillHeight).apply {
            topMargin = dp.indicatorRect.top + (dp.indicatorRect.height() - updatePill.pillHeight) / 2
        })
        indicator.leadingGapPx = dp.dp(6f)
        indicator.leadingPx = homeSearch.pillWidth.toFloat()
        indicator.onLeadingMoved = { x -> homeSearch.translationX = x; placeUpdatePill() }
        // Hide search bar and page indicators: both stay laid out (search still takes its origin from the button), unseen
        searchRowHidden = prefs.hideSearchRow
        if (searchRowHidden) { indicator.visibility = View.INVISIBLE; homeSearch.visibility = View.INVISIBLE }
        AppUpdate.onChanged = { syncUpdatePill() }
        syncUpdatePill()
        hotseat = CellLayout(activity, 5, 1)
        homeLayer.addView(hotseat, FrameLayout.LayoutParams(dp.hotseatRect.width(), dp.hotseatRect.height()).apply {
            leftMargin = dp.hotseatRect.left
            topMargin = dp.hotseatRect.top
        })
        workspace.listener = object : PagedView.Listener {
            override fun onPageChanged(page: Int) = indicator.setPage(page)
            override fun onScrollProgress(position: Float) = edit.onWorkspaceScroll()
        }
        edit.reset()
        workspace.onEmptyLongPress = { _, _ ->
            if (!edit.isActive && state.state == StateController.State.HOME && !folders.isOpen) {
                workspace.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                edit.enter()
            }
        }
        workspace.onEmptyTap = { x, _ ->
            if (edit.isActive) {
                // tap on a side page selects it, otherwise stay on the current one
                val page = when {
                    x < dp.widthPx * 0.12f -> workspace.currentPage - 1
                    x > dp.widthPx * 0.88f -> workspace.currentPage + 1
                    else -> workspace.currentPage
                }
                edit.exit(page.coerceAtLeast(0))
            }
        }

        val listTop = insets.top
        appsScroll = android.widget.ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp.dpi(24f))
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // no padding: fading edges are drawn inside it, so the gap above row 1 lives in the grid instead
            // appsLayer doesn't clip its children (ripple, transitions), so clip the list to its own box:
            // otherwise rows draw behind the search pill and past the fading edge
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.clipBounds = Rect(0, 0, v.width, v.height) }
        }
        appsGrid = CellLayout(activity, dp.appsCols, 1)
        appsScroll.addView(appsGrid, FrameLayout.LayoutParams(MATCH_PARENT, dp.appsGrid.height() / dp.appsRows))
        // the list ends just above the floating search pill
        appsLayer.addView(appsScroll, FrameLayout.LayoutParams(MATCH_PARENT, dp.searchBarTop - dp.dpi(8f) - listTop).apply {
            topMargin = listTop
        })
        searchPill = SearchPill(activity, dp, activity.getString(R.string.search_hint))
        searchPill.setOnClickListener { openSearch() }
        val gap = dp.dpi(8f)
        val total = dp.searchBarWidth + gap + dp.searchBarHeight
        appsLayer.addView(searchPill, FrameLayout.LayoutParams(dp.searchBarWidth, dp.searchBarHeight).apply {
            leftMargin = (dp.widthPx - total) / 2
            topMargin = dp.searchBarTop
        })
        moreButton = SearchPill(activity, dp, "", circle = true).apply { setOnClickListener { showAppsMenu(this) } }
        appsLayer.addView(moreButton, FrameLayout.LayoutParams(dp.searchBarHeight, dp.searchBarHeight).apply {
            leftMargin = (dp.widthPx - total) / 2 + dp.searchBarWidth + gap
            topMargin = dp.searchBarTop
        })
        bound = false
        if (repo?.apps?.isNotEmpty() == true) bindAll()
    }

    override fun onAppsChanged(apps: List<AppInfo>) {
        searchSources.refresh()                        // first run: suggestions ready before search first opens
        if (prefs.zoomInOpen || prefs.wallpaperOpen) splashLook.prefetch(apps.map { it.activityInfo })
        val m = model ?: return
        if (!bound && m.items.isEmpty()) {
            if (!m.load()) { m.createDefault(dp.homeCols, dp.homeRows); firstRun = true }
        }
        m.prune()
        if (knownApps.isNotEmpty() && prefs.addNewApps) {
            // an app back from an update was "new" to the last list but kept its spot (prune): not a second icon
            val onHome = m.items.mapNotNullTo(HashSet()) { (it as? HomeItem.App)?.key }
            apps.filter { it.key !in knownApps && it.key !in onHome }.forEach { addToHome(it, rebind = false) }
        }
        knownApps = apps.map { it.key }.toHashSet()
        reflowGrid(m)
        bindAll()
    }

    private var knownApps: Set<com.oneuiclone.home.model.AppKey> = emptySet()

    /** Move items that no longer fit after a grid change into free cells (One UI keeps everything). */
    private fun reflowGrid(m: WorkspaceModel) {
        var changed = false
        for (it in m.items.filter { it.container == Container.WORKSPACE }) {
            if (it.x + it.spanX > dp.homeCols || it.y + it.spanY > dp.homeRows) {
                if (it is HomeItem.Widget) { it.sx = it.sx.coerceAtMost(dp.homeCols); it.sy = it.sy.coerceAtMost(dp.homeRows) }
                var page = it.screen
                var pos = m.findSpace(page, it.spanX, it.spanY, dp.homeCols, dp.homeRows)
                while (pos == null) { page++; if (page >= m.pageCount) m.pageCount = page + 1; pos = m.findSpace(page, it.spanX, it.spanY, dp.homeCols, dp.homeRows) }
                it.screen = page; it.x = pos.first; it.y = pos.second
                changed = true
            }
        }
        if (changed) m.save()
    }

    private var wallpaperBright = false

    /** WallpaperColors → One UI white-background mode for labels, dots and status bar. */
    fun updateWallpaperColors() {
        val colors = try { android.app.WallpaperManager.getInstance(activity).getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM) } catch (_: Exception) { null }
        wallpaperBright = colors != null && (colors.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
        val dark = colors != null && (colors.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_THEME) != 0
        applyTextMode()
        indicator.darkWallpaper = dark
    }

    private fun applyTextMode() {
        val homeDark = wallpaperBright && state.state == StateController.State.HOME && !folders.isOpen && !edit.isActive
        fun walk(g: ViewGroup, darkLabels: Boolean) {
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                if (c is IconView) c.darkText = darkLabels else if (c is ViewGroup) walk(c, darkLabels)
            }
        }
        walk(homeLayer, wallpaperBright)
        indicator.darkDots = wallpaperBright
        @Suppress("DEPRECATION")
        val flags = activity.window.decorView.systemUiVisibility
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = if (homeDark)
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        else flags and (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
    }

    private fun applyBadge(v: IconView, pkgs: List<Pair<String, android.os.UserHandle>>) {
        v.badgeNumber = prefs.badgeNumbers
        v.badgeCount = if (!prefs.badges) 0 else pkgs.sumOf { BadgeService.count(it.first, it.second) }
    }

    /** Refresh badge counts on every visible icon. */
    fun updateBadges() {
        val r = repo ?: return
        val m = model
        fun walk(g: ViewGroup) {
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                if (c is IconView) {
                    when (val it = c.item) {
                        is HomeItem.App -> r.get(it.key)?.let { a -> applyBadge(c, listOf(a.key.component.packageName to a.user)) }
                        is HomeItem.Folder -> applyBadge(c, m?.appsInFolder(it.id)?.mapNotNull { f -> r.get(f.key) }?.map { a -> a.key.component.packageName to a.user } ?: emptyList())
                        else -> c.appInfo?.let { a -> applyBadge(c, listOf(a.key.component.packageName to a.user)) }
                    }
                } else if (c is ViewGroup) walk(c)
            }
        }
        walk(homeLayer); walk(appsLayer); walk(overlay)
    }

    private val badgeListener = { updateBadges() }

    private fun bindAll() {
        bindWorkspace()
        bindApps()
        bound = true
        updateWallpaperColors()
        if (firstRun) {
            firstRun = false
            val queue = ArrayDeque(model?.defaultWidgets.orEmpty().mapNotNull { w -> defaultProvider(w)?.let { w to it } })
            if (queue.isNotEmpty()) {
                seeding = true
                root.postDelayed({ addDefaultWidgets(queue) }, 600)
            }
        }
        activity.showSetupIfDue()
    }

    /** The installed provider for a default-layout widget, if any (see WorkspaceModel.DefaultWidget). */
    private fun defaultProvider(w: com.oneuiclone.home.model.WorkspaceModel.DefaultWidget): android.appwidget.AppWidgetProviderInfo? =
        widgets.providers()
            .filter { it.provider.packageName in w.packages && it.provider.className.contains(w.classHint, ignoreCase = true) }
            .sortedBy { w.packages.indexOf(it.provider.packageName) }
            .maxByOrNull { if (it.targetCellWidth == w.sx && it.targetCellHeight == w.sy) 1 else 0 }

    /**
     * The default layout's widgets one after another: each may ask the user to allow it (one dialog at a time, and
     * none after "Always allow"); one they turn down just leaves its spot empty. Setup shows once they're all in.
     */
    private fun addDefaultWidgets(queue: ArrayDeque<Pair<com.oneuiclone.home.model.WorkspaceModel.DefaultWidget, android.appwidget.AppWidgetProviderInfo>>) {
        val (w, info) = queue.removeFirstOrNull() ?: run {
            seeding = false
            activity.showSetupIfDue()
            return
        }
        widgets.add(info, onCancel = { addDefaultWidgets(queue) }) { id, i ->
            placeWidget(id, i, 0, w.x to w.y, w.sx to w.sy)
            addDefaultWidgets(queue)
        }
    }

    fun placeWidget(appWidgetId: Int, info: android.appwidget.AppWidgetProviderInfo, page: Int = workspace.currentPage,
                    at: Pair<Int, Int>? = null, span: Pair<Int, Int>? = null) {
        val m = model ?: return
        val (sx, sy) = span ?: widgets.spanFor(info, dp)
        var p = page
        var pos = at?.takeIf { !m.isOccupied(p, it.first, it.second, sx, sy) } ?: m.findSpace(p, sx, sy, dp.homeCols, dp.homeRows)
        while (pos == null) {
            p++
            if (p >= m.pageCount) m.pageCount = p + 1
            pos = m.findSpace(p, sx, sy, dp.homeCols, dp.homeRows)
        }
        m.items += HomeItem.Widget(m.newId(), appWidgetId, info.provider, Container.WORKSPACE, p, pos.first, pos.second, sx, sy)
        m.save()
        bindWorkspace()
        workspace.post { workspace.snapToPage(p) }
    }

    private fun pagePadding(cl: CellLayout, grid: Rect) {
        cl.setPadding(grid.left, grid.top, dp.widthPx - grid.right, dp.heightPx - grid.bottom)
    }

    fun bindWorkspace() {
        val m = model ?: return
        val r = repo ?: return
        val keep = workspace.currentPage
        workspace.removeAllViews()
        val pages = maxOf(m.pageCount, (m.items.filter { it.container == Container.WORKSPACE }.maxOfOrNull { it.screen } ?: 0) + 1)
        m.pageCount = pages
        for (p in 0 until pages) {
            val cl = CellLayout(activity, dp.homeCols, dp.homeRows)
            pagePadding(cl, dp.workspaceGrid)
            workspace.addView(cl, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        hotseat.removeAllViews()
        val dock = m.items.filter { it.container == Container.HOTSEAT }.sortedBy { it.x }
        hotseat.cols = dock.size.coerceIn(1, 7)
        dock.forEachIndexed { i, it -> it.x = i }
        for (item in m.items) {
            if (item is HomeItem.Widget) {
                if (item.container != Container.WORKSPACE) continue
                val quick = com.oneuiclone.home.widgets.QuickOpenWidget.kindOf(activity, item.provider)
                val hv = (if (item.provider == bubblesProvider) bubblesView(item)
                    else if (quick != null) quickOpenView(quick)
                    else if (item.provider == quickPairProvider) quickPairView(item.appWidgetId)
                    else widgets.createView(activity, item.appWidgetId, dp, item.sx, item.sy)) ?: continue
                val wc = com.oneuiclone.home.ui.WidgetContainer(activity, item, hv, dp.widgetPaddingPx)
                applyWidgetStyle(wc)
                wc.onLongPress = { showWidgetOptions(it) }
                wc.onDragStart = { c, x, y ->
                    if (prefs.lockLayout || edit.isActive) false else {
                        popup?.dismiss(); resizeFrame?.dismiss(); ripple.reset()
                        drag.startWidget(c, x, y); true
                    }
                }
                wc.onDragMove = { x, y -> drag.move(x, y) }
                wc.onDragEnd = { drag.drop() }
                (workspace.pageAt(item.screen) as? CellLayout)?.addCell(wc, item.x, item.y, item.sx, item.sy)
                continue
            }
            val v = createItemView(item, r) ?: continue
            when (item.container) {
                Container.WORKSPACE -> { v.showLabel = prefs.homeLabels; (workspace.pageAt(item.screen) as? CellLayout)?.addCell(v, item.x, item.y, item.spanX, item.spanY) }
                Container.HOTSEAT -> { v.showLabel = false; hotseat.addCell(v, item.x, 0) }
                Container.WORKSPACE -> Unit
                Container.FOLDER -> Unit
            }
        }
        indicator.count = pages
        edit.refreshPages()
        workspace.post { workspace.setCurrentPageImmediately(keep.coerceAtMost(pages - 1)) }
        indicator.setPage(keep.coerceAtMost(pages - 1), false)
        folders.rebound { findView(it) as? com.oneuiclone.home.ui.FolderIconView }
        updateBadges()                                   // new views: an edit mustn't drop badges until the next resume
    }

    private var resizeFrame: com.oneuiclone.home.ui.WidgetResizeFrame? = null

    private fun showWidgetOptions(wc: com.oneuiclone.home.ui.WidgetContainer) {
        popup?.dismiss()
        val m = model ?: return
        val info = widgets.appWidgetManager.getAppWidgetInfo(wc.item.appWidgetId)
        val bubbles = wc.item.provider == bubblesProvider
        val iconLike = bubbles || com.oneuiclone.home.widgets.QuickOpenWidget.kindOf(activity, wc.item.provider) != null ||
            wc.item.provider == quickPairProvider
        val opts = arrayListOf(
            com.oneuiclone.home.ui.QuickOption("Remove", com.oneuiclone.home.ui.QuickOption.REMOVE) {
                m.items.remove(wc.item); widgets.delete(wc.item.appWidgetId); m.save(); bindWorkspace()
            },
        )
        // the Bubbles, Bubble and Split widgets are drawn as icons, so they follow the icon style instead of having their own
        if (!iconLike) opts += com.oneuiclone.home.ui.QuickOption("Style", com.oneuiclone.home.ui.QuickOption.STYLE) { showWidgetStyle(wc) }
        // the 2×1 pill: centred in its row like other widgets, or level with the icons beside it
        (wc.getChildAt(0) as? com.oneuiclone.home.ui.QuickOpenPairView)?.let { pair ->
            opts += com.oneuiclone.home.ui.QuickOption(if (pair.centred) "Align with icons" else "Centre in row",
                com.oneuiclone.home.ui.QuickOption.STYLE) {
                pair.centred = !pair.centred
                com.oneuiclone.home.widgets.QuickOpenWidget.setPairCentred(activity, wc.item.appWidgetId, pair.centred)
            }
        }
        if (info?.configure != null) {
            opts += com.oneuiclone.home.ui.QuickOption(if (bubbles) "Configure" else "Settings", com.oneuiclone.home.ui.QuickOption.EDIT) {
                configureWidget(wc.item.appWidgetId)
            }
        }
        if (info != null && info.resizeMode != android.appwidget.AppWidgetProviderInfo.RESIZE_NONE && !prefs.lockLayout) {
            val minX = maxOf(1, kotlin.math.ceil(info.minResizeWidth.coerceAtLeast(1) / dp.cellWidth.toFloat()).toInt())
            val minY = maxOf(1, kotlin.math.ceil(info.minResizeHeight.coerceAtLeast(1) / dp.cellHeight.toFloat()).toInt())
            resizeFrame?.dismiss()
            val f = com.oneuiclone.home.ui.WidgetResizeFrame(activity, dp, m, wc.item,
                if ((info.resizeMode and android.appwidget.AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0) minX else wc.item.sx,
                if ((info.resizeMode and android.appwidget.AppWidgetProviderInfo.RESIZE_VERTICAL) != 0) minY else wc.item.sy,
                { bindWorkspace() }, { resizeFrame = null })
            overlay.addView(f, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            resizeFrame = f
        }
        val title = info?.loadLabel(activity.packageManager) ?: "Widget"
        val p = com.oneuiclone.home.ui.QuickOptionPopup(activity, dp, iconFactory, title, emptyList(), opts, { popup = null }, null,
            { rect, done -> snapshotBehind(rect, done) })
        p.passThroughOutside = true                     // the resize handles sit under the popup's scrim
        overlay.addView(p, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val loc = IntArray(2); wc.getLocationInWindow(loc)
        p.showFor(Rect(loc[0], loc[1], loc[0] + wc.width, loc[1] + wc.height), false)
        popup = p
    }

    private fun configureWidget(appWidgetId: Int) {
        try { widgets.host.startAppWidgetConfigureActivityForResult(activity, appWidgetId, 0, 0, null) } catch (_: Exception) {}
    }

    private val bubblesProvider by lazy { com.oneuiclone.home.widgets.BubblesWidget.provider(activity) }
    /** Each widget's cascade (icons and their shadows), kept while it shows the same icon bitmaps (bindWorkspace runs often). */
    private val bubbleLayers = HashMap<Int, List<com.oneuiclone.home.widgets.BubblesWidget.Layer>>()

    /**
     * The Bubbles widget on Home: its apps' Home icons cascading back from the first, labelled like an icon and
     * following the icon style, Frosted glass included ([BubblesIconView]); it opens them in bubbles, or its
     * configuration while it has none.
     */
    private fun bubblesView(item: HomeItem.Widget): IconView {
        val bw = com.oneuiclone.home.widgets.BubblesWidget
        // the apps' own icons unless Icons › Apply to Apps screen and search is on, so they're quick to tell apart
        val themed = appsScreenThemed
        val icons = bw.apps(activity, item.appWidgetId).mapNotNull { repo?.get(it)?.let { a -> if (themed) a.icon else a.drawerIcon } }.take(bw.MAX)
        val minimal = themed && prefs.themedIcons && prefs.minimalIcons
        val stencil = themed && prefs.themedIcons && prefs.iconLook == com.oneuiclone.home.icons.IconFactory.LOOK_STENCIL
        val layers = bubbleLayers[item.appWidgetId]?.takeIf { old -> old.size == icons.size &&
            old.indices.all { old[it].icon === icons[it] && old[it].cut == minimal && (old[it].hole != null) == stencil } }
            ?: bw.layers(icons, minimal, stencil).also { bubbleLayers[item.appWidgetId] = it }
        return com.oneuiclone.home.ui.BubblesIconView(activity, dp).apply {
            this.layers = layers
            if (layers.isEmpty()) icon = bw.emptyTile(dp.iconSizePx, com.oneuiclone.home.icons.IconShape.path(dp.iconSizePx.toFloat(), com.oneuiclone.home.icons.IconShape.container))
            containerShape = layers.isEmpty()
            if (prefs.iconGlass && (themed || layers.isEmpty())) plateGlass = widgetGlass
            label = activity.getString(R.string.bubbles_widget)
            contentDescription = label
            showLabel = prefs.homeLabels
            isLongClickable = false                       // the widget container owns the long-press
            setOnClickListener { openBubbles(item.appWidgetId, this) }
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> ripple.start(homeSiblings(), ev.rawX, ev.rawY)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ripple.reverse()
                }
                false
            }
        }
    }

    /** A tap on a Bubbles widget: its apps open in bubbles, and [view] shows it until the system has answered for each. */
    private fun openBubbles(appWidgetId: Int, view: com.oneuiclone.home.ui.BubblesIconView) {
        val r = repo ?: return
        val bw = com.oneuiclone.home.widgets.BubblesWidget
        val apps = bw.apps(activity, appWidgetId).mapNotNull { r.get(it) }.filter { AppBubbles.supports(it) }.take(bw.MAX)
        if (apps.isEmpty()) { configureWidget(appWidgetId); return }
        if (bw.open(activity, apps) { i, _ -> view.appDone(i) }) view.startOpening(apps.size)
    }

    private var quickOpen: com.oneuiclone.home.ui.QuickOpenPanel? = null

    /** The Bubble / Split widgets' and panel's frosted tint (QuickOpenWidget.frost). */
    private fun quickOpenFrost() = com.oneuiclone.home.widgets.QuickOpenWidget.frost(activity, iconFactory.isNight(), prefs.iconPlateAlpha)

    /** A Bubble or Split widget's tile, in the folders' and widgets' shape. */
    private fun quickOpenTile(kind: com.oneuiclone.home.widgets.QuickOpenWidget.Kind) = com.oneuiclone.home.widgets.QuickOpenWidget.tile(
        kind, dp.iconSizePx, quickOpenFrost(), mask = com.oneuiclone.home.icons.IconShape.path(dp.iconSizePx.toFloat(), com.oneuiclone.home.icons.IconShape.container))

    /** The Bubble or Split widget on Home: its tile alone (no name), centred in its cell; a tap opens its panel ([openQuickOpen]). */
    private fun quickOpenView(kind: com.oneuiclone.home.widgets.QuickOpenWidget.Kind): IconView =
        IconView(activity, dp).apply {
            // frosted like Frosted icons: a see-through plate in the icon colour over the blurred wallpaper
            icon = quickOpenTile(kind)
            containerShape = true
            plateGlass = widgetGlass
            label = com.oneuiclone.home.widgets.QuickOpenWidget.label(activity, kind)
            contentDescription = label
            showLabel = false                             // no name: the icon sits centred in its cell
            isLongClickable = false                       // the widget container owns the long-press
            val icon = this
            setOnClickListener { openQuickOpen(kind, QuickOpenSource(
                bounds = { Rect().also { icon.iconBoundsOnScreen(it) } },
                look = { icon.displayedIcon() ?: quickOpenTile(kind) },
                // and its shadow
                hole = { Rect().also { icon.iconBoundsOnScreen(it) }.apply { inset(-width() / 8, -height() / 8) } },
                hide = { icon.iconVisible = !it })) }
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> ripple.start(homeSiblings(), ev.rawX, ev.rawY)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ripple.reverse()
                }
                false
            }
        }

    private val quickPairProvider by lazy { com.oneuiclone.home.widgets.QuickOpenWidget.pairProvider(activity) }

    /** The 2×1 Bubble & Split widget on Home: one frosted pill, each half opening its own panel, which Home zooms out behind. */
    private fun quickPairView(appWidgetId: Int): com.oneuiclone.home.ui.QuickOpenPairView =
        com.oneuiclone.home.ui.QuickOpenPairView(activity, dp, quickOpenFrost()).apply {
            glass = widgetGlass
            centred = com.oneuiclone.home.widgets.QuickOpenWidget.pairCentred(activity, appWidgetId)
            label = ""
            contentDescription = activity.getString(R.string.quick_pair_widget)
            onPress = { ev, down -> if (down) ripple.start(homeSiblings(), ev.rawX, ev.rawY) else ripple.reverse() }
            onTap = { kind -> openQuickOpen(kind, null) }
        }

    /**
     * What a 1×1 widget's panel comes out of on Home: its icon. [bounds] in window coordinates; [look] as it shows
     * (glass included); [hole] what Home's blurred picture leaves out; [hide] takes it off Home while the panel
     * stands for it.
     */
    private class QuickOpenSource(val bounds: () -> Rect, val look: () -> android.graphics.Bitmap,
                                  val hole: () -> Rect, val hide: (Boolean) -> Unit)

    /**
     * The Bubble / Split panel over Home (iOS 27's Search or Ask, mockup A): Home blurred behind it, the bar out of the
     * widget ([src]); with no [src] (the 2×1) Home shrinks and fades out behind it instead. Bubble first needs bubbles
     * set up, as the Bubbles widget does.
     */
    private fun openQuickOpen(kind: com.oneuiclone.home.widgets.QuickOpenWidget.Kind, src: QuickOpenSource?) {
        val r = repo ?: return
        val qo = com.oneuiclone.home.widgets.QuickOpenWidget
        if (quickOpen != null || quickOpenPending || search != null || edit.isActive || folders.isOpen || drag.isDragging || popup != null) return
        if (kind != com.oneuiclone.home.widgets.QuickOpenWidget.Kind.SPLIT && qo.needsBubbleSetup(activity)) return
        if (src == null) { showQuickOpen(kind, r, null, null); return }
        // Home's picture first (a frame or two), so the blur fades in with the bar instead of popping in after it
        quickOpenPending = true
        // the widget's icon flies off as the bar, so Home's blurred picture is taken without it
        val icon = src.hole()
        val loc = IntArray(2); root.getLocationInWindow(loc)
        icon.offset(-loc[0], -loc[1])
        snapshotBehind(Rect(0, 0, root.width, root.height), { shot ->
            quickOpenPending = false
            if (quickOpen == null && search == null && !edit.isActive && !folders.isOpen) showQuickOpen(kind, r, shot, src)
        }, holes = listOf(icon))
        root.postDelayed({ quickOpenPending = false }, 500)          // PixelCopy turned down: let the next tap try again
    }

    private var quickOpenPending = false

    private fun showQuickOpen(kind: com.oneuiclone.home.widgets.QuickOpenWidget.Kind, r: com.oneuiclone.home.model.AppRepository,
                              shot: android.graphics.Bitmap?, src: QuickOpenSource?) {
        val qo = com.oneuiclone.home.widgets.QuickOpenWidget
        lateinit var p: com.oneuiclone.home.ui.QuickOpenPanel
        p = com.oneuiclone.home.ui.QuickOpenPanel(activity, kind == com.oneuiclone.home.widgets.QuickOpenWidget.Kind.SPLIT, qo.candidates(r.apps), searchSources.snapshot.scores,
            backdrop = shot,
            // the icon as it shows (its frosted plate with the glass in it), for the bar to leave and land back as
            origin = src?.let { com.oneuiclone.home.ui.QuickOpenPanel.Origin(it.bounds(), it.look()) },
            glass = widgetGlass,
            frost = quickOpenFrost(),
            onBubble = { app ->
                usage.launched(app.key)
                p.close()
                qo.openBubble(activity, app) { a -> launcherApps.startMainActivity(a.key.component, a.user, null, null) }
            },
            onSplit = { top, bottom ->
                usage.launched(top.key); usage.launched(bottom.key)
                quickOpenRecents.split(top, bottom)
                // the panel stays up until the apps cover Home (onStop closes it), so Home never flashes in between
                com.oneuiclone.home.widgets.SplitLaunch.fromHome(activity, top, bottom)
                root.postDelayed({ p.close(animate = false) }, 2000)
            },
            onClosed = { _ ->
                overlay.removeView(p)
                if (quickOpen === p) quickOpen = null
                // the bar has landed exactly as the icon, so it simply takes over (no bounce)
                src?.hide(false)
                if (src == null) quickOpenHome(1f, 0f)
            },
            home = if (src == null) { scale, gone -> quickOpenHome(scale, gone) } else null,
            multi = kind == com.oneuiclone.home.widgets.QuickOpenWidget.Kind.MULTI,
            onBubbles = { apps ->
                apps.forEach { usage.launched(it.key) }
                p.close()
                com.oneuiclone.home.widgets.BubblesWidget.open(activity, apps)
            },
            // the apps' own icons unless Icons › Apply to Apps screen and search is on, so they're quick to tell apart
            iconOf = if (appsScreenThemed) { a -> a.icon } else { a -> a.drawerIcon },
            recent = if (kind == com.oneuiclone.home.widgets.QuickOpenWidget.Kind.SPLIT) quickOpenRecents.splits() else quickOpenRecents.bubbles())
        overlay.addView(p, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        quickOpen = p
        p.requestApplyInsets()
        p.open()
        src?.hide(true)                                 // the bar is the widget now, until it lands back
    }

    /** Home behind the 2×1's panel: shrunk to [scale] about its centre and [gone] faded, the wallpaper blurring in. */
    private fun quickOpenHome(scale: Float, gone: Float) {
        val away = scale != 1f || gone > 0f
        if (away && homeLayer.layerType != View.LAYER_TYPE_HARDWARE) { centrePivot(homeLayer); homeLayer.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        homeLayer.scaleX = scale; homeLayer.scaleY = scale
        homeLayer.alpha = 1f - gone
        background.set(if (away) BackgroundController.Kind.QUICK_OPEN else BackgroundController.Kind.NONE, gone)
        if (!away) homeLayer.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    /** The widget's saved style: Original, or Blur (its background faded by the transparency over [widgetGlass]). */
    private fun applyWidgetStyle(wc: com.oneuiclone.home.ui.WidgetContainer) {
        val t = com.oneuiclone.home.widgets.WidgetStyles.transparency(activity, wc.item.appWidgetId, wc.item.provider.packageName)
        val hv = wc.getChildAt(0) as? com.oneuiclone.home.widgets.WidgetManager.RoundedWidgetView ?: return
        hv.backgroundOpacity = if (t == null) 1f else 1f - t / 100f
        hv.glass = if (t == null) null else widgetGlass
    }

    /** Widget long-press › Style (also saves the widget's diagnostics file). */
    private fun showWidgetStyle(wc: com.oneuiclone.home.ui.WidgetContainer) {
        resizeFrame?.dismiss()
        saveWidgetDiagnostics(wc)
        val id = wc.item.appWidgetId
        val styles = com.oneuiclone.home.widgets.WidgetStyles
        // a Theme change re-creates the widget's views, so changes go to whichever container shows it now
        val current = { widgetContainer(id) ?: wc }
        showStyleSheet("Widget style", wc, styles.transparency(activity, id, wc.item.provider.packageName), { t -> styles.set(activity, id, t) },
            { applyWidgetStyle(current()) }) { body ->
            // Theme: the widget's light or dark version, or the system's
            val pkg = wc.item.provider.packageName
            body.addView(android.widget.TextView(activity).apply { text = "Theme"; setPadding(0, dp.dpi(16f), 0, dp.dpi(4f)) })
            val group = android.widget.RadioGroup(activity).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
            val choices = listOf(styles.THEME_AUTO to "Auto", styles.THEME_LIGHT to "Light", styles.THEME_DARK to "Dark")
            val ids = choices.map { (theme, label) ->
                val b = android.widget.RadioButton(activity).apply { text = label; this.id = View.generateViewId() }
                group.addView(b, android.widget.RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                theme to b.id
            }
            group.check(ids.first { it.first == styles.theme(activity, id, pkg) }.second)
            group.setOnCheckedChangeListener { _, checked ->
                styles.setTheme(activity, id, ids.first { it.second == checked }.first)
                bindWorkspace()                                  // re-inflates the widget in that version
            }
            body.addView(group)
        }
    }

    /** The container showing widget [appWidgetId] on the Home screen, if it's there. */
    private fun widgetContainer(appWidgetId: Int): com.oneuiclone.home.ui.WidgetContainer? {
        for (p in 0 until workspace.childCount) {
            val page = workspace.getChildAt(p) as? ViewGroup ?: continue
            for (i in 0 until page.childCount) {
                val c = page.getChildAt(i) as? com.oneuiclone.home.ui.WidgetContainer ?: continue
                if (c.item.appWidgetId == appWidgetId) return c
            }
        }
        return null
    }

    /** A folder's saved style on its closed icon: Blur shows the blurred wallpaper under its colour, faded by the transparency. */
    private fun applyFolderStyle(icon: FolderIconView, folder: HomeItem.Folder) {
        val t = com.oneuiclone.home.widgets.WidgetStyles.folderTransparency(activity, folder.id)
        icon.fillOpacity = if (t == null) 1f else 1f - t / 100f
        icon.glass = if (t == null) null else widgetGlass
    }

    /**
     * The Style sheet (widgets and folders), as One UI's widget settings: Original or Blur, and Blur's transparency
     * ([saved]: null = Original). [save] stores each change and [apply] shows it at once; the sheet sits at the
     * bottom (the top for an [item] in the lower half) without dimming Home, so the item stays in view.
     */
    private fun showStyleSheet(title: String, item: View, saved: Int?, save: (Int?) -> Unit, apply: () -> Unit,
                               extra: ((android.widget.LinearLayout) -> Unit)? = null) {
        var transparency = saved ?: com.oneuiclone.home.widgets.WidgetStyles.DEFAULT_TRANSPARENCY
        val pad = dp.dpi(24f)
        val body = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, dp.dpi(8f), pad, 0)
        }
        val value = android.widget.TextView(activity).apply { text = "Transparency  $transparency%"; setPadding(0, dp.dpi(16f), 0, 0) }
        val slider = android.widget.SeekBar(activity).apply {
            max = 100; progress = transparency
            // room for the thumb at 0 % and 100 %, which would otherwise be cut in half
            val thumb = dp.dpi(12f)
            setPadding(thumb, dp.dpi(12f), thumb, dp.dpi(8f))
        }
        val blurOn = { slider.isEnabled = true; value.alpha = 1f; slider.alpha = 1f }
        val blurOff = { slider.isEnabled = false; value.alpha = 0.4f; slider.alpha = 0.4f }
        val group = android.widget.RadioGroup(activity).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        val original = android.widget.RadioButton(activity).apply { text = "Original"; this.id = View.generateViewId() }
        val blur = android.widget.RadioButton(activity).apply { text = "Blur"; this.id = View.generateViewId() }
        group.addView(original, android.widget.RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        group.addView(blur, android.widget.RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        group.check(if (saved == null) original.id else blur.id)
        if (group.checkedRadioButtonId == original.id) blurOff() else blurOn()
        group.setOnCheckedChangeListener { _, checked ->
            if (checked == blur.id) { save(transparency); blurOn() } else { save(null); blurOff() }
            apply()
        }
        slider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                transparency = p
                value.text = "Transparency  $p%"
                if (fromUser) { save(p); apply() }
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        body.addView(group)
        body.addView(value)
        body.addView(slider)
        extra?.invoke(body)
        val dialog = android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(body)
            .setPositiveButton("Done", null)
            .create()
        val loc = IntArray(2)
        item.getLocationOnScreen(loc)
        val low = loc[1] + item.height / 2 > root.height / 2
        dialog.window?.apply {
            setGravity(if (low) android.view.Gravity.TOP else android.view.Gravity.BOTTOM)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()
    }

    /**
     * Writes how the widget is built to Download/OneUIHome-widget-<package>.txt, so a widget Style can't handle can
     * be looked at from the user's phone.
     */
    private fun saveWidgetDiagnostics(wc: com.oneuiclone.home.ui.WidgetContainer) {
        val hv = wc.getChildAt(0) as? com.oneuiclone.home.widgets.WidgetManager.RoundedWidgetView ?: return
        val text = try { hv.describe() } catch (e: Exception) { "describe failed: $e" }
        val pkg = wc.item.provider.packageName
        backdropHandler.post {
            try {
                val resolver = activity.contentResolver
                val downloads = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val name = "OneUIHome-widget-$pkg.txt"
                resolver.delete(downloads, "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name))
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                }
                resolver.insert(downloads, values)?.let { uri -> resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            } catch (e: Exception) {
                android.util.Log.w("OneUiHome", "widget diagnostics failed", e)
            }
        }
    }

    private fun createItemView(item: HomeItem, r: AppRepository): IconView? = when (item) {
        is HomeItem.App -> {
            val app = r.get(item.key) ?: return null
            IconView(activity, dp).apply {
                this.item = item
                icon = app.icon
                // glass icons: the blurred wallpaper under the see-through plate (or Stencil's cut-out)
                if (prefs.iconGlass) plateGlass = widgetGlass
                label = app.label
                contentDescription = app.label
                setOnClickListener { launch(this, app) }
                attachItem(this, item, app, false) { homeSiblings() }
            }
        }
        is HomeItem.Widget -> null
        is HomeItem.Folder -> if (item.large) {
            val content = model!!.appsInFolder(item.id).mapNotNull { r.get(it.key) }
            com.oneuiclone.home.ui.LargeFolderIconView(activity, dp).apply {
                this.item = item
                label = item.title
                apps = content.map { it.icon to it }
                if (item.color != 0) bgColor = item.color
                applyFolderStyle(this, item)
                setOnClickListener {
                    val i = hitApp(lastTouchX, lastTouchY)
                    if (i >= 0) launch(this, content[i]) else folders.open(this, item, content, model!!.appsInFolder(item.id).filter { r.get(it.key) != null })
                }
                attachItem(this, item, null, false) { homeSiblings() }
            }
        } else {
            val content = model!!.appsInFolder(item.id).mapNotNull { r.get(it.key) }
            FolderIconView(activity, dp).apply {
                this.item = item
                label = item.title
                previews = content.map { it.icon }
                if (item.color != 0) bgColor = item.color
                applyFolderStyle(this, item)
                setOnClickListener {
                    val its = model!!.appsInFolder(item.id).filter { r.get(it.key) != null }
                    folders.open(this, item, its.mapNotNull { r.get(it.key) }, its)
                }
                attachItem(this, item, null, false) { homeSiblings() }
            }
        }
    }

    private fun homeSiblings(): List<View> {
        val out = ArrayList<View>()
        (workspace.pageAt(workspace.currentPage) as? ViewGroup)?.let { g -> for (i in 0 until g.childCount) out += g.getChildAt(i) }
        for (i in 0 until hotseat.childCount) out += hotseat.getChildAt(i)
        return out
    }

    /** Apps-screen icons currently on screen (the ripple only moves what can be seen). */
    private fun appsSiblings(): List<View> {
        val top = appsScroll.scrollY - appsGrid.top
        val bottom = top + appsScroll.height
        return (0 until appsGrid.childCount).map { appsGrid.getChildAt(it) }.filter { it.bottom > top && it.top < bottom }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachRipple(v: View, siblings: () -> List<View>) = attachItem(v as IconView, null, null, false, siblings)

    /**
     * Touch handling shared by every icon: 8.5 ripple on press, long-press → quick options, then drag once
     * the finger moves past the touch slop (popup dismisses, One UI behaviour).
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachItem(v: IconView, item: HomeItem?, app: AppInfo?, fromApps: Boolean, siblings: () -> List<View>) {
        v.setOnLongClickListener {
            ripple.reverse()
            v.parent?.requestDisallowInterceptTouchEvent(true)
            showQuickOptions(v, item, app, fromApps)
            dragArmed = v
            true
        }
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    armX = ev.rawX; armY = ev.rawY
                    app?.let { shortcutLoader.prefetch(it) }      // ready by the time a long-press fires
                    ripple.start(siblings(), ev.rawX, ev.rawY)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (drag.isDragging) { drag.move(ev.rawX, ev.rawY); return@setOnTouchListener true }
                    if (dragArmed === v && !(prefs.lockLayout && !fromApps) && kotlin.math.hypot(ev.rawX - armX, ev.rawY - armY) > slop) {
                        popup?.dismiss()
                        ripple.reset()                           // neighbours may slide aside to make room
                        if (fromApps) state.setImmediate(StateController.State.HOME)
                        // from an open folder: it stays up while the drag is on it, to rearrange its apps
                        drag.start(v, item, app, fromApps, ev.rawX, ev.rawY,
                            if (item?.container == Container.FOLDER) folders.beginDrag(v) else null)
                        return@setOnTouchListener true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ripple.reverse()
                    val wasDragging = drag.isDragging
                    if (wasDragging) { drag.drop(); folders.finishDetached() }
                    dragArmed = null
                    if (wasDragging) return@setOnTouchListener true
                }
            }
            false
        }
    }

    private fun showQuickOptions(v: IconView, item: HomeItem?, app: AppInfo?, fromApps: Boolean) {
        popup?.dismiss()
        val m = model ?: return
        val opts = ArrayList<com.oneuiclone.home.ui.QuickOption>()
        // (a folder's row is full with its own options, and Select does nothing yet)
        if (item !is HomeItem.Folder || fromApps) opts += com.oneuiclone.home.ui.QuickOption("Select", com.oneuiclone.home.ui.QuickOption.SELECT) {}
        if (fromApps && app != null) {
            opts += com.oneuiclone.home.ui.QuickOption("Add to Home", com.oneuiclone.home.ui.QuickOption.ADD) { addToHome(app) }
        } else if (item != null && !prefs.lockLayout) {
            opts += com.oneuiclone.home.ui.QuickOption("Remove", com.oneuiclone.home.ui.QuickOption.REMOVE) {
                m.items.remove(item)
                if (item is HomeItem.Folder) m.items.removeAll { it is HomeItem.App && it.folderId == item.id }
                m.save(); bindWorkspace()
            }
        }
        if (item is HomeItem.Folder && !fromApps) {
            opts += com.oneuiclone.home.ui.QuickOption(if (item.large) "Shrink" else "Enlarge", com.oneuiclone.home.ui.QuickOption.EDIT) {
                toggleLargeFolder(item)
            }
            opts += com.oneuiclone.home.ui.QuickOption("Rename", com.oneuiclone.home.ui.QuickOption.RENAME) { renameFolder(item) }
            opts += com.oneuiclone.home.ui.QuickOption("Add", com.oneuiclone.home.ui.QuickOption.ADD) { addAppsToFolder(item) }
            opts += com.oneuiclone.home.ui.QuickOption("Color", com.oneuiclone.home.ui.QuickOption.SELECT) { pickFolderColor(item) }
            opts += com.oneuiclone.home.ui.QuickOption("Style", com.oneuiclone.home.ui.QuickOption.STYLE) {
                showStyleSheet("Folder style", v, com.oneuiclone.home.widgets.WidgetStyles.folderTransparency(activity, item.id),
                    { t -> com.oneuiclone.home.widgets.WidgetStyles.setFolder(activity, item.id, t) },
                    { (v as? FolderIconView)?.let { applyFolderStyle(it, item) } })
            }
        }
        if (app != null && prefs.appBubbles && AppBubbles.supports(app)) {
            opts += com.oneuiclone.home.ui.QuickOption("Bubble", com.oneuiclone.home.ui.QuickOption.BUBBLE) { bubble(v, app) }
        }
        if (app != null && (app.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) {
            opts += com.oneuiclone.home.ui.QuickOption("Uninstall", com.oneuiclone.home.ui.QuickOption.UNINSTALL) {
                activity.startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.fromParts("package", app.key.component.packageName, null)))
            }
        }
        val title = when { app != null -> app.label; item is HomeItem.Folder -> item.title; else -> "" }
        val p = com.oneuiclone.home.ui.QuickOptionPopup(activity, dp, iconFactory, title,
            app?.let { shortcutLoader.get(it) } ?: emptyList(), opts, { popup = null },
            app?.let { a -> { launcherApps.startAppDetailsActivity(a.key.component, a.user, null, null) } },
            { rect, done -> snapshotBehind(rect, done) })
        overlay.addView(p, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val r = Rect(); v.iconBoundsOnScreen(r)
        p.showFor(r, v.parent === hotseat)
        popup = p
    }

    private val backdropThread = android.os.HandlerThread("backdrop").apply { start() }
    private val backdropHandler = android.os.Handler(backdropThread.looper)

    /**
     * What's behind [rect] (window coords) at 1/4 scale, for the popup's blurred glass. PixelCopy reads the last
     * frame on the GPU instead of redrawing the whole screen in software on the main thread (8–60 ms per
     * long-press); the window is translucent, so the wallpaper copy is composited underneath off-thread.
     */
    fun snapshotBehind(rect: Rect, done: (android.graphics.Bitmap) -> Unit, scale: Float = 0.25f, holes: List<Rect> = emptyList()) {
        if (rect.isEmpty) return
        val r = Rect(rect).apply { intersect(0, 0, root.width, root.height) }
        if (r.isEmpty) return
        val w = maxOf(1, (r.width() * scale).toInt())
        val h = maxOf(1, (r.height() * scale).toInt())
        val shot = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(activity.window, r, shot, { result ->
                if (result != android.view.PixelCopy.SUCCESS) return@request
                val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(out)
                c.save()
                c.scale(w / r.width().toFloat(), h / r.height().toFloat())
                c.translate(-r.left.toFloat(), -r.top.toFloat())
                if (!wallpaper.drawSnapshot(c)) c.drawColor(0xFF2A2A30.toInt())
                c.restore()
                c.drawBitmap(shot, 0f, 0f, null)
                // [holes] (window coords) show the wallpaper alone: what's there is about to leave (the Bubble /
                // Split widget's icon turns into the panel's bar), so the picture mustn't keep a copy of it
                for (hole in holes) {
                    c.save()
                    c.scale(w / r.width().toFloat(), h / r.height().toFloat())
                    c.translate(-r.left.toFloat(), -r.top.toFloat())
                    c.clipRect(hole)
                    if (!wallpaper.drawSnapshot(c)) c.drawColor(0xFF2A2A30.toInt())
                    c.restore()
                }
                root.post { done(out) }
            }, backdropHandler)
        } catch (_: Exception) {}
    }

    private fun toggleLargeFolder(f: HomeItem.Folder) {
        val m = model ?: return
        if (f.large) { f.large = false; m.save(); bindWorkspace(); return }
        if (f.container != Container.WORKSPACE) { Toast.makeText(activity, "Only folders on the Home screen can be enlarged.", Toast.LENGTH_SHORT).show(); return }
        // keep the folder's cell as the top-left if possible, otherwise shift up/left
        val cands = listOf(f.x to f.y, f.x - 1 to f.y, f.x to f.y - 1, f.x - 1 to f.y - 1)
            .filter { (x, y) -> x >= 0 && y >= 0 && x + 2 <= dp.homeCols && y + 2 <= dp.homeRows }
        val spot = cands.firstOrNull { (x, y) -> !m.isOccupied(f.screen, x, y, 2, 2, f) }
        if (spot == null) { Toast.makeText(activity, "Not enough space to enlarge this folder.", Toast.LENGTH_SHORT).show(); return }
        f.x = spot.first; f.y = spot.second; f.large = true
        m.save(); bindWorkspace()
    }

    /** Folder long-press › Rename (the pop-up folder has no name field to edit). */
    private fun renameFolder(f: HomeItem.Folder) {
        val field = android.widget.EditText(activity).apply {
            setText(f.title)
            setSelectAllOnFocus(true)
            isSingleLine = true
        }
        val box = FrameLayout(activity).apply {
            setPadding(dp.dpi(24f), dp.dpi(8f), dp.dpi(24f), 0)
            addView(field)
        }
        val dialog = android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Rename folder")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                f.title = field.text.toString().trim()
                model?.save(); bindWorkspace()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        // the keyboard's done key saves too
        field.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        field.setOnEditorActionListener { _, _, _ -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.performClick(); true }
        dialog.show()
        field.requestFocus()
    }

    private fun pickFolderColor(f: HomeItem.Folder) {
        val names = arrayOf("White", "Blue", "Green", "Orange", "Yellow")
        android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Folder color")
            .setItems(names) { _, i ->
                f.color = if (i == 0) 0 else com.oneuiclone.home.ui.FolderIconView.PALETTE[i]
                model?.save(); bindWorkspace()
            }.show()
    }

    private fun addToHome(app: AppInfo, rebind: Boolean = true) {
        val m = model ?: return
        for (page in 0 until m.pageCount) for (y in 0 until dp.homeRows) for (x in 0 until dp.homeCols) {
            if (!m.isOccupied(page, x, y)) {
                m.items += HomeItem.App(m.newId(), app.key, Container.WORKSPACE, page, x, y)
                m.save(); if (rebind) bindWorkspace(); return
            }
        }
        m.pageCount++
        m.items += HomeItem.App(m.newId(), app.key, Container.WORKSPACE, m.pageCount - 1, 0, 0)
        m.save(); if (rebind) bindWorkspace()
    }

    /** Icons › Apply to Apps screen and search: there (and in the Bubble and Split panels) apps get Home's icons, not their own. */
    private val appsScreenThemed get() = (prefs.themedIcons || prefs.darkIcons) && prefs.themeAppsScreen

    /**
     * Apps screen and search icons: the app's own colours, or Home's (theme, glass or dark) with Icon theme ›
     * Apply to Apps screen and search on.
     */
    private fun setAppsIcon(v: IconView, app: AppInfo) {
        val themed = appsScreenThemed
        v.icon = if (themed) app.icon else app.drawerIcon
        if (themed && prefs.iconGlass) v.plateGlass = widgetGlass
    }

    fun bindApps() {
        val r = repo ?: return
        val apps = r.apps                                       // already sorted A–Z (Collator)
        val rows = maxOf(1, (apps.size + dp.appsCols - 1) / dp.appsCols)
        appsGrid.removeAllViews()
        appsGrid.rows = rows
        val topGap = dp.appsGrid.top - insets.top
        appsGrid.setPadding(dp.appsGrid.left, topGap, dp.widthPx - dp.appsGrid.right, 0)
        appsGrid.layoutParams = appsGrid.layoutParams.apply { height = topGap + rows * (dp.appsGrid.height() / dp.appsRows) }
        apps.forEachIndexed { i, app ->
            appsGrid.addCell(IconView(activity, dp).apply {
                setAppsIcon(this, app)
                label = app.label
                contentDescription = app.label
                appInfo = app
                showLabel = prefs.appsLabels
                setOnClickListener { launch(this, app) }
                attachItem(this, null, app, true) { appsSiblings() }
            }, i % dp.appsCols, i / dp.appsCols)
        }
        updateBadges()
    }

    // ---------------------------------------------------------------------------------------------
    // launching (HighEndOpenParams: home scale 0.85 / 800 ms / (0.4,0,0,1), shortened to 280 ms cubic-out; close content 350 ms)
    // ---------------------------------------------------------------------------------------------

    /** An open folder stays up and zooms with the open ([openContent]); onStop closes it once Home is off screen. */
    fun launch(v: IconView, app: AppInfo) {
        if (AppBubbles.inSingleTaskBubble(activity, app)) {
            AnimLog.log("tap ${app.label}: single-task app in a bubble, opening the bubble")
            usage.launched(app.key)
            AppBubbles.show(activity, app) { start(v, app) }
            return
        }
        start(v, app)
    }

    private fun start(v: IconView, app: AppInfo) {
        val bounds = Rect()
        v.iconBoundsOnScreen(bounds)
        usage.launched(app.key)
        open(app.label.toString(), app, "Can't open ${app.label}", v) { launcherApps.startMainActivity(app.key.component, app.user, bounds, null) }
    }

    /** Quick options › Bubble; the first time, setup instead (notification permission, then bubble settings). */
    private fun bubble(v: IconView, app: AppInfo) {
        if (!AppBubbles.ready(activity)) {
            AppBubbles.missing(activity)?.let { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() }
            AppBubbles.setUp(activity)
            return
        }
        usage.launched(app.key)
        AppBubbles.show(activity, app) {
            Toast.makeText(activity, "${app.label} can't open in a bubble", Toast.LENGTH_SHORT).show()
            launch(v, app)
        }
    }

    /** Opens something that isn't an app icon (a search contact) from [v]. */
    private fun launchFrom(v: View, start: (Rect, android.os.Bundle?) -> Unit) {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val bounds = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        open("shortcut/contact", null, "Can't open that") { start(bounds, null) }
    }

    /**
     * A widget click that opens an app gets the same open as an icon (and its close lands back on the widget).
     * Clicks that only send a broadcast or start a service (play/pause, toggles) are left alone.
     */
    private fun launchFromWidget(appWidgetId: Int, sender: android.content.IntentSender, send: () -> Unit): Boolean {
        val activityLaunch = opensActivity(sender)
        AnimLog.log("widget click from ${sender.creatorPackage}: " + if (activityLaunch) "opens an app" else "not an app launch, left alone")
        if (!activityLaunch) return false
        val provider = widgets.appWidgetManager.getAppWidgetInfo(appWidgetId)?.provider?.packageName
        val packages = setOfNotNull(sender.creatorPackage, provider)
        val launch = WidgetLaunch(appWidgetId, packages)
        // Zoom In grows the card out of the widget, as the app that owns it (its splash colour and icon): where
        // the click goes isn't public, but a widget's clicks open its own app
        val widget = (widgetTarget(launch)?.view as? com.oneuiclone.home.ui.WidgetContainer)?.getChildAt(0)
        val owner = repo?.apps?.let { apps ->
            apps.firstOrNull { it.key.component.packageName == sender.creatorPackage && it.user == sender.creatorUserHandle }
                ?: apps.firstOrNull { it.key.component.packageName == provider }
        }
        open("widget ${sender.creatorPackage}", null, "Can't open that", widget = widget, widgetApp = owner) { send() }
        lastWidgetLaunch = launch
        return true
    }

    /** IntentSender and PendingIntent parcel the same binder, so the public PendingIntent type check applies. */
    private fun opensActivity(sender: android.content.IntentSender): Boolean {
        val p = android.os.Parcel.obtain()
        return try {
            sender.writeToParcel(p, 0)
            p.setDataPosition(0)
            android.app.PendingIntent.readPendingIntentOrNullFromParcel(p)?.isActivity == true
        } catch (_: Exception) {
            false
        } finally {
            p.recycle()
        }
    }

    /**
     * A gesture contract has come at least once, so this phone's recents lands closing apps on their icons with
     * Home showing (the Pixel's). Kept apart from the settings, whose changes rebuild Home.
     */
    private val closeState = activity.getSharedPreferences("close_state", android.content.Context.MODE_PRIVATE)
    private var contractSeen = closeState.getBoolean(KEY_CONTRACT_SEEN, false)
    /** Home was stopped behind an app with a close zoom armed, and the system hasn't finished putting it back. */
    private var homeCovered = false
    /** The close zoom is held until Home is on screen again (see [playReturnAnimation]). */
    private var returnWaiting = false
    private val returnWaitCap = Runnable { homeShown("wait cap") }

    private fun homeShown(source: String) {
        homeCovered = false
        if (!returnWaiting) return
        returnWaiting = false
        root.removeCallbacks(returnWaitCap)
        AnimLog.log("Home shown ($source): return animation")
        playReturnAnimation(shown = true)
    }

    /** Until when the system may still be drawing a close into Home (cleared early by [closeFinished]). */
    private var closingUntil = 0L
    /**
     * Home's window was hidden (or Home stopped) since the last open. If not, the open was interrupted before the app
     * covered Home and there's no system close coming back into it: a tap on the way back needn't wait for one.
     */
    private var hiddenSinceOpen = true
    private var pendingLaunch: (() -> Unit)? = null
    private val launchFallback = Runnable { closeFinished("wait cap") }

    private fun markClosing(why: String) {
        closingUntil = android.os.SystemClock.uptimeMillis() + CLOSE_MAX_MS
        AnimLog.log("closing window opened ($why)")
    }

    /**
     * The system's close into Home has finished (the activity's enter transition completed). A launch tapped while
     * the close was still running goes out now. The gesture contract's finish callback is too early for this: it
     * can come ~200 ms before the recents transition ends, and the home intent's own transition can follow it.
     */
    fun closeFinished(source: String = "enter complete") {
        AnimLog.log("close finished ($source) heldLaunch=${pendingLaunch != null}")
        closingUntil = 0L
        root.removeCallbacks(launchFallback)
        if (source != "wait cap") homeShown("enter complete")
        val start = pendingLaunch ?: return
        pendingLaunch = null
        start()
    }

    /**
     * Home zooms out at once, but an app started while the system is still closing the previous one is merged
     * into that close and snaps in without its open animation. So the start waits for the close to finish (at
     * most [LAUNCH_WAIT_MS]); the close itself is the system's and can't be sped up. While it waits, Home only
     * shrinks part of the way, and the rest of the zoom plays from the start, in step with the app fading in.
     */
    private fun open(label: String, app: AppInfo?, failure: String, icon: IconView? = null,
                     widget: View? = null, widgetApp: AppInfo? = null, start: () -> Unit) {
        val tapAt = android.os.SystemClock.uptimeMillis()
        val closingFor = closingUntil - tapAt
        val hold = closingFor > 0
        // Zoom In needs an app icon or a widget to grow from (search contacts open with Smooth), and an app that
        // covers Home: a translucent one would leave the card showing behind it
        val look = when {
            !prefs.zoomInOpen && !prefs.wallpaperOpen -> null
            icon != null && app != null -> timedLook(app)
            widget != null && widgetApp != null -> widgetLook(widgetApp).also {
                AnimLog.log("zoom in: widget card colour #%06X from %s".format(it.color and 0xFFFFFF, it.source))
            }
            else -> null
        }
        // Aura, Shutter, Orbit… are drawn by Launchii's live wallpaper; with another wallpaper they're Zoom In
        val effect = if (look != null && !look.translucent && com.oneuiclone.home.ui.LiveWallpaper.drives) prefs.openEffect else null
        val zoomIn = look != null && !look.translucent && effect == null
        AnimLog.log("tap $label " + (if (hold) "during close (window ${closingFor}ms left) -> hold" else "-> start now") +
            if (effect != null) " ($effect)" else if (zoomIn) " (zoom in)" else "")
        launched(app) {
            // a held Zoom In or wallpaper-drawn launch just waits with its card or effect up, like a slow app
            if (effect != null && icon != null) effectOpen(icon, app!!, effect, look!!.color)
            else if (effect != null) effectOpenFromWidget(widget!!, widgetApp!!, effect, look!!.color)
            else if (zoomIn && icon != null) zoomIn(icon, app!!, look!!.color)
            else if (zoomIn) zoomInFromWidget(widget!!, widgetApp!!, look!!.color)
            else zoomOpen(if (hold) OPEN_HOLD_SCALE else OPEN_SCALE, if (hold) LAUNCH_WAIT_MS else OPEN_ZOOM_MS)
        }
        logFrames()
        val go = {
            try {
                AnimLog.log("start $label after ${android.os.SystemClock.uptimeMillis() - tapAt}ms")
                if (hold && !zoomIn && effect == null) zoomOpen(OPEN_SCALE, OPEN_ZOOM_MS)
                start()
                if (launchCard != null) { root.removeCallbacks(cardTimeout); root.postDelayed(cardTimeout, CARD_TIMEOUT_MS) }
                if (effectContent != null) { root.removeCallbacks(effectTimeout); root.postDelayed(effectTimeout, CARD_TIMEOUT_MS) }
            } catch (e: Exception) {
                AnimLog.log("start $label failed: $e")
                Toast.makeText(activity, failure, Toast.LENGTH_SHORT).show()
                cancelOpen()
            }
        }
        if (hold) {
            pendingLaunch = go
            root.removeCallbacks(launchFallback)
            root.postDelayed(launchFallback, LAUNCH_WAIT_MS)
        } else {
            pendingLaunch = null
            go()
        }
    }

    /** The zoomed content's scale on each frame for 800 ms after a tap (F: the folder is still up), as one log line. */
    private fun logFrames() {
        val t0 = android.os.SystemClock.uptimeMillis()
        val sb = StringBuilder("frames after tap (ms:scale):")
        var maxGap = 0L
        var prev = t0
        android.view.Choreographer.getInstance().postFrameCallback(object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val now = android.os.SystemClock.uptimeMillis()
                maxGap = maxOf(maxGap, now - prev); prev = now
                sb.append(' ').append(now - t0).append(':').append("%.2f".format(openContent().scaleX)).append(if (folders.isOpen) "F" else "")
                effectContent?.let { sb.append("/a %.2f".format(it.alpha)) }
                launchCard?.let { sb.append("/card %.2f".format(it.progress)) }
                if (now - t0 < 800) android.view.Choreographer.getInstance().postFrameCallback(this)
                else AnimLog.log("$sb | longest frame gap ${maxGap}ms")
            }
        })
    }

    /** What the open zoom scales: an open folder (Home is faded out behind it), otherwise the current layer. */
    private fun openContent(): View = when {
        folders.isOpen -> overlay
        state.state == StateController.State.APPS -> appsLayer
        else -> homeLayer
    }

    private var launchCard: com.oneuiclone.home.ui.LaunchCard? = null
    /** The tapped icon, hidden while the card stands in for it. */
    private var cardIcon: IconView? = null
    private var cardAnim: ValueAnimator? = null
    private val splashLook = com.oneuiclone.home.icons.SplashLook(activity)
    private val cardTimeout = Runnable {
        AnimLog.log("zoom in: Home still not covered ${CARD_TIMEOUT_MS}ms after the start, card faded")
        dropLaunchCard(fade = true)
    }

    /**
     * Zoom In open: the icon grows into a card that ends as the app's splash screen while Home zooms toward the
     * icon and dims, the wallpaper zooming with it. All three share one spring, started on the tap. Android's own
     * crossfade to the app then plays over it whenever the app is ready.
     */
    private fun timedLook(app: AppInfo): com.oneuiclone.home.icons.SplashLook.Look {
        val cached = splashLook.isCached(app.activityInfo)
        val t0 = System.nanoTime()
        val look = splashLook.look(app.activityInfo)
        AnimLog.log("zoom in: splash colour #%06X from %s%s (%s, %.1fms)".format(look.color and 0xFFFFFF, look.source,
            if (look.translucent) ", translucent -> smooth" else "", if (cached) "cached" else "resolved now", (System.nanoTime() - t0) / 1e6))
        return look
    }

    /**
     * The card colour for a Zoom In from one of [app]'s widgets: its ordinary screens' background. Apps that go
     * dark at run time (Keep) declare a light background they don't show in dark mode, so in dark mode a light
     * colour isn't trusted: the launch screen's colour is used if it's dark, else a neutral dark surface. A dark
     * colour in light mode is kept (Clock is always dark).
     */
    private fun widgetLook(app: AppInfo): com.oneuiclone.home.icons.SplashLook.Look {
        val night = iconFactory.isNight()
        fun fits(c: Int) = !night || android.graphics.Color.luminance(c) < 0.35f
        val surface = splashLook.surfaceLook(app.activityInfo)
        if (fits(surface.color)) return surface
        val splash = splashLook.look(app.activityInfo)
        if (fits(splash.color)) return com.oneuiclone.home.icons.SplashLook.Look(splash.color, false,
            "${splash.source} (${surface.source} doesn't fit the mode)")
        val neutral = activity.getColor(if (night) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_10)
        return com.oneuiclone.home.icons.SplashLook.Look(neutral, false, "neutral surface (neither ${surface.source} nor ${splash.source} fits the mode)")
    }

    private fun zoomIn(v: IconView, app: AppInfo, splashColor: Int) {
        val icon = Rect()
        v.iconBoundsOnScreen(icon)
        // a see-through (glass) icon: the card comes in half see-through too, so it grows out of the glass
        val glass = v.plateGlass != null && v.icon === app.icon && app.icon !== app.drawerIcon
        // a Minimal icon has no plate: the card starts invisible behind the glyph and fills in as it grows
        val minimal = prefs.themedIcons && prefs.minimalIcons && v.icon === app.icon && app.icon !== app.drawerIcon
        // a dark icon: the card starts as its dark plate
        val dark = prefs.darkIcons && v.icon === app.icon && app.icon !== app.drawerIcon
        // a Dot, Prism or Stencil icon: the card starts as its plate
        val look = prefs.themedIcons && prefs.iconLook.isNotEmpty() && v.icon === app.icon && app.icon !== app.drawerIcon
        val fromAlpha = when {
            minimal -> com.oneuiclone.home.ui.LaunchCard.MINIMAL_FROM_ALPHA
            // a Stencil icon's cut-out shows the wallpaper: an opaque card under it would fill the symbol in at the tap
            look && prefs.iconLook == com.oneuiclone.home.icons.IconFactory.LOOK_STENCIL -> com.oneuiclone.home.ui.LaunchCard.STENCIL_FROM_ALPHA
            look && prefs.iconLook in com.oneuiclone.home.icons.IconFactory.PLAYFUL -> com.oneuiclone.home.ui.LaunchCard.PLAYFUL_FROM_ALPHA
            glass -> com.oneuiclone.home.ui.LaunchCard.GLASS_FROM_ALPHA
            else -> 1f
        }
        zoomIn(icon, v.displayedIcon(), app, splashColor, v, fromAlpha,
            when {
                dark -> com.oneuiclone.home.icons.IconFactory.DARK_PLATE
                look -> com.oneuiclone.home.icons.IconFactory.lookCard(prefs.iconLook, com.oneuiclone.home.icons.IconFactory.lookNight(prefs.iconMode, iconFactory.isNight()))
                else -> null
            })
    }

    /** Zoom In from a widget: the card grows out of the widget's shape and fades in over it, with no app icon on it. */
    private fun zoomInFromWidget(widget: View, app: AppInfo, splashColor: Int) {
        val loc = IntArray(2)
        widget.getLocationOnScreen(loc)
        val from = Rect(loc[0], loc[1], loc[0] + (widget.width * homeLayer.scaleX).toInt(), loc[1] + (widget.height * homeLayer.scaleY).toInt())
        zoomIn(from, null, app, splashColor, null)
    }

    /**
     * The Zoom In card from [from] (screen coordinates). [v]: the tapped icon, hidden while the card stands in for
     * it; null for a widget, which the card fades in over instead.
     */
    private fun zoomIn(from: Rect, iconBitmap: android.graphics.Bitmap?, app: AppInfo, splashColor: Int, v: IconView?,
                       fromAlpha: Float = 1f, fromColor: Int? = null) {
        // tapped again while its card was still shrinking back: the new card carries on from there, no restart
        val old = launchCard?.takeIf { it.retracting && v != null && cardIcon === v }
        dropLaunchCard(fade = false)
        val content = openContent()
        val icon = Rect(from)
        val rootLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)
        icon.offset(-rootLoc[0], -rootLoc[1])
        // Home zooms around the icon so it stays under the card; mid-close (already scaled) it keeps its pivot
        val pivot = if (content.scaleX == 1f) {
            val loc = IntArray(2)
            content.getLocationInWindow(loc)
            android.graphics.PointF(icon.exactCenterX() + rootLoc[0] - loc[0], icon.exactCenterY() + rootLoc[1] - loc[1])
        } else null
        val card = com.oneuiclone.home.ui.LaunchCard(activity).apply {
            this.from.set(icon)
            color = splashColor
            this.fromAlpha = fromAlpha
            this.fromColor = fromColor
            this.icon = iconBitmap
            if (v != null) {
                startRadius = icon.width() * com.oneuiclone.home.icons.IconShape.corner(com.oneuiclone.home.ui.LaunchCard.ICON_RADIUS)
                // the card starts as the icon's own shape
                if (!com.oneuiclone.home.icons.IconShape.isSquircle) fromShape = com.oneuiclone.home.icons.IconShape.current
            } else {
                startRadius = 26f * activity.resources.displayMetrics.density        // the widget's clip (RoundedWidgetView)
                fromIconPx = dp.iconSizePx.toFloat()
                fadeIn = true
            }
            endRadius = root.rootWindowInsets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 0f
            endScale = ZOOM_IN_CARD_SCALE
            // the splash's 160 dp circle, centred on the screen when the spring settles
            splashIconPx = 160f * activity.resources.displayMetrics.density
            if (old != null) { progress = old.progress; old.splashIcon?.let { splashIcon = it } }
        }
        AnimLog.log("zoom in: card from ${if (v == null) "widget " else ""}${icon.toShortString()}" + (if (fromAlpha < 1f) ", glass or minimal icon: from $fromAlpha opacity" else "") +
            if (old != null) ", picked up while shrinking back at %.2f".format(old.progress) else "")
        root.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        launchCard = card
        v?.iconVisible = false
        cardIcon = v
        val loadAt = android.os.SystemClock.uptimeMillis()
        if (v != null) splashLook.loadIcon(app.activityInfo) { d ->
            AnimLog.log("zoom in: full icon after ${android.os.SystemClock.uptimeMillis() - loadAt}ms" + if (launchCard === card) "" else " (card gone)")
            if (launchCard === card) card.splashIcon = d
        }

        val spring = Easing.spring(ZOOM_IN_STIFFNESS, ZOOM_IN_DAMPING, ZOOM_IN_MS)
        animateContentScale(content, ZOOM_IN_SCALE, ZOOM_IN_MS, spring, pivot)
        animateWallpaperZoom(wallpaperOpenZoom, ZOOM_IN_MS, spring)
        var barsSet = false
        cardAnim = ValueAnimator.ofFloat(card.progress, 1f).apply {
            duration = ZOOM_IN_MS
            interpolator = spring
            addUpdateListener {
                card.progress = it.animatedValue as Float
                // once the card fills most of the screen the status and navigation bars go dark on a light card
                if (!barsSet && card.progress > 0.6f) { barsSet = true; setLightBars(card.isLight) }
            }
            start()
        }
    }

    @Suppress("DEPRECATION")
    private fun setLightBars(light: Boolean) {
        val bars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val decor = activity.window.decorView
        decor.systemUiVisibility = if (light) decor.systemUiVisibility or bars else decor.systemUiVisibility and bars.inv()
    }

    /** Removes the Zoom In card (at once when Home is out of sight, faded otherwise) and gives the icon back. */
    private fun dropLaunchCard(fade: Boolean) {
        root.removeCallbacks(cardTimeout)
        val card = launchCard ?: return
        launchCard = null
        cardAnim?.cancel()
        cardAnim = null
        cardIcon?.takeIf { contractTarget?.view !== it }?.iconVisible = true
        cardIcon = null
        if (fade) card.animate().alpha(0f).setDuration(150).withEndAction { root.removeView(card) }.start()
        else root.removeView(card)
        applyTextMode()
    }

    /**
     * The open was interrupted before the app covered Home: the card shrinks back into its icon from wherever it
     * is (One UI / iOS), rather than standing full screen until it fades. False when there's no card.
     */
    private fun retractLaunchCard(why: String): Boolean {
        val card = launchCard ?: return false
        if (card.retracting) return true
        root.removeCallbacks(cardTimeout)
        card.retracting = true
        cardAnim?.cancel()
        AnimLog.log("zoom in: open interrupted ($why) at card %.2f, back into the icon".format(card.progress))
        var barsReset = false
        cardAnim = ValueAnimator.ofFloat(card.progress, 0f).apply {
            duration = CARD_RETRACT_MS
            interpolator = Easing.APP_CLOSE_POSITION
            addUpdateListener {
                card.progress = it.animatedValue as Float
                if (!barsReset && card.progress < 0.6f) { barsReset = true; applyTextMode() }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { if (launchCard === card) dropLaunchCard(fade = false) }
            })
            start()
        }
        return true
    }

    /** The layer a wallpaper-drawn open faded out (given back its opacity once the app covers it), or null. */
    private var effectContent: View? = null
    /** Shutter's tapped icon, hidden while the wallpaper's copy of it slides to the middle. */
    private var effectIcon: IconView? = null
    /** Whether the status and navigation bars go dark-on-light over the effect (Shutter on a light splash). */
    private var effectBarsLight = false
    private val effectTimeout = Runnable {
        AnimLog.log("open effect: Home still not covered ${CARD_TIMEOUT_MS}ms after the start, Home brought back")
        endEffect(animate = true)
    }
    private val effectBars = Runnable { if (effectContent != null) setLightBars(effectBarsLight) }

    /** How Home goes for a wallpaper-drawn open: shrinks to [scale] over [scaleMs], fades out over [fadeMs] from [fadeDelay]. */
    private class EffectHome(val scale: Float, val scaleMs: Long, val curve: android.view.animation.Interpolator,
                             val fadeDelay: Long, val fadeMs: Long)

    private fun effectHome(kind: com.oneuiclone.home.ui.LiveWallpaper.Kind) = when (kind) {
        com.oneuiclone.home.ui.LiveWallpaper.Kind.AURA -> EffectHome(OPEN_SCALE, OPEN_ZOOM_MS, Easing.CUBIC_OUT, 80, 220)
        com.oneuiclone.home.ui.LiveWallpaper.Kind.SHUTTER -> EffectHome(0.97f, 300, Easing.STANDARD, 60, 200)
        com.oneuiclone.home.ui.LiveWallpaper.Kind.ORBIT -> EffectHome(0.88f, 320, Easing.STANDARD, 80, 220)
        com.oneuiclone.home.ui.LiveWallpaper.Kind.ANTICIPATE -> EffectHome(0.8f, 410, ANTICIPATE_HOME, 140, 200)
        com.oneuiclone.home.ui.LiveWallpaper.Kind.SCAN -> EffectHome(0.96f, 300, Easing.STANDARD, 80, 220)
        com.oneuiclone.home.ui.LiveWallpaper.Kind.MIST -> EffectHome(0.95f, 320, Easing.STANDARD, 100, 220)
    }

    private fun effectOpen(v: IconView, app: AppInfo, kind: com.oneuiclone.home.ui.LiveWallpaper.Kind, splash: Int) {
        val r = Rect()
        v.iconBoundsOnScreen(r)
        effectOpen(r, v.icon ?: app.drawerIcon, kind, splash, app, v)
    }

    /** From a widget: the effect starts at the widget's middle, in the colours of the app that owns it. */
    private fun effectOpenFromWidget(widget: View, app: AppInfo, kind: com.oneuiclone.home.ui.LiveWallpaper.Kind, splash: Int) {
        val loc = IntArray(2)
        widget.getLocationOnScreen(loc)
        val cx = loc[0] + widget.width * homeLayer.scaleX / 2f
        val cy = loc[1] + widget.height * homeLayer.scaleY / 2f
        val half = dp.iconSizePx / 2
        effectOpen(Rect((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt()), app.drawerIcon, kind, splash, app, null)
    }

    /**
     * An open drawn by Launchii's live wallpaper ([kind]: Aura, Shutter, Orbit, Anticipate, Scan, Mist), starting at
     * [from] (screen coordinates); Home moves and fades out over it. The wallpaper draws on its own thread, and
     * Android never fades the wallpaper during an open, so the app grows out of it whenever it's ready. Home gets its
     * opacity (and Shutter's icon) back in onStop, out of sight.
     */
    private fun effectOpen(from: Rect, icon: android.graphics.Bitmap, kind: com.oneuiclone.home.ui.LiveWallpaper.Kind, splash: Int,
                           app: AppInfo, v: IconView?) {
        dropLaunchCard(fade = false)
        val content = openContent()
        val e = com.oneuiclone.home.ui.LiveWallpaper.OpenEffect(kind, icon, from.exactCenterX(), from.exactCenterY(),
            from.width().toFloat(), android.os.SystemClock.uptimeMillis(), splash)
        com.oneuiclone.home.ui.LiveWallpaper.startEffect(e)
        AnimLog.log("open effect $kind: from ${from.toShortString()}")
        val hm = effectHome(kind)
        animateContentScale(content, hm.scale, hm.scaleMs, hm.curve)
        if (kind == com.oneuiclone.home.ui.LiveWallpaper.Kind.AURA) animateWallpaperZoom(wallpaperOpenZoom, AURA_MS, Easing.STANDARD)
        content.animate().cancel()
        content.animate().alpha(0f).setStartDelay(hm.fadeDelay).setDuration(hm.fadeMs)
            .setInterpolator(android.view.animation.LinearInterpolator()).start()
        effectContent = content
        if (kind == com.oneuiclone.home.ui.LiveWallpaper.Kind.SHUTTER) {
            // the wallpaper's copy of the icon slides to the middle, then becomes the full icon in its splash circle
            v?.iconVisible = false
            effectIcon = v
            val loadAt = android.os.SystemClock.uptimeMillis()
            splashLook.loadIcon(app.activityInfo) { d ->
                AnimLog.log("open effect: full icon after ${android.os.SystemClock.uptimeMillis() - loadAt}ms")
                if (com.oneuiclone.home.ui.LiveWallpaper.effect === e) {
                    e.splashIcon = splashCircle(d)
                    e.splashIconAt = android.os.SystemClock.uptimeMillis()
                }
            }
        }
        // the bars over the effect: light on the dark ones, Shutter's after its splash colour
        effectBarsLight = kind == com.oneuiclone.home.ui.LiveWallpaper.Kind.SHUTTER && android.graphics.Color.luminance(splash) > 0.5f
        root.removeCallbacks(effectBars)
        root.postDelayed(effectBars, if (kind == com.oneuiclone.home.ui.LiveWallpaper.Kind.SHUTTER) 250L else AURA_MS / 3)
    }

    /**
     * [d] at the splash's 160 dp, in a circle: an adaptive icon's layers without the system mask (they reach a
     * quarter of the size past each side, as in [com.oneuiclone.home.ui.LaunchCard]).
     */
    private fun splashCircle(d: android.graphics.drawable.Drawable): android.graphics.Bitmap {
        val px = (160f * activity.resources.displayMetrics.density).toInt()
        val full = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(full)
        if (d is android.graphics.drawable.AdaptiveIconDrawable) {
            val out = px / 4
            val bounds = Rect(-out, -out, px + out, px + out)
            d.background?.let { it.bounds = bounds; it.draw(c) }
            d.foreground?.let { it.bounds = bounds; it.draw(c) }
        } else {
            d.setBounds(0, 0, px, px)
            d.draw(c)
        }
        // drawn through the circle: a DST_IN circle only touches pixels inside it, which left the corners (0.178, 6696.mp4)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawCircle(px / 2f, px / 2f, px / 2f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.BitmapShader(full, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        })
        return bmp
    }

    /** Gives the faded layer its opacity back (faded in when Home is showing) and lets the effect go. */
    private fun endEffect(animate: Boolean) {
        root.removeCallbacks(effectTimeout)
        com.oneuiclone.home.ui.LiveWallpaper.endEffect()
        root.removeCallbacks(effectBars)
        effectIcon?.iconVisible = true
        effectIcon = null
        val content = effectContent ?: return
        effectContent = null
        applyTextMode()
        content.animate().cancel()
        if (animate) content.animate().alpha(1f).setStartDelay(0).setDuration(OPEN_ZOOM_MS).start()
        else content.alpha = 1f
    }

    /** Home content to [scale], the wallpaper zooming by the same fraction of the open zoom. */
    private fun zoomOpen(scale: Float, duration: Long) {
        val content = openContent()
        val fraction = (1f - scale) / (1f - OPEN_SCALE)
        animateContentScale(content, scale, duration, Easing.CUBIC_OUT)
        animateWallpaperZoom(1f + (wallpaperOpenZoom - 1f) * fraction, duration, Easing.CUBIC_OUT)
    }

    /** The app didn't start: Home comes back from the open zoom. */
    private fun cancelOpen() {
        pendingReturnAnim = false
        lastLaunchedApp = null
        if (!retractLaunchCard("didn't start")) dropLaunchCard(fade = true)
        endEffect(animate = true)
        animateContentScale(openContent(), 1f, OPEN_ZOOM_MS, Easing.CUBIC_OUT)
        animateWallpaperZoom(1f, OPEN_ZOOM_MS, Easing.CUBIC_OUT)
    }

    /**
     * Android 16+ drops scale-up (and every other ActivityOptions animation) for task launches and plays its default
     * open, which on the Pixel is a ~70–100 ms crossfade once the app has drawn. The home zoom therefore runs from
     * the tap and is kept short and front-loaded, so it has mostly played before the app covers it, however fast
     * the app starts.
     */
    private fun launched(app: AppInfo?, zoom: () -> Unit) {
        // Opened straight after a close: the landing icon's surface doesn't scale with Home, and the ripple is stale.
        finishContract()
        returnRipple?.let { root.removeCallbacks(it); returnRipple = null }
        // a close still waiting for Home to show is overtaken by this open
        returnWaiting = false
        root.removeCallbacks(returnWaitCap)
        zoom()
        hiddenSinceOpen = false
        lastWidgetLaunch = null
        pendingReturnAnim = true
        lastLaunchedApp = app
    }

    private var zoomAnim: ValueAnimator? = null
    private var returnRipple: Runnable? = null
    private var wallpaperZoom = 1f
    /** HighEndOpenParams.wallpaperScale is 1.3; toned down to 20 % of that zoom (+6 %). */
    private val wallpaperOpenZoom = 1.06f

    private fun animateWallpaperZoom(to: Float, duration: Long, interp: android.view.animation.Interpolator) {
        // Cancelling a return settles the copy at rest (zoom 1); an app opened mid-return carries on from where it was.
        val from = wallpaperZoom
        zoomAnim?.cancel()
        wallpaperZoom = from
        wallpaper.setZoom(from)
        zoomAnim = ValueAnimator.ofFloat(wallpaperZoom, to).apply {
            this.duration = duration
            interpolator = interp
            addUpdateListener { wallpaperZoom = it.animatedValue as Float; wallpaper.setZoom(wallpaperZoom) }
            start()
        }
    }

    /**
     * Scales [v] around [pivot], by default its centre. A view still scaled around another pivot (the Zoom In
     * open's icon) keeps it, so it doesn't jump.
     */
    private fun animateContentScale(v: View, to: Float, duration: Long, interp: android.view.animation.Interpolator,
                                    pivot: android.graphics.PointF? = null) {
        contentAnim?.cancel()
        if (pivot != null) { v.pivotX = pivot.x; v.pivotY = pivot.y }
        else if (v.scaleX == 1f) centrePivot(v)
        val from = v.scaleX
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        contentAnim = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = interp
            addUpdateListener { val s = it.animatedValue as Float; v.scaleX = s; v.scaleY = s }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    v.setLayerType(View.LAYER_TYPE_NONE, null)
                    // back at rest: the centre again for anything else that scales it (the Home ↔ Apps swipe)
                    if (to == 1f) centrePivot(v)
                }
                override fun onAnimationCancel(animation: android.animation.Animator) = v.setLayerType(View.LAYER_TYPE_NONE, null)
            })
            start()
        }
    }

    private fun centrePivot(v: View) {
        v.pivotX = v.width / 2f
        v.pivotY = v.height / 2f
    }

    fun onPause() {
        leftHomeAt = System.currentTimeMillis()
        BadgeService.removeListener(badgeListener)
    }

    fun onStop() {
        com.oneuiclone.home.widgets.SplitLaunch.homeStopped()   // a split's top app covers Home: the bottom one can go
        searchSources.refresh()                        // Home is off screen: suggestions can change unseen
        quickOpen?.close(animate = false)
        if (pendingLaunch != null) AnimLog.log("stopped with a held launch: dropped")
        pendingLaunch = null
        root.removeCallbacks(launchFallback)
        homeHidden()
        // Left for an app Home didn't open (Recents, a quick switch, a notification): the way back zooms in all the same.
        // Not for the screen going off, so unlocking doesn't play a close.
        if (RECENTS_CLOSE_ZOOM && !pendingReturnAnim && !edit.isActive && search?.isOpen != true && awake() &&
            !activity.isChangingConfigurations) {   // recreated (new wallpaper colours): Home stays on screen
            pendingReturnAnim = true
            lastLaunchedApp = null
            lastWidgetLaunch = null
            AnimLog.log("stopped without a launch: return animation armed")
        }
        if (pendingReturnAnim) {
            contentAnim?.cancel()
            // A folder the app was opened from closes out of sight, so the way back lands on its icon.
            folders.closeNow()
            overlay.scaleX = 1f; overlay.scaleY = 1f; centrePivot(overlay)
            // One UI returns to the Home screen after an app is left with the home gesture.
            if (state.state == StateController.State.APPS) state.setImmediate(StateController.State.HOME)
            homeLayer.pivotX = homeLayer.width / 2f
            homeLayer.pivotY = homeLayer.height / 2f
            homeLayer.scaleX = OPEN_SCALE; homeLayer.scaleY = OPEN_SCALE
            appsLayer.scaleX = 1f; appsLayer.scaleY = 1f; centrePivot(appsLayer)
            // The way back is drawn by the system (recents / back) over the real wallpaper, so keep our copy at
            // rest while away; playReturnAnimation() brings the zoom back in step with the close.
            zoomAnim?.cancel()
            wallpaperZoom = 1f
            wallpaper.setZoom(1f)
            // Launchii's live wallpaper waits, out of sight, at the open zoom, so every close zooms it back
            if (com.oneuiclone.home.ui.LiveWallpaper.drives) com.oneuiclone.home.ui.LiveWallpaper.park(wallpaperOpenZoom)
            homeCovered = true
        }
    }

    /** Home's window hidden behind an app (or Home stopped): the open's card and effect go, out of sight. */
    private fun homeHidden() {
        hiddenSinceOpen = true
        if (launchCard != null) AnimLog.log("zoom in: Home covered, card removed")
        dropLaunchCard(fade = false)
        // Home is out of sight: its layer is opaque again for the close (the wallpaper drops its effect once hidden)
        effectIcon?.let { it.iconVisible = true; effectIcon = null }
        effectContent?.let { root.removeCallbacks(effectTimeout); root.removeCallbacks(effectBars); it.animate().cancel(); it.alpha = 1f; effectContent = null; applyTextMode() }
    }

    private fun awake(): Boolean =
        activity.getSystemService(android.os.PowerManager::class.java).isInteractive &&
            !activity.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

    private fun placeUpdatePill() {
        // with the Search button hidden the pill has the row to itself: centred
        updatePill.translationX = if (searchRowHidden) (dp.widthPx - updatePill.layoutParams.width) / 2f
            else homeSearch.translationX - dp.dp(6f) - updatePill.layoutParams.width
    }

    /** The pill as [AppUpdate] stands: hidden with nothing newer, else Update / 42 % / Installing / Retry. */
    private fun syncUpdatePill() {
        if (!::updatePill.isInitialized) return
        val p = updatePill
        if (AppUpdate.available == null) { p.animate().cancel(); p.visibility = View.GONE; return }
        p.label = when (AppUpdate.phase) {
            AppUpdate.Phase.Ready -> "Update"
            AppUpdate.Phase.Downloading -> "${(AppUpdate.progress * 100).toInt()}%"
            AppUpdate.Phase.Installing -> "Installing"
            AppUpdate.Phase.Failed -> "Retry"
        }
        p.progress = if (AppUpdate.phase == AppUpdate.Phase.Downloading) AppUpdate.progress else -1f
        // wide enough for "100%" while downloading, so the pill doesn't twitch with each percent
        val width = if (AppUpdate.phase == AppUpdate.Phase.Downloading) maxOf(p.pillWidth, p.pillWidth("100%")) else p.pillWidth
        if (p.layoutParams.width != width) { p.layoutParams = p.layoutParams.apply { this.width = width } }
        placeUpdatePill()
        if (p.visibility == View.VISIBLE) return
        p.visibility = if (edit.isActive) View.INVISIBLE else View.VISIBLE
        if (!updateAnnounced) {
            updateAnnounced = true
            p.alpha = 0f; p.scaleX = 0.6f; p.scaleY = 0.6f
            p.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
        }
    }

    fun onResume() {
        AppUpdate.onResumed(activity)
        AppUpdate.checkIfDue()
        // back before the app covered Home (an app that closed at once, Back mid-open): the card shrinks back
        retractLaunchCard("resumed")
        endEffect(animate = true)
        if (prefs.zoomInOpen || prefs.wallpaperOpen) repo?.let { r -> splashLook.prefetch(r.apps.map { it.activityInfo }) }
        centerWallpaper()
        BadgeService.addListener(badgeListener)
        updateBadges()
        Thread { quickOpenRecents.scan(usage) }.start()   // back from split screen: its pair is ready for the Split panel
        if (::dp.isInitialized && prefsSnapshot.isNotEmpty() && snapshot() != prefsSnapshot) {
            edit.reset()
            setupProfile(root.width, root.height)
        }
        wallpaper.reloadIfChanged()
        syncBlurredWallpaper()
        // Home is showing with no close to play (a new instance after a recreate, or a return already under way):
        // Launchii's live wallpaper follows Home's zoom rather than a parked one.
        if (!pendingReturnAnim && com.oneuiclone.home.ui.LiveWallpaper.drives) com.oneuiclone.home.ui.LiveWallpaper.setZoom(wallpaperZoom)
        playReturnAnimation()
    }

    /**
     * Blur wallpaper is baked into the system Home wallpaper (nothing blurs at runtime). If it was replaced from
     * elsewhere while blur is on, the new one gets the same treatment.
     */
    private fun syncBlurredWallpaper() =
        com.oneuiclone.home.ui.WallpaperBlur.apply(activity, prefs.homeBlurLevel, onlyIfReplaced = true) {
            if (it == com.oneuiclone.home.ui.WallpaperBlur.Result.DONE) wallpaper.reload()
        }

    /**
     * One UI doesn't scroll the wallpaper with pages. Without offsets the system shows the left edge of its
     * parallax crop, which our launcher-drawn copy (centred) wouldn't match.
     */
    private fun centerWallpaper() {
        val token = root.windowToken ?: return
        android.app.WallpaperManager.getInstance(activity).setWallpaperOffsets(token, 0.5f, 0.5f)
    }

    private fun settleWallpaper() {
        wallpaperZoom = 1f
        wallpaper.holdCopy = false
        wallpaper.setZoom(1f)
        wallpaper.copyAlpha = 1f
    }

    /**
     * Reverse of the open: home content scales back 0.85 → 1 and the wallpaper zooms out 1.3 → 1 while the app
     * window flies into its icon. Runs from the home intent (as soon as the system commits to going home), or from
     * onResume otherwise. The swipe itself showed the real, un-zoomed wallpaper, so the zoomed copy dissolves in
     * over the first 150 ms, eases out over 600 ms, then fades off the real wallpaper over 250 ms so any
     * difference between the two (crop, offset) dissolves instead of snapping.
     * @param landing the icon the window closes into (the reverse ripple's centre); by default the launched
     * app's, for returns without a gesture contract.
     */
    private fun playReturnAnimation(landing: CloseTarget? = lastLaunchedApp?.let { closeTargetFor(it.key.component, it.user) }
        ?: lastWidgetLaunch?.let { widgetTarget(it) }, shown: Boolean = false) {
        if (!pendingReturnAnim) return
        if (homeCovered && !contractSeen) {
            // No gesture contract on this phone (Redmi A5, Android Go): its close shows only the wallpaper and puts
            // Home back after it, 0.3–0.6 s after the window is gone, so a zoom started now was over before Home
            // showed (it popped in at rest). Home waits, out of sight, at the open scale and grows once it's back.
            if (!returnWaiting) {
                returnWaiting = true
                root.removeCallbacks(returnWaitCap)
                root.postDelayed(returnWaitCap, RETURN_WAIT_MS)
                AnimLog.log("return animation waits for Home to show")
            }
            return
        }
        pendingReturnAnim = false
        // once Home is back the system's close is over: a tap now needn't be held for it
        if (!shown && hiddenSinceOpen) markClosing("return animation")
        AnimLog.log("return animation to ${(landing?.view as? com.oneuiclone.home.ui.WidgetContainer)?.let { "widget " + it.item.provider.packageName } ?: landing?.view?.contentDescription ?: "screen centre"}")
        // The folder is still open only if Home was never stopped (a translucent app); it un-zooms in place.
        animateContentScale(if (folders.isOpen) overlay else homeLayer, 1f, 450, Easing.APP_CLOSE_POSITION)
        // Recents may have left the real wallpaper at another offset: re-centre it now, while our copy covers it.
        centerWallpaper()
        if (com.oneuiclone.home.ui.LiveWallpaper.drives) {
            // Launchii's live wallpaper is the real one: parked at the open zoom while the app covered it, it zooms
            // back with Home (from wherever it is; a return from Recents without an app has nothing to undo).
            wallpaperZoom = com.oneuiclone.home.ui.LiveWallpaper.zoom
            AnimLog.log("live wallpaper close zoom from $wallpaperZoom")
            animateWallpaperZoom(1f, 600, Easing.CUBIC_OUT)
        } else if (!CLOSE_ZOOMS_WALLPAPER) {
            // Already at rest once Home was stopped; a translucent app leaves the open zoom up, which follows Home back.
            animateWallpaperZoom(1f, 450, Easing.APP_CLOSE_POSITION)
        } else if (wallpaper.available) {
            zoomAnim?.cancel()
            wallpaperZoom = wallpaperOpenZoom
            wallpaper.copyAlpha = 0f
            wallpaper.holdCopy = true
            wallpaper.setZoom(wallpaperZoom)
            zoomAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                // 600 ms zoom-out, then the copy fades off the real wallpaper (250 ms) rather than being cut
                duration = 850
                addUpdateListener {
                    val ms = it.animatedFraction * 850f
                    val z = (ms / 600f).coerceAtMost(1f)
                    wallpaperZoom = wallpaperOpenZoom + (1f - wallpaperOpenZoom) * Easing.CUBIC_OUT.getInterpolation(z)
                    wallpaper.setZoom(if (z >= 1f) 1f else wallpaperZoom)
                    wallpaper.copyAlpha = if (ms < 600f) (ms / 150f).coerceAtMost(1f) else 1f - ((ms - 600f) / 250f).coerceIn(0f, 1f)
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = settleWallpaper()
                    override fun onAnimationCancel(animation: android.animation.Animator) = settleWallpaper()
                })
                start()
            }
        }
        // REVERSE ripple from the icon the app returns to, or the screen centre (AppCloseAnimationDelegate.itemTouchEvent)
        val r = Rect()
        if (landing != null) landing.boundsOnScreen(r) else r.set(0, 0, root.width, root.height)
        returnRipple?.let { root.removeCallbacks(it) }
        returnRipple = Runnable { returnRipple = null; ripple.pulse(homeSiblings(), r.exactCenterX(), r.exactCenterY()) }
            .also { root.postDelayed(it, 120) }
        lastLaunchedApp = null
    }

    // ---------------------------------------------------------------------------------------------
    // intents / back
    // ---------------------------------------------------------------------------------------------

    fun onNewIntent(intent: Intent, alreadyOnHome: Boolean) {
        android.util.Log.i("OneUiHome", "onNewIntent extras=" + intent.extras?.keySet() + " onHome=" + alreadyOnHome)
        if (!alreadyOnHome) {
            // Home asked for while an open is still in flight: an app tapped during a close and still held never
            // starts, and a card that hasn't been covered yet shrinks back into its icon with the close
            pendingLaunch?.let {
                AnimLog.log("home intent with a held launch: dropped")
                pendingLaunch = null
                root.removeCallbacks(launchFallback)
            }
            retractLaunchCard("home intent")
            endEffect(animate = true)
            markClosing("home intent")
            // Going home from an app opened in edit mode (Settings, wallpaper) lands on the plain Home screen.
            edit.exitNow()
        }
        val contract = GestureNavContract.fromIntent(intent)
        if (contract != null) {
            android.util.Log.i("OneUiHome", "gesture contract for " + contract.componentName)
            if (!contractSeen) {
                contractSeen = true
                closeState.edit().putBoolean(KEY_CONTRACT_SEEN, true).apply()
            }
            playReturnAnimation(handleContract(contract))
        } else {
            playReturnAnimation()
        }
        popup?.dismiss()
        resizeFrame?.dismiss()
        drag.cancel()
        quickOpen?.close(animate = alreadyOnHome)
        if (alreadyOnHome) {
            when {
                search?.isOpen == true -> closeSearch(toHome = true)
                edit.isActive -> edit.exit()
                folders.isOpen -> folders.close()
                state.state == StateController.State.APPS -> state.goTo(StateController.State.HOME)
                workspace.currentPage != 0 -> workspace.snapToPage(0)
            }
        }
    }

    private var iconSurface: android.view.SurfaceView? = null
    private var contractTarget: CloseTarget? = null
    /** When Home last went to the background: the start of the usage events that tell a split-screen close. */
    private var leftHomeAt = 0L

    /** Where a closing app lands: [view]'s icon (or slot [slot] of a large folder), or the widget it was opened from. */
    private inner class CloseTarget(val view: View, val slot: Int = -1) {
        private val widget get() = (view as? com.oneuiclone.home.ui.WidgetContainer)?.getChildAt(0)
        val isWidget get() = widget != null

        /** The slot in icon coordinates; null for the whole icon. */
        fun part(): Rect? = if (slot < 0) null else Rect().also { (view as LargeFolderIconView).slotRect(slot, it) }

        /** The landing rect in Home layer coordinates. */
        fun rect(): Rect {
            widget?.let { w -> return Rect(0, 0, w.width, w.height).also { homeLayer.offsetDescendantRectToMyCoords(w, it) } }
            val icon = view as IconView
            val r = part() ?: Rect(0, 0, icon.iconSizePx(), icon.iconSizePx())
            r.offset(icon.iconLeft(), icon.iconTop())
            homeLayer.offsetDescendantRectToMyCoords(icon, r)
            return r
        }

        fun boundsOnScreen(out: Rect) {
            val w = widget ?: return (view as IconView).iconBoundsOnScreen(out, part())
            val loc = IntArray(2); w.getLocationOnScreen(loc)
            out.set(loc[0], loc[1], loc[0] + (w.width * homeLayer.scaleX).toInt(), loc[1] + (w.height * homeLayer.scaleY).toInt())
        }

        /** What an icon's landing surface shows, drawn to fill it. */
        fun snapshot(): ((android.graphics.Canvas) -> Unit)? {
            val bmp = when (view) {
                is LargeFolderIconView -> view.slotBitmap(slot)
                is FolderIconView -> view.renderIcon()
                else -> (view as IconView).displayedIcon()
            } ?: return null
            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            if (view is FolderIconView) return { c -> c.drawBitmap(bmp, null, Rect(0, 0, c.width, c.height), paint) }
            val shaped = com.oneuiclone.home.icons.ShapedIcon()          // the Pixel 10's white corners (0.181)
            return { c -> shaped.draw(c, bmp, Rect(0, 0, c.width, c.height), paint) }
        }

        fun setHidden(hidden: Boolean) {
            if (view is LargeFolderIconView) view.hiddenSlot = if (hidden) slot else -1
            else (view as IconView).iconVisible = !hidden
        }
    }

    /** The widget [launch] came from, if it's on the current page. */
    private fun widgetTarget(launch: WidgetLaunch): CloseTarget? {
        val page = workspace.pageAt(workspace.currentPage) as? ViewGroup ?: return null
        for (i in 0 until page.childCount) {
            val c = page.getChildAt(i) as? com.oneuiclone.home.ui.WidgetContainer ?: continue
            if (c.item.appWidgetId == launch.appWidgetId) return CloseTarget(c)
        }
        return null
    }

    /**
     * Launcher3's getFirstMatchForAppClose: the app's own icon on the current page or the dock, otherwise the
     * folder there that holds it (for a large folder, the slot showing it, or the stack). Matched by package, as
     * the system can report the task's real activity rather than the launcher alias (Google Calendar). An app opened
     * from a widget goes back into that widget first, like Launcher3's launch-cookie match.
     */
    private fun closeTargetFor(component: android.content.ComponentName, user: android.os.UserHandle): CloseTarget? {
        lastWidgetLaunch?.takeIf { component.packageName in it.packages && !folders.isOpen }?.let { w ->
            widgetTarget(w)?.let { return it }
        }
        val m = model ?: return null
        val r = repo ?: return null
        val serial = r.serialOf(user)
        val apps = m.items.filterIsInstance<HomeItem.App>()
            .filter { it.key.userSerial == serial && it.key.component.packageName == component.packageName }
            .sortedByDescending { it.key.component == component }
        fun onScreen(it: HomeItem) =
            it.container == Container.HOTSEAT || (it.container == Container.WORKSPACE && it.screen == workspace.currentPage)
        apps.firstOrNull { onScreen(it) }?.let { return CloseTarget(findView(it) ?: return null) }
        if (folders.isOpen) return null
        for (a in apps) {
            if (a.container != Container.FOLDER) continue
            val folder = m.items.firstOrNull { it is HomeItem.Folder && it.id == a.folderId && onScreen(it) } ?: continue
            val view = findView(folder) ?: continue
            if (view !is LargeFolderIconView) return CloseTarget(view)
            val slot = view.slotFor(view.apps.indexOfFirst { (it.second as? AppInfo)?.key == a.key })
            if (slot >= 0) return CloseTarget(view, slot)
        }
        return null
    }

    /**
     * Launcher3's FloatingSurfaceView: the icon is hidden and drawn into a surface that the recents process
     * moves along with the closing window, so the icon never blinks out between the window landing and the
     * finish callback. Returns the icon the window closes into, if any.
     */
    private fun handleContract(c: GestureNavContract): CloseTarget? {
        AnimLog.log("gesture contract for ${c.componentName.packageName}")
        // A split pair closes as two windows, and Quickstep moves our one landing surface with each of them every
        // frame (only the last one is retargeted, so the other heads for its default spot above the dock): the icon
        // flickered between its slot and the middle of the screen. Launcher3 gives up its floating icon for split
        // closes too, so leave the contract unanswered: both windows go to the default spot, the icon stays shown.
        val t0 = android.os.SystemClock.uptimeMillis()
        val front = usage.frontApps(leftHomeAt - 1000)
        if (front != null && front.size >= 2 && c.componentName.packageName in front) {
            AnimLog.log("split screen ($front, ${android.os.SystemClock.uptimeMillis() - t0} ms): no landing icon")
            return null
        }
        layOutNow()
        val target = closeTargetFor(c.componentName, c.user) ?: return null
        android.util.Log.i("OneUiHome", "contract target found: " + target.view + " slot " + target.slot)
        AnimLog.log("close target: " + ((target.view as? com.oneuiclone.home.ui.WidgetContainer)?.let { "widget ${it.item.provider.packageName}" }
            ?: target.view.contentDescription))
        val rect = target.rect()
        AnimLog.log("close rect $rect")
        if (target.isWidget) {
            // The system lands the window as a square (icon-shaped) whatever the rect, fading it out on the way, so
            // a wide target overflowed the widget and a snapshot stretched to the window's rect snapped back to the
            // widget's shape at the end. The window goes into a square at the widget's centre instead, over the
            // real widget, which stays visible: no surface, nothing hidden, nothing to swap.
            val side = minOf(rect.width(), rect.height())
            val sq = Rect(rect.centerX() - side / 2, rect.centerY() - side / 2, rect.centerX() + side / 2, rect.centerY() + side / 2)
            finishContract()
            c.sendEndPosition(RectF(sq), null) { AnimLog.log("gesture contract finished") }
            return target
        }
        val draw = target.snapshot() ?: return null
        val pos = RectF(rect)
        finishContract()
        contractTarget = target
        target.setHidden(true)
        val sv = android.view.SurfaceView(activity)
        sv.setZOrderOnTop(true)
        sv.holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                drawIconSurface(holder, draw)
                if (iconSurface === sv) c.sendEndPosition(pos, sv.surfaceControl) {
                    android.util.Log.i("OneUiHome", "contract finished")
                    AnimLog.log("gesture contract finished")
                    finishContract()
                }
            }
            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) = drawIconSurface(holder, draw)
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
        })
        overlay.addView(sv, FrameLayout.LayoutParams(rect.width(), rect.height()).apply { leftMargin = rect.left; topMargin = rect.top })
        iconSurface = sv
        // position first, surface as soon as it exists (the recents process takes the latest message)
        c.sendEndPosition(pos, null) { finishContract() }
        root.postDelayed({ if (iconSurface === sv) finishContract() }, 2000)
        return target
    }

    /**
     * A stopped activity gets no layout passes, so icons rebound while an app was open (a package update reloading
     * the apps) are still 0×0 at 0,0 when the contract arrives, and the window flew into the top-left corner before
     * snapping to the real icon. Lay out now so the landing rect is real.
     */
    private fun layOutNow() {
        if (!root.isLayoutRequested || root.width == 0) return
        AnimLog.log("layout pending at contract: laying out now")
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(root.left, root.top, root.right, root.bottom)
    }

    private fun drawIconSurface(holder: android.view.SurfaceHolder, draw: (android.graphics.Canvas) -> Unit) {
        val canvas = try { holder.lockHardwareCanvas() } catch (_: Exception) { null } ?: return
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        draw(canvas)
        holder.unlockCanvasAndPost(canvas)
    }

    /** The window has landed: show the real icon, then drop the surface on the next frame (no gap). */
    private fun finishContract() {
        contractTarget?.setHidden(false)
        contractTarget = null
        val sv = iconSurface ?: return
        iconSurface = null
        root.postOnAnimation { overlay.removeView(sv) }
    }

    /** Icon or widget view of a Home screen item. */
    private fun findItemView(item: HomeItem): View? {
        if (item.container != Container.WORKSPACE) return findView(item)
        val page = workspace.pageAt(item.screen) as? CellLayout ?: return null
        for (i in 0 until page.childCount) {
            val c = page.getChildAt(i)
            if ((c as? IconView)?.item?.id == item.id || (c as? com.oneuiclone.home.ui.WidgetContainer)?.item?.id == item.id) return c
        }
        return null
    }

    fun findView(item: HomeItem): IconView? {
        val parent = if (item.container == Container.HOTSEAT) hotseat else workspace.pageAt(item.screen) as? CellLayout ?: return null
        for (i in 0 until parent.childCount) {
            val v = parent.getChildAt(i) as? IconView ?: continue
            if (v.item?.id == item.id) return v
        }
        return null
    }

    fun onBack(): Boolean {
        quickOpen?.takeIf { it.isOpen }?.let { it.close(); return true }
        if (search?.isOpen == true) { closeSearch(); return true }
        popup?.let { it.dismiss(); return true }
        resizeFrame?.let { it.dismiss(); return true }
        picker?.let { it.dismiss(); return true }
        if (edit.isActive) { edit.exit(); return true }
        if (folders.isOpen) { folders.close(); return true }
        if (state.state == StateController.State.APPS) {
            state.goTo(StateController.State.HOME)
            return true
        }
        return false
    }

    fun onUiModeChanged() {
        shortcutLoader.clear()
        repo?.invalidateIcons(dp.iconSizePx)
        // widgets re-inflate in the new mode (their own day/night resources and colours)
        widgets.clearViews()
        bindWorkspace()
    }

    // ---------------------------------------------------------------------------------------------
    // vertical gestures (home ⇄ apps, notification shade)
    // ---------------------------------------------------------------------------------------------

    private val slop = ViewConfiguration.get(activity).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var trackToApps = true
    private var consumed = false
    private var vt: VelocityTracker? = null
    /** The touch went down on a widget's list, grid or stack: its vertical swipes scroll it, not Home. */
    private var downOnWidgetScroller = false

    private fun interceptVertical(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; tracking = false; consumed = false
                downOnWidgetScroller = state.state == StateController.State.HOME && widgetScrollerAt(ev.rawX.toInt(), ev.rawY.toInt())
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (tracking || consumed) return true
                // a swipe while search is springing back to Home, or against it as it springs in: take hold of it
                // where it is (a second swipe its own way goes on to Apps: search handles that one)
                if (search?.isLifting == true && liftSpring != null && !liftClosing && ev.pointerCount == 1) {
                    val dy = ev.y - downY
                    val towardSearch = if (liftFromAbove) dy > 0 else dy < 0
                    if (abs(dy) > slop && abs(dy) > abs(ev.x - downX) * 1.2f && (liftTarget < 1f || !towardSearch)) {
                        catchLift(ev.y); consumed = true; return true
                    }
                    return false
                }
                // sheets/popups own their vertical gestures (the widget list scrolls, resize handles drag)
                if (folders.isOpen || edit.isActive || search != null || picker != null || popup != null || quickOpen != null ||
                    resizeFrame != null || drag.isDragging) return false
                if (ev.pointerCount >= 2) return checkPinch(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dy) > slop && abs(dy) > abs(dx) * 1.2f && !workspace.isDragging) {
                    // even at the list's end, so scrolling a widget never opens Apps or the shade by accident (Launcher3)
                    if (downOnWidgetScroller) return false
                    // Just below the dock icons and down, swiping up belongs to the system's home / recents gesture
                    if (state.state == StateController.State.HOME && dy < 0 && downY >= swipeUpDeadLine()) return false
                    if (state.state == StateController.State.HOME && dy < 0) {
                        if (prefs.swipeUpSearch && !prefs.swipeDownSearch) { startSearchLift(fromAbove = false); consumed = true } else startTracking(true)
                        return true
                    }
                    if (state.state == StateController.State.HOME && dy > 0 && prefs.swipeDownSearch) {
                        startSearchLift(fromAbove = true); consumed = true; return true
                    }
                    if (state.state == StateController.State.HOME && dy > 0 && prefs.swipeDownNotifications) {
                        expandNotifications(); consumed = true; return true
                    }
                    // pulling down returns Home only from the top of the list; otherwise the list scrolls
                    if (state.state == StateController.State.APPS && dy > 0 && !appsScroll.canScrollVertically(-1)) {
                        startTracking(false); return true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount == 2) pinchStart = spread(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { vt?.recycle(); vt = null; pinchStart = 0f }
        }
        return false
    }

    /**
     * Whether screen point ([x], [y]) is over a view in a widget on the current page that scrolls vertically: a
     * collection with more rows than fit (ListView, GridView) or a StackView, which flips cards on vertical swipes.
     */
    private fun widgetScrollerAt(x: Int, y: Int): Boolean {
        val page = workspace.pageAt(workspace.currentPage) as? ViewGroup ?: return false
        val r = Rect()
        fun hit(v: View) = v.isShown && v.getGlobalVisibleRect(r) && r.contains(x, y)
        fun scrolls(v: View): Boolean {
            if (!hit(v)) return false
            if (v is android.widget.StackView || v.canScrollVertically(1) || v.canScrollVertically(-1)) return true
            return v is ViewGroup && (0 until v.childCount).any { scrolls(v.getChildAt(it)) }
        }
        return (0 until page.childCount).any { (page.getChildAt(it) as? com.oneuiclone.home.ui.WidgetContainer)?.let(::scrolls) == true }
    }

    /** Root y below which a swipe up on Home is left to the system: 10 dp under the dock icons' bottom edge. */
    private fun swipeUpDeadLine(): Float {
        val icon = (0 until hotseat.childCount).map { hotseat.getChildAt(it) }.firstOrNull { it is IconView } as? IconView
        val iconBottom = if (icon != null) hotseat.top + icon.top + icon.iconTop() + icon.iconSizePx()
            else dp.hotseatRect.top + (dp.hotseatRect.height() + dp.iconSizePx) / 2
        return iconBottom + 10f * dp.density
    }

    private var pinchStart = 0f
    private fun spread(ev: MotionEvent) = kotlin.math.hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))

    /** Pinch-in on Home enters page edit (One UI). */
    private fun checkPinch(ev: MotionEvent): Boolean {
        if (pinchStart <= 0f || state.state != StateController.State.HOME) return false
        if (spread(ev) < pinchStart * 0.8f) {
            pinchStart = 0f
            consumed = true
            edit.enter()
            return true
        }
        return false
    }

    private fun showAppsMenu(anchor: View) {
        popup?.dismiss()
        val opts = arrayListOf(
            com.oneuiclone.home.ui.QuickOption("Sort", com.oneuiclone.home.ui.QuickOption.SELECT) {
                Toast.makeText(activity, "Sorted alphabetically", Toast.LENGTH_SHORT).show()
            },
            com.oneuiclone.home.ui.QuickOption("Settings", com.oneuiclone.home.ui.QuickOption.EDIT) { onSettingsButton() },
        )
        val p = com.oneuiclone.home.ui.QuickOptionPopup(activity, dp, iconFactory, "Apps", emptyList(), opts, { popup = null }, null,
            { rect, done -> snapshotBehind(rect, done) })
        overlay.addView(p, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val loc = IntArray(2); anchor.getLocationInWindow(loc)
        p.showFor(Rect(loc[0], loc[1], loc[0] + anchor.width, loc[1] + anchor.height), true)
        popup = p
    }

    private var search: com.oneuiclone.home.ui.AppsSearch? = null
    /** Opened from the Home screen Search button: closing goes back Home, into that button. */
    private var searchFromHome = false
    /** Hide search bar and page indicators is on (read when Home is built). */
    private var searchRowHidden = false

    /** Origins in root coordinates from layout positions, so they're right even mid-transition. */
    private fun drawerSearchOrigin() = searchPill.origin(searchPill.left.toFloat(), searchPill.top.toFloat())
    /** With the button hidden ([searchRowHidden]) the bar doesn't grow out of it: it fades in and out in place. */
    private fun homeSearchOrigin() = homeSearch.origin(homeSearch.left + homeSearch.translationX, homeSearch.top.toFloat(), fade = searchRowHidden)

    /**
     * The bar grows out of the pill that was tapped. From Home, the screen crossfades to the Apps screen's
     * blurred background in step with the bar (whose grid stays hidden): Home → search in one motion.
     * [fromAbove]: opened by a swipe down, so the results come down into place instead of rising.
     *
     * From Home it is Lift-Off: Home grows toward the viewer, blurs and fades while search rises out of the bar
     * underneath. [follow]: a finger drives it ([dragLift]); otherwise (the Search button) a spring plays it.
     */
    private fun openSearch(fromHome: Boolean = false, fromAbove: Boolean = false, follow: Boolean = false) {
        if (search != null) return
        if (fromHome && (state.state != StateController.State.HOME || state.isTransitioning || edit.isActive ||
                folders.isOpen || drag.isDragging || popup != null)) return
        val origin = if (fromHome) homeSearchOrigin() else drawerSearchOrigin()
        lateinit var s: com.oneuiclone.home.ui.AppsSearch
        s = com.oneuiclone.home.ui.AppsSearch(activity, dp, { repo?.apps ?: emptyList() },
            onLaunch = { v, a -> closeSearch(toHome = false); launch(v, a) },
            configureIcon = { v, a -> setAppsIcon(v, a); v.appInfo = a; attachItem(v, null, a, true) { folderSiblings(v) } },
            sources = searchSources,
            firstPage = { model?.firstPageApps() ?: emptySet() },
            onOpenIntent = { v, intent ->
                closeSearch(toHome = false)
                launchFrom(v) { _, opts -> activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts) }
            },
            onRequestContacts = {
                activity.requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), LauncherActivity.REQUEST_CONTACTS)
            },
            canSwipeHome = { !drag.isDragging && popup == null && dragArmed == null },
            onSwipeHome = { closeSearch(toHome = true) },
            // with Swipe down for search a swipe up undoes it (the Apps screen is a swipe up from Home)
            onSwipeUp = { if (prefs.swipeDownSearch) closeSearch(lift = true) else searchToApps() },
            onDismiss = { closeSearch() },
        ) {
            // the bar has landed exactly on its pill: swap them in the same frame
            searchPill.visibility = View.VISIBLE
            if (!searchRowHidden) homeSearch.visibility = View.VISIBLE
            removeSearch(s)
            if (search === s) search = null
            if (state.state == StateController.State.HOME && !state.isTransitioning) restoreAppsChrome()
        }
        // above both screens, so the bar isn't faded or scaled by the Home ⇄ Apps transition
        root.addView(s, root.indexOfChild(overlay), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        search = s
        searchFromHome = fromHome
        searchPill.visibility = View.INVISIBLE
        if (fromHome) {
            homeSearch.visibility = View.INVISIBLE
            appsScroll.animate().cancel(); appsScroll.alpha = 0f; appsScroll.translationY = 0f
            moreButton.animate().cancel(); moreButton.alpha = 0f; moreButton.translationY = 0f
            s.requestApplyInsets()
            liftSpring?.cancel(); liftSpring = null
            searchLift = true; liftClosing = false
            state.liftHome = true
            state.begin(true)
            s.beginLift(origin, fromAbove)
            applyLift(0f)
            if (!follow) springLift(1f)
            return
        } else {
            moreButton.animate().alpha(0f).setDuration(150).start()
            appsScroll.animate().alpha(0f).setDuration(200).start()
        }
        s.requestApplyInsets()
        s.open(origin, fromAbove)
    }

    fun onContactsPermission(granted: Boolean) { search?.onContactsPermission(granted) }

    /** Apps screen as it is without search (after the bar went back Home over a hidden Apps screen). */
    private fun restoreAppsChrome() {
        appsScroll.animate().cancel(); appsScroll.alpha = 1f; appsScroll.translationY = 0f
        moreButton.animate().cancel(); moreButton.alpha = 1f; moreButton.translationY = 0f
    }

    /** A result being dragged to Home still owns the touch stream, so its view stays attached until the drop. */
    private fun removeSearch(s: View) {
        if (drag.isDragging) { s.visibility = View.INVISIBLE; root.postDelayed({ removeSearch(s) }, 100); return }
        root.removeView(s)
    }

    // ---------------------------------------------------------------------------------------------
    // Lift-Off (search from Home)
    // ---------------------------------------------------------------------------------------------

    /** 0 = Home, 1 = search: the finger sets it, then [liftSpring] takes it the rest of the way. */
    private var lift = 0f
    private var liftSpring: SpringAnimation? = null
    private var liftTarget = 0f
    /** A finger is driving it, measured from [liftDownY] (down for a swipe down: [liftFromAbove]). */
    private var liftTracking = false
    private var liftDownY = 0f
    private var liftFromAbove = false
    /** The open search came in by Lift-Off, so it goes back to Home the same way; [liftClosing] while it does. */
    private var searchLift = false
    private var liftClosing = false

    /** Finger travel for the whole lift: the bar rises at 0.9× the finger (at least 180 dp). */
    private fun liftDistance() = maxOf(dp.dp(180f), (search?.liftTravel ?: 0f) / 0.9f)

    private fun applyLift(p: Float) {
        lift = p
        state.drag(if (liftClosing) 1f - p else p)
        search?.setLift(p)
    }

    private fun startSearchLift(fromAbove: Boolean) {
        openSearch(fromHome = true, fromAbove = fromAbove, follow = true)
        if (search?.isLifting != true) return
        liftTracking = true
        liftDownY = downY
        liftFromAbove = fromAbove
    }

    /** A finger caught the lift mid-spring at [y]: it carries on from where it is. */
    private fun catchLift(y: Float) {
        liftSpring?.cancel(); liftSpring = null
        liftTracking = true
        val d = lift * liftDistance()
        liftDownY = if (liftFromAbove) y - d else y + d
    }

    /** Follows the finger 1:1 (a soft stop past the end). */
    private fun dragLift(y: Float) {
        val r = (if (liftFromAbove) y - liftDownY else liftDownY - y) / liftDistance()
        applyLift(if (r > 1f) 1f + 0.16f * (1f - exp(-(r - 1f) * 2.4f)) else r.coerceAtLeast(0f))
    }

    /** [velocity]: px/ms toward search. */
    private fun releaseLift(velocity: Float) {
        liftTracking = false
        val pv = velocity / liftDistance()
        val open = pv > LIFT_FLICK || (pv > -LIFT_BACK_FLICK && lift > LIFT_COMMIT)
        // the harder the swipe, the more the bar overshoots when it lands (none for a slow let-go)
        val bounce = if (open) ((pv - LIFT_BOUNCE_FROM) / LIFT_BOUNCE_RANGE).coerceIn(0f, 1f) else 0f
        springLift(if (open) 1f else 0f, pv * 1000f, bounce)
    }

    /**
     * Carries the lift to [target] from the finger's [velocity] (progress/s), without overshoot; only the bar
     * bounces as it lands, by [bounce] (0–1) of [LIFT_BOUNCE_DP].
     */
    private fun springLift(target: Float, velocity: Float = 0f, bounce: Float = 0f) {
        liftSpring?.cancel()
        liftTarget = target
        var kick = bounce
        lateinit var a: SpringAnimation
        a = SpringAnimation(FloatValueHolder(lift)).apply {
            spring = SpringForce(target).setStiffness(LIFT_STIFFNESS).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
            setStartVelocity(velocity)
            setMinimumVisibleChange(0.002f)
            addUpdateListener { _, value, _ ->
                if (liftSpring !== a) return@addUpdateListener
                applyLift(value)
                if (kick > 0f && value >= 0.985f) { search?.bounceBar(dp.dp(LIFT_BOUNCE_DP) * kick); kick = 0f }
            }
            addEndListener { _, canceled, _, _ ->
                if (canceled || liftSpring !== a) return@addEndListener
                liftSpring = null
                applyLift(target)
                liftDone(target >= 1f)
                if (kick > 0f) search?.bounceBar(dp.dp(LIFT_BOUNCE_DP) * kick)
            }
        }
        liftSpring = a
        a.start()
    }

    /** Ends a lift on its way in at once, open. */
    private fun landLift() {
        liftSpring?.cancel(); liftSpring = null; liftTracking = false
        applyLift(1f)
        liftDone(true)
    }

    /** The lift has landed: search open over the Apps screen, or back on Home (after a close, or let go early). */
    private fun liftDone(opened: Boolean) {
        val s = search
        when {
            liftClosing -> { liftClosing = false; searchLift = false; s?.endLift(false); state.settle(true) }
            opened -> { state.settle(true); s?.endLift(true) }
            else -> { searchLift = false; s?.endLift(false); state.settle(false) }
        }
    }

    /**
     * [toHome]: the bar shrinks back into the Home screen Search button while the Apps screen gives way to
     * Home; otherwise it returns to the apps-screen pill and the grid fades back in. [lift]: closed by a swipe up,
     * so the results rise away as they fade.
     */
    private fun closeSearch(toHome: Boolean = searchFromHome, lift: Boolean = false) {
        val s = search ?: return
        if (s.isLifting) {
            if (liftClosing) return
            // still lifting in: a result was tapped (it lands open first), or it goes back Home the way it came
            if (toHome) { liftTracking = false; springLift(0f); return }
            landLift()
        }
        if (!s.isOpen) return
        if (toHome && searchLift && state.state == StateController.State.APPS && !state.isTransitioning) {
            liftClosing = true
            state.liftHome = true
            state.begin(false)
            s.beginLiftClose(homeSearchOrigin(), away = lift)
            applyLift(1f)
            springLift(0f)
            return
        }
        if (toHome) {
            s.close(homeSearchOrigin(), lift)
            if (state.state == StateController.State.APPS || state.isTransitioning)
                state.goTo(StateController.State.HOME, com.oneuiclone.home.ui.AppsSearch.MORPH_MS)
        } else {
            appsScroll.animate().alpha(1f).setDuration(200).start()
            moreButton.animate().alpha(1f).setDuration(150).start()
            s.close(drawerSearchOrigin(), lift)
        }
    }

    /**
     * A second swipe up: search gives way to the Apps screen (already underneath). The bar settles into the
     * apps-screen pill while the grid fades and slides up just as search's results came in.
     */
    private fun searchToApps() {
        val s = search ?: return
        // a quick second swipe can come while search is still springing in: land it and carry on
        if (s.isLifting) { if (liftClosing) return; landLift() }
        if (!s.isOpen) return
        searchFromHome = false
        searchLift = false
        if (state.state != StateController.State.APPS) state.goTo(StateController.State.APPS, com.oneuiclone.home.ui.AppsSearch.MORPH_MS)
        for (v in listOf(appsScroll, moreButton)) {
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = dp.dp(com.oneuiclone.home.ui.AppsSearch.OPEN_RISE_DP)
            v.animate().alpha(1f).translationY(0f).setDuration(com.oneuiclone.home.ui.AppsSearch.OPEN_MS)
                .setInterpolator(Easing.IME).start()
        }
        s.close(drawerSearchOrigin())
    }

    /** Icons: icon style, size and labels. Opened like an app, as Settings is. */
    private fun onIconsButton() {
        open("Icons", null, "Can't open Icons") {
            startSettingsPage(com.oneuiclone.home.settings.IconThemeActivity::class.java)
        }
    }

    /** Wallpaper and style: previews, wallpaper, blur and colour palette. Opened like an app, as Settings is. */
    private fun onStyleButton() {
        open("Wallpaper and style", null, "Can't open Wallpaper and style") {
            startSettingsPage(com.oneuiclone.home.settings.WallpaperStyleActivity::class.java)
        }
    }

    private fun onWidgetsButton() {
        if (picker != null) return
        val page = workspace.currentPage
        edit.exit(keepBackground = true)
        background.set(BackgroundController.Kind.WIDGETS, 1f)
        homeLayer.animate().alpha(0f).setDuration(200).start()
        lateinit var wp: com.oneuiclone.home.widgets.WidgetPicker
        wp = com.oneuiclone.home.widgets.WidgetPicker(activity, dp, widgets,
            onPick = { info ->
                wp.dismiss()
                widgets.add(info) { id, i -> placeWidget(id, i, page) }
            },
            onClose = {
                picker = null
                homeLayer.animate().alpha(1f).setDuration(200).start()
                background.set(BackgroundController.Kind.WIDGETS, 0f)
            })
        overlay.addView(wp, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        wp.show()
        picker = wp
    }

    /**
     * Settings, Icons and Wallpaper and style share one task, so NEW_TASK alone would bring back whichever page was
     * left on top of it; CLEAR_TASK starts that task over with just the page that was tapped.
     */
    private fun startSettingsPage(page: Class<*>) {
        activity.startActivity(Intent(activity, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    /** Opened like an app (Home zooms out and back), in its own task so the home gesture closes it like one. */
    private fun onSettingsButton() {
        open("Settings", null, "Can't open settings") {
            startSettingsPage(com.oneuiclone.home.settings.SettingsActivity::class.java)
        }
    }

    private fun startTracking(toApps: Boolean) {
        tracking = true
        trackToApps = toApps
        state.begin(toApps)
    }

    private fun touchVertical(ev: MotionEvent): Boolean {
        vt?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (liftTracking) dragLift(ev.y) else if (tracking) {
                val d = if (trackToApps) downY - ev.y else ev.y - downY
                state.drag(d / dp.swipeProgressDistance)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (liftTracking) {
                    vt?.computeCurrentVelocity(1)
                    val vy = vt?.yVelocity ?: 0f
                    if (ev.actionMasked == MotionEvent.ACTION_CANCEL) { liftTracking = false; springLift(0f) }
                    else { dragLift(ev.y); releaseLift(if (liftFromAbove) vy else -vy) }
                }
                if (tracking) {
                    vt?.computeCurrentVelocity(1)
                    val vy = vt?.yVelocity ?: 0f
                    val toward = if (trackToApps) -vy else vy
                    state.release(if (ev.actionMasked == MotionEvent.ACTION_CANCEL) -2f else toward / dp.samsungPxScale)
                }
                tracking = false; consumed = false
                vt?.recycle(); vt = null
            }
        }
        return true
    }

    private fun expandNotifications() {
        try {
            val sb = activity.getSystemService("statusbar")
            sb.javaClass.getMethod("expandNotificationsPanel").invoke(sb)
        } catch (_: Throwable) {
        }
    }

    inner class RootLayout : FrameLayout(activity) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0 && (w != oldw || h != oldh)) post { setupProfile(w, h) }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
            interceptVertical(ev) || super.onInterceptTouchEvent(ev)

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) { interceptVertical(ev); return true }
            if (!tracking && !consumed) {
                interceptVertical(ev)
                return true
            }
            return touchVertical(ev)
        }
    }

    /**
     * The widget [appWidgetId] as it shows on Home now (style and glass included), or null if it isn't laid out.
     * A widget drawing hardware bitmaps can't be copied this way.
     */
    fun widgetSnapshot(appWidgetId: Int): android.graphics.Bitmap? {
        if (!::workspace.isInitialized) return null
        for (p in 0 until workspace.childCount) {
            val page = workspace.getChildAt(p) as? ViewGroup ?: continue
            for (i in 0 until page.childCount) {
                val wc = page.getChildAt(i) as? com.oneuiclone.home.ui.WidgetContainer ?: continue
                if (wc.item.appWidgetId != appWidgetId || wc.width == 0 || wc.height == 0) continue
                return try {
                    android.graphics.Bitmap.createBitmap(wc.width, wc.height, android.graphics.Bitmap.Config.ARGB_8888)
                        .also { wc.draw(android.graphics.Canvas(it)) }
                } catch (e: Exception) {
                    android.util.Log.w("OneUiHome", "widget snapshot failed", e)
                    null
                }
            }
        }
        return null
    }

    fun destroy() {
        if (live?.get() === this) live = null
        android.app.WallpaperManager.getInstance(activity).removeOnColorsChangedListener(wallpaperColorsListener)
        com.oneuiclone.home.widgets.BubblesWidget.prefs(activity).unregisterOnSharedPreferenceChangeListener(bubblesListener)
        shortcutLoader.destroy()
        backdropThread.quitSafely()
        wallpaper.destroy()
        widgetGlass.destroy()
        repo?.destroy()
    }

    @Suppress("unused")
    private fun myUser() = Process.myUserHandle()
}
