package com.oneuiclone.home.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.icons.IconFactory
import com.oneuiclone.home.icons.IconShape

/**
 * One UI folder icon (FolderIconSupplier): squircle container (#99FCFCFF default, 1.7 % inset, faint dark
 * rim) with a 3×3 preview grid — child size 0.21·icon, gap 0.04·icon, centred.
 */
open class FolderIconView(context: Context, dp: DeviceProfile) : IconView(context, dp) {

    companion object {
        const val DEFAULT_BG = 0x99FCFCFF.toInt()          // folder_icon_default_background_color
        /** FolderStyle.IconColorId: white, blue, green, orange, yellow (close_folder_color1..5). */
        val PALETTE = intArrayOf(0x99FCFCFF.toInt(), 0x99AACFEE.toInt(), 0x9999DCC8.toInt(), 0x99EFC898.toInt(), 0x99F3E1AB.toInt())
        const val GRID = 3
        const val SIZE_RATIO = 0.21f
        const val GAP_RATIO = 0.04f

        /** Icons › Folder icons: the apps in the plate, poking out past its edge, or four as on Pixel Launcher. */
        const val INSIDE = "inside"
        const val OUTSIDE = "outside"
        const val PIXEL = "pixel"
        /** Prefs.folderIcons, kept in step by Prefs. Only for round folders (IconShape.container is Circle). */
        @Volatile var style = INSIDE
        /** The style in use: Inside unless folders are round. */
        val shown get() = if (IconShape.container == IconShape.CIRCLE) style else INSIDE
        /** Outside: the plate is this much of the usual one, and the apps poke out past it. */
        const val POP_PLATE = 0.92f
        /** The plate's share of the usual one: smaller when the apps poke out (Pixel's is a round app icon's size). */
        val popPlate get() = if (shown == OUTSIDE) POP_PLATE else 1f
        /** Pixel: apps 5 and up show only inside (Launcher3's MAX_NUM_ITEMS_IN_PREVIEW). */
        const val PIXEL_SLOTS = 4
        /**
         * Pixel's app icons as a share of the plate, and their centres' distance from its middle. Pixel Launcher's four
         * measure 0.40 at 0.24 (the user's screenshot); the user wants them bigger still, so 0.43, nearly touching.
         */
        const val PIXEL_SIZE_FOUR = 0.43f
        const val PIXEL_OFFSET_FOUR = 0.24f
        const val PIXEL_SIZE_FEW = 0.46f
        const val PIXEL_OFFSET_TWO = 0.26f
        const val PIXEL_OFFSET_THREE = 0.27f
        const val PIXEL_SIZE_ONE = 0.52f
        /** Prefs.popupFolders, kept in step by Prefs: which grid the folder opens in (Pixel's preview shows its top left). */
        @Volatile var popupFolders = false

        /** The open folder's columns for [count] apps: the pop-up's near-square grid, or the full folder's. */
        fun folderColumns(count: Int) = if (popupFolders) FolderController.popupGrid(count).first else FolderController.COLS

        /**
         * Pixel: the folder positions (ranks) the four preview slots show, top-left, top-right, bottom-left, bottom-right:
         * the 2×2 at the open folder's top left, so the preview matches where the apps are inside. Three or fewer show in
         * order; four in order too (rearranged into 2×2 only when the folder's grid doesn't already have them so).
         */
        fun pixelRanks(count: Int): List<Int> {
            if (count <= 4) return (0 until minOf(count, PIXEL_SLOTS)).toList()
            val cols = folderColumns(count)
            return if (cols >= 2 && cols + 1 < count) listOf(0, 1, cols, cols + 1) else listOf(0, 1, 2, 3)
        }
    }

    /** This icon's plate, as a share of the usual one ([popPlate]; a large folder keeps its own layout). */
    open val plateScale get() = popPlate
    /** This icon's layout ([shown]; a large folder keeps its own). */
    protected open val layout get() = shown
    /** How many apps the preview shows. */
    open val previewSlots get() = if (layout == PIXEL) PIXEL_SLOTS else GRID * GRID

    /** The folder position (rank) preview slot [slot] shows: the same, but for Pixel ([pixelRanks]). */
    fun rankInSlot(slot: Int): Int = if (layout == PIXEL) pixelRanks(previews.size).getOrElse(slot) { slot } else slot

    /** The preview slot the app at folder position [rank] shows in, or -1 if it isn't in the preview. */
    fun slotOfRank(rank: Int): Int = if (layout == PIXEL) pixelRanks(previews.size).indexOf(rank)
        else if (rank < previewSlots) rank else -1

    var previews: List<Bitmap> = emptyList()
        set(v) { field = v; invalidate() }
    /** Matching app keys for [previews], used when opaque themed folders re-tint in Recent wallpapers previews. */
    var previewKeys: List<com.oneuiclone.home.model.AppKey> = emptyList()
    var bgColor = DEFAULT_BG
        set(v) { field = v; fillPaint.color = v; invalidate() }
    /** 0..1 — used when the folder is opening (preview hidden while icons fly). */
    var previewAlpha = 1f
        set(v) { field = v; invalidate() }
    var containerAlpha = 1f
        set(v) { field = v; invalidate() }
    /** Preview slots left empty while the apps for them fly in (dropped into the folder). */
    var hiddenPreviews: Set<Int> = emptySet()
        set(v) { field = v; invalidate() }

    /**
     * Style › Blur: the blurred wallpaper behind the icon fills the container, under its colour at [fillOpacity].
     * It's fixed to the window, so the icon redraws whenever it moves on screen.
     */
    var glass: WidgetGlass? = null
        set(v) {
            if (field === v) return
            if (isAttachedToWindow) { field?.views?.remove(this); v?.views?.add(this) }
            field = v; invalidate()
        }
    var fillOpacity = 1f
        set(v) { field = v; invalidate() }
    private val glassPaint = GlassPaint()
    private val toWindow = Matrix()
    private val drawnAt = Matrix()
    private val glassAt = Matrix()
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

    protected val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DEFAULT_BG }
    protected val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x24000000
    }
    protected val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    protected val shape = Path()
    private var shapeSize = -1
    private val dst = RectF()

    protected fun ensureShape() {
        if (shapeSize == iconSize) return
        shapeSize = iconSize
        val inset = iconSize * 3f / 180f
        val inner = iconSize - inset * 2
        shape.reset()
        IconShape.path(100f, IconShape.container).transform(Matrix().apply {
            setScale(inner / 100f * 1.02f, inner / 100f * 1.02f)
            postTranslate(inset - inner * 0.01f, inset - inner * 0.01f)
        }, shape)
        rimPaint.strokeWidth = iconSize * 2.5f / 180f
    }

    /**
     * The squircle container (rim, glass, colour) on a canvas already moved to the icon's [left], [top], grown by
     * [folderBgScale] while an app hovers over the folder. It takes in the parts of the apps that poke out past it
     * ([outline]), so the see-through themes (Frosted, Clear Colour), whose previews are their symbols alone
     * (AppInfo.previewIcon), show the folder's own glass and colour there, all one shape, rather than the wallpaper
     * sharp through them (user, 0.235/0.238; 0.234's own glass under each app looked worse).
     */
    protected open fun drawContainer(canvas: Canvas, left: Int, top: Int) {
        val path = outline(folderBgScale * plateScale)
        // no contour: 0.246 gave folders the icons' emboss with Icon contour on; the user wanted none (0.247)
        drawnAt.reset(); transformMatrixToGlobal(drawnAt)
        val at = glassFixed
        if (at != null) glassAt.set(at) else { glassAt.set(drawnAt); glassAt.preTranslate(left.toFloat(), top.toFloat()) }
        if (glassPaint.aim(glass, glassAt)) {
            glassPaint.paint.alpha = (255 * containerAlpha).toInt()
            canvas.drawPath(path, glassPaint.paint)
        }
        val tint = if (GlassPaint.overrideActive && GlassPaint.overrideFolderTint != 0 &&
            (item as? com.oneuiclone.home.model.HomeItem.Folder)?.color == 0 && glass != null) GlassPaint.overrideFolderTint else bgColor
        val prevColor = fillPaint.color
        if (tint != prevColor) fillPaint.color = tint
        fillPaint.alpha = (android.graphics.Color.alpha(tint) * containerAlpha * fillOpacity).toInt()
        canvas.drawPath(path, fillPaint)
        if (tint != prevColor) fillPaint.color = prevColor
    }

    private val outlinePath = Path()
    private var outlineKey: List<Any>? = null
    private val outlineRect = RectF()

    /**
     * The container's outline at [scale] about its centre, in icon coordinates: its shape, joined with each preview
     * app's shape where it shows, so the parts poking out are the folder too. Cached until something moves.
     */
    private fun outline(scale: Float): Path {
        // every layout: Pixel's four poke past the plate a little too (0.235 joined them only for Outside)
        val n = if (previewAlpha > 0f) minOf(previewSlots, previews.size) else 0
        val key = listOf(iconSize, scale, n, layout, IconShape.current, IconShape.container, hiddenPreviews)
        if (key == outlineKey) return outlinePath
        outlineKey = key
        outlinePath.reset()
        shape.transform(Matrix().apply { setScale(scale, scale, iconSize / 2f, iconSize / 2f) }, outlinePath)
        if (n > 0) outlinePath.op(appShapes(), Path.Op.UNION)
        return outlinePath
    }

    /**
     * The preview apps' shapes, joined, in icon coordinates: what the outline adds to the plate where they poke past it
     * (Pixel's four, Outside). A pop-up or full folder closing into the icon lands on the plate and these together, so
     * the icon taking over doesn't add them in one frame (recording 7000: Pixel folders landed as a plain circle).
     */
    fun appShapes(): Path {
        val out = Path()
        for (i in 0 until minOf(previewSlots, previews.size)) {
            if (i in hiddenPreviews) continue
            childRect(i, outlineRect)
            val app = IconShape.path(outlineRect.width())
            app.offset(outlineRect.left, outlineRect.top)
            out.op(app, Path.Op.UNION)
        }
        return out
    }

    /** Position of preview child [rank] relative to icon top-left. */
    open fun childRect(rank: Int, out: RectF) {
        when (layout) {
            OUTSIDE -> { poppedRect(rank, out); return }
            PIXEL -> { pixelRect(rank, out); return }
        }
        val f = iconSize.toFloat()
        val gap = GAP_RATIO * f
        val size = SIZE_RATIO * f
        val start = (f - (GRID - 1) * gap - GRID * size) / 2f
        val step = gap + size
        // a round folder with only one or two rows centres them, rather than leaving the third row's space below
        val rows = (previews.size.coerceIn(1, GRID * GRID) + GRID - 1) / GRID
        val lift = if (IconShape.container == IconShape.CIRCLE) (GRID - rows) * step / 2f else 0f
        val x = start + (rank % GRID) * step
        val y = start + (rank / GRID) * step + lift
        out.set(x, y, x + size, y + size)
    }

    /**
     * Outside: centred rows on a pitch in the plate's size, the icons big enough that the outer ones poke out past
     * the circle (two: side by side, bigger, as on Pixel; four at most: 2×2; five or six: 3×2; up to nine: 3×3). A
     * short last row is centred.
     */
    private fun poppedRect(rank: Int, out: RectF) {
        val n = previews.size.coerceIn(1, GRID * GRID)
        val cols = if (n <= 4) 2 else 3
        // (0.207: about 8 % bigger and set further out than 0.199's 0.6/0.508/0.34/0.295 and 0.42/0.36/0.31/0.27)
        val pitch = when { n <= 2 -> 0.64f; n <= 4 -> 0.55f; n <= 6 -> 0.37f; else -> 0.32f }
        val size = when { n <= 2 -> 0.45f; n <= 4 -> 0.39f; n <= 6 -> 0.335f; else -> 0.29f }
        // the plate's side: the mask inset 3/180 each side (see ensureShape), at plateScale
        val plate = iconSize * (1f - 6f / 180f) * plateScale
        val rows = (n + cols - 1) / cols
        val r = rank / cols
        val inRow = if (r == rows - 1) n - r * cols else cols
        val cx = iconSize / 2f + (rank % cols - (inRow - 1) / 2f) * pitch * plate
        val cy = iconSize / 2f + (r - (rows - 1) / 2f) * pitch * plate
        val h = size * plate / 2f
        out.set(cx - h, cy - h, cx + h, cy + h)
    }

    /**
     * Pixel: placed as Pixel Launcher places them, measured from its Home screen (the user's screenshot), a little
     * bigger: four in a tight 2×2, centres [PIXEL_OFFSET_FOUR] of the plate from its middle; three in a triangle, the
     * top one first, clockwise; two side by side; one in the middle. Four in reading order.
     */
    private fun pixelRect(rank: Int, out: RectF) {
        val n = previews.size.coerceIn(1, PIXEL_SLOTS)
        val plate = iconSize * (1f - 6f / 180f) * plateScale
        val size = when (n) { 1 -> PIXEL_SIZE_ONE; 4 -> PIXEL_SIZE_FOUR; else -> PIXEL_SIZE_FEW } * plate
        val c = iconSize / 2f
        val (dx, dy) = when (n) {
            1 -> 0f to 0f
            2 -> (if (rank == 0) -PIXEL_OFFSET_TWO else PIXEL_OFFSET_TWO) to 0f
            3 -> when (rank) { 0 -> 0f to -PIXEL_OFFSET_THREE; 1 -> PIXEL_OFFSET_THREE * 0.866f to PIXEL_OFFSET_THREE * 0.5f
                else -> -PIXEL_OFFSET_THREE * 0.866f to PIXEL_OFFSET_THREE * 0.5f }
            else -> (if (rank % 2 == 0) -PIXEL_OFFSET_FOUR else PIXEL_OFFSET_FOUR) to (if (rank < 2) -PIXEL_OFFSET_FOUR else PIXEL_OFFSET_FOUR)
        }
        val x = c + dx * plate
        val y = c + dy * plate
        out.set(x - size / 2f, y - size / 2f, x + size / 2f, y + size / 2f)
    }

    override fun drawIcon(canvas: Canvas, left: Int, top: Int) {
        ensureShape()
        canvas.save()
        canvas.translate(left.toFloat(), top.toFloat())
        if (containerAlpha > 0f) drawContainer(canvas, left, top)
        if (previewAlpha > 0f) {
            bmpPaint.alpha = (255 * previewAlpha).toInt()
            for (i in 0 until minOf(previewSlots, previews.size)) {
                if (i in hiddenPreviews) continue
                val rank = rankInSlot(i)
                val b = (if (GlassPaint.overrideActive && GlassPaint.overrideFolderTint == 0) {
                    previewKeys.getOrNull(rank)?.let { GlassPaint.overrideIcons?.get(it) }
                } else null) ?: previews.getOrNull(rank) ?: continue
                childRect(i, dst)
                drawPreview(canvas, b, dst, left, top)
            }
        }
        canvas.restore()
    }

    /** Preview app [b] in [rect], on a canvas moved to the icon's [left], [top]. */
    protected fun drawPreview(canvas: Canvas, b: Bitmap, rect: RectF, @Suppress("UNUSED_PARAMETER") left: Int, @Suppress("UNUSED_PARAMETER") top: Int) {
        shaped.draw(canvas, b, rect, bmpPaint)
    }

    fun iconRectOnScreen(out: Rect) = iconBoundsOnScreen(out)

    /** While set (by [renderIcon]): icon → window for the glass, in place of where the icon is drawn now. */
    protected var glassFixed: Matrix? = null

    /**
     * The folder icon as drawn, for the surface an app closes into. [atRest] is the icon's rect in the window once
     * Home is back at rest: the glass is the blurred wallpaper there, not where the icon is now. When the contract
     * comes, Home still waits at the open scale (0.85 about the centre), so the surface's glass came from a lighter
     * part of the wallpaper and snapped to the real one when the icon took over on landing (recording 6937).
     */
    fun renderIcon(atRest: Rect? = null): Bitmap {
        val w = iconSize
        val h = iconHeight
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        glassFixed = atRest?.let { r ->
            Matrix().apply { setScale(r.width() / w.toFloat(), r.height() / h.toFloat()); postTranslate(r.left.toFloat(), r.top.toFloat()) }
        }
        try { drawIcon(Canvas(b), 0, 0) } finally { glassFixed = null }
        return b
    }
}
