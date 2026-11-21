package com.oneuiclone.home.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.oneuiclone.home.motion.Easing

/**
 * One UI page dots (PageIndicatorMarker): selected alpha 1.0, others 0.45 on light wallpapers /
 * 0.6 on dark ones, 500 ms SINE_IN_OUT_90 cross-fade.
 */
class PageIndicator(context: Context, private val slotPx: Float, private val dotPx: Float) : View(context) {

    var count = 1
        set(v) { field = v; alphas = FloatArray(v) { if (it == current) 1f else unselected }; invalidate() }
    var current = 0; private set
    var darkWallpaper = false
        set(v) { field = v; setPage(current, false) }
    /** Dark dots on bright wallpapers. */
    var darkDots = false
        set(v) { field = v; paint.color = if (v) 0xFF1D1D1F.toInt() else 0xFFFFFFFF.toInt(); invalidate() }

    /** Width of the Home screen search button, centred in this row; the dots sit [leadingGapPx] to its right. */
    var leadingPx = 0f
        set(v) { field = v; invalidate(); leadingMoved() }
    var leadingGapPx = 0f
        set(v) { field = v; invalidate() }
    /** 0..1: how far the dots stay beside the button (it fades out in page edit and the dots re-centre). */
    var leadingShown = 1f
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Receives the x where the leading button starts whenever it moves. */
    var onLeadingMoved: ((Float) -> Unit)? = null
    /** Multiplies every dot's alpha. */
    var opacity = 1f
        set(v) { field = v; invalidate() }

    private val dotsWidth get() = if (count > 1) slotPx * count else 0f
    fun leadingLeft() = (width - leadingPx) / 2f
    private fun dotsLeft(): Float {
        val centred = (width - dotsWidth) / 2f
        if (leadingPx <= 0f) return centred
        return centred + (leadingLeft() + leadingPx + leadingGapPx - centred) * leadingShown
    }
    private fun leadingMoved() { if (width > 0) onLeadingMoved?.invoke(leadingLeft()) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        leadingMoved()
    }

    private val unselected get() = if (darkWallpaper) 0.6f else 0.45f
    private var alphas = FloatArray(1) { 1f }
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        setShadowLayer(dotPx * 0.35f, 0f, 0f, 0x33000000)
    }

    fun setPage(page: Int, animate: Boolean = true) {
        val from = alphas.copyOf()
        current = page
        val to = FloatArray(count) { if (it == page) 1f else unselected }
        anim?.cancel()
        if (!animate) { alphas = to; invalidate(); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            interpolator = Easing.SINE_IN_OUT_90
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                alphas = FloatArray(count) { i -> Easing.lerp(from.getOrElse(i) { unselected }, to[i], t) }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (count <= 1) return
        val overrideDark = if (GlassPaint.overrideActive) GlassPaint.overrideDarkText else null
        val prevColor = paint.color
        if (overrideDark != null) paint.color = if (overrideDark) 0xFF1D1D1F.toInt() else 0xFFFFFFFF.toInt()
        var x = dotsLeft() + slotPx / 2f
        val cy = height / 2f
        for (i in 0 until count) {
            paint.alpha = (255 * opacity * alphas.getOrElse(i) { unselected }).toInt()
            canvas.drawCircle(x, cy, dotPx / 2f, paint)
            x += slotPx
        }
        if (overrideDark != null) paint.color = prevColor
    }
}
