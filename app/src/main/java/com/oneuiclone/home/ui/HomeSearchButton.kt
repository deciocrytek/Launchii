package com.oneuiclone.home.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.motion.Easing

/**
 * One UI 9 "Finder" entry on the Home screen: a small glass pill left of the page dots. Tapping it grows
 * into the apps-screen search bar, so its geometry is exposed as an [AppsSearch.Origin]. With no [icon] it is
 * also the text-only Update pill left of it ([com.oneuiclone.home.AppUpdate]).
 */
@SuppressLint("ViewConstructor")
class HomeSearchButton(
    context: Context,
    private val dp: DeviceProfile,
    label: String,
    private val icon: ((Canvas, Float, Float, Float, Paint) -> Unit)? = MagnifierView::draw,
) : View(context) {

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * resources.displayMetrics.scaledDensity
        typeface = Fonts.ui(context, 400)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp.dp(1f)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    /**
     * Folder glass (as a folder set to Style › Blur): the blurred wallpaper behind the pill under the folder colour
     * at [glassFill]. Fixed to the window, so the pill redraws whenever it moves on screen.
     */
    var glass: WidgetGlass? = null
        set(v) {
            if (field === v) return
            if (isAttachedToWindow) { field?.views?.remove(this); v?.views?.add(this) }
            field = v; applyColors(); invalidate()
        }
    /** How see-through the glass pill's tint is (0..1), as a folder's Style › Blur transparency. */
    var glassTransparency = 0f
        set(v) { field = v; applyColors(); invalidate() }

    /**
     * The glass pill's tint: the default folder colour in light mode; in dark mode a dark one, like widgets showing
     * their dark version, so the pill doesn't glow on a dark Home screen.
     */
    private fun glassFill(): Int {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val base = if (night) NIGHT_GLASS_BG else FolderIconView.DEFAULT_BG
        return (base and 0x00FFFFFF) or ((android.graphics.Color.alpha(base) * (1f - glassTransparency)).toInt() shl 24)
    }
    private val glassPaint = GlassPaint()
    private val toWindow = android.graphics.Matrix()
    private val drawnAt = android.graphics.Matrix()
    private val moved = android.view.ViewTreeObserver.OnPreDrawListener {
        if (glass != null) {
            toWindow.reset(); transformMatrixToGlobal(toWindow)
            if (toWindow != drawnAt) invalidate()
        }
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glass?.views?.add(this)
        viewTreeObserver.addOnPreDrawListener(moved)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(moved)
        glass?.views?.remove(this)
        super.onDetachedFromWindow()
    }

    val pillHeight = dp.dpi(32f)
    private val glyphLeft = dp.dp(12f)
    private val glyphBox = dp.dp(16f)
    /** After the glyph, or as far in as the end padding when there is none, so the label sits centred. */
    private val textStart = if (icon != null) glyphLeft + glyphBox + dp.dp(6f) else dp.dp(14f)
    var label = label
        set(v) { if (field != v) { field = v; contentDescription = v; invalidate() } }
    /** The width [label] needs; the owner resizes the view to it. */
    val pillWidth get() = pillWidth(label)
    fun pillWidth(label: String) = (textStart + text.measureText(label) + dp.dp(14f)).toInt()
    /** 0..1 fills the pill from the left (a download), or < 0 for none. */
    var progress = -1f
        set(v) { if (field != v) { field = v; invalidate() } }
    private val progressFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }

    init {
        isClickable = true
        contentDescription = label
        applyColors()
    }

    private fun applyColors() {
        val c = SearchColors.of(context)
        fill.color = if (glass != null) glassFill() else c.homePill
        border.color = c.stroke
        // on the glass the hint reads like the Home screen's labels: white
        text.color = if (glass != null) GLASS_INK else c.hint
        glyph.color = text.color
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyColors()
        invalidate()
    }

    private companion object {
        const val GLASS_INK = 0xE6FFFFFF.toInt()
        /** Dark mode's glass tint before the transparency: opaque, so at 70 % it's a 30 % dark veil. */
        const val NIGHT_GLASS_BG = 0xFF1C1C1E.toInt()
    }

    /** The pill as the search bar's start / end state; [left]/[top] are root coordinates at rest. */
    fun origin(left: Float, top: Float, fade: Boolean = false) = AppsSearch.Origin(
        RectF(left, top, left + pillWidth, top + pillHeight), fill.color, border.color, 0f,
        glyphLeft, glyphBox, textStart, text.textSize, glass, if (glass != null) text.color else null, fade,
    )

    override fun onDraw(canvas: Canvas) {
        val inset = dp.dp(0.5f)
        rect.set(inset, inset, width - inset, height - inset)
        val r = rect.height() / 2f
        drawnAt.reset(); transformMatrixToGlobal(drawnAt)
        if (glassPaint.aim(glass, drawnAt)) canvas.drawRoundRect(rect, r, r, glassPaint.paint)
        canvas.drawRoundRect(rect, r, r, fill)
        if (progress >= 0f) {
            canvas.save()
            canvas.clipRect(rect.left, rect.top, rect.left + rect.width() * progress, rect.bottom)
            canvas.drawRoundRect(rect, r, r, progressFill)
            canvas.restore()
        }
        canvas.drawRoundRect(rect, r, r, border)
        icon?.invoke(canvas, glyphLeft, (height - glyphBox) / 2f, glyphBox, glyph)
        val fm = text.fontMetrics
        canvas.drawText(label, textStart, height / 2f - (fm.ascent + fm.descent) / 2f, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.94f).scaleY(0.94f).setDuration(120).setInterpolator(Easing.BASIC).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(Easing.BASIC).start()
        }
        return super.onTouchEvent(event)
    }
}

/** Search magnifier glyph; [draw] lets pills paint the identical shape at any size. */
class MagnifierView(context: Context, color: Int) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; this.color = color
    }
    var color: Int
        get() = p.color
        set(v) { if (p.color != v) { p.color = v; invalidate() } }

    override fun onDraw(canvas: Canvas) = draw(canvas, 0f, 0f, width.toFloat(), p)

    companion object {
        fun draw(canvas: Canvas, left: Float, top: Float, s: Float, p: Paint) {
            p.strokeWidth = s * 0.085f
            val cr = s * 0.30f; val cx = left + s * 0.42f; val cy = top + s * 0.42f
            canvas.drawCircle(cx, cy, cr, p)
            canvas.drawLine(cx + cr * 0.72f, cy + cr * 0.72f, left + s * 0.86f, top + s * 0.86f, p)
        }
    }
}
