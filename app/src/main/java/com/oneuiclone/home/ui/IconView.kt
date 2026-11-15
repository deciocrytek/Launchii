package com.oneuiclone.home.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.model.HomeItem

object Fonts {
    private var cache: Typeface? = null
    private val weights = HashMap<Int, Typeface>()

    /** Inter (OFL, assets/licenses/Inter-OFL.txt) at a given weight; falls back to sans-serif. */
    fun ui(context: Context, weight: Int = 400): Typeface {
        weights[weight]?.let { return it }
        val tf = try {
            Typeface.Builder(context.assets, "fonts/Inter-VF.ttf")
                .setFontVariationSettings("'wght' $weight")
                .setWeight(weight)
                .build()
        } catch (e: Exception) {
            null
        } ?: Typeface.create(Typeface.SANS_SERIF, weight, false)
        weights[weight] = tf
        if (cache == null) cache = tf
        return tf
    }
}

/**
 * App icon + label drawn directly (no TextView) — one draw call per element, like One UI's IconViewImpl.
 */
open class IconView(context: Context, protected val dp: DeviceProfile) : View(context) {

    var item: HomeItem? = null
    /** Apps-screen icons are not workspace items; keep their app for badges. */
    var appInfo: com.oneuiclone.home.model.AppInfo? = null
    var icon: Bitmap? = null
        set(v) { field = v; invalidate() }
    var label: CharSequence = ""
        set(v) { field = v; if (width > 0) fitLabel(); requestLayout(); invalidate() }
    var showLabel = true
        set(v) { field = v; invalidate() }
    /** Used while the icon is "lifted" for drag / folder animations. */
    var iconVisible = true
        set(v) { field = v; invalidate() }
    var labelAlpha = 1f
        set(v) { field = v; invalidate() }
    /** The icon's own fade (its label keeps [labelAlpha]), for apps that come into a folder without flying there. */
    var iconAlpha = 1f
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Notification count (0 = none). [badgeNumber] false → dot. */
    var badgeCount = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var badgeNumber = true
    /** One UI "white background" mode (WhiteBgColorUpdater): dark labels without shadow on bright wallpapers. */
    var darkText = false
        set(v) {
            if (field == v) return
            field = v
            textPaint.color = if (v) 0xFF1D1D1F.toInt() else 0xFFFAFAFA.toInt()
            if (v) textPaint.clearShadowLayer() else textPaint.setShadowLayer(dp.dp(2f), 0f, 0f, 0x59000000)
            shadowAlpha = 0x59
            invalidate()
        }

    protected open val iconSize get() = dp.iconSizePx
    /** Last touch-down point inside this view (for multi-target items like large folders). */
    var lastTouchX = 0f; var lastTouchY = 0f

    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    /** Draws the icon through its shape: the Pixel 10's GPU can paint the clear corners white (ShapedIcon). */
    protected val shaped = com.oneuiclone.home.icons.ShapedIcon()
    protected val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFAFAFA.toInt()
        textSize = dp.labelTextSizePx
        typeface = Fonts.ui(context, 400)
        textAlign = Paint.Align.CENTER
        // text_shadow_radius 2 / dy 0 (One UI Home integers.xml)
        setShadowLayer(dp.dp(2f), 0f, 0f, 0x59000000)
    }
    /** Alpha of [textPaint]'s current shadow (follows [labelAlpha]). */
    private var shadowAlpha = 0x59
    private var drawnLabel: CharSequence = ""
    private val dst = Rect()

    init {
        isClickable = true
        isLongClickable = true
        isHapticFeedbackEnabled = true
    }

    /** Top of the icon inside this view: icon+gap+label block is centred vertically. */
    fun iconTop(): Int {
        val fm = textPaint.fontMetrics
        val labelH = if (showLabel) (fm.descent - fm.ascent) + dp.iconLabelGapPx else 0f
        val block = iconSize + labelH
        return ((height - block) / 2f).toInt().coerceAtLeast(0)
    }

    fun iconLeft(): Int = (width - iconSize) / 2

    /** Icon bounds in window coordinates; [part] (in icon coordinates) narrows them, e.g. to a large-folder slot. */
    fun iconBoundsOnScreen(out: Rect, part: Rect? = null) {
        val loc = IntArray(2)
        getLocationInWindow(loc)
        val sx = scaleXOnScreen()
        val l = loc[0] + (iconLeft() + (part?.left ?: 0)) * sx
        val t = loc[1] + (iconTop() + (part?.top ?: 0)) * sx
        out.set(l.toInt(), t.toInt(), (l + (part?.width() ?: iconSize) * sx).toInt(), (t + (part?.height() ?: iconSize) * sx).toInt())
    }

    private fun scaleXOnScreen(): Float {
        var s = scaleX
        var p = parent
        while (p is View) { s *= p.scaleX; p = p.parent }
        return s
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitLabel()
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) { lastTouchX = event.x; lastTouchY = event.y }
        return super.dispatchTouchEvent(event)
    }

    fun iconSizePx(): Int = iconSize

    /** One UI shrinks letter spacing (−0.4 → −0.7 steps) before ellipsizing long labels. */
    private fun fitLabel() {
        val avail = (width - dp.dp(6f)).coerceAtLeast(1f)
        textPaint.letterSpacing = 0f
        if (textPaint.measureText(label, 0, label.length) > avail) {
            textPaint.letterSpacing = -0.04f
            if (textPaint.measureText(label, 0, label.length) > avail) textPaint.letterSpacing = -0.07f
        }
        drawnLabel = TextUtils.ellipsize(label, textPaint, avail, TextUtils.TruncateAt.END)
    }

    override fun onDraw(canvas: Canvas) {
        val top = iconTop()
        val left = iconLeft()
        if (iconVisible && iconAlpha > 0f) {
            val faded = iconAlpha < 1f
            if (faded) canvas.saveLayerAlpha(null, (255 * iconAlpha).toInt())
            drawIcon(canvas, left, top); drawBadge(canvas, left, top)
            if (faded) canvas.restore()
        }
        if (showLabel && labelAlpha > 0f) {
            if (drawnLabel.isEmpty() && label.isNotEmpty()) fitLabel()
            val fm = textPaint.fontMetrics
            val baseline = top + iconSize + dp.iconLabelGapPx - fm.ascent
            textPaint.alpha = (255 * labelAlpha).toInt()
            // A translucent shadow colour ignores the paint's alpha, so a fading label showed as its
            // full dark shadow under faint text (dark labels at the start of a folder open): fade it too.
            if (!darkText) {
                val sa = (0x59 * labelAlpha).toInt()
                if (sa != shadowAlpha) { shadowAlpha = sa; textPaint.setShadowLayer(dp.dp(2f), 0f, 0f, sa shl 24) }
            }
            canvas.drawText(drawnLabel, 0, drawnLabel.length, width / 2f, baseline, textPaint)
        }
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF54D13.toInt()                                          // badge_bg_color
        setShadowLayer(dp.dp(1f), 0f, 0f, 0x33000000)                      // BADGE_SHADOW
    }
    private val badgeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFCFCFC.toInt()                                          // badge_number_color
        textAlign = Paint.Align.CENTER
        typeface = Fonts.ui(context, 600)
    }

    /**
     * One UI's home badge (NumberBadgeRenderer / DotBadgeRenderer): centred 0.3965 icon right and up of the icon's centre,
     * so it overhangs the top-right corner; circle 32.7 %, 10+ a pill whose right edge stays put; dot 12 % across, in the
     * corner. Pulled in by 0.05 when there's no room above the icon (One UI's isCutoffCondition).
     */
    private fun drawBadge(canvas: Canvas, left: Int, top: Int) {
        if (badgeCount <= 0) return
        val s = iconSize.toFloat()
        if (!badgeNumber) {
            val r = s * 0.06f
            canvas.drawCircle(left + s - r, top + r, r, badgePaint)
            return
        }
        val pullIn = if (top < s * 0.137f / 2f) 0.05f else 0f
        val cy = top + s * (0.5f - 0.3965f + pullIn)
        val d = s * 0.327f
        val txt = if (badgeCount > 999) "999+" else badgeCount.toString()
        badgeText.textSize = if (s > dp.dp(48f)) dp.dp(12f) else dp.dp(9f)
        val pad = dp.dp(2f)
        val (cx, w) = when {
            badgeCount < 10 -> s * (0.5f + 0.3965f - pullIn) to d
            badgeCount < 100 -> s * (0.5f + 0.371f - pullIn) - pad to s * 0.378f + 2 * pad
            badgeCount <= 999 -> s * (0.5f + 0.318f - pullIn) - pad to s * 0.484f + 2 * pad
            else -> s * (0.5f + 0.24f - pullIn) - pad to s * 0.64f + 2 * pad
        }
        val half = maxOf(w, badgeText.measureText(txt) + 2 * pad) / 2f
        val r = android.graphics.RectF(left + cx - half, cy - d / 2f, left + cx + half, cy + d / 2f)
        canvas.drawRoundRect(r, d / 2f, d / 2f, badgePaint)
        val fm = badgeText.fontMetrics
        canvas.drawText(txt, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, badgeText)
    }

    /**
     * One UI's "make folder" background (IconViewImpl.folderBackground): above 1, a folder plate this much bigger
     * than the icon shows behind it while an app hovers over it (dropping it there makes a folder). A folder icon
     * scales its own container instead.
     */
    var folderBgScale = 1f
        set(v) { field = v; invalidate() }
    private val folderBgFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FolderIconView.DEFAULT_BG }
    private val folderBgRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x24000000 }

    protected open fun drawIcon(canvas: Canvas, left: Int, top: Int) {
        val b = icon ?: return
        dst.set(left, top, left + iconSize, top + iconSize)
        if (folderBgScale > 1f) {
            if (hoverPathSize != iconSize) { hoverPath = com.oneuiclone.home.icons.IconShape.path(iconSize.toFloat(), com.oneuiclone.home.icons.IconShape.container); hoverPathSize = iconSize }
            val grow = ((folderBgScale - 1f) / 0.1f).coerceAtMost(1f)       // fades in over the first tenth
            folderBgFill.alpha = (android.graphics.Color.alpha(FolderIconView.DEFAULT_BG) * grow).toInt()
            folderBgRim.alpha = (0x24 * grow).toInt()
            folderBgRim.strokeWidth = iconSize * 2.5f / 180f
            canvas.save()
            canvas.translate(left.toFloat(), top.toFloat())
            // (the folder this makes: its plate, smaller when its apps poke out)
            val s = folderBgScale * FolderIconView.popPlate
            canvas.scale(s, s, iconSize / 2f, iconSize / 2f)
            canvas.drawPath(hoverPath!!, folderBgRim)
            canvas.drawPath(hoverPath!!, folderBgFill)
            canvas.restore()
        }
        plateGlass?.let { drawPlateGlass(canvas, it, left, top) }
        shaped.draw(canvas, b, dst, iconPaint)
    }

    /**
     * Glass icons: the blurred wallpaper behind the icon, in the icon's shape, under its see-through plate. It's
     * fixed to the window, so the icon redraws whenever it moves on screen.
     */
    var plateGlass: WidgetGlass? = null
        set(v) {
            if (field === v) return
            if (isAttachedToWindow) {
                field?.views?.remove(this); v?.views?.add(this)
                if (field == null) viewTreeObserver.addOnPreDrawListener(plateMoved)
                else if (v == null) viewTreeObserver.removeOnPreDrawListener(plateMoved)
            }
            field = v; invalidate()
        }
    private val plateGlassPaint = GlassPaint()
    private val plateToWindow = android.graphics.Matrix()
    private val plateDrawnAt = android.graphics.Matrix()
    private val plateAim = android.graphics.Matrix()
    private var platePath: android.graphics.Path? = null
    private var platePathSize = -1
    // the folder plate shown while an app hovers (the folders' shape)
    private var hoverPath: android.graphics.Path? = null
    private var hoverPathSize = -1

    /**
     * A Launchii widget tile (Bubble, Split, an empty Bubbles widget), not an app: it takes the folders' and widgets'
     * shape (Icons › Keep folders and widgets squircle).
     */
    var containerShape = false
        set(v) { field = v; shaped.container = v; platePathSize = -1; invalidate() }
    private val shapeId get() = if (containerShape) com.oneuiclone.home.icons.IconShape.container else com.oneuiclone.home.icons.IconShape.current
    private val plateMoved = android.view.ViewTreeObserver.OnPreDrawListener {
        plateToWindow.reset(); transformMatrixToGlobal(plateToWindow)
        if (plateToWindow != plateDrawnAt) invalidate()
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        plateGlass?.let { it.views.add(this); viewTreeObserver.addOnPreDrawListener(plateMoved) }
    }

    override fun onDetachedFromWindow() {
        plateGlass?.let { it.views.remove(this); viewTreeObserver.removeOnPreDrawListener(plateMoved) }
        super.onDetachedFromWindow()
    }

    /**
     * The icon as it looks on screen at rest, for animations that stand in for it (opening card, closing landing
     * surface, drag): with glass icons the bitmap's plate is see-through, so the glass behind it is drawn in. The
     * glass is the part behind the icon's resting place (layout only), not where an animation has moved it for
     * now: a close starts with Home still zoomed from the open, and lands where the icon rests.
     */
    fun displayedIcon(): Bitmap? {
        val b = icon ?: return null
        val g = plateGlass ?: return b
        val out = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        var x = iconLeft().toFloat()
        var y = iconTop().toFloat()
        var v: View = this
        while (true) {
            x += v.left; y += v.top
            val p = v.parent as? View ?: break
            x -= p.scrollX; y -= p.scrollY
            v = p
        }
        plateAim.setTranslate(x, y)
        if (plateGlassPaint.aim(g, plateAim)) {
            if (platePathSize != iconSize) { platePath = com.oneuiclone.home.icons.IconShape.path(iconSize.toFloat(), shapeId); platePathSize = iconSize }
            plateGlassPaint.paint.alpha = 255
            c.drawPath(platePath!!, plateGlassPaint.paint)
        }
        c.drawBitmap(b, null, Rect(0, 0, iconSize, iconSize), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun drawPlateGlass(canvas: Canvas, g: WidgetGlass, left: Int, top: Int) {
        plateDrawnAt.reset(); transformMatrixToGlobal(plateDrawnAt)
        plateAim.set(plateDrawnAt); plateAim.preTranslate(left.toFloat(), top.toFloat())
        if (!plateGlassPaint.aim(g, plateAim)) return
        if (platePathSize != iconSize) { platePath = com.oneuiclone.home.icons.IconShape.path(iconSize.toFloat(), shapeId); platePathSize = iconSize }
        plateGlassPaint.paint.alpha = iconPaint.alpha
        canvas.save()
        canvas.translate(left.toFloat(), top.toFloat())
        canvas.drawPath(platePath!!, plateGlassPaint.paint)
        canvas.restore()
    }

    companion object {
        /**
         * How far above its cell's centre a labelled Home icon's centre sits: half its label and gap, as
         * [iconTop] centres the icon+gap+label block. A widget set to Align with icons rises by this much.
         */
        fun labelLift(context: Context, dp: DeviceProfile): Float {
            val fm = TextPaint().apply { textSize = dp.labelTextSizePx; typeface = Fonts.ui(context, 400) }.fontMetrics
            return ((fm.descent - fm.ascent) + dp.iconLabelGapPx) / 2f
        }
    }
}
