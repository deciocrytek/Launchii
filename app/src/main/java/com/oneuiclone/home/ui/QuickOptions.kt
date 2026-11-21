package com.oneuiclone.home.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Process
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.icons.IconFactory
import com.oneuiclone.home.motion.Easing
import kotlin.math.roundToInt

/** A global option (bottom row of the One UI quick option popup). */
class QuickOption(val label: String, val glyph: Int, val action: () -> Unit) {
    companion object {
        const val SELECT = 0
        const val REMOVE = 1
        const val UNINSTALL = 2
        const val ADD = 3
        const val INFO = 4
        const val EDIT = 5
        const val STYLE = 6
        const val RENAME = 7
        const val BUBBLE = 8
    }
}

/**
 * One UI 8.5 quick option popup (quick_option_* dimens/colours, PopupAnimationCreator):
 * 20dp corners, title row 58dp, deep shortcuts (26dp icon, 15dp gap, 14dp text), global option row 55dp
 * (19dp glyph, 10dp label). Show: alpha 200 ms + scale 0.8→1 350 ms (0.22,0.25,0,1).
 * Hide: alpha 200 ms + scale →0.8 200 ms.
 */
@SuppressLint("ViewConstructor")
class QuickOptionPopup(
    context: Context,
    private val dp: DeviceProfile,
    private val iconFactory: IconFactory,
    title: String,
    /** Deep shortcuts, preloaded off the main thread (see [ShortcutLoader]). */
    shortcuts: List<Pair<ShortcutInfo, Bitmap>>,
    options: List<QuickOption>,
    private val onDismissed: () -> Unit,
    onAppInfo: (() -> Unit)?,
    /** Captures what's behind the card asynchronously; the card turns to blurred glass when it arrives. */
    private val backdrop: ((Rect, (Bitmap) -> Unit) -> Unit)? = null,
) : FrameLayout(context) {

    private val night = iconFactory.isNight()
    private val bgColor = if (night) 0xF2252525.toInt() else 0xF2FCFCFC.toInt()       // quick_option_bg_color
    private val childColor = if (night) 0x0DFAFAFA else 0x66FCFCFC                     // quick_option_child_bg_color
    private val fg = if (night) 0xFFFAFAFA.toInt() else 0xFF252525.toInt()
    private val titleColor = if (night) 0xFFFAFAFA.toInt() else 0xFF272727.toInt()
    /** Outer rounded frame (animated); holds blurred backdrop + tint + content. */
    private val card = FrameLayout(context)
    private val content = LinearLayout(context)
    private val backdropView = BlurBackdropView(context)             // One UI draws quick options on real blur
    private val blurTint = if (night) 0xD9252525.toInt() else 0xB3FCFCFC.toInt()       // quick_option_bg_color_blur
    private var shown = false
    var isDismissing = false; private set
    /** Touches outside the card dismiss it and fall through to what's below (widget resize handles). */
    var passThroughOutside = false
    /** Rows the long-press finger can slide onto and let go ("glide"); the touch stays with the icon. */
    private val glideTargets = ArrayList<View>()
    private var glideHot: View? = null
    /** The long-pressed icon, screen coords (set in [showFor]). */
    private val anchorRect = Rect()

    init {
        clipChildren = false
        content.orientation = LinearLayout.VERTICAL
        card.background = GradientDrawable().apply {
            cornerRadius = dp.dp(20f)                                        // quick_option_round_corner_radius
            setColor(bgColor)
        }
        card.elevation = dp.dp(3f)                                           // quick_option_popup_elevation
        card.clipToOutline = true
        card.addView(backdropView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        card.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ---- title row ----
        val titleRow = FrameLayout(context)
        val titleView = TextView(context).apply {
            text = title
            setTextColor(titleColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dp.dp(16f))              // quick_option_title_text_size_dp
            typeface = Fonts.ui(context, 600)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp.dpi(20f); rightMargin = dp.dpi(44f)
        })
        if (onAppInfo != null) {
            val info = GlyphView(context, QuickOption.INFO, fg).apply {
                setOnClickListener { onAppInfo(); dismiss() }
            }
            glideTargets += info
            titleRow.addView(info, LayoutParams(dp.dpi(32f), dp.dpi(30f), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = dp.dpi(10f)
            })
        }
        content.addView(titleRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp.dpi(52f)))

        // ---- deep shortcuts ----
        if (shortcuts.isNotEmpty()) {
            val section = section()
            shortcuts.take(4).forEach { (sc, icon) ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp.dpi(18f), dp.dpi(9f), dp.dpi(18f), dp.dpi(9f))
                    isClickable = true
                    background = recoilBackground()
                    setOnClickListener {
                        try {
                            context.getSystemService(LauncherApps::class.java)
                                .startShortcut(sc.`package`, sc.id, null, null, sc.userHandle ?: Process.myUserHandle())
                        } catch (_: Exception) {}
                        dismiss()
                    }
                }
                glideTargets += row
                row.addView(BitmapIconView(context, icon), LinearLayout.LayoutParams(dp.dpi(26f), dp.dpi(26f)))
                row.addView(TextView(context).apply {
                    text = sc.shortLabel
                    setTextColor(fg)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, dp.dp(14f))      // deep_shortcut_text_size
                    typeface = Fonts.ui(context, 400)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp.dpi(15f) })
                section.addView(row)
            }
            content.addView(section, sectionParams())
        }

        // ---- global options ----
        if (options.isNotEmpty()) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp.dpi(4.5f), 0, dp.dpi(4.5f), 0)
            }
            options.forEach { opt ->
                val item = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    isClickable = true
                    background = recoilBackground()
                    setOnClickListener { opt.action(); dismiss() }
                }
                glideTargets += item
                item.addView(GlyphView(context, opt.glyph, fg), LinearLayout.LayoutParams(dp.dpi(19f), dp.dpi(19f)))
                item.addView(TextView(context).apply {
                    text = opt.label
                    setTextColor(fg)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, dp.dp(10f))      // quick_option_global_text_size_dp
                    typeface = Fonts.ui(context, 400)
                    isSingleLine = true
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp.dpi(3f)
                })
                row.addView(item, LinearLayout.LayoutParams(0, dp.dpi(55f), 1f).apply {
                    leftMargin = dp.dpi(1.5f); rightMargin = dp.dpi(1.5f)
                })
            }
            val section = section()
            section.addView(row)
            content.addView(section, sectionParams().apply { bottomMargin = dp.dpi(4f) })
        }
        addView(card, LayoutParams(dp.dpi(265f).coerceAtMost((dp.widthPx * 0.85f).toInt()), LayoutParams.WRAP_CONTENT))
    }

    private fun section() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = dp.dp(16f); setColor(childColor) }
        setPadding(0, dp.dpi(2f), 0, dp.dpi(2f))
    }

    private fun sectionParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        leftMargin = dp.dpi(4f); rightMargin = dp.dpi(4f); bottomMargin = dp.dpi(4f)
    }

    private fun recoilBackground() = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(if (night) 0x33FFFFFF else 0x1A000000), null,
        GradientDrawable().apply { cornerRadius = dp.dp(12f); setColor(0xFFFFFFFF.toInt()) })

    // ---- glide: long-press an icon, slide onto a row, let go ----

    /** Card bounds on screen at rest (ignores the 0.8→1 show scale). */
    private fun cardOnScreen(out: RectF) {
        val loc = IntArray(2); getLocationOnScreen(loc)
        out.set(loc[0] + card.x, loc[1] + card.y, loc[0] + card.x + card.width, loc[1] + card.y + card.height)
    }

    /**
     * Where a long-press finger at ([x], [y]) (screen coords) is: [ZONE_CARD] over the card, [ZONE_PATH] on the
     * icon or the gap between it and the card (still undecided), else [ZONE_OUT] (the icon gets dragged).
     */
    fun glideZone(x: Float, y: Float, pad: Float): Int {
        if (!shown || isDismissing || anchorRect.isEmpty) return ZONE_OUT
        val c = RectF(); cardOnScreen(c)
        if (c.contains(x, y)) return ZONE_CARD
        val path = RectF(anchorRect)
        path.union(anchorRect.exactCenterX(), if (c.centerY() < anchorRect.exactCenterY()) c.bottom else c.top)
        path.inset(-pad, -pad)
        return if (path.contains(x, y)) ZONE_PATH else ZONE_OUT
    }

    /** True if the card sits above the icon (the usual case), so "towards the card" means up. */
    fun cardAbove(): Boolean { val c = RectF(); cardOnScreen(c); return c.centerY() < anchorRect.exactCenterY() }

    /** Highlights the row under the finger (or none), with a tick when it changes. */
    fun glideAt(x: Float, y: Float) {
        if (isDismissing) return
        val loc = IntArray(2)
        val hit = glideTargets.firstOrNull { t ->
            if (!t.isShown) return@firstOrNull false
            t.getLocationOnScreen(loc)
            x >= loc[0] && x < loc[0] + t.width && y >= loc[1] && y < loc[1] + t.height
        }
        if (hit !== glideHot) {
            glideHot?.isPressed = false
            glideHot = hit
            if (hit != null) {
                hit.getLocationOnScreen(loc)
                hit.drawableHotspotChanged(x - loc[0], y - loc[1])
                hit.isPressed = true
                hit.performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_TICK)
            }
        }
    }

    /** Finger lifted: runs the highlighted row. False if none was (the popup just stays open, as after a tap). */
    fun glideRelease(): Boolean {
        val hot = glideHot ?: return false
        glideHot = null
        hot.isPressed = false
        if (isDismissing) return false
        hot.performClick()
        return true
    }

    fun glideCancel() { glideHot?.isPressed = false; glideHot = null }

    /** Position next to [anchor] (icon rect in screen coords) and animate in. */
    fun showFor(anchor: Rect, fromHotseat: Boolean) {
        anchorRect.set(anchor)
        val cardLp = card.layoutParams as LayoutParams
        content.measure(MeasureSpec.makeMeasureSpec(cardLp.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val w = cardLp.width
        val h = content.measuredHeight
        cardLp.height = h
        card.layoutParams = cardLp
        val margin = dp.widthPx * (if (fromHotseat) 0.018f else 0.036f)           // quick_option_horizontal_minimum_margin
        val gap = dp.dp(4f)                                                      // quick_option_gap_between_popup_and_icon_dp
        val topMin = dp.dp(38f) + dp.insets.top                                  // quick_option_top_minimum_margin
        var y = anchor.top - gap - h
        val above = y >= topMin
        var squeezed = false
        if (!above) {
            y = anchor.bottom + gap + dp.labelTextSizePx * 1.6f
            // With the keyboard up (search results) the room below ends at its top edge. If the card fits on
            // neither side of the icon, it goes in the bigger gap, clamped on screen (it may cover part of the icon).
            val ime = rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
            if (ime > 0) {
                val bottomMax = dp.heightPx - ime - gap
                if (y + h > bottomMax) {
                    squeezed = true
                    val roomAbove = anchor.top - gap - topMin
                    val roomBelow = bottomMax - (anchor.bottom + gap)
                    y = if (roomAbove >= roomBelow) topMin else (bottomMax - h).coerceAtLeast(topMin)
                }
            }
        }
        val x = (anchor.exactCenterX() - w / 2f).coerceIn(margin, dp.widthPx - margin - w)
        card.x = x; card.y = y
        backdrop?.invoke(Rect(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt())) {
            if (isDismissing) return@invoke
            backdropView.set(it, dp.dp(24f) * it.width / w.toFloat())
            (card.background as GradientDrawable).setColor(blurTint)
            backdropView.tint = blurTint
        }
        card.pivotX = (anchor.exactCenterX() - x).coerceIn(0f, w.toFloat())
        card.pivotY = when { squeezed -> (anchor.exactCenterY() - y).coerceIn(0f, h.toFloat()); above -> h.toFloat(); else -> 0f }
        card.alpha = 0f; card.scaleX = 0.8f; card.scaleY = 0.8f
        shown = true
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(card, ALPHA, 0f, 1f).apply { duration = 200; interpolator = Easing.LINEAR },
                ObjectAnimator.ofFloat(card, SCALE_X, 0.8f, 1f).apply { duration = 350; interpolator = Easing.BASIC },
                ObjectAnimator.ofFloat(card, SCALE_Y, 0.8f, 1f).apply { duration = 350; interpolator = Easing.BASIC },
            )
            start()
        }
    }

    fun dismiss() {
        if (isDismissing) return
        isDismissing = true
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(card, ALPHA, card.alpha, 0f).apply { duration = 200; interpolator = Easing.LINEAR },
                ObjectAnimator.ofFloat(card, SCALE_X, card.scaleX, 0.8f).apply { duration = 200; interpolator = Easing.SINE_IN_OUT_33 },
                ObjectAnimator.ofFloat(card, SCALE_Y, card.scaleY, 0.8f).apply { duration = 200; interpolator = Easing.SINE_IN_OUT_33 },
            )
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    (parent as? ViewGroup)?.removeView(this@QuickOptionPopup)
                    onDismissed()
                }
            })
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val r = RectF(card.x, card.y, card.x + card.width, card.y + card.height)
            if (!r.contains(event.x, event.y)) { dismiss(); if (passThroughOutside) return false }
        }
        return true
    }

    companion object {
        const val ZONE_OUT = 0
        const val ZONE_PATH = 1
        const val ZONE_CARD = 2
    }

    private class BitmapIconView(context: Context, private val bmp: Bitmap) : View(context) {
        private val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) = canvas.drawBitmap(bmp, null, Rect(0, 0, width, height), p)
    }
}

/** Minimal line glyphs standing in for One UI's quick option icons. */
class GlyphView(context: Context, private val glyph: Int, color: Int) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    init { isClickable = glyph == QuickOption.INFO }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        p.strokeWidth = s * 0.085f
        val r = s * 0.42f
        when (glyph) {
            QuickOption.SELECT -> {
                canvas.drawCircle(cx, cy, r, p)
                canvas.drawLine(cx - r * 0.45f, cy, cx - r * 0.1f, cy + r * 0.35f, p)
                canvas.drawLine(cx - r * 0.1f, cy + r * 0.35f, cx + r * 0.5f, cy - r * 0.3f, p)
            }
            QuickOption.REMOVE -> { canvas.drawCircle(cx, cy, r, p); canvas.drawLine(cx - r * 0.5f, cy, cx + r * 0.5f, cy, p) }
            QuickOption.ADD -> { canvas.drawCircle(cx, cy, r, p); canvas.drawLine(cx - r * 0.5f, cy, cx + r * 0.5f, cy, p); canvas.drawLine(cx, cy - r * 0.5f, cx, cy + r * 0.5f, p) }
            QuickOption.UNINSTALL -> {
                val w = r * 0.75f
                canvas.drawLine(cx - r, cy - r * 0.6f, cx + r, cy - r * 0.6f, p)
                canvas.drawRoundRect(cx - w, cy - r * 0.6f, cx + w, cy + r, s * 0.06f, s * 0.06f, p)
                canvas.drawLine(cx - r * 0.3f, cy - r * 0.95f, cx + r * 0.3f, cy - r * 0.95f, p)
            }
            QuickOption.EDIT -> {
                canvas.drawLine(cx - r * 0.7f, cy + r * 0.7f, cx + r * 0.6f, cy - r * 0.6f, p)
                canvas.drawLine(cx - r * 0.7f, cy + r * 0.7f, cx - r * 0.75f, cy + r * 0.2f, p)
            }
            QuickOption.RENAME -> {
                // a "T" on a text line
                canvas.drawLine(cx - r * 0.7f, cy - r * 0.75f, cx + r * 0.7f, cy - r * 0.75f, p)
                canvas.drawLine(cx, cy - r * 0.75f, cx, cy + r * 0.45f, p)
                canvas.drawLine(cx - r, cy + r * 0.9f, cx + r, cy + r * 0.9f, p)
            }
            QuickOption.STYLE -> {
                // half-filled circle, like a contrast control
                canvas.drawCircle(cx, cy, r, p)
                p.style = Paint.Style.FILL
                canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 90f, 180f, true, p)
                p.style = Paint.Style.STROKE
            }
            QuickOption.BUBBLE -> {
                // a bubble with a smaller one budding off it
                canvas.drawCircle(cx - r * 0.18f, cy + r * 0.18f, r * 0.72f, p)
                canvas.drawCircle(cx + r * 0.68f, cy - r * 0.68f, r * 0.26f, p)
            }
            QuickOption.INFO -> {
                val rr = s * 0.3f
                p.strokeWidth = s * 0.06f
                canvas.drawCircle(cx, cy, rr, p)
                canvas.drawLine(cx, cy - rr * 0.1f, cx, cy + rr * 0.5f, p)
                p.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy - rr * 0.45f, s * 0.035f, p)
                p.style = Paint.Style.STROKE
            }
        }
    }
}
