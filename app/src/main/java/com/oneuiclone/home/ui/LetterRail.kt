package com.oneuiclone.home.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.oneuiclone.home.device.DeviceProfile
import kotlin.math.exp

/**
 * The Apps screen's letter rail (0.262, design opinion 8): the letters the apps start with, down the lower right edge
 * where a thumb reaches. Sliding along it jumps the list to that letter ([onLetter], with a tick per letter); the
 * letters near the finger swell and lean out of its way, and a bubble beside it shows the letter. Only letters with
 * apps are shown ("#" for anything that isn't A–Z), so each one is a real jump.
 */
@SuppressLint("ViewConstructor")
class LetterRail(context: Context, private val dp: DeviceProfile) : View(context) {

    /** Letters shown, top to bottom, and the app index each one starts at. */
    var letters: List<Pair<String, Int>> = emptyList()
        set(v) { field = v; excludeBackGesture(); invalidate() }
    /** The list should show the app at this index (the first with the letter). */
    var onLetter: ((Int) -> Unit)? = null
    /** False while the Apps screen isn't up (touches pass through). */
    var enabledNow: () -> Boolean = { true }

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Fonts.ui(context, 600)
        setShadowLayer(dp.dp(3f), 0f, dp.dp(0.5f), 0x66000000)
    }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6262626.toInt() }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = Color.WHITE; textSize = dp.dp(30f); typeface = Fonts.ui(context, 600)
    }
    private val restSize = dp.dp(11f)
    /** Height each letter gets: 18 dp, less if the rail would run off the top. */
    private fun step(): Float = if (letters.isEmpty()) 0f else minOf(dp.dp(18f), (height - dp.dp(8f)) / letters.size)
    /** The rail's letters sit this far in from the right edge (the view is wider, for the bubble and the lean). */
    private val railInset = dp.dp(16f)

    private var touching = false
    private var fingerY = 0f
    private var current = -1
    /** 0 → 1 as the finger lands, back to 0 after it lifts. */
    private var touch = 0f
    private val touchAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 180
        interpolator = com.oneuiclone.home.motion.Easing.STANDARD
        addUpdateListener { touch = it.animatedValue as Float; invalidate() }
    }

    /** Letters run up from the bottom of the view; this is where the top one is drawn. */
    private fun top(): Float = height - dp.dp(4f) - step() * letters.size

    private fun indexAt(y: Float): Int {
        if (letters.isEmpty()) return -1
        return ((y - top()) / step()).toInt().coerceIn(0, letters.size - 1)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        excludeBackGesture()
    }

    /**
     * The rail is on the edge the back gesture starts from: its lower part (the system honours 200 dp per edge) is kept
     * for the rail, so a scrub that drifts sideways doesn't go back.
     */
    private fun excludeBackGesture() {
        if (letters.isEmpty() || height == 0) { systemGestureExclusionRects = emptyList(); return }
        val t = maxOf(top() - dp.dp(12f), height - dp.dp(200f))
        systemGestureExclusionRects = listOf(android.graphics.Rect((width - railInset * 2.2f).toInt(), t.toInt(), width, height))
    }

    /** Only the strip with the letters takes touches; the rest of the view is for drawing the bubble. */
    private fun onRail(x: Float, y: Float) = x >= width - railInset * 2.2f && y >= top() - dp.dp(12f) && y <= height

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (letters.isEmpty() || !enabledNow() || !onRail(e.x, e.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                touching = true
                current = -1
                touchAnim.setFloatValues(touch, 1f); touchAnim.start()
                scrub(e.y)
            }
            MotionEvent.ACTION_MOVE -> if (touching) scrub(e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (touching) {
                touching = false
                touchAnim.setFloatValues(touch, 0f); touchAnim.start()
            }
        }
        return touching
    }

    private fun scrub(y: Float) {
        fingerY = y
        val i = indexAt(y)
        if (i != current) {
            current = i
            performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
            onLetter?.invoke(letters[i].second)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (letters.isEmpty()) return
        val s = step()
        val top = top()
        val railX = width - railInset
        val focus = if (current >= 0) top + (current + 0.5f) * s else fingerY
        val y0 = fingerY.coerceIn(top, top + s * letters.size)
        letters.forEachIndexed { i, (letter, _) ->
            val cy = top + (i + 0.5f) * s
            // fisheye: letters near the finger grow and lean left, out from under the thumb
            val near = exp(-((cy - y0) / (s * 2.2f)).let { it * it }) * touch
            val size = restSize * (1f + 0.9f * near)
            text.textSize = size
            text.color = if (i == current && touching) Color.WHITE else Color.argb((255 * (0.62f + 0.38f * near)).toInt(), 255, 255, 255)
            canvas.drawText(letter, railX - dp.dp(26f) * near, cy - (text.descent() + text.ascent()) / 2f, text)
        }
        // the letter, big, in a bubble left of the finger
        if (touch > 0.01f && current >= 0) {
            val r = dp.dp(30f) * (0.6f + 0.4f * touch)
            val bx = railX - dp.dp(84f)
            val by = focus.coerceIn(r, height - r)
            bubble.alpha = (0xE6 * touch).toInt()
            canvas.drawCircle(bx, by, r, bubble)
            bubbleText.alpha = (255 * touch).toInt()
            bubbleText.textSize = dp.dp(30f) * (0.6f + 0.4f * touch)
            canvas.drawText(letters[current].first, bx, by - (bubbleText.descent() + bubbleText.ascent()) / 2f, bubbleText)
        }
    }

    companion object {
        /** The rail letter for [label]: its first letter, accents dropped ("É" → "E"); "#" for digits and the rest. */
        fun letterOf(label: CharSequence): String {
            val first = label.toString().trim().firstOrNull() ?: return "#"
            val plain = java.text.Normalizer.normalize(first.toString(), java.text.Normalizer.Form.NFD).firstOrNull() ?: first
            val up = plain.uppercaseChar()
            return if (up in 'A'..'Z') up.toString() else "#"
        }

        /** Each letter's first app in [labels] (already A–Z), in list order; "#" first if any app has one. */
        fun sections(labels: List<CharSequence>): List<Pair<String, Int>> {
            val seen = LinkedHashMap<String, Int>()
            labels.forEachIndexed { i, l -> seen.putIfAbsent(letterOf(l), i) }
            val hash = seen.remove("#")
            return (if (hash != null) listOf("#" to hash) else emptyList()) + seen.entries.map { it.key to it.value }
        }
    }
}
