package com.oneuiclone.home.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.model.AppInfo
import com.oneuiclone.home.model.Container
import com.oneuiclone.home.model.HomeItem
import com.oneuiclone.home.model.WorkspaceModel
import com.oneuiclone.home.motion.Easing
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Drag & drop (DragAnimatableFrameLayout / DragVIProvider, spec §13):
 *  pickup: move under finger 317 ms (0.64,0,0.67,1); scale 1→0.8 267 ms after 200 ms (0.6,0,0.4,1);
 *  then 0.8→1 183 ms (0.16,0,0.4,1). Background: Drag state (blur factor 0.5, no dim) over 150 ms.
 */
class DragController(
    private val context: Context,
    private val overlay: FrameLayout,
    private val dpProvider: () -> DeviceProfile,
    private val model: () -> WorkspaceModel?,
    private val workspace: () -> PagedView,
    private val hotseat: () -> CellLayout,
    private val background: BackgroundController,
    private val rebind: () -> Unit,
    private val findViewFor: (HomeItem) -> IconView?,
    private val deleteWidget: (HomeItem.Widget) -> Unit = {},
    /** Icon or widget view of a Home screen item (for the make-room preview). */
    private val findItemView: (HomeItem) -> View? = { findViewFor(it) },
    /** An app rested on a folder: open it around the drag (with an empty cell for the app), or null if it can't. */
    private val openFolderForDrop: (FolderIconView, HomeItem.Folder, AppInfo, HomeItem.App) -> FolderDrag? = { _, _, _, _ -> null },
) {
    companion object {
        /** Hover this long over an occupied spot before the items there move aside (Launcher3 REORDER_TIMEOUT-ish). */
        const val REORDER_DELAY = 200L
        /** Off an open folder this long before it closes and the drag goes on over Home (Launcher3 ON_EXIT_CLOSE_DELAY 400). */
        const val FOLDER_EXIT_DELAY = 300L
        /** Hovering over an app: the folder plate behind it grows to this (One UI's 1.1 hid behind the dragged icon). */
        const val MAKE_FOLDER_SCALE = 1.2f
        /** Hovering over a folder: its container grows to this. */
        const val ADD_TO_FOLDER_SCALE = 1.15f
        /** The dragged icon over a folder target, as if going in. */
        const val OVER_FOLDER_DRAG_SCALE = 0.85f
        /** Into a folder (IconViewImpl.getFolderTransformAnim, FOLDER_CREATE_DURATION_MS): decelerate 2. */
        const val INTO_FOLDER_DURATION = 350L
        /** Resting on a folder this long opens it, to choose where in it the app goes (a quicker drop adds it last). */
        const val SPRING_OPEN_DELAY = 500L
    }

    /** The open folder an app is dragged around in, until the finger leaves it (FolderController). */
    interface FolderDrag {
        /** The One UI full-screen folder (its blurred background), not the pop-up card. */
        val fullScreen: Boolean
        fun isInside(x: Float, y: Float): Boolean
        /** Make room where the drag is, turn pages at the edges. */
        fun dragOver(x: Float, y: Float)
        /** The app comes from outside the folder (it opened under the drag); [icon] is the cell kept for it. */
        val adding: Boolean
        /** The dragged app's icon in the folder. */
        val icon: IconView
        /** The drag left: the folder fades away, Home comes back. */
        fun exit()
        fun cancel()
        /** Dropped in the folder: the new order is kept; [landed] gets the dragged icon's place (window coords) and rank. */
        fun drop(landed: (Rect?, Int) -> Unit)
    }

    var isDragging = false; private set
    /** An item dropped on Remove (not one dragged out of a folder, not a widget): for the Undo pill. */
    var onRemoved: ((HomeItem) -> Unit)? = null
    private var folderDrag: FolderDrag? = null
    private var folderExitPending: Runnable? = null
    private var springPending: Runnable? = null
    /** The folder that opened under the drag (the app goes into it if dropped there). */
    private var addingTo: HomeItem.Folder? = null

    private var dragView: DragView? = null
    private var pickupAnim: ValueAnimator? = null
    private var removeBar: RemoveBar? = null
    private var source: IconView? = null
    private var item: HomeItem? = null
    private var app: AppInfo? = null
    private var fromApps = false
    private var touchX = 0f
    private var touchY = 0f
    private var pickupStart = 0L
    private var startX = 0f
    private var startY = 0f
    private val main = Handler(Looper.getMainLooper())
    private var pageFlipPending: Runnable? = null
    private var hoverTarget: IconView? = null
    private val folderBgAnims = java.util.WeakHashMap<IconView, ValueAnimator>()
    private var bgAnim: ValueAnimator? = null
    /** Widget being moved: drawn live into the drag view, kept (invisible) in its cell until the drop. */
    private var widgetSource: WidgetContainer? = null
    private var grabX = 0f
    private var grabY = 0f
    /** Items currently slid aside by the preview, and the target that preview is for. */
    private var shownPushes: Map<HomeItem, Pair<Int, Int>> = emptyMap()
    private var shownKey: String? = null
    private var pendingKey: String? = null
    private var reorderPending: Runnable? = null
    /** Size of the dragged icon (a large folder's Liquid Crystal pane is taller than it is wide). */
    private var dragSize = 0
    private var dragHeight = 0
    /** Free-cell dots and the landing outline (design opinion 9), under the drag view. */
    private var guides: DragGuides? = null
    private var lastTarget: Target? = null

    /** [folder]: the app is dragged from an open folder, which stays up while the drag is on it. */
    fun start(src: IconView, it: HomeItem?, a: AppInfo?, apps: Boolean, x: Float, y: Float, folder: FolderDrag? = null) {
        val dp = dpProvider()
        val bmp = (src as? FolderIconView)?.let { snapshot(it) } ?: src.displayedIcon() ?: return
        isDragging = true
        folderDrag = folder
        source = src; item = it; app = a; fromApps = apps
        touchX = x; touchY = y
        dragSize = src.iconSizePx()
        dragHeight = src.iconHeightPx()
        val r = Rect(); src.iconBoundsOnScreen(r)
        startX = r.left.toFloat(); startY = r.top.toFloat()
        src.iconVisible = false
        src.labelAlpha = 0f
        src.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        val dv = DragView(context, bmp, shape = src !is FolderIconView)
        overlay.addView(dv, FrameLayout.LayoutParams(dragSize, dragHeight))
        dv.x = startX; dv.y = startY
        dv.elevation = dp.dp(8f)
        dragView = dv
        pickupStart = android.os.SystemClock.uptimeMillis()
        // scale "breathe" (DragScaleDown then DragScaleUp), one animator so a drop can stop it
        pickupAnim = ValueAnimator.ofFloat(0f, 450f).apply {
            startDelay = 200; duration = 450; interpolator = Easing.LINEAR
            addUpdateListener {
                val ms = it.animatedValue as Float
                val s = if (ms < 267f) 1f - 0.2f * Easing.DRAG_SCALE_DOWN.getInterpolation(ms / 267f)
                    else 0.8f + 0.2f * Easing.DRAG_SCALE_UP.getInterpolation(((ms - 267f) / 183f).coerceAtMost(1f))
                dv.scaleX = s; dv.scaleY = s
            }
            start()
        }
        // follow-finger blend for the first 317 ms
        dv.postOnAnimation(object : Runnable {
            override fun run() {
                if (dragView !== dv) return
                position()
                if (android.os.SystemClock.uptimeMillis() - pickupStart < 340) dv.postOnAnimation(this)
            }
        })

        if (item != null && !fromApps) {
            val rb = RemoveBar(context, dp)
            overlay.addView(rb, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, rb.barHeight))
            rb.alpha = 0f
            rb.animate().alpha(1f).setDuration(150).start()
            removeBar = rb
        }
        if (folder == null) animateBackground(1f, 150)                   // (in a folder: the folder's background)
        if (folder == null) updateGuides(currentTarget())                 // (in a folder: once it leaves it)
    }

    /** Long-press + move on a widget: it lifts slightly and follows the finger, keeping the grab point. */
    fun startWidget(src: WidgetContainer, x: Float, y: Float) {
        val dp = dpProvider()
        isDragging = true
        widgetSource = src; item = src.item; app = null; fromApps = false
        touchX = x; touchY = y
        val loc = IntArray(2); src.getLocationInWindow(loc)
        grabX = x - loc[0]; grabY = y - loc[1]
        src.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val dv = DragView(context, null, src)
        overlay.addView(dv, FrameLayout.LayoutParams(src.width, src.height))
        dv.x = loc[0].toFloat(); dv.y = loc[1].toFloat()
        dv.elevation = dp.dp(8f)
        dragView = dv
        src.alpha = 0f
        dv.animate().scaleX(1.04f).scaleY(1.04f).setDuration(150).setInterpolator(Easing.BASIC).start()
        val rb = RemoveBar(context, dp)
        overlay.addView(rb, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, rb.barHeight))
        rb.alpha = 0f
        rb.animate().alpha(1f).setDuration(150).start()
        removeBar = rb
        animateBackground(1f, 150)
        updateGuides(currentTarget())
    }

    /**
     * Where the dragged widget lands: the cell under its top-left, making room there if needed, else the nearest
     * free spot for its span.
     */
    private fun widgetTarget(w: HomeItem.Widget): Target? {
        val dp = dpProvider()
        val m = model() ?: return null
        val page = workspace().currentPage
        val fx = (touchX - grabX - dp.workspaceGrid.left) / dp.cellWidth
        val fy = (touchY - grabY - dp.workspaceGrid.top) / dp.cellHeight
        val tx = kotlin.math.round(fx).toInt().coerceIn(0, (dp.homeCols - w.sx).coerceAtLeast(0))
        val ty = kotlin.math.round(fy).toInt().coerceIn(0, (dp.homeRows - w.sy).coerceAtLeast(0))
        if (!m.isOccupied(page, tx, ty, w.sx, w.sy, w)) return Target(Container.WORKSPACE, page, tx, ty, null)
        planReorder(m, page, tx, ty, w.sx, w.sy, w)?.let { return Target(Container.WORKSPACE, page, tx, ty, null, it) }
        var best: Pair<Int, Int>? = null
        var bd = Float.MAX_VALUE
        for (yy in 0..dp.homeRows - w.sy) for (xx in 0..dp.homeCols - w.sx) {
            if (m.isOccupied(page, xx, yy, w.sx, w.sy, w)) continue
            val d = hypot(xx - fx, yy - fy)
            if (d < bd) { bd = d; best = xx to yy }
        }
        return best?.let { Target(Container.WORKSPACE, page, it.first, it.second, null) }
    }

    private fun dropWidget(m: WorkspaceModel, w: HomeItem.Widget, dv: DragView, removing: Boolean) {
        val dp = dpProvider()
        val src = widgetSource
        if (removing) {
            endReorder(revert = true)
            m.items.remove(w); deleteWidget(w); m.save()
            dv.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(200).withEndAction { overlay.removeView(dv) }.start()
            dragView = null; widgetSource = null
            rebind(); cleanup(); return
        }
        val t = widgetTarget(w)
        if (t != null) {
            w.screen = t.screen; w.x = t.x; w.y = t.y
            if (t.pushes.isNotEmpty()) showPushes(t.pushes, null)      // in case the drop beat the preview delay
            for ((it, p) in t.pushes) { it.x = p.first; it.y = p.second }
            m.save()
        }
        endReorder(revert = false)
        // fly into the (new) cell, then rebind; the source keeps the touch stream until then
        val tx = dp.workspaceGrid.left + w.x * dp.cellWidth.toFloat()
        val ty = dp.workspaceGrid.top + w.y * dp.cellHeight.toFloat()
        val samePage = w.screen == workspace().currentPage
        dv.animate().x(tx).y(ty).scaleX(1f).scaleY(1f).alpha(if (samePage) 1f else 0f).setDuration(250).setInterpolator(Easing.BASIC)
            .withEndAction {
                rebind()
                src?.alpha = 1f
                overlay.removeView(dv)
            }.start()
        dragView = null; widgetSource = null
        cleanup()
    }

    private fun snapshot(v: FolderIconView): Bitmap {
        val b = Bitmap.createBitmap(v.iconSizePx(), v.iconHeightPx(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        // Hide the label first: without it the icon re-centres lower in the cell, so iconTop() changes.
        val label = v.showLabel
        v.showLabel = false
        c.translate(-v.iconLeft().toFloat(), -v.iconTop().toFloat())
        v.draw(c)
        v.showLabel = label
        return b
    }

    private fun animateBackground(to: Float, duration: Long) {
        bgAnim?.cancel()
        val from = if (background.current == BackgroundController.Kind.DRAG) background.currentProgress else 0f
        bgAnim = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            addUpdateListener { background.set(BackgroundController.Kind.DRAG, it.animatedValue as Float) }
            start()
        }
    }

    /** The full-screen folder's blurred, dimmed background hands over to the drag's ([toDrag]) or to none. */
    private fun leaveFolderBackground(full: Boolean, toDrag: Boolean, duration: Long) {
        if (!full) { animateBackground(if (toDrag) 1f else 0f, duration); return }
        bgAnim?.cancel()
        val to = if (toDrag) BackgroundController.Kind.DRAG else BackgroundController.Kind.NONE
        bgAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener { background.blend(BackgroundController.Kind.FOLDER, to, it.animatedValue as Float) }
            start()
        }
    }

    /** The drag is off the open folder: it closes and the drag goes on over Home. */
    private fun leaveFolder() {
        cancelFolderExit()
        val fd = folderDrag ?: return
        folderDrag = null
        addingTo = null
        fd.exit()
        leaveFolderBackground(fd.fullScreen, toDrag = true, 200)
    }

    private fun cancelFolderExit() {
        folderExitPending?.let { main.removeCallbacks(it) }
        folderExitPending = null
    }

    private fun position() {
        val dv = dragView ?: return
        if (widgetSource != null) { dv.x = touchX - grabX; dv.y = touchY - grabY; return }
        val tx = touchX - dragSize / 2f
        val ty = touchY - dragHeight / 2f
        val t = ((android.os.SystemClock.uptimeMillis() - pickupStart) / 317f).coerceIn(0f, 1f)
        val e = Easing.DRAG_TRANSITION.getInterpolation(t)
        dv.x = startX + (tx - startX) * e
        dv.y = startY + (ty - startY) * e
    }

    fun move(x: Float, y: Float) {
        if (!isDragging) return
        touchX = x; touchY = y
        position()
        val dp = dpProvider()
        removeBar?.setActive(y < (removeBar?.barHeight ?: 0))
        folderDrag?.let { fd ->
            hideGuides()
            if (removeBar?.active == true || fd.isInside(x, y)) {
                cancelFolderExit()
                if (removeBar?.active != true) fd.dragOver(x, y)
                return
            }
            if (folderExitPending == null) {
                folderExitPending = Runnable { folderExitPending = null; leaveFolder(); if (isDragging) move(touchX, touchY) }
                    .also { main.postDelayed(it, FOLDER_EXIT_DELAY) }
            }
            return
        }
        // edge page flip
        val edge = dp.widthPx * 0.06f
        val dir = when { x < edge -> -1; x > dp.widthPx - edge -> 1; else -> 0 }
        if (dir != 0 && y < dp.hotseatRect.top) {
            if (pageFlipPending == null) {
                val r = Runnable {
                    pageFlipPending = null
                    val ws = workspace()
                    val target = ws.currentPage + dir
                    val m = model() ?: return@Runnable
                    if (target >= ws.pageCount && dir > 0) {
                        m.pageCount = ws.pageCount + 1
                        val cl = CellLayout(context, dp.homeCols, dp.homeRows)
                        cl.setPadding(dp.workspaceGrid.left, dp.workspaceGrid.top, dp.widthPx - dp.workspaceGrid.right, dp.heightPx - dp.workspaceGrid.bottom)
                        ws.addView(cl)
                    }
                    if (target in 0 until ws.pageCount) ws.snapToPage(target)
                    if (isDragging && folderDrag == null) ws.post { if (isDragging && folderDrag == null) updateGuides(currentTarget()) }
                }
                pageFlipPending = r
                main.postDelayed(r, 500)
            }
        } else {
            pageFlipPending?.let { main.removeCallbacks(it) }
            pageFlipPending = null
        }
        val t = if (removeBar?.active == true) null else currentTarget()
        if (widgetSource == null) updateHover(t)
        updateReorder(t)
        if (folderDrag == null) updateGuides(t)                          // (a spring-open may have just taken over)
    }

    private fun currentTarget(): Target? =
        if (widgetSource != null) (item as? HomeItem.Widget)?.let { widgetTarget(it) } else dropTarget()

    /**
     * Folder hover (IconViewImpl.setFolderBackground, 100 ms): over an app a folder plate grows out from behind it;
     * over a folder its container grows. The dragged icon shrinks a little, as if going in.
     */
    private fun updateHover(target: Target?) {
        val v = if (target?.mergeWith != null) findViewFor(target.mergeWith) else null
        if (v === hoverTarget) return
        hoverTarget?.let { animateFolderBg(it, 1f) }
        v?.let { animateFolderBg(it, hoverScale(it)) }
        hoverTarget = v
        dragView?.animateHover(if (v != null) OVER_FOLDER_DRAG_SCALE else 1f)
        cancelSpring()
        val folder = target?.mergeWith as? HomeItem.Folder
        if (folder != null && v is FolderIconView) {
            springPending = Runnable { springPending = null; springOpen(folder, v) }.also { main.postDelayed(it, SPRING_OPEN_DELAY) }
        }
    }

    private fun cancelSpring() {
        springPending?.let { main.removeCallbacks(it) }
        springPending = null
    }

    /** The app rested on [folder]: it opens around the drag, which goes on inside it to pick the app's place. */
    private fun springOpen(folder: HomeItem.Folder, v: FolderIconView) {
        if (!isDragging || folderDrag != null || hoverTarget !== v) return
        val m = model() ?: return
        val a = app ?: source?.appInfo ?: return
        // from the Apps screen: the Home item it'll become (added to the model when it's placed)
        val dragged = item as? HomeItem.App ?: if (item == null) HomeItem.App(m.newId(), a.key, Container.WORKSPACE, 0, 0, 0) else return
        bgAnim?.cancel()                                               // (the folder's open takes the background over)
        val fd = openFolderForDrop(v, folder, a, dragged) ?: return
        item = dragged
        folderDrag = fd
        hideGuides()
        addingTo = folder
        endReorder(revert = true)
        v.folderBgScale = 1f
        folderBgAnims.remove(v)?.cancel()
        hoverTarget = null
        dragView?.animateHover(1f)
        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun hoverScale(v: IconView): Float = when (v) {
        is LargeFolderIconView -> 1f + dpProvider().dp(8f) / v.iconSizePx()   // large_folder_drag_over_scale_anim_offset 4dp a side
        is FolderIconView -> ADD_TO_FOLDER_SCALE
        else -> MAKE_FOLDER_SCALE
    }

    private fun animateFolderBg(v: IconView, to: Float) {
        folderBgAnims.remove(v)?.cancel()
        if (v.folderBgScale == to) return
        folderBgAnims[v] = ValueAnimator.ofFloat(v.folderBgScale, to).apply {
            duration = 100; interpolator = Easing.BASIC
            addUpdateListener { v.folderBgScale = it.animatedValue as Float }
            start()
        }
    }

    private class Target(
        val container: Container, val screen: Int, val x: Int, val y: Int, val mergeWith: HomeItem?,
        /** Items that move aside to make room: item → new top-left cell. */
        val pushes: Map<HomeItem, Pair<Int, Int>> = emptyMap(),
    )

    /**
     * Make room for an [sx]×[sy] item at ([x], [y]) on [page]: everything it overlaps moves to the nearest free
     * spot that fits it (largest items first, so widgets get first pick). Null if something can't fit anywhere.
     */
    private fun planReorder(m: WorkspaceModel, page: Int, x: Int, y: Int, sx: Int, sy: Int, dragged: HomeItem?): Map<HomeItem, Pair<Int, Int>>? {
        val dp = dpProvider()
        val cols = dp.homeCols; val rows = dp.homeRows
        if (x < 0 || y < 0 || x + sx > cols || y + sy > rows) return null
        val others = m.items.filter { it !== dragged && it.container == Container.WORKSPACE && it.screen == page }
        fun overlaps(i: HomeItem) = x < i.x + i.spanX && x + sx > i.x && y < i.y + i.spanY && y + sy > i.y
        val blocking = others.filter { overlaps(it) }
        if (blocking.isEmpty()) return emptyMap()
        val grid = Array(rows) { BooleanArray(cols) }
        fun mark(ax: Int, ay: Int, asx: Int, asy: Int) {
            for (yy in ay.coerceAtLeast(0) until minOf(rows, ay + asy)) for (xx in ax.coerceAtLeast(0) until minOf(cols, ax + asx)) grid[yy][xx] = true
        }
        fun free(ax: Int, ay: Int, asx: Int, asy: Int): Boolean {
            for (yy in ay until ay + asy) for (xx in ax until ax + asx) if (grid[yy][xx]) return false
            return true
        }
        others.filter { it !in blocking }.forEach { mark(it.x, it.y, it.spanX, it.spanY) }
        mark(x, y, sx, sy)
        val moves = LinkedHashMap<HomeItem, Pair<Int, Int>>()
        val order = blocking.sortedWith(compareByDescending<HomeItem> { it.spanX * it.spanY }
            .thenBy { hypot((it.x - x).toFloat(), (it.y - y).toFloat()) })
        for (b in order) {
            var best: Pair<Int, Int>? = null
            var bd = Float.MAX_VALUE
            for (yy in 0..rows - b.spanY) for (xx in 0..cols - b.spanX) {
                if (!free(xx, yy, b.spanX, b.spanY)) continue
                val d = hypot((xx - b.x).toFloat(), (yy - b.y).toFloat())
                if (d < bd) { bd = d; best = xx to yy }
            }
            val p = best ?: return null
            moves[b] = p
            mark(p.first, p.second, b.spanX, b.spanY)
        }
        return moves
    }

    /** Preview the make-room moves after [REORDER_DELAY] of hovering; leaving the spot puts things back. */
    private fun updateReorder(t: Target?) {
        val key = t?.takeIf { it.pushes.isNotEmpty() }?.let { "${it.screen}:${it.x}:${it.y}" }
        if (key == shownKey) { cancelPendingReorder(); return }
        if (key != null && key == pendingKey) return
        cancelPendingReorder()
        if (key == null) { showPushes(emptyMap(), null); return }
        val pushes = t.pushes
        val r = Runnable { reorderPending = null; pendingKey = null; showPushes(pushes, key) }
        reorderPending = r; pendingKey = key
        main.postDelayed(r, REORDER_DELAY)
    }

    private fun cancelPendingReorder() {
        reorderPending?.let { main.removeCallbacks(it) }
        reorderPending = null; pendingKey = null
    }

    private fun showPushes(pushes: Map<HomeItem, Pair<Int, Int>>, key: String?) {
        val dp = dpProvider()
        for (it in shownPushes.keys) if (it !in pushes) {
            findItemView(it)?.animate()?.translationX(0f)?.translationY(0f)?.setDuration(150)?.setInterpolator(Easing.BASIC)?.start()
        }
        for ((it, p) in pushes) {
            findItemView(it)?.animate()?.translationX((p.first - it.x) * dp.cellWidth.toFloat())
                ?.translationY((p.second - it.y) * dp.cellHeight.toFloat())?.setDuration(200)?.setInterpolator(Easing.BASIC)?.start()
        }
        shownPushes = pushes; shownKey = key
        if (isDragging && folderDrag == null) updateGuides(lastTarget)    // (the free cells changed)
    }

    /** Drag ended: the preview is either committed by the rebind or must be put back. */
    private fun endReorder(revert: Boolean) {
        cancelPendingReorder()
        if (revert) showPushes(emptyMap(), null)
        shownPushes = emptyMap(); shownKey = null
    }

    /** Only an app goes into a folder (a folder dragged onto an icon makes room instead). */
    private fun draggingApp() = item is HomeItem.App || (item == null && app != null)

    private val hitRect = Rect()

    /**
     * Whether a drop at (x, y) goes into [target]: within 0.55 icon of an app's centre (Launcher3's folder creation
     * radius), anywhere on a folder's container and a little around it. In the dock only across counts.
     */
    private fun mergesWith(target: HomeItem, x: Float, y: Float, dockBand: Boolean = false): Boolean {
        val v = findViewFor(target) ?: return false
        v.iconBoundsOnScreen(hitRect)
        // (the hover plate doesn't scale the view, so these bounds hold still while it shows)
        val dx = abs(hitRect.exactCenterX() - x)
        val dy = if (dockBand) 0f else abs(hitRect.exactCenterY() - y)
        if (target is HomeItem.Folder) {
            val reach = hitRect.width() / 2f + dpProvider().iconSizePx * 0.1f
            return dx < reach && dy < reach
        }
        return hypot(dx, dy) < dpProvider().iconSizePx * 0.55f
    }

    private fun dropTarget(): Target? {
        val dp = dpProvider()
        val m = model() ?: return null
        val x = touchX; val y = touchY
        if (y >= dp.hotseatRect.top - dp.dp(8f)) {
            val count = m.items.count { it.container == Container.HOTSEAT && it !== item }
            if (count >= 7) return null
            val rel = ((x - dp.hotseatRect.left) / dp.hotseatRect.width()).coerceIn(0f, 0.999f)
            val idx = (rel * (count + 1)).toInt()
            val over = if (draggingApp()) m.items.firstOrNull { it.container == Container.HOTSEAT && it !== item &&
                (it is HomeItem.App || it is HomeItem.Folder) && mergesWith(it, x, y, dockBand = true) } else null
            return Target(Container.HOTSEAT, 0, idx, 0, over)
        }
        val ws = workspace()
        val cellW = dp.cellWidth.toFloat(); val cellH = dp.cellHeight.toFloat()
        val cx = ((x - dp.workspaceGrid.left) / cellW).toInt().coerceIn(0, dp.homeCols - 1)
        val cy = ((y - dp.workspaceGrid.top) / cellH).toInt().coerceIn(0, dp.homeRows - 1)
        val page = ws.currentPage
        val sx = (item?.spanX ?: 1).coerceAtMost(dp.homeCols)
        val sy = (item?.spanY ?: 1).coerceAtMost(dp.homeRows)
        val occupant = m.items.firstOrNull { it !== item && it.container == Container.WORKSPACE && it.screen == page &&
            cx >= it.x && cx < it.x + it.spanX && cy >= it.y && cy < it.y + it.spanY }
        // over an icon → make / add to a folder; anywhere else in its cell → push it aside (not one already moving)
        if (occupant != null && occupant !is HomeItem.Widget && draggingApp() && occupant !in shownPushes && mergesWith(occupant, x, y)) {
            return Target(Container.WORKSPACE, page, cx, cy, occupant)
        }
        val tx = cx.coerceAtMost(dp.homeCols - sx)
        val ty = cy.coerceAtMost(dp.homeRows - sy)
        if (!m.isOccupied(page, tx, ty, sx, sy, item)) return Target(Container.WORKSPACE, page, tx, ty, null)
        planReorder(m, page, tx, ty, sx, sy, item)?.let { return Target(Container.WORKSPACE, page, tx, ty, null, it) }
        // no room to rearrange: nearest free spot
        var best: Pair<Int, Int>? = null
        var bd = Float.MAX_VALUE
        for (yy in 0..dp.homeRows - sy) for (xx in 0..dp.homeCols - sx) {
            if (m.isOccupied(page, xx, yy, sx, sy, item)) continue
            val d = hypot((xx - cx).toFloat(), (yy - cy).toFloat())
            if (d < bd) { bd = d; best = xx to yy }
        }
        return best?.let { Target(Container.WORKSPACE, page, it.first, it.second, null) }
    }

    fun drop() {
        if (!isDragging) return
        isDragging = false
        fadeGuides()
        pickupAnim?.cancel(); pickupAnim = null                         // (it would fight the landing's scale)
        pageFlipPending?.let { main.removeCallbacks(it) }; pageFlipPending = null
        cancelFolderExit()
        cancelSpring()
        // the plate behind the hovered icon: carried on by the folder it becomes, otherwise it shrinks back
        val hovered = hoverTarget
        val hoverBg = hovered?.folderBgScale ?: 1f
        hovered?.let { animateFolderBg(it, 1f) }; hoverTarget = null
        val m = model()
        val dv = dragView
        val removing = removeBar?.active == true && item != null
        removeBar?.let { rb -> rb.animate().alpha(0f).setDuration(150).withEndAction { overlay.removeView(rb) }.start() }
        removeBar = null
        val fd = folderDrag
        folderDrag = null
        val inFolder = fd != null && !removing && fd.isInside(touchX, touchY)
        if (fd == null) animateBackground(0f, 250)
        else if (!inFolder) { fd.exit(); addingTo = null; leaveFolderBackground(fd.fullScreen, toDrag = false, 250) }
        else if (background.current == BackgroundController.Kind.DRAG) animateBackground(0f, 250)   // a pop-up opened by the drag

        if (m == null || dv == null) { fd?.cancel(); cleanup(); return }
        if (inFolder) { dropInFolder(fd!!, dv); return }
        (item as? HomeItem.Widget)?.let { if (widgetSource != null) { dropWidget(m, it, dv, removing); return } }
        val fromFolder = (item as? HomeItem.App)?.takeIf { it.container == Container.FOLDER }?.folderId ?: -1L
        if (removing) {
            endReorder(revert = true)
            m.items.remove(item)
            if (item is HomeItem.Folder) m.items.removeAll { it is HomeItem.App && it.folderId == item!!.id }
            if (fromFolder >= 0) dissolveIfNeeded(m, fromFolder)
            m.save()
            if (fromFolder < 0) onRemoved?.invoke(item!!)
            dv.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(200).withEndAction { overlay.removeView(dv) }.start()
            dragView = null
            rebind(); cleanup(); return
        }
        val t = dropTarget()
        endReorder(revert = t == null || t.pushes.isEmpty())
        // an app dropped onto: its icon shrinks into the new folder alongside the dropped one
        val onto = (t?.mergeWith as? HomeItem.App)?.let { findViewFor(it) }
        val made = onto?.displayedIcon()?.let { b -> b to Rect().also { onto.iconBoundsOnScreen(it) } }
        val placed: HomeItem? = if (t == null) item else place(m, t)
        val dropped = lastPlaced
        lastPlaced = null
        if (fromFolder >= 0) dissolveIfNeeded(m, fromFolder)
        m.save()
        rebind()
        if (t?.mergeWith != null && placed is HomeItem.Folder) {
            val fv = findViewFor(placed) as? FolderIconView
            val rank = m.appsInFolder(placed.id).indexOfFirst { it === dropped }
            if (fv != null && rank >= 0) {
                intoFolder(fv, dv, rank, made, if (hovered != null && hovered.item?.id == t.mergeWith.id) hoverBg else 1f)
                return
            }
        }
        val target = placed?.let { findViewFor(it) }
        if (target == null) {
            dv.animate().alpha(0f).setDuration(150).withEndAction { overlay.removeView(dv) }.start()
            dragView = null
            cleanup(); return
        }
        target.iconVisible = false
        target.post {
            val r = Rect(); target.iconBoundsOnScreen(r)
            val sx = dv.x; val sy = dv.y; val ss = dv.scaleX * dv.hover
            dv.stopHover(); dv.hover = 1f
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 250; interpolator = Easing.BASIC
                addUpdateListener {
                    val f = it.animatedFraction
                    dv.x = sx + (r.left - sx) * f; dv.y = sy + (r.top - sy) * f
                    val s = ss + (1f - ss) * f; dv.scaleX = s; dv.scaleY = s
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        target.iconVisible = true
                        overlay.removeView(dv)
                    }
                })
                start()
            }
            dragView = null
            cleanup()
        }
    }

    /** Dropped in the open folder it came from: the apps keep the order they made room in, and it lands in its gap. */
    private fun dropInFolder(fd: FolderDrag, dv: DragView) {
        endReorder(revert = true)
        val src = fd.icon
        val origin = source                                            // where the drag began (outside, when adding)
        val dragged = item as? HomeItem.App
        val into = addingTo
        addingTo = null
        dragView = null
        source = null; item = null; app = null                         // (src stays hidden until the drag view lands)
        fd.drop { r, rank ->
            val m = model()
            if (fd.adding && into != null && dragged != null && m != null) addToFolder(m, dragged, into, rank)
            origin?.takeIf { it !== src }?.let { it.iconVisible = true; it.labelAlpha = 1f }
            if (r == null) {
                dv.animate().alpha(0f).setDuration(150).withEndAction { overlay.removeView(dv) }.start()
                src?.let { it.iconVisible = true; it.labelAlpha = 1f }
                return@drop
            }
            fly(listOf(Flight(dv, dv.scaleX * dv.hover, RectF(r))), 250, Easing.BASIC) {
                src?.let { it.iconVisible = true; it.labelAlpha = 1f }
            }
        }
    }

    /** [dragged] goes into [folder] at [rank] (chosen in the open folder), the rest after it; its old folder may dissolve. */
    private fun addToFolder(m: WorkspaceModel, dragged: HomeItem.App, folder: HomeItem.Folder, rank: Int) {
        val fromFolder = if (dragged.container == Container.FOLDER) dragged.folderId else -1L
        if (m.items.none { it === dragged }) m.items += dragged
        val apps = m.appsInFolder(folder.id).filter { it !== dragged }.toMutableList()
        apps.add(rank.coerceIn(0, apps.size), dragged)
        dragged.container = Container.FOLDER; dragged.folderId = folder.id
        apps.forEachIndexed { i, a -> a.rank = i }
        if (fromFolder >= 0 && fromFolder != folder.id) dissolveIfNeeded(m, fromFolder)
        m.save()
        rebind()
    }

    /**
     * Dropped onto an app (a new folder) or onto a folder: the apps shrink into their places in the folder icon's
     * preview while its plate settles from the hover size (IconViewImpl.getFolderTransformAnim: 350 ms, decelerate
     * 2). [made]: the app dropped onto, as it was shown, when this made the folder.
     */
    private fun intoFolder(folder: FolderIconView, dv: DragView, rank: Int, made: Pair<Bitmap, Rect>?, plateFrom: Float) {
        dragView = null
        cleanup()
        val large = folder as? LargeFolderIconView
        // the preview slot an app at [r] shows in, -1 if none (large folders show 4 apps per page)
        fun slotOf(r: Int) = large?.slotFor(r) ?: folder.slotOfRank(r)
        fun ownSlot(r: Int) = slotOf(r) >= 0
        folder.hiddenPreviews = listOfNotNull(rank.takeIf { ownSlot(it) }?.let { slotOf(it) }, if (made != null) 0 else null).toSet()
        folder.folderBgScale = plateFrom
        if (made != null) folder.labelAlpha = 0f
        folder.post {
            val ir = Rect(); folder.iconBoundsOnScreen(ir)
            val k = ir.width() / folder.iconSizePx().toFloat()
            val slot = RectF()
            fun goal(r: Int): RectF {
                val s = slotOf(r)
                if (s < 0) return RectF(ir.exactCenterX(), ir.exactCenterY(), ir.exactCenterX(), ir.exactCenterY())
                folder.childRect(s, slot)
                return RectF(ir.left + slot.left * k, ir.top + slot.top * k, ir.left + slot.right * k, ir.top + slot.bottom * k)
            }
            val flights = arrayListOf(Flight(dv, dv.scaleX * dv.hover, goal(rank)))
            made?.let { (bmp, r) ->
                val mv = DragView(context, bmp)
                overlay.addView(mv, overlay.indexOfChild(dv).coerceAtLeast(0), FrameLayout.LayoutParams(r.width(), r.height()))
                mv.x = r.left.toFloat(); mv.y = r.top.toFloat()
                flights += Flight(mv, 1f, goal(0))
            }
            val elevation = dv.elevation
            fly(flights, INTO_FOLDER_DURATION, Easing.DECEL_2, { e ->
                dv.elevation = elevation * (1f - e)
                folder.folderBgScale = plateFrom + (1f - plateFrom) * e
                if (made != null) folder.labelAlpha = e
            }) {
                folder.hiddenPreviews = emptySet()
                folder.folderBgScale = 1f
                folder.labelAlpha = 1f
            }
        }
    }

    /** A drag view on its way from where it is (drawn at [fromScale]) to [to] (window coords; empty: it fades out there). */
    private class Flight(val view: DragView, val fromScale: Float, val to: RectF) {
        // (from its layout params: one just added hasn't been laid out yet)
        val w = view.layoutParams.width.toFloat()
        val h = view.layoutParams.height.toFloat()
        val fromX = view.x + w / 2f
        val fromY = view.y + h / 2f
    }

    private fun fly(flights: List<Flight>, duration: Long, curve: android.animation.TimeInterpolator,
                    each: (Float) -> Unit = {}, done: () -> Unit) {
        for (f in flights) { f.view.stopHover(); f.view.hover = 1f; f.view.animate().cancel() }
        ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration; interpolator = curve
            addUpdateListener {
                val e = it.animatedValue as Float
                for (f in flights) {
                    val v = f.view
                    val s = f.fromScale + (f.to.width() / f.w - f.fromScale) * e
                    v.scaleX = s; v.scaleY = s
                    v.x = f.fromX + (f.to.centerX() - f.fromX) * e - f.w / 2f
                    v.y = f.fromY + (f.to.centerY() - f.fromY) * e - f.h / 2f
                    if (f.to.isEmpty) v.alpha = 1f - e
                }
                each(e)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    flights.forEach { overlay.removeView(it.view) }
                    done()
                }
            })
            start()
        }
    }

    /** One UI: a folder left with a single app is replaced by that app; an empty folder disappears. */
    private fun dissolveIfNeeded(m: WorkspaceModel, folderId: Long) {
        val folder = m.items.firstOrNull { it is HomeItem.Folder && it.id == folderId } as? HomeItem.Folder ?: return
        val rest = m.appsInFolder(folderId)
        if (rest.size > 1) { rest.forEachIndexed { i, a -> a.rank = i }; return }
        m.items.remove(folder)
        rest.firstOrNull()?.let { a ->
            a.container = folder.container; a.screen = folder.screen; a.x = folder.x; a.y = folder.y; a.folderId = -1
        }
    }

    /** What the last [place] put down (an app from the Apps screen is a new item). */
    private var lastPlaced: HomeItem? = null

    private fun place(m: WorkspaceModel, t: Target): HomeItem? {
        val dragged: HomeItem = item ?: app?.let { HomeItem.App(m.newId(), it.key, Container.WORKSPACE, 0, 0, 0) } ?: return null
        if (m.items.none { it === dragged }) m.items += dragged       // (from the Apps screen, after a folder opened under it)
        lastPlaced = dragged
        val merge = t.mergeWith
        if (merge != null && dragged is HomeItem.App) {
            if (merge is HomeItem.Folder) {
                dragged.container = Container.FOLDER; dragged.folderId = merge.id
                dragged.rank = m.appsInFolder(merge.id).size
                return merge
            }
            if (merge is HomeItem.App) {
                val folder = HomeItem.Folder(m.newId(), context.getString(com.oneuiclone.home.R.string.folder_default),
                    merge.container, merge.screen, merge.x, merge.y)
                m.items += folder
                merge.container = Container.FOLDER; merge.folderId = folder.id; merge.rank = 0
                dragged.container = Container.FOLDER; dragged.folderId = folder.id; dragged.rank = 1
                return folder
            }
        }
        if (t.container == Container.HOTSEAT) {
            val dock = m.items.filter { it.container == Container.HOTSEAT && it !== dragged }.sortedBy { it.x }.toMutableList()
            dock.add(t.x.coerceIn(0, dock.size), dragged)
            dragged.container = Container.HOTSEAT
            dock.forEachIndexed { i, it -> it.x = i; it.y = 0 }
            return dragged
        }
        dragged.container = Container.WORKSPACE
        dragged.screen = t.screen; dragged.x = t.x; dragged.y = t.y
        if (dragged is HomeItem.App) dragged.folderId = -1
        for ((it, p) in t.pushes) { it.x = p.first; it.y = p.second }
        return dragged
    }

    fun cancel() {
        if (!isDragging) return
        isDragging = false
        fadeGuides()
        pickupAnim?.cancel(); pickupAnim = null
        endReorder(revert = true)
        cancelFolderExit()
        cancelSpring()
        addingTo = null
        pageFlipPending?.let { main.removeCallbacks(it) }; pageFlipPending = null
        hoverTarget?.let { animateFolderBg(it, 1f) }; hoverTarget = null
        dragView?.let { overlay.removeView(it) }; dragView = null
        widgetSource?.alpha = 1f; widgetSource = null
        removeBar?.let { overlay.removeView(it) }; removeBar = null
        // still in the folder: it stays open, with its background
        folderDrag?.cancel() ?: animateBackground(0f, 150)
        folderDrag = null
        cleanup()
    }

    // ---- drag guides (design opinion 9) ----

    private val guideRect = RectF()
    private var guideDots = FloatArray(0)
    private var dotsKey = ""

    /** The guides view, added under the drag view when first needed. */
    private fun guidesView(): DragGuides {
        guides?.let { return it }
        val g = DragGuides(context, dpProvider())
        val at = dragView?.let { overlay.indexOfChild(it) }?.takeIf { it >= 0 } ?: overlay.childCount
        overlay.addView(g, at, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        guides = g
        return g
    }

    private fun hideGuides() {
        val g = guides ?: return
        g.dots(null, guideDots, 0)
        g.outline(null, null, DragGuides.Kind.ICON)
        dotsKey = ""
    }

    /** Dropped or cancelled: the guides fade out (in their own time, the rebind doesn't cut them off). */
    private fun fadeGuides() {
        val g = guides ?: return
        guides = null; lastTarget = null; dotsKey = ""
        g.onGone = { overlay.removeView(g) }
        g.fadeOut()
    }

    /**
     * Dots on the free cells of the page under the drag, and the outline on [t]'s spot: a Home cell (icon-sized, in
     * the icon's shape, where the icon sits in its cell), the dock slot it would take, or the widget's span. No
     * outline over a folder target (its plate shows instead), over Remove, or with nowhere to go.
     */
    private fun updateGuides(t: Target?) {
        lastTarget = t
        val m = model() ?: return
        val dp = dpProvider()
        val g = guidesView()
        val page = workspace().currentPage
        val cl = workspace().pageAt(page) as? CellLayout
        // dots: only recounted when the page or the cells taken change
        val key = "$page:${shownKey}:${m.items.size}:${cl?.width}"
        if (key != dotsKey && cl != null && cl.cellW > 0) {
            dotsKey = key
            val cols = cl.cols; val rows = cl.rows
            val taken = BooleanArray(cols * rows)
            for (it in m.items) {
                if (it === item || it.container != Container.WORKSPACE || it.screen != page) continue
                val p = shownPushes[it]
                val ix = p?.first ?: it.x; val iy = p?.second ?: it.y
                for (yy in iy.coerceAtLeast(0) until minOf(rows, iy + it.spanY)) for (xx in ix.coerceAtLeast(0) until minOf(cols, ix + it.spanX)) taken[yy * cols + xx] = true
            }
            if (guideDots.size < cols * rows * 2) guideDots = FloatArray(cols * rows * 2)
            val cy0 = iconCentreInCell(cl)
            var n = 0
            for (yy in 0 until rows) for (xx in 0 until cols) {
                if (taken[yy * cols + xx]) continue
                guideDots[n * 2] = cl.cellLeft(xx) + cl.cellW / 2f
                guideDots[n * 2 + 1] = cl.cellTop(yy) + cy0
                n++
            }
            g.dots(cl, guideDots, n)
        }
        // outline
        if (t == null || t.mergeWith != null) { g.outline(null, null, DragGuides.Kind.ICON); return }
        if (t.container == Container.HOTSEAT) {
            val hs = hotseat()
            val count = m.items.count { it.container == Container.HOTSEAT && it !== item }
            val slotW = (hs.width - hs.paddingLeft - hs.paddingRight) / (count + 1f)
            val cx = hs.paddingLeft + (t.x + 0.5f) * slotW
            val cy = hs.paddingTop + (hs.height - hs.paddingTop - hs.paddingBottom) / 2f
            val h = dp.iconSizePx / 2f
            guideRect.set(cx - h, cy - h, cx + h, cy + h)
            g.outline(hs, guideRect, DragGuides.Kind.ICON)
            return
        }
        val tl = workspace().pageAt(t.screen) as? CellLayout ?: run { g.outline(null, null, DragGuides.Kind.ICON); return }
        if (tl.cellW <= 0) return
        val w = item as? HomeItem.Widget
        if (w != null) {
            val pad = dp.widgetPaddingPx.toFloat()
            guideRect.set(tl.cellLeft(t.x) + pad, tl.cellTop(t.y) + pad, tl.cellLeft(t.x + w.sx) - pad, tl.cellTop(t.y + w.sy) - pad)
            g.outline(tl, guideRect, DragGuides.Kind.WIDGET)
            return
        }
        val sx = (item?.spanX ?: 1).coerceAtMost(tl.cols)
        val sy = (item?.spanY ?: 1).coerceAtMost(tl.rows)
        // a large folder's container: from one icon's left to the next column's icon's right
        val side = (sx - 1) * tl.cellW + dp.iconSizePx
        val labelH = tl.cellH - 2f * iconCentreInCell(tl)                       // (0 with labels off)
        val l = tl.cellLeft(t.x) + (sx * tl.cellW - side) / 2f
        val top = tl.cellTop(t.y) + (sy * tl.cellH - side - labelH) / 2f       // (IconView.iconTop)
        guideRect.set(l, top, l + side, top + side)
        g.outline(tl, guideRect, if (item is HomeItem.Folder) DragGuides.Kind.FOLDER else DragGuides.Kind.ICON)
    }

    /**
     * How far down its cell an app icon's centre sits on Home (its icon+gap+label block is centred, IconView.iconTop):
     * read off a 1×1 icon on [cl] or another page; the cell's middle if there's none.
     */
    private fun iconCentreInCell(cl: CellLayout): Float {
        fun sample(g: CellLayout): Float? {
            for (i in 0 until g.childCount) {
                val v = g.getChildAt(i) as? IconView ?: continue
                if (v is LargeFolderIconView || v.height <= 0) continue
                val lp = v.layoutParams as? CellLayout.LayoutParams ?: continue
                if (lp.spanX != 1 || lp.spanY != 1) continue
                return v.iconTop() + v.iconSizePx() / 2f
            }
            return null
        }
        sample(cl)?.let { return it }
        val ws = workspace()
        for (p in 0 until ws.pageCount) (ws.pageAt(p) as? CellLayout)?.takeIf { it !== cl }?.let { o -> sample(o)?.let { return it } }
        return cl.cellH / 2f
    }

    private fun cleanup() {
        source?.let { it.iconVisible = true; it.labelAlpha = 1f }
        source = null; item = null; app = null
    }

    /** Draws either an icon bitmap or, for widgets, the live source view (no software snapshot). */
    private class DragView(context: Context, private val bmp: Bitmap?, private val live: View? = null,
                           private val shape: Boolean = false) : View(context) {
        private val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        /** App icons are cut to the icon shape (the Pixel 10's white corners, 0.181). */
        private val shaped = if (shape) com.oneuiclone.home.icons.ShapedIcon() else null
        /** Extra scale over a folder target, on top of the pickup's (which animates scaleX / scaleY). */
        var hover = 1f
            set(v) { field = v; invalidate() }
        private var hoverAnim: ValueAnimator? = null
        fun stopHover() { hoverAnim?.cancel(); hoverAnim = null }
        fun animateHover(to: Float) {
            hoverAnim?.cancel()
            hoverAnim = ValueAnimator.ofFloat(hover, to).apply {
                duration = 100; interpolator = Easing.BASIC
                addUpdateListener { hover = it.animatedValue as Float }
                start()
            }
        }
        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.scale(hover, hover, width / 2f, height / 2f)
            if (live != null) live.draw(canvas)
            else bmp?.let { if (shaped != null) shaped.draw(canvas, it, Rect(0, 0, width, height), p) else canvas.drawBitmap(it, null, Rect(0, 0, width, height), p) }
            canvas.restore()
        }
    }

    /** CancelDropTargetBar: "Remove" target at the top (drop_target_height_ratio_in_page_top 6.9 %). */
    private class RemoveBar(context: Context, dp: DeviceProfile) : FrameLayout(context) {
        val barHeight = (dp.heightPx * 0.069f).toInt() + dp.insets.top
        var active = false; private set
        private val label = TextView(context).apply {
            text = "Remove"
            setTextColor(0xFFFAFAFA.toInt())
            textSize = 15f
            typeface = Fonts.ui(context, 600)
            gravity = Gravity.CENTER
            setShadowLayer(dp.dp(2f), 0f, 0f, 0x59000000)
        }
        init {
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { topMargin = dp.insets.top })
        }
        fun setActive(a: Boolean) {
            if (a == active) return
            active = a
            label.setTextColor(if (a) 0xFFFF6B6B.toInt() else 0xFFFAFAFA.toInt())
            label.animate().scaleX(if (a) 1.08f else 1f).scaleY(if (a) 1.08f else 1f).setDuration(150).start()
        }
    }
}
