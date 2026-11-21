package com.oneuiclone.home.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.icons.IconShape

/**
 * Drag guides (0.263, design opinion 9): while something is dragged on Home, faint dots mark the free cells on the
 * page and a glass outline of the landing spot springs from cell to cell. A view in the drag overlay, under the drag
 * view, so a rebind after the drop (new CellLayouts) doesn't cut its fade short. The dots and the outline's goal are
 * given in the coordinates of the CellLayout they belong to (a Home page or the dock) and follow it on screen (page
 * flips); once that layout is gone they stay where it last was.
 */
class DragGuides(context: Context, private val dp: DeviceProfile) : View(context) {

    enum class Kind { ICON, FOLDER, WIDGET }

    /** Called once everything has faded out after [fadeOut] (the owner removes the view). */
    var onGone: (() -> Unit)? = null

    // the dots: layout-local centres, mapped each draw
    private var dotsLayout: View? = null
    private var dotPts = FloatArray(0)
    private var dotCount = 0
    private var mappedPts = FloatArray(0)
    private val dotsToHere = Matrix()
    private var dotsAlpha = 0f
    private var dotsGoal = 0f

    // the outline: a goal in its layout's coordinates, the drawn rect in this view's (springing)
    private var outLayout: View? = null
    private val outLocal = RectF()
    private val outToHere = Matrix()
    private var kind = Kind.ICON
    private val goal = RectF()
    private val cur = RectF()
    private val vel = FloatArray(4)
    private var outAlpha = 0f
    private var outGoal = 0f
    private var lastFrame = 0L
    private var animating = false
    private var fading = false
    private val gs = FloatArray(4)
    private val cs = FloatArray(4)
    private var dotShadowAlpha = -1

    private val tmp = Matrix()
    private val here = Matrix()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
    private var shapePath: Path? = null
    private var shapeKey = ""
    private val tmpRect = RectF()

    /** The page or dock moved on screen (a page flip): redraw so the guides stay on it. */
    private val moved = ViewTreeObserver.OnPreDrawListener {
        if (trackChanged()) invalidate()
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(moved)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(moved)
        super.onDetachedFromWindow()
    }

    /** Dots at [centres] (x, y pairs, [count] of them, in [layout]'s coordinates); a null layout fades them out. */
    fun dots(layout: View?, centres: FloatArray, count: Int) {
        if (fading) return
        if (layout == null) { if (dotsGoal != 0f) { dotsGoal = 0f; kick() }; return }
        if (dotPts.size < count * 2) { dotPts = FloatArray(count * 2); mappedPts = FloatArray(count * 2) }
        System.arraycopy(centres, 0, dotPts, 0, count * 2)
        dotCount = count
        dotsLayout = layout
        layoutToHere(layout, dotsToHere)
        dotsGoal = 1f
        kick()
    }

    /** The landing outline at [local] in [layout]'s coordinates; a null layout fades it out where it is. */
    fun outline(layout: View?, local: RectF?, kind: Kind) {
        if (fading) return
        if (layout == null || local == null) { if (outGoal != 0f) { outGoal = 0f; kick() }; return }
        if (outGoal == 1f && layout === outLayout && kind == this.kind && local == outLocal) return   // (every move: same spot)
        outLayout = layout
        outLocal.set(local)
        this.kind = kind
        layoutToHere(layout, outToHere)
        outToHere.mapRect(goal, outLocal)
        if (outAlpha <= 0.01f) { cur.set(goal); vel.fill(0f) }               // (appearing: it starts on the spot)
        outGoal = 1f
        kick()
    }

    /** The drop (or a cancel): everything fades out, then [onGone]. */
    fun fadeOut() {
        fading = true
        dotsGoal = 0f; outGoal = 0f
        // the layouts may be replaced by the rebind: keep where they are now
        dotsLayout?.let { if (it.isAttachedToWindow) layoutToHere(it, dotsToHere) }
        outLayout?.let { if (it.isAttachedToWindow) layoutToHere(it, outToHere) }
        dotsLayout = null; outLayout = null
        kick()
    }

    private fun kick() {
        if (!animating) { animating = true; lastFrame = SystemClock.uptimeMillis() }
        postInvalidateOnAnimation()
    }

    /** [out] maps [layout]'s coordinates to this view's; false if it can't (not laid out yet). */
    private fun layoutToHere(layout: View, out: Matrix): Boolean {
        here.reset(); transformMatrixToGlobal(here)
        if (!here.invert(tmp)) return false
        out.reset(); layout.transformMatrixToGlobal(out)
        out.postConcat(tmp)
        return true
    }

    private val probe = Matrix()

    /** Re-reads the tracked layouts' places; true if one moved. */
    private fun trackChanged(): Boolean {
        var changed = false
        dotsLayout?.takeIf { it.isAttachedToWindow }?.let {
            if (layoutToHere(it, probe) && probe != dotsToHere) { dotsToHere.set(probe); changed = true }
        }
        outLayout?.takeIf { it.isAttachedToWindow }?.let {
            if (layoutToHere(it, probe) && probe != outToHere) {
                // the outline rides along with its page (no spring lag), then springs on from there
                outToHere.set(probe)
                val l = goal.left; val t = goal.top; val r = goal.right; val b = goal.bottom
                outToHere.mapRect(goal, outLocal)
                cur.set(cur.left + goal.left - l, cur.top + goal.top - t, cur.right + goal.right - r, cur.bottom + goal.bottom - b)
                changed = true
            }
        }
        return changed
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (animating) ((now - lastFrame) / 1000f).coerceIn(0f, 1f / 30f) else 0f
        lastFrame = now
        val busy = step(dt)

        if (dotsAlpha > 0f && dotCount > 0) {
            dotsToHere.mapPoints(mappedPts, 0, dotPts, 0, dotCount)
            val r = dp.dp(3.5f)
            haloPaint.alpha = (255 * 0.12f * dotsAlpha).toInt()
            dotPaint.alpha = (255 * 0.7f * dotsAlpha).toInt()
            // a soft drop under each dot (mockup 0 2px 6px); a shadow colour ignores the paint's alpha, so fade it too
            val sa = (0x40 * dotsAlpha).toInt()
            if (sa != dotShadowAlpha) { dotShadowAlpha = sa; dotPaint.setShadowLayer(dp.dp(2f), 0f, dp.dp(0.7f), sa shl 24) }
            for (i in 0 until dotCount) {
                val x = mappedPts[i * 2]; val y = mappedPts[i * 2 + 1]
                canvas.drawCircle(x, y, r + dp.dp(2.5f), haloPaint)
                canvas.drawCircle(x, y, r, dotPaint)
            }
        }
        if (outAlpha > 0f && cur.width() > 1f) drawOutline(canvas)
        if (busy) postInvalidateOnAnimation()
        else {
            animating = false
            if (fading && dotsAlpha <= 0f && outAlpha <= 0f) { fading = false; post { onGone?.invoke() } }
        }
    }

    /** Fades and the outline's spring, by [dt] seconds; true while anything still moves. */
    private fun step(dt: Float): Boolean {
        val fadeRate = if (fading) 1f / 0.22f else 1f / 0.2f
        dotsAlpha = approach(dotsAlpha, dotsGoal, fadeRate * dt)
        outAlpha = approach(outAlpha, outGoal, fadeRate * dt)
        var moving = false
        if (outGoal > 0f || outAlpha > 0f) {
            // a lively spring (ω 26/s, ζ 0.72): it snaps to the next cell with a hint of overshoot
            val w = 26f; val z = 0.72f
            val g = gs; g[0] = goal.left; g[1] = goal.top; g[2] = goal.right; g[3] = goal.bottom
            val c = cs; c[0] = cur.left; c[1] = cur.top; c[2] = cur.right; c[3] = cur.bottom
            var t = dt
            while (t > 0f) {
                val h = minOf(t, 1f / 240f); t -= h
                for (i in 0..3) {
                    val acc = -w * w * (c[i] - g[i]) - 2f * z * w * vel[i]
                    vel[i] += acc * h; c[i] += vel[i] * h
                }
            }
            for (i in 0..3) {
                if (kotlin.math.abs(c[i] - g[i]) < 0.3f && kotlin.math.abs(vel[i]) < 3f) { c[i] = g[i]; vel[i] = 0f } else moving = true
            }
            cur.set(c[0], c[1], c[2], c[3])
        }
        return moving || dotsAlpha != dotsGoal || outAlpha != outGoal
    }

    private fun approach(v: Float, to: Float, by: Float) = if (v < to) minOf(to, v + by) else maxOf(to, v - by)

    private fun drawOutline(canvas: Canvas) {
        // mockup: 4px white 90 % stroke round a 16 % white fill (≈1.5 dp at 1080 px)
        fillPaint.alpha = (255 * 0.16f * outAlpha).toInt()
        strokePaint.alpha = (255 * 0.85f * outAlpha).toInt()
        val sw = dp.dp(1.5f)
        if (kind == Kind.WIDGET) {
            val r = dp.dp(22f)
            canvas.drawRoundRect(cur, r, r, fillPaint)
            strokePaint.strokeWidth = sw
            tmpRect.set(cur); tmpRect.inset(sw / 2f, sw / 2f)
            canvas.drawRoundRect(tmpRect, r - sw / 2f, r - sw / 2f, strokePaint)
            return
        }
        // icons and folders: their own shape (as the folder hover plate is)
        val shape = if (kind == Kind.FOLDER) IconShape.container else IconShape.current
        if (shapePath == null || shapeKey != shape) { shapePath = IconShape.path(100f, shape); shapeKey = shape }
        val sx = cur.width() / 100f; val sy = cur.height() / 100f
        canvas.save()
        canvas.translate(cur.left, cur.top)
        canvas.scale(sx, sy)
        canvas.drawPath(shapePath!!, fillPaint)
        // the stroke sits just inside the edge, at the same width whatever the scale
        strokePaint.strokeWidth = sw / sx
        val k = 1f - sw / cur.width()
        canvas.scale(k, k, 50f, 50f)
        canvas.drawPath(shapePath!!, strokePaint)
        canvas.restore()
    }
}
