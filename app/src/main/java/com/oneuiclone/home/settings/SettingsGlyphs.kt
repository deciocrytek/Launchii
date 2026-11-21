package com.oneuiclone.home.settings

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/** Material-style glyphs (24×24 path data, non-zero fill) for the settings hub, its sections and search results. */
object Glyphs {
    const val APPS = "M4,8h4V4H4v4zM10,20h4v-4h-4v4zM4,20h4v-4H4v4zM4,14h4v-4H4v4zM10,14h4v-4h-4v4zM16,4v4h4V4h-4zM10,8h4V4h-4v4zM16,14h4v-4h-4v4zM16,20h4v-4h-4v4z"
    const val TOUCH = "M9,11.24V7.5C9,6.12 10.12,5 11.5,5S14,6.12 14,7.5v3.74c1.21,-0.81 2,-2.18 2,-3.74C16,5.01 13.99,3 11.5,3S7,5.01 7,7.5c0,1.56 0.79,2.93 2,3.74zM18.84,15.87l-4.54,-2.26c-0.17,-0.07 -0.35,-0.11 -0.54,-0.11H13v-6c0,-0.83 -0.67,-1.5 -1.5,-1.5S10,6.67 10,7.5v10.74l-3.43,-0.72c-0.08,-0.01 -0.15,-0.03 -0.24,-0.03 -0.31,0 -0.59,0.13 -0.79,0.33l-0.79,0.8 4.94,4.94c0.27,0.27 0.65,0.44 1.06,0.44h6.79c0.75,0 1.33,-0.55 1.44,-1.28l0.75,-5.27c0.01,-0.07 0.02,-0.14 0.02,-0.2 0,-0.62 -0.38,-1.16 -0.91,-1.38z"
    const val SEARCH = "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"
    /** Three circles fanning out: motion. */
    const val MOTION = "M15,2c-2.71,0 -5.05,1.54 -6.22,3.78 -1.28,0.67 -2.34,1.72 -3,3C3.54,9.95 2,12.29 2,15c0,3.87 3.13,7 7,7 2.71,0 5.05,-1.54 6.22,-3.78 1.28,-0.67 2.34,-1.72 3,-3C20.46,14.05 22,11.71 22,9c0,-3.87 -3.13,-7 -7,-7zM9,20c-2.76,0 -5,-2.24 -5,-5 0,-1.12 0.37,-2.16 1,-3 0,3.87 3.13,7 7,7 -0.84,0.63 -1.88,1 -3,1zM12,17c-2.76,0 -5,-2.24 -5,-5 0,-1.12 0.37,-2.16 1,-3 0,3.86 3.13,6.99 7,7 -0.84,0.63 -1.88,1 -3,1zM16.7,13.7c-0.53,0.19 -1.1,0.3 -1.7,0.3 -2.76,0 -5,-2.24 -5,-5 0,-0.6 0.11,-1.17 0.3,-1.7 0.53,-0.19 1.1,-0.3 1.7,-0.3 2.76,0 5,2.24 5,5 0,0.6 -0.11,1.17 -0.3,1.7zM19,12c0,-3.86 -3.13,-6.99 -7,-7 0.84,-0.63 1.88,-1 3,-1 2.76,0 5,2.24 5,5 0,1.12 -0.37,2.16 -1,3z"
    const val FOLDER = "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z"
    const val BELL = "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"
    const val SHIELD = "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4zM12,11.99h7c-0.53,4.12 -3.28,7.79 -7,8.94V12H5V6.3l7,-3.11v8.8z"
    const val RESTORE = "M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08V8H12z"
    const val INFO = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2V7h2v2z"
    const val IMAGE = "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z"
    /** Triangle, circle and square: icon shapes and themes. */
    const val SHAPES = "M12,2l-5.5,9h11zM17.5,13.5m-4.5,0a4.5,4.5 0,1 0,9 0a4.5,4.5 0,1 0,-9 0zM3,13.5h8v8H3z"
    const val BLOCK = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM4,12c0,-4.42 3.58,-8 8,-8 1.85,0 3.55,0.63 4.9,1.69L5.69,16.9C4.63,15.55 4,13.85 4,12zM12,20c-1.85,0 -3.55,-0.63 -4.9,-1.69L18.31,7.1C19.37,8.45 20,10.15 20,12c0,4.42 -3.58,8 -8,8z"
    const val WRENCH = "M22.7,19l-9.1,-9.1c0.9,-2.3 0.4,-5 -1.5,-6.9 -2,-2 -5,-2.4 -7.4,-1.3L9,6 6,9 1.6,4.7C0.4,7.1 0.9,10.1 2.9,12.1c1.9,1.9 4.6,2.4 6.9,1.5l9.1,9.1c0.4,0.4 1,0.4 1.4,0l2.3,-2.3c0.5,-0.4 0.5,-1.1 0.1,-1.4z"
    const val WIDGETS = "M13,13v8h8v-8h-8zM3,21h8v-8H3v8zM3,3v8h8V3H3zM16.66,1.69L11,7.34 16.66,13l5.66,-5.66 -5.66,-5.65z"
    const val DOWNLOAD = "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z"
    const val CHECK = "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z"
    const val CLOSE = "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"
    const val WAND = "M7.5,5.6L10,7 8.6,4.5 10,2 7.5,3.4 5,2l1.4,2.5L5,7zM19.5,15.4L17,14l1.4,2.5L17,19l2.5,-1.4L22,19l-1.4,-2.5L22,14zM22,2l-2.5,1.4L17,2l1.4,2.5L17,7l2.5,-1.4L22,7l-1.4,-2.5zM14.37,7.29c-0.39,-0.39 -1.02,-0.39 -1.41,0L1.29,18.96c-0.39,0.39 -0.39,1.02 0,1.41l2.34,2.34c0.39,0.39 1.02,0.39 1.41,0L16.7,11.05c0.39,-0.39 0.39,-1.02 0,-1.41l-2.33,-2.35zM13.34,12.78l-2.12,-2.12 2.44,-2.44 2.12,2.12 -2.44,2.44z"
    const val BUBBLE = "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z"
    const val BADGE = "M18.5,1.5a4,4 0,1 1,0 8a4,4 0,1 1,0 -8zM13,5H6c-1.1,0 -2,0.9 -2,2v11c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2v-7A6,6 0,0 1,13 5z"
}

/** A [Glyphs] path filled in [color], [sizePx] square, centred in its bounds. */
class GlyphDrawable(pathData: String, color: Int, private val sizePx: Int) : Drawable() {
    private val src: Path = PathParser.createPathFromPathData(pathData)
    private val path = Path()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val s = sizePx / 24f
        path.set(src)
        path.transform(Matrix().apply { setScale(s, s); postTranslate(bounds.exactCenterX() - sizePx / 2f, bounds.exactCenterY() - sizePx / 2f) })
    }

    override fun draw(canvas: Canvas) = canvas.drawPath(path, p)
    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
