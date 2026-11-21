package com.oneuiclone.home.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.icons.IconShape
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Large (2×2) folder — Liquid Crystal Pane (Option B):
 *  - Every app inside renders at 100% regular [DeviceProfile.iconSizePx] on the exact same X/Y grid
 *    baselines as neighboring Home icons.
 *  - A crystal-clear optical glass pane (low-blur wallpaper refraction, cool diagonal specular sheen,
 *    top highlight band, and luminous bevel rim) wraps cleanly around the 4 full-size icons.
 *  - When the folder holds more than 4 apps, swiping horizontally inside the pane pages in-place
 *    through 4-app pages with spring physics and whisper-quiet capsule pips along the bottom edge;
 *    swiping past page 0 or the last page hands seamlessly off to Home's [PagedView].
 *  - Tapping any app icon launches it directly; tapping empty pane space or the bottom pips opens
 *    the full folder.
 */
@SuppressLint("ViewConstructor")
class LargeFolderIconView(context: Context, dp: DeviceProfile) : FolderIconView(context, dp) {

    companion object {
        const val PER_PAGE = 4
        /** Remembers the visible in-place page per folder across Home rebinds & app returns. */
        private val savedPages = HashMap<Long, Int>()
        fun savedPage(folderId: Long): Int = savedPages[folderId] ?: 0
    }

    /** Set so page state survives workspace rebinds. */
    var folderId: Long = -1L
        set(v) {
            field = v
            if (v >= 0L) setPageImmediate(savedPages[v] ?: 0)
        }

    /** Full-size icons (with their own theme/glass plates) and their backing AppInfo/AppKey. */
    var apps: List<Pair<Bitmap, Any>> = emptyList()
        set(v) {
            field = v
            previews = v.map { it.first }
            val maxPage = (pageCount - 1).coerceAtLeast(0)
            if (currentPage > maxPage) setPageImmediate(maxPage) else if (!isSwiping && snapAnim?.isRunning != true) {
                scrollPx = currentPage * paneWidthF()
            }
            invalidate()
        }

    /** Slot on [currentPage] (0..3) hidden while an app closes into it (its floating surface stands in). */
    var hiddenSlot = -1
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Whether the folder explicitly has a custom user-chosen tint colour (vs default/clear glass). */
    var hasCustomTint = false
        set(v) { if (field != v) { field = v; invalidate() } }

    val pageCount get() = ((apps.size + PER_PAGE - 1) / PER_PAGE).coerceAtLeast(1)
    var currentPage = 0; private set
    val pageStartIndex get() = currentPage * PER_PAGE
    var isSwiping = false; private set

    // ---- 1:1 Home grid geometry ----

    private fun labelBlockH(): Float {
        if (!showLabel) return 0f
        val fm = textPaint.fontMetrics
        return (fm.descent - fm.ascent) + dp.iconLabelGapPx
    }

    /** Exact horizontal origin of a regular 1×1 icon inside its cell. */
    private fun cellIconLeft(): Float = (dp.cellWidth - dp.iconSizePx) / 2f

    /** Exact vertical origin of a regular 1×1 icon inside its cell (matches [IconView.iconTop]). */
    private fun cellIconTop(): Float {
        val block = dp.iconSizePx + labelBlockH()
        return ((dp.cellHeight - block) / 2f).coerceAtLeast(0f)
    }

    private fun haloX(): Float = minOf(dp.dp(11f), cellIconLeft() - dp.dp(3f)).coerceAtLeast(dp.dp(4f))
    private fun haloTop(): Float = minOf(dp.dp(11f), cellIconTop() - dp.dp(2f)).coerceAtLeast(dp.dp(4f))
    private fun haloBottom(): Float {
        val belowRow1 = dp.cellHeight - cellIconTop() - dp.iconSizePx
        val reservedForLabel = if (showLabel) labelBlockH() else dp.dp(2f)
        val avail = (belowRow1 - reservedForLabel).coerceAtLeast(dp.dp(5f))
        val want = haloTop() + if (pageCount > 1) dp.dp(7f) else 0f
        return minOf(want, avail).coerceAtLeast(dp.dp(5f))
    }

    private fun paneWidthF(): Float = dp.cellWidth + dp.iconSizePx + haloX() * 2f
    private fun paneHeightF(): Float = dp.cellHeight + dp.iconSizePx + haloTop() + haloBottom()
    val paneRadiusPx: Float get() = dp.dp(32f).coerceAtMost(minOf(paneWidthF(), paneHeightF()) * 0.22f)

    override val iconSize: Int get() = paneWidthF().roundToInt()
    override val iconHeight: Int get() = paneHeightF().roundToInt()
    override val plateScale get() = 1f
    override val layout get() = INSIDE
    override val previewSlots get() = PER_PAGE

    /** Exact pane left in view coords so inner icons land on [cellIconLeft] in cols 0 & 1. */
    override fun iconLeft(): Int = (cellIconLeft() - haloX()).roundToInt()

    /** Exact pane top in view coords so inner icons land on [cellIconTop] in rows 0 & 1. */
    override fun iconTop(): Int = (cellIconTop() - haloTop()).roundToInt()

    /** Rect of slot [rank] (0..3 on the active page) in pane/icon coordinates — exact regular [dp.iconSizePx]. */
    override fun childRect(rank: Int, out: RectF) {
        val s = rank.coerceIn(0, PER_PAGE - 1)
        val x = haloX() + (s % 2) * dp.cellWidth
        val y = haloTop() + (s / 2) * dp.cellHeight
        out.set(x, y, x + dp.iconSizePx, y + dp.iconSizePx)
    }

    /** Absolute app index under (x, y) in view coords on [currentPage], or -1 for empty pane / pips (opens folder). */
    fun hitApp(x: Float, y: Float): Int {
        val l = iconLeft().toFloat(); val t = iconTop().toFloat()
        val start = pageStartIndex
        val shown = minOf(PER_PAGE, (apps.size - start).coerceAtLeast(0))
        for (i in 0 until shown) {
            childRect(i, slot)
            slot.offset(l, t)
            if (slot.contains(x, y)) return start + i
        }
        return -1
    }

    /** Slot (0..3) currently showing app [index] on [currentPage], or -1 if it's on another page. */
    fun slotFor(index: Int): Int {
        val rel = index - pageStartIndex
        return if (rel in 0 until PER_PAGE && index < apps.size) rel else -1
    }

    /** Slot [i] (0..3) in pane/icon coordinates. */
    fun slotRect(i: Int, out: Rect) { childRect(i, slot); slot.round(out) }

    /** Slot [i]'s full-size icon as drawn (including its glass plate if active), for close landing surfaces. */
    fun slotBitmap(i: Int): Bitmap? {
        val idx = pageStartIndex + i
        val bmp = apps.getOrNull(idx)?.first ?: return null
        val sz = dp.iconSizePx
        val out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val g = plateGlass
        if (g != null) {
            childRect(i, slot)
            var x = iconLeft() + slot.left
            var y = iconTop() + slot.top
            var v: android.view.View = this
            while (true) {
                x += v.left; y += v.top
                val p = v.parent as? android.view.View ?: break
                x -= p.scrollX; y -= p.scrollY
                v = p
            }
            slotGlassAt.setTranslate(x, y)
            if (slotGlassPaint.aim(g, slotGlassAt)) {
                ensureSlotPath()
                slotGlassPaint.paint.alpha = 255
                c.drawPath(slotPath!!, slotGlassPaint.paint)
            }
        }
        bmpPaint.alpha = 255
        shaped.draw(c, bmp, RectF(0f, 0f, sz.toFloat(), sz.toFloat()), bmpPaint)
        return out
    }

    fun setPageImmediate(page: Int) {
        snapAnim?.cancel()
        currentPage = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        scrollPx = currentPage * paneWidthF()
        if (folderId >= 0L) savedPages[folderId] = currentPage
        invalidate()
    }

    // ---- In-place horizontal swipe ----

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVel = dp.dp(350f)
    private var scrollPx = 0f
    private var snapAnim: SpringAnimation? = null
    private val scrollHolder = FloatValueHolder(0f)
    private var vt: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var downScroll = 0f
    private var trackingSwipe = false
    private var swipeDecided = false
    private var sendingCancel = false

    private fun paneContains(x: Float, y: Float): Boolean {
        val l = iconLeft().toFloat(); val t = iconTop().toFloat()
        return x >= l && x <= l + paneWidthF() && y >= t && y <= t + paneHeightF()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (sendingCancel) return super.dispatchTouchEvent(ev)
        if (pageCount <= 1) return super.dispatchTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isSwiping = false
                swipeDecided = false
                trackingSwipe = paneContains(ev.x, ev.y)
                if (trackingSwipe) {
                    downX = ev.x; downY = ev.y; lastX = ev.x
                    snapAnim?.cancel()
                    downScroll = scrollPx
                    vt?.recycle()
                    vt = VelocityTracker.obtain().apply { addMovement(ev) }
                    // Tentatively hold parent intercept until we see whether this is a valid folder swipe.
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> if (trackingSwipe) {
                vt?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!swipeDecided) {
                    val thresh = touchSlop * 0.65f
                    if (abs(dx) > thresh || abs(dy) > thresh) {
                        val horiz = abs(dx) > abs(dy) * 1.15f
                        val canScrollDir = (dx < 0f && currentPage < pageCount - 1) || (dx > 0f && currentPage > 0)
                        swipeDecided = true
                        if (horiz && canScrollDir) {
                            isSwiping = true
                            lastX = ev.x
                            // Cancel pending ripple / click / long-press cleanly.
                            sendingCancel = true
                            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                            sendingCancel = false
                            return true
                        } else {
                            // Vertical gesture (Apps / Notifications) or edge swipe: hand straight to Home.
                            trackingSwipe = false
                            parent?.requestDisallowInterceptTouchEvent(false)
                            vt?.recycle(); vt = null
                        }
                    }
                }
                if (isSwiping) {
                    val delta = lastX - ev.x
                    lastX = ev.x
                    val pw = paneWidthF().coerceAtLeast(1f)
                    val maxS = (pageCount - 1) * pw
                    val next = scrollPx + delta
                    scrollPx = when {
                        next < 0f -> next * 0.18f
                        next > maxS -> maxS + (next - maxS) * 0.18f
                        else -> next
                    }
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isSwiping) {
                    val pw = paneWidthF().coerceAtLeast(1f)
                    var target = currentPage
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        vt?.addMovement(ev)
                        vt?.computeCurrentVelocity(1000)
                        val vx = vt?.xVelocity ?: 0f
                        val dragged = scrollPx - downScroll
                        target = when {
                            vx < -flingVel && currentPage < pageCount - 1 -> currentPage + 1
                            vx > flingVel && currentPage > 0 -> currentPage - 1
                            dragged > pw * 0.30f && currentPage < pageCount - 1 -> currentPage + 1
                            dragged < -pw * 0.30f && currentPage > 0 -> currentPage - 1
                            else -> currentPage
                        }
                    }
                    vt?.recycle(); vt = null
                    isSwiping = false
                    trackingSwipe = false
                    swipeDecided = false
                    snapToPage(target)
                    return true
                }
                vt?.recycle(); vt = null
                trackingSwipe = false
                swipeDecided = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun snapToPage(page: Int) {
        val target = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        currentPage = target
        if (folderId >= 0L) savedPages[folderId] = target
        val dest = target * paneWidthF()
        snapAnim?.cancel()
        scrollHolder.value = scrollPx
        snapAnim = SpringAnimation(scrollHolder).apply {
            spring = SpringForce(dest).setStiffness(420f).setDampingRatio(0.86f)
            setMinimumVisibleChange(0.5f)
            addUpdateListener { _, v, _ -> scrollPx = v; invalidate() }
            start()
        }
    }

    // ---- Liquid Crystal Pane & Full-Size Icon Rendering ----

    private val slot = RectF()
    private val paneRect = RectF()
    private val paneClipPath = Path()
    private val crystalGlassPaint = GlassPaint()
    private val slotGlassPaint = GlassPaint()
    private val drawnAt = Matrix()
    private val glassAt = Matrix()
    private val slotGlassAt = Matrix()
    private var slotPath: Path? = null
    private var slotPathSize = -1
    private var slotPathShape = ""

    private val crystalDiagPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crystalTopPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crystalRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val customTintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shaderW = -1f
    private var shaderH = -1f

    private fun ensureCrystalShaders(w: Float, h: Float) {
        if (shaderW == w && shaderH == h) return
        shaderW = w; shaderH = h
        crystalDiagPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0x38FFFFFF, 0x10FFFFFF, 0x05FFFFFF, 0x24FFFFFF),
            floatArrayOf(0f, 0.32f, 0.68f, 1f),
            Shader.TileMode.CLAMP,
        )
        crystalTopPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.14f,
            0x54FFFFFF, 0x00FFFFFF,
            Shader.TileMode.CLAMP,
        )
        crystalRimPaint.strokeWidth = dp.dp(1.5f)
        crystalRimPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xBFFFFFFF.toInt(), 0x2EFFFFFF, 0x85FFFFFF.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun ensureSlotPath() {
        val sz = dp.iconSizePx
        if (slotPathSize != sz || slotPathShape != IconShape.current) {
            slotPathSize = sz
            slotPathShape = IconShape.current
            slotPath = IconShape.path(sz.toFloat(), IconShape.current)
        }
    }

    override fun drawContainer(canvas: Canvas, left: Int, top: Int) {
        val w = paneWidthF()
        val h = paneHeightF()
        val r = paneRadiusPx
        ensureCrystalShaders(w, h)

        canvas.save()
        if (folderBgScale != 1f) canvas.scale(folderBgScale, folderBgScale, w / 2f, h / 2f)

        paneRect.set(0f, 0f, w, h)
        paneClipPath.reset()
        paneClipPath.addRoundRect(paneRect, r, r, Path.Direction.CW)

        drawnAt.reset(); transformMatrixToGlobal(drawnAt)
        val at = glassFixed
        if (at != null) glassAt.set(at) else { glassAt.set(drawnAt); glassAt.preTranslate(left.toFloat(), top.toFloat()) }

        // 1. Crisp low-blur optical wallpaper refraction (photo remains readable, never muddy)
        if (crystalGlassPaint.aimCrystal(glass ?: plateGlass, glassAt)) {
            crystalGlassPaint.paint.alpha = (255 * containerAlpha).toInt()
            canvas.drawPath(paneClipPath, crystalGlassPaint.paint)
        } else {
            // Fallback when wallpaper copy isn't available: subtle bright crystal veil
            customTintPaint.color = 0x28FFFFFF
            customTintPaint.alpha = (0x28 * containerAlpha).toInt()
            canvas.drawPath(paneClipPath, customTintPaint)
        }

        // 2. Optional whisper-light custom folder colour tint (only when the user explicitly picked one)
        if (hasCustomTint) {
            customTintPaint.color = (bgColor and 0x00FFFFFF) or 0xFF000000.toInt()
            customTintPaint.alpha = (44 * containerAlpha * fillOpacity).toInt()
            canvas.drawPath(paneClipPath, customTintPaint)
        }

        // 3. Diagonal cool-white crystal wash + top specular highlight band
        canvas.save()
        canvas.clipPath(paneClipPath)
        crystalDiagPaint.alpha = (255 * containerAlpha).toInt()
        canvas.drawRect(0f, 0f, w, h, crystalDiagPaint)
        crystalTopPaint.alpha = (255 * containerAlpha).toInt()
        canvas.drawRect(0f, 0f, w, h * 0.14f, crystalTopPaint)
        canvas.restore()

        // 4. Crisp diagonal optical bevel rim
        val halfStroke = crystalRimPaint.strokeWidth / 2f
        paneRect.inset(halfStroke, halfStroke)
        crystalRimPaint.alpha = (255 * containerAlpha).toInt()
        canvas.drawRoundRect(paneRect, (r - halfStroke).coerceAtLeast(0f), (r - halfStroke).coerceAtLeast(0f), crystalRimPaint)

        canvas.restore()
    }

    override fun drawIcon(canvas: Canvas, left: Int, top: Int) {
        val w = paneWidthF()
        val h = paneHeightF()
        val r = paneRadiusPx

        canvas.save()
        canvas.translate(left.toFloat(), top.toFloat())

        if (containerAlpha > 0f) drawContainer(canvas, left, top)

        if (previewAlpha > 0f && apps.isNotEmpty()) {
            // Keep scrollPx synced if layout changed while idle
            if (!isSwiping && snapAnim?.isRunning != true) scrollPx = currentPage * w

            paneRect.set(0f, 0f, w, h)
            paneClipPath.reset()
            paneClipPath.addRoundRect(paneRect, r, r, Path.Direction.CW)

            canvas.save()
            canvas.clipPath(paneClipPath)

            val pg = plateGlass
            if (pg != null) {
                drawnAt.reset(); transformMatrixToGlobal(drawnAt)
                ensureSlotPath()
            }
            bmpPaint.alpha = (255 * previewAlpha).toInt()

            val pageFloat = if (w > 0f) scrollPx / w else 0f
            val firstPg = floor(pageFloat).toInt().coerceIn(0, pageCount - 1)
            val lastPg = ceil(pageFloat).toInt().coerceIn(0, pageCount - 1)

            for (p in firstPg..lastPg) {
                val dx = p * w - scrollPx
                val start = p * PER_PAGE
                val count = minOf(PER_PAGE, apps.size - start)
                for (s in 0 until count) {
                    if (p == currentPage && (s == hiddenSlot || s in hiddenPreviews)) continue
                    val pair = apps[start + s]
                    val bmp = (if (GlassPaint.overrideActive) {
                        val k = (pair.second as? com.oneuiclone.home.model.AppInfo)?.key ?: pair.second as? com.oneuiclone.home.model.AppKey
                        k?.let { GlassPaint.overrideIcons?.get(it) }
                    } else null) ?: pair.first
                    childRect(s, slot)
                    slot.offset(dx, 0f)
                    if (pg != null) {
                        val at = glassFixed
                        if (at != null) {
                            slotGlassAt.set(at); slotGlassAt.preTranslate(slot.left, slot.top)
                        } else {
                            slotGlassAt.set(drawnAt); slotGlassAt.preTranslate(left + slot.left, top + slot.top)
                        }
                        if (slotGlassPaint.aim(pg, slotGlassAt)) {
                            slotGlassPaint.paint.alpha = (255 * previewAlpha).toInt()
                            canvas.save()
                            canvas.translate(slot.left, slot.top)
                            canvas.drawPath(slotPath!!, slotGlassPaint.paint)
                            canvas.restore()
                        }
                    }
                    shaped.draw(canvas, bmp, slot, bmpPaint)
                }
            }
            canvas.restore()

            if (pageCount > 1 && containerAlpha > 0f) drawPips(canvas, w, h, pageFloat)
        }

        canvas.restore()
    }

    /** Whisper-quiet capsule page pips centered along the bottom rim of the Liquid Crystal Pane. */
    private fun drawPips(canvas: Canvas, w: Float, h: Float, pageFloat: Float) {
        val dotH = dp.dp(3.2f)
        val activeW = dp.dp(11f)
        val gap = dp.dp(4.5f)
        val widths = FloatArray(pageCount) { i ->
            val focus = (1f - abs(pageFloat - i)).coerceIn(0f, 1f)
            dotH + (activeW - dotH) * focus
        }
        val totalW = widths.sum() + gap * (pageCount - 1)
        var x = (w - totalW) / 2f
        val cy = h - dp.dp(7.5f)
        val rad = dotH / 2f
        for (i in 0 until pageCount) {
            val focus = (1f - abs(pageFloat - i)).coerceIn(0f, 1f)
            val a = (0.45f + 0.55f * focus) * containerAlpha * previewAlpha
            pipPaint.color = Color.WHITE
            pipPaint.alpha = (255 * a).roundToInt()
            canvas.drawRoundRect(x, cy - rad, x + widths[i], cy + rad, rad, rad, pipPaint)
            x += widths[i] + gap
        }
    }
}
