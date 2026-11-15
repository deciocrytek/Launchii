package com.oneuiclone.home.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import com.oneuiclone.home.device.DeviceProfile
import kotlin.math.roundToInt

/**
 * One UI large (2×2) folder: squircle container aligned to the icon grid with a 2×2 grid of directly
 * tappable apps; with more than 4 apps the 4th slot shows the One UI 8.5 stacked preview (layered
 * mini icons) instead of the three-dot overflow, and tapping it opens the folder.
 */
class LargeFolderIconView(context: Context, dp: DeviceProfile) : FolderIconView(context, dp) {

    /** Container spans from the left edge of one icon to the right edge of the next column's icon. */
    override val iconSize: Int get() = dp.cellWidth + dp.iconSizePx
    override val plateScale get() = 1f
    override val layout get() = INSIDE

    var apps: List<Pair<Bitmap, Any>> = emptyList()
        set(v) { field = v; previews = v.map { it.first }; invalidate() }
    /** Slot not drawn while an app closes into it (its surface stands in). */
    var hiddenSlot = -1
        set(v) { field = v; invalidate() }

    private val slot = RectF()
    private val pad get() = iconSize * 0.075f
    private val inner get() = (iconSize - pad * 3f) / 2f

    override fun childRect(rank: Int, out: RectF) {
        val x = pad + (rank % 2) * (inner + pad)
        val y = pad + (rank / 2) * (inner + pad)
        out.set(x, y, x + inner, y + inner)
    }

    val hasStack get() = apps.size > 4

    /** Index of the app under (x, y) in view coords, -1 for "open folder" (stack / empty / padding). */
    fun hitApp(x: Float, y: Float): Int {
        val l = iconLeft().toFloat(); val t = iconTop().toFloat()
        val shown = if (hasStack) 3 else minOf(4, apps.size)
        for (i in 0 until shown) {
            childRect(i, slot); slot.offset(l, t)
            if (slot.contains(x, y)) return i
        }
        return -1
    }

    /** The slot showing app [index]: its own, or the stack for the rest; -1 if none. */
    fun slotFor(index: Int): Int = when {
        index < 0 -> -1
        hasStack -> minOf(index, 3)
        index < minOf(4, apps.size) -> index
        else -> -1
    }

    /** Slot [i] in icon coordinates. */
    fun slotRect(i: Int, out: Rect) { childRect(i, slot); slot.round(out) }

    /** Slot [i]'s content alone (no container), for the surface an app closes into. */
    fun slotBitmap(i: Int): Bitmap {
        childRect(i, slot)
        val b = Bitmap.createBitmap(slot.width().roundToInt(), slot.height().roundToInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.translate(-slot.left, -slot.top)
        bmpPaint.alpha = 255
        drawSlot(c, i, iconLeft(), iconTop())
        return b
    }

    override fun drawIcon(canvas: Canvas, left: Int, top: Int) {
        ensureShape()
        canvas.save()
        canvas.translate(left.toFloat(), top.toFloat())
        if (containerAlpha > 0f) drawContainer(canvas, left, top)
        if (previewAlpha > 0f) {
            bmpPaint.alpha = (255 * previewAlpha).toInt()
            val slots = if (hasStack) 4 else minOf(4, previews.size)
            for (i in 0 until slots) if (i != hiddenSlot && i !in hiddenPreviews) drawSlot(canvas, i, left, top)
        }
        canvas.restore()
    }

    /** An app, or for slot 3 with more than four apps the 8.5 stacked preview: up to three layered mini icons. */
    private fun drawSlot(canvas: Canvas, i: Int, left: Int, top: Int) {
        childRect(i, slot)
        if (i < 3 || !hasStack) { drawPreview(canvas, previews[i], slot, left, top); return }
        val rest = previews.drop(3).take(3)
        val m = slot.width() * 0.62f
        val step = (slot.width() - m) / 2f
        rest.reversed().forEachIndexed { j, b ->
            val k = rest.size - 1 - j
            val l = slot.left + step * k
            val t = slot.top + step * (2 - k).coerceAtMost(2) * 0.5f + step * 0.5f
            shaped.draw(canvas, b, RectF(l, t, l + m, t + m), bmpPaint)
        }
    }
}
