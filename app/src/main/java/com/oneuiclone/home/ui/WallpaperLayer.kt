package com.oneuiclone.home.ui

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Launcher-drawn copy of the (static) system wallpaper so we can reproduce what One UI does to it:
 *  - blur(progress × radius) + Samsung colour curve (wallpaper_view_blur_*: sat 0.25, minX 15, maxX 255 →
 *    minY 53.5, maxY 232) for Apps / Folder / Edit states,
 *  - zoom to 1.3× on app open (HighEndOpenParams.wallpaperScale) and back on close.
 *
 * The real system wallpaper stays visible behind the window; our copy only becomes visible while it is
 * transformed, so live wallpapers simply fall back to window blur.
 */
@SuppressLint("ViewConstructor")
class WallpaperLayer(context: Context) : android.view.ViewGroup(context) {

    private val wm = WallpaperManager.getInstance(context)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    /** Quarter-resolution, blurred & colour-graded copy. */
    private val blurView = BitmapView(context)
    /** Full-resolution copy used for zoom. */
    private val sharpView = BitmapView(context)

    var available = false; private set
    private var full: Bitmap? = null
    private var small: Bitmap? = null
    private var blurProgress = 0f
    private var zoom = 1f
    var maxBlurRadiusPx = 0f

    /**
     * The system draws an image wallpaper scaled by config_wallpaperMaxScale (1.10 in AOSP) while no launcher
     * drives its zoom, which a third-party launcher can't. Our copy uses the same base scale so handing back to
     * the real wallpaper after the open/close zoom is seamless.
     */
    private val systemScale = systemScale()

    companion object {
        const val DOWNSCALE = 4
        /** The launcher's exact copy of a wallpaper it set itself, valid while the system id matches. */
        const val OWN_FILE = "wallpaper.png"
        const val PREFS = "wallpaper"
        const val KEY_SYSTEM_ID = "system_id"
        /** Bumped each time the copy is rewritten: under Launchii's live wallpaper a new picture keeps the system id. */
        const val KEY_COPY_STAMP = "copy_stamp"
        /** False once the user turned Launchii's live wallpaper off: wallpapers set here then stay image wallpapers. */
        const val KEY_LIVE_WANTED = "live_wanted"
        const val LOCK_FILE = "wallpaper_lock.png"
        const val KEY_LOCK_ID = "lock_id"
        /** wallpaper_view_blur_saturation — interpreted as a +25 % saturation boost [INF]. */
        const val SATURATION = 1.25f
        private const val MIN_X = 15f
        private const val MAX_X = 255f
        private const val MIN_Y = 53.5f
        private const val MAX_Y = 232f


        fun systemScale(): Float = try {
            val res = android.content.res.Resources.getSystem()
            val id = res.getIdentifier("config_wallpaperMaxScale", "dimen", "android")
            if (id != 0) res.getFloat(id).coerceIn(1f, 1.5f) else 1f
        } catch (_: Exception) { 1f }

        /**
         * True while the system wallpaper is one the launcher set itself (so it has the exact copy for blur and zoom),
         * or Launchii's live wallpaper, which draws that copy.
         */
        fun hasOwnCopy(context: Context): Boolean = try {
            val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SYSTEM_ID, -1)
            id > 0 && java.io.File(context.filesDir, OWN_FILE).exists() &&
                (WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM) == id || LiveWallpaper.isActive(context))
        } catch (_: Exception) { false }

        /**
         * Sets [bmp] as the system wallpaper with [set] (returns its id, ≤ 0 if unknown) and records it as the
         * launcher's exact copy. The slow PNG write happens first, so the copy is in place the moment the new
         * wallpaper is: setting it recreates the activity, which reads the copy straight away.
         */
        fun recordOwnCopy(context: Context, bmp: Bitmap, set: () -> Int): Int {
            val file = java.io.File(context.filesDir, OWN_FILE)
            val tmp = java.io.File(context.filesDir, "$OWN_FILE.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val id = set().takeIf { it > 0 } ?: WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            if (!tmp.renameTo(file)) throw java.io.IOException("couldn't keep the wallpaper copy")
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_SYSTEM_ID, id).putLong(KEY_COPY_STAMP, prefs.getLong(KEY_COPY_STAMP, 0L) + 1).commit()
            return id
        }

        /**
         * Sets [bmp] as the Home wallpaper: while Launchii's live wallpaper is on, as the picture it draws (the live
         * wallpaper stays), else as an image wallpaper. Records it as the copy. Slow: call off the main thread.
         */
        fun setHomeBitmap(context: Context, bmp: Bitmap): Int {
            val wm = WallpaperManager.getInstance(context)
            if (!LiveWallpaper.isActive(context)) return recordOwnCopy(context, bmp) { wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM) }
            return recordOwnCopy(context, bmp) { wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.also { LiveWallpaper.contentChanged() }
        }

        /**
         * Turning Launchii's live wallpaper on gives Home a new system wallpaper id. The copy (and a blur baked into
         * it) is the same picture, so it moves over to the new id.
         */
        @Synchronized fun adoptLiveId(context: Context) {
            if (!LiveWallpaper.isActive(context)) return
            val id = WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = prefs.getInt(KEY_SYSTEM_ID, -1)
            if (id <= 0 || old < 0 || old == id) return
            val e = prefs.edit().putInt(KEY_SYSTEM_ID, id)
            if (prefs.getInt(WallpaperBlur.KEY_BLURRED_ID, -1) == old) e.putInt(WallpaperBlur.KEY_BLURRED_ID, id)
            e.commit()
        }

        /**
         * Sets a display-sized [bmp] (WallpaperActivity) on [which] = FLAG_SYSTEM and / or FLAG_LOCK. With the home
         * screen in it, it's kept as our exact copy. Slow: call off the main thread.
         */
        fun setDisplayBitmap(context: Context, bmp: Bitmap, which: Int) {
            val wm = WallpaperManager.getInstance(context)
            when {
                // Launchii's live wallpaper stays on Home with the new picture; a lock screen gets its own image
                which and WallpaperManager.FLAG_SYSTEM != 0 && LiveWallpaper.isActive(context) -> {
                    setHomeBitmap(context, bmp)
                    if (which and WallpaperManager.FLAG_LOCK != 0) wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
                }
                which and WallpaperManager.FLAG_SYSTEM != 0 -> recordOwnCopy(context, bmp) { wm.setBitmap(bmp, null, true, which) }
                else -> wm.setBitmap(bmp, null, true, which)
            }
            if (which and WallpaperManager.FLAG_LOCK != 0) recordLockCopy(context, bmp)
        }

        /**
         * A copy of a lock screen wallpaper the launcher set, for the Wallpaper and style preview (it can't read
         * the system's). Kept with the lock wallpaper id; with no separate lock wallpaper (-1) it shows Home's.
         */
        fun recordLockCopy(context: Context, bmp: Bitmap) {
            val id = WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_LOCK)
            if (id <= 0) return
            val file = java.io.File(context.filesDir, LOCK_FILE)
            val tmp = java.io.File(context.filesDir, "$LOCK_FILE.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (tmp.renameTo(file)) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_LOCK_ID, id).apply()
        }

        /** The lock copy, while the lock screen still shows it. */
        fun lockCopyFile(context: Context): java.io.File? = try {
            val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LOCK_ID, -1)
            val f = java.io.File(context.filesDir, LOCK_FILE)
            f.takeIf { id > 0 && it.exists() && WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_LOCK) == id }
        } catch (_: Exception) { null }

        /** Draws a display-sized wallpaper [b] as the system shows it on Home (scaled by [systemScale] about the centre). */
        fun drawFramed(c: Canvas, b: Bitmap) {
            val s = systemScale()
            c.save()
            c.scale(s, s, b.width / 2f, b.height / 2f)
            c.drawBitmap(b, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
        }

        /** ImageWallpaper's centre-crop of [src] to a [w]×[h] display. */
        fun centerCrop(src: Bitmap, w: Int, h: Int): Bitmap {
            val scale = max(w / src.width.toFloat(), h / src.height.toFloat())
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val m = Matrix().apply {
                setScale(scale, scale)
                postTranslate((w - src.width * scale) / 2f, (h - src.height * scale) / 2f)
            }
            Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }

        /** Samsung curve as a colour matrix: saturation, then out = minY + (in − minX)·(maxY−minY)/(maxX−minX). */
        fun curveMatrix(): ColorMatrix {
            val m = ColorMatrix().apply { setSaturation(SATURATION) }
            val k = (MAX_Y - MIN_Y) / (MAX_X - MIN_X)
            val b = MIN_Y - MIN_X * k
            val curve = ColorMatrix(floatArrayOf(
                k, 0f, 0f, 0f, b,
                0f, k, 0f, 0f, b,
                0f, 0f, k, 0f, b,
                0f, 0f, 0f, 1f, 0f,
            ))
            m.postConcat(curve)
            return m
        }
    }

    init {
        clipChildren = false
        addView(sharpView)
        addView(blurView)
        sharpView.visibility = INVISIBLE
        blurView.visibility = INVISIBLE
        blurView.pivotX = 0f
        blurView.pivotY = 0f
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        sharpView.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        blurView.measure(MeasureSpec.makeMeasureSpec(max(1, w / DOWNSCALE), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(max(1, h / DOWNSCALE), MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        sharpView.layout(0, 0, r - l, b - t)
        blurView.layout(0, 0, blurView.measuredWidth, blurView.measuredHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (maxBlurRadiusPx == 0f) maxBlurRadiusPx = w * 0.16f
        post { reload() }
    }

    /**
     * Wallpaper the launcher set itself ([setFromImage]): an exact display-sized bitmap, so the system has
     * nothing to crop, offset or parallax and our copy matches it pixel for pixel. Kept with the system
     * wallpaper id it was set as; once another app changes the wallpaper the id differs and it's ignored.
     */
    private val ownFile = java.io.File(context.filesDir, OWN_FILE)
    private val ownPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /**
     * The copy recorded after this instance last read it: setting a wallpaper recreates the activity (new dynamic
     * colours), so the instance that set it can finish recording it after this one has already loaded. Held here
     * because SharedPreferences keeps its listeners weakly.
     */
    private val ownCopyRecorded = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_SYSTEM_ID || key == KEY_COPY_STAMP) reload()
    }
    @Volatile private var destroyed = false
    init { ownPrefs.registerOnSharedPreferenceChangeListener(ownCopyRecorded) }

    private fun ownWallpaper(): Bitmap? = try {
        val id = ownPrefs.getInt(KEY_SYSTEM_ID, -1)
        if (id < 0 || !ownFile.exists() || (wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM) != id && !LiveWallpaper.isActive(context))) null
        else android.graphics.BitmapFactory.decodeFile(ownFile.path)
    } catch (_: Exception) { null }

    /** System wallpaper id, own-copy id and copy stamp the last [reload] saw. */
    @Volatile private var loaded = Triple(-2, -2, -2L)
    private fun ids() = Triple(wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM), ownPrefs.getInt(KEY_SYSTEM_ID, -1), ownPrefs.getLong(KEY_COPY_STAMP, 0L))

    /**
     * A wallpaper replaced while the launcher was away (Settings › Blur wallpaper) or whose own copy was
     * recorded after the change callback already reloaded.
     */
    fun reloadIfChanged() {
        if (!destroyed) io.execute { if (ids() != loaded) main.post { reload() } }
    }

    /** Called on the main thread after each [reload], whether or not a copy is [available]. */
    var onLoaded: (() -> Unit)? = null

    /** Re-read the system wallpaper (call on wallpaper change). */
    fun reload() {
        val w = width
        val h = height
        if (w == 0 || h == 0 || destroyed) return
        io.execute {
            loaded = ids()
            // Only the launcher's own copy: reading a wallpaper set elsewhere needs All files access, which Play
            // doesn't allow for launchers. Without a copy the window blur fallback is used.
            val src = ownWallpaper()
            android.util.Log.i("OneUiHome", "wallpaper copy: " + if (src == null) "none (system ${loaded.first}, copy ${loaded.second})" else "loaded (${loaded.first})")
            if (src == null) { main.post { if (!destroyed) { available = false; updateViews(); onLoaded?.invoke() } }; return@execute }
            val f = if (src.width == w && src.height == h) src else centerCrop(src, w, h)
            val s = Bitmap.createScaledBitmap(f, max(1, w / DOWNSCALE), max(1, h / DOWNSCALE), true)
            main.post {
                if (destroyed) return@post
                full = f; small = s
                sharpView.bitmap = f
                blurView.bitmap = s
                available = true
                updateViews()
                onLoaded?.invoke()
            }
        }
    }

    /** 0..1 blur progress (radius & curve), as BackgroundManager.setProgress. */
    fun setBlur(progress: Float) {
        blurProgress = progress.coerceIn(0f, 1f)
        updateViews()
    }

    /** Fades the copy over / off the real wallpaper so hand-overs dissolve instead of cutting. */
    var copyAlpha = 1f
        set(v) { field = v; sharpView.alpha = v }

    /** Keeps the (un-zoomed) copy on screen while it fades out after a zoom. */
    var holdCopy = false
        set(v) { field = v; updateViews() }

    /**
     * The copy stands in for the real wallpaper, which the system is keeping hidden (a Home-button close): shown at
     * the current zoom whatever else is going on, live wallpaper included.
     */
    var cover = false
        set(v) { field = v; updateViews() }

    /** Home's layer: its zoom also drives Launchii's live wallpaper, when that's the wallpaper. */
    var drivesLive = false

    /** Wallpaper zoom (1 = none, 1.3 = HighEndOpenParams). */
    fun setZoom(z: Float) {
        zoom = z
        if (drivesLive) LiveWallpaper.setZoom(z)
        updateViews()
    }

    private var lastRadius = -1f

    private fun updateViews() {
        if (!available) {
            blurView.visibility = INVISIBLE; sharpView.visibility = INVISIBLE
            return
        }
        val zoomed = zoom != 1f
        // Launchii's live wallpaper zooms itself: the copy would only cross-dissolve with it
        val live = drivesLive && LiveWallpaper.drives
        sharpView.visibility = if (((zoomed || holdCopy) && !live || cover) && blurProgress < 1f) VISIBLE else INVISIBLE
        val z = zoom * systemScale
        sharpView.scaleX = z; sharpView.scaleY = z
        if (blurProgress > 0f) {
            blurView.visibility = VISIBLE
            // BackgroundManager: bgAlpha = 765 × progress (opaque by p≈0.33), radius = progress × max
            blurView.alpha = (blurProgress * 3f).coerceAtMost(1f)
            val r = (blurProgress * maxBlurRadiusPx / DOWNSCALE).coerceAtLeast(0.01f)
            if (kotlin.math.abs(r - lastRadius) > 0.05f) {
                lastRadius = r
                blurView.setRenderEffect(
                    RenderEffect.createColorFilterEffect(
                        ColorMatrixColorFilter(interpolatedCurve(blurProgress)),
                        RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP),
                    )
                )
            }
            val s = DOWNSCALE * z
            blurView.pivotX = 0f; blurView.pivotY = 0f
            blurView.scaleX = s; blurView.scaleY = s
            blurView.translationX = width * (1 - z) / 2f
            blurView.translationY = height * (1 - z) / 2f
        } else {
            blurView.visibility = INVISIBLE
        }
    }

    private fun interpolatedCurve(p: Float): ColorMatrix {
        val target = curveMatrix().array
        val id = ColorMatrix().array
        val t = p.coerceIn(0f, 1f)
        return ColorMatrix(FloatArray(20) { id[it] + (target[it] - id[it]) * t })
    }

    /** Draw the (unblurred) wallpaper copy into [c] for backdrop snapshots. Returns false if unavailable. */
    fun drawSnapshot(c: Canvas): Boolean {
        drawFramed(c, full ?: return false)
        return true
    }

    /** Stops loading; a wallpaper still being set finishes, so the next instance gets its copy. */
    fun destroy() {
        destroyed = true
        ownPrefs.unregisterOnSharedPreferenceChangeListener(ownCopyRecorded)
        io.shutdown()
    }

    private class BitmapView(context: Context) : View(context) {
        var bitmap: Bitmap? = null
            set(v) { field = v; invalidate() }
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(canvas: Canvas) {
            val b = bitmap ?: return
            canvas.drawBitmap(b, null, android.graphics.Rect(0, 0, width, height), paint)
        }
    }

    @Suppress("unused")
    private fun Float.r() = roundToInt()
}
