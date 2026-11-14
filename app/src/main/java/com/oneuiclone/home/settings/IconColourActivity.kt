package com.oneuiclone.home.settings

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Icons › Icon colour (Themed and Frosted): its own page, so the picker isn't a long scroll under the other rows.
 * The dock strip stays fixed at the top; under it, Colour palette (your phone's palette colour, the default), twelve
 * ready-made colours, and a saturation / brightness square over a hue bar for any other.
 *
 * The strip follows every touch at once: when the page opens, it's drawn in the background with black plates, white
 * plates and the palette's colour, and any other colour is mixed from the first two ([StylePreview.Recolour]).
 * Until those are ready (a moment after opening), it's drawn in the colour outright, at most every 60 ms.
 */
class IconColourActivity : OneUiPage() {

    companion object {
        /** Ready-made colours, about as bright as the palette's accent (system_accent1_400). */
        private val PRESETS = intArrayOf(
            0xFFE5534B.toInt(), 0xFFEF7A3C.toInt(), 0xFFD9A021.toInt(), 0xFF5FA846.toInt(), 0xFF2E9E6B.toInt(), 0xFF1F9C9C.toInt(),
            0xFF3B9EDB.toInt(), 0xFF3D73E0.toInt(), 0xFF6A5ACD.toInt(), 0xFF9B59C9.toInt(), 0xFFE0528F.toInt(), 0xFF6B6F76.toInt(),
        )

        /** Icon colour's summary for the saved colour [v] (0 = the palette's). */
        fun name(v: Int) = if (v == 0) "Colour palette" else "Custom colour"
    }

    private lateinit var preview: StylePreview
    private lateinit var dockStrip: PreviewCard
    private var built = false
    /** The icon colour while it's dragged, previewed before it's saved (0 = the palette's). */
    private var liveColour: Int? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var colourQueued = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = StylePreview(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preview.destroy()
    }

    override fun build() {
        if (!built) { built = true; layout() }
        preview.load { refreshDock() }
    }

    private fun stripWidth() = resources.displayMetrics.widthPixels - dp(12f) * 2 - dp(14f) * 2

    /** The saved icon settings the strip shows (the colour aside). */
    private fun stripLook() = StylePreview.Look.of(prefs)

    private fun refreshDock() {
        val at = mixedAt
        preview.render(stripLook().copy(themeColor = liveColour ?: prefs.themeColor), stripWidth() / 3, stripWidth()) { _, dock ->
            // a colour mixed in since this was asked for is newer: keep it
            if (at == mixedAt) dockStrip.bitmap = dock
            prepareMix()
        }
    }

    /** Mixes any picked colour into the dock strip at once ([StylePreview.Recolour]); null until made, or when stale ([mixFor]). */
    private var mix: StylePreview.Recolour? = null
    /** The dock strip in the colour palette's colour, made with [mix]. */
    private var paletteStrip: android.graphics.Bitmap? = null
    /** What [mix] was made for, and what's being made now. */
    private var mixFor: Any? = null
    private var mixMaking: Any? = null
    /** Counts colours mixed in, so a slower full drawing asked for earlier doesn't cover a newer one. */
    private var mixedAt = 0

    /** Everything but the colour that the strip depends on: [mix] is only good while this stays the same. */
    private fun mixKey() = listOf(stripLook().copy(themeColor = 0), preview.version, stripWidth())

    /**
     * Makes [mix] (and [paletteStrip]) for the strip as it is now, if it isn't made yet: the strip drawn three times,
     * with black plates, white plates and the colour palette's, in the background.
     */
    private fun prepareMix() {
        if (!::dockStrip.isInitialized) return
        val key = mixKey()
        if (key == mixFor || key == mixMaking) return
        mixMaking = key
        val base = stripLook()
        preview.dockStrips(listOf(base.copy(themeColor = Color.BLACK), base.copy(themeColor = Color.WHITE), base.copy(themeColor = 0)),
            stripWidth()) { s ->
            if (mixMaking == key) mixMaking = null
            // something else changed while these were drawn: make them again
            if (mixKey() != key) { prepareMix(); return@dockStrips }
            mix = StylePreview.Recolour(s[0], s[1]).also { it.mix(Color.GRAY) } // a first run, so the first touch is quick too
            paletteStrip = s[2]; mixFor = key
        }
    }

    /**
     * Icon colour [c] (0 = the palette's) in the dock strip; saved when [done]. Once [mix] is made, it's mixed in at
     * once, every touch; before that, the icons are drawn in it at most every 60 ms.
     */
    private fun colour(c: Int, done: Boolean) {
        val m = mix
        if (m != null && mixFor == mixKey()) {
            mixedAt++
            dockStrip.bitmap = if (c == 0) paletteStrip else m.mix(c)
            liveColour = if (done) null else c
            if (done) prefs.themeColor = c
            return
        }
        if (done) {
            liveColour = null; prefs.themeColor = c; refreshDock(); return
        }
        liveColour = c
        if (colourQueued) return
        colourQueued = true
        main.postDelayed({ colourQueued = false; if (liveColour != null) refreshDock() }, 60)
    }

    /** Colour palette, the ready-made colours, then the square and the hue bar, in one card. */
    private fun pickerCard() = card {
        val c = current ?: return@card
        val paletteColour = getColor(android.R.color.system_accent1_400)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(14f), dp(20f), dp(18f))
        }
        val hsv = FloatArray(3).also { Color.colorToHSV(if (prefs.themeColor != 0) prefs.themeColor else paletteColour, it) }
        val square = SvSquare(this, d)
        val hue = HueBar(this, d)
        val dots = ArrayList<SwatchDot>()
        lateinit var paletteChip: android.widget.TextView
        var picked = prefs.themeColor
        fun mark() {
            val on = picked == 0
            paletteChip.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 100f * d
                setColor(if (on) (primary and 0x00FFFFFF) or 0x26000000 else if (night) 0xFF2A2A2C.toInt() else 0xFFEDEDF0.toInt())
                setStroke(dp(2f), if (on) primary else Color.TRANSPARENT)
            }
            paletteChip.setTextColor(if (on) text else sub)
            dots.forEach { it.ring = if (it.color == picked) text else 0 }
        }
        fun show() { square.set(hsv); hue.set(hsv[0]) }
        /** A colour from the square or the hue bar: previewed while dragged, saved on letting go. */
        fun custom(done: Boolean) {
            picked = Color.HSVToColor(hsv)
            mark()
            colour(picked, done)
        }
        fun use(v: Int) {
            picked = v
            Color.colorToHSV(if (v == 0) paletteColour else v, hsv)
            show(); mark()
            colour(v, true)
        }
        paletteChip = android.widget.TextView(this).apply {
            text = "Colour palette"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = com.oneuiclone.home.ui.Fonts.ui(this@IconColourActivity, 500)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(8f), dp(16f), dp(8f))
            compoundDrawablePadding = dp(8f)
            setCompoundDrawablesRelative(android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
                paint.color = paletteColour; setBounds(0, 0, dp(20f), dp(20f))
            }, null, null, null)
            setOnClickListener { if (picked != 0) use(0) }
        }
        panel.addView(paletteChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12f) })
        for (row in PRESETS.toList().chunked(6)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (v in row) {
                val dot = SwatchDot(this).apply {
                    color = v
                    contentDescription = "Colour"
                    setOnClickListener { if (picked != v) use(v) }
                }
                dots.add(dot)
                line.addView(FrameLayout(this).apply {
                    addView(dot, FrameLayout.LayoutParams(dp(40f), dp(40f), android.view.Gravity.CENTER))
                }, LinearLayout.LayoutParams(0, dp(48f), 1f))
            }
            panel.addView(line)
        }
        square.onPick = { s, v, done -> hsv[1] = s; hsv[2] = v; custom(done) }
        hue.onPick = { h, done -> hsv[0] = h; square.set(hsv); custom(done) }
        panel.addView(square, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180f)).apply { topMargin = dp(14f) })
        panel.addView(hue, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28f)).apply { topMargin = dp(16f) })
        show(); mark()
        c.addView(panel)
    }

    private fun layout() {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        // fixed: the app bar and the dock
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), 0) }
        appBar("Icon colour")
        card {
            dockStrip = PreviewCard(this@IconColourActivity, dp(20f).toFloat(), bg).apply { contentDescription = "Your dock in this colour" }
            current?.addView(FrameLayout(this@IconColourActivity).apply {
                setPadding(dp(14f), dp(4f), dp(14f), dp(4f))
                addView(dockStrip, FrameLayout.LayoutParams(stripWidth(), preview.dockHeight(stripWidth())))
            })
        }
        page.addView(list)

        // the picker, scrolling only if larger text makes it taller than the screen
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(24f)) }
        pickerCard()
        val scroll = ScrollView(this).apply { addView(list); isVerticalScrollBarEnabled = false; clipToPadding = false }
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        page.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            scroll.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        setContentView(page)
    }
}

/** A colour as a round swatch, with a [ring] (0 = none) around it when it's the one chosen. */
internal class SwatchDot(context: android.content.Context) : View(context) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    var color = 0; set(v) { field = v; invalidate() }
    var ring = 0; set(v) { field = v; invalidate() }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val r = minOf(width, height) / 2f
        val dpx = resources.displayMetrics.density
        p.style = android.graphics.Paint.Style.FILL; p.color = color
        canvas.drawCircle(width / 2f, height / 2f, if (ring != 0) r - 5f * dpx else r, p)
        if (ring != 0) {
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 2f * dpx; p.color = ring
            canvas.drawCircle(width / 2f, height / 2f, r - 1f * dpx, p)
        }
    }
}

/** Saturation (left to right) and brightness (bottom to top) for one hue, with a thumb at the colour. */
internal class SvSquare(context: android.content.Context, private val dpx: Float) : View(context) {
    var onPick: ((Float, Float, Boolean) -> Unit)? = null
    private val hsv = floatArrayOf(0f, 1f, 1f)
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val thumb = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()
    private val clip = android.graphics.Path()
    private val corner = 16f * dpx

    fun set(c: FloatArray) { c.copyInto(hsv); fill.shader = null; invalidate() }

    override fun onDraw(canvas: android.graphics.Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        if (fill.shader == null) {
            val pure = android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
            val across = android.graphics.LinearGradient(0f, 0f, rect.right, 0f, android.graphics.Color.WHITE, pure, android.graphics.Shader.TileMode.CLAMP)
            val down = android.graphics.LinearGradient(0f, 0f, 0f, rect.bottom, android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK, android.graphics.Shader.TileMode.CLAMP)
            fill.shader = android.graphics.ComposeShader(across, down, android.graphics.PorterDuff.Mode.SRC_OVER)
        }
        canvas.drawRoundRect(rect, corner, corner, fill)
        val x = hsv[1] * rect.right; val y = (1f - hsv[2]) * rect.bottom
        thumb.style = android.graphics.Paint.Style.FILL; thumb.color = android.graphics.Color.HSVToColor(hsv)
        canvas.drawCircle(x, y, 11f * dpx, thumb)
        thumb.style = android.graphics.Paint.Style.STROKE; thumb.strokeWidth = 3f * dpx; thumb.color = android.graphics.Color.WHITE
        canvas.drawCircle(x, y, 11f * dpx, thumb)
        thumb.strokeWidth = 1f * dpx; thumb.color = 0x33000000
        canvas.drawCircle(x, y, 12.5f * dpx, thumb)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { fill.shader = null }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        hsv[1] = (e.x / width).coerceIn(0f, 1f)
        hsv[2] = (1f - e.y / height).coerceIn(0f, 1f)
        invalidate()
        val done = e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL
        onPick?.invoke(hsv[1], hsv[2], done)
        return true
    }
}

/** Every hue in a rounded bar, with a thumb at the chosen one. */
internal class HueBar(context: android.content.Context, private val dpx: Float) : View(context) {
    var onPick: ((Float, Boolean) -> Unit)? = null
    private var hue = 0f
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val thumb = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()

    fun set(h: Float) { hue = h; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val stops = IntArray(7) { android.graphics.Color.HSVToColor(floatArrayOf(it * 60f % 360f, 1f, 1f)) }
        fill.shader = android.graphics.LinearGradient(height / 2f, 0f, w - height / 2f, 0f, stops, null, android.graphics.Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, fill)
        val x = r + hue / 360f * (width - 2 * r)
        thumb.style = android.graphics.Paint.Style.FILL; thumb.color = android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        canvas.drawCircle(x, r, r - 2f * dpx, thumb)
        thumb.style = android.graphics.Paint.Style.STROKE; thumb.strokeWidth = 3f * dpx; thumb.color = android.graphics.Color.WHITE
        canvas.drawCircle(x, r, r - 2f * dpx, thumb)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        val r = height / 2f
        hue = ((e.x - r) / (width - 2 * r) * 360f).coerceIn(0f, 359.9f)
        invalidate()
        val done = e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL
        onPick?.invoke(hue, done)
        return true
    }
}
