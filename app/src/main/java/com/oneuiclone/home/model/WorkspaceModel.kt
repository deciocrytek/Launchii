package com.oneuiclone.home.model

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Container { WORKSPACE, HOTSEAT, FOLDER }

sealed class HomeItem {
    abstract val id: Long
    abstract var container: Container
    abstract var screen: Int
    abstract var x: Int
    abstract var y: Int
    open val spanX: Int get() = 1
    open val spanY: Int get() = 1

    data class App(
        override val id: Long,
        val key: AppKey,
        override var container: Container,
        override var screen: Int,
        override var x: Int,
        override var y: Int,
        var folderId: Long = -1,
        var rank: Int = 0,
    ) : HomeItem()

    data class Widget(
        override val id: Long,
        var appWidgetId: Int,
        val provider: android.content.ComponentName,
        override var container: Container,
        override var screen: Int,
        override var x: Int,
        override var y: Int,
        var sx: Int,
        var sy: Int,
    ) : HomeItem() {
        override val spanX: Int get() = sx
        override val spanY: Int get() = sy
    }

    data class Folder(
        override val id: Long,
        var title: String,
        override var container: Container,
        override var screen: Int,
        override var x: Int,
        override var y: Int,
        /** 0 = default glass, otherwise ARGB. */
        var color: Int = 0,
        var large: Boolean = false,
    ) : HomeItem() {
        override val spanX: Int get() = if (large) 2 else 1
        override val spanY: Int get() = if (large) 2 else 1
    }
}

/** Workspace items + JSON persistence, and the first run's default layout ([createDefault]). */
class WorkspaceModel(private val context: Context, private val repo: AppRepository) {

    private val file = File(context.filesDir, "workspace.json")
    val items = ArrayList<HomeItem>()
    var pageCount = 1
    private var nextId = 1L

    fun newId() = nextId++

    fun appsInFolder(folderId: Long): List<HomeItem.App> =
        items.filterIsInstance<HomeItem.App>().filter { it.container == Container.FOLDER && it.folderId == folderId }.sortedBy { it.rank }

    /** Apps you can see or reach from Home page 1: its icons, the dock, and the folders on either. */
    fun firstPageApps(): Set<AppKey> {
        fun onFirst(it: HomeItem) = it.container == Container.HOTSEAT || (it.container == Container.WORKSPACE && it.screen == 0)
        val folders = items.filter { it is HomeItem.Folder && onFirst(it) }.map { it.id }.toSet()
        return items.filterIsInstance<HomeItem.App>()
            .filter { onFirst(it) || (it.container == Container.FOLDER && it.folderId in folders) }
            .map { it.key }.toSet()
    }

    fun itemsOn(container: Container, screen: Int): List<HomeItem> =
        items.filter { it.container == container && (container == Container.HOTSEAT || it.screen == screen) }

    /** First free [sx]×[sy] area on [screen], scanning rows top-down. */
    fun findSpace(screen: Int, sx: Int, sy: Int, cols: Int, rows: Int): Pair<Int, Int>? {
        for (y in 0..rows - sy) for (x in 0..cols - sx) if (!isOccupied(screen, x, y, sx, sy)) return x to y
        return null
    }

    fun isOccupied(screen: Int, x: Int, y: Int, sx: Int = 1, sy: Int = 1, ignore: HomeItem? = null): Boolean =
        items.any {
            it !== ignore && it.container == Container.WORKSPACE && it.screen == screen &&
                x < it.x + it.spanX && x + sx > it.x && y < it.y + it.spanY && y + sy > it.y
        }

    /**
     * Whether [items] came from disk (or a snapshot, or [createDefault]). Until then they're just the empty list a new
     * model starts with, and [save] would write that over the real layout: a cold start's few seconds before the app
     * list arrives were enough for any save (a new page, a widget) to leave Home blank for good.
     */
    var ready = false
        private set

    /** Snapshots of earlier layouts (newest first), see [save] and [restore]. */
    private val snapDir = File(context.filesDir, "layouts")
    /** Item count of the file on disk, to notice a save that drops most of the layout. */
    private var savedCount = -1

    /**
     * Reads the layout. A file that won't parse is kept aside (never overwritten by a default) and the newest snapshot
     * is used instead; so is a snapshot when the file is missing but snapshots exist.
     */
    fun load(): Boolean {
        if (file.exists()) {
            if (parse(file)) { ready = true; savedCount = items.size; return true }
            val bad = File(context.filesDir, "workspace-broken-${System.currentTimeMillis()}.json")
            try { file.copyTo(bad, overwrite = true); file.delete() } catch (_: Exception) {}
            android.util.Log.w("OneUiHome", "layout: workspace.json unreadable (${bad.length()} bytes), kept as ${bad.name}")
        }
        for (snap in snapshotFiles()) {
            if (parse(snap)) {
                android.util.Log.w("OneUiHome", "layout: restored from snapshot ${snap.name} (${items.size} items)")
                ready = true; savedCount = -1      // written back on the next save
                return true
            }
        }
        items.clear()
        return false
    }

    private fun parse(f: File): Boolean = try {
        val root = JSONObject(f.readText())
        val list = ArrayList<HomeItem>()
        val arr = root.getJSONArray("items")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val c = Container.valueOf(o.getString("c"))
            when (o.getString("t")) {
                "app" -> AppKey.parse(o.getString("k"))?.let {
                    list += HomeItem.App(o.getLong("id"), it, c, o.getInt("s"), o.getInt("x"), o.getInt("y"),
                        o.optLong("f", -1), o.optInt("r", 0))
                }
                "widget" -> android.content.ComponentName.unflattenFromString(o.getString("p"))?.let {
                    list += HomeItem.Widget(o.getLong("id"), o.getInt("wid"), it, c, o.getInt("s"), o.getInt("x"), o.getInt("y"),
                        o.getInt("sx"), o.getInt("sy"))
                }
                "folder" -> list += HomeItem.Folder(o.getLong("id"), o.getString("title"), c, o.getInt("s"),
                    o.getInt("x"), o.getInt("y"), o.optInt("color", 0), o.optBoolean("large", false))
            }
        }
        pageCount = root.optInt("pages", 1)
        nextId = maxOf(root.optLong("nextId", 1), (list.maxOfOrNull { it.id } ?: 0) + 1)
        items.clear()
        items.addAll(list)
        true
    } catch (e: Exception) {
        android.util.Log.w("OneUiHome", "layout: can't read ${f.name}: $e")
        false
    }

    fun save() {
        if (!ready) {
            android.util.Log.w("OneUiHome", "layout: save before load refused (${items.size} items)", Throwable())
            return
        }
        val arr = JSONArray()
        for (it in items) {
            val o = JSONObject().put("id", it.id).put("c", it.container.name).put("s", it.screen).put("x", it.x).put("y", it.y)
            when (it) {
                is HomeItem.App -> o.put("t", "app").put("k", it.key.flatten()).put("f", it.folderId).put("r", it.rank)
                is HomeItem.Folder -> o.put("t", "folder").put("title", it.title).put("color", it.color).put("large", it.large)
                is HomeItem.Widget -> o.put("t", "widget").put("wid", it.appWidgetId).put("p", it.provider.flattenToString())
                    .put("sx", it.sx).put("sy", it.sy)
            }
            arr.put(o)
        }
        val root = JSONObject().put("pages", pageCount).put("nextId", nextId).put("items", arr)
        snapshotBefore(items.size)
        val tmp = File(file.path + ".tmp")
        try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(root.toString().toByteArray())
                out.fd.sync()                           // a rename that lands before the data would leave an empty file
            }
            if (!tmp.renameTo(file)) android.util.Log.w("OneUiHome", "layout: rename failed")
            savedCount = items.size
        } catch (e: Exception) {
            android.util.Log.w("OneUiHome", "layout: save failed: $e")
        }
    }

    /**
     * Copies the file on disk into [snapDir] before it's replaced: at most once an hour as a matter of course, and
     * always when the new layout has under half the items (whatever caused that, the old one can be restored).
     */
    private fun snapshotBefore(newCount: Int) {
        if (!file.exists() || file.length() == 0L) return
        val newest = snapshotFiles().firstOrNull()
        val now = System.currentTimeMillis()
        val big = savedCount >= 4 && newCount * 2 < savedCount
        if (!big && newest != null && now - newest.lastModified() < 60 * 60 * 1000L) return
        try {
            snapDir.mkdirs()
            file.copyTo(File(snapDir, "workspace-$now.json"), overwrite = true)
            if (big) android.util.Log.w("OneUiHome", "layout: $savedCount → $newCount items, snapshot kept", Throwable())
            snapshotFiles().drop(MAX_SNAPSHOTS).forEach { it.delete() }
        } catch (e: Exception) {
            android.util.Log.w("OneUiHome", "layout: snapshot failed: $e")
        }
    }

    private fun snapshotFiles(): List<File> =
        snapDir.listFiles { f -> f.name.startsWith("workspace-") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name.removePrefix("workspace-").removeSuffix(".json").toLongOrNull() ?: 0 }
            .orEmpty()

    /** A saved earlier layout, for Settings › Restore Home screen layout. */
    class Snapshot(val file: File, val time: Long, val apps: Int, val folders: Int, val widgets: Int)

    fun snapshots(): List<Snapshot> = snapshotFiles().mapNotNull { f ->
        try {
            val arr = JSONObject(f.readText()).getJSONArray("items")
            var apps = 0; var folders = 0; var widgets = 0
            for (i in 0 until arr.length()) when (arr.getJSONObject(i).optString("t")) {
                "app" -> apps++; "folder" -> folders++; "widget" -> widgets++
            }
            Snapshot(f, f.name.removePrefix("workspace-").removeSuffix(".json").toLongOrNull() ?: f.lastModified(), apps, folders, widgets)
        } catch (_: Exception) { null }
    }

    /** Replaces the layout with [snap]'s (the current one is snapshotted first, so a restore can be undone). */
    fun restore(snap: Snapshot): Boolean {
        val keep = ArrayList(items); val keepPages = pageCount; val keepNext = nextId
        if (!parse(snap.file)) { items.clear(); items.addAll(keep); pageCount = keepPages; nextId = keepNext; return false }
        ready = true
        savedCount = -1
        if (file.exists()) try {
            snapDir.mkdirs()
            file.copyTo(File(snapDir, "workspace-${System.currentTimeMillis()}.json"), overwrite = true)
        } catch (_: Exception) {}
        save()
        return true
    }

    companion object {
        const val MAX_SNAPSHOTS = 10
    }

    /**
     * Remove entries whose app is really gone: uninstalled, or its profile removed. An app that's only missing from the
     * list for now (being updated, its profile paused or locked, a list read half-way) keeps its spot, unshown until it's
     * back; one whose activity an update renamed moves to the package's new one. Deleting on absence alone emptied Home.
     */
    fun prune() {
        if (repo.apps.isEmpty()) return
        val missing = items.filterIsInstance<HomeItem.App>().filter { repo.get(it.key) == null }
        if (missing.isEmpty()) return
        val dead = ArrayList<HomeItem.App>()
        var moved = false
        for (a in missing) {
            when (val s = repo.whereIs(a.key)) {
                AppRepository.Gone -> dead += a
                is AppRepository.Moved ->
                    if (items.any { it is HomeItem.App && it.key == s.key }) dead += a
                    else { items[items.indexOf(a)] = a.copy(key = s.key); moved = true }
                AppRepository.Away -> Unit
            }
        }
        android.util.Log.i("OneUiHome", "prune: ${missing.size} missing, ${dead.size} gone, moved=$moved")
        if (dead.isEmpty()) { if (moved) save(); return }
        items.removeAll(dead.toSet())
        // dissolve folders that became too small
        for (f in items.filterIsInstance<HomeItem.Folder>().toList()) {
            val content = appsInFolder(f.id)
            if (content.isEmpty()) items.remove(f)
        }
        save()
    }

    // ---------------- default layout ----------------

    private fun resolve(intent: Intent): AppInfo? {
        val ri = context.packageManager.resolveActivity(intent, 0) ?: return null
        val pkg = ri.activityInfo?.packageName ?: return null
        if (pkg == "android") return null
        return repo.findByPackage(pkg)
    }

    /**
     * A widget of the default layout, placed once Home is up (binding it can need the user's consent): the first
     * installed provider from [packages] whose class name contains [classHint], preferring one that's [sx]×[sy].
     */
    class DefaultWidget(val x: Int, val y: Int, val sx: Int, val sy: Int, val packages: List<String>, val classHint: String)

    /** The widgets [createDefault] left for the launcher to add. */
    var defaultWidgets: List<DefaultWidget> = emptyList()
        private set

    /**
     * The layout the app's maker uses (4×6, page 1): widgets on top, folders and apps below. Each spot holds its app
     * only if it's installed (else it stays empty); the dock closes up. Rows count from the bottom on a shorter grid,
     * and anything that no longer fits is left out.
     */
    fun createDefault(cols: Int, rows: Int) {
        ready = true
        items.clear()
        nextId = 1
        pageCount = 2
        val used = HashSet<AppKey>()
        fun app(vararg pkgs: String, label: String? = null): AppInfo? =
            (pkgs.firstNotNullOfOrNull { repo.findByPackage(it) }
                ?: label?.let { l -> repo.apps.firstOrNull { it.label.equals(l, ignoreCase = true) } })
                ?.takeIf { it.key !in used }
        val top = rows - 6                                              // the 4×6 layout's row 0 on this grid
        fun fits(x: Int, y: Int, sx: Int = 1, sy: Int = 1) = y + top >= 0 && x + sx <= cols && y + top + sy <= rows

        val dock = listOfNotNull(
            app("com.google.android.dialer") ?: resolve(Intent(Intent.ACTION_DIAL)),
            app("com.google.android.apps.messaging") ?: Telephony.Sms.getDefaultSmsPackage(context)?.let { app(it) },
            app("com.google.android.youtube"),
            app("com.derek.arrudaledger"),
        ).distinctBy { it.key }
        dock.forEachIndexed { i, a -> items += HomeItem.App(newId(), a.key, Container.HOTSEAT, 0, i, 0); used += a.key }

        fun place(a: AppInfo?, x: Int, y: Int) {
            if (a == null || !fits(x, y)) return
            items += HomeItem.App(newId(), a.key, Container.WORKSPACE, 0, x, y + top); used += a.key
        }
        /** A folder of whichever of [apps] are installed: a lone app goes in its spot by itself. */
        fun folder(title: String, x: Int, y: Int, apps: List<() -> AppInfo?>) {
            if (!fits(x, y)) return
            val content = ArrayList<AppInfo>()
            for (find in apps) find()?.let { content += it; used += it.key }
            when (content.size) {
                0 -> return
                1 -> { used -= content[0].key; place(content[0], x, y) }
                else -> {
                    val f = HomeItem.Folder(newId(), title, Container.WORKSPACE, 0, x, y + top)
                    items += f
                    content.forEachIndexed { i, a -> items += HomeItem.App(newId(), a.key, Container.FOLDER, 0, 0, 0, f.id, i) }
                }
            }
        }

        folder("Google", 0, 4, listOf(
            { app("com.google.android.googlequicksearchbox") }, { app("com.google.android.apps.maps") },
            { app("com.google.android.apps.docs") }, { app("com.google.android.calendar") },
            { app("com.google.android.keep") }, { app("com.google.android.gm") }, { app("com.google.android.apps.bard") },
        ))
        folder("Social", 1, 4, listOf(
            { app("com.twitter.android") }, { app("com.instagram.barcelona") }, { app("com.facebook.katana") },
            { app("com.scopely.monopolygo") }, { app("com.zhiliaoapp.musically", "com.ss.android.ugc.trill") },
        ))
        place(app("com.t3tools.t3code", label = "T3 Code"), 2, 4)
        place(app("com.instagram.android"), 3, 4)
        place(app("com.waze"), 0, 5)
        place(app("com.android.chrome"), 1, 5)
        place(app("com.google.android.apps.photos"), 2, 5)
        place(app("com.google.android.apps.youtube.music"), 3, 5)

        defaultWidgets = listOf(
            DefaultWidget(0, 0, 4, 3, listOf("com.google.android.apps.weather", "com.google.android.googlequicksearchbox"), "weather"),
            DefaultWidget(0, 3, 2, 1, listOf("com.google.android.settings.intelligence"), "battery"),
            DefaultWidget(2, 3, 1, 1, listOf(context.packageName), "SplitWidget"),
            DefaultWidget(3, 3, 1, 1, listOf(context.packageName), "MultiBubbleWidget"),
        ).filter { fits(it.x, it.y, it.sx, it.sy) }.map { DefaultWidget(it.x, it.y + top, it.sx, it.sy, it.packages, it.classHint) }

        // Page 2: a few more apps so paging can be exercised.
        repo.apps.filter { it.key !in used }.take(cols * 2).forEachIndexed { i, a ->
            items += HomeItem.App(newId(), a.key, Container.WORKSPACE, 1, i % cols, i / cols)
        }
        save()
    }
}
