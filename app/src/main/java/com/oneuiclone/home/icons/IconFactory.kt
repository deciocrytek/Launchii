package com.oneuiclone.home.icons

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import com.oneuiclone.home.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Re-implementation of One UI 8.5's icon-tray pipeline (spec §16):
 * layers → emboss highlight (SRC_OVER) → emboss shadow (MULTIPLY) → night layer → icon shape mask (the squircle by default).
 */
class IconFactory(private val context: Context) {

    companion object {
        /** framework-res config_icon_mask on S26 (One UI 8.5). */
        const val ONE_UI_MASK =
            "M99,50C99,6.935,77.063,1,50,1C22.935,1,1,6.935,1,50c0,43.063,21.935,49,49,49C77.063,99,99,93.063,99,50z"
        private const val TRAY_COLOR = 0xFFFEFEFE.toInt()          // ICON_BG_COLOR (-65794)
        private const val NIGHT_LAYER = 0x19000000                  // sLayerColorForNight
        private const val CONTAIN_SCALE = 0.72f                     // DEFAULT_THEME_APPICON_SCALE
        private const val ANALYSIS_SIZE = 192
        /** Dark icons' plate: near-black, a touch lighter at the top (One UI 8.5 / iOS 18 dark icons). */
        private const val DARK_TOP = 0xFF2E2E31.toInt()
        private const val DARK_BOTTOM = 0xFF141416.toInt()
        /** The dark plate's overall colour (the Zoom In card starts from it). */
        const val DARK_PLATE = 0xFF212123.toInt()
        /** Apps whose monochrome layer themes badly: Themed and Frosted recolour their full icon (createTonal) instead. */
        val FORCE_TONAL = setOf("com.t3tools.t3code")
        /** Chrome, drawn as One UI shows it ([createChrome]) rather than from its own icon, whose layout varies by version. */
        private val CHROME = setOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")
        /** The logo's width as a share of the plate (One UI's Chrome: ≈ 74 %). */
        private const val CHROME_LOGO_FRACTION = 0.74f
        /** The Chrome logo's white ring (around the blue centre), as a share of its radius. */
        private const val CHROME_RING = 0.46f
        /** Icon theme › Dot ([homeIcon]'s look): the glyph as an even grid of dots, black on paper or white on black. */
        const val LOOK_DOT = "dot"
        /** Icon theme › Prism: the glyph in a gradient of the app's own colour, on a white or graphite plate. */
        const val LOOK_PRISM = "prism"
        /** Icon theme › Stencil: the glyph cut through a black plate, showing the wallpaper behind the icon frosted. */
        const val LOOK_STENCIL = "stencil"
        /** Icon theme › Satin: the glyph in white, faintly shaded, on a matte black plate (dark in light mode too). */
        const val LOOK_SATIN = "satin"
        private const val DOT_PLATE_LIGHT = 0xFFF3F2ED.toInt()
        private const val DOT_PLATE_DARK = 0xFF141414.toInt()
        private const val DOT_INK_LIGHT = 0xFF151515.toInt()
        private const val DOT_INK_DARK = 0xFFF2F2F2.toInt()
        /** Dots per side, and the share of the plate the grid spans (the glyph is fitted to it). */
        private const val DOT_GRID = 13
        private const val DOT_SPAN = 0.62f
        /** A cell gets a dot when the glyph covers this much of it; the dot's radius as a share of the cell. */
        private const val DOT_MIN = 0.35f
        private const val DOT_RADIUS = 0.38f
        private const val PRISM_PLATE_LIGHT = 0xFFFFFFFF.toInt()
        private const val PRISM_PLATE_DARK = 0xFF1C1D22.toInt()
        /** Stencil's plate, dark in light mode too (the user's choice): black, a touch lighter at the top; its overall colour for the Zoom In card. */
        private const val STENCIL_TOP = 0xFF2A2A30.toInt()
        private const val STENCIL_BOTTOM = 0xFF0B0B0E.toInt()
        private const val STENCIL_PLATE = 0xFF19191D.toInt()
        /** The frost on Stencil's cut-out (white, this opaque), over the blurred wallpaper drawn under the icon. */
        private const val STENCIL_FROST = 0.18f
        /** Satin's plate: black, lighter toward the top-left corner; its overall colour for the Zoom In card. */
        private val SATIN_STOPS = intArrayOf(0xFF38383C.toInt(), 0xFF151517.toInt(), 0xFF070708.toInt())
        private val SATIN_POS = floatArrayOf(0f, 0.38f, 1f)
        private const val SATIN_PLATE = 0xFF141416.toInt()
        /** Satin's glyph: a touch bigger than on the themed plate, near-white, with a faint grey along its lower-right edges. */
        private const val SATIN_GLYPH_SCALE = 1.1f
        private const val SATIN_INK = 0xFFFAFAFB.toInt()
        private const val SATIN_SHADE = 0xFF8A8A93.toInt()
        private const val SATIN_SHADE_ALPHA = 0.35f
        private const val SATIN_DROP_ALPHA = 0.45f
        /** Icon theme › Doodle: sketchbook paper with a wobbly ink outline, the glyph hatched in crayon and outlined in ink. */
        const val LOOK_DOODLE = "doodle"
        /** Icon theme › Sprinkles: pastel frosting tinted with the app's colour, rainbow sprinkles kept off the glyph. */
        const val LOOK_SPRINKLES = "sprinkles"
        /** Icon theme › Comic: the app's colour with halftone dots and an ink border, the glyph yellow in thick ink. */
        const val LOOK_COMIC = "comic"
        /** Comic's ink border round the icon (Icons › Comic border, Prefs.comicBorder); off leaves just the plate. */
        @Volatile var comicBorder = true
        /** The icons' contour (One UI's emboss: a light rim along the top, a faint dark one all round; Satin's light rim; Icons › Icon contour, Prefs.iconShine). */
        @Volatile var iconShine = true
        /** Icons › Icon colour: the colour Themed and Frosted icons take (opaque ARGB), or 0 for the colour palette's (Prefs.themeColor). */
        @Volatile var themeColor = 0

        /**
         * Themed and Frosted icons' plate: the picked [themeColor], or the colour palette's accent (system_accent1_400).
         * The same shade in light and dark mode (0.217: the darker dark-mode shade, accent1_500, is gone).
         */
        fun themeAccent(context: Context): Int =
            if (themeColor != 0) themeColor or 0xFF000000.toInt() else context.getColor(android.R.color.system_accent1_400)
        /** Icon theme › Groovy: the glyph cut out of cream, ringed by three 70s stripes in the app's colour family. */
        const val LOOK_GROOVY = "groovy"
        /** The playful looks (the Zoom In card fills in around them: LaunchCard.PLAYFUL_FROM_ALPHA). */
        val PLAYFUL = setOf(LOOK_DOODLE, LOOK_SPRINKLES, LOOK_COMIC, LOOK_GROOVY)
        /**
         * Icon theme › Glass: each app's own icon with a subtle glass finish (soft light from above, a light rim, white
         * parts fading into the plate); at night the symbol in the app's colour on a near-black plate. Modelled on iOS 26.
         */
        const val LOOK_IOS = "ios"
        /**
         * Icon theme › Clear Colour: each app's symbol in its own colours on clear frosted glass (Home draws the blurred
         * wallpaper under it, as for Frosted). The same in light and dark mode. [createClearColour].
         */
        const val LOOK_CLEAR_COLOUR = "clearcolour"
        /**
         * Icon theme › Minimal Colour: Clear Colour's symbol alone, in its own colours, with no plate or glass behind it
         * (as Minimal is Themed's white symbol alone). [createMinimalColour].
         */
        const val LOOK_MINIMAL_COLOUR = "minimalcolour"
        /** The looks that show the blurred wallpaper under the icon (Prefs.iconGlass): Stencil's cut-out, Clear Colour's glass. */
        val GLASS_LOOKS = setOf(LOOK_STENCIL, LOOK_CLEAR_COLOUR)
        /**
         * Its veil over the glass is the Icon colour (as Frosted's plate), the Icon transparency see-through at the top and
         * fading to this share of that at the foot (0.231's white veil ran 30 % to 11 %); the glow along its top edge is white (16 %).
         */
        const val CLEAR_VEIL_FOOT = 0.37f
        private const val CLEAR_GLOW = 0x29FFFFFF
        /** The sheen over the symbol's top (27 % white), and its shadow's strength. */
        private const val CLEAR_SHEEN = 0x45FFFFFF
        private const val CLEAR_SHADOW = 0.26f
        /** Chrome's logo radius, as a share of the icon. */
        private const val CLEAR_CHROME_RADIUS = 0.3f
        /** A foreground still covering more than this share of the icon once its plate's colour is cut out is the whole art: shown whole. */
        private const val CLEAR_FULL_ART = 0.8f
        /** Under this share left, the cut took the whole symbol. */
        private const val CLEAR_EMPTY = 0.01f
        /** clearArt: the outer ring it reads the background from (share of the icon from its centre). */
        private const val ART_RING_IN = 0.40f
        private const val ART_RING_OUT = 0.47f
        /** A pixel counts as coloured above this chroma; a background is a hue when this share of the ring is coloured. */
        private const val ART_CHROMA = 0.3f
        private const val ART_COLOURED_RING = 0.4f
        /** The most saturated share of the ring's main hue family that sets the background's hue and strength. */
        private const val ART_KEY_SHARE = 0.4f
        /** Background by hue: within [ART_HUE_NEAR]°, fading to art by [ART_HUE_FAR]°. By colour: distance [ART_DIST_NEAR]–[ART_DIST_FAR]. */
        private const val ART_HUE_NEAR = 11f
        private const val ART_HUE_FAR = 20f
        /** …and with a colour as strong as the background's: art below [ART_WEAK] of its chroma, background above [ART_STRONG]. */
        private const val ART_WEAK = 0.6f
        private const val ART_STRONG = 0.8f
        private const val ART_DIST_NEAR = 0.09f
        private const val ART_DIST_FAR = 0.2f
        /** The art must keep between these shares of the icon, or it's shown whole. */
        private const val ART_MIN = 0.05f
        private const val ART_MAX = 0.95f
        /**
         * The flood starts only from clear background ([ART_SEED] or less art) and steps only between neighbours this
         * close in colour ([ART_STEP]), so it follows the background's smooth gradient but stops at the art's edge:
         * 0.233–0.237 flooded on by key alone, into Mr. Monopoly's shaded ear and skin (user: "a bit transparent").
         */
        private const val ART_SEED = 0.2f
        /** 0.240: tighter (was 0.05, and any pixel under 0.5 art), as the phone's Monopoly GO icon still lost its ear. */
        private const val ART_STEP = 0.03f
        private const val ART_PROP = 0.35f
        /** [unmixEdges]: a pixel this see-through or more once keyed counts as cut. */
        private const val UNMIX_CUT = 64
        /** A symbol with at least this share of coloured pixels (chroma above [CLEAR_COLOURFUL_CHROMA]) keeps its own colours. */
        private const val CLEAR_COLOURFUL_CHROMA = 0.25f
        private const val CLEAR_COLOURFUL_SHARE = 0.15f
        /** A plain symbol takes its plate's colours only from a plate darker than this (a pale plate wouldn't read on glass). */
        private const val CLEAR_FILL_MAX_LUMINANCE = 0.75f
        /** A plain symbol's black parts in its plate's colour at this brightness (white parts at full). */
        private const val CLEAR_DEEP = 0.32f
        /** iOS Dark's plate, top to foot (measured), and its overall colour for the Zoom In card. */
        private const val IOS_DARK_TOP = 0xFF333333.toInt()
        private const val IOS_DARK_BOTTOM = 0xFF101010.toInt()
        private const val IOS_DARK_PLATE = 0xFF222222.toInt()
        /** The glass rim: white over the edge at the top, the sides and the foot, and the stroke's width (a share of the icon). */
        private val IOS_RIM_LIGHT = floatArrayOf(0.52f, 0.33f, 0.15f)
        private val IOS_RIM_DARK = floatArrayOf(0.16f, 0.07f, 0.05f)
        private const val IOS_RIM_WIDTH = 0.028f
        /** How opaque a white part of the symbol is at its foot (it's 1 at its top). */
        private const val IOS_FADE = 0.5f
        private const val DOODLE_PAPER_LIGHT = 0xFFFFFAF0.toInt()
        private const val DOODLE_PAPER_DARK = 0xFF1E1D1B.toInt()
        private const val DOODLE_INK_LIGHT = 0xFF262421.toInt()
        private const val DOODLE_INK_DARK = 0xFFF2EFE6.toInt()
        /** Doodle's crayon for apps with no colour of their own. */
        private const val DOODLE_GREY_LIGHT = 0xFF6B78E6.toInt()
        private const val DOODLE_GREY_DARK = 0xFF9AA6FF.toInt()
        /** Sprinkles' frosting for apps with no colour of their own, and the Zoom In card's start (a neutral frosting). */
        private const val SPRINKLES_GREY = 0xFF7A6CF0.toInt()
        private const val SPRINKLES_PLATE_LIGHT = 0xFFFBEFF3.toInt()
        private const val SPRINKLES_PLATE_DARK = 0xFF2A2222.toInt()
        private val SPRINKLE_COLORS = intArrayOf(0xFFFF5C8A.toInt(), 0xFFFFC93C.toInt(), 0xFF3EC7FF.toInt(), 0xFF7CDB5A.toInt(),
            0xFFB07CFF.toInt(), 0xFFFF8A3C.toInt(), 0xFFFFFFFF.toInt())
        private const val SPRINKLE_COUNT = 16
        private const val COMIC_GREY = 0xFFFF4D6D.toInt()
        private const val COMIC_INK = 0xFF111111.toInt()
        private const val COMIC_YELLOW = 0xFFFFE14D.toInt()
        private const val COMIC_GLYPH_SCALE = 0.97f
        private const val GROOVY_PLATE_LIGHT = 0xFFFBEFD9.toInt()
        private const val GROOVY_PLATE_DARK = 0xFF2B2019.toInt()
        /** Groovy's glyph is smaller than the themed one, to leave room for its stripes; their hue for apps with no colour. */
        private const val GROOVY_GLYPH_SCALE = 0.72f
        private const val GROOVY_GREY_HUE = 20f
        /** Prism's gradient for apps with no colour of their own (by chroma): blue into violet. */
        private const val PRISM_GREY_FROM = 0xFF3D8BFF.toInt()
        private const val PRISM_GREY_TO = 0xFF9B5CFF.toInt()

        /** Icons › Light or dark ([homeIcon]'s lookMode), for the styles that have both: follow dark mode, or one of them always. */
        const val MODE_AUTO = "auto"
        const val MODE_LIGHT = "light"
        const val MODE_DARK = "dark"

        /** Whether a Dot or Prism icon is drawn in its dark version: [mode], or with dark mode ([systemNight]) for Auto. */
        fun lookNight(mode: String, systemNight: Boolean): Boolean = when (mode) {
            MODE_LIGHT -> false
            MODE_DARK -> true
            else -> systemNight
        }

        /** A look's plate colour (the Zoom In card starts from it); Comic's, which is each app's colour, starts as its ink. */
        fun lookPlate(look: String, night: Boolean): Int = when (look) {
            LOOK_DOT -> if (night) DOT_PLATE_DARK else DOT_PLATE_LIGHT
            LOOK_STENCIL -> STENCIL_PLATE
            LOOK_SATIN -> SATIN_PLATE
            LOOK_DOODLE -> if (night) DOODLE_PAPER_DARK else DOODLE_PAPER_LIGHT
            LOOK_SPRINKLES -> if (night) SPRINKLES_PLATE_DARK else SPRINKLES_PLATE_LIGHT
            LOOK_COMIC -> COMIC_INK
            LOOK_GROOVY -> if (night) GROOVY_PLATE_DARK else GROOVY_PLATE_LIGHT
            LOOK_IOS -> if (night) IOS_DARK_PLATE else Color.WHITE
            LOOK_CLEAR_COLOUR, LOOK_MINIMAL_COLOUR -> Color.WHITE
            else -> if (night) PRISM_PLATE_DARK else PRISM_PLATE_LIGHT
        }

        /**
         * The Zoom In card's start colour for a look, or null for the app's own splash colour (Glass in light mode keeps
         * each app's own plate).
         */
        fun lookCard(look: String, night: Boolean): Int? = when {
            look == LOOK_IOS && !night -> null
            // glass: the card grows out of it see-through, in the app's own splash colour, as from Frosted icons
            look == LOOK_CLEAR_COLOUR -> null
            // no plate: the card fills in from nothing behind the symbol (Launcher), in the app's splash colour
            look == LOOK_MINIMAL_COLOUR -> null
            else -> lookPlate(look, night)
        }

        private val ONE_DOT = arrayOf(
            intArrayOf(22, 22), intArrayOf(96, 2), intArrayOf(169, 22), intArrayOf(189, 96),
            intArrayOf(169, 169), intArrayOf(96, 189), intArrayOf(22, 169), intArrayOf(2, 96),
        )

        /**
         * Temporary (0.168, the white-corner bug on the user's phone): how each icon was made ("package/size" →
         * description), shown by Icons › Icon check.
         */
        val DIAG = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** A corner pixel just outside every icon shape: its alpha and colour, which should be "a0". */
        fun corner(b: Bitmap): String = if (b.isRecycled) "recycled" else b.getPixel(1, 1).let {
            "a${Color.alpha(it)}" + if (Color.alpha(it) > 0) " #%06X".format(it and 0xFFFFFF) else ""
        }

        /**
         * Temporary (0.170): the raw premultiplied bytes, which [Bitmap.getPixel] hides (it reports every alpha-0 pixel
         * as 0). "Loose" pixels have colour above their alpha, e.g. alpha 0 with white left in: software drawing skips
         * them but the GPU adds that colour, which would be the white corners. [fix] clears them. Returns the count and
         * the corner's raw R G B A.
         */
        fun loose(b: Bitmap, fix: Boolean = false): Pair<Int, String> {
            if (b.isRecycled || b.config != Bitmap.Config.ARGB_8888) return 0 to "n/a"
            val buf = java.nio.ByteBuffer.allocate(b.byteCount)
            b.copyPixelsToBuffer(buf)
            val a = buf.array()
            val row = b.rowBytes
            val at = row + 4
            val raw = "%02X %02X %02X %02X".format(a[at].toInt() and 255, a[at + 1].toInt() and 255, a[at + 2].toInt() and 255, a[at + 3].toInt() and 255)
            var n = 0
            for (y in 0 until b.height) {
                var i = y * row
                for (x in 0 until b.width) {
                    val al = a[i + 3].toInt() and 255
                    if ((a[i].toInt() and 255) > al || (a[i + 1].toInt() and 255) > al || (a[i + 2].toInt() and 255) > al) {
                        n++
                        if (fix) { a[i] = 0; a[i + 1] = 0; a[i + 2] = 0; if (al == 0) a[i + 3] = 0 }
                    }
                    i += 4
                }
            }
            if (fix && n > 0 && b.isMutable) { buf.rewind(); b.copyPixelsFromBuffer(buf) }
            return n to raw
        }

        /** The icon shape (Icons › Icon shape, [IconShape.current]) in a [size] box. */
        fun maskPath(size: Float): Path = IconShape.path(size)
    }

    private val highlight: Bitmap = decode(R.drawable.oneui_emboss_highlight)
    private val shadow: Bitmap = decode(R.drawable.oneui_emboss_shadow)

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.SRC_OVER }
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.MULTIPLY }
    private val nightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NIGHT_LAYER
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }

    private fun decode(id: Int): Bitmap = BitmapFactory.decodeResource(
        context.resources, id, BitmapFactory.Options().apply { inScaled = false })

    /**
     * This factory's own Icon colour (opaque ARGB, 0 = the colour palette's), or null to follow the saved one
     * ([themeColor]). The Icons page preview draws in colours that aren't saved (and in black and white, to mix
     * any other from) without touching the icons Home draws.
     */
    @Volatile var ownColour: Int? = null
    private fun picked() = ownColour ?: themeColor
    /** [themeAccent] for [picked]. */
    private fun accent(): Int = picked().let { if (it != 0) it or 0xFF000000.toInt() else context.getColor(android.R.color.system_accent1_400) }

    fun isNight(ctx: Context = context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * Home screen icon (themed per the settings) to the Apps screen icon (always the app's own colours). Shared by
     * AppRepository and the Wallpaper and style preview.
     */
    fun homeIcon(d: Drawable, size: Int, night: Boolean, themed: Boolean, themeAll: Boolean, plateAlpha: Float,
                 dark: Boolean = false, pkg: String? = null, minimal: Boolean = false, look: String = "",
                 lookMode: String = MODE_AUTO): Pair<Bitmap, Bitmap> {
        val chrome = pkg in CHROME
        val normal = if (chrome) createChrome(size, dark = false) else create(d, size, night)
        val forced = pkg in FORCE_TONAL
        // a real themed version only exists for adaptive icons with a monochrome layer
        val hasMono = (d as? android.graphics.drawable.AdaptiveIconDrawable)?.monochrome != null && !forced
        val styled = themed && look.isNotEmpty() && (hasMono || themeAll || forced)
        return when {
            dark && chrome -> createChrome(size, dark = true)
            dark && isGame(pkg) -> normal
            // one odd icon must not take Home down with it (icons load at start, so a throw here crash-loops)
            dark -> runCatching { createDark(d, size) }.onFailure { android.util.Log.w("IconFactory", "Dark icon failed: $pkg", it) }.getOrNull() ?: normal
            // Glass works from each app's own icon, so every app gets it, with a monochrome layer or not
            themed && look == LOOK_IOS -> runCatching { createIos(d, size, lookNight(lookMode, night), pkg) }.onFailure { android.util.Log.w("IconFactory", "Glass icon failed: $pkg", it) }.getOrNull() ?: normal
            // Clear Colour also works from each app's own icon, so every app gets it
            themed && look == LOOK_CLEAR_COLOUR -> runCatching { createClearColour(d, size, pkg, plateAlpha) }.onFailure { android.util.Log.w("IconFactory", "Clear Colour icon failed: $pkg", it) }.getOrNull() ?: normal
            themed && look == LOOK_MINIMAL_COLOUR -> runCatching { createMinimalColour(d, size, pkg) }.onFailure { android.util.Log.w("IconFactory", "Minimal Colour icon failed: $pkg", it) }.getOrNull() ?: normal
            styled -> runCatching { createLook(d, size, lookNight(lookMode, night), look, hasMono, pkg?.hashCode() ?: 0) }.onFailure { android.util.Log.w("IconFactory", "$look icon failed: $pkg", it) }.getOrNull() ?: normal
            themed && minimal && hasMono -> createMinimal(d, size)
            themed && minimal && (themeAll || forced) -> createTonal(d, size, night, glyphOnly = true)
            themed && hasMono -> createThemed(d, size, night, plateAlpha)
            themed && (themeAll || forced) -> createTonal(d, size, night, plateAlpha)
            else -> normal
        }.also { out ->
            // a loose pixel (colour above its alpha) is invisible to software drawing but drawn by the GPU: clear any
            val fixed = runCatching { loose(out, fix = true).first }.getOrDefault(-1)
            if (pkg != null) DIAG["$pkg/$size"] = "loose at made $fixed (cleared), src ${d.javaClass.simpleName}" +
                ((d as? AdaptiveIconDrawable)?.let { " (bg ${it.background?.javaClass?.simpleName}, fg ${it.foreground?.javaClass?.simpleName})" } ?: "") +
                ", mono $hasMono, styled $styled, own icon ${out === normal}; made: corner ${corner(out)}, " +
                "${out.config} alpha ${out.hasAlpha()} premul ${out.isPremultiplied} ${out.width}px"
        } to normal
    }

    /** Games keep their own art under Dark icons: it's an illustration, not a logo to restyle. Every other look styles them (the user's choice: Monopoly GO themed in Dot, Prism and the playful looks). */
    @Suppress("DEPRECATION")
    private fun isGame(pkg: String?): Boolean {
        val info = pkg?.let { runCatching { context.packageManager.getApplicationInfo(it, 0) }.getOrNull() } ?: return false
        return info.category == android.content.pm.ApplicationInfo.CATEGORY_GAME ||
            info.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME != 0
    }

    /**
     * SemAppIconSolution.getColorThemeIcon(): accent background ([themeAccent], the same in light and dark mode) with the
     * monochrome layer tinted white (One UI goes black at night; the user wants white in both), then emboss + mask.
     * Falls back to the normal icon.
     * [plateAlpha] below 1 (glass icons): the background is that see-through, for the glass drawn under it.
     */
    fun createThemed(src: Drawable, size: Int, night: Boolean = isNight(), plateAlpha: Float = 1f): Bitmap {
        val mono = (src as? AdaptiveIconDrawable)?.monochrome ?: return create(src, size, night)
        val res = context.resources
        val accent = accent()
        val bg = (accent and 0x00FFFFFF) or ((plateAlpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24)
        val fg = Color.WHITE
        val glyph = mono.constantState?.newDrawable(res)?.mutate() ?: mono.mutate()
        glyph.setTint(fg)
        val themed = AdaptiveIconDrawable(android.graphics.drawable.ColorDrawable(bg), glyph)
        return create(themed, size, night = false, opaqueBase = plateAlpha >= 1f)
    }

    /** Minimal icon: [createThemed]'s white glyph on its own, no plate ([minimal]). Falls back to the normal icon. */
    fun createMinimal(src: Drawable, size: Int): Bitmap {
        val mono = (src as? AdaptiveIconDrawable)?.monochrome ?: return create(src, size)
        return minimal(monoGlyph(mono, size), size)
    }

    /** The monochrome layer in white, at the size it has on the themed plate, clipped to the mask. */
    private fun monoGlyph(mono: Drawable, size: Int): Bitmap {
        val glyph = (mono.constantState?.newDrawable(context.resources)?.mutate() ?: mono.mutate()).apply { setTint(Color.WHITE) }
        val layers = blank(size)
        val inset = (size * 0.25f).roundToInt()
        glyph.bounds = Rect(-inset, -inset, size + inset, size + inset)
        glyph.draw(Canvas(layers))
        return clip(layers, size)
    }

    /**
     * Dot, Prism, Stencil and Satin icons ([LOOK_DOT], [LOOK_PRISM], [LOOK_STENCIL], [LOOK_SATIN]): the themed glyph (the monochrome
     * layer, or the tonal glyph for apps without one) on a plate of the style's own: Dot and Prism follow light and dark mode, Stencil
     * and Satin are dark in both.
     */
    fun createLook(src: Drawable, size: Int, night: Boolean, look: String, hasMono: Boolean, seed: Int = 0): Bitmap {
        val mono = (src as? AdaptiveIconDrawable)?.monochrome
        val glyph = if (hasMono && mono != null) monoGlyph(mono, size) else createTonal(src, size, night, glyphOnly = true, bare = true)
        return when (look) {
            LOOK_DOT -> dots(glyph, size, night)
            LOOK_STENCIL -> stencil(glyph, size)
            LOOK_SATIN -> satin(glyph, size)
            LOOK_DOODLE -> doodle(glyph, size, night, brandColor(src), seed)
            LOOK_SPRINKLES -> sprinkles(glyph, size, night, brandColor(src), seed)
            LOOK_COMIC -> comic(glyph, size, night, brandColor(src))
            LOOK_GROOVY -> groovy(glyph, size, night, brandColor(src))
            else -> prism(glyph, size, night, brandColor(src))
        }
    }

    /**
     * The glyph redrawn as a [DOT_GRID]² grid of even dots, like the Nothing Phone's glyph font: fitted to [DOT_SPAN]
     * of the plate, each cell gets a dot when the glyph covers enough of it. Recycles [glyph].
     */
    private fun dots(glyph: Bitmap, size: Int, night: Boolean): Bitmap {
        val alpha = IntArray(size * size)
        glyph.getPixels(alpha, 0, size, 0, 0, size, size)
        val box = opaqueBounds(glyph)
        glyph.recycle()
        val layers = blank(size)
        val c = Canvas(layers)
        c.drawColor(if (night) DOT_PLATE_DARK else DOT_PLATE_LIGHT)
        if (!box.isEmpty) {
            val span = size * DOT_SPAN
            val cell = span / DOT_GRID
            val o = (size - span) / 2f
            // plate point p shows glyph point box centre + (p - plate centre) / scale
            val scale = (span * 0.96f / max(box.width(), box.height())).coerceIn(0.8f, 1.6f)
            val mid = size / 2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) DOT_INK_DARK else DOT_INK_LIGHT }
            for (gy in 0 until DOT_GRID) for (gx in 0 until DOT_GRID) {
                val x0 = (box.exactCenterX() + (o + gx * cell - mid) / scale).toInt()
                val y0 = (box.exactCenterY() + (o + gy * cell - mid) / scale).toInt()
                val x1 = (box.exactCenterX() + (o + (gx + 1) * cell - mid) / scale).toInt()
                val y1 = (box.exactCenterY() + (o + (gy + 1) * cell - mid) / scale).toInt()
                var sum = 0f; var k = 0
                for (y in y0 until y1) for (x in x0 until x1) {
                    if (x in 0 until size && y in 0 until size) sum += Color.alpha(alpha[y * size + x]) / 255f
                    k++
                }
                if (k > 0 && sum / k >= DOT_MIN) c.drawCircle(o + (gx + 0.5f) * cell, o + (gy + 0.5f) * cell, cell * DOT_RADIUS, p)
            }
        }
        applyEmboss(c, size)
        return clip(layers, size)
    }

    /**
     * The glyph cut through a black plate: the icon is see-through there but for a faint white frost, over the blurred
     * wallpaper Home draws under it (IconView.plateGlass, as for Frosted icons), so the symbol shows the wallpaper
     * frosted. Recycles [glyph].
     */
    private fun stencil(glyph: Bitmap, size: Int): Bitmap {
        val layers = blank(size)
        val c = Canvas(layers)
        c.drawPaint(Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, size.toFloat(), STENCIL_TOP, STENCIL_BOTTOM, Shader.TileMode.CLAMP)
        })
        applyEmboss(c, size)
        c.drawBitmap(glyph, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        c.drawBitmap(glyph, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * STENCIL_FROST).roundToInt() })
        glyph.recycle()
        return clip(layers, size)
    }

    /**
     * The glyph in near-white on a matte black plate with a faint light rim (the user's "Hint" mockup): the glyph a little
     * bigger than on the themed plate, a faint grey along its lower-right edges and a small soft shadow under it, so it
     * reads almost flat. Lengths are in 24ths of the icon, as in the mockup. Recycles [glyph].
     */
    private fun satin(glyph: Bitmap, size: Int): Bitmap {
        val k = size / 24f
        val mid = size / 2f
        // the glyph as a near-white mask, scaled about the centre
        val ink = blank(size)
        Canvas(ink).apply {
            scale(SATIN_GLYPH_SCALE, SATIN_GLYPH_SCALE, mid, mid)
            drawBitmap(glyph, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = android.graphics.PorterDuffColorFilter(SATIN_INK, PorterDuff.Mode.SRC_IN)
            })
        }
        glyph.recycle()

        val layers = blank(size)
        val c = Canvas(layers)
        c.drawPaint(Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, size * 0.7f, size.toFloat(), SATIN_STOPS, SATIN_POS, Shader.TileMode.CLAMP)
        })
        // the rim: a thin light line just inside the edge, brightest at the top (Icon contour)
        if (iconShine) {
            val rim = maskPath(size.toFloat()).apply {
                transform(android.graphics.Matrix().apply { setScale(0.97f, 0.97f, mid, mid) })
            }
            c.drawPath(rim, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.42f * k
                shader = android.graphics.LinearGradient(0f, 0f, size * 0.4f, size.toFloat(),
                    intArrayOf(0x61FFFFFF, 0x0FFFFFFF, 0x00FFFFFF, 0x1FFFFFFF), floatArrayOf(0f, 0.3f, 0.75f, 1f), Shader.TileMode.CLAMP)
            })
        }
        // the small soft shadow under the glyph
        val offset = IntArray(2)
        val drop = ink.extractAlpha(Paint().apply { maskFilter = blur(0.4f * k) }, offset)
        c.drawBitmap(drop, offset[0].toFloat(), offset[1] + 0.3f * k, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            color = Color.BLACK; alpha = (255 * SATIN_DROP_ALPHA).roundToInt()
        })
        drop.recycle()
        c.drawBitmap(ink, 0f, 0f, bitmapPaint)
        // the faint shade: the glyph minus a soft copy of itself nudged up-left leaves its lower-right edges
        val shade = blank(size)
        val nudged = ink.extractAlpha(Paint().apply { maskFilter = blur(0.3f * k) }, offset)
        Canvas(shade).apply {
            drawBitmap(ink, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = android.graphics.PorterDuffColorFilter(SATIN_SHADE, PorterDuff.Mode.SRC_IN)
            })
            drawBitmap(nudged, offset[0] - 0.25f * k, offset[1] - 0.32f * k, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            })
        }
        nudged.recycle()
        c.drawBitmap(shade, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * SATIN_SHADE_ALPHA).roundToInt() })
        shade.recycle()
        ink.recycle()
        return clip(layers, size)
    }

    /** A blur of standard deviation [sigma] px (an SVG stdDeviation), as Android's blur radius. */
    private fun blur(sigma: Float) =
        android.graphics.BlurMaskFilter(max(0.5f, (sigma - 0.5f) / 0.57735f), android.graphics.BlurMaskFilter.Blur.NORMAL)

    // The playful looks (the user's mockup, mockups/icon-themes-playful.html). Lengths are in 24ths of the icon, as there.

    /** [glyph] in [color], [scale]d about the centre. */
    private fun tinted(glyph: Bitmap, size: Int, color: Int, scale: Float = 1f): Bitmap {
        val out = blank(size)
        Canvas(out).apply {
            scale(scale, scale, size / 2f, size / 2f)
            drawBitmap(glyph, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = android.graphics.PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            })
        }
        return out
    }

    /** [mask] grown by [r] px all round, in [color] (an outline under it): copies stamped around rings out to [r]. */
    private fun grown(mask: Bitmap, size: Int, r: Float, color: Int): Bitmap {
        val out = blank(size)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = android.graphics.PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }
        c.drawBitmap(mask, 0f, 0f, p)
        // rings about 1.5 px apart, so thin strokes and small dots grow without gaps
        val rings = (r / 1.5f).roundToInt().coerceIn(1, 6)
        for (k in 1..rings) {
            val ring = r * k / rings
            val steps = (2 * Math.PI * ring / 1.5f).roundToInt().coerceIn(8, 48)
            for (i in 0 until steps) {
                val a = 2 * Math.PI * i / steps
                c.drawBitmap(mask, (ring * kotlin.math.cos(a)).toFloat(), (ring * kotlin.math.sin(a)).toFloat(), p)
            }
        }
        return out
    }

    /** [brand] made cheerful at lightness [l]; [grey] for apps with no colour of their own. */
    private fun candy(brand: Int?, l: Float, grey: Int): Int {
        if (brand == null) return grey
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(brand, hsl)
        return androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hsl[0], max(hsl[1], 0.78f), l))
    }

    private fun hue(brand: Int?): Float? = brand?.let { FloatArray(3).also { h -> androidx.core.graphics.ColorUtils.colorToHSL(it, h) }[0] }

    /**
     * Sketchbook paper with a wobbly ink outline; the glyph coloured in with a crayon hatch in the app's colour, printed a
     * little off from its ink outline, and tilted a degree or two ([seed]) so no two icons sit quite straight. Recycles [glyph].
     */
    private fun doodle(glyph: Bitmap, size: Int, night: Boolean, brand: Int?, seed: Int): Bitmap {
        val k = size / 24f
        val mid = size / 2f
        val rnd = java.util.Random(seed.toLong())
        val ink = if (night) DOODLE_INK_DARK else DOODLE_INK_LIGHT
        val crayon = candy(brand, if (night) 0.62f else 0.55f, if (night) DOODLE_GREY_DARK else DOODLE_GREY_LIGHT)
        // the hatch: crayon strokes at 35–55°, kept to the glyph
        val hatch = blank(size)
        Canvas(hatch).apply {
            rotate(35f + rnd.nextFloat() * 20f, mid, mid)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = crayon }
            var x = -size.toFloat()
            while (x < size * 2f) { drawRect(x, -size.toFloat(), x + 0.62f * k, size * 2f, p); x += 1.1f * k }
        }
        Canvas(hatch).drawBitmap(glyph, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        // the ink outline: just outside the glyph's edge
        val outline = grown(glyph, size, 0.4f * k, ink)
        Canvas(outline).drawBitmap(glyph, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        glyph.recycle()

        val layers = blank(size)
        val c = Canvas(layers)
        c.drawColor(if (night) DOODLE_PAPER_DARK else DOODLE_PAPER_LIGHT)
        c.save()
        c.rotate((rnd.nextFloat() - 0.5f) * 6f, mid, mid)
        c.drawBitmap(hatch, 0.55f * k, 0.5f * k, bitmapPaint)
        c.drawBitmap(outline, 0f, 0f, bitmapPaint)
        c.restore()
        hatch.recycle(); outline.recycle()
        // the plate's own outline, drawn by hand: inset so its wobble stays inside the shape
        val edge = maskPath(size.toFloat()).apply { transform(android.graphics.Matrix().apply { setScale(0.92f, 0.92f, mid, mid) }) }
        c.drawPath(edge, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 0.75f * k; color = ink
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            pathEffect = android.graphics.ComposePathEffect(android.graphics.CornerPathEffect(1.5f * k),
                android.graphics.DiscretePathEffect(2.2f * k, 0.3f * k))
        })
        return clip(layers, size)
    }

    /**
     * Pastel frosting tinted with the app's colour (chocolate-dark at night), 16 rainbow sprinkles scattered by [seed] and
     * kept off the glyph, and the glyph in a deeper shade with a thin border. Recycles [glyph].
     */
    private fun sprinkles(glyph: Bitmap, size: Int, night: Boolean, brand: Int?, seed: Int): Bitmap {
        val k = size / 24f
        val rnd = java.util.Random(seed.toLong())
        val base = candy(brand, 0.5f, SPRINKLES_GREY)
        val blend = androidx.core.graphics.ColorUtils::blendARGB
        val plate = if (night) blend(base, 0xFF1B1514.toInt(), 0.78f) else blend(base, Color.WHITE, 0.78f)
        val ink = if (night) blend(base, Color.WHITE, 0.25f) else blend(base, Color.BLACK, 0.12f)

        val sprinkles = blank(size)
        Canvas(sprinkles).apply {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val r = RectF(-1f * k, -0.32f * k, 1f * k, 0.32f * k)
            for (i in 0 until SPRINKLE_COUNT) {
                p.color = SPRINKLE_COLORS[i % SPRINKLE_COLORS.size]
                save()
                translate((2.5f + rnd.nextFloat() * 19f) * k, (2.5f + rnd.nextFloat() * 19f) * k)
                rotate(rnd.nextFloat() * 180f)
                drawRoundRect(r, 0.32f * k, 0.32f * k, p)
                restore()
            }
        }
        val keepOff = grown(glyph, size, 1.02f * k, Color.BLACK)
        Canvas(sprinkles).drawBitmap(keepOff, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        keepOff.recycle()
        val border = grown(glyph, size, 0.42f * k, if (night) 0xFF2A2020.toInt() else Color.WHITE)
        val face = tinted(glyph, size, ink)
        glyph.recycle()

        val layers = blank(size)
        val c = Canvas(layers)
        c.drawColor(plate)
        c.drawBitmap(sprinkles, 0f, 0f, bitmapPaint)
        c.drawBitmap(border, 0f, 0f, bitmapPaint)
        c.drawBitmap(face, 0f, 0f, bitmapPaint)
        sprinkles.recycle(); border.recycle(); face.recycle()
        applyEmboss(c, size)
        return clip(layers, size)
    }

    /**
     * Pop art: the app's colour with halftone dots fading out toward the top left and a black ink border ([comicBorder]); the glyph in
     * comic-book yellow (white for yellow apps) with a thick ink outline and a hard offset shadow. Recycles [glyph].
     */
    private fun comic(glyph: Bitmap, size: Int, night: Boolean, brand: Int?): Bitmap {
        val k = size / 24f
        val mid = size / 2f
        val plate = candy(brand, if (night) 0.45f else 0.54f, COMIC_GREY)
        val yellowApp = hue(brand)?.let { it in 35f..75f } == true

        val dots = blank(size)
        Canvas(dots).apply {
            rotate(45f, mid, mid)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = androidx.core.graphics.ColorUtils.blendARGB(plate, Color.BLACK, 0.4f) }
            val step = 1.5f * k
            var y = -size * 0.5f
            while (y < size * 1.5f) {
                var x = -size * 0.5f
                while (x < size * 1.5f) { drawCircle(x, y, 0.48f * k, p); x += step }
                y += step
            }
        }
        Canvas(dots).drawPaint(Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
                intArrayOf(0x00000000, 0x00000000, Color.BLACK), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        val g = tinted(glyph, size, Color.WHITE, COMIC_GLYPH_SCALE)
        glyph.recycle()
        val outline = grown(g, size, 0.7f * k, COMIC_INK)
        val face = tinted(g, size, if (yellowApp) Color.WHITE else COMIC_YELLOW)
        g.recycle()

        val layers = blank(size)
        val c = Canvas(layers)
        c.drawColor(plate)
        c.drawBitmap(dots, 0f, 0f, bitmapPaint)
        dots.recycle()
        if (comicBorder) {
            val edge = maskPath(size.toFloat()).apply { transform(android.graphics.Matrix().apply { setScale(0.97f, 0.97f, mid, mid) }) }
            c.drawPath(edge, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.9f * k; color = COMIC_INK })
        }
        c.drawBitmap(outline, 0.9f * k, 0.9f * k, bitmapPaint)
        c.drawBitmap(outline, 0f, 0f, bitmapPaint)
        c.drawBitmap(face, 0f, 0f, bitmapPaint)
        outline.recycle(); face.recycle()
        return clip(layers, size)
    }

    /**
     * 70s bubble lettering: the glyph, smaller, cut out of a cream plate (espresso at night) and ringed by three stacked
     * stripes: a deep shade of the app's colour, then two warmer neighbouring hues. Recycles [glyph].
     */
    private fun groovy(glyph: Bitmap, size: Int, night: Boolean, brand: Int?): Bitmap {
        val k = size / 24f
        val plate = if (night) GROOVY_PLATE_DARK else GROOVY_PLATE_LIGHT
        val h = hue(brand) ?: GROOVY_GREY_HUE
        val hsl = { dh: Float, s: Float, l: Float -> androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf((h + dh) % 360f, s, l)) }
        val stripes = listOf(hsl(0f, 0.62f, if (night) 0.42f else 0.32f) to 2.11f, hsl(22f, 0.8f, 0.52f) to 1.41f, hsl(44f, 0.9f, 0.62f) to 0.7f)
        val g = tinted(glyph, size, Color.WHITE, GROOVY_GLYPH_SCALE)
        glyph.recycle()

        val layers = blank(size)
        val c = Canvas(layers)
        c.drawColor(plate)
        for ((color, r) in stripes) grown(g, size, r * k, color).let { c.drawBitmap(it, 0f, 0f, bitmapPaint); it.recycle() }
        tinted(g, size, plate).let { c.drawBitmap(it, 0f, 0f, bitmapPaint); it.recycle() }
        g.recycle()
        applyEmboss(c, size)
        return clip(layers, size)
    }

    /**
     * The glyph in a gradient from [brand] (top left) into its neighbouring hue, over a soft glow of the same
     * gradient, on a white plate (graphite in dark mode). Recycles [glyph].
     */
    private fun prism(glyph: Bitmap, size: Int, night: Boolean, brand: Int?): Bitmap {
        val (from, to) = prismColors(brand, night)
        val gradient = Paint().apply {
            shader = android.graphics.LinearGradient(size * 0.2f, size * 0.2f, size * 0.8f, size * 0.8f, from, to, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        val offset = IntArray(2)
        val blur = glyph.extractAlpha(Paint().apply {
            maskFilter = android.graphics.BlurMaskFilter(size * 0.06f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }, offset)
        val glow = blank(size)
        Canvas(glow).apply {
            drawBitmap(blur, offset[0].toFloat(), offset[1] + size * 0.035f, Paint(Paint.FILTER_BITMAP_FLAG))
            drawPaint(gradient)
        }
        blur.recycle()
        Canvas(glyph).drawPaint(gradient)

        val layers = blank(size)
        val c = Canvas(layers)
        c.drawColor(if (night) PRISM_PLATE_DARK else PRISM_PLATE_LIGHT)
        c.drawBitmap(glow, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = if (night) 140 else 102 })
        c.drawBitmap(glyph, 0f, 0f, bitmapPaint)
        glow.recycle()
        glyph.recycle()
        applyEmboss(c, size)
        return clip(layers, size)
    }

    /** Prism's gradient: [brand] made vivid, into the hue 42° on (yellows on white: back toward orange); blue into violet for apps with no colour. */
    private fun prismColors(brand: Int?, night: Boolean): Pair<Int, Int> {
        if (brand == null) return PRISM_GREY_FROM to PRISM_GREY_TO
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(brand, hsl)
        val s = max(hsl[1], 0.8f)
        // a yellow darkened to read on white goes olive, more so toward green: on white it runs toward orange instead
        val yellow = !night && hsl[0] in 35f..80f
        val h = if (yellow) hsl[0] - 8f else hsl[0]
        val next = if (yellow) (hsl[0] + 320f) % 360f else (hsl[0] + 42f) % 360f
        return readable(h, s, if (night) 0.62f else 0.55f, night) to readable(next, s, if (night) 0.65f else 0.58f, night)
    }

    /** That colour, darkened until it reads on the white plate (yellows) or lightened until it reads on graphite (blues). */
    private fun readable(h: Float, s: Float, l: Float, night: Boolean): Int {
        var lightness = l
        var c = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(h, s, lightness))
        while (if (night) Color.luminance(c) < 0.22f && lightness < 0.85f else Color.luminance(c) > 0.3f && lightness > 0.25f) {
            lightness += if (night) 0.02f else -0.02f
            c = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(h, s, lightness))
        }
        return c
    }

    /**
     * The app's own colour: the hue (in 15° steps) its coloured pixels weigh most in, by chroma, averaged; null for an
     * icon with next to no colour (judged by chroma, never HSL saturation, which is high for any tint near white).
     */
    private fun brandColor(src: Drawable): Int? {
        val s = 48
        val b = blank(s)
        val c = Canvas(b)
        if (src is AdaptiveIconDrawable) drawAdaptive(c, src, s) else drawLegacy(c, src, s)
        val px = IntArray(s * s)
        b.getPixels(px, 0, s, 0, 0, s, s)
        b.recycle()
        val weight = FloatArray(24)
        val r = FloatArray(24); val g = FloatArray(24); val bl = FloatArray(24)
        val hsl = FloatArray(3)
        for (p in px) {
            val ch = chroma(p)
            if (ch < 0.2f || Color.alpha(p) < 128) continue
            androidx.core.graphics.ColorUtils.colorToHSL(p, hsl)
            val k = (hsl[0] / 15f).toInt().coerceIn(0, 23)
            weight[k] += ch; r[k] += Color.red(p) * ch; g[k] += Color.green(p) * ch; bl[k] += Color.blue(p) * ch
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        if (weight[best] < s * s * 0.02f) return null
        return Color.rgb((r[best] / weight[best]).roundToInt(), (g[best] / weight[best]).roundToInt(), (bl[best] / weight[best]).roundToInt())
    }

    /**
     * A white glyph at the size it has on the themed plate (so Minimal icons line up with Themed and Frosted ones),
     * over a soft shadow so it still reads on a light wallpaper. Recycles [glyph].
     */
    private fun minimal(glyph: Bitmap, size: Int): Bitmap {
        val out = blank(size)
        val c = Canvas(out)
        val offset = IntArray(2)
        val shadow = glyph.extractAlpha(Paint().apply {
            maskFilter = android.graphics.BlurMaskFilter(size * 0.03f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }, offset)
        c.drawBitmap(shadow, offset[0].toFloat(), offset[1] + size * 0.012f, Paint(Paint.FILTER_BITMAP_FLAG).apply { color = 0x47000000 })
        c.drawBitmap(glyph, 0f, 0f, bitmapPaint)
        shadow.recycle()
        glyph.recycle()
        return out
    }

    /**
     * Forced theme icon for apps without a monochrome layer ("tonal"): the icon's own background becomes the
     * theme plate, detail that stands out from it moves toward the glyph colour (white) and
     * detail on the other side of the background moves toward a deep accent. Flat logos come
     * out like a real monochrome glyph; illustrations keep their shading. [plateAlpha] as for [createThemed]: the
     * plate is see-through by that much, the glyph (and detail moving away from the plate) stays solid.
     * [glyphOnly]: just the white glyph, for Minimal icons ([minimal]); [bare] leaves out Minimal's shadow (Dot, Prism).
     */
    fun createTonal(src: Drawable, size: Int, night: Boolean = isNight(), plateAlpha: Float = 1f, glyphOnly: Boolean = false,
                    bare: Boolean = false): Bitmap {
        val res = context.resources
        val plate = accent()
        // white glyph in light and dark mode alike (as createThemed), so detail on the other side of the plate goes deep
        val glyph = Color.WHITE
        // the deep side: the palette's darker tone, or the picked colour darkened to about as far
        val away = if (picked() != 0) androidx.core.graphics.ColorUtils.blendARGB(plate, Color.BLACK, 0.5f)
            else res.getColor(android.R.color.system_accent1_700, null)

        val layers = blank(size)
        val c = Canvas(layers)
        val n = size * size
        val px = IntArray(n)
        val bg = IntArray(n)
        if (src is AdaptiveIconDrawable) {
            // per-pixel reference: the background layer on its own (handles gradient backgrounds)
            c.drawColor(Color.BLACK)
            val inset = (size * 0.25f).roundToInt()
            src.background?.let { it.bounds = Rect(-inset, -inset, size + inset, size + inset); it.draw(c) }
            layers.getPixels(bg, 0, size, 0, 0, size, size)
            drawAdaptive(c, src, size)
            layers.getPixels(px, 0, size, 0, 0, size, size)
        } else {
            drawLegacy(c, src, size)
            layers.getPixels(px, 0, size, 0, 0, size, size)
            bg.fill(ringMedian(px, size))
        }

        val lum = FloatArray(n)
        val lumBg = FloatArray(n)
        val dist = FloatArray(n)
        for (i in 0 until n) {
            lum[i] = luminance(px[i]); lumBg[i] = luminance(bg[i])
            val dr = Color.red(px[i]) - Color.red(bg[i])
            val dg = Color.green(px[i]) - Color.green(bg[i])
            val db = Color.blue(px[i]) - Color.blue(bg[i])
            dist[i] = kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat()) / 255f
        }
        // polarity: content that is mostly darker than its background (logo on a white tray) should still
        // become the glyph, so flip which side of the background maps to it
        var sum = 0f
        for (i in 0 until n) if (dist[i] > 0.15f) sum += lum[i] - lumBg[i]
        val pol = if (sum < 0f) -1f else 1f
        // contrast stretch: the strongest glyph-side detail (95th percentile) always reaches the full glyph
        // colour, so low-contrast art (light grey on white) doesn't come out faint
        val hist = IntArray(256)
        var count = 0
        for (i in 0 until n) {
            val d = (lum[i] - lumBg[i]) * pol
            if (d > 0.02f) { hist[(d * 255).roundToInt().coerceIn(0, 255)]++; count++ }
        }
        var stretch = 1f
        if (count > 0) {
            var acc = 0; var b = 0
            while (b < 255 && acc + hist[b] < count * 0.95f) { acc += hist[b]; b++ }
            stretch = max(b / 255f, 0.08f)
        }

        for (i in 0 until n) {
            val lb = lumBg[i]
            val d = (lum[i] - lb) * pol
            val toward = max(minOf(if (pol > 0) 1f - lb else lb, stretch), 0.05f)
            val from = max(if (pol > 0) lb else 1f - lb, 0.05f)
            // saturated detail at the background's luminance (e.g. TikTok's cyan/red) still reads as glyph
            val chroma = (dist[i] - kotlin.math.abs(lum[i] - lb) * 1.2f).coerceIn(0f, 1f)
            val up = (Math.pow((d / toward).coerceIn(0f, 1f).toDouble(), 0.75).toFloat() + chroma * 0.6f).coerceAtMost(1f)
            val dn = Math.pow((-d / from).coerceIn(0f, 1f).toDouble(), 0.9).toFloat()
            // glyph only: faint detail (a tile a shade off its background) would show as a see-through square
            if (glyphOnly) { px[i] = Color.argb((255 * smooth(0.22f, 0.6f, up)).roundToInt(), 255, 255, 255); continue }
            val solid = max(up, dn)
            px[i] = Color.argb((255 * (plateAlpha + (1f - plateAlpha) * solid)).roundToInt().coerceIn(0, 255),
                tone(Color.red(plate), Color.red(glyph), Color.red(away), up, dn),
                tone(Color.green(plate), Color.green(glyph), Color.green(away), up, dn),
                tone(Color.blue(plate), Color.blue(glyph), Color.blue(away), up, dn),
            )
        }
        layers.setPixels(px, 0, size, 0, 0, size, size)
        if (glyphOnly) return clip(layers, size).let { if (bare) it else minimal(it, size) }
        applyEmboss(c, size)
        return clip(layers, size)
    }

    /**
     * Dark icon, worked out for any app the way iOS 18 makes its dark icons (no per-app artwork): the icon's
     * foreground is lifted off its background onto a dark plate. A simple two-tone logo (a white glyph on a brand
     * colour, like Phone's handset on green) takes the brand colour onto the glyph. A colourful one keeps its colours,
     * with near-black detail turned light so it doesn't vanish. A foreground that carries its own light and dark
     * (Waze's white ghost with its black outline and face) is kept as drawn. When no foreground separates cleanly
     * (photos, busy art), null: the icon stays as it is.
     *
     * Foreground: an adaptive icon's foreground layer, less whatever in it matches the background layer under it
     * (Maps draws a white tile, with a drop shadow, into its foreground); a legacy (or one-layer) icon is split by
     * colour distance from the ring just inside the mask edge.
     */
    fun createDark(src: Drawable, size: Int): Bitmap? {
        val work = darkGlyph(src, size) ?: return null
        val layers = blank(size)
        val lc = Canvas(layers)
        lc.drawPaint(Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, size.toFloat(), DARK_TOP, DARK_BOTTOM, Shader.TileMode.CLAMP)
        })
        lc.drawBitmap(work, 0f, 0f, bitmapPaint)
        work.recycle()
        applyEmboss(lc, size)
        return clip(layers, size)
    }

    /** [createDark]'s foreground, lifted off its background and recoloured for a dark plate; null when none separates. */
    private fun darkGlyph(src: Drawable, size: Int): Bitmap? {
        val n = size * size
        val work = blank(size)
        val c = Canvas(work)
        val fg = IntArray(n)
        var brand = Color.BLACK
        var split = false
        val inset = (size * 0.25f).roundToInt()
        val layerBounds = Rect(-inset, -inset, size + inset, size + inset)
        if (src is AdaptiveIconDrawable && src.foreground != null) {
            src.background?.let { it.bounds = layerBounds; it.draw(c) }
            val bg = IntArray(n)
            work.getPixels(bg, 0, size, 0, 0, size, size)
            brand = averageColor(bg) ?: Color.BLACK
            work.eraseColor(Color.TRANSPARENT)
            src.foreground.bounds = layerBounds
            src.foreground.draw(c)
            work.getPixels(fg, 0, size, 0, 0, size, size)
            keyOutBackground(fg, bg, size)
            split = coverage(fg, size) in 0.02f..0.85f
        }
        if (!split) {
            work.eraseColor(Color.TRANSPARENT)
            if (src is AdaptiveIconDrawable) drawAdaptive(c, src, size) else drawLegacy(c, src, size)
            val px = IntArray(n)
            work.getPixels(px, 0, size, 0, 0, size, size)
            brand = ringMedian(px, size)
            for (i in 0 until n) {
                val p = px[i]
                val d = colorDistance(p, brand)
                val a = smooth(0.09f, 0.22f, d) * Color.alpha(p) / 255f
                fg[i] = (p and 0x00FFFFFF) or ((a * 255).roundToInt() shl 24)
            }
            unblendEdges(fg, px, IntArray(n) { brand }, size)
            val cov = coverage(fg, size)
            if (cov < 0.02f || cov > 0.8f) { work.recycle(); return null }
        }

        // two-tone? most of the solid foreground is white-ish (a glyph made to sit on the brand colour). A foreground
        // with white and near-black parts both is a self-contained drawing, kept as it is
        val hsl = FloatArray(3)
        var solid = 0f
        var white = 0f
        var black = 0f
        for (p in fg) {
            val a = Color.alpha(p) / 255f
            if (a < 0.5f) continue
            androidx.core.graphics.ColorUtils.colorToHSL(p, hsl)
            solid += a
            if (chroma(p) < 0.1f && hsl[2] > 0.72f) white += a
            if (hsl[2] < 0.2f) black += a
        }
        val drawn = solid > 0f && white / solid > 0.2f && black / solid > 0.06f
        val tint = if (!drawn && solid > 0f && white / solid > 0.6f) glyphTint(brand) else null

        for (i in 0 until n) {
            val p = fg[i]
            val a = Color.alpha(p)
            if (a == 0) continue
            androidx.core.graphics.ColorUtils.colorToHSL(p, hsl)
            // by chroma, not HSL saturation: that's 1 for any tint at all near white or black (an edge of a white logo
            // over the brand colour, 252,251,255), which kept such pixels white in a dotted line around Life360's logo
            val neutral = (1f - chroma(p) / 0.2f).coerceIn(0f, 1f)
            var out = p or (0xFF shl 24)
            if (tint != null) {
                // white parts take the brand colour, shaded by how light they were; coloured parts stay
                val w = neutral * smooth(0.5f, 0.8f, hsl[2])
                val shaded = androidx.core.graphics.ColorUtils.blendARGB(Color.BLACK, tint, (0.55f + 0.45f * hsl[2]).coerceAtMost(1f))
                out = androidx.core.graphics.ColorUtils.blendARGB(out, shaded, w)
            }
            // near-black detail (a black logo on a white tray) turns light, or it would disappear on the plate
            val k = if (drawn) 0f else neutral * (1f - smooth(0.12f, 0.32f, hsl[2]))
            out = androidx.core.graphics.ColorUtils.blendARGB(out, 0xFFE6E6E8.toInt(), k)
            fg[i] = (out and 0x00FFFFFF) or (a shl 24)
        }
        work.setPixels(fg, 0, size, 0, 0, size, size)
        return work
    }

    /**
     * Icon theme › Glass ([LOOK_IOS]): each app's own icon, its background layer lit from above (a touch lighter at
     * the top, darker at the foot), its foreground on top with white parts fading to [IOS_FADE] at the foot (the plate
     * shows through) over a faint shadow. Night ([night]): the foreground recoloured as for Dark icons ([darkGlyph]) on a
     * #333→#111 plate, lit at its top, darker at its foot, over a crisp dark shadow. Both get a subtle glass rim and no
     * One UI emboss. Games and legacy icons keep their own art under the rim. (Measured from Apple's iOS 26 image.)
     */
    fun createIos(src: Drawable, size: Int, night: Boolean, pkg: String?): Bitmap {
        val s = size.toFloat()
        val layers = blank(size)
        val c = Canvas(layers)
        val game = isGame(pkg)
        if (pkg in CHROME) {
            if (night) {
                c.drawPaint(Paint().apply {
                    shader = android.graphics.LinearGradient(0f, 0f, 0f, s, IOS_DARK_TOP, IOS_DARK_BOTTOM, Shader.TileMode.CLAMP)
                })
                drawChromeLogo(c, s / 2f, s / 2f, s * CHROME_LOGO_FRACTION / 2f)
            } else if (IconShape.current == IconShape.CIRCLE) {
                // under the Circle shape, zoomed in as Default draws it: the logo is the whole icon, no white plate.
                // With Glass's finish, as for any app: the colours are the plate, lit from above; the white ring and the
                // blue centre are the symbol on top of them, with its soft shadow
                val r = s * reach(IconShape.CIRCLE)
                drawChromeLogo(c, s / 2f, s / 2f, r)
                c.drawPaint(iosLight(s))
                val glyph = blank(size)
                Canvas(glyph).apply {
                    // (clipped, not DST_IN: a blend mode only reaches the pixels the circle itself covers)
                    clipPath(Path().apply { addCircle(s / 2f, s / 2f, r * CHROME_RING, Path.Direction.CW) })
                    drawChromeLogo(this, s / 2f, s / 2f, r)
                }
                softShadow(c, glyph, size, 0.2f)
                c.drawBitmap(glyph, 0f, 0f, bitmapPaint)
                glyph.recycle()
            } else {
                // any other shape: the logo on its white plate with its grey ring, as Default draws it, with Glass's
                // finish: the plate lit from above, the logo the symbol on it with its soft shadow
                c.drawColor(Color.WHITE)
                c.drawPaint(iosLight(s))
                val r = s * CHROME_LOGO_FRACTION / 2f
                val glyph = blank(size)
                Canvas(glyph).apply {
                    drawChromeLogo(this, s / 2f, s / 2f, r)
                    drawCircle(s / 2f, s / 2f, r + s * 0.007f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = s * 0.014f; color = 0x2E000000
                    })
                }
                softShadow(c, glyph, size, 0.14f)
                c.drawBitmap(glyph, 0f, 0f, bitmapPaint)
                glyph.recycle()
            }
        } else if (night && !game) {
            val glyph = darkGlyph(src, size)
            if (glyph != null) {
                c.drawPaint(Paint().apply {
                    shader = android.graphics.LinearGradient(0f, 0f, 0f, s, IOS_DARK_TOP, IOS_DARK_BOTTOM, Shader.TileMode.CLAMP)
                })
                iosDarkGlyph(c, glyph, size)
            } else ownIcon(c, src, size)
        } else if (src is AdaptiveIconDrawable && !game) {
            iosLayers(c, src, size)
        } else ownIcon(c, src, size)
        iosRim(c, size, if (night && !game) IOS_RIM_DARK else IOS_RIM_LIGHT, IOS_RIM_WIDTH)
        return clip(layers, size)
    }

    /**
     * Icon theme › Clear Colour ([LOOK_CLEAR_COLOUR]): each app's symbol in its own colours on clear frosted glass: a
     * veil in the Icon colour, [plateAlpha] opaque (Icons › Icon transparency, as Frosted's plate) and lighter toward
     * the foot, over the blurred wallpaper Home draws under the icon (IconView.plateGlass, as for Frosted), with a soft
     * lift under the symbol. Its edge is Default icons' contour (applyEmboss, Icons › Icon contour); 0.231's white rim
     * went in 0.234. The same in light and dark mode. Mockup A of
     * mockups/frosted-colour-2.html. Recolouring is in [clearGlyph].
     */
    fun createClearColour(src: Drawable, size: Int, pkg: String?, plateAlpha: Float = 1f): Bitmap {
        val s = size.toFloat()
        val layers = blank(size)
        val c = Canvas(layers)
        val veil = accent() and 0x00FFFFFF
        val top = plateAlpha.coerceIn(0f, 1f)
        fun at(a: Float) = veil or ((a * 255).roundToInt() shl 24)
        c.drawPaint(Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, s, at(top), at(top * CLEAR_VEIL_FOOT), Shader.TileMode.CLAMP)
        })
        // a soft glow along the top edge, as light catching the glass
        c.drawPaint(Paint().apply {
            shader = android.graphics.RadialGradient(s / 2f, 0f, s * 0.75f, intArrayOf(CLEAR_GLOW, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        })
        val glyph = clearGlyph(src, size, pkg) ?: clearArt(src, size) ?: run {
            // art with no background to take away (a photo, a busy scene): its own icon, whole
            layers.eraseColor(Color.TRANSPARENT)
            ownIcon(c, src, size)
            applyEmboss(c, size)
            return clip(layers, size)
        }
        clearSheen(glyph)
        softShadow(c, glyph, size, CLEAR_SHADOW)
        c.drawBitmap(glyph, 0f, 0f, bitmapPaint)
        glyph.recycle()
        // Icon contour: the same edge as Default icons (One UI's emboss), not 0.231's white rim (user, 0.242)
        applyEmboss(c, size)
        return clip(layers, size)
    }

    /**
     * Clear Colour's cut edges: a pixel near a cut-out part ([UNMIX_CUT] or less left) that is a blend of the plate and
     * the art beside it (on the line between the plate's colour and the strongest solid colour within a few pixels)
     * becomes that art colour, as see-through as it was near the plate. Unlike unblendEdges it looks a few pixels
     * round, not one: a white mark on red, scaled down, blends over 2–3 pixels, and the inner ones stayed pale (user,
     * 0.239: dotted white outlines round YT Music's ring and Skipii's mark). Checked on YT Music's own layers.
     */
    private fun unmixEdges(px: IntArray, drawn: IntArray, bg: IntArray, size: Int) {
        val r = max(2, size / 64)
        val keyed = px.copyOf()
        for (y in 0 until size) for (x in 0 until size) {
            val i = y * size + x
            val a = Color.alpha(drawn[i])
            if (a == 0 || Color.alpha(bg[i]) < 128 || Color.alpha(keyed[i]) == 0) continue
            val p = drawn[i]
            val b = bg[i]
            val d = colorDistance(p, b)
            var cut = false
            var best = -1
            var bestD = 0f
            for (yy in max(0, y - r)..minOf(size - 1, y + r)) for (xx in max(0, x - r)..minOf(size - 1, x + r)) {
                val j = yy * size + xx
                if (Color.alpha(keyed[j]) < UNMIX_CUT) { cut = true; continue }
                if (Color.alpha(drawn[j]) > 200) { val dj = colorDistance(drawn[j], b); if (dj > bestD) { bestD = dj; best = j } }
            }
            if (!cut || best < 0 || bestD < d + 0.08f) continue
            val f = drawn[best]
            val fr = (Color.red(f) - Color.red(b)).toFloat()
            val fg = (Color.green(f) - Color.green(b)).toFloat()
            val fb = (Color.blue(f) - Color.blue(b)).toFloat()
            val pr = (Color.red(p) - Color.red(b)).toFloat()
            val pg = (Color.green(p) - Color.green(b)).toFloat()
            val pb = (Color.blue(p) - Color.blue(b)).toFloat()
            val len2 = fr * fr + fg * fg + fb * fb
            if (len2 < 1f) continue
            val t = ((pr * fr + pg * fg + pb * fb) / len2).coerceIn(0f, 1f)
            val er = pr - t * fr; val eg = pg - t * fg; val eb = pb - t * fb
            // well off that line: detail of its own, not a blend
            if (kotlin.math.sqrt(er * er + eg * eg + eb * eb) / 441.7f > 0.12f) continue
            px[i] = (f and 0x00FFFFFF) or ((a * t).roundToInt() shl 24)
        }
    }

    /** Light from above on a Clear Colour symbol: a white sheen over its top, gone by about 60 % of the way down. Off with Icon contour. */
    private fun clearSheen(glyph: Bitmap) {
        if (!iconShine) return
        val box = opaqueBounds(glyph)
        if (!box.isEmpty) Canvas(glyph).drawPaint(Paint().apply {
            shader = android.graphics.LinearGradient(0f, box.top.toFloat(), 0f, box.top + box.height() * 0.6f,
                CLEAR_SHEEN, 0x00FFFFFF, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        })
    }

    /**
     * Icon theme › Minimal Colour ([LOOK_MINIMAL_COLOUR]): Clear Colour's symbol on its own, the app's own colours with
     * no plate or glass, over Minimal's soft shadow (user, 0.235: "just the inner icon of the frosted coloured icons").
     * Art with no background to take away shows whole, as in Clear Colour.
     */
    fun createMinimalColour(src: Drawable, size: Int, pkg: String?): Bitmap {
        val glyph = clearGlyph(src, size, pkg) ?: clearArt(src, size) ?: run {
            val layers = blank(size)
            ownIcon(Canvas(layers), src, size)
            return clip(layers, size)
        }
        clearSheen(glyph)
        return minimal(glyph, size)
    }

    /**
     * Clear Colour's symbol, on transparent:
     * - Chrome: its logo, drawn ([drawChromeLogo]).
     * - An adaptive icon's foreground, with the parts that match its own plate cut out (they were the plate showing
     *   through: YT Music's ring, WhatsApp's handset), so the glass shows there instead.
     * - What's left keeps its colours when it has some (Photos, Gmail). A white, grey or black symbol takes its plate's
     *   colours instead (gradients included, as Instagram's), white parts in the plate's colour and dark parts in a deep
     *   shade of it (Waze's outline and face). A plate too light to read on glass leaves the symbol as it is.
     * - null for games, legacy icons and foregrounds that still fill the whole icon once the plate's colour is cut out
     *   (Skipii's foreground repeats its white plate, so it's cut away and its red mark is left): [clearArt] takes
     *   those. A small tile of the icon on the glass looked wrong (user, 0.231: Skipii, Monopoly GO).
     */
    private fun clearGlyph(src: Drawable, size: Int, pkg: String?): Bitmap? {
        val s = size.toFloat()
        val glyph = blank(size)
        val g = Canvas(glyph)
        if (pkg in CHROME) {
            drawChromeLogo(g, s / 2f, s / 2f, s * CLEAR_CHROME_RADIUS)
            return glyph
        }
        val d = src as? AdaptiveIconDrawable
        val fg = d?.foreground
        if (d != null && fg != null) {
            val inset = (size * 0.25f).roundToInt()
            val bounds = Rect(-inset, -inset, size + inset, size + inset)
            fg.bounds = bounds
            fg.draw(g)
            val px = IntArray(size * size)
            glyph.getPixels(px, 0, size, 0, 0, size, size)
            if (isGame(pkg)) {
                // a game's layers already split its art from its background: the art alone, exactly as drawn, with
                // no colour matching that could take its shading (user, 0.240: Mr. Monopoly's ear and cheek were
                // eaten by the red key). Art that fills the icon goes to clearArt
                if (coverage(px, size) in CLEAR_EMPTY..CLEAR_FULL_ART) return glyph
                glyph.recycle()
                return null
            }
            val plate = blank(size)
            d.background?.let { it.bounds = bounds; it.draw(Canvas(plate)) }
            val bg = IntArray(size * size)
            plate.getPixels(bg, 0, size, 0, 0, size, size)
            plate.recycle()
            // the plate showing through the symbol: cut out, then the antialiased edge pixels unmixed from the plate's
            // colour ([unmixEdges]), so no pale ring of it is left round the art (user, 0.239: white outlines peeking
            // round YT Music's and Skipii's red marks). Not keyOutBackground: its speck pass also takes thin lines
            // between cut areas, and its unblend looks only a pixel round
            val drawn = px.copyOf()
            for (i in px.indices) {
                val a = Color.alpha(px[i])
                if (a == 0 || Color.alpha(bg[i]) < 128) continue
                val k = smooth(0.06f, 0.16f, colorDistance(px[i], bg[i]))
                if (k < 1f) px[i] = (px[i] and 0x00FFFFFF) or ((a * k).roundToInt() shl 24)
            }
            unmixEdges(px, drawn, bg, size)
            // nothing left (the symbol was all plate colour) shows the icon whole too
            if (coverage(px, size) in CLEAR_EMPTY..CLEAR_FULL_ART) {
                var weight = 0f
                var colour = 0f
                for (p in px) {
                    val a = (p ushr 24) / 255f
                    weight += a
                    if (chroma(p) > CLEAR_COLOURFUL_CHROMA) colour += a
                }
                val plateColour = averageColor(bg)
                val plain = weight > 0f && colour / weight < CLEAR_COLOURFUL_SHARE
                if (plain && plateColour != null && luminance(plateColour) < CLEAR_FILL_MAX_LUMINANCE) {
                    for (i in px.indices) {
                        val p = px[i]
                        if (p ushr 24 == 0) continue
                        val b = bg[i].takeIf { it ushr 24 > 0 } ?: plateColour
                        val k = CLEAR_DEEP + (1f - CLEAR_DEEP) * luminance(p)
                        px[i] = (p and 0xFF000000.toInt()) or
                            ((Color.red(b) * k).roundToInt() shl 16) or ((Color.green(b) * k).roundToInt() shl 8) or (Color.blue(b) * k).roundToInt()
                    }
                }
                glyph.setPixels(px, 0, size, 0, 0, size, size)
                return glyph
            }
        }
        glyph.recycle()
        return null
    }

    /**
     * Clear Colour for an icon that is all art (a game, a legacy icon, a foreground that fills the icon): its own icon
     * with the background around the art taken away, so the glass shows there and the art sits on it in its own
     * colours (user, 0.233: "Monopoly can have the red background be frosted"). The background is the colour most of
     * the icon's outer ring has: a hue for a coloured one, matched by hue so a gradient goes too (Monopoly GO's red
     * runs from orange-red to deep red) and as strong a colour (his skin is a near hue, but paler); otherwise a colour
     * (a white tray). Checked on Monopoly GO's Play Store icon with a port of this. Only background joined to the edge goes
     * (a flood fill inward), so the same colour inside the art stays (Mr. Monopoly's nose and mouth). The art's own
     * edge is softened by how far each pixel's colour is from the background beside it. Null if too little or too much
     * comes away.
     */
    private fun clearArt(src: Drawable, size: Int): Bitmap? {
        val n = size * size
        val work = blank(size)
        ownIcon(Canvas(work), src, size)
        val px = IntArray(n)
        work.getPixels(px, 0, size, 0, 0, size, size)
        val h = size / 2f
        val hsl = FloatArray(3)
        // the outer ring: its hues (coloured pixels only), and all of it for a neutral background
        val bins = FloatArray(24)
        val ring = ArrayList<Int>()
        var coloured = 0
        for (y in 0 until size) for (x in 0 until size) {
            val d = kotlin.math.hypot(x - h, y - h) / size
            if (d < ART_RING_IN || d > ART_RING_OUT) continue
            val p = px[y * size + x]
            ring += p
            if (chroma(p) > ART_CHROMA) {
                androidx.core.graphics.ColorUtils.colorToHSL(p, hsl)
                bins[(hsl[0] / 15f).toInt().coerceIn(0, 23)] += 1f
                coloured++
            }
        }
        if (ring.isEmpty()) { work.recycle(); return null }
        var keyHue = -1f
        var keyChroma = 0f
        var keyColour = 0
        if (coloured > ring.size * ART_COLOURED_RING) {
            // the busiest hue, with its neighbours (a hue near a bin's edge splits between two)
            val top = bins.indices.maxBy { bins[it] + 0.5f * (bins[(it + 23) % 24] + bins[(it + 1) % 24]) }
            val centre = top * 15f + 7.5f
            // the background is the ring's strongest colour: only the most saturated [ART_KEY_SHARE] of that hue
            // family sets its hue and strength. Averaging all of it took in art of a near hue: on the user's phone
            // Monopoly GO's icon is zoomed in, its ring mostly Mr. Monopoly, so his skin pulled the key off the red
            // and his ear was keyed out with it (0.241; found on a screenshot of the icon in Default)
            val family = ArrayList<Pair<Float, Float>>()
            for (p in ring) {
                val ch = chroma(p)
                if (ch <= ART_CHROMA) continue
                androidx.core.graphics.ColorUtils.colorToHSL(p, hsl)
                if (hueGap(hsl[0], centre) <= 22.5f) family += hsl[0] to ch
            }
            family.sortByDescending { it.second }
            val strongest = family.subList(0, maxOf(1, (family.size * ART_KEY_SHARE).toInt()).coerceAtMost(family.size))
            var sx = 0.0; var sy = 0.0; var cs = 0f
            for ((hue, ch) in strongest) {
                sx += kotlin.math.cos(Math.toRadians(hue.toDouble())); sy += kotlin.math.sin(Math.toRadians(hue.toDouble())); cs += ch
            }
            keyHue = ((Math.toDegrees(kotlin.math.atan2(sy, sx)) + 360.0) % 360.0).toFloat()
            // how strong the background's colour is: art of a near hue but weaker colour stays (Mr. Monopoly's shaded skin)
            keyChroma = if (strongest.isNotEmpty()) cs / strongest.size else ART_CHROMA
        } else keyColour = ringMedian(px, size)
        // 0 for the background, 1 for art
        fun art(p: Int): Float = if (keyHue >= 0f) {
            androidx.core.graphics.ColorUtils.colorToHSL(p, hsl)
            max(smooth(ART_HUE_NEAR, ART_HUE_FAR, hueGap(hsl[0], keyHue)), 1f - smooth(keyChroma * ART_WEAK, keyChroma * ART_STRONG, chroma(p)))
        } else smooth(ART_DIST_NEAR, ART_DIST_FAR, colorDistance(p, keyColour))
        val away = FloatArray(n) { art(px[it]) }
        val cleared = BooleanArray(n)
        val queue = ArrayDeque<Int>()
        for (y in 0 until size) for (x in 0 until size) {
            val i = y * size + x
            if (kotlin.math.hypot(x - h, y - h) / size >= ART_RING_IN && away[i] < ART_SEED) { cleared[i] = true; queue.add(i) }
        }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % size
            for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < size - 1) i + 1 else -1, i - size, i + size)) {
                if (j < 0 || j >= n || cleared[j] || away[j] >= ART_PROP || colorDistance(px[i], px[j]) >= ART_STEP) continue
                cleared[j] = true; queue.add(j)
            }
        }
        // the art's edge: as see-through as it is close in colour to the background beside it (an antialiased pixel
        // half background goes half; the art's own colours stay solid)
        val src = px.copyOf()
        for (i in 0 until n) {
            if (cleared[i]) { px[i] = 0; continue }
            val x = i % size
            var near = 1f
            for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < size - 1) i + 1 else -1, i - size, i + size)) {
                if (j >= 0 && j < n && cleared[j]) near = minOf(near, colorDistance(src[i], src[j]))
            }
            if (near < 1f) px[i] = (src[i] and 0x00FFFFFF) or ((Color.alpha(src[i]) * smooth(0.04f, 0.25f, near)).roundToInt() shl 24)
        }
        val cover = coverage(px, size)
        if (cover < ART_MIN || cover > ART_MAX) { work.recycle(); return null }
        work.setPixels(px, 0, size, 0, 0, size, size)
        return work
    }

    /** How far apart two hues are, 0–180°. */
    private fun hueGap(a: Float, b: Float): Float = kotlin.math.abs(((a - b) % 360f + 540f) % 360f - 180f)

    /** How far [shape]'s outline reaches from the icon's centre at its farthest, as a share of the icon (a hair over). */
    private fun reach(shape: String): Float = reaches.getOrPut(shape) {
        val m = android.graphics.PathMeasure(IconShape.path(100f, shape), true)
        val pos = FloatArray(2)
        var far = 0f
        var d = 0f
        while (d <= m.length) {
            m.getPosTan(d, pos, null)
            far = max(far, kotlin.math.hypot(pos[0] - 50f, pos[1] - 50f))
            d += 0.5f
        }
        far / 100f + 0.005f
    }
    private val reaches = java.util.concurrent.ConcurrentHashMap<String, Float>()

    /**
     * Glass's light on a plate: about 10 % lighter at the top and 14 % darker at the foot than in the middle (measured
     * on iOS's plates), over an icon [s] px tall.
     */
    private fun iosLight(s: Float) = Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, s, intArrayOf(0x1FFFFFFF, 0x00FFFFFF, 0x00000000, 0x24000000),
                floatArrayOf(0f, 0.45f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }

    private fun ownIcon(c: Canvas, src: Drawable, size: Int) {
        if (src is AdaptiveIconDrawable) drawAdaptive(c, src, size) else drawLegacy(c, src, size)
    }

    /** Glass in light mode: the background layer lit from above, the foreground's white parts fading toward its foot. */
    private fun iosLayers(c: Canvas, d: AdaptiveIconDrawable, size: Int) {
        val s = size.toFloat()
        val inset = (size * 0.25f).roundToInt()
        val bounds = Rect(-inset, -inset, size + inset, size + inset)
        c.drawColor(Color.BLACK)
        d.background?.let { it.bounds = bounds; it.draw(c) }
        c.drawPaint(iosLight(s))
        val fg = d.foreground ?: return
        val glyph = blank(size)
        fg.bounds = bounds
        fg.draw(Canvas(glyph))
        glassFade(glyph, IOS_FADE, whiteOnly = true)
        softShadow(c, glyph, size, 0.14f)
        c.drawBitmap(glyph, 0f, 0f, bitmapPaint)
        glyph.recycle()
    }

    /** iOS Dark's symbol: lit about 18 % at its top, about 42 % darker at its foot, over a crisp dark shadow. Recycles [glyph]. */
    private fun iosDarkGlyph(c: Canvas, glyph: Bitmap, size: Int) {
        val box = opaqueBounds(glyph)
        if (!box.isEmpty) Canvas(glyph).drawPaint(Paint().apply {
            shader = android.graphics.LinearGradient(0f, box.top.toFloat(), 0f, box.bottom.toFloat(),
                intArrayOf(0x2EFFFFFF, 0x00FFFFFF, 0x6B000000), floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        })
        val offset = IntArray(2)
        val drop = glyph.extractAlpha(Paint().apply { maskFilter = blur(size * 0.007f) }, offset)
        c.drawBitmap(drop, offset[0].toFloat(), offset[1] + size * 0.014f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            color = Color.BLACK; alpha = 191
        })
        drop.recycle()
        c.drawBitmap(glyph, 0f, 0f, bitmapPaint)
        glyph.recycle()
    }

    /** A faint soft shadow of [glyph] just under it, [alpha] black. */
    private fun softShadow(c: Canvas, glyph: Bitmap, size: Int, alpha: Float) {
        val offset = IntArray(2)
        val drop = glyph.extractAlpha(Paint().apply { maskFilter = blur(size * 0.009f) }, offset)
        c.drawBitmap(drop, offset[0].toFloat(), offset[1] + size * 0.007f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            color = Color.BLACK; this.alpha = (255 * alpha).roundToInt()
        })
        drop.recycle()
    }

    /**
     * iOS's glass symbols: opaque at the top, [foot] opaque at the foot, so the plate (or the glass) shows through
     * toward the bottom. [whiteOnly]: only white and near-white parts fade (a colourful logo keeps its colours solid).
     */
    private fun glassFade(glyph: Bitmap, foot: Float, whiteOnly: Boolean) {
        val w = glyph.width
        val h = glyph.height
        val box = opaqueBounds(glyph)
        if (box.height() < 2) return
        val px = IntArray(w * h)
        glyph.getPixels(px, 0, w, 0, 0, w, h)
        val hsl = FloatArray(3)
        for (y in box.top until box.bottom) {
            val t = (y - box.top).toFloat() / (box.height() - 1)
            for (x in 0 until w) {
                val i = y * w + x
                val p = px[i]
                val a = p ushr 24
                if (a == 0) continue
                var k = t
                if (whiteOnly) {
                    androidx.core.graphics.ColorUtils.colorToHSL(p, hsl)
                    k *= (1f - chroma(p) / 0.2f).coerceIn(0f, 1f) * smooth(0.62f, 0.9f, hsl[2])
                }
                if (k <= 0f) continue
                px[i] = (p and 0x00FFFFFF) or ((a * (1f - (1f - foot) * k)).roundToInt().coerceIn(0, 255) shl 24)
            }
        }
        glyph.setPixels(px, 0, w, 0, 0, w, h)
    }

    /**
     * iOS's glass rim: white just inside the edge, about 1 pt, strongest at the top and faintest at the foot ([a]: top,
     * sides, foot). Drawn on the shape's own edge; [clip] cuts away its outer half. Off with Icons › Icon contour.
     */
    private fun iosRim(c: Canvas, size: Int, a: FloatArray, width: Float) {
        if (!iconShine) return
        val s = size.toFloat()
        fun white(x: Float) = ((x.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or 0x00FFFFFF
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            shader = android.graphics.LinearGradient(0f, 0f, 0f, s,
                intArrayOf(white(a[0]), white((a[0] + a[1]) / 2f), white(a[1]), white(a[2])), floatArrayOf(0f, 0.22f, 0.5f, 1f),
                Shader.TileMode.CLAMP)
        }
        val path = maskPath(s)
        p.strokeWidth = s * width
        c.drawPath(path, p)
        p.strokeWidth = s * width * 0.5f
        c.drawPath(path, p)
    }

    /**
     * Chrome as One UI shows it: the logo at [CHROME_LOGO_FRACTION] of a pure white plate with a thin grey ring
     * around it (kept white in dark mode, no night dim), or on the dark plate for Dark icons. Drawn here, not from
     * Chrome's icon: versions put the logo in different layers at different sizes (one overflows the mask), so
     * shrinking the real icon came out blank or as a square crop.
     * Under the Circle shape the logo is the whole icon, as the Pixel Launcher shows it: no plate, no ring (a hair
     * past the circle's 49 % radius, so the clip's edge is all logo).
     */
    private fun createChrome(size: Int, dark: Boolean): Bitmap {
        val layers = blank(size)
        val c = Canvas(layers)
        val full = !dark && IconShape.current == IconShape.CIRCLE
        if (dark) c.drawPaint(Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, size.toFloat(), DARK_TOP, DARK_BOTTOM, Shader.TileMode.CLAMP)
        }) else c.drawColor(Color.WHITE)
        val mid = size / 2f
        val r = if (full) size * 0.495f else size * CHROME_LOGO_FRACTION / 2f
        drawChromeLogo(c, mid, mid, r)
        if (!dark && !full) {
            val w = size * 0.014f
            c.drawCircle(mid, mid, r + w / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = w; color = 0x2E000000
            })
        }
        applyEmboss(c, size)
        return clip(layers, size)
    }

    /**
     * The Chrome logo, radius [r]: a blue centre in a white ring, and three segments (red top, yellow right, green
     * bottom left), each bounded by a line tangent to the ring, the line of the next one rotated 120°.
     */
    private fun drawChromeLogo(c: Canvas, cx: Float, cy: Float, r: Float) {
        val ring = r * CHROME_RING
        val blue = r * 0.37f
        // the red segment's lines end on the outer circle at alpha (right) and alpha + 240° (upper left)
        val alpha = Math.toDegrees(-kotlin.math.asin((ring / r).toDouble())).toFloat()
        val outer = RectF(cx - r, cy - r, cx + r, cy + r)
        val inner = RectF(cx - ring, cy - ring, cx + ring, cy + ring)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawCircle(cx, cy, r, p.apply { color = Color.WHITE })
        for ((i, col) in intArrayOf(0xFFEA4335.toInt(), 0xFFFBBC04.toInt(), 0xFF34A853.toInt()).withIndex()) {
            val rot = 120f * i
            val path = Path()
            path.arcTo(outer, alpha + 240f + rot, 120f, true)               // outer edge, clockwise to the line's end
            path.arcTo(inner, -90f + rot, -120f)                            // along the ring (lineTo its tangent point first)
            path.close()                                                    // the previous segment's line, back out
            c.drawPath(path, p.apply { color = col })
        }
        c.drawCircle(cx, cy, ring, p.apply { color = Color.WHITE })
        c.drawCircle(cx, cy, blue, p.apply { color = 0xFF4285F4.toInt() })
    }

    /**
     * Clear foreground pixels that match the opaque background under them (they read as background in the icon),
     * then what's left stranded around the cleared area: the edge and drop shadow of a tile drawn into the
     * foreground, which would otherwise outline it on the dark plate. Detail away from cleared areas is untouched.
     */
    private fun keyOutBackground(fg: IntArray, bg: IntArray, size: Int) {
        val n = size * size
        val src = fg.copyOf()
        val cleared = IntArray(n)
        for (i in 0 until n) {
            val a = Color.alpha(fg[i])
            if (a == 0 || Color.alpha(bg[i]) < 128) continue
            val k = smooth(0.06f, 0.16f, colorDistance(fg[i], bg[i]))
            if (k >= 1f) continue
            fg[i] = (fg[i] and 0x00FFFFFF) or ((a * k).roundToInt() shl 24)
            if (a > 128 && k < 0.5f) cleared[i] = 1
        }
        val edge = unblendEdges(fg, src, bg, size)
        val clearedSum = summedArea(cleared, size)
        if (clearedSum[n + 2 * size] == 0) return
        val solidSum = summedArea(IntArray(n) { if (Color.alpha(fg[it]) > 128) 1 else 0 }, size)
        val r = max(2, size / 48)
        for (y in 0 until size) for (x in 0 until size) {
            val i = y * size + x
            if (Color.alpha(fg[i]) == 0) continue
            val x0 = max(0, x - r); val y0 = max(0, y - r)
            val x1 = minOf(size, x + r + 1); val y1 = minOf(size, y + r + 1)
            val area = ((x1 - x0) * (y1 - y0)).toFloat()
            if (boxSum(clearedSum, size, x0, y0, x1, y1) / area >= 0.15f &&
                boxSum(solidSum, size, x0, y0, x1, y1) / area < 0.3f) { fg[i] = 0; cleared[i] = 1 }
        }
        // a shadow's faint outer reach: translucent pixels joined to the cleared area go with it
        val queue = ArrayDeque<Int>()
        for (i in 0 until n) if (cleared[i] == 1) queue.add(i)
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % size
            for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < size - 1) i + 1 else -1, i - size, i + size)) {
                if (j < 0 || j >= n || cleared[j] == 1 || edge[j]) continue
                val a = Color.alpha(fg[j])
                if (a == 0 || a > 128) continue
                fg[j] = 0; cleared[j] = 1; queue.add(j)
            }
        }
    }

    /**
     * Edge pixels drawn against the background are part logo colour, part background: a logo on a white square inside
     * the foreground layer (Skipii), a legacy icon on its tray, or a white logo whose see-through edge is mixed with
     * the brand colour drawn under it (Life360). Kept as they are, they ring the logo in a pale, dotted fringe on the
     * dark plate. Each pixel beside the background is judged as it shows over the background, and takes the colour of
     * the strongest solid logo pixel next to it instead, with the share of that colour it holds as its alpha. [src]:
     * the pixels as drawn, [fg]: the foreground being built. Returns the pixels it changed.
     */
    private fun unblendEdges(fg: IntArray, src: IntArray, bg: IntArray, size: Int): BooleanArray {
        val n = size * size
        // each pixel as it shows: over the background where that's solid
        val shown = IntArray(n) {
            val s = src[it]; val b = bg[it]
            if (Color.alpha(b) >= 128) androidx.core.graphics.ColorUtils.compositeColors(s, b or (0xFF shl 24)) else s
        }
        val d = FloatArray(n) {
            if (Color.alpha(bg[it]) >= 128 || Color.alpha(src[it]) > 128) colorDistance(shown[it], bg[it]) else -1f
        }
        val done = BooleanArray(n)
        val r = max(1, size / 96)
        for (y in 0 until size) for (x in 0 until size) {
            val i = y * size + x
            if (d[i] < 0f || Color.alpha(src[i]) == 0) continue
            var nearBg = false
            var best = -1
            var bestD = d[i] + 0.05f
            for (yy in max(0, y - r)..minOf(size - 1, y + r)) for (xx in max(0, x - r)..minOf(size - 1, x + r)) {
                val j = yy * size + xx
                if (d[j] < 0f) continue
                if (d[j] < 0.06f) nearBg = true
                // only solid logo pixels give the colour (not a translucent shadow)
                if (d[j] > bestD && Color.alpha(src[j]) > 128) { bestD = d[j]; best = j }
            }
            if (!nearBg || best < 0) continue
            // p = b + t (f - b): how far along from the background to the logo colour; a pixel well off that line
            // is detail of its own, not a blend
            val p = shown[i]; val b = bg[i]; val f = shown[best]
            val fr = (Color.red(f) - Color.red(b)).toFloat()
            val fgr = (Color.green(f) - Color.green(b)).toFloat()
            val fb = (Color.blue(f) - Color.blue(b)).toFloat()
            val pr = (Color.red(p) - Color.red(b)).toFloat()
            val pg = (Color.green(p) - Color.green(b)).toFloat()
            val pb = (Color.blue(p) - Color.blue(b)).toFloat()
            // the logo pixel can be this pixel's background colour exactly (a background layer that varies): no line
            val len2 = fr * fr + fgr * fgr + fb * fb
            if (len2 < 1f) continue
            val t = ((pr * fr + pg * fgr + pb * fb) / len2).coerceIn(0f, 1f)
            val er = pr - t * fr; val eg = pg - t * fgr; val eb = pb - t * fb
            if (kotlin.math.sqrt(er * er + eg * eg + eb * eb) / 441.7f > 0.08f) continue
            fg[i] = (f and 0x00FFFFFF) or ((255 * t).roundToInt() shl 24)
            done[i] = true
        }
        return done
    }

    /** Summed-area table of [v] ([size]² values), (size + 1)² entries with a zero first row and column. */
    private fun summedArea(v: IntArray, size: Int): IntArray {
        val w = size + 1
        val s = IntArray(w * w)
        for (y in 0 until size) {
            var row = 0
            for (x in 0 until size) {
                row += v[y * size + x]
                s[(y + 1) * w + x + 1] = s[y * w + x + 1] + row
            }
        }
        return s
    }

    private fun boxSum(s: IntArray, size: Int, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val w = size + 1
        return s[y1 * w + x1] - s[y0 * w + x1] - s[y1 * w + x0] + s[y0 * w + x0]
    }

    /** The brand colour as a glyph on the dark plate: kept vivid and lifted to read (white if it has no hue). */
    private fun glyphTint(brand: Int): Int {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(brand, hsl)
        if (chroma(brand) < 0.08f) return 0xFFF2F2F4.toInt()
        hsl[1] = hsl[1].coerceIn(0.45f, 0.9f)
        hsl[2] = hsl[2].coerceIn(0.58f, 0.72f)
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
    }

    /** Average colour of the opaque pixels, or null if there are none. */
    private fun averageColor(px: IntArray): Int? {
        var r = 0L; var g = 0L; var b = 0L; var k = 0L
        for (p in px) if (Color.alpha(p) > 128) { r += Color.red(p); g += Color.green(p); b += Color.blue(p); k++ }
        return if (k == 0L) null else Color.rgb((r / k).toInt(), (g / k).toInt(), (b / k).toInt())
    }

    /** Share of the mask (its inscribed circle, near enough) that [px] covers. */
    private fun coverage(px: IntArray, size: Int): Float {
        val h = size / 2f
        val r2 = (size * 0.48f) * (size * 0.48f)
        var inside = 0; var covered = 0f
        for (y in 0 until size) for (x in 0 until size) {
            val dx = x - h; val dy = y - h
            if (dx * dx + dy * dy > r2) continue
            inside++
            covered += Color.alpha(px[y * size + x]) / 255f
        }
        return if (inside == 0) 0f else covered / inside
    }

    /** How coloured [c] is, 0 (grey, white or black) to 1, whatever its lightness. */
    private fun chroma(c: Int): Float {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return (maxOf(r, g, b) - minOf(r, g, b)) / 255f
    }

    private fun colorDistance(a: Int, b: Int): Float {
        val dr = Color.red(a) - Color.red(b); val dg = Color.green(a) - Color.green(b); val db = Color.blue(a) - Color.blue(b)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat()) / 441.7f
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun tone(plate: Int, glyph: Int, away: Int, up: Float, dn: Float): Int =
        (plate + (glyph - plate) * up + (away - plate) * dn).roundToInt().coerceIn(0, 255)

    private fun luminance(c: Int) = (Color.red(c) * 0.2126f + Color.green(c) * 0.7152f + Color.blue(c) * 0.0722f) / 255f

    /** Median colour of the ring just inside the mask edge (the icon's own background colour). */
    private fun ringMedian(px: IntArray, size: Int): Int {
        val r = ArrayList<Int>(); val g = ArrayList<Int>(); val b = ArrayList<Int>()
        val h = size / 2f
        for (y in 0 until size) for (x in 0 until size) {
            val d = kotlin.math.hypot(x - h, y - h)
            if (d > size * 0.36f && d < size * 0.44f) {
                val p = px[y * size + x]; r += Color.red(p); g += Color.green(p); b += Color.blue(p)
            }
        }
        if (r.isEmpty()) return TRAY_COLOR
        r.sort(); g.sort(); b.sort()
        return Color.rgb(r[r.size / 2], g[g.size / 2], b[b.size / 2])
    }

    /**
     * Render [src] as a One UI 8.5 icon of [size] px. [opaqueBase] false keeps a see-through background layer
     * see-through (glass icons) instead of over black.
     */
    fun create(src: Drawable, size: Int, night: Boolean = isNight(), opaqueBase: Boolean = true): Bitmap {
        val layers = blank(size)
        val c = Canvas(layers)
        if (src is AdaptiveIconDrawable) {
            drawAdaptive(c, src, size, opaqueBase)
        } else {
            drawLegacy(c, src, size)
        }
        applyEmboss(c, size)
        if (night) c.drawPaint(nightPaint)
        return clip(layers, size)
    }

    private fun drawAdaptive(c: Canvas, d: AdaptiveIconDrawable, size: Int, opaqueBase: Boolean = true) {
        if (opaqueBase) c.drawColor(Color.BLACK)
        // AdaptiveIconDrawable layers are 1.5x the masked bounds (viewport 72/108, EXTRA_INSET 25 %).
        val inset = (size * 0.25f).roundToInt()
        val b = Rect(-inset, -inset, size + inset, size + inset)
        d.background?.let { it.bounds = b; it.draw(c) }
        d.foreground?.let { it.bounds = b; it.draw(c) }
    }

    /**
     * SemAppIconSolution.getAppIconAlphaRelativeScaleForIconTray(): sample 8 points on a 192 grid; if all
     * are opaque (alpha > 26) and the icon is square → crop (full bleed); otherwise contain at 0.72 on a
     * #FEFEFE tray.
     */
    private fun drawLegacy(c: Canvas, d: Drawable, size: Int) {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: size
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: size
        val probe = blank(ANALYSIS_SIZE)
        val scale = ANALYSIS_SIZE.toFloat() / max(iw, ih)
        val pw = (iw * scale).roundToInt()
        val ph = (ih * scale).roundToInt()
        val pl = (ANALYSIS_SIZE - pw) / 2
        val pt = (ANALYSIS_SIZE - ph) / 2
        d.setBounds(pl, pt, pl + pw, pt + ph)
        d.draw(Canvas(probe))

        // transparent margin (IconScale.alpha): trim to content bounds
        val content = opaqueBounds(probe)
        var filled = 0
        if (!content.isEmpty) {
            for (p in ONE_DOT) {
                val x = content.left + p[0] * content.width() / ANALYSIS_SIZE
                val y = content.top + p[1] * content.height() / ANALYSIS_SIZE
                if (Color.alpha(probe.getPixel(x.coerceIn(0, ANALYSIS_SIZE - 1), y.coerceIn(0, ANALYSIS_SIZE - 1))) > 26) filled++
            }
        }
        val square = kotlin.math.abs(content.width() - content.height()) <= 2
        val target = RectF()
        if (filled == 8 && square) {
            // CROP: content fills the whole icon
            target.set(0f, 0f, size.toFloat(), size.toFloat())
        } else {
            c.drawColor(TRAY_COLOR)
            val s = if (filled == 8) 0.68f else CONTAIN_SCALE
            val cw = content.width().coerceAtLeast(1)
            val ch = content.height().coerceAtLeast(1)
            val fit = size * s / max(cw, ch)
            val w = cw * fit
            val h = ch * fit
            target.set((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f)
        }
        c.drawBitmap(probe, content, target, bitmapPaint)
        probe.recycle()
    }

    private fun opaqueBounds(b: Bitmap): Rect {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var bt = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 26) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > bt) bt = y
            }
        }
        return if (r < 0) Rect() else Rect(l, t, r + 1, bt + 1)
    }

    private fun applyEmboss(c: Canvas, size: Int) {
        if (!iconShine) return
        // the bitmaps follow the squircle's edge; another shape gets the same rims drawn along its own
        if (!IconShape.isSquircle) { IconShape.drawEmboss(c, size); return }
        val dst = Rect(0, 0, size, size)
        c.drawBitmap(highlight, null, dst, highlightPaint)
        c.drawBitmap(shadow, null, dst, shadowPaint)
    }

    /**
     * A new [size]² bitmap, cleared to transparent. Only some icons, only on the user's Android 17 phone and only
     * in Dot, came out with opaque white corners; [clip] leaves everything outside the shape untouched, so a new
     * bitmap that isn't cleared would keep a freed icon's pixels there. Not reproduced, so this is a guard, not a
     * confirmed fix.
     */
    private fun blank(size: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }

    private fun clip(layers: Bitmap, size: Int): Bitmap {
        val out = blank(size)
        val path = maskPath(size.toFloat())
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.shader = BitmapShader(layers, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        Canvas(out).drawPath(path, p)
        layers.recycle()
        return out
    }
}
