package com.oneuiclone.home.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View

/**
 * Zoom In open (iOS-style): the tapped icon grows into a card in the app's splash colour, its icon growing into
 * the splash's 160 dp circle, over a dim of Home. The card aims at [endScale] of the screen (past its edges) so it
 * covers the screen well before the spring settles. It ends on what Android's starting window shows, so the
 * system's crossfade to the app lands on a near-identical frame.
 */
class LaunchCard(context: Context) : View(context) {
    /** The icon on screen when tapped, in this view's coordinates. */
    val from = RectF()
    var color = Color.WHITE
    /**
     * The card's opacity at the start, below 1 for a see-through (glass) icon or a plateless (Minimal) one: the card
     * turns solid over the first part of the grow, so it reads as the icon itself opening rather than a card drawn
     * behind it.
     */
    var fromAlpha = 1f
    /**
     * A dark, Dot, Prism or Stencil icon's plate colour (Icon theme › Dark, Dot, Prism, Stencil): the card grows in it for the first [DARK_HOLD] of the way,
     * light or dark mode alike, then turns into [color]; the full icon waits until then too. Null: [color] throughout.
     */
    var fromColor: Int? = null
    /** As drawn on Home (themed when theme icons are on). */
    var icon: Bitmap? = null
    /** The app's own icon at full resolution; arrives a few frames in and fades in over [icon]. */
    var splashIcon: Drawable? = null
        set(v) { field = v; splashIconAt = android.os.SystemClock.uptimeMillis(); invalidate() }
    private var splashIconAt = 0L
    var startRadius = 0f
    /**
     * An icon shape (IconShape) other than the squircle the card's rounded rectangle approximates: the card is
     * clipped to it, growing out from the icon, until it holds the whole card ([SHAPE_GONE] of the way).
     */
    var fromShape: String? = null
    var endRadius = 0f
    var splashIconPx = 0f
    /** Fraction of the screen the card grows to, centred (above 1: past the edges). */
    var endScale = 1f
    /** The icon's size at the start; below 0, the size of [from] (an icon grows from itself). */
    var fromIconPx = -1f
    /**
     * Opening from a widget: the card and the icon fade in as the card grows out of the widget's shape, so the
     * widget (zooming with Home underneath) turns into the card.
     */
    var fadeIn = false

    /**
     * The open was interrupted (Home came back before the app covered it): the card is shrinking back into its icon,
     * the full icon fading back into Home's on the way, and taps go through to Home again.
     */
    var retracting = false

    /** Spring progress 0 → 1 (may overshoot a little). */
    var progress = 0f
        set(v) { field = v; invalidate() }

    /** Whether the card is light enough to want dark status / navigation bar icons. */
    val isLight get() = Color.luminance(color) > 0.5f

    private val dimPaint = Paint().apply { color = Color.BLACK }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shaped = com.oneuiclone.home.icons.ShapedIcon()
    private val rect = RectF()
    private val iconRect = RectF()
    private val iconBounds = Rect()
    private val clip = Path()
    private val shapeClip = Path()

    override fun onDraw(canvas: Canvas) {
        val p = progress
        val pc = p.coerceIn(0f, 1f)
        dimPaint.alpha = (DIM * pc * 255).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        val ex = width * (1f - endScale) / 2f
        val ey = height * (1f - endScale) / 2f
        rect.set(lerp(from.left, ex, p), lerp(from.top, ey, p), lerp(from.right, width - ex, p), lerp(from.bottom, height - ey, p))
        val r = lerp(startRadius, endRadius, pc)
        val dark = fromColor
        val c = if (dark == null) color else androidx.core.graphics.ColorUtils.blendARGB(dark, color,
            ((pc - DARK_HOLD) / DARK_TURN).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) })
        cardPaint.color = c
        val shown = if (fadeIn) (pc / FADE_IN).coerceAtMost(1f) else 1f
        val solid = lerp(fromAlpha, 1f, (pc / FROM_ALPHA_GONE).coerceAtMost(1f))
        cardPaint.alpha = (Color.alpha(c) * shown * solid).toInt()
        val shape = fromShape?.takeIf { pc < SHAPE_GONE }
        if (shape != null) {
            val size = lerp(from.width(), com.oneuiclone.home.icons.IconShape.cover(rect.width(), rect.height()), pc / SHAPE_GONE)
            com.oneuiclone.home.icons.IconShape.path(shapeClip, rect.centerX(), rect.centerY(), size, shape)
            canvas.save()
            canvas.clipPath(shapeClip)
        }
        canvas.drawRoundRect(rect, r, r, cardPaint)
        if (shape != null) canvas.restore()

        // the icon stays centred in the card, growing from its Home size into the splash circle
        val size = lerp(if (fromIconPx < 0f) from.width() else fromIconPx, splashIconPx, pc)
        iconRect.set(rect.centerX() - size / 2f, rect.centerY() - size / 2f, rect.centerX() + size / 2f, rect.centerY() + size / 2f)
        val ir = lerp(ICON_RADIUS, 0.5f, (pc * 1.6f).coerceAtMost(1f)) * size
        clip.rewind()
        clip.addRoundRect(iconRect, ir, ir, Path.Direction.CW)
        if (shown < 1f) canvas.saveLayerAlpha(iconRect, (shown * 255).toInt()) else canvas.save()
        val big = splashIcon
        // a dark icon stays itself while the card is still dark; the full icon fades in from then
        if (dark != null && pc < DARK_HOLD && big != null) splashIconAt = android.os.SystemClock.uptimeMillis()
        var bigAlpha = if (big == null) 0f else ((android.os.SystemClock.uptimeMillis() - splashIconAt) / SWAP_MS.toFloat()).coerceIn(0f, 1f)
        if (retracting) bigAlpha = minOf(bigAlpha, ((pc - RETRACT_ICON_GONE) / RETRACT_ICON_TURN).coerceIn(0f, 1f))
        // the Home icon keeps its own squircle: the clip would cut through its light rim (stark on a dark themed
        // plate), leaving a stray arc along the top. It changes shape by fading into the full icon instead.
        icon?.let {
            iconPaint.alpha = ((1f - bigAlpha) * 255).toInt()
            if (iconPaint.alpha > 0) shaped.draw(canvas, it, iconRect, iconPaint)
        }
        canvas.clipPath(clip)
        if (big != null && bigAlpha > 0f) {
            if (bigAlpha < 1f) canvas.saveLayerAlpha(iconRect, (bigAlpha * 255).toInt())
            if (big is AdaptiveIconDrawable) {
                // the layers without the system mask (a circle on Pixel): the card's own clip shapes the icon. The
                // mask covers the middle 72 of 108 units, so the layers reach a quarter of the size past each side.
                val out = iconRect.width() / 4f
                iconBounds.set((iconRect.left - out).toInt(), (iconRect.top - out).toInt(), (iconRect.right + out).toInt(), (iconRect.bottom + out).toInt())
                big.background?.let { it.bounds = iconBounds; it.draw(canvas) }
                big.foreground?.let { it.bounds = iconBounds; it.draw(canvas) }
            } else {
                iconRect.roundOut(iconBounds)
                big.bounds = iconBounds
                big.draw(canvas)
            }
            if (bigAlpha < 1f) { canvas.restore(); postInvalidateOnAnimation() }
        }
        canvas.restore()
    }

    /** Covers Home until it's gone: taps mustn't reach the icons underneath (they may again once it's shrinking back). */
    override fun onTouchEvent(event: MotionEvent) = !retracting

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    companion object {
        /** How much Home darkens behind the card. */
        const val DIM = 0.25f
        /** Corner radius of a Home icon as a fraction of its size; the card's clip morphs from it to a circle. */
        const val ICON_RADIUS = 0.3f
        private const val SWAP_MS = 120L
        /** Shrinking back, the full icon has turned back into Home's by this much of the way... */
        private const val RETRACT_ICON_GONE = 0.1f
        /** ...over this much before it. */
        private const val RETRACT_ICON_TURN = 0.4f
        /** With [fromShape], how far the card is along when the shape has grown past all of it. */
        private const val SHAPE_GONE = 0.4f
        /** With [fadeIn], the card is opaque from this much of the way. */
        private const val FADE_IN = 0.3f
        /** The card is solid from this much of the way (about 125 ms in), whatever [fromAlpha]. */
        private const val FROM_ALPHA_GONE = 0.7f
        /** With [fromColor], the card is that colour for this much of the way... */
        const val DARK_HOLD = 0.25f
        /** ...then turns into its own colour over this much more. */
        const val DARK_TURN = 0.3f
        /** [fromAlpha] for a glass icon. */
        const val GLASS_FROM_ALPHA = 0.5f
        /** [fromAlpha] for a Minimal icon: just the glyph at first, the card filling in around it. */
        const val MINIMAL_FROM_ALPHA = 0f
        /** [fromAlpha] for a Stencil icon: its cut-out keeps showing the wallpaper at first, the card filling in. */
        const val STENCIL_FROM_ALPHA = 0f
        /**
         * [fromAlpha] for a Doodle, Sprinkles, Comic or Groovy icon: their plates differ from app to app (and Doodle's
         * wobbles), so one solid plate colour under the icon at the tap shows as a square behind it; the card fills in.
         */
        const val PLAYFUL_FROM_ALPHA = 0f
        /**
         * [fromAlpha] for a Themed icon (0.220): like the playful ones, the card starts see-through in the icon's theme
         * colour ([fromColor]) and fills in, turning into the app's colour, instead of a solid app-colour square at the tap.
         */
        const val THEMED_FROM_ALPHA = 0f
    }
}
