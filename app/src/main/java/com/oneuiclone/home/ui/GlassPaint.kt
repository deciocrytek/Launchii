package com.oneuiclone.home.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader

/**
 * A paint that fills with [WidgetGlass]: whatever it draws shows the blurred wallpaper that's behind it on screen.
 * Aim it before each draw with the canvas's mapping to the window.
 */
class GlassPaint {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    private val local = Matrix()

    /** Points [paint] at [glass] for a canvas mapped to the window by [toWindow]; false when there's nothing to draw. */
    fun aim(glass: WidgetGlass?, toWindow: Matrix): Boolean {
        if (glass == null) return false
        return if (overrideActive) aimBitmap(overrideBitmap, overrideScale, toWindow)
        else aimBitmap(glass.bitmap, glass.scale, toWindow)
    }

    /** Points [paint] at [glass]'s lighter optical crystal blur (falling back to the standard glass). */
    fun aimCrystal(glass: WidgetGlass?, toWindow: Matrix): Boolean {
        if (glass == null) return false
        if (overrideActive) {
            val b = overrideCrystalBitmap ?: overrideBitmap ?: return false
            val s = if (overrideCrystalBitmap != null) overrideCrystalScale else overrideScale
            return aimBitmap(b, s, toWindow)
        }
        val b = glass.crystalBitmap ?: glass.bitmap ?: return false
        val s = if (glass.crystalBitmap != null) glass.crystalScale else glass.scale
        return aimBitmap(b, s, toWindow)
    }

    companion object {
        /**
         * Active only during a synchronous offscreen Home capture on the main thread (Recent wallpapers previews,
         * widget captures). Views that use glass read these instead of their live [WidgetGlass] without touching
         * or invalidating anything on screen.
         */
        var overrideActive = false
        var overrideBitmap: Bitmap? = null
        var overrideScale = 1f
        var overrideCrystalBitmap: Bitmap? = null
        var overrideCrystalScale = 1f
        /** Optional per-app icon overrides for palette-tinted themes during a tile capture. */
        var overrideIcons: Map<com.oneuiclone.home.model.AppKey, Bitmap>? = null
        /** Optional ARGB folder tint override (0 = keep folder's own) for default frosted folders during a tile capture. */
        var overrideFolderTint = 0
        /** Optional dark-text/dark-dots override during a tile capture (null = keep current). */
        var overrideDarkText: Boolean? = null
        /** Pre-captured raw bitmaps for colour-key widgets (appWidgetId → raw capture) reused across tiles. */
        var overrideKeyedWidgets: Map<Int, com.oneuiclone.home.widgets.WidgetManager.WidgetCapture>? = null

        inline fun <T> withOverride(
            bitmap: Bitmap?,
            scale: Float,
            crystalBitmap: Bitmap? = bitmap,
            crystalScale: Float = scale,
            icons: Map<com.oneuiclone.home.model.AppKey, Bitmap>? = null,
            folderTint: Int = 0,
            darkText: Boolean? = null,
            keyedWidgets: Map<Int, com.oneuiclone.home.widgets.WidgetManager.WidgetCapture>? = null,
            block: () -> T,
        ): T {
            val prevA = overrideActive; val prevB = overrideBitmap; val prevS = overrideScale
            val prevCB = overrideCrystalBitmap; val prevCS = overrideCrystalScale
            val prevI = overrideIcons; val prevF = overrideFolderTint
            val prevD = overrideDarkText; val prevK = overrideKeyedWidgets
            overrideActive = true
            overrideBitmap = bitmap; overrideScale = scale
            overrideCrystalBitmap = crystalBitmap; overrideCrystalScale = crystalScale
            overrideIcons = icons; overrideFolderTint = folderTint
            overrideDarkText = darkText; overrideKeyedWidgets = keyedWidgets
            return try { block() } finally {
                overrideActive = prevA; overrideBitmap = prevB; overrideScale = prevS
                overrideCrystalBitmap = prevCB; overrideCrystalScale = prevCS
                overrideIcons = prevI; overrideFolderTint = prevF
                overrideDarkText = prevD; overrideKeyedWidgets = prevK
            }
        }
    }

    private fun aimBitmap(b: Bitmap?, scale: Float, toWindow: Matrix): Boolean {
        if (b == null) return false
        if (!toWindow.invert(local)) return false
        local.preScale(scale, scale)                                     // glass pixels → window → canvas
        if (bitmap !== b) {
            bitmap = b
            paint.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        paint.shader.setLocalMatrix(local)
        return true
    }
}

/**
 * Fills itself with [glass] (the blurred wallpaper behind it on screen) under [fill], as a closed folder's
 * container does with Style › Blur. The glass is fixed to the window, so it redraws whenever the view moves.
 */
class GlassView(context: android.content.Context, private val glass: WidgetGlass, private val fill: Int) : android.view.View(context) {
    private val glassPaint = GlassPaint()
    private val fillPaint = Paint().apply { color = fill }
    private val toWindow = Matrix()
    private val drawnAt = Matrix()
    private val moved = android.view.ViewTreeObserver.OnPreDrawListener {
        toWindow.reset(); transformMatrixToGlobal(toWindow)
        if (toWindow != drawnAt) invalidate()
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glass.views += this
        viewTreeObserver.addOnPreDrawListener(moved)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(moved)
        glass.views -= this
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        drawnAt.reset(); transformMatrixToGlobal(drawnAt)
        if (glassPaint.aim(glass, drawnAt)) canvas.drawPaint(glassPaint.paint)
        canvas.drawPaint(fillPaint)
    }
}
