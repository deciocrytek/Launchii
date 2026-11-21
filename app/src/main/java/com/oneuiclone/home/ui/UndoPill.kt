package com.oneuiclone.home.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.motion.Easing

/**
 * The glass pill above the dock after something leaves Home (0.263, design opinion 2): "Chrome removed · Undo" for
 * [SHOW_MS], then it fades away. Undo puts it back where it was.
 */
@SuppressLint("ViewConstructor")
class UndoPill(context: Context, dp: DeviceProfile, label: String, private val onUndo: () -> Unit, private val onGone: () -> Unit) :
    LinearLayout(context) {

    private var gone = false
    private val timeout = Runnable { hide() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { cornerRadius = dp.dp(24f); setColor(0xE6262626.toInt()) }
        elevation = dp.dp(6f)
        setPadding(dp.dpi(20f), 0, dp.dpi(6f), 0)
        addView(TextView(context).apply {
            text = "$label removed"
            setTextColor(0xFFFAFAFA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dp.dp(14f))
            typeface = Fonts.ui(context, 500)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (dp.widthPx * 0.5f).toInt()
        })
        addView(TextView(context).apply {
            text = "Undo"
            setTextColor(0xFFFFE2B8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dp.dp(14f))
            typeface = Fonts.ui(context, 700)
            gravity = Gravity.CENTER
            setPadding(dp.dpi(14f), 0, dp.dpi(14f), 0)
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null,
                GradientDrawable().apply { cornerRadius = dp.dp(18f); setColor(0xFFFFFFFF.toInt()) })
            setOnClickListener {
                if (gone) return@setOnClickListener
                performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                onUndo()
                hide()
            }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, dp.dpi(36f)).apply { leftMargin = dp.dpi(8f) })
    }

    fun show() {
        alpha = 0f; translationY = dp(16f); scaleX = 0.92f; scaleY = 0.92f
        animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(Easing.STANDARD).start()
        postDelayed(timeout, SHOW_MS)
    }

    /** Fades out and removes itself (also when replaced by a newer one, or Home leaves). */
    fun hide(now: Boolean = false) {
        if (gone) return
        gone = true
        removeCallbacks(timeout)
        if (now) { (parent as? android.view.ViewGroup)?.removeView(this); onGone(); return }
        animate().cancel()
        animate().alpha(0f).translationY(dp(8f)).setDuration(220).setInterpolator(Easing.STANDARD)
            .withEndAction { (parent as? android.view.ViewGroup)?.removeView(this); onGone() }.start()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object { const val SHOW_MS = 6000L }
}
