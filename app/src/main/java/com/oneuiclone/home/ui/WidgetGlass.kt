package com.oneuiclone.home.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * The wallpaper as One UI's blurred widget backgrounds show it: a quarter-resolution copy of the Home wallpaper
 * (window coordinates), blurred and graded with Samsung's blur curve. Widgets set to Blur draw the part of it behind
 * their background (WidgetManager.RoundedWidgetView.glass), so it lines up with the real wallpaper wherever they move.
 */
class WidgetGlass {
    /** Blurred copy; null while it's built or when the wallpaper can't be read (widgets are then just translucent). */
    var bitmap: Bitmap? = null; private set
    /** Window pixels per bitmap pixel. */
    var scale = 1f; private set
    /** Crisp low-blur optical copy for Liquid Crystal large folders (no heavy tone curve). */
    var crystalBitmap: Bitmap? = null; private set
    var crystalScale = 1f; private set
    /** Widgets drawing the glass, redrawn when it changes. */
    val views = HashSet<View>()

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "widget-glass").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0

    /** Rebuild from [wallpaper]'s copy (call after each reload). */
    fun rebuild(wallpaper: WallpaperLayer) =
        build(wallpaper.width, wallpaper.height, wallpaper.available) { wallpaper.drawSnapshot(it) }

    /**
     * Rebuild from a display-sized wallpaper image ([WallpaperLayer.drawSnapshot]'s framing), for windows that
     * draw Home themselves (the Wallpaper and style preview).
     */
    fun rebuild(full: Bitmap) = build(full.width, full.height, true) { WallpaperLayer.drawFramed(it, full); true }

    private fun build(w: Int, h: Int, available: Boolean, draw: (Canvas) -> Boolean) {
        val gen = ++generation
        if (!available || w == 0 || h == 0 || io.isShutdown) { publish(gen, null, 1f, null, 1f); return }
        io.execute {
            val pair = try { blurredPair(w, h, draw) } catch (e: Exception) {
                android.util.Log.w("OneUiHome", "widget glass failed", e)
                null
            }
            val out = pair?.first
            val crys = pair?.second
            main.post {
                publish(
                    gen,
                    out, if (out == null) 1f else w / out.width.toFloat(),
                    crys, if (crys == null) 1f else w / crys.width.toFloat(),
                )
            }
        }
    }

    private fun publish(gen: Int, b: Bitmap?, s: Float, c: Bitmap?, cs: Float) {
        if (gen != generation) return
        val armed = fadeArmedAt != 0L && android.os.SystemClock.uptimeMillis() - fadeArmedAt < FADE_ARM_MS
        // the glass the views show right now (a mid-fade mix included) is what fades out
        val shownB = bitmap; val shownC = crystalBitmap
        if (armed && b != null && shownB != null) {
            if (fadeTo == null) {
                fadeFrom = shownB.copyFor(); fadeFromCrystal = shownC?.copyFor()
                fadePublishedAt = android.os.SystemClock.uptimeMillis()
            }
            fadeTo = b; fadeToCrystal = c
            scale = s; crystalScale = cs
            main.removeCallbacks(fadeTimeout)
            choreographer.removeFrameCallback(fadeFrame)
            choreographer.postFrameCallback(fadeFrame)
            // the wallpaper was holding its crossfade for this: both start now
            LiveWallpaper.startCrossfade()
            stepFade()
            return
        }
        endFade()
        bitmap = b; scale = s
        crystalBitmap = c; crystalScale = cs
        views.forEach { it.invalidate() }
        if (armed) LiveWallpaper.startCrossfade()
    }

    // ---- Crossfade to a picked recent wallpaper (0.267): old glass → new on the wallpaper's own clock ----

    /** Uptime [armFade] was called, 0 when not armed. */
    private var fadeArmedAt = 0L
    private var fadeFrom: Bitmap? = null
    private var fadeFromCrystal: Bitmap? = null
    private var fadeTo: Bitmap? = null
    private var fadeToCrystal: Bitmap? = null
    private var fadePublishedAt = 0L
    /** Two mixes each, alternated, so views see a new bitmap every frame and redraw (they compare identity). */
    private val mixes = arrayOfNulls<Bitmap>(2)
    private val crystalMixes = arrayOfNulls<Bitmap>(2)
    private var mixIndex = 0
    private val mixPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val choreographer by lazy { android.view.Choreographer.getInstance() }
    private val fadeFrame: android.view.Choreographer.FrameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { if (stepFade()) choreographer.postFrameCallback(this) }
    }
    private val fadeTimeout = Runnable { fadeArmedAt = 0L }

    /**
     * Main thread: the next rebuild (a recent wallpaper just picked) fades from the current glass to the new one
     * in step with the live wallpaper's crossfade, instead of switching at once.
     */
    fun armFade() {
        fadeArmedAt = android.os.SystemClock.uptimeMillis()
        main.removeCallbacks(fadeTimeout)
        main.postDelayed(fadeTimeout, FADE_ARM_MS)
    }

    /** Draws this frame's mix and publishes it; false once the fade is over. */
    private fun stepFade(): Boolean {
        val to = fadeTo ?: return false
        val from = fadeFrom
        val now = android.os.SystemClock.uptimeMillis()
        val liveAt = LiveWallpaper.crossfadeStartedAt
        val start = when {
            // the live wallpaper's crossfade: the same clock
            liveAt >= fadeArmedAt && fadeArmedAt != 0L -> liveAt
            // it hasn't started yet: hold the old look a while for it, then go on our own
            LiveWallpaper.drives -> if (now - fadePublishedAt < LIVE_WAIT_MS) now else fadePublishedAt + LIVE_WAIT_MS
            // an image wallpaper (the system changes it): from when the new glass arrived
            else -> fadePublishedAt
        }
        val p = ((now - start) / LiveWallpaper.CROSSFADE_MS.toFloat()).coerceIn(0f, 1f)
        if (p >= 1f || from == null) {
            bitmap = to; crystalBitmap = fadeToCrystal
            endFade()
            views.forEach { it.invalidate() }
            return false
        }
        val e = com.oneuiclone.home.motion.Easing.SINE_IN_OUT_33.getInterpolation(p)
        mixIndex = 1 - mixIndex
        bitmap = mix(mixes, from, to, e)
        crystalBitmap = fadeToCrystal?.let { tc -> fadeFromCrystal?.let { fc -> mix(crystalMixes, fc, tc, e) } ?: tc }
        views.forEach { it.invalidate() }
        return true
    }

    /** [to] with [from] fading off it (1 − [e]), as the live wallpaper draws its crossfade, into a reused bitmap. */
    private fun mix(pool: Array<Bitmap?>, from: Bitmap, to: Bitmap, e: Float): Bitmap {
        val m = pool[mixIndex]?.takeIf { it.width == to.width && it.height == to.height }
            ?: Bitmap.createBitmap(to.width, to.height, Bitmap.Config.ARGB_8888).also { pool[mixIndex] = it }
        val c = Canvas(m)
        mixPaint.alpha = 255
        c.drawBitmap(to, 0f, 0f, mixPaint)
        mixPaint.alpha = (255 * (1f - e)).toInt()
        if (from.width == to.width && from.height == to.height) c.drawBitmap(from, 0f, 0f, mixPaint)
        else c.drawBitmap(from, null, android.graphics.Rect(0, 0, to.width, to.height), mixPaint)
        return m
    }

    private fun endFade() {
        choreographer.removeFrameCallback(fadeFrame)
        if (fadeTo != null) fadeArmedAt = 0L
        fadeFrom = null; fadeFromCrystal = null; fadeTo = null; fadeToCrystal = null
    }

    /** A copy when this is one of the reused mixes (it's about to be drawn over), else itself. */
    private fun Bitmap.copyFor(): Bitmap = if (mixes.any { it === this } || crystalMixes.any { it === this }) copy(Bitmap.Config.ARGB_8888, false) else this

    fun destroy() { endFade(); io.shutdownNow() }

    companion object {
        /** Blur strength as a fraction of the Apps screen's full blur. */
        const val LEVEL = 0.6f
        /** Lighter optical blur for the Liquid Crystal 2×2 folder pane (photo stays softly readable). */
        const val CRYSTAL_LEVEL = 0.16f
        /** How long after a pick a rebuild still counts as the picked wallpaper's (decode, blur and save are slow). */
        private const val FADE_ARM_MS = 5000L
        /** Longest the glass holds its old look waiting for the wallpaper's crossfade to start. */
        private const val LIVE_WAIT_MS = 700L

        /**
         * The glass for a [w]×[h] window whose wallpaper [draw] paints (false when it can't): quarter size, blurred
         * and graded. Window pixels per bitmap pixel are w / its width.
         */
        fun blurred(w: Int, h: Int, draw: (Canvas) -> Boolean): Bitmap? = blurredPair(w, h, draw)?.first

        /**
         * Standard + crystal glass directly from a thumbnail [thumb] framed centre-cropped onto [screenW]×[screenH]
         * (matching [HomeMenu]'s tile framing, without system parallax scale).
         */
        fun blurredPairFromThumb(thumb: Bitmap, screenW: Int, screenH: Int): Pair<Bitmap, Bitmap>? =
            blurredPair(screenW, screenH) { c ->
                val w = screenW.toFloat(); val h = screenH.toFloat()
                val f = max(w / thumb.width, h / thumb.height)
                val dw = thumb.width * f; val dh = thumb.height * f
                c.drawBitmap(thumb, null, android.graphics.RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f),
                    Paint(Paint.FILTER_BITMAP_FLAG))
                true
            }

        fun blurredPair(w: Int, h: Int, draw: (Canvas) -> Boolean): Pair<Bitmap, Bitmap>? {
            val small = Bitmap.createBitmap(max(1, w / WallpaperLayer.DOWNSCALE), max(1, h / WallpaperLayer.DOWNSCALE), Bitmap.Config.ARGB_8888)
            val c = Canvas(small)
            c.scale(small.width / w.toFloat(), small.height / h.toFloat())
            if (!draw(c)) return null
            val blurred = WallpaperBlur.boxBlur(small, LEVEL * w * 0.16f / WallpaperLayer.DOWNSCALE)
            val standard = Bitmap.createBitmap(blurred.width, blurred.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(blurred, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(WallpaperLayer.curveMatrix()) })
            }
            val crystalBlur = WallpaperBlur.boxBlur(small, CRYSTAL_LEVEL * w * 0.16f / WallpaperLayer.DOWNSCALE)
            val crystal = Bitmap.createBitmap(crystalBlur.width, crystalBlur.height, Bitmap.Config.ARGB_8888).also {
                val cm = android.graphics.ColorMatrix().apply { setSaturation(1.15f) }
                cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(
                    1.04f, 0f, 0f, 0f, 4f,
                    0f, 1.04f, 0f, 0f, 4f,
                    0f, 0f, 1.04f, 0f, 4f,
                    0f, 0f, 0f, 1f, 0f,
                )))
                Canvas(it).drawBitmap(crystalBlur, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
            }
            return standard to crystal
        }
    }
}
