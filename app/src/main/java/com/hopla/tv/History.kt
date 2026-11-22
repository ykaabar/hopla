package com.hopla.tv

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Something the VOD player can play. [showId] links an episode back to its series. */
data class Playable(
    val url: String, val title: String, val subtitle: String, val image: String,
    val showId: String = "",
)

/**
 * What the household watched and kept: recent channels, favourites, where each film or episode was left.
 * Small, so it lives in SharedPreferences as JSON.
 *
 * Xtream stream URLs contain the username and password; everything saved here has them masked
 * ([mask]/[unmask]), so the only copy of the credentials is the encrypted link in [Vault].
 */
class History(private val prefs: SharedPreferences) {
    private var secret = ""

    private fun mask(url: String) = if (secret.isEmpty()) url else url.replace(secret, "/§/")
    private fun unmask(url: String) = if (secret.isEmpty()) url else url.replace("/§/", secret)

    /** History belongs to one playlist: another playlist's films and channels would not play. */
    fun bindTo(link: String, xtream: Xtream?) {
        secret = xtream?.secret.orEmpty()
        val id = Integer.toHexString(link.trim().hashCode())
        if (prefs.getString("historyFor", null) == id) return
        val e = prefs.edit().putString("historyFor", id)
        listOf("resume", "recentLive", "favorites", "last", "group").forEach { e.remove(it) }
        prefs.all.keys.filter { it.startsWith("pos:") || it.startsWith("dur:") }.forEach { e.remove(it) }
        e.apply()
    }

    // ---- films and episodes

    fun position(url: String) = prefs.getLong("pos:${mask(url)}", 0)

    /** Every started title: real (unmasked) url -> progress. */
    fun progressAll(): Map<String, Float> = prefs.all.keys.filter { it.startsWith("dur:") }.associate { k ->
        val url = unmask(k.removePrefix("dur:"))
        url to progress(url)
    }

    fun progress(url: String): Float {
        val dur = prefs.getLong("dur:${mask(url)}", 0)
        return if (dur > 0) (position(url).toFloat() / dur).coerceIn(0f, 1f) else 0f
    }

    /** Remembers where [p] stopped; nearly finished counts as watched and leaves "Reprendre". */
    fun save(p: Playable, pos: Long, dur: Long) {
        if (dur <= 0 || pos < 10_000) return
        if (pos > dur * 0.95) return finish(p)
        prefs.edit().putLong("pos:${mask(p.url)}", pos).putLong("dur:${mask(p.url)}", dur).apply()
        write(listOf(p) + resume().filter { it.url != p.url }.take(19))
    }

    fun finish(p: Playable) {
        prefs.edit().remove("pos:${mask(p.url)}").remove("dur:${mask(p.url)}").apply()
        write(resume().filter { it.url != p.url })
    }

    fun resume(): List<Playable> = objects("resume").map {
        Playable(unmask(it.optString("url")), it.optString("title"), it.optString("subtitle"), it.optString("image"), it.optString("showId"))
    }

    private fun write(items: List<Playable>) {
        val a = JSONArray()
        items.forEach {
            a.put(JSONObject().put("url", mask(it.url)).put("title", it.title).put("subtitle", it.subtitle).put("image", it.image).put("showId", it.showId))
        }
        prefs.edit().putString("resume", a.toString()).apply()
    }

    // ---- live TV

    fun recentChannels(): List<String> = strings("recentLive").map(::unmask)

    fun addChannel(url: String) {
        putStrings("recentLive", (listOf(url) + recentChannels().filter { it != url }.take(11)).map(::mask))
        prefs.edit().putString("last", mask(url)).apply()
    }

    val lastChannel get() = prefs.getString("last", null)?.let(::unmask)
    var lastGroup: String?
        get() = prefs.getString("group", null)
        set(v) = prefs.edit().putString("group", v).apply()

    // ---- favourites: "c:<channel url>", "m:<film id>", "s:<series id>"

    fun favorites(): List<String> = strings("favorites").map { if (it.startsWith("c:")) "c:" + unmask(it.removePrefix("c:")) else it }

    fun isFavorite(key: String) = key in favorites()

    /** Adds or removes; returns true when [key] is now a favourite. */
    fun toggleFavorite(key: String): Boolean {
        val now = favorites()
        val added = key !in now
        val list = if (added) listOf(key) + now else now - key
        putStrings("favorites", list.map { if (it.startsWith("c:")) "c:" + mask(it.removePrefix("c:")) else it })
        return added
    }

    // ---- storage helpers

    private fun strings(key: String): List<String> = prefs.getString(key, null)?.let { s ->
        runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull()
    }.orEmpty()

    private fun putStrings(key: String, list: List<String>) = prefs.edit().putString(key, JSONArray(list).toString()).apply()

    private fun objects(key: String): List<JSONObject> = prefs.getString(key, null)?.let { s ->
        runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }.getOrNull()
    }.orEmpty()
}
