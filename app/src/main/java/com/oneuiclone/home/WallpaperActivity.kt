package com.oneuiclone.home

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import com.oneuiclone.home.motion.Easing
import com.oneuiclone.home.settings.Prefs
import com.oneuiclone.home.settings.StylePreview
import com.oneuiclone.home.ui.Fonts
import com.oneuiclone.home.ui.WallpaperLayer
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Launchii's own "set wallpaper" screen, as Niagara does it. WallpaperManager.getCropAndSetWallpaperIntent (what
 * Photos, galleries and file managers call for "Set as wallpaper") first offers CROP_AND_SET_WALLPAPER to the
 * default home app, so while Launchii is Home every such request lands here; ATTACH_DATA puts it in "Use as" lists.
 *
 * The photo fills the screen and is panned / pinched into place; the visible part is set at exactly the display
 * size, so the system has nothing left to crop or parallax and Home keeps a pixel-identical copy for its blur and
 * open / close zoom ([WallpaperLayer.setDisplayBitmap]). Home's first page sits on top, as it will look, so the
 * photo can be placed around the icons; a button hides it.
 */
class WallpaperActivity : Activity() {

    companion object {
        /** Return to the caller after setting (Wallpaper and style shows the result in its preview) instead of Home. */
        const val EXTRA_STAY = "app.launchii.extra.STAY"
        private const val REQUEST_LIVE = 7
        private const val STATE_WHICH = "which"
        /** Photos can be pinched up to this far past filling the screen. */
        const val MAX_ZOOM = 4f
        private const val HINT_MS = 2600L
        /** After a pan or pinch stops, Home's glass and label colour follow the new framing this much later. */
        private const val SETTLE_MS = 150L
    }

    private val io = Executors.newSingleThreadExecutor()
    private val d get() = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).roundToInt()
    private val night get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private lateinit var crop: CropView
    private lateinit var controls: FrameLayout
    private lateinit var hint: TextView
    private lateinit var apply: TextView
    private lateinit var homeLayer: FrameLayout
    private lateinit var homeToggle: GlyphButton
    private var preview: StylePreview? = null
    private val look by lazy { StylePreview.Look.of(Prefs(this)) }
    /** Home's labels and dots are dark ([StylePreview.homeView]): the photo as framed takes dark text. */
    private var bright: Boolean? = null
    /** Estimated Material You accent for the currently framed photo (0 = keep saved themeColor). */
    private var previewAccent = 0
    private var sheet: Sheet? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setWhich = savedInstanceState?.getInt(STATE_WHICH) ?: 0
        window.setDecorFitsSystemWindows(false)
        val uri = intent?.data
        if (uri == null) { finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        crop = CropView(this).apply { alpha = 0f; onTap = { toggleControls() }; onSettled = { settled() } }
        root.addView(crop, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Home on top; it never takes touches, so drags and pinches reach the photo through it
        homeLayer = object : FrameLayout(this) {
            override fun dispatchTouchEvent(ev: MotionEvent) = false
        }.apply { alpha = 0f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        root.addView(homeLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        controls = FrameLayout(this)
        // scrims so the white controls read over bright photos
        controls.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x66000000, 0))
        }, FrameLayout.LayoutParams(MATCH_PARENT, dp(140f), Gravity.TOP))

        val close = GlyphButton(this, GlyphButton.CLOSE).apply {
            background = pill(0x59000000, 0x33FFFFFF)
            contentDescription = "Cancel"
            setOnClickListener { cancel() }
        }
        controls.addView(close, FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.TOP or Gravity.START))

        // Home toggle and Set wallpaper top right, so nothing covers Home's dock
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        homeToggle = GlyphButton(this, GlyphButton.HOME).apply {
            background = pill(0x59000000, 0x33FFFFFF)
            contentDescription = "Hide Home screen"
            visibility = View.INVISIBLE
            setOnClickListener { toggleHome() }
        }
        actions.addView(homeToggle, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { marginEnd = dp(8f) })
        controls.addView(actions, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))

        hint = TextView(this).apply {
            text = "Pinch and drag to adjust"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Fonts.ui(this@WallpaperActivity, 500)
            setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
            background = pill(0x59000000, 0)
            alpha = 0f
        }
        controls.addView(hint, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        apply = TextView(this).apply {
            text = "Set wallpaper"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Fonts.ui(this@WallpaperActivity, 600)
            gravity = Gravity.CENTER
            setPadding(dp(20f), 0, dp(20f), 0)
            background = pill(Color.WHITE, 0x22000000)
            isEnabled = false
            setOnClickListener { showSheet() }
        }
        actions.addView(apply, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44f)))
        root.addView(controls, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            (close.layoutParams as FrameLayout.LayoutParams).setMargins(dp(16f), bars.top + dp(12f), 0, 0)
            (actions.layoutParams as FrameLayout.LayoutParams).setMargins(0, bars.top + dp(12f), dp(16f), 0)
            (hint.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + dp(68f)
            controls.requestLayout()
            sheet?.inset(bars.bottom)
            insets
        }
        setContentView(root)

        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (sheet != null) hideSheet() else cancel()
        }
        load(uri)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
        crop.removeCallbacks(settle)
        preview?.destroy()
    }

    /** Decodes no larger than [MAX_ZOOM] needs, so a 200 MP photo doesn't take the whole heap. */
    private fun load(uri: Uri) {
        val (w, h) = displaySize()
        io.execute {
            val bmp = try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                    val s = max(w / info.size.width.toFloat(), h / info.size.height.toFloat()) * MAX_ZOOM
                    if (s < 1f) dec.setTargetSize(max(1, (info.size.width * s).roundToInt()), max(1, (info.size.height * s).roundToInt()))
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: Exception) {
                android.util.Log.w("OneUiHome", "wallpaper: can't read $uri", e)
                null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (bmp == null) {
                    Toast.makeText(this, "Couldn't open that image", Toast.LENGTH_SHORT).show()
                    cancel()
                    return@runOnUiThread
                }
                crop.bitmap = bmp
                crop.scaleX = 1.04f; crop.scaleY = 1.04f
                crop.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(350).setInterpolator(Easing.STANDARD).start()
                apply.isEnabled = true
                loadHome()
                hint.animate().alpha(1f).setDuration(200).setStartDelay(250).withEndAction {
                    hint.animate().alpha(0f).setDuration(300).setStartDelay(HINT_MS).start()
                }.start()
            }
        }
    }

    /** Reads Home's first page (as Wallpaper and style's preview does) and lays it over the photo. */
    private fun loadHome() {
        val p = StylePreview(this)
        preview = p
        p.load { settled() }
    }

    private val settle = Runnable { refreshHome() }

    /** The photo stopped moving: Home follows the new framing a moment later. */
    private fun settled() {
        crop.removeCallbacks(settle)
        crop.postDelayed(settle, SETTLE_MS)
    }

    /**
     * Frosted plates, folders, widgets and the search pill blur the photo as framed now; labels turn dark on a light
     * part and palette-tinted icons preview the new photo's Material You accent. Home is rebuilt only when brightness
     * or the estimated accent changes noticeably.
     */
    private fun refreshHome() {
        val p = preview ?: return
        if (crop.bitmap == null || isDestroyed) return
        val full = crop.render(p.width, p.height)
        p.rebuildGlass(full)
        val wantPalette = look.themeColor == 0 && com.oneuiclone.home.icons.IconFactory.usesPalette(look.themed, look.dark, look.minimal, look.look)
        io.execute {
            val small = Bitmap.createScaledBitmap(full, max(1, full.width / 8), max(1, full.height / 8), true)
            val wc = WallpaperColors.fromBitmap(small)
            val light = (wc.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
            val est = if (wantPalette) com.oneuiclone.home.icons.IconFactory.estimateAccent(wc) else 0
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val accentChanged = wantPalette && (previewAccent == 0 || !com.oneuiclone.home.icons.IconFactory.accentSimilar(est, previewAccent))
                if (bright != null && light == bright && !accentChanged) return@runOnUiThread
                bright = light
                if (accentChanged) previewAccent = est
                val effective = if (wantPalette && previewAccent != 0) look.copy(themeColor = previewAccent) else look
                p.homeView(effective, light) { v ->
                    homeLayer.removeAllViews()
                    homeLayer.addView(v, FrameLayout.LayoutParams(p.width, p.height))
                    if (homeToggle.visibility != View.VISIBLE) {
                        homeToggle.visibility = View.VISIBLE
                        homeLayer.animate().alpha(1f).setDuration(250).setInterpolator(Easing.STANDARD).start()
                    }
                }
            }
        }
    }

    /** Home over the photo, or the photo alone. */
    private fun toggleHome() {
        val show = homeLayer.alpha < 0.5f
        homeLayer.animate().cancel()
        homeLayer.animate().alpha(if (show) 1f else 0f).setDuration(200).setInterpolator(Easing.STANDARD).start()
        homeToggle.on = show
        homeToggle.contentDescription = if (show) "Hide Home screen" else "Show Home screen"
    }

    /** Portrait size of the whole display, as the wallpaper is drawn (behind the system bars and cutout). */
    private fun displaySize(): Pair<Int, Int> {
        val b = windowManager.maximumWindowMetrics.bounds
        return min(b.width(), b.height()) to max(b.width(), b.height())
    }

    /** Tapping the photo hides everything for an unobstructed look, as a preview. */
    private fun toggleControls() {
        if (sheet != null || busy) return
        val show = controls.alpha < 0.5f
        controls.animate().cancel()
        if (show) controls.visibility = View.VISIBLE
        controls.animate().alpha(if (show) 1f else 0f).setDuration(200).setInterpolator(Easing.STANDARD)
            .withEndAction { if (!show) controls.visibility = View.INVISIBLE }.start()
    }

    private fun showSheet() {
        if (sheet != null || busy || crop.bitmap == null) return
        val s = Sheet(this)
        sheet = s
        (window.decorView as FrameLayout).addView(s, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        window.decorView.rootWindowInsets?.let { s.inset(it.getInsets(WindowInsets.Type.systemBars()).bottom) }
        s.show()
    }

    private fun hideSheet(then: (() -> Unit)? = null) {
        val s = sheet ?: return
        sheet = null
        s.hide { (window.decorView as FrameLayout).removeView(s); then?.invoke() }
    }

    private fun setWallpaper(which: Int) {
        if (busy) return
        busy = true
        hint.animate().cancel(); hint.alpha = 0f
        apply.text = "Setting wallpaper…"
        apply.isEnabled = false
        val (w, h) = displaySize()
        val out = crop.render(w, h)
        val lw = com.oneuiclone.home.ui.LiveWallpaper
        // Home's picture goes on as Launchii's live wallpaper: only the copy is written before Android's preview, as
        // an image wallpaper set first changes the colours and restarts the preview mid-connect (it crashed on AOSP)
        val offerLive = which and WallpaperManager.FLAG_SYSTEM != 0 && lw.wanted(this) && !lw.isActive(this)
        io.execute {
            val ok = try {
                if (!offerLive) WallpaperLayer.setDisplayBitmap(this, out, which)
                else {
                    WallpaperLayer.recordOwnCopy(this, out) { WallpaperManager.getInstance(this).getWallpaperId(WallpaperManager.FLAG_SYSTEM) }
                    if (which and WallpaperManager.FLAG_LOCK != 0) WallpaperLayer.setDisplayBitmap(this, out, WallpaperManager.FLAG_LOCK)
                }
                // Home's hold menu offers the last few back (lock-only sets don't count)
                try { com.oneuiclone.home.ui.WallpaperHistory.add(this, out, which) } catch (e: Exception) {
                    android.util.Log.w("OneUiHome", "wallpaper history: add failed", e)
                }
                true
            } catch (e: Exception) {
                android.util.Log.w("OneUiHome", "set wallpaper failed", e)
                false
            }
            runOnUiThread {
                if (!ok) {
                    busy = false
                    apply.text = "Set wallpaper"
                    apply.isEnabled = true
                    Toast.makeText(this, "Couldn't set that wallpaper", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                setResult(RESULT_OK)
                setWhich = which
                if (offerLive) offerLive() else { blurHome(); done() }
            }
        }
    }

    /**
     * Home's wallpaper blur goes on now rather than when Home next resumes, so Wallpaper and style's preview (which
     * reloads on the change) shows Home as it will be.
     */
    private fun blurHome() {
        if (setWhich and WallpaperManager.FLAG_SYSTEM != 0) com.oneuiclone.home.ui.WallpaperBlur.apply(
            applicationContext, com.oneuiclone.home.settings.Prefs(this).homeBlurLevel, onlyIfReplaced = true) {}
    }

    /**
     * Where the wallpaper was set ([setWallpaper]'s which), for [done] after the live wallpaper preview. Kept across
     * the recreate a new wallpaper's colours cause.
     */
    private var setWhich = 0

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_WHICH, setWhich)
    }

    /**
     * Home's wallpaper goes on as Launchii's live wallpaper (so it zooms with app opens and closes). Android asks
     * once, in its live wallpaper preview; afterwards new pictures keep it. Declining there stops asking (Settings ›
     * Live wallpaper turns it back on).
     */
    private fun offerLive() {
        try {
            startActivityForResult(com.oneuiclone.home.ui.LiveWallpaper.pickerIntent(this), REQUEST_LIVE)
        } catch (_: android.content.ActivityNotFoundException) {
            onActivityResult(REQUEST_LIVE, RESULT_CANCELED, null)
        }
    }

    /** Back from the live wallpaper preview: declined, the picture goes on as an image wallpaper instead. */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_LIVE) return
        if (com.oneuiclone.home.ui.LiveWallpaper.isActive(this)) { blurHome(); done(); return }
        com.oneuiclone.home.ui.LiveWallpaper.setWanted(this, false)
        val app = applicationContext
        io.execute {
            val ok = try {
                val bmp = android.graphics.BitmapFactory.decodeFile(java.io.File(filesDir, WallpaperLayer.OWN_FILE).path)
                bmp != null && WallpaperLayer.setHomeBitmap(app, bmp) > 0
            } catch (e: Exception) {
                android.util.Log.w("OneUiHome", "set wallpaper failed", e)
                false
            }
            runOnUiThread {
                if (!ok) Toast.makeText(app, "Couldn't set that wallpaper", Toast.LENGTH_SHORT).show()
                blurHome()
                done()
            }
        }
    }

    /**
     * Show it where it was set: Home for the home screen, back to the app for a lock-screen-only one (or back to
     * Wallpaper and style, whose preview shows either).
     */
    private fun done() {
        val stay = intent.getBooleanExtra(EXTRA_STAY, false)
        if (!stay && setWhich and WallpaperManager.FLAG_SYSTEM != 0) {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else if (!stay) {
            Toast.makeText(this, "Lock screen wallpaper set", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun pill(color: Int, ripple: Int): android.graphics.drawable.Drawable {
        val shape = GradientDrawable().apply { cornerRadius = dp(100f).toFloat(); setColor(color) }
        return if (ripple == 0) shape else RippleDrawable(ColorStateList.valueOf(ripple), shape, null)
    }

    /** Bottom sheet: where to set it. */
    @SuppressLint("ViewConstructor")
    private inner class Sheet(context: Context) : FrameLayout(context) {
        private val scrim = View(context).apply { setBackgroundColor(0x66000000); alpha = 0f }
        private val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(24f), dp(8f), dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = dp(28f).toFloat()
                setColor(if (night) 0xFF1E1E20.toInt() else Color.WHITE)
            }
            elevation = dp(8f).toFloat()
            isClickable = true
        }
        private val fg = if (night) Color.WHITE else 0xFF111111.toInt()
        private val sub = if (night) 0xFF9A9A9E.toInt() else 0xFF6E6E73.toInt()

        init {
            addView(scrim, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            scrim.setOnClickListener { hideSheet() }
            card.addView(TextView(context).apply {
                text = "Set wallpaper on"
                setTextColor(fg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Fonts.ui(context, 600)
                setPadding(dp(16f), 0, dp(16f), dp(14f))
            })
            option(GlyphButton.HOME, "Home screen", WallpaperManager.FLAG_SYSTEM)
            option(GlyphButton.LOCK, "Lock screen", WallpaperManager.FLAG_LOCK)
            option(GlyphButton.BOTH, "Home and lock screens", WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            addView(card, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }

        private fun option(glyph: Int, label: String, which: Int) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), 0, dp(16f), 0)
                background = RippleDrawable(ColorStateList.valueOf(if (night) 0x26FFFFFF else 0x1A000000),
                    null, GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(Color.WHITE) })
                setOnClickListener { hideSheet { setWallpaper(which) } }
            }
            row.addView(GlyphButton(context, glyph).apply {
                color = fg
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (night) 0x1FFFFFFF else 0x0F000000) }
                isClickable = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(40f), dp(40f)))
            row.addView(TextView(context).apply {
                text = label
                setTextColor(fg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Fonts.ui(context, 500)
                setPadding(dp(16f), 0, 0, 0)
            })
            card.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, dp(60f)))
        }

        fun inset(bottom: Int) {
            (card.layoutParams as LayoutParams).setMargins(dp(12f), 0, dp(12f), bottom + dp(12f))
            card.requestLayout()
        }

        fun show() {
            card.translationY = dp(360f).toFloat()
            scrim.animate().alpha(1f).setDuration(200).start()
            card.animate().translationY(0f).setDuration(350).setInterpolator(Easing.STANDARD).start()
            controls.animate().alpha(0f).setDuration(150).start()
        }

        fun hide(end: () -> Unit) {
            scrim.animate().alpha(0f).setDuration(200).start()
            card.animate().translationY((card.height + dp(24f)).toFloat()).setDuration(200).setInterpolator(Easing.SINE_IN_70)
                .withEndAction(end).start()
            controls.visibility = View.VISIBLE
            controls.animate().alpha(1f).setDuration(200).start()
        }
    }

    /** Small line icons, drawn so they need no resources: close, and phone-with-home / lock / both. */
    private class GlyphButton(context: Context, private val glyph: Int) : View(context) {
        companion object { const val CLOSE = 0; const val HOME = 1; const val LOCK = 2; const val BOTH = 3 }
        var color = Color.WHITE
            set(v) { field = v; invalidate() }
        /** Off: struck through (Home hidden). */
        var on = true
            set(v) { field = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val d = resources.displayMetrics.density

        override fun onDraw(c: Canvas) {
            p.color = color
            p.strokeWidth = 1.8f * d
            val cx = width / 2f; val cy = height / 2f
            when (glyph) {
                CLOSE -> {
                    val r = 6.5f * d
                    c.drawLine(cx - r, cy - r, cx + r, cy + r, p); c.drawLine(cx + r, cy - r, cx - r, cy + r, p)
                }
                BOTH -> { phone(c, cx - 4.5f * d, cy, HOME, 0.7f); phone(c, cx + 4.5f * d, cy, LOCK, 0.7f) }
                else -> phone(c, cx, cy, glyph, 1f)
            }
            if (!on) {
                val r = 9f * d
                c.drawLine(cx - r, cy - r, cx + r, cy + r, p)
            }
        }

        private fun phone(c: Canvas, cx: Float, cy: Float, inner: Int, s: Float) {
            val hw = 6f * d * s; val hh = 10f * d * s
            c.drawRoundRect(cx - hw, cy - hh, cx + hw, cy + hh, 2.5f * d * s, 2.5f * d * s, p)
            if (inner == HOME) {
                // a 2×2 app grid
                val g = 1.6f * d * s
                for (x in intArrayOf(-1, 1)) for (y in intArrayOf(-1, 1))
                    c.drawPoint(cx + x * g * 1.2f, cy - 1.5f * d * s + y * g * 1.2f, p)
            } else {
                // padlock
                val bw = 2.6f * d * s
                c.drawRect(cx - bw, cy - 0.5f * d * s, cx + bw, cy + 3.5f * d * s, p)
                c.drawArc(cx - 1.6f * d * s, cy - 4f * d * s, cx + 1.6f * d * s, cy + 0.6f * d * s, 180f, 180f, false, p)
            }
        }
    }

    /**
     * The photo, always covering the screen: drag to move it, pinch (or double-tap) to zoom. Shown at the system's
     * own home wallpaper scale ([WallpaperLayer.systemScale]), so what's on screen is exactly how Home will look;
     * [render] sets the full unscaled frame.
     */
    private class CropView(context: Context) : View(context) {
        var bitmap: Bitmap? = null
            set(v) { field = v; reset() }
        var onTap: (() -> Unit)? = null
        /** A drag, pinch or double-tap zoom has finished. */
        var onSettled: (() -> Unit)? = null

        private var scale = 1f
        private var minScale = 1f
        private var tx = 0f
        private var ty = 0f
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var anim: ValueAnimator? = null
        private val preview = WallpaperLayer.systemScale()
        /** Screen point → frame point (undoes [preview] about the centre). */
        private fun fx(x: Float) = (x - width / 2f) / preview + width / 2f
        private fun fy(y: Float) = (y - height / 2f) / preview + height / 2f

        private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(g: ScaleGestureDetector): Boolean {
                zoomTo(scale * g.scaleFactor, fx(g.focusX), fy(g.focusY))
                return true
            }
        })
        private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { anim?.cancel(); return true }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                tx -= dx / preview; ty -= dy / preview
                clamp(); invalidate()
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap?.invoke(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                animateZoom(if (scale > minScale * 1.05f) minScale else minScale * 2f, fx(e.x), fy(e.y))
                return true
            }
        })

        private fun reset() {
            val b = bitmap ?: return
            if (width == 0 || height == 0) return
            minScale = max(width / b.width.toFloat(), height / b.height.toFloat())
            scale = minScale
            tx = (width - b.width * scale) / 2f
            ty = (height - b.height * scale) / 2f
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = reset()

        private fun zoomTo(s: Float, fx: Float, fy: Float) {
            val n = s.coerceIn(minScale, minScale * MAX_ZOOM)
            val k = n / scale
            tx = fx - (fx - tx) * k
            ty = fy - (fy - ty) * k
            scale = n
            clamp(); invalidate()
        }

        private fun animateZoom(target: Float, fx: Float, fy: Float) {
            anim?.cancel()
            anim = ValueAnimator.ofFloat(scale, target).apply {
                duration = 300
                interpolator = Easing.STANDARD
                addUpdateListener { zoomTo(it.animatedValue as Float, fx, fy) }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) { onSettled?.invoke() }
                })
                start()
            }
        }

        private fun clamp() {
            val b = bitmap ?: return
            tx = tx.coerceIn(width - b.width * scale, 0f)
            ty = ty.coerceIn(height - b.height * scale, 0f)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (bitmap == null) return false
            pinch.onTouchEvent(e)
            if (!pinch.isInProgress) gestures.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) onSettled?.invoke()
            return true
        }

        override fun onDraw(c: Canvas) {
            val b = bitmap ?: return
            c.save()
            c.scale(preview, preview, width / 2f, height / 2f)
            c.translate(tx, ty)
            c.scale(scale, scale)
            c.drawBitmap(b, 0f, 0f, paint)
            c.restore()
        }

        /** The visible part of the photo at [w]×[h]. */
        fun render(w: Int, h: Int): Bitmap {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                scale(w / width.toFloat(), h / height.toFloat())
                drawColor(Color.BLACK)
                // without the view's own open-animation scale, which may still be running
                translate(tx, ty)
                scale(scale, scale)
                drawBitmap(bitmap!!, 0f, 0f, paint)
            }
            return out
        }
    }
}
