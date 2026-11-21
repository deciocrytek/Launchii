package com.oneuiclone.home.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.max

/**
 * Draws the opening effects (Aura, Shutter, Orbit, Anticipate, Scan, Mist) over a wallpaper picture: for Launchii's
 * live wallpaper (its drawing thread) and for the App opening animation page's preview (0.262), so both show exactly
 * the same thing. [glow] and [palette] come from [buildGlow] / [buildPalette] for the effect's icon.
 */
class OpenEffectPainter(density: Float) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var matrix = Matrix()
    /** Anticipate never zooms the picture out past the screen's edges: the smallest zoom that still covers it. */
    private var minZoom = 1f
    private val blurNode = android.graphics.RenderNode("open-effect")
    private val fxPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scanPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // Scan's frosted band: a little dimmer and richer under its haze
        colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply {
            setSaturation(SCAN_SATURATION)
            postConcat(android.graphics.ColorMatrix().apply { setScale(SCAN_BRIGHTNESS, SCAN_BRIGHTNESS, SCAN_BRIGHTNESS, 1f) })
        })
    }
    private val fxRect = android.graphics.RectF()
    private val splashPx = 160f * density
    /** Aura's glow and the icon's colours (Orbit, Scan, Mist) for the effect being drawn; null until built. */
    @Volatile var glow: Bitmap? = null
    @Volatile var palette: IntArray? = null
    /** A soft white disc for Orbit's glows and Mist's clouds, made once. */
    private var disc: Bitmap? = null

    /**
     * One frame of [a]. A fade-out ([LiveWallpaper.OpenEffect.endAt]) draws the plain picture and the effect's
     * frame over it, more see-through each frame. Distances follow the mockups, drawn on a 300 px wide phone.
     */
    fun draw(c: android.graphics.Canvas, b: Bitmap, matrix: Matrix, a: LiveWallpaper.OpenEffect, w: Float, h: Float,
             now: Long = android.os.SystemClock.uptimeMillis(), minZoom: Float = 1f) {
        this.matrix = matrix
        this.minZoom = minZoom
        val m = (now - a.startAt).toFloat()
        val out = if (a.endAt == 0L) 1f else 1f - ((now - a.endAt) / EFFECT_FADE_MS.toFloat()).coerceIn(0f, 1f)
        if (out < 1f) {
            c.drawBitmap(b, matrix, paint)
            if (out <= 0f) return
            c.saveLayerAlpha(0f, 0f, w, h, (out * 255).toInt())
        }
        val u = w / 300f
        when (a.kind) {
            LiveWallpaper.Kind.AURA -> drawAura(c, b, a, m, w, h)
            LiveWallpaper.Kind.SHUTTER -> drawShutter(c, b, a, m, w, h, now)
            LiveWallpaper.Kind.ORBIT -> drawOrbit(c, b, a, m, w, h, u)
            LiveWallpaper.Kind.ANTICIPATE -> drawAnticipate(c, b, m, w, h)
            LiveWallpaper.Kind.SCAN -> drawScan(c, b, a, m, w, h, u)
            LiveWallpaper.Kind.MIST -> drawMist(c, b, m, w, h, u)
        }
        if (out < 1f) c.restore()
    }

    /**
     * The picture, blurred by [sigma] px (at a quarter of the size once it's big: a blur hides it) and darkened by
     * [dim]; [p] can add a colour filter.
     */
    private fun drawPicture(c: android.graphics.Canvas, b: Bitmap, w: Float, h: Float, sigma: Float, dim: Float, p: Paint = paint) {
        if (sigma < 0.5f) c.drawBitmap(b, matrix, p)
        else {
            val d = if (sigma >= BLUR_SMALL_FROM) BLUR_DOWNSCALE else 1f
            blurNode.setPosition(0, 0, kotlin.math.ceil(w / d).toInt(), kotlin.math.ceil(h / d).toInt())
            blurNode.pivotX = 0f; blurNode.pivotY = 0f
            blurNode.scaleX = d; blurNode.scaleY = d
            val rc = blurNode.beginRecording()
            rc.scale(1f / d, 1f / d)
            rc.drawBitmap(b, matrix, p)
            blurNode.endRecording()
            blurNode.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(sigma / d, sigma / d, android.graphics.Shader.TileMode.CLAMP))
            c.drawRenderNode(blurNode)
        }
        if (dim > 0.001f) c.drawColor(Color.argb(dim, 0f, 0f, 0f))
    }

    /** Aura: the picture blurs and darkens, and the glow rises from the icon to the middle of the screen, then breathes. */
    private fun drawAura(c: android.graphics.Canvas, b: Bitmap, a: LiveWallpaper.OpenEffect, m: Float, w: Float, h: Float) {
        val p = ease(m, AURA_MS)
        drawPicture(c, b, w, h, AURA_BLUR * w * p, AURA_DIM * p)
        val g = glow ?: return
        val breath = if (m > AURA_MS) 1f + AURA_BREATH * kotlin.math.sin((m - AURA_MS) / AURA_BREATH_MS * 2f * Math.PI.toFloat()) else 1f
        val size = (a.fromSize + (AURA_GLOW * w - a.fromSize) * p) * breath
        val cx = a.fromX + (w / 2f - a.fromX) * p
        val cy = a.fromY + (h * AURA_GLOW_Y - a.fromY) * p
        fxPaint.colorFilter = null
        fxPaint.alpha = (255f * (p * 1.4f).coerceAtMost(1f) * AURA_GLOW_ALPHA).toInt()
        fxRect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        c.drawBitmap(g, null, fxRect, fxPaint)
    }

    /**
     * Shutter: slats in the splash colour roll down, first on the icon's row and then outward, and the icon slides
     * to the middle at splash size, so the screen ends as the app's splash.
     */
    private fun drawShutter(c: android.graphics.Canvas, b: Bitmap, a: LiveWallpaper.OpenEffect, m: Float, w: Float, h: Float, now: Long) {
        c.drawBitmap(b, matrix, paint)
        val rowH = h / SHUTTER_SLATS
        val row = (a.fromY / rowH).toInt().coerceIn(0, SHUTTER_SLATS - 1)
        fxPaint.colorFilter = null
        fxPaint.color = a.splash
        for (i in 0 until SHUTTER_SLATS) {
            val p = ease(m - kotlin.math.abs(i - row) * SHUTTER_STAGGER_MS, SHUTTER_SLAT_MS)
            if (p > 0f) c.drawRect(0f, i * rowH, w, i * rowH + (rowH + 1f) * p, fxPaint)
        }
        val ip = ease(m - SHUTTER_ICON_DELAY_MS, SHUTTER_ICON_MS)
        val size = a.fromSize + (splashPx - a.fromSize) * ip
        val cx = a.fromX + (w / 2f - a.fromX) * ip
        val cy = a.fromY + (h / 2f - a.fromY) * ip
        fxRect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        // the Home icon until the full one has loaded, then a quick crossfade into it
        val big = a.splashIcon
        val bigAlpha = if (big == null) 0f else ((now - a.splashIconAt) / SPLASH_ICON_SWAP_MS).coerceIn(0f, 1f)
        fxPaint.alpha = ((1f - bigAlpha) * 255).toInt()
        if (fxPaint.alpha > 0) c.drawBitmap(a.icon, null, fxRect, fxPaint)
        if (big != null) { fxPaint.alpha = (bigAlpha * 255).toInt(); c.drawBitmap(big, null, fxRect, fxPaint) }
        fxPaint.alpha = 255
    }

    /** Orbit: lights in the icon's colours orbit a point that glides from the icon to the middle of a dark, blurred picture. */
    private fun drawOrbit(c: android.graphics.Canvas, b: Bitmap, a: LiveWallpaper.OpenEffect, m: Float, w: Float, h: Float, u: Float) {
        val p = ease(m, ORBIT_MS)
        drawPicture(c, b, w, h, ORBIT_BLUR * u * p, ORBIT_DIM * p)
        val r = ORBIT_RADIUS * u * ease(m, ORBIT_OPEN_MS)
        val cx = a.fromX + (w / 2f - a.fromX) * p
        val cy = a.fromY + (h / 2f - a.fromY) * p
        val turn = m / ORBIT_TURN_MS * 2f * Math.PI.toFloat()
        val cols = palette ?: intArrayOf(Color.WHITE)
        val shown = (m / 80f).coerceIn(0f, 1f)
        val glowDisc = disc()
        for (i in 0 until ORBIT_DOTS) {
            val ang = turn + i.toFloat() / ORBIT_DOTS * 2f * Math.PI.toFloat()
            val size = (8f + 4f * kotlin.math.sin(turn * 0.5f + i)) * u
            val x = cx + kotlin.math.cos(ang) * r
            val y = cy + kotlin.math.sin(ang) * r
            val g = size + 28f * u
            fxRect.set(x - g / 2f, y - g / 2f, x + g / 2f, y + g / 2f)
            fxPaint.colorFilter = android.graphics.PorterDuffColorFilter(withAlpha(cols[i % cols.size], 0.85f * shown), android.graphics.PorterDuff.Mode.SRC_IN)
            fxPaint.alpha = 255
            c.drawBitmap(glowDisc, null, fxRect, fxPaint)
            fxPaint.colorFilter = null
            fxPaint.color = withAlpha(Color.WHITE, shown)
            c.drawCircle(x, y, size / 2f, fxPaint)
        }
        fxPaint.alpha = 255
    }

    /**
     * Anticipate: the picture pulls back a little, then lunges toward you and darkens, then breathes. Never zoomed
     * out past the screen's edges.
     */
    private fun drawAnticipate(c: android.graphics.Canvas, b: Bitmap, m: Float, w: Float, h: Float) {
        val wind = 1f - (1f - (m / ANTICIPATE_WIND_MS).coerceIn(0f, 1f)).let { it * it * it }
        val go = ease(m - ANTICIPATE_WIND_MS, ANTICIPATE_GO_MS)
        val breath = if (m > 450f) 1f + 0.015f * kotlin.math.sin((m - 450f) / 1200f * 2f * Math.PI.toFloat()) else 1f
        val z = max(((1f + (ANTICIPATE_WIND - 1f) * wind) + (ANTICIPATE_LUNGE - ANTICIPATE_WIND) * go) * breath, minZoom)
        c.save()
        c.scale(z, z, w / 2f, h / 2f)
        c.drawBitmap(b, matrix, paint)
        c.restore()
        if (go > 0f) c.drawColor(Color.argb(ANTICIPATE_DIM * go, 0f, 0f, 0f))
    }

    /**
     * Scan: two glowing lines split from the icon's row to the edges, and the band between them turns to frosted
     * glass (blurred, under a light haze tinted with the icon's colour); then one line glides up and down.
     */
    private fun drawScan(c: android.graphics.Canvas, b: Bitmap, a: LiveWallpaper.OpenEffect, m: Float, w: Float, h: Float, u: Float) {
        c.drawBitmap(b, matrix, paint)
        val p = ease(m, SCAN_MS)
        val top = a.fromY + (-4f * u - a.fromY) * p
        val bot = a.fromY + (h + 4f * u - a.fromY) * p
        val tint = palette?.firstOrNull() ?: Color.WHITE
        if (bot - top > 0.5f) {
            c.save()
            c.clipRect(0f, top, w, bot)
            drawPicture(c, b, w, h, SCAN_FROST * u, 0f, scanPaint)
            c.drawColor(withAlpha(Color.WHITE, SCAN_HAZE))
            c.drawColor(withAlpha(tint, SCAN_TINT))
            c.restore()
        }
        if (p < 0.98f) { scanLine(c, top, w, u, tint, 1f); scanLine(c, bot, w, u, tint, 1f) }
        if (m > SCAN_MS + 30f) {
            val y = h / 2f - kotlin.math.cos((m - SCAN_MS - 30f) / SCAN_SWEEP_MS * 2f * Math.PI.toFloat()) * (h / 2f - 40f * u)
            scanLine(c, y, w, u, tint, 0.6f)
        }
    }

    private fun scanLine(c: android.graphics.Canvas, y: Float, w: Float, u: Float, tint: Int, alpha: Float) {
        fxPaint.colorFilter = null
        fxPaint.shader = android.graphics.LinearGradient(0f, y - 14f * u, 0f, y + 14f * u,
            intArrayOf(Color.TRANSPARENT, withAlpha(tint, 0.9f * alpha), Color.TRANSPARENT), null, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, y - 14f * u, w, y + 14f * u, fxPaint)
        fxPaint.shader = null
        fxPaint.color = withAlpha(Color.WHITE, alpha)
        c.drawRect(0f, y - u, w, y + u, fxPaint)
    }

    /** Mist: soft clouds, white and in the icon's colour, roll in from the edges toward the middle and drift. */
    private fun drawMist(c: android.graphics.Canvas, b: Bitmap, m: Float, w: Float, h: Float, u: Float) {
        val p = ease(m, MIST_MS)
        drawPicture(c, b, w, h, MIST_BLUR * u * p, MIST_DIM * p)
        val tint = palette?.firstOrNull() ?: Color.WHITE
        val cloud = disc()
        val shown = (p * 1.5f).coerceAtMost(1f)
        MIST_CLOUDS.forEachIndexed { i, k ->
            val d = (520f + (k.near - 520f) * p) * u
            val drift = 12f * u * kotlin.math.sin(m / 2000f * 2f * Math.PI.toFloat() + k.phase)
            val x = w / 2f + kotlin.math.cos(k.angle) * d + drift
            val y = h / 2f + kotlin.math.sin(k.angle) * d * 1.6f
            val size = (k.size + 60f) * u
            val col = if (i % 2 == 1) withAlpha(Color.WHITE, 0.6f * shown) else withAlpha(tint, 0.5f * shown)
            fxPaint.colorFilter = android.graphics.PorterDuffColorFilter(col, android.graphics.PorterDuff.Mode.SRC_IN)
            fxRect.set(x - size / 2f, y - size / 2f, x + size / 2f, y + size / 2f)
            c.drawBitmap(cloud, null, fxRect, fxPaint)
        }
        fxPaint.colorFilter = null
    }

    private fun ease(m: Float, d: Float) = AURA_CURVE.getInterpolation((m / d).coerceIn(0f, 1f))
    private fun withAlpha(c: Int, a: Float) = Color.argb((Color.alpha(c) * a.coerceIn(0f, 1f)).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    /** A soft white disc, opaque in the middle and gone at the rim. */
    private fun disc(): Bitmap = disc ?: run {
        val s = 128
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.RadialGradient(s / 2f, s / 2f, s / 2f, intArrayOf(Color.WHITE, 0x99FFFFFF.toInt(), 0x33FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), android.graphics.Shader.TileMode.CLAMP)
        android.graphics.Canvas(bmp).drawCircle(s / 2f, s / 2f, s / 2f, p)
        (bmp.copy(Bitmap.Config.HARDWARE, false) ?: bmp).also { disc = it }
    }

    /**
     * Up to three of [icon]'s colours, most common first: its colourful pixels grouped by hue (12 groups) and
     * brightened so they glow on a dark screen. A grey icon gives its lightest colour, or white.
     */
    fun buildPalette(icon: Bitmap): IntArray {
        val soft = if (icon.config == Bitmap.Config.HARDWARE) icon.copy(Bitmap.Config.ARGB_8888, false) else icon
        val n = 16
        val px = IntArray(n * n)
        Bitmap.createScaledBitmap(soft, n, n, true).getPixels(px, 0, n, 0, 0, n, n)
        val count = IntArray(12); val sr = FloatArray(12); val sg = FloatArray(12); val sb = FloatArray(12)
        var lightest = Color.WHITE; var lightLum = -1f
        val hsv = FloatArray(3)
        for (col in px) {
            if (Color.alpha(col) < 128) continue
            val r = Color.red(col) / 255f; val g = Color.green(col) / 255f; val bl = Color.blue(col) / 255f
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * bl
            if (lum > lightLum) { lightLum = lum; lightest = col }
            if (maxOf(r, g, bl) - minOf(r, g, bl) < 0.25f) continue
            Color.colorToHSV(col, hsv)
            val k = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
            count[k]++; sr[k] += r; sg[k] += g; sb[k] += bl
        }
        val top = (0 until 12).filter { count[it] >= 2 }.sortedByDescending { count[it] }.take(3)
        if (top.isEmpty()) return intArrayOf(lightest or 0xFF000000.toInt())
        return top.map { k ->
            Color.colorToHSV(Color.rgb(sr[k] / count[k], sg[k] / count[k], sb[k] / count[k]), hsv)
            hsv[2] = maxOf(hsv[2], 0.85f)
            Color.HSVToColor(hsv)
        }.toIntArray()
    }

    /**
     * A soft round glow in [icon]'s colours, laid out as in the icon (the icon fills its middle half): each
     * pixel is a Gaussian blend of a 12 × 12 sampling of the icon, weighted toward its colourful pixels, so a
     * white plate with a red M glows red rather than white. A grey icon glows in its lightest parts. Runs on
     * [glowWorker] (separable, about 60 k multiply-adds).
     */
    fun buildGlow(icon: Bitmap): Bitmap {
        val soft = if (icon.config == Bitmap.Config.HARDWARE) icon.copy(Bitmap.Config.ARGB_8888, false) else icon
        val n = GLOW_SAMPLES
        val px = IntArray(n * n)
        Bitmap.createScaledBitmap(soft, n, n, true).getPixels(px, 0, n, 0, 0, n, n)
        val cw = FloatArray(n * n); val cr = FloatArray(n * n); val cg = FloatArray(n * n); val cb = FloatArray(n * n)
        for (i in px.indices) {
            val col = px[i]
            val al = Color.alpha(col) / 255f
            if (al < 0.1f) continue
            val r = Color.red(col) / 255f; val g = Color.green(col) / 255f; val bl = Color.blue(col) / 255f
            val chroma = maxOf(r, g, bl) - minOf(r, g, bl)
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * bl
            val wt = al * (0.03f + 6f * chroma + 0.3f * lum)
            cw[i] = wt; cr[i] = wt * r; cg[i] = wt * g; cb[i] = wt * bl
        }
        val s = GLOW_PX
        // separable Gaussian: weight of sample j (at 0.25 + 0.5 * (j + 0.5) / n across the glow) at pixel x
        val sigma2 = 2f * 0.12f * 0.12f
        val gauss = FloatArray(s * n) { idx ->
            val u = (idx / n + 0.5f) / s
            val pj = 0.25f + 0.5f * ((idx % n) + 0.5f) / n
            kotlin.math.exp(-(u - pj) * (u - pj) / sigma2)
        }
        // down the columns first (per glow row y, per sample column), then across
        val tw = FloatArray(s * n); val tr = FloatArray(s * n); val tg = FloatArray(s * n); val tb = FloatArray(s * n)
        for (y in 0 until s) for (col in 0 until n) {
            var w = 0f; var r = 0f; var g = 0f; var bl = 0f
            for (row in 0 until n) {
                val gy = gauss[y * n + row]; val i = row * n + col
                w += gy * cw[i]; r += gy * cr[i]; g += gy * cg[i]; bl += gy * cb[i]
            }
            val t = y * n + col
            tw[t] = w; tr[t] = r; tg[t] = g; tb[t] = bl
        }
        val out = IntArray(s * s)
        for (y in 0 until s) for (x in 0 until s) {
            val d = kotlin.math.hypot((x + 0.5f) / s - 0.5f, (y + 0.5f) / s - 0.5f) / 0.5f
            if (d >= 1f) continue
            var r = 0f; var g = 0f; var bl = 0f; var wsum = 0f
            for (col in 0 until n) {
                val gx = gauss[x * n + col]; val t = y * n + col
                wsum += gx * tw[t]; r += gx * tr[t]; g += gx * tg[t]; bl += gx * tb[t]
            }
            if (wsum <= 1e-12f) continue
            // brighter toward the middle, fading to nothing at the rim
            val e = d * d * (3f - 2f * d)
            val alpha = Math.pow((1f - e).toDouble(), 1.3).toFloat()
            val lift = 0.15f * (1f - d) * (1f - d)
            r /= wsum; g /= wsum; bl /= wsum
            // a little more colour than the blend (averaging greys it), then lighter toward the middle
            val grey = 0.2126f * r + 0.7152f * g + 0.0722f * bl
            fun ch(v: Float) = (grey + (v - grey) * GLOW_SATURATION).let { it + (1f - it) * lift }.coerceIn(0f, 1f)
            out[y * s + x] = Color.argb(alpha, ch(r), ch(g), ch(bl))
        }
        val bmp = Bitmap.createBitmap(out, s, s, Bitmap.Config.ARGB_8888)
        return bmp.copy(Bitmap.Config.HARDWARE, false) ?: bmp
    }

}

/** The opening effects' curve (Material emphasized decelerate), as in the 2026-11-06 mockups. */
private val AURA_CURVE = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
/** Fade back to the plain picture when Home returns uncovered, and the longest an uncovered effect stays up. */
internal const val EFFECT_FADE_MS = 250L
internal const val EFFECT_MAX_MS = 8000L
/** Aura: the glow and blur come in over [AURA_MS]. */
private const val AURA_MS = 420f
/** The picture's blur (sigma, as a fraction of the screen width) and darkening at full strength. */
private const val AURA_BLUR = 10f / 300f
private const val AURA_DIM = 0.5f
/** The glow's final width (fraction of the screen width), height of its centre (fraction of the screen) and opacity. */
private const val AURA_GLOW = 440f / 300f
private const val AURA_GLOW_Y = 0.47f
private const val AURA_GLOW_ALPHA = 0.95f
/** Once up, the glow breathes ±5 % every 1.1 s while the app is still starting. */
private const val AURA_BREATH = 0.05f
private const val AURA_BREATH_MS = 1100f
/** Shutter (mockups 4, #1): slats roll down over [SHUTTER_SLAT_MS], each row [SHUTTER_STAGGER_MS] after the one nearer the icon. */
private const val SHUTTER_SLATS = 14
private const val SHUTTER_SLAT_MS = 220f
private const val SHUTTER_STAGGER_MS = 28f
private const val SHUTTER_ICON_DELAY_MS = 100f
private const val SHUTTER_ICON_MS = 420f
private const val SPLASH_ICON_SWAP_MS = 120f
/** Orbit (#2): 6 lights, ring radius in mockup px (300 px wide), one turn every [ORBIT_TURN_MS]. */
private const val ORBIT_DOTS = 6
private const val ORBIT_MS = 420f
private const val ORBIT_OPEN_MS = 300f
private const val ORBIT_RADIUS = 58f
private const val ORBIT_TURN_MS = 900f
private const val ORBIT_BLUR = 8f
private const val ORBIT_DIM = 0.45f
/** Anticipate (#5): back to [ANTICIPATE_WIND] over the wind-up, then forward to [ANTICIPATE_LUNGE]. */
private const val ANTICIPATE_WIND_MS = 110f
private const val ANTICIPATE_GO_MS = 320f
private const val ANTICIPATE_WIND = 0.97f
private const val ANTICIPATE_LUNGE = 1.12f
private const val ANTICIPATE_DIM = 0.25f
/** Scan (#6): the lines reach the edges over [SCAN_MS]; then one sweeps the screen every [SCAN_SWEEP_MS]. */
private const val SCAN_MS = 450f
private const val SCAN_SWEEP_MS = 2400f
/** The band is frosted glass (user, 2026-11-06): blur in mockup px, a white haze and the icon's colour over it. */
private const val SCAN_FROST = 16f
private const val SCAN_BRIGHTNESS = 0.85f
private const val SCAN_SATURATION = 1.2f
private const val SCAN_HAZE = 0.12f
private const val SCAN_TINT = 0.12f
/** Mist (#10): clouds roll in over [MIST_MS] over a slightly blurred picture. */
private const val MIST_MS = 520f
private const val MIST_BLUR = 6f
private const val MIST_DIM = 0.1f
private class Cloud(val angle: Float, val size: Float, val near: Float, val phase: Float)
private val MIST_CLOUDS = java.util.Random(23).let { r ->
    List(8) { i -> Cloud(i / 8f * 2f * Math.PI.toFloat() + r.nextFloat() * 0.5f, 200f + r.nextFloat() * 120f, 40f + r.nextFloat() * 90f, r.nextFloat() * 6f) }
}
private const val GLOW_SAMPLES = 12
private const val GLOW_PX = 96
private const val GLOW_SATURATION = 1.3f
/** From this blur (px), the picture is blurred at 1/[BLUR_DOWNSCALE] of the screen's size. */
private const val BLUR_SMALL_FROM = 8f
private const val BLUR_DOWNSCALE = 4f
