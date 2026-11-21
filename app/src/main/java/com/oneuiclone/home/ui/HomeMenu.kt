package com.oneuiclone.home.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.motion.Easing

/**
 * Hold an empty spot on Home (0.263, design opinion 4, variant C, Pixel-like): a wide glass card opens at the finger
 * with **Recent wallpapers** (the last [WallpaperHistory.MAX] set through Launchii, each shown with Home on it, the
 * current one ringed and checked), a Wallpaper & style chip, and a row of shortcuts: Widgets, Icons, Edit, Settings.
 * Tapping an older wallpaper moves the ring to it, puts it back (crossfading under Launchii's live wallpaper) and
 * closes the card. Pinch still opens the full edit mode.
 */
@SuppressLint("ViewConstructor")
class HomeMenu(
    context: Context,
    private val dp: DeviceProfile,
    night: Boolean,
    /** Home's current page as drawn now, without the wallpaper (drawn over each wallpaper), or null. */
    private val homeShot: Bitmap?,
    private val onStyle: () -> Unit,
    private val onWidgets: () -> Unit,
    private val onIcons: () -> Unit,
    private val onEdit: () -> Unit,
    private val onSettings: () -> Unit,
    private val onPick: (WallpaperHistory.Entry) -> Unit,
    private val onDismissed: () -> Unit,
    private val backdrop: ((Rect, (Bitmap) -> Unit) -> Unit)? = null,
) : FrameLayout(context) {

    /** One tile: a wallpaper from history, the current one set elsewhere (its colours), or Choose a photo. */
    class Tile(
        val entry: WallpaperHistory.Entry?,
        val thumb: Bitmap?,
        val current: Boolean,
        val add: Boolean = false,
        val colours: IntArray? = null,
        /** Tile-specific Home overlay frosted against this wallpaper (null falls back to [homeShot]). */
        val overlay: Bitmap? = null,
    )

    private val fg = if (night) 0xFFFAFAFA.toInt() else 0xFF252525.toInt()
    private val tint = if (night) 0xD9252525.toInt() else 0xB3FCFCFC.toInt()
    private val scrim = View(context).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
    private val card = FrameLayout(context)
    private val backdropView = BlurBackdropView(context)
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val tilesRow = FrameLayout(context)
    private val ring = RingView(context)
    private val tileViews = ArrayList<TileView>()
    private var tileW = 0
    private var tileH = 0
    private var gap = 0
    /** Room round each preview for the current-wallpaper ring, inside the row so nothing clips it. */
    private var ringInset = 0
    private var shown = false
    var isDismissing = false; private set
    private var originX = 0f
    private var originY = 0f

    init {
        clipChildren = false
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        card.background = GradientDrawable().apply { cornerRadius = dp.dp(28f); setColor(tint) }
        card.clipToOutline = true
        card.elevation = dp.dp(3f)
        card.addView(backdropView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        card.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val cardW = (dp.widthPx - dp.dp(18f) * 2).toInt()
        val pad = dp.dpi(12f)
        gap = dp.dpi(8f)
        ringInset = dp.dpi(4f)
        tileW = (cardW - pad * 2 - gap * 3) / 4
        tileH = (tileW * dp.heightPx / dp.widthPx.toFloat()).toInt()

        // ---- header: Recent wallpapers · Wallpaper & style ----
        val header = FrameLayout(context)
        header.addView(TextView(context).apply {
            text = "Recent wallpapers"
            setTextColor(fg); alpha = 0.9f
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dp.dp(14f))
            typeface = Fonts.ui(context, 600)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp.dpi(16f) })
        header.addView(TextView(context).apply {
            text = "Wallpaper & style"
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dp.dp(12f))
            typeface = Fonts.ui(context, 500)
            gravity = Gravity.CENTER
            setPadding(dp.dpi(12f), 0, dp.dpi(12f), 0)
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), GradientDrawable().apply {
                cornerRadius = dp.dp(16f); setColor(if (night) 0x26FFFFFF else 0x1A000000)
            }, null)
            setOnClickListener { onStyle(); dismiss() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, dp.dpi(32f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp.dpi(10f) })
        content.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp.dpi(52f)))

        // ---- the previews ----
        tilesRow.clipChildren = false
        content.clipChildren = false
        content.addView(tilesRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, tileH + ringInset * 2).apply {
            leftMargin = pad - ringInset; rightMargin = pad - ringInset
        })

        // ---- shortcuts ----
        content.addView(View(context).apply { setBackgroundColor(if (night) 0x1FFFFFFF else 0x1F000000) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, dp.dpi(0.7f))).apply {
                leftMargin = dp.dpi(16f); rightMargin = dp.dpi(16f); topMargin = dp.dpi(6f)
            })
        val glyphs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(MenuGlyph.WIDGETS to "Widgets", MenuGlyph.ICONS to "Icons", MenuGlyph.EDIT to "Edit", MenuGlyph.SETTINGS to "Settings").forEach { (g, label) ->
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if (night) 0x33FFFFFF else 0x1A000000), null,
                    GradientDrawable().apply { cornerRadius = dp.dp(14f); setColor(Color.WHITE) })
                setOnClickListener {
                    dismiss()
                    when (g) { MenuGlyph.WIDGETS -> onWidgets(); MenuGlyph.ICONS -> onIcons(); MenuGlyph.EDIT -> onEdit(); else -> onSettings() }
                }
            }
            item.addView(MenuGlyph(context, g, fg), LinearLayout.LayoutParams(dp.dpi(22f), dp.dpi(22f)))
            item.addView(TextView(context).apply {
                text = label
                setTextColor(fg)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, dp.dp(11f))
                typeface = Fonts.ui(context, 400)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp.dpi(4f) })
            glyphs.addView(item, LinearLayout.LayoutParams(0, dp.dpi(62f), 1f).apply { leftMargin = dp.dpi(3f); rightMargin = dp.dpi(3f) })
        }
        content.addView(glyphs, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp.dpi(4f); bottomMargin = dp.dpi(8f); leftMargin = dp.dpi(6f); rightMargin = dp.dpi(6f)
        })
        addView(card, LayoutParams(cardW, LayoutParams.WRAP_CONTENT))
    }

    /** Fills the previews (may come after [showAt]: history loads off the main thread). */
    fun setTiles(tiles: List<Tile>) {
        tilesRow.removeAllViews()
        tileViews.clear()
        tiles.take(WallpaperHistory.MAX).forEachIndexed { i, t ->
            val v = TileView(context, t, homeShot, dp.dp(16f))
            v.contentDescription = when { t.add -> "Choose a photo"; t.current -> "Current wallpaper"; else -> "Recent wallpaper ${i + 1}" }
            v.setOnClickListener { pick(i, t) }
            tilesRow.addView(v, LayoutParams(tileW, tileH).apply { leftMargin = ringInset + i * (tileW + gap); topMargin = ringInset })
            tileViews += v
            // each rises in just after the card
            v.alpha = 0f; v.translationY = dp.dp(10f)
            v.animate().alpha(1f).translationY(0f).setStartDelay(60L + i * 40L).setDuration(260).setInterpolator(Easing.STANDARD).start()
        }
        val cur = tiles.indexOfFirst { it.current }
        if (cur >= 0) {
            // the ring's box starts at the row's edge: tile + ringInset all round, so it is never cut
            tilesRow.addView(ring, LayoutParams(tileW + ringInset * 2, tileH + ringInset * 2).apply { leftMargin = cur * (tileW + gap); topMargin = 0 })
            ring.translationX = 0f
            ring.alpha = 0f
            ring.animate().alpha(1f).setStartDelay(180).setDuration(220).start()
        }
    }

    private var picked = false

    private fun pick(i: Int, t: Tile) {
        if (picked || isDismissing) return
        when {
            t.add -> { onStyle(); dismiss() }
            t.current -> dismiss()
            t.entry != null -> {
                picked = true
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                val from = (ring.layoutParams as? LayoutParams)?.leftMargin ?: 0
                ring.animate().translationX((i * (tileW + gap) - from).toFloat()).setDuration(300).setInterpolator(Easing.STANDARD).start()
                ring.pulse()
                onPick(t.entry)
                // closes at once (the ring's slide plays as it goes), so the change is seen in full
                dismiss()
            }
        }
    }

    /** Opens the card at the finger ([x], [y], screen coords): below it when there's room, else above. */
    fun showAt(x: Float, y: Float) {
        val lp = card.layoutParams as LayoutParams
        content.measure(MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val h = content.measuredHeight
        lp.height = h
        card.layoutParams = lp
        val top = dp.insets.top + dp.dp(12f)
        val bottom = dp.heightPx - dp.insets.bottom - dp.dp(12f)
        val below = y + dp.dp(20f)
        val cy = if (below + h <= bottom) below else (y - dp.dp(20f) - h).coerceAtLeast(top).coerceAtMost(bottom - h)
        card.x = (dp.widthPx - lp.width) / 2f
        card.y = cy
        originX = x; originY = y
        backdrop?.invoke(Rect(card.x.toInt(), cy.toInt(), (card.x + lp.width).toInt(), (cy + h).toInt())) {
            if (isDismissing) return@invoke
            backdropView.set(it, dp.dp(24f) * it.width / lp.width.toFloat())
            backdropView.tint = tint
        }
        card.pivotX = (x - card.x).coerceIn(0f, lp.width.toFloat())
        card.pivotY = (y - cy).coerceIn(0f, h.toFloat())
        card.alpha = 0f; card.scaleX = 0.78f; card.scaleY = 0.78f
        shown = true
        card.animate().alpha(1f).setDuration(160).setInterpolator(Easing.LINEAR).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
        scrim.animate().alpha(0.22f).setDuration(220).start()
    }

    fun dismiss() {
        if (isDismissing) return
        isDismissing = true
        scrim.animate().cancel()
        scrim.animate().alpha(0f).setDuration(200).start()
        card.animate().cancel()
        card.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(180).setInterpolator(Easing.SINE_IN_OUT_33)
            .withEndAction {
                (parent as? ViewGroup)?.removeView(this)
                onDismissed()
            }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val r = RectF(card.x, card.y, card.x + card.width, card.y + card.height)
            if (!r.contains(event.x, event.y)) dismiss()
        }
        return true
    }

    /** A preview: the wallpaper's thumbnail with Home drawn over it, rounded; a "+" for Choose a photo. */
    private class TileView(context: Context, private val t: Tile, private val home: Bitmap?, private val radius: Float) : View(context) {
        private val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x99FFFFFF.toInt() }

        init { isClickable = true }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            clip.reset(); clip.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)
            c.save(); c.clipPath(clip)
            val s = if (isPressed) 0.96f else 1f
            c.scale(s, s, w / 2f, h / 2f)
            when {
                t.add -> {
                    p.color = 0x26FFFFFF; c.drawRect(0f, 0f, w, h, p)
                    line.strokeWidth = w * 0.025f
                    c.drawLine(w / 2f - w * 0.12f, h / 2f, w / 2f + w * 0.12f, h / 2f, line)
                    c.drawLine(w / 2f, h / 2f - w * 0.12f, w / 2f, h / 2f + w * 0.12f, line)
                }
                t.thumb != null -> {
                    val b = t.thumb
                    val f = maxOf(w / b.width, h / b.height)
                    val dw = b.width * f; val dh = b.height * f
                    c.drawBitmap(b, null, RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), p)
                }
                else -> {
                    // set elsewhere: Launchii can't read it, so its colours stand in
                    val cols = t.colours?.takeIf { it.size >= 2 } ?: intArrayOf(0xFF3A4A6B.toInt(), 0xFF1C2333.toInt())
                    p.shader = android.graphics.LinearGradient(0f, 0f, w * 0.3f, h, cols, null, android.graphics.Shader.TileMode.CLAMP)
                    c.drawRect(0f, 0f, w, h, p)
                    p.shader = null
                }
            }
            val overlay = t.overlay ?: home
            if (!t.add && overlay != null) c.drawBitmap(overlay, null, RectF(0f, 0f, w, h), p)
            c.restore()
        }

        override fun dispatchSetPressed(pressed: Boolean) { super.dispatchSetPressed(pressed); invalidate() }
        override fun setPressed(pressed: Boolean) { super.setPressed(pressed); invalidate() }
    }

    /** The white ring and check on the current wallpaper. */
    private class RingView(context: Context) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2.5f * d }
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0xFF1D1B1A.toInt(); strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private var checkScale = 1f

        fun pulse() {
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 300
                addUpdateListener { checkScale = 1f - 0.35f * kotlin.math.sin(Math.PI.toFloat() * (it.animatedValue as Float)); invalidate() }
                start()
            }
        }

        override fun onDraw(c: Canvas) {
            val inset = ringP.strokeWidth / 2f
            c.drawRoundRect(inset, inset, width - inset, height - inset, 20f * d, 20f * d, ringP)
            val r = 9f * d * checkScale
            val cx = width - 14f * d; val cy = height - 14f * d
            c.drawCircle(cx, cy, r, disc)
            val k = checkScale
            c.drawLine(cx - 4f * d * k, cy, cx - 1f * d * k, cy + 3f * d * k, tick)
            c.drawLine(cx - 1f * d * k, cy + 3f * d * k, cx + 4.5f * d * k, cy - 3f * d * k, tick)
        }
    }

    /** Line glyphs for the shortcuts row. */
    private class MenuGlyph(context: Context, private val g: Int, color: Int) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(c: Canvas) {
            val s = minOf(width, height).toFloat()
            p.strokeWidth = s * 0.08f
            val m = s * 0.12f
            when (g) {
                WIDGETS -> {
                    val q = (s - 2 * m - s * 0.12f) / 2f; val r = s * 0.08f
                    for (i in 0..1) for (j in 0..1) {
                        val x = m + i * (q + s * 0.12f); val y = m + j * (q + s * 0.12f)
                        c.drawRoundRect(x, y, x + q, y + q, r, r, p)
                    }
                }
                ICONS -> {
                    c.drawRoundRect(m, m, s - m, s - m, s * 0.26f, s * 0.26f, p)
                    c.drawCircle(s / 2f, s / 2f, s * 0.12f, p)
                }
                EDIT -> {
                    c.drawRoundRect(s * 0.3f, m, s - m, s * 0.72f, s * 0.07f, s * 0.07f, p)
                    c.drawRoundRect(m, s * 0.28f, s * 0.7f, s - m, s * 0.07f, s * 0.07f, p)
                }
                else -> {
                    c.drawCircle(s / 2f, s / 2f, s * 0.16f, p)
                    for (k in 0 until 8) {
                        val a = k * Math.PI / 4
                        val r0 = s * 0.28f; val r1 = s * 0.4f
                        c.drawLine(s / 2f + (kotlin.math.cos(a) * r0).toFloat(), s / 2f + (kotlin.math.sin(a) * r0).toFloat(),
                            s / 2f + (kotlin.math.cos(a) * r1).toFloat(), s / 2f + (kotlin.math.sin(a) * r1).toFloat(), p)
                    }
                    c.drawCircle(s / 2f, s / 2f, s * 0.28f, p)
                }
            }
        }

        companion object { const val WIDGETS = 0; const val ICONS = 1; const val EDIT = 2; const val SETTINGS = 3 }
    }
}
