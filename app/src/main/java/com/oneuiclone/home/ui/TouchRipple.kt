package com.oneuiclone.home.ui

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.animation.PathInterpolator
import android.view.View
import com.oneuiclone.home.motion.Easing
import kotlin.math.hypot

/**
 * One UI 8.5 touch ripple (ViewRippleAnimationHelper / RippleAnimationParams, spec §11):
 * every sibling moves toward the touch point by d/50, starting 6 ms·(d/unit) later, over 650 ms with
 * the overshooting (0.807, 0.586, 0.266, 1.628) curve. STOP/REVERSE plays the same animator backwards.
 */
class TouchRipple(private val samsungPxScale: Float) {

    companion object {
        const val DURATION = 650L
        const val DISTANCE_UNIT = 50f
        const val DELAY_PER_UNIT = 6L

        /** Press bounce: the ripple curve only up to its peak (items furthest in, ~438 ms), then straight back. */
        private const val PEAK_FRACTION = 0.674f
        private const val PEAK_MS = DURATION * PEAK_FRACTION
        private const val BACK_MS = 300f
        const val BOUNCE_MS = 738L                       // PEAK_MS + BACK_MS
        private val PEAK = Easing.RIPPLE.getInterpolation(PEAK_FRACTION)          // ~1.19
        private val BACK: TimeInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f) // leaves the peak at rest speed
        private val BOUNCE = TimeInterpolator { f ->
            val t = f * BOUNCE_MS
            if (t < PEAK_MS) Easing.RIPPLE.getInterpolation(t / DURATION)
            else PEAK * (1f - BACK.getInterpolation(((t - PEAK_MS) / BACK_MS).coerceAtMost(1f)))
        }
    }

    private class Entry(val view: View, val anim: ValueAnimator, val baseX: Float, val baseY: Float)

    private val active = ArrayList<Entry>()

    /** @param tx,ty touch point in screen coordinates. */
    fun start(views: List<View>, tx: Float, ty: Float) = launch(views, tx, ty, DURATION, Easing.RIPPLE)

    /**
     * Press (0.279, press-ripple mockup E): the start of [start]'s ripple, up to where the items are furthest in, then
     * each goes right back in 300 ms by itself. Nothing waits for the long-press, hold or lift.
     */
    fun bounce(views: List<View>, tx: Float, ty: Float) = launch(views, tx, ty, BOUNCE_MS, BOUNCE)

    private fun launch(views: List<View>, tx: Float, ty: Float, length: Long, curve: TimeInterpolator) {
        reset()
        val loc = IntArray(2)
        for (v in views) {
            if (!v.isShown) continue
            v.getLocationOnScreen(loc)
            val cx = loc[0] + v.width * v.scaleX / 2f - v.translationX
            val cy = loc[1] + v.height * v.scaleY / 2f - v.translationY
            val d = hypot(cx - tx, cy - ty)
            if (d < 1f) continue
            val ux = (cx - tx) / d
            val uy = (cy - ty) / d
            val units = d / DISTANCE_UNIT                 // our px; displacement = d / 50 (density independent ratio)
            val dx = -units * ux
            val dy = -units * uy
            val baseX = v.translationX
            val baseY = v.translationY
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = length
                interpolator = curve
                startDelay = (DELAY_PER_UNIT * (d / (DISTANCE_UNIT * samsungPxScale))).toLong()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    v.translationX = baseX + dx * f
                    v.translationY = baseY + dy * f
                }
            }
            active += Entry(v, anim, baseX, baseY)
            anim.start()
        }
    }

    /** STOP / REVERSE: play back from wherever each item currently is. */
    fun reverse() {
        for (e in active) {
            if (e.anim.isStarted && e.anim.isRunning) {
                e.anim.reverse()
            } else if (e.anim.isStarted) {
                e.anim.reverse()
            } else {
                e.anim.cancel()
                e.view.translationX = e.baseX
                e.view.translationY = e.baseY
            }
        }
    }

    /**
     * A scroll took the touch over: no bounce plays while the list moves. Items that already moved ease straight
     * back to rest over [ms] (no overshoot); ones whose delay hadn't run out never move. [snap] goes straight back
     * (a folder about to open out of it measures where it rests).
     */
    fun settle(ms: Long = 120L, snap: View? = null) {
        val back = ArrayList<Entry>()
        for (e in active) {
            e.anim.cancel()
            val v = e.view
            if (v === snap) { v.translationX = e.baseX; v.translationY = e.baseY; continue }
            val fromX = v.translationX; val fromY = v.translationY
            if (fromX == e.baseX && fromY == e.baseY) continue
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ms
                interpolator = Easing.CUBIC_OUT
                addUpdateListener {
                    val f = it.animatedValue as Float
                    v.translationX = fromX + (e.baseX - fromX) * f
                    v.translationY = fromY + (e.baseY - fromY) * f
                }
            }
            back += Entry(v, anim, e.baseX, e.baseY)    // a new press's reset() cancels these and restores rest
            anim.start()
        }
        active.clear()
        active += back
    }

    /** One-shot "release" wave from a point (app returning to its icon): out and back. */
    fun pulse(views: List<View>, tx: Float, ty: Float) {
        start(views, tx, ty)
        val first = active.firstOrNull()?.view ?: return
        first.postDelayed({ reverse() }, DURATION / 2)
    }

    fun reset() {
        for (e in active) {
            e.anim.cancel()
            e.view.translationX = e.baseX
            e.view.translationY = e.baseY
        }
        active.clear()
    }
}
