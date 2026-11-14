package com.oneuiclone.home.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.oneuiclone.home.device.DeviceProfile

/**
 * One UI 8.5 floating apps-screen search bar: 244×52dp pill, 32dp radius, white-tinted glass,
 * 1dp border, 24dp magnifier, 16dp text start padding, 15sp hint (search_bar_* dimens). Dark glass in dark mode.
 */
class SearchPill(context: Context, private val dp: DeviceProfile, private val hint: String, private val circle: Boolean = false) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp.dp(1f)
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x00000000
        setShadowLayer(dp.dp(8f), 0f, dp.dp(2f), 0x1F000000)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * resources.displayMetrics.scaledDensity
        typeface = Fonts.ui(context, 400)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp.dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    init {
        isClickable = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
        applyColors()
    }

    private fun applyColors() {
        val c = SearchColors.of(context)
        fill.color = c.pill
        border.color = c.stroke
        text.color = c.hint
        glyph.color = c.hint
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyColors()
        invalidate()
    }

    /** The drawn pill (1dp inside the view) as the search bar's start / end state; [left]/[top] in root coordinates. */
    fun origin(left: Float, top: Float): AppsSearch.Origin {
        val i = dp.dp(1f)
        return AppsSearch.Origin(
            RectF(left + i, top + i, left + dp.searchBarWidth - i, top + dp.searchBarHeight - i), fill.color, border.color, dp.dp(2f),
            dp.dp(16f) - i, dp.dp(24f), dp.dp(16f + 24f + 6f) - i, text.textSize,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val inset = dp.dp(1f)
        rect.set(inset, inset, width - inset, height - inset)
        val r = minOf(dp.searchBarRadius, rect.height() / 2f)
        canvas.drawRoundRect(rect, r, r, shadow)
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, border)
        if (circle) {
            // ⋮ more button (SupportFloatingMoreMenu)
            val cx = width / 2f; val cy = height / 2f
            glyph.style = Paint.Style.FILL
            for (i in -1..1) canvas.drawCircle(cx, cy + i * dp.dp(6f), dp.dp(2.2f), glyph)
            glyph.style = Paint.Style.STROKE
            return
        }
        // magnifier glyph (24dp box)
        val box = dp.dp(24f)
        val gx = dp.dp(16f)
        val gy = (height - box) / 2f
        val cr = box * 0.30f
        val cx = gx + box * 0.42f
        val cy = gy + box * 0.42f
        canvas.drawCircle(cx, cy, cr, glyph)
        canvas.drawLine(cx + cr * 0.72f, cy + cr * 0.72f, gx + box * 0.86f, gy + box * 0.86f, glyph)
        val fm = text.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(hint, gx + box + dp.dp(6f), baseline, text)
    }
}

/**
 * Search bar palette: light glass with grey ink by day, dark glass with light ink in dark mode. The open bar is
 * frosted: [frost] over the blurred wallpaper ([frostBare] when there's no wallpaper copy to blur, so it's a little
 * milkier over the window blur instead), edged by [frostStroke].
 */
class SearchColors private constructor(
    val pill: Int, val homePill: Int, val frost: Int, val frostBare: Int, val frostStroke: Int,
    val stroke: Int, val hint: Int, val text: Int,
) {
    companion object {
        private val DAY = SearchColors(0xD9FFFFFF.toInt(), 0x99FFFFFF.toInt(), 0x3DFFFFFF, 0x52FFFFFF, 0x59FFFFFF,
            0x33FFFFFF, 0xFF5C5C61.toInt(), 0xFF1C1C1E.toInt())
        private val NIGHT = SearchColors(0xD92A2A2E.toInt(), 0x992A2A2E.toInt(), 0x6B1C1C1E, 0x8C1C1C1E.toInt(), 0x29FFFFFF,
            0x1FFFFFFF, 0xFFA9A9AE.toInt(), 0xFFFAFAFA.toInt())

        fun of(context: Context) = if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES) NIGHT else DAY
    }
}
