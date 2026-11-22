package com.hopla.tv

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory SharedPreferences: Android's is a stub in local unit tests. */
private class MemoryPrefs : SharedPreferences {
    val map = HashMap<String, Any?>()
    override fun getAll(): Map<String, *> = map
    override fun getString(k: String, d: String?) = map[k] as String? ?: d
    override fun getStringSet(k: String, d: MutableSet<String>?) = d
    override fun getInt(k: String, d: Int) = map[k] as Int? ?: d
    override fun getLong(k: String, d: Long) = map[k] as Long? ?: d
    override fun getFloat(k: String, d: Float) = map[k] as Float? ?: d
    override fun getBoolean(k: String, d: Boolean) = map[k] as Boolean? ?: d
    override fun contains(k: String) = k in map
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(k: String, v: String?) = apply { map[k] = v }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { map[k] = v }
        override fun putInt(k: String, v: Int) = apply { map[k] = v }
        override fun putLong(k: String, v: Long) = apply { map[k] = v }
        override fun putFloat(k: String, v: Float) = apply { map[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { map[k] = v }
        override fun remove(k: String) = apply { map.remove(k) }
        override fun clear() = apply { map.clear() }
        override fun commit() = true
        override fun apply() = Unit
    }
}

class HistoryTest {
    private val x = Xtream("http://host", "bob", "s3cret")

    @Test fun credentialsNeverStoredInClear() {
        val prefs = MemoryPrefs()
        val h = History(prefs).apply { bindTo("http://host/get.php?username=bob&password=s3cret", x) }
        val channel = x.live(12)
        val film = Playable(x.movie(7, "mp4"), "Dune", "2021", "")
        h.addChannel(channel)
        h.toggleFavorite("c:$channel")
        h.save(film, 60_000, 600_000)

        // Nothing on disk holds the password, in values or in keys...
        prefs.map.forEach { (k, v) -> assertFalse("$k = $v", "s3cret" in k || "s3cret" in v.toString()) }
        // ...and reading back gives the full, playable addresses.
        assertEquals(channel, h.lastChannel)
        assertTrue(h.isFavorite("c:$channel"))
        assertEquals(film.url, h.resume().single().url)
        assertEquals(60_000L, h.position(film.url))
    }
}

class CatalogMaskTest {
    @Test fun credentialsMaskedOnDisk() {
        val x = Xtream("http://host", "bob", "s3cret")
        val c = Catalog(listOf(Channel("TF1", "FR", x.live(1))), listOf(Movie("7", "Dune", "", "", x.movie(7, "mp4"), 0, "")))
        val out = java.io.ByteArrayOutputStream()
        writeCatalog(c, out, x.secret)
        assertFalse("s3cret" in String(out.toByteArray(), Charsets.UTF_8))
        assertEquals(c.live, readCatalog(java.io.ByteArrayInputStream(out.toByteArray()), x.secret)!!.live)
        assertEquals(c.movies, readCatalog(java.io.ByteArrayInputStream(out.toByteArray()), x.secret)!!.movies)
    }
}
