package com.oneuiclone.home.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.model.AppInfo
import com.oneuiclone.home.model.HomeItem
import com.oneuiclone.home.motion.Easing
import kotlin.math.roundToInt

/**
 * One UI "full" folder (OpenFullFolderContainer + H5.w geometry, spec §8).
 *  - container morphs from the folder icon: translation → 0, scale (icon/tray) → 1, 350 ms, (0.17,0.34,0.3,1)
 *  - preview icons fly from the 3×3 preview to their grid cells on the same curve
 *  - the folder's container bitmap scales with the panel and fades (linear 80 ms from the start; close: 60 ms)
 *  - background: blur + 20 % dim linear over 350 ms; the home screen fades with DecelerateInterpolator(2)
 */
class FolderController(
    private val context: Context,
    private val overlay: FrameLayout,
    private val dpProvider: () -> DeviceProfile,
    private val background: BackgroundController,
    private val homeContent: () -> View,
    private val onLaunch: (IconView, AppInfo) -> Unit,
    private val onRename: (HomeItem.Folder, String) -> Unit,
    /** Lets the launcher attach ripple/long-press/drag handling to icons inside the panel. */
    private val configureIcon: (IconView, HomeItem.App?, AppInfo) -> Unit = { _, _, _ -> },
    private val onAddApps: (HomeItem.Folder) -> Unit = {},
    private val onFolderOptions: (HomeItem.Folder) -> Unit = {},
    /** Pixel-style pop-up folder: a compact glass card over the icon instead of the full-screen folder. */
    private val popupFolders: () -> Boolean = { false },
    /** Captures what's behind a rect (window coords); the pop-up card turns to blurred glass when it arrives. */
    private val backdrop: ((Rect, (Bitmap) -> Unit) -> Unit)? = null,
    /** A folder's Style › Blur transparency (0..100), null for Original: how see-through its pop-up card is. */
    private val transparency: (HomeItem.Folder) -> Int? = { null },
    /** Apps rearranged by dragging inside the folder: the folder's apps in their new order. */
    private val onReorder: (HomeItem.Folder, List<HomeItem.App>) -> Unit = { _, _ -> },
    /** How far a pop-up folder has dimmed and shrunk Home (0 none .. 1 all), every frame: Home scales with it. */
    private val onOpenness: (Float) -> Unit = {},
) {
    companion object {
        const val DURATION = 350L
        /** Open: the folder squircle starts fading after this delay (ms) and is gone this long (ms) later. */
        const val BG_FADE_DELAY = 0f
        const val BG_FADE_DURATION = 80f
        /** 3×3, matching the folder icon's 3×3 preview so every app opens where it was shown. */
        const val COLS = 3
        const val ROWS = 3
        /** One UI's full-folder tray height is for 4 rows; the row height is kept and the tray wraps 3. */
        const val SPEC_GRID = 4
        /** Cubic handle lengths (× corner radius): a circular arc's, and the One UI icon mask's along its vertical
         *  sides (43.065 of 49 in ONE_UI_MASK; along the horizontal ones it's 27.063 of 49, the circle's). */
        const val CIRCLE_K = 0.5523f
        const val SQUIRCLE_V = 0.8789f
        /** Pop-up reveal with an icon shape other than the squircle: how far along the shape has grown past the card. */
        const val SHAPE_GONE = 0.4f
        /** Pop-up folder grid limits (Launcher3 numFolderColumns / numFolderRows); more apps page. */
        const val POPUP_MAX_COLS = 4
        const val POPUP_MAX_ROWS = 3
        /** Pixel Launcher (recording 6748): an open pop-up dims the whole screen 20 % (status bar and dock too) and
         *  shrinks all of Home (workspace, page dots, Search, dock) to 97.5 % about the screen centre, both eased
         *  over 350 ms without a bounce. The wallpaper doesn't move. */
        const val POPUP_DIM = 0.2f
        const val POPUP_HOME_SCALE = 0.975f
        /** The pop-up itself springs (M3 Expressive default spatial: stiffness 380, damping 0.8): open, its apps grow
         *  ~1.3 % past their size around 290 ms and settle by ~440 ms; close, it dips under the icon and comes back. */
        const val POPUP_STIFFNESS = 380f
        const val POPUP_DAMPING = 0.8f
        const val POPUP_SPRING_MS = 500L

        /** Launcher3 FolderGridOrganizer.calculateGridSize: the smallest near-square grid for [count] apps. */
        fun popupGrid(count: Int): Pair<Int, Int> {
            if (count >= POPUP_MAX_COLS * POPUP_MAX_ROWS) return POPUP_MAX_COLS to POPUP_MAX_ROWS
            var x = 0; var y = 0
            while (true) {
                val ox = x; val oy = y
                if (x * y < count) {
                    if ((x <= y || y == POPUP_MAX_ROWS) && x < POPUP_MAX_COLS) x++ else if (y < POPUP_MAX_ROWS) y++
                    if (y == 0) y++
                } else if ((y - 1) * x >= count && y >= x) {
                    y = maxOf(0, y - 1)
                } else if ((x - 1) * y >= count) {
                    x = maxOf(0, x - 1)
                }
                if (x == ox && y == oy) return maxOf(1, x) to maxOf(1, y)
            }
        }
    }

    private var panel: FolderPanel? = null
    private var source: FolderIconView? = null
    private var anim: ValueAnimator? = null
    var isOpen = false; private set
    private var fliers = ArrayList<Flier>()
    private var folder: HomeItem.Folder? = null
    /** The open folder's apps, in the order shown. */
    private var folderItems: List<HomeItem.App> = emptyList()
    /** An app from the folder is being dragged: the panel stays attached (it has the touch stream) until it ends. */
    private var dragging = false
    /** How open the folder is (0..1), last set by [report]. */
    private var openness = 0f
    /** A pop-up's dim over everything behind the card: just under [overlay], so an app opened from the folder
     *  (which scales the overlay) doesn't shrink it. */
    private val dim = View(context).apply { background = android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK) }
    /** The folder icon where it was before the pop-up shrank Home: what the card closes back into. */
    private val restRect = Rect()
    /** Full-screen and never scaled, over the panel: takes the touches while the folder opens ([FolderPanel.openingTouch]). */
    @SuppressLint("ClickableViewAccessibility")
    private val catcher = object : View(context) {
        override fun onTouchEvent(event: MotionEvent) = panel?.openingTouch(event) == true
    }

    private fun report(e: Float) {
        openness = e
        if (dim.parent == null) return
        dim.background.alpha = (255 * POPUP_DIM * e).roundToInt()
        onOpenness(e)
    }

    private class Flier(val view: FlyingIcon, val from: RectF, val to: RectF)

    /** [placeholder]: the last app is one being dragged in, its cell left empty until it's dropped. */
    fun open(icon: FolderIconView, folder: HomeItem.Folder, apps: List<AppInfo>, items: List<HomeItem.App> = emptyList(),
             placeholder: Boolean = false) {
        if (isOpen || dragging) return
        if (anim?.isRunning == true) {
            // tapped again while it closes: it turns round where it is
            val p = panel
            if (p != null && !placeholder && this.folder?.id == folder.id) {
                isOpen = true
                p.passThrough = false
                runAnimation(open = true)
                return
            }
            finishClose()
        }
        val dp = dpProvider()
        isOpen = true
        source = icon
        this.folder = folder
        folderItems = items
        val anchor = Rect().also { icon.iconBoundsOnScreen(it) }
        restRect.set(anchor)
        val p = FolderPanel(context, dp, folder, apps, onLaunch, { title -> onRename(folder, title) }, { close() },
            if (popupFolders()) android.graphics.PointF(anchor.exactCenterX(), anchor.exactCenterY()) else null,
            transparency(folder)?.let { 1f - it / 100f } ?: FolderPanel.GLASS_OPACITY,
            // Style › Blur: the card is the closed folder's container opened up, the same glass under the same colour
            icon.glass?.takeIf { transparency(folder) != null },
            (icon.bgColor and 0x00FFFFFF) or ((android.graphics.Color.alpha(icon.bgColor) * icon.fillOpacity).toInt() shl 24)) { v, i ->
            configureIcon(v, items.getOrNull(i), apps[i])
        }
        if (placeholder) p.placeholder = p.gridIcons().lastOrNull()
        p.onAdd = { close(); onAddApps(folder) }
        p.onMore = { onFolderOptions(folder) }
        panel = p
        val host = overlay.parent as? ViewGroup
        if (p.isPopup && dim.parent == null && host != null) {
            dim.background.alpha = 0
            host.addView(dim, host.indexOfChild(overlay), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        overlay.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        (catcher.parent as? ViewGroup)?.removeView(catcher)
        overlay.addView(catcher, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        p.measure(View.MeasureSpec.makeMeasureSpec(dp.widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp.heightPx, View.MeasureSpec.EXACTLY))
        p.layout(0, 0, dp.widthPx, dp.heightPx)
        // The last frame still shows Home without the card: that's the glass's backdrop.
        if (p.isPopup && !p.hasFolderGlass) backdrop?.invoke(p.trayRect) { if (panel === p) p.setBackdrop(it) }
        runAnimation(open = true)
    }

    /**
     * An app in the open folder started to be dragged ([v] is its icon). While the finger stays on the folder the
     * other apps make room for it and a drop rearranges them; off the folder, the folder fades away and the drag goes
     * on over Home. Null if [v] isn't in the open folder. Call [finishDetached] when the touch ends.
     */
    fun beginDrag(v: IconView): DragController.FolderDrag? = beginDrag(v, adding = false)

    /**
     * [adding]: [v] is the placeholder for an app dragged in from outside (the folder opened under the drag); the
     * drop reports where it goes and the drag puts it there. The folder keeps opening meanwhile.
     */
    private fun beginDrag(v: IconView, adding: Boolean): DragController.FolderDrag? {
        val p = panel ?: return null
        if (!isOpen || v !in p.gridIcons()) return null
        if (!adding && anim?.isRunning == true) anim?.end()          // still opening: land it first
        p.hideKeyboard()
        p.beginReorder(v)
        dragging = true
        return object : DragController.FolderDrag {
            override val fullScreen get() = !p.isPopup
            override val adding get() = adding
            override val icon get() = v
            override fun isInside(x: Float, y: Float) = panel === p && isOpen && p.holdsDrag(x, y)
            override fun dragOver(x: Float, y: Float) = p.dragOver(x, y)
            override fun exit() = exitDrag(p, v)
            override fun cancel() {
                p.cancelReorder()
                if (adding) close()                                       // (its empty cell would stay)
            }
            override fun drop(landed: (Rect?, Int) -> Unit) {
                // after the touch event that dropped it: the icons may change parents
                p.post {
                    val order = p.commitReorder()
                    val f = folder
                    if (order != null && order.all { it < folderItems.size }) {
                        folderItems = order.map { folderItems[it] }
                        if (!adding && f != null) onReorder(f, folderItems)
                    }
                    if (adding) p.placeholder = null                      // (it shows when the drag view lands)
                    val rank = p.gridIcons().indexOf(v)
                    p.post { landed(if (panel === p) p.iconRectInPanel(v) else null, rank) }
                }
            }
        }
    }

    /**
     * An app dragged onto [icon] rested there: the folder opens around the drag, with an empty cell for it at the
     * end ([apps] / [items] end with it), and the drag goes on inside. Null if the folder can't open now.
     */
    fun openForDrop(icon: FolderIconView, folder: HomeItem.Folder, apps: List<AppInfo>, items: List<HomeItem.App>): DragController.FolderDrag? {
        if (isOpen || anim?.isRunning == true) return null
        // a folder the drag came out of is still attached (its icon has the touch stream) until the touch ends
        panel?.let { lingering += it; panel = null }
        open(icon, folder, apps, items, placeholder = true)
        val p = panel ?: return null
        return beginDrag(p.gridIcons().lastOrNull() ?: return null, adding = true)
    }

    /** Folders a drag left that still hold its touch stream, removed when it ends. */
    private val lingering = ArrayList<FolderPanel>()

    /**
     * The drag left the folder: it fades away and Home comes back (the drag sets the background), its icon showing
     * the apps still in it. The panel stays attached, invisible, until the touch ends.
     */
    private fun exitDrag(p: FolderPanel, dragged: IconView) {
        if (!isOpen || panel !== p) return
        isOpen = false
        anim?.cancel()
        p.cancelReorder()
        source?.let { s ->
            val key = dragged.appInfo?.key
            if (s is LargeFolderIconView) s.apps = s.apps.filter { (it.second as? AppInfo)?.key != key }
            else s.previews = p.gridIcons().filter { it !== dragged }.mapNotNull { it.appInfo?.previewIcon ?: it.icon }
            s.previewAlpha = 1f; s.containerAlpha = 1f
        }
        val home = homeContent()
        val fromHome = home.alpha
        val fromPanel = p.alpha
        val fromOpenness = openness
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = Easing.BASIC
            addUpdateListener {
                val f = it.animatedFraction
                p.alpha = fromPanel * (1f - f)
                home.alpha = fromHome + (1f - fromHome) * f
                report(fromOpenness * (1f - f))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { if (anim === animation && !dragging && panel === p) finishClose() }
            })
            start()
        }
    }

    /** The touch that dragged an app from the folder ended. */
    fun finishDetached() {
        dragging = false
        lingering.forEach { overlay.removeView(it) }
        lingering.clear()
        if (isOpen || anim?.isRunning == true) return
        finishClose()
    }

    /** Home was bound again while the folder is up: its icon is a new view now (hidden while the folder is open). */
    fun rebound(find: (HomeItem.Folder) -> FolderIconView?) {
        val f = folder ?: return
        if (panel == null) return
        val v = find(f) ?: return
        source = v
        if (isOpen) { v.previewAlpha = 0f; v.containerAlpha = 0f }
    }

    fun close(animate: Boolean = true) {
        if (!isOpen) return
        isOpen = false
        panel?.hideKeyboard()
        if (!animate) { finishClose(); return }
        // a tap while it closes is for Home (the folder icon under the shrinking card reopens it), not its apps
        panel?.passThrough = true
        runAnimation(open = false)
    }

    /** Closes the folder at once, open or still closing: Home is about to be covered. */
    fun closeNow() {
        if (isOpen) close(animate = false) else if (anim?.isRunning == true && !dragging) finishClose()
    }

    /** Where the last frame left the animation, so a reversal picks up from there instead of jumping. */
    private var shownE = 0f
    private var shownF = 0f
    private var shownTint = 1f
    private var shownContent = 0f

    private fun runAnimation(open: Boolean) {
        val p = panel ?: return
        val icon = source ?: return
        val dp = dpProvider()
        // turning round mid-way (a tap in and out): start from what's on screen
        val old = anim
        val turning = old?.isRunning == true
        anim = null
        old?.cancel()
        val e0 = if (turning) shownE else if (open) 0f else 1f
        val f0 = if (turning) shownF else if (open) 0f else 1f
        val tint0 = if (turning) shownTint else if (open) 1f else 0f
        val content0 = if (turning) shownContent else if (open) 0f else 1f
        val h0 = openness
        val target = if (open) 1f else 0f
        clearFliers()

        // geometry: container scales from the folder icon (a pop-up's, where it was before Home shrank: it is
        // there again as the close lands)
        val iconRect = Rect()
        if (p.isPopup) iconRect.set(restRect) else icon.iconBoundsOnScreen(iconRect)
        val tray = p.trayRect
        val srcSize = icon.iconSizePx()
        // a wide pop-up card (3×2) shrinks by its height: its centred square is what becomes the icon
        // (Icons › Folder icons › Outside: the icon's plate is smaller than the icon)
        val startScale = srcSize * icon.plateScale / (if (p.isPopup) minOf(tray.width(), tray.height()) else tray.width()).toFloat()
        val dx = iconRect.exactCenterX() - tray.exactCenterX()
        val dy = iconRect.exactCenterY() - tray.exactCenterY()
        p.pivotX = tray.exactCenterX()
        p.pivotY = tray.exactCenterY()

        // Pop-up (Launcher3 FolderAnimationManager): the card is revealed from the folder icon, its shape morphing
        // from the icon's rounded square to the whole card while it scales up from the icon's size, so it starts
        // and ends exactly as the icon. The full folder scales out a copy of the icon's squircle instead.
        val popup = p.isPopup
        // (the icon's fill as the icon draws it: its colour at the folder's fill opacity)
        if (popup) p.prepareReveal((icon.bgColor and 0x00FFFFFF) or
            ((android.graphics.Color.alpha(icon.bgColor) * icon.fillOpacity).toInt() shl 24),
            // the apps poking past the plate, from the icon into the card's coordinates: the icon (srcSize) is the
            // card's shorter side over plateScale, centred on the card (see startScale)
            icon.appShapes().apply {
                val k = minOf(tray.width(), tray.height()) / (srcSize * icon.plateScale)
                transform(android.graphics.Matrix().apply {
                    setTranslate(-srcSize / 2f, -srcSize / 2f); postScale(k, k); postTranslate(tray.width() / 2f, tray.height() / 2f)
                })
            })

        // folder container bitmap that scales with the panel
        val bgCopy = FolderBgCopy(context, icon)
        if (!popup) overlay.addView(bgCopy, FrameLayout.LayoutParams(srcSize, srcSize))
        bgCopy.x = iconRect.left.toFloat(); bgCopy.y = iconRect.top.toFloat()
        bgCopy.pivotX = 0f; bgCopy.pivotY = 0f

        // flying preview icons
        val previewRect = RectF()
        // (Icons › Folder icons › Pixel: the four in the preview fly to their places; the rest come with the card)
        val visible = (icon as? LargeFolderIconView)?.let { if (it.hasStack) 3 else 4 } ?: icon.previewSlots
        val inFolder = p.gridIcons().filter { it !== p.placeholder }
        (0 until minOf(visible, inFolder.size)).forEach { slot ->
            // (Pixel's preview shows the folder's top-left 2×2, not its first four)
            val target = inFolder.getOrNull(if (icon is LargeFolderIconView) slot else icon.rankInSlot(slot)) ?: return@forEach
            icon.childRect(slot, previewRect)
            previewRect.offset(iconRect.left.toFloat(), iconRect.top.toFloat())
            val to = RectF(p.iconRectInPanel(target))
            // (with the see-through themes the closed folder shows the symbol alone: AppInfo.previewIcon)
            val fly = FlyingIcon(context, target.icon, target.appInfo?.previewIcon)
            overlay.addView(fly, FrameLayout.LayoutParams(dp.iconSizePx, dp.iconSizePx))
            fliers += Flier(fly, RectF(previewRect), to)
            target.iconVisible = false
        }
        icon.previewAlpha = 0f
        icon.containerAlpha = 0f
        p.contentAlpha(content0, fliers.isNotEmpty())
        // while it opens, a tap goes by where the apps are landing (a quick tap aims at the app's place)
        p.opening = open

        // Both 350 ms on the folder curve. The pop-up leaves Home as it is behind the card; the full folder fades
        // Home out over a blurred, dimmed wallpaper. The pop-up card isn't faded as a whole (the reveal shapes
        // it), so it needs no offscreen layer redrawn on every frame.
        val total = if (popup) POPUP_SPRING_MS else DURATION
        val curve = if (popup) Easing.spring(POPUP_STIFFNESS, POPUP_DAMPING, total) else Easing.FOLDER
        val home = homeContent()
        val fromDrag = open && background.current == BackgroundController.Kind.DRAG
        if (!popup) {
            home.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            p.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            interpolator = Easing.LINEAR
            addUpdateListener { a ->
                val t = a.animatedFraction
                val f = f0 + (target - f0) * t                        // 0 = closed, 1 = open
                val e = e0 + (target - e0) * curve.getInterpolation(t)
                shownE = e; shownF = f
                // container (AbstractC0653q1.I)
                val s = startScale + (1f - startScale) * e
                p.scaleX = s; p.scaleY = s
                p.translationX = dx * (1f - e); p.translationY = dy * (1f - e)
                val ms = t * total
                if (popup) {
                    // Home dims and shrinks on the folder curve, not the spring (it doesn't bounce)
                    report(h0 + (target - h0) * Easing.FOLDER.getInterpolation((ms / DURATION).coerceAtMost(1f)))
                    // the icon's colour over the card hands over to the card's own look early in the open and
                    // comes back as the close reaches the icon (nothing to hand over with Style › Blur: they're the same)
                    val tint = (if (open) tint0 - ms / 140f else 1f - e / 0.12f).coerceIn(0f, 1f)
                    shownTint = tint
                    p.setReveal(e, tint)
                } else p.alpha = e.coerceIn(0f, 1f)
                // container bitmap: scales with the panel, fades over BG_FADE_DURATION after BG_FADE_DELAY (open) / 60 ms (close)
                val bs = (tray.width() / srcSize.toFloat() - 1f) * e + 1f
                bgCopy.scaleX = bs; bgCopy.scaleY = bs
                bgCopy.x = iconRect.left + (tray.left - iconRect.left) * e
                bgCopy.y = iconRect.top + (tray.top - iconRect.top) * e
                bgCopy.moved()
                bgCopy.alpha = if (open) 1f - ((ms - BG_FADE_DELAY) / BG_FADE_DURATION).coerceIn(0f, 1f) else (ms / 60f).coerceIn(0f, 1f)
                // flying icons
                for (fl in fliers) {
                    val l = fl.from.left + (fl.to.left - fl.from.left) * e
                    val tp = fl.from.top + (fl.to.top - fl.from.top) * e
                    val w = fl.from.width() + (fl.to.width() - fl.from.width()) * e
                    fl.view.pivotX = 0f; fl.view.pivotY = 0f
                    fl.view.x = l; fl.view.y = tp
                    val sc = w / dp.iconSizePx
                    fl.view.scaleX = sc; fl.view.scaleY = sc
                    fl.view.mix = e.coerceIn(0f, 1f)
                }
                // labels & other content of the panel
                val content = if (open) content0 + (1f - content0) * (if (popup) (ms - 100f) / 200f else (ms - 40f) / 200f).coerceIn(0f, 1f)
                    else content0 * (1f - (if (popup) ms / 160f else ms / 200f)).coerceIn(0f, 1f)
                shownContent = content
                p.contentAlpha(content, fliers.isNotEmpty())
                // background + home fade (DEACCEL_2)
                if (!popup) {
                    // opened by a drag hovering on it: from the drag's background
                    if (fromDrag) background.blend(BackgroundController.Kind.DRAG, BackgroundController.Kind.FOLDER, f)
                    else background.set(BackgroundController.Kind.FOLDER, f)
                    home.alpha = 1f - Easing.DECEL_2.getInterpolation(f)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    overlay.removeView(bgCopy)
                    if (!popup) {
                        home.setLayerType(View.LAYER_TYPE_NONE, null)
                        p.setLayerType(View.LAYER_TYPE_NONE, null)
                    }
                    if (anim !== animation) return                  // replaced (turned round or closed at once)
                    if (open) {
                        p.opening = false
                        p.gridIcons().forEach { if (it !== p.placeholder) it.iconVisible = true }
                        p.contentAlpha(1f, false)
                        clearFliers()
                    } else finishClose()
                }
            })
            start()
        }
    }

    private fun clearFliers() {
        fliers.forEach { overlay.removeView(it.view) }
        fliers.clear()
    }

    private fun finishClose() {
        val a = anim
        anim = null
        a?.cancel()
        clearFliers()
        panel?.let { overlay.removeView(it) }
        panel = null
        overlay.removeView(catcher)
        source?.apply { previewAlpha = 1f; containerAlpha = 1f }
        source = null
        folder = null
        dragging = false
        // (a drag that left the folder has the background now)
        if (background.current == BackgroundController.Kind.FOLDER) background.set(BackgroundController.Kind.FOLDER, 0f)
        homeContent().alpha = 1f
        if (dim.parent != null) {
            report(0f)
            (dim.parent as ViewGroup).removeView(dim)
        }
    }

    /**
     * Bitmap-only icon used for the flight, cut to the icon shape (the Pixel 10's white corners, 0.181). With a
     * different [preview] (the closed folder's symbol alone, for Frosted and Clear Colour) it turns from that into
     * [bmp] as the folder opens ([mix] 0 at the folder icon, 1 in the open folder), so the plates fade in and out with
     * the flight rather than snapping away as the close lands (user, 0.244).
     */
    private class FlyingIcon(context: Context, private val bmp: Bitmap?, preview: Bitmap? = null) : View(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val shaped = com.oneuiclone.home.icons.ShapedIcon()
        private val preview = preview?.takeIf { it !== bmp }
        var mix = 1f
            set(v) { if (field != v) { field = v; if (preview != null) invalidate() } }
        override fun onDraw(canvas: Canvas) {
            val dst = Rect(0, 0, width, height)
            val p = preview
            if (p == null) { paint.alpha = 255; bmp?.let { shaped.draw(canvas, it, dst, paint) }; return }
            if (mix < 1f) { paint.alpha = (255 * (1f - mix)).toInt(); shaped.draw(canvas, p, dst, paint) }
            if (mix > 0f) { paint.alpha = (255 * mix).toInt(); bmp?.let { shaped.draw(canvas, it, dst, paint) } }
        }
    }

    /**
     * Copy of the folder squircle container that scales out with the panel, drawn as the icon draws it: with
     * Style › Blur, the blurred wallpaper behind it under the colour at the icon's fill opacity. The glass is fixed
     * to the window, so the copy redraws on every frame it moves ([moved]).
     */
    private class FolderBgCopy(context: Context, private val src: FolderIconView) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = src.bgColor
            alpha = (android.graphics.Color.alpha(src.bgColor) * src.fillOpacity).toInt()
        }
        private val glass = src.glass
        private val glassPaint = GlassPaint()
        private val toWindow = android.graphics.Matrix()
        private val path = android.graphics.Path()
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            path.reset()
            com.oneuiclone.home.icons.IconShape.path(w.toFloat(), com.oneuiclone.home.icons.IconShape.container).let { path.set(it) }
            // the icon's plate (smaller when its apps poke out)
            val s = src.plateScale
            if (s != 1f) path.transform(android.graphics.Matrix().apply { setScale(s, s, w / 2f, h / 2f) })
            // and the apps poking past it, as the icon's outline has them (so the close lands on the icon's shape)
            val k = w / src.iconSizePx().toFloat()
            path.op(src.appShapes().apply { transform(android.graphics.Matrix().apply { setScale(k, k) }) }, android.graphics.Path.Op.UNION)
        }
        fun moved() { if (glass != null) invalidate() }
        override fun onDraw(canvas: Canvas) {
            toWindow.reset(); transformMatrixToGlobal(toWindow)
            if (glassPaint.aim(glass, toWindow)) canvas.drawPath(path, glassPaint.paint)
            canvas.drawPath(path, paint)
        }
    }
}

/** Full-screen folder content: title, option buttons, 4×4 paged grid, page indicator. */
@SuppressLint("ViewConstructor")
class FolderPanel(
    context: Context,
    private val dp: DeviceProfile,
    folder: HomeItem.Folder,
    apps: List<AppInfo>,
    onLaunch: (IconView, AppInfo) -> Unit,
    onRename: (String) -> Unit,
    private val onDismiss: () -> Unit,
    /** Screen centre of the folder icon for the compact pop-up style; null = One UI full-screen folder. */
    popupAnchor: android.graphics.PointF? = null,
    /** How opaque the pop-up's tint is over its blurred backdrop (Style › Blur sets it from the transparency). */
    glassOpacity: Float = GLASS_OPACITY,
    /** Style › Blur: the pop-up card shows this glass under [folderFill], exactly like the closed folder icon. */
    private val folderGlass: WidgetGlass? = null,
    private val folderFill: Int = 0,
    configure: (IconView, Int) -> Unit = { _, _ -> },
) : FrameLayout(context) {

    companion object {
        /** The pop-up card's own look (Original): the surface colour at 70 % over the blur. */
        const val GLASS_OPACITY = 0.7f
    }

    val trayRect: Rect
    private val popup = popupAnchor != null
    val isPopup get() = popup
    private val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    /** Pop-up glass: the wallpaper-tinted surface colour (Pixel's folder background) over the blurred backdrop. */
    private val surface = context.getColor(if (night) android.R.color.system_neutral1_800 else android.R.color.system_neutral1_50)
    private val glassOpacity = glassOpacity.coerceIn(0f, 1f)
    private val glassTint = (surface and 0x00FFFFFF) or ((this.glassOpacity * 255).toInt() shl 24)
    /** Until the backdrop arrives (a frame or two into the open), the card is near-opaque. */
    private val cardColor = (surface and 0x00FFFFFF) or 0xF2000000.toInt()
    private var ink = if (popup && !night) 0xFF1D1D1F.toInt() else 0xFFFAFAFA.toInt()
    private val title = EditText(context)
    private val pager = PagedView(context)
    private val indicator = PageIndicator(context, dp.indicatorSlotPx, dp.indicatorDotPx)
    private val icons = ArrayList<IconView>()
    /** The grid's columns and apps per page (set while it's built, in init: declared before it). */
    private var gridCols = 1
    private var perPage = 1
    private val addBtn = OptionButton(context, OptionButton.ADD, ink)
    private val settingBtn = OptionButton(context, OptionButton.MORE, ink)
    private val card: FrameLayout? = if (popup) FrameLayout(context) else null
    private val glass: BlurBackdropView? = if (popup) BlurBackdropView(context) else null
    var onAdd: () -> Unit = {}
    var onMore: () -> Unit = {}

    init {
        clipChildren = false
        if (popupAnchor != null) {
            trayRect = buildPopup(apps, folder, popupAnchor, onLaunch, onRename, configure)
        } else {
            trayRect = buildFull(apps, folder, onLaunch, onRename, configure)
        }
    }

    private fun setupTitle(folder: HomeItem.Folder, onRename: (String) -> Unit, sizeSp: Float) {
        title.setText(folder.title)
        title.setTextColor(ink)
        title.setHintTextColor((ink and 0x00FFFFFF) or 0x80000000.toInt())
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        title.typeface = Fonts.ui(context, 600)
        title.background = null
        title.isSingleLine = true
        title.inputType = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_CLASS_TEXT
        title.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        title.setOnEditorActionListener { v, _, _ -> onRename(v.text.toString()); hideKeyboard(); true }
    }

    /** Pages of [cols]×[rows] icon cells inside [pager]; returns the page count. */
    private fun fillPages(apps: List<AppInfo>, cols: Int, rows: Int, padH: Int, padT: Int, padB: Int,
                          onLaunch: (IconView, AppInfo) -> Unit, configure: (IconView, Int) -> Unit): Int {
        val per = cols * rows
        gridCols = cols; perPage = per
        val pages = maxOf(1, (apps.size + per - 1) / per)
        for (pg in 0 until pages) {
            val cl = CellLayout(context, cols, rows)
            cl.setPadding(padH, padT, padH, padB)
            pager.addView(cl, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            for (i in pg * per until minOf(apps.size, (pg + 1) * per)) {
                val a = apps[i]
                val idx = i - pg * per
                val v = IconView(context, dp).apply {
                    icon = a.icon; label = a.label; appInfo = a
                    contentDescription = a.label
                    darkText = popup && !night
                    setOnClickListener { onLaunch(this, a) }
                }
                configure(v, i)
                icons += v
                cl.addCell(v, idx % cols, idx / cols)
            }
        }
        indicator.count = pages
        pager.listener = object : PagedView.Listener { override fun onPageChanged(page: Int) = indicator.setPage(page) }
        return pages
    }

    /**
     * Pop-up folder, after Pixel's (Launcher3 Folder): a rounded glass card centred on the folder icon and kept on
     * screen, its grid just big enough for the apps (1×1 up to 4×3, then pages), the name in a footer below.
     * Cells are icon-sized plus Launcher3's folder_cell padding, so the card is much smaller than the full folder.
     */
    private fun buildPopup(apps: List<AppInfo>, folder: HomeItem.Folder, anchor: android.graphics.PointF, onLaunch: (IconView, AppInfo) -> Unit,
                           onRename: (String) -> Unit, configure: (IconView, Int) -> Unit): Rect {
        val w = dp.widthPx
        val h = dp.heightPx
        val (cols, rows) = FolderController.popupGrid(apps.size)
        val multiPage = apps.size > cols * rows
        val margin = dp.dpi(8f)
        val padTop = dp.dpi(16f)                                      // folder_content_padding_top
        val padBottom = padTop                                        // no footer: the pop-up shows only the apps
        val dots = if (multiPage) dp.dpi(14f) else 0
        val cellH = dp.iconSizePx + dp.dpi(12f) + labelBlock()        // icon + 2 × folder_cell_y_padding + label
        // icons as far apart across as down, so the icons make a square grid (the labels hang below them)
        val cellW = minOf(cellH, (w - 2 * margin) / cols)
        // the card's edge as far from the outer icons' sides as from the top icons and the bottom labels
        val inset = padTop + (cellH - dp.iconSizePx - labelBlock()) / 2
        val padX = (inset - (cellW - dp.iconSizePx) / 2).coerceIn(0, maxOf(0, (w - 2 * margin - cols * cellW) / 2))
        val cardW = cols * cellW + 2 * padX
        val gridW = cols * cellW
        val cardH = padTop + rows * cellH + dots + padBottom
        // Folder.centerAboutIcon: centred on the icon, bounded by the screen
        val minTop = dp.insets.top + margin
        val left = (anchor.x - cardW / 2f).roundToInt().coerceIn(margin, maxOf(margin, w - margin - cardW))
        val top = (anchor.y - cardH / 2f).roundToInt().coerceIn(minTop, maxOf(minTop, h - dp.insets.bottom - margin - cardH))
        val tray = Rect(left, top, left + cardW, top + cardH)

        val c = card!!
        c.background = GradientDrawable().apply { cornerRadius = dp.dp(28f); setColor(if (folderGlass != null) folderFill else cardColor) }   // dialogCornerRadius
        c.clipToOutline = true
        c.isClickable = true                                          // taps on the card itself don't close it
        c.elevation = dp.dp(2f)
        if (folderGlass != null) {
            c.addView(GlassView(context, folderGlass, folderFill), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else c.addView(glass, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(c, LayoutParams(cardW, cardH).apply { leftMargin = left; topMargin = top })

        c.addView(pager, LayoutParams(gridW, rows * cellH).apply { leftMargin = (cardW - gridW) / 2; topMargin = padTop })
        val pages = fillPages(apps, cols, rows, 0, 0, 0, onLaunch, configure)
        if (pages > 1) {
            indicator.darkDots = !night
            c.addView(indicator, LayoutParams(cardW, dots).apply { topMargin = padTop + rows * cellH })
        }

        // no footer (name, add, options): those are on the folder's long-press menu
        folderGlass?.let { inkForFolderGlass(it, tray) }
        return tray
    }

    // ---- pop-up reveal ----
    /** The card's visible shape at the start of the reveal: the folder icon's plate (in the icon shape), in card coordinates. */
    private val revealFrom = RectF()
    private var reveal = 1f
    private val revealRect = RectF()
    private val revealPath = android.graphics.Path()
    private val revealShape = android.graphics.Path()
    /** The folder icon's apps where they poke past its plate, in card coordinates ([prepareReveal]). */
    private val revealApps = android.graphics.Path()
    private val revealAppsNow = android.graphics.Path()
    /** The poking-out apps have sunk into the card this far into the reveal. */
    private val APPS_GONE = 0.15f
    /** The folder icon's colour over the card while it's still icon-sized (Original style). */
    private var iconTint: View? = null

    /**
     * Sets the card up to be revealed from the folder icon. When the panel is scaled down so the card's shorter
     * side is the icon's and centred on it, a square that side at the card's centre is exactly the icon. (A square
     * the card's width cut to a wide card's height stayed wide, and snapped to the icon at the end of the close.)
     * The outline starts as the icon's own squircle plate and eases into the card's rounded rectangle: a rounded
     * rectangle all the way snapped to the squircle's rounder corners on the last frame.
     */
    fun prepareReveal(iconColor: Int, apps: android.graphics.Path) {
        val c = card ?: return
        revealApps.set(apps)
        val w = c.width.toFloat()
        val h = c.height.toFloat()
        val side = minOf(w, h)
        val inset = side * 3f / 180f                                  // FolderIconView's plate inside its icon
        revealFrom.set(w / 2f - side / 2f + inset, h / 2f - side / 2f + inset, w / 2f + side / 2f - inset, h / 2f + side / 2f - inset)
        c.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val f = reveal
                revealRect.set(revealFrom.left + (0f - revealFrom.left) * f, revealFrom.top + (0f - revealFrom.top) * f,
                    revealFrom.right + (view.width - revealFrom.right) * f, revealFrom.bottom + (view.height - revealFrom.bottom) * f)
                // (the folder's shape: Icons › Keep folders and widgets squircle keeps it One UI's)
                val shape = com.oneuiclone.home.icons.IconShape.container
                if (shape == com.oneuiclone.home.icons.IconShape.SQUIRCLE) {
                    val r = revealFrom.width() / 2f + (dp.dp(28f) - revealFrom.width() / 2f) * f
                    roundedShape(revealPath, revealRect, r, FolderController.SQUIRCLE_V + (FolderController.CIRCLE_K - FolderController.SQUIRCLE_V) * f, FolderController.CIRCLE_K)
                } else if (shape == com.oneuiclone.home.icons.IconShape.CIRCLE) {
                    // a rounded rectangle is exactly a circle at the icon (one with half-side corners), so the
                    // corners just ease from the icon's into the card's the whole way
                    val r0 = revealFrom.width() * com.oneuiclone.home.icons.IconShape.corner(0f, shape)
                    roundedShape(revealPath, revealRect, r0 + (dp.dp(28f) - r0) * f, FolderController.CIRCLE_K, FolderController.CIRCLE_K)
                } else {
                    // Square (iOS continuous corners), Cookie, Hexagon: a rectangle rounding into the card's corners, cut to the shape. The shape is
                    // sized from the rectangle (as big at the icon, as big as holds it all by SHAPE_GONE), so it
                    // eases out over the morph. (Sized from the icon, it stayed a near-square until the last
                    // frames of a close and then snapped into the shape.)
                    roundedShape(revealPath, revealRect, dp.dp(28f) * f, FolderController.CIRCLE_K, FolderController.CIRCLE_K)
                    val grow = f / FolderController.SHAPE_GONE
                    if (grow < 1f) {
                        // (the shape fills 98 of its 100 units; the plate draws it 100/98 bigger to fill the icon)
                        val side = maxOf(revealRect.width(), revealRect.height()) * 100f / 98f
                        val size = side + (com.oneuiclone.home.icons.IconShape.cover(side, side) - side) * grow
                        com.oneuiclone.home.icons.IconShape.path(revealShape, revealRect.centerX(), revealRect.centerY(), size, shape)
                        revealPath.op(revealShape, android.graphics.Path.Op.INTERSECT)
                    }
                }
                // the apps poking past the icon's plate (Pixel, Outside): there at the icon, sinking into the card
                // (scaled in about its centre, where they're inside its plate) by APPS_GONE
                if (!revealApps.isEmpty && f < APPS_GONE) {
                    val g = (f / APPS_GONE).let { it * it * (3f - 2f * it) }
                    val s = 1f - 0.6f * g
                    revealApps.transform(android.graphics.Matrix().apply { setScale(s, s, view.width / 2f, view.height / 2f) }, revealAppsNow)
                    revealPath.op(revealAppsNow, android.graphics.Path.Op.UNION)
                }
                outline.setPath(revealPath)
                // the icon casts no shadow: the card's fades with the reveal (out over the last stretch of a close,
                // in as it opens) instead of vanishing when the icon takes over
                outline.alpha = minOf(cardLook, f / SHADOW_FADE)
            }
        }
        if (folderGlass == null && iconTint == null) {
            iconTint = View(context).apply { setBackgroundColor(iconColor); alpha = 0f }
            c.addView(iconTint, 1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    /**
     * [out] = [rect] with corners of radius [r], each a cubic whose handles are [kv]·r along the vertical sides and
     * [kh]·r along the horizontal ones. With r half a square's side, kv = [FolderController.SQUIRCLE_V] and kh = [FolderController.CIRCLE_K] it's One
     * UI's icon mask (IconFactory.ONE_UI_MASK); with both [FolderController.CIRCLE_K] it's an ordinary rounded rectangle.
     */
    private fun roundedShape(out: android.graphics.Path, rect: RectF, r: Float, kv: Float, kh: Float) {
        val rr = minOf(r, rect.width() / 2f, rect.height() / 2f)
        val l = rect.left; val t = rect.top; val rt = rect.right; val b = rect.bottom
        out.reset()
        out.moveTo(rt, t + rr)
        out.cubicTo(rt, t + rr - kv * rr, rt - rr + kh * rr, t, rt - rr, t)
        out.lineTo(l + rr, t)
        out.cubicTo(l + rr - kh * rr, t, l, t + rr - kv * rr, l, t + rr)
        out.lineTo(l, b - rr)
        out.cubicTo(l, b - rr + kv * rr, l + rr - kh * rr, b, l + rr, b)
        out.lineTo(rt - rr, b)
        out.cubicTo(rt - rr + kh * rr, b, rt, b - rr + kv * rr, rt, b - rr)
        out.close()
    }

    /** How much of the card's own look (frosted glass, background, shadow) shows under the icon's colour. */
    private var cardLook = 1f
    /** The card's shadow is whole from this far into the reveal; nearer the icon it fades out. */
    private val SHADOW_FADE = 0.35f

    /**
     * Reveal progress [f] (0 = the icon, 1 = the whole card) and how much the card looks like the icon instead
     * ([tint]): the icon's colour comes in as the card's glass, background and shadow go, so at 1 it's drawn as
     * the icon is (its colour over Home) and the hand-over to the real icon doesn't jump.
     */
    fun setReveal(f: Float, tint: Float) {
        reveal = f.coerceIn(0f, 1f)
        iconTint?.let { t ->
            t.alpha = tint
            cardLook = 1f - tint
            card?.background?.alpha = (255 * cardLook).toInt()
            glass?.alpha = cardLook
        }
        card?.invalidateOutline()
    }

    /** The card shows the closed folder's glass instead of a snapshot of what's behind it. */
    val hasFolderGlass get() = folderGlass != null

    /** Text and glyphs that read on the folder glass over [tray] (window coordinates), with the fill on top. */
    private fun inkForFolderGlass(g: WidgetGlass, tray: Rect) {
        val b = g.bitmap ?: return
        val r = Rect((tray.left / g.scale).toInt(), (tray.top / g.scale).toInt(), (tray.right / g.scale).toInt(), (tray.bottom / g.scale).toInt())
        if (!r.intersect(0, 0, b.width, b.height) || r.isEmpty) return
        val behind = android.graphics.Color.valueOf(Bitmap.createScaledBitmap(Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height()), 1, 1, true).getPixel(0, 0))
        val fill = android.graphics.Color.valueOf(folderFill)
        val a = fill.alpha()
        val seen = android.graphics.Color.valueOf(behind.red() + (fill.red() - behind.red()) * a,
            behind.green() + (fill.green() - behind.green()) * a, behind.blue() + (fill.blue() - behind.blue()) * a)
        setInk(seen.luminance() > 0.45f)
    }

    /** What's behind the card, captured before it opened: the card becomes frosted glass. */
    fun setBackdrop(b: Bitmap) {
        val g = glass ?: return
        g.tint = glassTint
        // A see-through card (Style › Blur) can end up dark in light mode or light in dark mode: the text follows
        // what the glass actually looks like
        if (glassOpacity < GLASS_OPACITY) {
            val behind = android.graphics.Color.valueOf(Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0))
            val tint = android.graphics.Color.valueOf(surface)
            val o = glassOpacity
            val seen = android.graphics.Color.valueOf(behind.red() + (tint.red() - behind.red()) * o,
                behind.green() + (tint.green() - behind.green()) * o, behind.blue() + (tint.blue() - behind.blue()) * o)
            setInk(seen.luminance() > 0.45f)
        }
        g.set(b, dp.dp(32f) * b.width / trayRect.width().toFloat())
        (card?.background as? GradientDrawable)?.setColor(glassTint)  // kept for the outline's shadow
    }

    /** Dark text and glyphs on a light card, light ones on a dark card. */
    private fun setInk(darkInk: Boolean) {
        ink = if (darkInk) 0xFF1D1D1F.toInt() else 0xFFFAFAFA.toInt()
        title.setTextColor(ink)
        title.setHintTextColor((ink and 0x00FFFFFF) or 0x80000000.toInt())
        addBtn.color = ink
        settingBtn.color = ink
        icons.forEach { it.darkText = darkInk }
    }

    /** The label under an icon and its gap: what makes an app's cell taller than it is wide. */
    private fun labelBlock(): Int {
        val fm = android.text.TextPaint().apply { textSize = dp.labelTextSizePx }.fontMetrics
        return (dp.iconLabelGapPx + fm.descent - fm.ascent).roundToInt()
    }

    private fun buildFull(apps: List<AppInfo>, folder: HomeItem.Folder, onLaunch: (IconView, AppInfo) -> Unit,
                          onRename: (String) -> Unit, configure: (IconView, Int) -> Unit): Rect {
        val w = dp.widthPx
        val h = dp.heightPx
        val padH = (w * 0.031f).roundToInt()                           // icon_tray_horizontal_padding
        val padT = (h * 0.021f).roundToInt()
        val padB = (h * 0.027f).roundToInt()
        // icon_tray_width spans the full tray (aligned with the + / ⋮ buttons) and is split into COLS columns;
        // icon_tray_height is for 4 rows, so keep that row height and wrap the tray around ROWS
        val specW = (w * 0.792f).roundToInt()
        val cellH = ((h * 0.552f).roundToInt() - padT - padB) / FolderController.SPEC_GRID
        // columns widened past the spec's to the row height, so the icons make a square grid, not a tall one
        val cellW = minOf(cellH, (w - 2 * padH) / FolderController.COLS)
        val trayW = FolderController.COLS * cellW + 2 * padH
        val trayH = cellH * FolderController.ROWS + padT + padB
        val trayTop = (h * 0.385f).roundToInt()                        // icon_tray_margin_top
        val tray = Rect((w - trayW) / 2, trayTop, (w + trayW) / 2, trayTop + trayH)

        // title (open_folder_title: 34sp, weight 600, #fafafa, hint #80ffffff)
        setupTitle(folder, onRename, 34f)
        title.gravity = Gravity.CENTER
        // The spec's 5.8 % box can't hold 34sp plus EditText padding (the glyphs were clipped): size it to the
        // text, centred where that box was.
        title.setPadding(0, 0, 0, 0)
        val titleW = (w * 0.875f).roundToInt()
        val titleH = title.lineHeight + dp.dpi(8f)
        addView(title, LayoutParams(titleW, titleH).apply {
            leftMargin = (w - titleW) / 2
            topMargin = (h * (0.163f + 0.058f / 2f)).roundToInt() - titleH / 2  // title_margin_top + half height
        })

        // option buttons (add apps / folder settings)
        val btn = (w * 0.078f).roundToInt()                            // option_btn_icon_size
        val btnTop = (h * 0.325f).roundToInt()                         // option_btn_margin_top
        addBtn.setOnClickListener { onAdd() }
        settingBtn.setOnClickListener { onMore() }
        // moved out with the right-hand column, so ⋮ still lines up with its icons' right edge
        fun rightIconEdge(trayW: Int, cellW: Int) = (w - trayW) / 2 + padH + (FolderController.COLS - 1) * cellW + (cellW + dp.iconSizePx) / 2
        val btnShift = rightIconEdge(trayW, cellW) - rightIconEdge(specW, (specW - 2 * padH) / FolderController.COLS)
        addView(addBtn, LayoutParams(btn, btn).apply { leftMargin = (w * 0.661f).roundToInt() + btnShift; topMargin = btnTop })
        addView(settingBtn, LayoutParams(btn, btn).apply { leftMargin = (w * 0.781f).roundToInt() + btnShift; topMargin = btnTop })

        // paged 4×4 grid; the tray has no visible background, so a tap on an empty cell closes the folder
        addView(pager, LayoutParams(trayW, trayH).apply { leftMargin = tray.left; topMargin = tray.top })
        fillPages(apps, FolderController.COLS, FolderController.ROWS, padH, padT, padB, onLaunch, configure)
        pager.onEmptyTap = { _, _ -> onDismiss() }
        addView(indicator, LayoutParams(LayoutParams.MATCH_PARENT, (h * 0.043f).roundToInt()).apply {
            topMargin = (h * 0.937f).roundToInt() - (h * 0.043f).roundToInt() / 2  // page_indicator_margin_top
        })
        return tray
    }

    fun gridIcons(): List<IconView> = icons

    /** The cell kept for an app being dragged in from outside: empty until it's dropped (or the drag leaves). */
    var placeholder: IconView? = null
        set(v) { field = v; v?.let { it.iconVisible = false; it.labelAlpha = 0f } }

    // ---- an app dragged around inside the folder ----
    private var dragIcon: IconView? = null
    /** Where the dragged app would land now: its rank among the folder's apps. */
    private var dragTo = -1
    private var pendingRank = -1
    private var reorderPending: Runnable? = null
    private var flipPending: Runnable? = null
    /** Icons slid aside for the dragged app, and where to. */
    private val slid = HashMap<IconView, Pair<Float, Float>>()

    fun beginReorder(v: IconView) {
        dragIcon = v
        dragTo = icons.indexOf(v)
        pendingRank = dragTo
    }

    /** Whether a drag at (x, y) (window coords) is still in the folder; off it for a moment, the folder closes. */
    fun holdsDrag(x: Float, y: Float): Boolean {
        val r = RectF(trayRect)
        if (popup) r.inset(-dp.dp(16f), -dp.dp(16f))
        else {
            r.inset(-dp.dp(16f), -dp.dp(24f))
            if (pager.childCount > 1) { r.left = 0f; r.right = width.toFloat() }   // the screen edges turn pages
        }
        return r.contains(x, y)
    }

    /** The drag moved inside the folder: the apps make room where it is, and resting at an edge turns the page. */
    fun dragOver(x: Float, y: Float) {
        val dir = pageEdge(x)
        val target = pager.currentPage + dir
        if (dir != 0 && target in 0 until pager.childCount) {
            if (flipPending == null) {
                flipPending = Runnable { flipPending = null; pager.snapToPage(target) }.also { postDelayed(it, 500) }
            }
        } else {
            flipPending?.let { removeCallbacks(it) }; flipPending = null
        }
        val rank = rankAt(x, y)
        if (rank < 0 || rank == pendingRank) return
        reorderPending?.let { removeCallbacks(it) }; reorderPending = null
        pendingRank = rank
        if (rank == dragTo) return
        reorderPending = Runnable { reorderPending = null; reorderTo(rank) }.also { postDelayed(it, DragController.REORDER_DELAY) }
    }

    /** -1 / 1 when (x) rests in the strip that turns to the previous / next page, else 0. */
    private fun pageEdge(x: Float): Int {
        if (pager.childCount < 2) return 0
        val zone = if (popup) trayRect.width() * 0.12f else width * 0.06f
        val l = if (popup) trayRect.left.toFloat() else 0f
        val r = if (popup) trayRect.right.toFloat() else width.toFloat()
        return when { x < l + zone -> -1; x > r - zone -> 1; else -> 0 }
    }

    /** Rank of the cell under (x, y) (window coords) on the page shown; -1 off the grid. */
    private fun rankAt(x: Float, y: Float): Int {
        val page = pager.currentPage
        val cl = pager.pageAt(page) as? CellLayout ?: return -1
        val loc = IntArray(2); cl.getLocationInWindow(loc)
        val px = x - loc[0]; val py = y - loc[1]
        if (px < 0 || py < 0 || px >= cl.width || py >= cl.height) return -1
        val cell = IntArray(2); cl.cellAt(px, py, cell)
        return (page * perPage + cell[1] * gridCols + cell[0]).coerceAtMost(icons.size - 1)
    }

    /** Top-left of the cell for [rank], in the pager's content coordinates. */
    private fun cellOrigin(rank: Int): Pair<Float, Float> {
        val cl = pager.pageAt(rank / perPage) as CellLayout
        val i = rank % perPage
        return (cl.left + cl.cellLeft(i % gridCols)).toFloat() to (cl.top + cl.cellTop(i / gridCols)).toFloat()
    }

    /** The order the icons are shown in while the dragged app would land at [to]. */
    private fun orderWith(v: IconView, to: Int): List<IconView> =
        ArrayList(icons).apply { remove(v); add(to.coerceIn(0, size), v) }

    /** The apps slide to make a gap at [to], the ones next to the old gap first (Launcher3 Folder.realTimeReorder). */
    private fun reorderTo(to: Int) {
        val v = dragIcon ?: return
        val from = dragTo
        dragTo = to
        var n = 0
        orderWith(v, to).withIndex().filter { it.value !== v }.sortedBy { kotlin.math.abs(it.index - from) }.forEach { (k, icon) ->
            val (ox, oy) = cellOrigin(icons.indexOf(icon))
            val (nx, ny) = cellOrigin(k)
            val goal = (nx - ox) to (ny - oy)
            if ((slid[icon] ?: (0f to 0f)) == goal) return@forEach
            slid[icon] = goal
            icon.animate().translationX(goal.first).translationY(goal.second)
                .setStartDelay(minOf(n++, 5) * 20L).setDuration(200).setInterpolator(Easing.BASIC).start()
        }
    }

    private fun stopDragTimers() {
        reorderPending?.let { removeCallbacks(it) }; reorderPending = null
        flipPending?.let { removeCallbacks(it) }; flipPending = null
    }

    /** The drag ended elsewhere (or was cancelled): everything slides back. */
    fun cancelReorder() {
        stopDragTimers()
        for (icon in slid.keys) icon.animate().translationX(0f).translationY(0f).setStartDelay(0).setDuration(150).setInterpolator(Easing.BASIC).start()
        slid.clear()
        dragIcon = null
    }

    /**
     * Dropped in the folder: every icon takes the cell it slid to. Returns the new order as indices into the old
     * one, or null if nothing moved.
     */
    fun commitReorder(): List<Int>? {
        stopDragTimers()
        val v = dragIcon ?: return null
        dragIcon = null
        val order = orderWith(v, dragTo)
        slid.clear()
        val indices = order.map { icons.indexOf(it) }
        if (indices.withIndex().all { it.index == it.value }) { icons.forEach { it.animate().cancel(); it.translationX = 0f; it.translationY = 0f }; return null }
        icons.clear(); icons.addAll(order)
        order.forEachIndexed { k, icon ->
            icon.animate().cancel(); icon.translationX = 0f; icon.translationY = 0f
            val cl = pager.pageAt(k / perPage) as CellLayout
            val i = k % perPage
            if (icon.parent !== cl) {
                (icon.parent as? ViewGroup)?.removeView(icon)
                cl.addCell(icon, i % gridCols, i / gridCols)
            } else {
                (icon.layoutParams as CellLayout.LayoutParams).apply { cellX = i % gridCols; cellY = i / gridCols }
                cl.requestLayout()
            }
        }
        // the dragged app's page, so it's seen landing
        val page = order.indexOf(v) / perPage
        if (page != pager.currentPage) pager.snapToPage(page)
        return indices
    }

    /** Final (unscaled) icon rect of [v] in panel/screen coordinates. */
    /** The app cell at window point ([x], [y]) once the folder is open (the panel at rest fills the window). */
    private fun iconAtRest(x: Int, y: Int): IconView? {
        if (!trayRect.contains(x, y)) return null
        val r = Rect()
        return icons.firstOrNull { v ->
            v !== placeholder && v.isShown && r.run { set(0, 0, v.width, v.height); offsetDescendantRectToMyCoords(v, this); contains(x, y) }
        }
    }

    fun iconRectInPanel(v: IconView): Rect {
        val r = Rect(v.iconLeft(), v.iconTop(), v.iconLeft() + dp.iconSizePx, v.iconTop() + dp.iconSizePx)
        offsetDescendantRectToMyCoords(v, r)
        return r
    }

    /** Title/buttons/labels alpha during the morph (icons fly separately). */
    fun contentAlpha(a: Float, iconsFlying: Boolean) {
        title.alpha = a; addBtn.alpha = a; settingBtn.alpha = a; indicator.alpha = a
        icons.forEach { if (it !== placeholder) it.labelAlpha = a }
        // the apps that don't fly from the folder icon (past its preview: Pixel's fifth on) come and go with the rest
        icons.forEach { if (it !== placeholder) it.iconAlpha = if (iconsFlying) a else 1f }
        if (!iconsFlying) icons.forEach { if (it !== placeholder) it.iconVisible = true }
    }

    fun hideKeyboard() {
        if (title.hasFocus()) {
            title.clearFocus()
            context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(title.windowToken, 0)
        }
    }

    /** Closing: touches go to Home underneath. */
    var passThrough = false
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        if (passThrough && ev.actionMasked == MotionEvent.ACTION_DOWN) false else super.dispatchTouchEvent(ev)

    /**
     * Set while the folder opens. A tap then goes to the app whose place it's on once open, and opens it on lift.
     * Left to the views, the card grows on under the finger between down and up, the app slides out from under it
     * and the click is cancelled (a quick tap on an app did nothing).
     */
    var opening = false
    private var tapping = false
    private var tapApp: IconView? = null
    private var tapOutside = false
    private var tapX = 0f
    private var tapY = 0f
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    /**
     * A touch while the folder opens, from the controller's full-screen catcher (the panel is scaled down then, and
     * a view only gets touches inside its scaled bounds: a tap outside the small card went to Home underneath).
     * False lets it through to the panel.
     */
    fun openingTouch(event: MotionEvent): Boolean {
        // No double-tap guard on the folder icon's spot (0.196–0.200): the app that lands there (Drive in a pop-up
        // clamped to the screen edge) is what a fast tapper aims at, and Launcher3 lets that tap through too.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && opening) {
            tapping = true
            tapX = event.rawX; tapY = event.rawY
            tapApp = iconAtRest(tapX.toInt(), tapY.toInt())
            tapOutside = !trayRect.contains(tapX.toInt(), tapY.toInt()) &&
                !(!popup && tapY.toInt() in title.top..title.bottom)
            return true
        }
        if (tapping) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (kotlin.math.hypot(event.rawX - tapX, event.rawY - tapY) > slop) { tapApp = null; tapOutside = false }
                MotionEvent.ACTION_UP -> {
                    tapping = false
                    val app = tapApp
                    tapApp = null
                    if (app != null && app.isAttachedToWindow) app.performClick() else if (tapOutside) onDismiss()
                }
                MotionEvent.ACTION_CANCEL -> { tapping = false; tapApp = null }
            }
            return true
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // tap outside the tray closes the folder
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val x = event.x.toInt(); val y = event.y.toInt()
            val titleHit = !popup && y in title.top..title.bottom
            if (!trayRect.contains(x, y) && !titleHit) onDismiss()
        }
        return true
    }

    /** Simple One UI-style glyph buttons. */
    private class OptionButton(context: Context, private val kind: Int, color: Int) : View(context) {
        companion object { const val ADD = 0; const val MORE = 1 }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        var color: Int
            get() = p.color
            set(v) { p.color = v; invalidate() }
        init { isClickable = true }
        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            p.strokeWidth = width * 0.075f
            if (kind == ADD) {
                val r = width * 0.26f
                canvas.drawLine(c - r, c, c + r, c, p)
                canvas.drawLine(c, c - r, c, c + r, p)
            } else {
                p.style = Paint.Style.FILL
                val r = width * 0.055f
                for (i in -1..1) canvas.drawCircle(c, c + i * width * 0.2f, r, p)
                p.style = Paint.Style.STROKE
            }
        }
    }
}
