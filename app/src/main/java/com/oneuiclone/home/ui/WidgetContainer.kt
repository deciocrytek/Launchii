package com.oneuiclone.home.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.oneuiclone.home.model.HomeItem
import kotlin.math.abs

/** Hosts an AppWidgetHostView in a cell span and detects long-press over it (CheckLongPressHelper). */
@SuppressLint("ViewConstructor")
class WidgetContainer(context: Context, val item: HomeItem.Widget, child: View, pad: Int) : FrameLayout(context) {
    var onLongPress: ((WidgetContainer) -> Unit)? = null
    /** After the long-press, moving past the slop drags the widget (raw screen coordinates). */
    var onDragStart: ((WidgetContainer, Float, Float) -> Boolean)? = null
    var onDragMove: ((Float, Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var dx = 0f
    private var dy = 0f
    private var fired = false
    private val lp = Runnable { fired = true; parent?.requestDisallowInterceptTouchEvent(true); performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onLongPress?.invoke(this) }

    /**
     * How far the widget sits above its cells' centre (px): 0 centred in its rows, or [IconView.labelLift] to line
     * up with the labelled icons beside it. Moves it inside its cells (at layout, as the padding can't go below 0),
     * so drags, ripples and its own translation are untouched.
     */
    var lift = 0
        set(v) { if (field != v) { field = v; requestLayout() } }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (lift != 0) for (i in 0 until childCount) getChildAt(i).offsetTopAndBottom(-lift)
    }

    init {
        setPadding(pad, pad, pad, pad)
        clipToPadding = false
        addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { fired = false; dragging = false; dx = ev.x; dy = ev.y; postDelayed(lp, ViewConfiguration.getLongPressTimeout().toLong()) }
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - dx) > slop || abs(ev.y - dy) > slop) removeCallbacks(lp)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(lp)
        }
        return fired
    }

    /**
     * Reached when the widget itself didn't take the touch (non-interactive area): claim it so the
     * long-press belongs to the widget instead of falling through to the page (which would enter page edit).
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> when {
                !fired -> if (abs(event.x - dx) > slop || abs(event.y - dy) > slop) removeCallbacks(lp)
                dragging -> onDragMove?.invoke(event.rawX, event.rawY)
                abs(event.x - dx) > slop || abs(event.y - dy) > slop ->
                    dragging = onDragStart?.invoke(this, event.rawX, event.rawY) == true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(lp)
                if (dragging) { dragging = false; onDragEnd?.invoke() }
            }
        }
        return true
    }
}
