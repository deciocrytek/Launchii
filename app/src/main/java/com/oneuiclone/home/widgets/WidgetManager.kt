package com.oneuiclone.home.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.os.Bundle
import android.os.UserHandle
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.RemoteViews
import com.oneuiclone.home.ui.GlassPaint
import com.oneuiclone.home.ui.WidgetGlass
import com.oneuiclone.home.device.DeviceProfile
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * App widget hosting. One UI clips widgets to 26dp corners (AppTransitionParams.WIDGET_FINAL_RADIUS).
 */
class WidgetManager(private val activity: Activity) {

    companion object {
        const val HOST_ID = 0x0E1
        const val REQUEST_BIND = 4101
        const val REQUEST_CONFIGURE = 4102
    }

    val appWidgetManager: AppWidgetManager = AppWidgetManager.getInstance(activity)
    val host = object : AppWidgetHost(activity, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            RoundedWidgetView(context)
    }

    /**
     * Widget clicks that send a PendingIntent: RemoteViews starts it through the clicked view's context, which is
     * the widget's [LaunchContext]. Return true when the launcher sends it itself (by calling `send`).
     */
    var onLaunch: ((appWidgetId: Int, sender: IntentSender, send: () -> Unit) -> Boolean)? = null

    /** Pending add: provider waiting for bind/config result. */
    var pending: Pending? = null

    class Pending(val appWidgetId: Int, val info: AppWidgetProviderInfo, val onDone: (Int, AppWidgetProviderInfo) -> Unit,
                  val onCancel: () -> Unit = {})

    private var listening = false

    /**
     * Listen from first start until destroy (Quickstep's widget holder does the same). Restarting on every
     * onStart re-applies every RemoteViews, which shows up as the Home screen "refreshing" after an app closes.
     */
    fun start() {
        if (listening) return
        try { host.startListening(); listening = true } catch (_: Exception) {}
    }

    fun stop() {
        if (!listening) return
        listening = false
        try { host.stopListening() } catch (_: Exception) {}
    }

    fun providers(): List<AppWidgetProviderInfo> = appWidgetManager.installedProviders
        .filter { (it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0 }

    fun findProvider(cn: ComponentName, user: UserHandle? = null): AppWidgetProviderInfo? =
        appWidgetManager.getInstalledProvidersForPackage(cn.packageName, user).firstOrNull { it.provider == cn }

    /** Cell span for a provider (targetCellWidth/Height when declared, else min size). */
    fun spanFor(info: AppWidgetProviderInfo, dp: DeviceProfile): Pair<Int, Int> {
        if (info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            return info.targetCellWidth.coerceAtMost(dp.homeCols) to info.targetCellHeight.coerceAtMost(dp.homeRows)
        }
        val sx = max(1, ceil(info.minWidth / dp.cellWidth.toFloat()).toInt())
        val sy = max(1, ceil(info.minHeight / dp.cellHeight.toFloat()).toInt())
        return sx.coerceAtMost(dp.homeCols) to sy.coerceAtMost(dp.homeRows)
    }

    /** Start adding [info]; [onDone] is called with the bound id once ready, [onCancel] if the user says no. */
    fun add(info: AppWidgetProviderInfo, onCancel: () -> Unit = {}, onDone: (Int, AppWidgetProviderInfo) -> Unit) {
        val id = host.allocateAppWidgetId()
        val opts = Bundle()
        if (appWidgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, opts)) {
            configureOrFinish(id, info, onDone, onCancel)
        } else {
            pending = Pending(id, info, onDone, onCancel)
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            activity.startActivityForResult(intent, REQUEST_BIND)
        }
    }

    private fun configureOrFinish(id: Int, info: AppWidgetProviderInfo, onDone: (Int, AppWidgetProviderInfo) -> Unit,
                                  onCancel: () -> Unit = {}) {
        val needsConfig = info.configure != null &&
            (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) == 0
        if (needsConfig) {
            pending = Pending(id, info, onDone, onCancel)
            try {
                host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE, null)
            } catch (e: Exception) {
                pending = null; onDone(id, info)
            }
        } else {
            onDone(id, info)
        }
    }

    /** Forwarded from Activity.onActivityResult. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        val p = pending ?: return false
        when (requestCode) {
            REQUEST_BIND -> {
                pending = null
                if (resultCode == Activity.RESULT_OK) configureOrFinish(p.appWidgetId, p.info, p.onDone, p.onCancel)
                else { host.deleteAppWidgetId(p.appWidgetId); p.onCancel() }
                return true
            }
            REQUEST_CONFIGURE -> {
                pending = null
                if (resultCode == Activity.RESULT_OK) p.onDone(p.appWidgetId, p.info) else { host.deleteAppWidgetId(p.appWidgetId); p.onCancel() }
                return true
            }
        }
        return false
    }

    /**
     * Host views survive workspace rebinds (drop, rename, resize, page changes): creating a new one re-inflates
     * the widget's RemoteViews, which is slow and makes widgets flash. Only a span change re-sizes it.
     */
    private val views = HashMap<Int, AppWidgetHostView>()
    private val sizes = HashMap<Int, SizeF>()
    private val themes = HashMap<Int, Int>()

    fun createView(context: Context, appWidgetId: Int, dp: DeviceProfile, spanX: Int, spanY: Int): AppWidgetHostView? {
        val info = appWidgetManager.getAppWidgetInfo(appWidgetId) ?: return null
        val theme = WidgetStyles.theme(activity, appWidgetId, info.provider.packageName)
        val v = views[appWidgetId]?.takeIf { it.appWidgetInfo?.provider == info.provider && themes[appWidgetId] == theme }
            ?: host.createView(LaunchContext(context, appWidgetId, theme), appWidgetId, info).also {
                views[appWidgetId]?.let { old -> (old.parent as? android.view.ViewGroup)?.removeView(old) }
                views[appWidgetId] = it; themes[appWidgetId] = theme; sizes.remove(appWidgetId)
            }
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        // the size it's really drawn at, inside WidgetContainer's padding: widgets that build their layout for the
        // reported size (Glance, Google Weather) otherwise lay out for more room than they get and cut text off
        val pad = 2 * dp.widgetPaddingPx
        val size = SizeF((spanX * dp.cellWidth - pad) / dp.density, (spanY * dp.cellHeight - pad) / dp.density)
        if (sizes[appWidgetId] != size) {
            v.updateAppWidgetSize(Bundle(), listOf(size))
            sizes[appWidgetId] = size
        }
        return v
    }

    /**
     * Drop every cached host view so the next bind inflates the widgets' RemoteViews again. Needed on a light/dark
     * switch: the activity handles uiMode itself, and an existing view keeps the layout it inflated in the old mode.
     */
    fun clearViews() {
        views.values.forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        views.clear(); sizes.clear()
    }

    fun delete(appWidgetId: Int) {
        views.remove(appWidgetId); sizes.remove(appWidgetId); themes.remove(appWidgetId)
        WidgetStyles.clear(activity, appWidgetId)
        try { host.deleteAppWidgetId(appWidgetId) } catch (_: Exception) {}
    }

    /**
     * A widget's view context. The public route to a widget's clicks: AppWidgetHostView's InteractionHandler is
     * hidden API, but its default handler starts every PendingIntent with `view.context.startIntentSender`.
     */
    private inner class LaunchContext(base: Context, private val appWidgetId: Int, theme: Int) : ContextWrapper(base) {
        /**
         * Style › Theme: the widget's light or dark version whatever the system mode. RemoteViews inflates with
         * this context's configuration (getContextForResourcesEnsuringCorrectCachedApkPaths keeps it), so its
         * own night resources and day/night colours follow it.
         */
        private val themed: Context? = if (theme == WidgetStyles.THEME_AUTO) null else base.createConfigurationContext(
            android.content.res.Configuration(base.resources.configuration).apply {
                uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (theme == WidgetStyles.THEME_DARK) android.content.res.Configuration.UI_MODE_NIGHT_YES
                    else android.content.res.Configuration.UI_MODE_NIGHT_NO
            })

        override fun getResources(): android.content.res.Resources = themed?.resources ?: super.getResources()

        override fun startIntentSender(intent: IntentSender, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int,
                                       extraFlags: Int, options: Bundle?) {
            val send = { super.startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, options) }
            if (onLaunch?.invoke(appWidgetId, intent, send) != true) send()
        }

        override fun startIntentSender(intent: IntentSender, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int) =
            startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, null)
    }

    /**
     * Hardware-safe capture of a widget on Home that can re-frost live against any [WidgetGlass]
     * (used by Set wallpaper / Wallpaper & style previews and by Recent wallpapers tile overlays).
     */
    class WidgetCapture(
        val content: Bitmap,
        val glassMask: Bitmap? = null,
        val keyColor: Int = 0,
        val keyInner: Int = 0,
        val opacity: Float = 1f,
        val radius: Float = 0f,
        val hasGlass: Boolean = false,
        /** Cell padding and vertical alignment lift from [com.oneuiclone.home.ui.WidgetContainer], in px. */
        val pad: Int = 0,
        val lift: Int = 0,
    ) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val glassPaint = GlassPaint().apply { paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
        private val clip = android.graphics.Path()
        private val dst = Rect()
        private val keyMatrix = Matrix()
        private var keyShader: RuntimeShader? = null
        private var keyPaint: Paint? = null
        private val shaderMatrix = Matrix()
        private var lastGlassBmp: Bitmap? = null
        private var lastGlassShader: BitmapShader? = null
        private var lastContentShader: BitmapShader? = null

        private fun glassShaderFor(bmp: Bitmap, matrix: Matrix): BitmapShader {
            val s = if (lastGlassBmp === bmp && lastGlassShader != null) lastGlassShader!!
            else BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { lastGlassBmp = bmp; lastGlassShader = it }
            s.setLocalMatrix(matrix)
            return s
        }

        /** Draws the widget into [canvas] at [dstW]×[dstH], frosted against [glass] at [toWindow]. */
        fun draw(canvas: Canvas, dstW: Int, dstH: Int, glass: WidgetGlass?, toWindow: Matrix) =
            drawWithGlass(canvas, dstW, dstH, if (hasGlass) glass?.bitmap else null, glass?.scale ?: 1f, toWindow)

        /** Draws the widget into [canvas] at [dstW]×[dstH], frosted against an explicit [glassBmp]/[glassScale]. */
        fun drawWithGlass(canvas: Canvas, dstW: Int, dstH: Int, glassBmp: Bitmap?, glassScale: Float, toWindow: Matrix) {
            if (dstW <= 0 || dstH <= 0) return
            val saved = canvas.save()
            if (radius > 0f && content.width > 0) {
                val r = radius * (dstW / content.width.toFloat())
                clip.reset()
                clip.addRoundRect(0f, 0f, dstW.toFloat(), dstH.toFloat(), r, r, android.graphics.Path.Direction.CW)
                canvas.clipPath(clip)
            }
            dst.set(0, 0, dstW, dstH)
            when {
                keyColor != 0 -> drawKeyed(canvas, dstW, dstH, glassBmp, glassScale, toWindow)
                glassMask != null -> {
                    if (hasGlass && glassBmp != null && toWindow.invert(keyMatrix)) {
                        keyMatrix.preScale(glassScale, glassScale)
                        val sc = canvas.saveLayer(0f, 0f, dstW.toFloat(), dstH.toFloat(), null)
                        canvas.drawBitmap(glassMask, null, dst, paint)
                        glassPaint.paint.shader = glassShaderFor(glassBmp, keyMatrix)
                        canvas.drawRect(0f, 0f, dstW.toFloat(), dstH.toFloat(), glassPaint.paint)
                        canvas.restoreToCount(sc)
                    }
                    canvas.drawBitmap(content, null, dst, paint)
                }
                else -> canvas.drawBitmap(content, null, dst, paint)
            }
            canvas.restoreToCount(saved)
        }

        private fun drawKeyed(canvas: Canvas, dstW: Int, dstH: Int, glassBmp: Bitmap?, glassScale: Float, toWindow: Matrix) {
            if (canvas.isHardwareAccelerated) {
                val sh = keyShader ?: RuntimeShader(ColourKey.AGSL).also { keyShader = it }
                val p = keyPaint ?: Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).also { keyPaint = it }
                shaderMatrix.setScale(dstW / content.width.toFloat(), dstH / content.height.toFloat())
                val contentShader = (lastContentShader ?: BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { lastContentShader = it })
                    .apply { setLocalMatrix(shaderMatrix) }
                val glassShader = if (hasGlass && glassBmp != null && toWindow.invert(keyMatrix)) {
                    keyMatrix.preScale(glassScale, glassScale)
                    glassShaderFor(glassBmp, keyMatrix)
                } else ColourKey.NO_GLASS
                sh.setInputShader("content", contentShader)
                sh.setInputShader("glass", glassShader)
                sh.setFloatUniform("key", android.graphics.Color.red(keyColor) / 255f, android.graphics.Color.green(keyColor) / 255f,
                    android.graphics.Color.blue(keyColor) / 255f, android.graphics.Color.alpha(keyColor) / 255f)
                sh.setFloatUniform("inner", android.graphics.Color.red(keyInner) / 255f, android.graphics.Color.green(keyInner) / 255f,
                    android.graphics.Color.blue(keyInner) / 255f, android.graphics.Color.alpha(keyInner) / 255f)
                sh.setFloatUniform("opacity", opacity)
                sh.setFloatUniform("hasGlass", if (glassShader === ColourKey.NO_GLASS) 0f else 1f)
                p.shader = sh
                canvas.drawRect(0f, 0f, dstW.toFloat(), dstH.toFloat(), p)
            } else {
                val shaded = ColourKey.shadeSoftware(content, dstW, dstH, keyColor, keyInner, opacity,
                    if (hasGlass) glassBmp else null, glassScale, toWindow)
                canvas.drawBitmap(shaded, null, dst, paint)
            }
        }
    }

    /** AppWidgetHostView clipped to One UI's 26dp widget radius. */
    class RoundedWidgetView(context: Context) : AppWidgetHostView(context) {
        private val radius = 26f * context.resources.displayMetrics.density
        init {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }

        /**
         * Style › Blur: the widget's own background at [backgroundOpacity] over [glass], which is masked by that
         * same background so it takes its exact shape. Its content stays as it is. Every update can replace the
         * background, so this is re-applied after updates and checked before each frame (which also redraws the
         * glass when the widget moves).
         */
        var backgroundOpacity = 1f
            set(v) { field = v.coerceIn(0f, 1f); applyStyle() }
        var glass: WidgetGlass? = null
            set(v) {
                if (field === v) return
                if (isAttachedToWindow) { field?.views?.remove(this); v?.views?.add(this) }
                field = v
                applyStyle()
            }
        private val styled get() = backgroundOpacity < 1f || glass != null
        val isKeyed get() = keyed && keyColor != 0
        private val wrapped = ArrayList<GlassDrawable>()
        private val keepStyled = ViewTreeObserver.OnPreDrawListener { applyStyle(); true }
        private val overrideClip = android.graphics.Path()
        private val overrideToWindow = Matrix()

        override fun draw(canvas: Canvas) {
            if (GlassPaint.overrideActive && width > 0 && height > 0) {
                val sc = canvas.save()
                overrideClip.reset()
                overrideClip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, android.graphics.Path.Direction.CW)
                canvas.clipPath(overrideClip)
                if (keyed && keyColor != 0) {
                    val cap = GlassPaint.overrideKeyedWidgets?.get(appWidgetId) ?: capture(0.35f)
                    if (cap != null) {
                        overrideToWindow.reset(); transformMatrixToGlobal(overrideToWindow)
                        cap.drawWithGlass(canvas, width, height, GlassPaint.overrideBitmap, GlassPaint.overrideScale, overrideToWindow)
                    } else super.draw(canvas)
                } else {
                    super.draw(canvas)
                }
                canvas.restoreToCount(sc)
                return
            }
            super.draw(canvas)
        }

        /**
         * Captures this widget into a hardware-safe [WidgetCapture] at [scale] (0..1) so previews can re-frost it
         * over any wallpaper without touching or flickering the live widget.
         */
        fun capture(scale: Float = 1f, pad: Int = 0, lift: Int = 0): WidgetCapture? {
            if (width == 0 || height == 0) return null
            val cw = max(1, (width * scale).roundToInt())
            val ch = max(1, (height * scale).roundToInt())
            val sx = cw / width.toFloat(); val sy = ch / height.toFloat()
            fun record(drawBlock: (Canvas) -> Unit): Bitmap? = try {
                val pic = android.graphics.Picture()
                val c = pic.beginRecording(cw, ch)
                c.scale(sx, sy)
                drawBlock(c)
                pic.endRecording()
                val hw = Bitmap.createBitmap(pic, cw, ch, Bitmap.Config.HARDWARE)
                val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
                hw.recycle()
                sw
            } catch (e: Exception) {
                android.util.Log.w("OneUiHome", "widget capture failed", e)
                null
            }
            if (keyed && keyColor != 0) {
                val raw = record { super.draw(it) } ?: return null
                return WidgetCapture(raw, keyColor = keyColor, keyInner = keyInner, opacity = backgroundOpacity,
                    radius = radius * sx, hasGlass = glass != null, pad = pad, lift = lift)
            }
            if (wrapped.isNotEmpty() && glass != null) {
                val content = GlassPaint.withOverride(null, 1f) { record { super.draw(it) } } ?: return null
                val hostToWin = Matrix().also { transformMatrixToGlobal(it) }
                val winToHost = Matrix()
                val mask = if (hostToWin.invert(winToHost)) {
                    record { c ->
                        val m = Matrix()
                        for (gd in wrapped) {
                            gd.locateInto(m)
                            m.postConcat(winToHost)
                            c.save()
                            c.concat(m)
                            gd.drawable?.draw(c)
                            c.restore()
                        }
                    }
                } else null
                return WidgetCapture(content, glassMask = mask, radius = radius * sx, hasGlass = true, pad = pad, lift = lift)
            }
            val bmp = record { super.draw(it) } ?: return null
            return WidgetCapture(bmp, radius = radius * sx, hasGlass = false, pad = pad, lift = lift)
        }

        override fun updateAppWidget(remoteViews: RemoteViews?) {
            super.updateAppWidget(remoteViews)
            keyDirty = true
            applyStyle()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            keyDirty = true
            glass?.views?.add(this)
            viewTreeObserver.addOnPreDrawListener(keepStyled)
        }

        override fun onDetachedFromWindow() {
            viewTreeObserver.removeOnPreDrawListener(keepStyled)
            glass?.views?.remove(this)
            super.onDetachedFromWindow()
        }

        private fun applyStyle() {
            if (!styled && wrapped.isEmpty() && !keyed) return
            val root = getChildAt(0) ?: return
            val targets = backgroundImages(root) + listOfNotNull(backgroundView(root))
            wrapped.clear()
            for (v in targets) {
                val d = (if (v is ImageView) v.drawable else v.background) ?: continue
                if (!styled) { if (d is GlassDrawable) setDrawable(v, d.drawable!!); continue }
                val g = d as? GlassDrawable ?: GlassDrawable(d, v).also { setDrawable(v, null); setDrawable(v, it) }
                g.update(backgroundOpacity, glass)
                wrapped += g
            }
            if (styled && wrapped.isEmpty()) applyKey() else clearKey()
        }

        // ---- colour-key fallback ----------------------------------------------------------------------------
        // Widgets with no background view to wrap (drawn as one surface, e.g. RemoteCompose on Android 16): the
        // background colour, read at the middle of the top edge, is replaced by glass in a GPU shader on the whole
        // widget. Pixels near that colour blend over by how close they are. (A RenderEffect shader can't read its
        // input anywhere but the pixel it draws, so the colour is read beforehand: from the widget's own content,
        // drawn off screen without the effect, again after every update, re-creation or resize, as a light/dark
        // switch or new data can change it.) For [ColourKey.CLEAR_INNER] widgets the inner card's colour (Pixel
        // Weather's black/white hourly strip) is keyed out the same way.

        private var keyed = false
        /** Background colour read for the key: 0 when there's no opaque one; stale when [keyDirty]. */
        private var keyColor = 0
        /** The inner card's colour keyed out with it, 0 for none. */
        private var keyInner = 0
        private var keyDirty = true
        private var keyApplied = 0
        private var keyInnerApplied = 0
        private var keyRetries = 0
        private val keyRetry = Runnable { applyStyle() }
        private var keyShader: RuntimeShader? = null
        private val keyAt = Matrix()
        private val keyToWindow = Matrix()
        private val keyGlassMatrix = Matrix()
        private var keyBitmap: Bitmap? = null
        private var keyOpacity = -1f
        private var keyW = 0
        private var keyH = 0

        private fun applyKey() {
            if (width == 0 || height == 0) return
            if (keyDirty || width != keyW || height != keyH) {
                val k = readKey()
                when {
                    k != null -> { keyColor = k.first; keyInner = k.second; keyDirty = false; keyRetries = 0 }
                    // An update's new views aren't laid out (or drawn) yet: keep the colour it had, so the widget
                    // stays see-through, and read again shortly. Give up after a few seconds (no solid background).
                    keyRetries < ColourKey.KEY_RETRIES -> { keyRetries++; removeCallbacks(keyRetry); postDelayed(keyRetry, 300) }
                    else -> { keyDirty = false; keyRetries = 0; if (keyColor == 0) return }
                }
            }
            val key = keyColor
            val inner = keyInner
            keyToWindow.reset(); transformMatrixToGlobal(keyToWindow)
            val g = glass
            val bmp = g?.bitmap
            if (keyed && keyToWindow == keyAt && bmp === keyBitmap && backgroundOpacity == keyOpacity && width == keyW &&
                height == keyH && key == keyApplied && inner == keyInnerApplied) return
            keyAt.set(keyToWindow); keyBitmap = bmp; keyOpacity = backgroundOpacity; keyW = width; keyH = height
            keyApplied = key; keyInnerApplied = inner
            val sh = keyShader ?: RuntimeShader(ColourKey.AGSL).also { keyShader = it }
            val glassShader = if (bmp != null && keyToWindow.invert(keyGlassMatrix)) {
                keyGlassMatrix.preScale(g.scale, g.scale)                 // glass pixels → window → this view
                BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(keyGlassMatrix) }
            } else ColourKey.NO_GLASS
            sh.setInputShader("glass", glassShader)
            sh.setFloatUniform("key", android.graphics.Color.red(key) / 255f, android.graphics.Color.green(key) / 255f,
                android.graphics.Color.blue(key) / 255f, android.graphics.Color.alpha(key) / 255f)
            sh.setFloatUniform("inner", android.graphics.Color.red(inner) / 255f, android.graphics.Color.green(inner) / 255f,
                android.graphics.Color.blue(inner) / 255f, android.graphics.Color.alpha(inner) / 255f)
            sh.setFloatUniform("opacity", backgroundOpacity)
            sh.setFloatUniform("hasGlass", if (glassShader === ColourKey.NO_GLASS) 0f else 1f)
            setRenderEffect(RenderEffect.createRuntimeShaderEffect(sh, "content"))
            keyed = true
        }

        /**
         * Down the column from the top edge: the first opaque colour that holds for a few pixels (past the
         * background's anti-aliased edge), i.e. the widget's background. Null if there's none.
         */
        private fun firstSolid(col: Bitmap): Int? {
            val run = 4
            for (y in 0 until col.height - run) {
                val c = col.getPixel(0, y)
                if (android.graphics.Color.alpha(c) < 250) continue
                if ((1..run).all { col.getPixel(0, y + it) == c }) return c
            }
            return null
        }

        private fun clearKey() {
            keyDirty = true                                  // read afresh next time, from the widget as it is
            removeCallbacks(keyRetry)
            if (!keyed) return
            keyed = false
            setRenderEffect(null)
        }

        /**
         * The inner card's colour in [col] below the background: the most common neutral near-black or near-white
         * colour (Pixel Weather's hourly strip, black in dark mode and white in light) that's clearly not [key] and
         * holds for [ColourKey.INNER_RUN_DP] in one piece, so text and icons don't count. 0 when there's none.
         */
        private fun innerSolid(col: Bitmap, key: Int): Int {
            val minRun = (ColourKey.INNER_RUN_DP * resources.displayMetrics.density).toInt().coerceAtLeast(4)
            val runs = HashMap<Int, Int>()                   // colour → total length of its long runs
            var y = 0
            while (y < col.height) {
                val c = col.getPixel(0, y)
                var end = y + 1
                while (end < col.height && col.getPixel(0, end) == c) end++
                if (end - y >= minRun && ColourKey.isInnerCard(c, key)) runs[c] = (runs[c] ?: 0) + end - y
                y = end
            }
            return runs.maxByOrNull { it.value }?.key ?: 0
        }

        /**
         * The widget's background colour, from its own content: the top of the middle column drawn off screen
         * (so without the effect, and whatever is on screen), rendered by the GPU since widgets can hold hardware
         * bitmaps, paired with the inner card's colour to clear too (0 for none; only [ColourKey.CLEAR_INNER]
         * widgets, read down the whole column). Null when there's no opaque background there.
         */
        private fun readKey(): Pair<Int, Int>? {
            val root = getChildAt(0) ?: return null
            if (root.width == 0 || root.height == 0) return null
            val clearInner = ColourKey.clearsInner(appWidgetInfo?.provider)
            val depth = if (clearInner) height else (ColourKey.DEPTH_DP * resources.displayMetrics.density).toInt().coerceIn(8, height)
            val x = width / 2
            return try {
                val pic = android.graphics.Picture()
                val c = pic.beginRecording(width, depth)
                c.clipRect(x, 0, x + 1, depth)
                c.translate(root.left.toFloat(), root.top.toFloat())
                root.draw(c)
                pic.endRecording()
                val hw = Bitmap.createBitmap(pic, width, depth, Bitmap.Config.HARDWARE)
                val col = hw.copy(Bitmap.Config.ARGB_8888, false)
                hw.recycle()
                val column = Bitmap.createBitmap(col, x, 0, 1, depth)
                val key = firstSolid(column) ?: return null
                key to if (clearInner) innerSolid(column, key) else 0
            } catch (e: Exception) {
                android.util.Log.w("OneUiHome", "widget colour key read failed", e)
                null
            }
        }

        /** The widget's view tree as text, for working out how a widget draws its background (Style's diagnostic file). */
        fun describe(): String {
            val sb = StringBuilder()
            val root = getChildAt(0)
            sb.append("provider: ").append(appWidgetInfo?.provider?.flattenToShortString()).append('\n')
            sb.append("android: ").append(android.os.Build.VERSION.RELEASE).append(" (sdk ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
            sb.append("host: ").append(width).append('x').append(height).append('\n')
            sb.append("style: opacity ").append(backgroundOpacity).append(", glass ").append(glass?.bitmap != null)
                .append(", wrapped ").append(wrapped.size).append(", colour key ").append(keyed)
                .append(" #").append(Integer.toHexString(keyColor)).append(" inner #").append(Integer.toHexString(keyInner)).append('\n')
            sb.append("background view: ").append(root?.let { backgroundView(it) }?.let { name(it) })
                .append(", background images: ").append(root?.let { backgroundImages(it).size }).append("\n\n")
            fun walk(v: View, depth: Int) {
                sb.append("  ".repeat(depth)).append(name(v))
                    .append(" [").append(v.left).append(',').append(v.top).append(' ').append(v.width).append('x').append(v.height).append(']')
                if (v.visibility != VISIBLE) sb.append(if (v.visibility == GONE) " GONE" else " INVISIBLE")
                if (v.alpha != 1f) sb.append(" alpha=").append(v.alpha)
                if (v.clipToOutline) sb.append(" clipToOutline")
                v.background?.let { sb.append(" bg=").append(drawableInfo(it)) }
                v.backgroundTintList?.let { sb.append(" bgTint=#").append(Integer.toHexString(it.defaultColor)) }
                if (v is ImageView) v.drawable?.let { sb.append(" src=").append(drawableInfo(it)) }
                if (v is android.widget.TextView) sb.append(" text=\"").append(v.text.toString().take(30)).append('"')
                sb.append('\n')
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
            root?.let { walk(it, 0) }
            return sb.toString()
        }

        private fun name(v: View): String {
            val id = if (v.id == View.NO_ID) "" else try { "#" + v.resources.getResourceEntryName(v.id) } catch (_: Exception) { "#" + Integer.toHexString(v.id) }
            return v.javaClass.name.substringAfterLast('.') + id
        }

        private fun drawableInfo(d: Drawable): String = when (d) {
            is android.graphics.drawable.ColorDrawable -> "Color#" + Integer.toHexString(d.color)
            is android.graphics.drawable.GradientDrawable -> "Gradient(" + (d.color?.defaultColor?.let { "#" + Integer.toHexString(it) } ?: "-") + ", r=" + d.cornerRadius + ")"
            is DrawableWrapper -> d.javaClass.simpleName + "(" + (d.drawable?.let { drawableInfo(it) } ?: "-") + ")"
            else -> d.javaClass.name.substringAfterLast('.') + " " + d.intrinsicWidth + "x" + d.intrinsicHeight
        }

        /** Sets [v]'s background (its image for an ImageView). Clearing it first detaches the old drawable, so a
         * wrapper made around it keeps the drawable's callback. */
        private fun setDrawable(v: View, d: Drawable?) = if (v is ImageView) v.setImageDrawable(d) else v.background = d

        /**
         * The view drawing the widget's background, found as Launcher3's RoundedCornerEnforcement does: the one
         * tagged @android:id/background (Android 12+ widgets), else the first view with a background down the
         * chain of single children from the widget's root.
         */
        private fun backgroundView(root: View): View? {
            root.findViewById<View>(android.R.id.background)?.takeIf { it.background != null }?.let { return it }
            var v: View = root
            while (v.background == null) v = (v as? ViewGroup)?.takeIf { it.childCount == 1 }?.getChildAt(0) ?: return null
            return v
        }

        /**
         * Older widgets (Google's search bar) draw their background as images instead: the ImageViews that fill
         * their parent before any content, at the first level down the single-child chain that has several views.
         */
        private fun backgroundImages(root: View): List<ImageView> {
            var g = root as? ViewGroup ?: return emptyList()
            while (g.childCount == 1) g = g.getChildAt(0) as? ViewGroup ?: return emptyList()
            if (g.width == 0 || g.height == 0) return emptyList()
            val out = ArrayList<ImageView>()
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                if (c.visibility == GONE) continue
                if (c !is ImageView || c.width < g.width * 0.9f || c.height < g.height * 0.9f) break
                out += c
            }
            return out
        }
    }

    /** The colour-key fallback's shader ([RoundedWidgetView] with no background view to wrap). */
    private object ColourKey {
        /** Reads of the background colour that may come up empty after an update before the widget counts as having none. */
        const val KEY_RETRIES = 10
        /** How far down from the middle of the top edge the colour-key fallback looks for the background colour. */
        const val DEPTH_DP = 28f
        /** How long a run of one colour must be to count as an inner card rather than text or an icon. */
        const val INNER_RUN_DP = 12f
        /** Widgets whose inner card is keyed out along with the background (the user asked for Pixel Weather's). */
        private val CLEAR_INNER = setOf("com.google.android.apps.weather")

        fun clearsInner(provider: ComponentName?): Boolean = provider != null && (provider.packageName in CLEAR_INNER ||
            // the same widget, when the Google app still hosts it
            provider.packageName == "com.google.android.googlequicksearchbox" && provider.className.contains("weather", true))

        /**
         * Opaque, grey (no tint) near-black or near-white, and not the background [key], on its far side
         * from the text: darker than a dark background, lighter than a light one (so white text on dark isn't it).
         */
        fun isInnerCard(c: Int, key: Int): Boolean {
            if (android.graphics.Color.alpha(c) < 250) return false
            val r = android.graphics.Color.red(c); val g = android.graphics.Color.green(c); val b = android.graphics.Color.blue(c)
            if (maxOf(r, g, b) - minOf(r, g, b) > 16) return false
            val lum = (r + g + b) / 3
            if (lum in 40..215) return false
            val keyLum = (android.graphics.Color.red(key) + android.graphics.Color.green(key) + android.graphics.Color.blue(key)) / 3
            if (if (keyLum < 128) lum > keyLum else lum < keyLum) return false
            // Only needs to not be the key itself: light mode's background (Material tone 94, e.g. #F3EDF7) is
            // just ~23 away from its white cards, which a wider margin missed.
            val dr = r - android.graphics.Color.red(key); val dg = g - android.graphics.Color.green(key); val db = b - android.graphics.Color.blue(key)
            return dr * dr + dg * dg + db * db > 6 * 6
        }

        val NO_GLASS = BitmapShader(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

        const val AGSL = """
            uniform shader content;
            uniform shader glass;
            uniform float4 key;
            uniform float4 inner;
            uniform float opacity;
            uniform float hasGlass;

            half4 main(float2 p) {
                half4 c = content.eval(p);
                if (key.a < 0.9 || c.a < 0.004) return c;
                float3 u = float3(c.rgb) / float(c.a);       // unpremultiplied
                // the nearest background colour: the key, or with an inner card, anywhere between the two (its
                // anti-aliased edge blends them, and would otherwise stay as an outline)
                float t = 0.0;
                if (inner.a > 0.9) {
                    float3 e = inner.rgb - key.rgb;
                    t = clamp(dot(u - key.rgb, e) / dot(e, e), 0.0, 1.0);
                }
                float3 k = mix(key.rgb, inner.rgb, t);
                // how close this pixel is to it
                float w = 1.0 - smoothstep(0.015, 0.08, distance(u, k));
                half4 g = hasGlass > 0.5 ? glass.eval(p) : half4(0.0);
                half4 bg = half4(half3(k), 1.0) * opacity + g * (1.0 - opacity);
                return mix(c, bg * c.a, w);
            }
        """

        /** Software equivalent of [AGSL] for offscreen software canvases (Recent wallpapers / Wallpaper & style). */
        fun shadeSoftware(
            raw: Bitmap,
            dstW: Int,
            dstH: Int,
            keyColor: Int,
            keyInner: Int,
            opacity: Float,
            glassBmp: Bitmap?,
            glassScale: Float,
            toWindow: Matrix,
        ): Bitmap {
            val w = raw.width; val h = raw.height
            val n = w * h
            val px = IntArray(n)
            raw.getPixels(px, 0, w, 0, 0, w, h)
            val keyA = android.graphics.Color.alpha(keyColor)
            if (keyA < 230) return raw
            val kr = android.graphics.Color.red(keyColor) / 255f
            val kg = android.graphics.Color.green(keyColor) / 255f
            val kb = android.graphics.Color.blue(keyColor) / 255f
            val hasInner = android.graphics.Color.alpha(keyInner) > 230
            val ir = android.graphics.Color.red(keyInner) / 255f
            val ig = android.graphics.Color.green(keyInner) / 255f
            val ib = android.graphics.Color.blue(keyInner) / 255f
            val er = ir - kr; val eg = ig - kg; val eb = ib - kb
            val eLen2 = (er * er + eg * eg + eb * eb).coerceAtLeast(1e-6f)

            val hasGlass = glassBmp != null
            val gw = glassBmp?.width ?: 1
            val gh = glassBmp?.height ?: 1
            val gpx = if (glassBmp != null) IntArray(gw * gh).also { glassBmp.getPixels(it, 0, gw, 0, 0, gw, gh) } else null
            // Map raw pixel (x, y) → widget local (dstW, dstH) → window → glass pixel
            val m = Matrix()
            m.setScale(dstW / w.toFloat(), dstH / h.toFloat())
            m.postConcat(toWindow)
            if (glassScale > 0f) m.postScale(1f / glassScale, 1f / glassScale)
            val mv = FloatArray(9); m.getValues(mv)
            val m00 = mv[Matrix.MSCALE_X]; val m01 = mv[Matrix.MSKEW_X]; val m02 = mv[Matrix.MTRANS_X]
            val m10 = mv[Matrix.MSKEW_Y]; val m11 = mv[Matrix.MSCALE_Y]; val m12 = mv[Matrix.MTRANS_Y]
            val invOp = 1f - opacity

            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val c = px[row + x]
                    val ca = c ushr 24
                    if (ca == 0) continue
                    val ur = ((c shr 16) and 0xFF) / 255f
                    val ug = ((c shr 8) and 0xFF) / 255f
                    val ub = (c and 0xFF) / 255f
                    val mr: Float; val mg: Float; val mb: Float
                    if (hasInner) {
                        val t = (((ur - kr) * er + (ug - kg) * eg + (ub - kb) * eb) / eLen2).coerceIn(0f, 1f)
                        mr = kr + er * t; mg = kg + eg * t; mb = kb + eb * t
                    } else {
                        mr = kr; mg = kg; mb = kb
                    }
                    val dr = ur - mr; val dg = ug - mg; val db = ub - mb
                    val dist = kotlin.math.sqrt(dr * dr + dg * dg + db * db)
                    if (dist >= 0.08f) continue
                    val s = ((dist - 0.015f) / (0.08f - 0.015f)).coerceIn(0f, 1f)
                    val weight = 1f - s * s * (3f - 2f * s)
                    var gr = 0f; var gg = 0f; var gb = 0f
                    if (gpx != null) {
                        val gx = (m00 * x + m01 * y + m02).toInt().coerceIn(0, gw - 1)
                        val gy = (m10 * x + m11 * y + m12).toInt().coerceIn(0, gh - 1)
                        val gp = gpx[gy * gw + gx]
                        gr = ((gp shr 16) and 0xFF) / 255f
                        gg = ((gp shr 8) and 0xFF) / 255f
                        gb = (gp and 0xFF) / 255f
                    }
                    val caF = ca / 255f
                    val bgR = mr * opacity + gr * invOp
                    val bgG = mg * opacity + gg * invOp
                    val bgB = mb * opacity + gb * invOp
                    val bgA = if (hasGlass) 1f else opacity
                    val invW = 1f - weight
                    val outA = caF * (invW + bgA * weight)
                    if (outA < 0.004f) {
                        px[row + x] = 0
                    } else {
                        val outR = (ur * caF * invW + bgR * caF * weight) / outA
                        val outG = (ug * caF * invW + bgG * caF * weight) / outA
                        val outB = (ub * caF * invW + bgB * caF * weight) / outA
                        val aI = (outA * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val rI = (outR * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val gI = (outG * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val bI = (outB * 255f + 0.5f).toInt().coerceIn(0, 255)
                        px[row + x] = (aI shl 24) or (rI shl 16) or (gI shl 8) or bI
                    }
                }
            }
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
        }
    }

    /**
     * A widget background ([owner]'s background or image) drawn at [opacity] over [glass]. The glass is cut to the
     * background's own pixels (its shape, corners and edge), and placed in window coordinates so it lines up with
     * the real wallpaper. Layers, so overlapping parts of the background don't add up.
     */
    private class GlassDrawable(inner: Drawable, private val owner: View) : DrawableWrapper(inner) {
        private var opacity = 1f
        private var glass: WidgetGlass? = null
        private val toWindow = Matrix()
        private val drawnAt = Matrix()
        private var drawnBitmap: Bitmap? = null
        private val glassPaint = GlassPaint().apply { paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }

        /** Redraws when the style changed, the glass was rebuilt, or the widget moved on screen. */
        fun update(opacity: Float, glass: WidgetGlass?) {
            var dirty = opacity != this.opacity || glass !== this.glass
            this.opacity = opacity; this.glass = glass
            if (glass != null) {
                locate(toWindow)
                if (toWindow != drawnAt || glass.bitmap !== drawnBitmap) dirty = true
            }
            if (dirty) invalidateSelf()
        }

        /** Drawable coordinates → window. */
        fun locateInto(m: Matrix) = locate(m)

        private fun locate(m: Matrix) {
            m.reset()
            owner.transformMatrixToGlobal(m)
            if (owner is ImageView) {
                m.preTranslate(owner.paddingLeft.toFloat(), owner.paddingTop.toFloat())
                m.preConcat(owner.imageMatrix)
            }
        }

        override fun draw(canvas: Canvas) {
            val l = bounds.left.toFloat(); val t = bounds.top.toFloat()
            val r = bounds.right.toFloat(); val b = bounds.bottom.toFloat()
            if (!GlassPaint.overrideActive) drawnBitmap = glass?.bitmap
            locate(drawnAt)
            if (glassPaint.aim(glass, drawnAt)) {
                val sc = canvas.saveLayer(l, t, r, b, null)
                super.draw(canvas)                                       // the mask: the background itself
                canvas.drawRect(l, t, r, b, glassPaint.paint)
                canvas.restoreToCount(sc)
            }
            if (opacity <= 0f) return
            val sc = canvas.saveLayerAlpha(l, t, r, b, (opacity * 255).roundToInt())
            super.draw(canvas)
            canvas.restoreToCount(sc)
        }
    }
}
