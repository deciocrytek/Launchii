package com.oneuiclone.home.settings

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.oneuiclone.home.icons.SplashLook
import com.oneuiclone.home.motion.Easing
import com.oneuiclone.home.ui.Fonts
import com.oneuiclone.home.ui.LiveWallpaper
import com.oneuiclone.home.ui.OpenEffectPainter
import com.oneuiclone.home.ui.WallpaperLayer
import java.util.concurrent.Executors

/**
 * Settings › Motion (0.263; App opening animation, 0.262, design opinion 7): your own Home in a big preview that keeps opening an app
 * from the dock in the chosen style, and a chip for each of the 8 styles; tapping a chip picks it and plays it. The
 * wallpaper effects are drawn by the same [OpenEffectPainter] as Launchii's live wallpaper, so the preview is the real
 * thing; Home's part (scale and fade) and the app's arrival follow Launcher's open paths.
 */
class OpeningAnimationActivity : OneUiPage() {

    private class Style(val key: String, val name: String, val what: String, val kind: LiveWallpaper.Kind?)

    private val styles = listOf(
        Style(Prefs.OPEN_SMOOTH, "Smooth", "Home sinks back a little and the app fades in. Quick and quiet.", null),
        Style(Prefs.OPEN_ZOOM_IN, "Zoom In", "The icon grows into a card in the app's colour, which becomes the app.", null),
        Style(Prefs.OPEN_AURA, "Aura", "Your wallpaper blurs and dims while a glow in the app's colours rises to the middle.", LiveWallpaper.Kind.AURA),
        Style(Prefs.OPEN_SHUTTER, "Shutter", "Slats in the app's colour roll down from the icon's row, and the icon slides to the middle.", LiveWallpaper.Kind.SHUTTER),
        Style(Prefs.OPEN_ORBIT, "Orbit", "Lights in the app's colours circle a point that glides to the middle of a darker wallpaper.", LiveWallpaper.Kind.ORBIT),
        Style(Prefs.OPEN_ANTICIPATE, "Anticipate", "Your wallpaper pulls back a touch, then lunges toward you.", LiveWallpaper.Kind.ANTICIPATE),
        Style(Prefs.OPEN_SCAN, "Scan", "Two glowing lines split from the icon and frost the wallpaper between them.", LiveWallpaper.Kind.SCAN),
        Style(Prefs.OPEN_MIST, "Mist", "Soft clouds in white and the app's colours roll in from the edges.", LiveWallpaper.Kind.MIST),
    )

    private lateinit var stylePreview: StylePreview
    private lateinit var player: Player
    private lateinit var name: TextView
    private lateinit var what: TextView
    private lateinit var note: TextView
    /** The Live wallpaper link's state, shown on every resume. */
    private var liveShown: () -> Unit = {}
    private val chips = ArrayList<TextView>()
    private val io = Executors.newSingleThreadExecutor()
    private var built = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stylePreview = StylePreview(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        stylePreview.destroy()
        io.shutdownNow()
    }

    override fun build() {
        if (!built) { built = true; layout() }
        liveShown()
        stylePreview.load {
            stylePreview.openScene(StylePreview.Look.of(prefs), 0.5f) { scene -> if (scene != null) prepare(scene) }
        }
        select(prefs.openAnimation, play = false)
    }

    /** The app's splash colour and its glow and colours for the effects, off the main thread; then it plays. */
    private fun prepare(scene: StylePreview.OpenScene) {
        io.execute {
            val splash = try {
                val la = getSystemService(LauncherApps::class.java)
                val user = getSystemService(android.os.UserManager::class.java).getUserForSerialNumber(scene.key.userSerial)
                val info = la.resolveActivity(Intent(Intent.ACTION_MAIN).setComponent(scene.key.component), user)
                SplashLook(this).look(info).color
            } catch (_: Exception) { if (night) 0xFF1C1C1C.toInt() else Color.WHITE }
            val glow = try { player.painter.buildGlow(scene.icon) } catch (_: Exception) { null }
            val palette = try { player.painter.buildPalette(scene.icon) } catch (_: Exception) { null }
            val wall = scene.wallpaper.copy(Bitmap.Config.HARDWARE, false) ?: scene.wallpaper
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                player.painter.glow = glow
                player.painter.palette = palette
                player.set(scene, wall, splash)
                select(prefs.openAnimation, play = true)
            }
        }
    }

    private fun select(key: String, play: Boolean) {
        val i = styles.indexOfFirst { it.key == key }.coerceAtLeast(0)
        val s = styles[i]
        chips.forEachIndexed { j, c -> styleChip(c, j == i) }
        name.text = s.name
        what.text = s.what
        note.visibility = if (s.kind != null && !LiveWallpaper.isActive(this)) View.VISIBLE else View.GONE
        player.style = s.kind
        player.zoomIn = s.key == Prefs.OPEN_ZOOM_IN
        if (play) player.restart()
    }

    private fun layout() {
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(24f)) }
        appBar("Motion")
        groupHeader("App opening animation")

        // the preview: as big as fits with the chips still on screen
        val dm = resources.displayMetrics
        val ratio = stylePreview.height / stylePreview.width.toFloat()
        val maxH = dm.heightPixels * 0.56f
        val w = minOf(dm.widthPixels - dp(12f) * 2 - dp(48f) * 2, (maxH / ratio).toInt())
        player = Player(this, dp(26f).toFloat())
        player.setOnClickListener { player.restart() }
        player.contentDescription = "Preview of the app opening animation"
        list.addView(player, LinearLayout.LayoutParams(w, (w * ratio).toInt()).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4f); bottomMargin = dp(20f)
        })

        name = TextView(this).apply {
            setTextColor(this@OpeningAnimationActivity.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Fonts.ui(this@OpeningAnimationActivity, 650)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        list.addView(name)
        what = TextView(this).apply {
            setTextColor(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24f), dp(4f), dp(24f), dp(16f))
            minLines = 2
        }
        list.addView(what)
        // the chips, four to a row
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8f), 0, dp(8f), 0) }
        styles.chunked(4).forEach { rowStyles ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowStyles.forEach { s ->
                val chip = TextView(this).apply {
                    text = s.name
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Fonts.ui(this@OpeningAnimationActivity, 600)
                    isSingleLine = true
                    setOnClickListener {
                        prefs.openAnimation = s.key
                        select(s.key, play = true)
                    }
                }
                chips += chip
                row.addView(chip, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginStart = dp(4f); marginEnd = dp(4f) })
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) })
        }
        list.addView(grid)
        note = TextView(this).apply {
            text = "Drawn by Launchii's live wallpaper, which is off, so apps open with Zoom In for now. Turn it on in Wallpaper › Live wallpaper."
            setTextColor(primary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24f), dp(10f), dp(24f), 0)
        }
        list.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(20f) })

        // the live wallpaper draws six of the styles: its switch is in Wallpaper, linked here (0.263)
        card {
            val r = linkRow("Live wallpaper", "") {
                startActivity(android.content.Intent(this, WallpaperStyleActivity::class.java).putExtra(EXTRA_HIGHLIGHT, "Live wallpaper"))
            }
            val icon = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(primarySoft) }
                foreground = GlyphDrawable(Glyphs.IMAGE, primary, dp(22f))
            }
            r.setPadding(dp(18f), r.paddingTop, r.paddingRight, r.paddingBottom)
            r.addView(icon, 0, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { marginEnd = dp(16f) })
            val summary = (r.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            liveShown = {
                summary.text = (if (LiveWallpaper.isActive(this)) "On" else "Off") +
                    " · Aura, Shutter, Orbit, Anticipate, Scan and Mist are drawn by it. In Wallpaper."
            }
        }
        card {
            toggle("Settle in on unlock", "When you unlock to the Home screen, your wallpaper eases in and your apps ripple in from the fingerprint sensor.",
                prefs.unlockSettle) { prefs.unlockSettle = it }
        }

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); addView(list); isVerticalScrollBarEnabled = false; clipToPadding = false }
        val statusScrim = View(this).apply { setBackgroundColor(bg); translationZ = dp(8f).toFloat(); outlineProvider = null }
        val root = FrameLayout(this)
        root.addView(scroll)
        root.addView(statusScrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            statusScrim.layoutParams.height = bars.top
            root.requestLayout()
            insets
        }
        setContentView(root)
    }

    private fun styleChip(c: TextView, on: Boolean) {
        c.background = GradientDrawable().apply {
            cornerRadius = dp(22f).toFloat()
            setColor(if (on) primary else cardColor)
        }
        c.setTextColor(if (on) onPrimary else text)
    }

    /**
     * Plays the open on a loop: Home at rest, a tap on the app, the open in [style] (or Smooth / [zoomIn]), the app's
     * splash for a moment, then back to Home. Draws in display coordinates scaled to the view.
     */
    private class Player(context: Context, private val radius: Float) : View(context) {
        val painter = OpenEffectPainter(context.resources.displayMetrics.density)
        var style: LiveWallpaper.Kind? = null
        var zoomIn = false
        private var scene: StylePreview.OpenScene? = null
        private var wall: Bitmap? = null
        private var splash = Color.WHITE
        private var t0 = 0L
        private val clip = Path()
        private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val wallMatrix = Matrix()
        private val base = WallpaperLayer.systemScale()
        private val rect = RectF()
        private var effect: LiveWallpaper.OpenEffect? = null

        fun set(s: StylePreview.OpenScene, w: Bitmap, splashColour: Int) {
            scene = s; wall = w; splash = splashColour
            restart()
        }

        fun restart() {
            val s = scene ?: return
            t0 = SystemClock.uptimeMillis()
            val k = style
            effect = if (k == null) null else LiveWallpaper.OpenEffect(k, s.icon, s.iconRect.centerX(), s.iconRect.centerY(),
                s.iconRect.width(), TAP_MS, splash).also { if (k == LiveWallpaper.Kind.SHUTTER) { it.splashIcon = s.icon; it.splashIconAt = TAP_MS } }
            postInvalidateOnAnimation()
        }

        /** When the app has covered the screen, in ms after the tap. */
        private fun appAt() = when { style != null -> 1050f; zoomIn -> 520f; else -> 230f }

        override fun onDraw(c: Canvas) {
            val s = scene ?: return
            val wallBmp = wall ?: return
            clip.reset(); clip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            val k = width / s.width.toFloat()
            c.scale(k, k)
            val W = s.width.toFloat(); val H = s.height.toFloat()
            val now = SystemClock.uptimeMillis() - t0
            val m = (now - TAP_MS).toFloat()                       // since the tap
            val app = appAt()
            val back = m - (app + HOLD_MS)                           // since the return began
            val loop = TAP_MS + app + HOLD_MS + RETURN_MS + REST_MS

            // ---- the wallpaper ----
            wallMatrix.setScale(W / wallBmp.width, H / wallBmp.height)
            val e = effect
            if (e != null && m >= 0f && back < 0f) {
                wallMatrix.postScale(base, base, W / 2f, H / 2f)
                painter.draw(c, wallBmp, wallMatrix, e, W, H, now = now, minZoom = 1f / base)
            } else {
                val z = if (e == null && m > 0f) 1f + 0.06f * Easing.CUBIC_OUT.getInterpolation(((if (back > 0f) RETURN_MS - back else m) / 280f).coerceIn(0f, 1f)) else 1f
                wallMatrix.postScale(base * z, base * z, W / 2f, H / 2f)
                c.drawBitmap(wallBmp, wallMatrix, bmp)
            }

            // ---- Home: scale and fade as Launcher's open paths do ----
            var scale = 1f; var alpha = 1f
            if (m > 0f) {
                val (to, ms, delay, fade) = homeMotion()
                scale = 1f + (to - 1f) * Easing.STANDARD.getInterpolation((m / ms).coerceIn(0f, 1f))
                if (fade > 0f) alpha = 1f - ((m - delay) / fade).coerceIn(0f, 1f)
            }
            if (back > 0f) {
                val p = Easing.CUBIC_OUT.getInterpolation((back / RETURN_MS).coerceIn(0f, 1f))
                scale = 0.92f + 0.08f * p; alpha = p
            }
            if (alpha > 0.01f) {
                c.save()
                c.scale(scale, scale, W / 2f, H / 2f)
                bmp.alpha = (alpha * 255).toInt()
                c.drawBitmap(s.home, null, RectF(0f, 0f, W, H), bmp)
                bmp.alpha = 255
                c.restore()
            }

            // ---- the tap: a soft press on the icon ----
            if (m > -160f && m < 200f) {
                val p = ((m + 160f) / 360f).coerceIn(0f, 1f)
                fill.color = Color.argb((0.28f * (1f - p) * 255).toInt(), 255, 255, 255)
                c.drawCircle(s.iconRect.centerX(), s.iconRect.centerY(), s.iconRect.width() * (0.4f + 0.5f * p), fill)
            }

            // ---- Zoom In's card: the icon grows into a card in the app's colour ----
            if (zoomIn && m > 0f && m < app) {
                val p = Easing.STANDARD.getInterpolation((m / app).coerceIn(0f, 1f))
                val ir = s.iconRect
                rect.set(ir.left * (1f - p), ir.top * (1f - p), ir.right + (W - ir.right) * p, ir.bottom + (H - ir.bottom) * p)
                val r = ir.width() * 0.3f * (1f - p) + radius / k * p
                fill.color = splash
                fill.alpha = (255 * (p * 3f).coerceAtMost(1f)).toInt()
                c.drawRoundRect(rect, r, r, fill)
                val size = ir.width() * (1f + 0.9f * p)
                val a = (1f - (p - 0.4f) / 0.6f).coerceIn(0f, 1f)
                if (a > 0f) {
                    bmp.alpha = (a * 255).toInt()
                    c.drawBitmap(s.icon, null, RectF(rect.centerX() - size / 2f, rect.centerY() - size / 2f, rect.centerX() + size / 2f, rect.centerY() + size / 2f), bmp)
                    bmp.alpha = 255
                }
            }

            // ---- the app: its splash, faded in as Android shows a launched app, out again on the way back ----
            val appIn = ((m - app) / 120f).coerceIn(0f, 1f)
            val appOut = if (back > 0f) 1f - (back / (RETURN_MS * 0.6f)).coerceIn(0f, 1f) else 1f
            val appAlpha = if (zoomIn && m >= app) appOut else appIn * appOut
            if (appAlpha > 0.01f) {
                c.saveLayerAlpha(0f, 0f, W, H, (appAlpha * 255).toInt())
                c.drawColor(splash)
                val size = W * 0.3f
                c.drawBitmap(s.icon, null, RectF(W / 2f - size / 2f, H / 2f - size / 2f, W / 2f + size / 2f, H / 2f + size / 2f), bmp)
                c.restore()
            }
            c.restore()

            if (now >= loop) restart() else postInvalidateOnAnimation()
        }

        /** Home's scale target, scale ms, fade delay and fade ms (Launcher.effectHome; Smooth and Zoom In keep it). */
        private fun homeMotion(): Motion = when (style) {
            LiveWallpaper.Kind.AURA -> Motion(0.85f, 280f, 80f, 220f)
            LiveWallpaper.Kind.SHUTTER -> Motion(0.97f, 300f, 60f, 200f)
            LiveWallpaper.Kind.ORBIT -> Motion(0.88f, 320f, 80f, 220f)
            LiveWallpaper.Kind.ANTICIPATE -> Motion(0.8f, 410f, 140f, 200f)
            LiveWallpaper.Kind.SCAN -> Motion(0.96f, 300f, 80f, 220f)
            LiveWallpaper.Kind.MIST -> Motion(0.95f, 320f, 100f, 220f)
            null -> if (zoomIn) Motion(0.92f, 500f, 0f, 0f) else Motion(0.85f, 280f, 0f, 0f)
        }

        private data class Motion(val scale: Float, val ms: Float, val fadeDelay: Float, val fadeMs: Float)

        companion object {
            /** Home at rest before the tap, the app held, the way back, and a pause before the next play. */
            private const val TAP_MS = 700L
            private const val HOLD_MS = 700f
            private const val RETURN_MS = 380f
            private const val REST_MS = 300f
        }
    }
}
