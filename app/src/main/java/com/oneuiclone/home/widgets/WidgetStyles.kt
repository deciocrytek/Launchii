package com.oneuiclone.home.widgets

import android.content.Context

/**
 * Per-widget and per-folder background style (long-press › Style), as One UI's widget settings: Original, or Blur
 * with a transparency, where the background fades by that much over blurred wallpaper. A folder's style covers its
 * closed icon and its pop-up card.
 */
object WidgetStyles {
    private const val PREFS = "widget_styles"
    /** Where the slider starts when Blur is picked for something that was Original. */
    const val DEFAULT_TRANSPARENCY = 50
    /** Stored for an explicit Original; no value at all means the default applies. */
    private const val ORIGINAL = -2

    /** Widgets start as Blur at this transparency (and [THEME_DARK]), except [LIGHT_BY_DEFAULT] ones (Original, light). */
    const val WIDGET_DEFAULT_TRANSPARENCY = 100
    /** Folders start as Blur at this transparency. */
    const val FOLDER_DEFAULT_TRANSPARENCY = 70

    /** 0..100 when the widget is set to Blur, null for Original. */
    fun transparency(context: Context, appWidgetId: Int, providerPackage: String?): Int? =
        read(context, key(appWidgetId), if (providerPackage in LIGHT_BY_DEFAULT) null else WIDGET_DEFAULT_TRANSPARENCY)

    fun set(context: Context, appWidgetId: Int, transparency: Int?) = write(context, key(appWidgetId), transparency)

    private fun read(context: Context, key: String, default: Int?): Int? = when (val v = prefs(context).getInt(key, -1)) {
        -1 -> default
        ORIGINAL -> null
        else -> v
    }

    private fun write(context: Context, key: String, transparency: Int?) {
        prefs(context).edit().putInt(key, transparency?.coerceIn(0, 100) ?: ORIGINAL).apply()
    }

    /** Forgets everything stored for a removed widget. */
    fun clear(context: Context, appWidgetId: Int) {
        prefs(context).edit().remove(key(appWidgetId)).remove(themeKey(appWidgetId)).remove(alignKey(appWidgetId)).apply()
    }

    /** Same as [transparency], for the folder with model id [folderId]. */
    fun folderTransparency(context: Context, folderId: Long): Int? = read(context, folderKey(folderId), FOLDER_DEFAULT_TRANSPARENCY)

    fun setFolder(context: Context, folderId: Long, transparency: Int?) = write(context, folderKey(folderId), transparency)

    const val THEME_AUTO = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2
    /** Widgets shown Original and in their light version unless set otherwise (the user asked for Keep's to stay light). */
    private val LIGHT_BY_DEFAULT = setOf("com.google.android.keep")

    /** Which version of the widget to show: [THEME_AUTO] follows the system light/dark mode. Dark by default. */
    fun theme(context: Context, appWidgetId: Int, providerPackage: String?): Int =
        prefs(context).getInt(themeKey(appWidgetId), -1).takeIf { it >= 0 }
            ?: if (providerPackage in LIGHT_BY_DEFAULT) THEME_LIGHT else THEME_DARK

    fun setTheme(context: Context, appWidgetId: Int, theme: Int) {
        prefs(context).edit().putInt(themeKey(appWidgetId), theme).apply()
    }

    /**
     * Whether the widget sits on the icons' centre line (long-press › Align with icons) instead of centred in its
     * rows (the default): with Home labels on, the icons sit above their cells' centres by [IconView.labelLift].
     */
    fun iconAligned(context: Context, appWidgetId: Int): Boolean = prefs(context).getBoolean(alignKey(appWidgetId), false)

    fun setIconAligned(context: Context, appWidgetId: Int, aligned: Boolean) {
        prefs(context).edit().putBoolean(alignKey(appWidgetId), aligned).apply()
    }

    private fun key(appWidgetId: Int) = "t_$appWidgetId"
    private fun alignKey(appWidgetId: Int) = "a_$appWidgetId"
    private fun themeKey(appWidgetId: Int) = "m_$appWidgetId"
    private fun folderKey(folderId: Long) = "f_$folderId"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
