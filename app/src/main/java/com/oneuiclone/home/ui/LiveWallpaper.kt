package com.oneuiclone.home.ui

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Launchii's live wallpaper (0.162; on by default for wallpapers set here since 0.163): the launcher's own wallpaper
 * copy ([WallpaperLayer.OWN_FILE]), drawn by this app so Home zooms the real wallpaper itself on app opens and closes.
 * The system only zooms an image wallpaper for the Pixel Launcher, and Home's copy of it (drawn in the launcher window,
 * which the system fades during the transitions) cross-dissolved with it. Blur wallpaper bakes into the picture it
 * draws ([WallpaperLayer.setHomeBitmap]). The engine runs in the launcher's process, so the zoom is passed in memory.
 */
object LiveWallpaper {
    /** The zoom the engines draw (1 = rest, as the system shows an image wallpaper). Main thread. */
    var zoom = 1f; private set
    /** Changed on the main thread; also read by Home's wallpaper loader ([isActive]). */
    private val engines = java.util.concurrent.CopyOnWriteArraySet<LiveWallpaperService.ZoomEngine>()
    /** A zoom to take once the wallpaper is out of sight ([park]). */
    private var parked: Float? = null

    /** True while Launchii's wallpaper is drawing (not a preview): Home then zooms it instead of its copy. */
    val drives get() = engines.isNotEmpty()

    fun setZoom(z: Float) {
        parked = null
        apply(z)
    }

    /**
     * Moves the wallpaper to [z] out of sight: now if it's hidden (an app covers it), else once it is. An app opened
     * from Recents then still closes with the wallpaper zooming back, without a jump while Recents shows it.
     */
    fun park(z: Float) {
        if (engines.none { it.visible }) apply(z) else parked = z
    }

    private fun apply(z: Float) {
        if (z == zoom) return
        zoom = z
        engines.forEach { it.requestDraw() }
    }

    /** The opening animations the wallpaper draws (2026-11-06 mockups): Aura (0.177), then Shutter, Orbit, Anticipate, Scan and Mist (0.178). */
    enum class Kind { AURA, SHUTTER, ORBIT, ANTICIPATE, SCAN, MIST }

    /**
     * An opening animation drawn over the picture from the tap until the app covers the wallpaper. Android fades
     * Home's window during an open but never the wallpaper's, so it lasts through the system's part of the open.
     * [icon]: the tapped icon (any config); [fromX]/[fromY]/[fromSize]: its centre and width on screen; [startAt]:
     * the tap (uptime); [splash]: the app's splash colour (Shutter).
     */
    class OpenEffect(val kind: Kind, val icon: Bitmap, val fromX: Float, val fromY: Float, val fromSize: Float,
                     val startAt: Long, val splash: Int = Color.WHITE) {
        /** Uptime its fade-out began, or 0 while it's up. */
        @Volatile var endAt = 0L
        /** Shutter: the full icon in its splash circle, once loaded (otherwise the Home icon grows). */
        @Volatile var splashIcon: Bitmap? = null
        @Volatile var splashIconAt = 0L
    }

    /** Set just before a new picture is written (a recent wallpaper picked from Home's menu): the engine crossfades to it. */
    @Volatile var crossfadeNext = false

    /** Length of that crossfade (sine in-out); Home's frosted icons and widgets fade over the same. */
    const val CROSSFADE_MS = 800L

    /**
     * The engine has the new picture and is holding the old one on screen until Home's frosted copy of the new one
     * is ready ([startCrossfade]), so icons, widgets and wallpaper change together. A timeout starts it regardless.
     */
    private val crossfadeHeld = AtomicBoolean(false)

    /** Uptime the last crossfade started (0 = none yet). Home's glass reads it to fade on the same clock. */
    @Volatile var crossfadeStartedAt = 0L; private set

    internal fun holdCrossfade() = crossfadeHeld.set(true)

    /** Any thread: starts a held crossfade now (no-op when none is held). True if this call started it. */
    fun startCrossfade(): Boolean {
        if (!crossfadeHeld.compareAndSet(true, false)) return false
        val now = android.os.SystemClock.uptimeMillis()
        crossfadeStartedAt = now
        engines.forEach { it.beginFade(now) }
        return true
    }

    /** The effect being drawn, or null. */
    @Volatile var effect: OpenEffect? = null; internal set

    /** Main thread: starts [a] on the wallpaper (replacing any other). */
    fun startEffect(a: OpenEffect) {
        effect = a
        engines.forEach { it.animateEffect() }
    }

    /** Fades the effect out (Home came back before an app covered it, or the launch failed). */
    fun endEffect() {
        val a = effect ?: return
        if (a.endAt == 0L) a.endAt = android.os.SystemClock.uptimeMillis()
        engines.forEach { it.animateEffect() }
    }

    /** The picture changed (a new wallpaper, or Blur wallpaper): any thread. */
    fun contentChanged() = engines.forEach { it.reload() }

    /** Whether wallpapers set in Launchii should go on as its live wallpaper (until the user turns it off). */
    fun wanted(context: Context) = prefs(context).getBoolean(WallpaperLayer.KEY_LIVE_WANTED, true)
    fun setWanted(context: Context, on: Boolean) { prefs(context).edit().putBoolean(WallpaperLayer.KEY_LIVE_WANTED, on).apply() }
    private fun prefs(context: Context) = context.getSharedPreferences(WallpaperLayer.PREFS, Context.MODE_PRIVATE)

    internal fun attach(e: LiveWallpaperService.ZoomEngine) { engines += e }
    internal fun detach(e: LiveWallpaperService.ZoomEngine) { engines -= e }
    internal fun visibilityChanged() {
        if (engines.none { it.visible }) {
            // the app covers the wallpaper: the effect is over, and the close finds the plain picture
            // (redrawn now, out of sight: a visible wallpaper first shows the last frame it posted)
            effect?.let {
                effect = null
                com.oneuiclone.home.debug.AnimLog.log("open effect ${it.kind}: wallpaper hidden, dropped")
                engines.forEach { e -> e.requestDraw() }
            }
            parked?.let { parked = null; apply(it) }
        }
    }

    /** True while Launchii's live wallpaper is the Home wallpaper. */
    fun isActive(context: Context): Boolean = drives || try {
        WallpaperManager.getInstance(context).wallpaperInfo?.packageName == context.packageName
    } catch (_: Exception) { false }

    /** The system's preview of Launchii's wallpaper, with its Set wallpaper button. */
    fun pickerIntent(context: Context): Intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, LiveWallpaperService::class.java))

    /** Puts the launcher's copy back as an image wallpaper, on the lock screen too if it showed Launchii's. Slow. */
    fun turnOff(context: Context): Boolean {
        val wm = WallpaperManager.getInstance(context)
        val bmp = BitmapFactory.decodeFile(java.io.File(context.filesDir, WallpaperLayer.OWN_FILE).path) ?: return false
        val lockToo = try { wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK)?.packageName == context.packageName } catch (_: Exception) { false } ||
            wm.getWallpaperId(WallpaperManager.FLAG_LOCK) <= 0
        // a blurred Home's lock screen showed the sharp original: it keeps it
        val sharp = if (lockToo && WallpaperBlur.isBlurred(context))
            BitmapFactory.decodeFile(java.io.File(context.filesDir, WallpaperBlur.SHARP_FILE).path) else null
        WallpaperBlur.keepBlur(context) {
            // straight to the system: WallpaperLayer's setters keep a live wallpaper that's on
            val both = lockToo && sharp == null
            WallpaperLayer.recordOwnCopy(context, bmp) {
                wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM or if (both) WallpaperManager.FLAG_LOCK else 0)
            }
            val lockBmp = if (both) bmp else sharp
            if (lockBmp != null) {
                if (!both) wm.setBitmap(lockBmp, null, true, WallpaperManager.FLAG_LOCK)
                WallpaperLayer.recordLockCopy(context, lockBmp)
            }
        }
        return true
    }
}

/** Builds Aura glows and effect colours off the drawing thread, so an effect starts on its first frame. */
/** Crossfade to a recent wallpaper picked from Home's menu (0.263). */
private const val CROSSFADE_MS = LiveWallpaper.CROSSFADE_MS.toFloat()
/** Longest the crossfade waits for Home's frosted copy before it starts anyway. */
private const val CROSSFADE_HOLD_MS = 600L
private val glowWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "AuraGlow") }

class LiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ZoomEngine()

    /**
     * Draws the copy centre-cropped to the surface at [WallpaperLayer.systemScale] × the zoom, as the system shows an
     * image wallpaper (so turning it on changes nothing at rest). Draws only when the zoom, surface or visibility
     * changes, on its own thread: a still wallpaper costs nothing.
     */
    inner class ZoomEngine : Engine() {
        private val thread = HandlerThread("LiveWallpaper").apply { start() }
        private val handler = Handler(thread.looper)
        private val lock = Any()
        private val pending = AtomicBoolean()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val matrix = Matrix()
        private val baseScale = WallpaperLayer.systemScale()
        /** Drawing thread only. [lockBitmap]: the sharp original, for a lock screen sharing a blurred Home. */
        private var bitmap: Bitmap? = null
        private var lockBitmap: Bitmap? = null
        @Volatile private var colors: WallpaperColors? = null
        /** Guarded by [lock]: false once the surface is gone, so the thread never draws into a dead one. */
        private var surfaceReady = false
        @Volatile var visible = false; private set
        @Volatile private var drawZoom = 1f
        /** Unlocked: Home's zoom and picture replace the lock screen's. */
        private val unlocked = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = requestDraw()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(false)
            if (!isPreview) {
                LiveWallpaper.attach(this)
                // exported: SystemUI (not the system uid) sends USER_PRESENT, which is protected
                registerReceiver(unlocked, android.content.IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_EXPORTED)
            }
            drawZoom = if (isPreview) 1f else LiveWallpaper.zoom
            handler.post {
                if (!isPreview) WallpaperLayer.adoptLiveId(this@LiveWallpaperService)
                load()
            }
        }

        /** Any thread: re-reads the picture. */
        fun reload() { handler.post { load() } }

        private fun load() {
            val src = BitmapFactory.decodeFile(java.io.File(filesDir, WallpaperLayer.OWN_FILE).path) ?: return
            val lockShared = try {
                WallpaperManager.getInstance(this@LiveWallpaperService).getWallpaperId(WallpaperManager.FLAG_LOCK) <= 0
            } catch (_: Exception) { false }
            lockBitmap = if (!lockShared || !WallpaperBlur.isBlurred(this@LiveWallpaperService)) null
                else BitmapFactory.decodeFile(java.io.File(filesDir, WallpaperBlur.SHARP_FILE).path)?.let { it.copy(Bitmap.Config.HARDWARE, false) ?: it }
            val small = Bitmap.createScaledBitmap(src, max(1, src.width / 8), max(1, src.height / 8), true)
            colors = try { WallpaperColors.fromBitmap(small) } catch (_: Exception) { null }
            // a hardware bitmap stays on the GPU, so each zoom frame is a single textured draw
            val old = bitmap
            bitmap = src.copy(Bitmap.Config.HARDWARE, false) ?: src
            if (!isPreview && LiveWallpaper.crossfadeNext) {
                LiveWallpaper.crossfadeNext = false
                if (old != null && visible) {
                    // the old picture stays on top (fadeAt 0) until Home has its frosted copy of the new one
                    fadeFrom = old
                    fadeAt = 0L
                    LiveWallpaper.holdCrossfade()
                    handler.postDelayed({ LiveWallpaper.startCrossfade() }, CROSSFADE_HOLD_MS)
                }
            }
            notifyColorsChanged()
            draw()
        }

        override fun onComputeColors(): WallpaperColors? = colors

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            synchronized(lock) { surfaceReady = true }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            requestDraw()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            // the system waits for this frame before showing the wallpaper
            handler.runWithScissorsCompat { draw() }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            synchronized(lock) { surfaceReady = false }
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (!isPreview) LiveWallpaper.visibilityChanged()
            if (visible) requestDraw()
        }

        override fun onDestroy() {
            if (!isPreview) try { unregisterReceiver(unlocked) } catch (_: Exception) {}
            LiveWallpaper.detach(this)
            synchronized(lock) { surfaceReady = false }
            handler.post { choreographer?.removeFrameCallback(effectFrame) }
            thread.quitSafely()
            super.onDestroy()
        }

        /** Main thread: draws the current zoom on the next turn of the drawing thread (coalesced). */
        fun requestDraw() {
            if (!isPreview) drawZoom = LiveWallpaper.zoom
            // the effect's frame loop draws every frame, with this zoom
            if (effectDrawing != null && LiveWallpaper.effect != null) return
            if (pending.compareAndSet(false, true)) handler.post { pending.set(false); draw() }
        }

        private fun draw() {
            val locked = isLocked()
            val b = (if (locked) lockBitmap else null) ?: bitmap ?: return
            val effect = if (locked || isPreview) null else LiveWallpaper.effect
            synchronized(lock) {
                if (!surfaceReady) return
                val holder = surfaceHolder
                val frame = holder.surfaceFrame
                if (frame.isEmpty) return
                val c = try { holder.lockHardwareCanvas() } catch (_: Exception) { null } ?: return
                try {
                    val w = frame.width().toFloat(); val h = frame.height().toFloat()
                    val fit = max(w / b.width, h / b.height)
                    // the lock screen shows it at rest: Home's zoom belongs to Home
                    val z = baseScale * if (locked) 1f else drawZoom
                    matrix.setScale(fit, fit)
                    matrix.postTranslate((w - b.width * fit) / 2f, (h - b.height * fit) / 2f)
                    matrix.postScale(z, z, w / 2f, h / 2f)
                    c.drawColor(Color.BLACK)
                    val from = fadeFrom
                    if (effect == null && from != null && !locked) {
                        // a recent wallpaper picked from Home's menu: the old picture fades off the new one
                        val p = if (fadeAt == 0L) 0f else ((android.os.SystemClock.uptimeMillis() - fadeAt) / CROSSFADE_MS).coerceIn(0f, 1f)
                        c.drawBitmap(b, matrix, paint)
                        if (p < 1f) {
                            val f = max(w / from.width, h / from.height)
                            fadeMatrix.setScale(f, f)
                            fadeMatrix.postTranslate((w - from.width * f) / 2f, (h - from.height * f) / 2f)
                            fadeMatrix.postScale(z, z, w / 2f, h / 2f)
                            fadePaint.alpha = (255 * (1f - com.oneuiclone.home.motion.Easing.SINE_IN_OUT_33.getInterpolation(p))).toInt()
                            c.drawBitmap(from, fadeMatrix, fadePaint)
                        } else fadeFrom = null
                    } else if (effect == null) c.drawBitmap(b, matrix, paint) else painter.draw(c, b, matrix, effect, w, h, minZoom = 1f / (baseScale * drawZoom))
                } finally {
                    holder.unlockCanvasAndPost(c)
                }
            }
        }

        // ---- Crossfade to a picked recent wallpaper (drawing thread) ----
        @Volatile private var fadeFrom: Bitmap? = null
        /** Uptime the crossfade started; 0 while it's held ([LiveWallpaper.startCrossfade]). */
        @Volatile private var fadeAt = 0L

        /** Any thread: the held crossfade starts, timed from [at] (uptime). */
        fun beginFade(at: Long) {
            handler.post {
                if (fadeFrom == null || fadeAt != 0L) return@post
                fadeAt = at
                val ch = choreographer ?: android.view.Choreographer.getInstance().also { choreographer = it }
                ch.removeFrameCallback(fadeFrame)
                ch.postFrameCallback(fadeFrame)
            }
        }
        private val fadeMatrix = Matrix()
        private val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val fadeFrame: android.view.Choreographer.FrameCallback = android.view.Choreographer.FrameCallback {
            draw()
            if (fadeFrom != null) choreographer?.postFrameCallback(fadeFrame)
        }

        // ---- Opening effects (drawing thread, except [effectDrawing]) ----
        private val painter = OpenEffectPainter(resources.displayMetrics.density)
        /** The effect [painter]'s glow and colours are being built for, on [glowWorker]. */
        @Volatile private var glowFor: LiveWallpaper.OpenEffect? = null
        private var choreographer: android.view.Choreographer? = null
        /** The effect the frame loop is running for (read on the main thread too). */
        @Volatile private var effectDrawing: LiveWallpaper.OpenEffect? = null
        private var effectFrames = 0
        private var effectLast = 0L
        private var effectGap = 0L
        private var effectSlowest = 0L
        private val effectFrame = android.view.Choreographer.FrameCallback { stepEffect() }

        /** Any thread: runs the effect's frame loop until it ends or the wallpaper hides; its colours join once built. */
        fun animateEffect() {
            if (isPreview) return
            handler.post {
                val a = LiveWallpaper.effect ?: return@post
                if (glowFor !== a) {
                    glowFor = a
                    painter.glow = null; painter.palette = null
                    glowWorker.execute {
                        val t0 = System.nanoTime()
                        val g = if (a.kind == LiveWallpaper.Kind.AURA) try { painter.buildGlow(a.icon) } catch (e: Exception) { null } else null
                        val pal = try { painter.buildPalette(a.icon) } catch (e: Exception) { null }
                        if (glowFor === a) { painter.glow = g; painter.palette = pal }
                        com.oneuiclone.home.debug.AnimLog.log("open effect %s: colours %s in %.1fms".format(a.kind,
                            pal?.joinToString { "#%06X".format(it and 0xFFFFFF) } ?: "failed", (System.nanoTime() - t0) / 1e6))
                    }
                }
                if (effectDrawing !== a) { effectDrawing = a; effectFrames = 0; effectLast = 0L; effectGap = 0L; effectSlowest = 0L }
                val ch = choreographer ?: android.view.Choreographer.getInstance().also { choreographer = it }
                ch.removeFrameCallback(effectFrame)
                ch.postFrameCallback(effectFrame)
            }
        }

        private fun stepEffect() {
            val a = effectDrawing
            val now = android.os.SystemClock.uptimeMillis()
            if (a != null && LiveWallpaper.effect === a) {
                // never covered (an app that shows the wallpaper): let it go
                if (a.endAt == 0L && now - a.startAt > EFFECT_MAX_MS) a.endAt = now
                if (a.endAt != 0L && now - a.endAt >= EFFECT_FADE_MS) LiveWallpaper.effect = null
            }
            if (effectLast != 0L) effectGap = max(effectGap, now - effectLast)
            effectLast = now
            effectFrames++
            draw()
            effectSlowest = max(effectSlowest, android.os.SystemClock.uptimeMillis() - now)
            if (a != null && LiveWallpaper.effect === a) { choreographer?.postFrameCallback(effectFrame); return }
            if (a != null) com.oneuiclone.home.debug.AnimLog.log("open effect ${a.kind}: $effectFrames frames over ${now - a.startAt}ms, " +
                "longest gap ${effectGap}ms, slowest draw ${effectSlowest}ms" + if (a.endAt != 0L) ", faded out" else "")
            effectDrawing = null
            painter.glow = null; painter.palette = null; glowFor = null
        }

        private fun isLocked() = try {
            getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        } catch (_: Exception) { false }

        /** Runs [block] on the drawing thread and waits for it (at most 300 ms). */
        private fun Handler.runWithScissorsCompat(block: () -> Unit) {
            if (looper.isCurrentThread) { block(); return }
            val done = java.util.concurrent.CountDownLatch(1)
            post { try { block() } finally { done.countDown() } }
            done.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }
}
