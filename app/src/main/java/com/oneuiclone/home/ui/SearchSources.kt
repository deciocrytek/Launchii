package com.oneuiclone.home.ui

import android.Manifest
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import com.oneuiclone.home.model.AppInfo
import com.oneuiclone.home.model.AppKey
import com.oneuiclone.home.model.AppUsage
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Off-main-thread sources for search, alive as long as the launcher: what to suggest before anything is typed
 * (app usage scores and contacts) and the contacts matching a query (READ_CONTACTS).
 *
 * Suggestions are a [Snapshot] worked out in the background and only replaced while search isn't on screen
 * ([refresh] from onStop, at most every [REFRESH_MS]); each search takes the snapshot as it opens, so nothing
 * reshuffles in front of the user. The snapshot is saved, so a restarted launcher shows the same thing.
 */
class SearchSources(
    private val context: Context,
    private val usage: AppUsage,
    private val apps: () -> List<AppInfo>,
) {
    class Contact(val id: Long, val uri: Uri, val name: String, val photo: Bitmap?)

    class Snapshot(val scores: Map<AppKey, Double>, val contacts: List<Contact>)

    /**
     * What search learned from the apps opened in it (0.230, as Pixel's search does), taken as a search opens so
     * nothing moves while it's up: per typed query, the app opened after it most ([top]), and how much each app is
     * opened from search at all ([use]). Both fade with a [PICK_HALF_LIFE_DAYS] half-life.
     */
    class Picks(private val top: Map<String, AppKey>, private val use: Map<AppKey, Double>, private val recent: Set<AppKey>) {
        /** The app picked most after typing [q] (normalised), once it has [PICK_MIN]. */
        fun top(q: String): AppKey? = top[q]
        /** Search opens of [k], faded (whole opens only, so one old open doesn't count). */
        fun use(k: AppKey): Int = (use[k] ?: 0.0).toInt()
        /** Opened (from anywhere) in the last few minutes. */
        fun recent(k: AppKey) = k in recent
    }

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "search-sources").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val opened = context.getSharedPreferences("search_contacts", Context.MODE_PRIVATE)
    private val saved = context.getSharedPreferences("search_suggestions", Context.MODE_PRIVATE)
    private var refreshing = false
    private val pickPrefs = context.getSharedPreferences("search_picks", Context.MODE_PRIVATE)
    /** query → app → (score, when); app → (score, when). Main thread, after [init] loads them. */
    private var byQuery = HashMap<String, HashMap<String, DoubleArray>>()
    private var searchUse = HashMap<String, DoubleArray>()

    /** What a search opening now shows. Main thread. */
    var snapshot = Snapshot(emptyMap(), emptyList()); private set

    val contactsAllowed get() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    init {
        worker.execute {
            val s = restore()
            val q = loadPicks()
            main.post {
                if (snapshot.scores.isEmpty() && snapshot.contacts.isEmpty()) snapshot = s
                if (byQuery.isEmpty() && searchUse.isEmpty()) { byQuery = q.first; searchUse = q.second }
            }
        }
    }

    /** What a search opening now learned from earlier ones. Main thread. */
    fun picks(): Picks {
        val now = System.currentTimeMillis()
        val top = HashMap<String, AppKey>()
        for ((q, apps) in byQuery) {
            val best = apps.maxByOrNull { decayed(it.value, now) } ?: continue
            if (decayed(best.value, now) >= PICK_MIN) AppKey.parse(best.key)?.let { top[q] = it }
        }
        val use = HashMap<AppKey, Double>()
        for ((k, v) in searchUse) AppKey.parse(k)?.let { use[it] = decayed(v, now) }
        val recent = apps().mapNotNullTo(HashSet()) { a -> a.key.takeIf { now - usage.lastLaunched(it) < RECENT_MS } }
        return Picks(top, use, recent)
    }

    /**
     * [key] was opened from search after typing [query] (normalised; empty: from the suggestions). The query counts
     * fully, the shorter ones typed on the way to it half ("spo" → Spotify also teaches "s" and "sp", a little).
     */
    fun picked(query: String, key: AppKey) {
        val now = System.currentTimeMillis()
        val k = key.flatten()
        fun add(map: HashMap<String, DoubleArray>, id: String, w: Double) {
            val old = map[id]
            map[id] = doubleArrayOf((old?.let { decayed(it, now) } ?: 0.0) + w, now.toDouble())
        }
        add(searchUse, k, 1.0)
        val q = query.take(MAX_PICK_QUERY)
        for (n in 1..q.length) {
            val prefix = q.substring(0, n).trimEnd()
            if (prefix.isEmpty()) continue
            add(byQuery.getOrPut(prefix) { HashMap() }, k, if (n == q.length) 1.0 else 0.5)
        }
        prunePicks(now)
        savePicks()
    }

    private fun decayed(v: DoubleArray, now: Long) =
        v[0] * Math.pow(0.5, (now - v[1]).coerceAtLeast(0.0) / (PICK_HALF_LIFE_DAYS * 86_400_000.0))

    /** At most [MAX_PICK_QUERIES] queries (the faintest go) and [MAX_PICKS_PER_QUERY] apps each. */
    private fun prunePicks(now: Long) {
        for (apps in byQuery.values) if (apps.size > MAX_PICKS_PER_QUERY) {
            apps.entries.sortedBy { decayed(it.value, now) }.take(apps.size - MAX_PICKS_PER_QUERY).forEach { apps.remove(it.key) }
        }
        if (byQuery.size > MAX_PICK_QUERIES) {
            byQuery.entries.sortedBy { e -> e.value.values.maxOfOrNull { decayed(it, now) } ?: 0.0 }
                .take(byQuery.size - MAX_PICK_QUERIES).map { it.key }.forEach { byQuery.remove(it) }
        }
    }

    private fun savePicks() {
        fun enc(m: Map<String, DoubleArray>) = JSONObject().apply { for ((k, v) in m) put(k, JSONArray().put(v[0]).put(v[1])) }
        val q = JSONObject()
        for ((query, apps) in byQuery) q.put(query, enc(apps))
        pickPrefs.edit().putString(KEY_PICKS, q.toString()).putString(KEY_USE, enc(searchUse).toString()).apply()
    }

    private fun loadPicks(): Pair<HashMap<String, HashMap<String, DoubleArray>>, HashMap<String, DoubleArray>> = try {
        fun dec(o: JSONObject) = HashMap<String, DoubleArray>().apply {
            for (k in o.keys()) o.optJSONArray(k)?.let { put(k, doubleArrayOf(it.getDouble(0), it.getDouble(1))) }
        }
        val q = HashMap<String, HashMap<String, DoubleArray>>()
        pickPrefs.getString(KEY_PICKS, null)?.let { JSONObject(it) }?.let { o -> for (k in o.keys()) o.optJSONObject(k)?.let { q[k] = dec(it) } }
        val use = pickPrefs.getString(KEY_USE, null)?.let { dec(JSONObject(it)) } ?: HashMap()
        q to use
    } catch (_: Exception) {
        HashMap<String, HashMap<String, DoubleArray>>() to HashMap()
    }

    /**
     * Works the suggestions out again when the last time was over [REFRESH_MS] ago ([force]: now), then [done].
     * Call it while search is closed; an open search keeps the snapshot it opened with either way.
     */
    fun refresh(force: Boolean = false, done: (() -> Unit)? = null) {
        if (refreshing || (!force && System.currentTimeMillis() - saved.getLong(KEY_AT, 0L) < REFRESH_MS)) return
        val list = apps()
        if (list.isEmpty()) return                                  // not loaded yet: try again next time
        refreshing = true
        worker.execute {
            val s = Snapshot(usage.scores(list), if (contactsAllowed) suggestedContacts(MAX_SUGGESTED) else emptyList())
            save(s)
            main.post { snapshot = s; refreshing = false; done?.invoke() }
        }
    }

    /** Contacts whose name matches [q] (the provider folds accents itself). */
    fun query(q: String, done: (List<Contact>) -> Unit) {
        worker.execute {
            val ct = if (contactsAllowed) matching(q) else emptyList()
            main.post { done(ct) }
        }
    }

    /** A contact opened from search moves up the suggestions (from the next refresh on). */
    fun contactOpened(c: Contact) {
        val k = c.uri.toString()
        opened.edit().putInt(k, opened.getInt(k, 0) + 1).apply()
    }

    private fun save(s: Snapshot) {
        val scores = JSONObject()
        for ((k, v) in s.scores) if (v > 0.0) scores.put(k.flatten(), v)
        saved.edit()
            .putString(KEY_SCORES, scores.toString())
            .putString(KEY_CONTACTS, JSONArray(s.contacts.map { it.uri.toString() }).toString())
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    private fun restore(): Snapshot = try {
        val scores = HashMap<AppKey, Double>()
        saved.getString(KEY_SCORES, null)?.let { JSONObject(it) }?.let { o ->
            for (k in o.keys()) AppKey.parse(k)?.let { scores[it] = o.getDouble(k) }
        }
        val uris = saved.getString(KEY_CONTACTS, null)?.let { JSONArray(it) }
        val contacts = if (uris == null || !contactsAllowed) emptyList()
            else (0 until uris.length()).flatMap { read(Uri.parse(uris.getString(it)), null, null, 1) }
        Snapshot(scores, contacts)
    } catch (_: Exception) {
        Snapshot(emptyMap(), emptyList())
    }

    private val cols = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY,
        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)

    /** Names starting with [q] before ones where a later word does ("m": Mom before Jake Morales). */
    private fun matching(q: String): List<Contact> =
        read(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q)), null,
            "${ContactsContract.Contacts.STARRED} DESC, ${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC", 50)
            .sortedBy { if (it.name.startsWith(q, ignoreCase = true)) 0 else 1 }.take(MAX_MATCHES)

    /**
     * Most likely to be wanted first: contacts opened from search before, starred ones, then people from recent
     * conversations (messaging apps' conversation shortcuts), then whoever was contacted or edited most recently.
     */
    private fun suggestedContacts(max: Int): List<Contact> {
        val out = LinkedHashMap<Long, Contact>()
        fun add(list: List<Contact>) { for (c in list) if (out.size < max) out.putIfAbsent(c.id, c) }
        opened.all.entries.sortedByDescending { it.value as? Int ?: 0 }.forEach { (uri, _) ->
            if (out.size < max) add(read(Uri.parse(uri), null, null, 1))
        }
        if (out.size < max) add(read(ContactsContract.Contacts.CONTENT_URI, "${ContactsContract.Contacts.STARRED}=1",
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC", max))
        for (name in conversationNames()) {
            if (out.size >= max) break
            add(read(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(name)), null, null, 3)
                .filter { it.name.equals(name, ignoreCase = true) }.take(1))
        }
        if (out.size < max) add(read(ContactsContract.Contacts.CONTENT_URI, "${ContactsContract.Contacts.HAS_PHONE_NUMBER}=1",
            "${ContactsContract.Contacts.LAST_TIME_CONTACTED} DESC, ${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} DESC",
            max + out.size))
        return out.values.toList()
    }

    /** Titles of conversation shortcuts (one per chat), most recently active first. */
    private fun conversationNames(): List<String> = try {
        if (!launcherApps.hasShortcutHostPermission()) emptyList() else {
            val q = LauncherApps.ShortcutQuery().setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED)
            launcherApps.profiles.flatMap { launcherApps.getShortcuts(q, it) ?: emptyList() }
                .filter { it.categories?.contains(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION) == true }
                .sortedByDescending { it.lastChangedTimestamp }
                .mapNotNull { it.shortLabel?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                .distinct()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun read(uri: Uri, selection: String?, order: String?, limit: Int): List<Contact> = try {
        context.contentResolver.query(uri, cols, selection, null, order)?.use { c ->
            val out = ArrayList<Contact>()
            while (c.moveToNext() && out.size < limit) contact(c)?.let { out += it }
            out
        } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun contact(c: Cursor): Contact? {
        val name = c.getString(2) ?: return null
        val photo = c.getString(3)?.let { p ->
            try { context.contentResolver.openInputStream(Uri.parse(p))?.use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
        }
        return Contact(c.getLong(0), ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1)), name, photo)
    }

    companion object {
        /** Suggested contacts before anything is typed (three rows of two). */
        const val MAX_SUGGESTED = 6
        /** Matching contacts while typing (two rows of two). */
        const val MAX_MATCHES = 4
        private const val REFRESH_MS = 6 * 60 * 60 * 1000L
        private const val KEY_SCORES = "scores"
        private const val KEY_CONTACTS = "contacts"
        private const val KEY_AT = "at"
        private const val KEY_PICKS = "picks"
        private const val KEY_USE = "use"
        /**
         * A query's top pick moves to the front once it has this much: one open right after typing exactly that query
         * (it holds for about two days, then needs another), or two opens on the way to longer ones (prefixes count half).
         */
        private const val PICK_MIN = 0.9
        private const val PICK_HALF_LIFE_DAYS = 14.0
        private const val MAX_PICK_QUERY = 16
        private const val MAX_PICK_QUERIES = 400
        private const val MAX_PICKS_PER_QUERY = 5
        /** "Just used": opened this recently gets a small lift in typed results. */
        private const val RECENT_MS = 10 * 60 * 1000L
    }
}
