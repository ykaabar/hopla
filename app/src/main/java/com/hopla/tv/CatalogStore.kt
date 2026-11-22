package com.hopla.tv

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Compact binary copy of a parsed [Catalog]. Re-parsing 20+ MB of provider JSON took about 3.5 s at every
 * start on a Fire TV with a large list; reading this back is a straight sequence of strings.
 */
private const val VERSION = 2 // 2: credentials masked in stream addresses (a version-1 file held them in clear)
private const val MASK = "/§/"

private fun DataOutputStream.str(s: String) {
    val b = s.toByteArray(Charsets.UTF_8)
    writeInt(b.size)
    write(b)
}

private fun DataInputStream.str(): String {
    val b = ByteArray(readInt())
    readFully(b)
    return String(b, Charsets.UTF_8)
}

/** [secret] is the account's "/user/pass/" as it appears in stream addresses; it is never written. */
fun writeCatalog(c: Catalog, out: OutputStream, secret: String = "") {
    fun hide(url: String) = if (secret.isEmpty()) url else url.replace(secret, MASK)
    DataOutputStream(out.buffered(1 shl 16)).run {
        writeInt(VERSION)
        str(hide(c.guide))
        writeInt(c.live.size)
        c.live.forEach { str(it.name); str(it.group); str(hide(it.url)); str(it.logo); str(it.epgId) }
        writeInt(c.movies.size)
        c.movies.forEach { str(it.id); str(it.name); str(it.group); str(it.poster); str(hide(it.url)); writeLong(it.added); str(it.rating); writeInt(it.year) }
        writeInt(c.shows.size)
        c.shows.forEach {
            str(it.id); str(it.name); str(it.group); str(it.cover); str(it.plot); str(it.rating); writeLong(it.added)
            writeInt(it.year); str(it.backdrop); str(it.genre)
        }
        flush()
    }
}

/** Null when the file is from another format version (it is then simply rebuilt from the JSON). */
fun readCatalog(input: InputStream, secret: String = ""): Catalog? = DataInputStream(input.buffered(1 shl 16)).run {
    fun show(url: String) = if (secret.isEmpty()) url else url.replace(MASK, secret)
    if (readInt() != VERSION) return null
    val guide = show(str())
    val live = List(readInt()) { Channel(str(), str(), show(str()), str(), str()) }
    val movies = List(readInt()) { Movie(str(), str(), str(), str(), show(str()), readLong(), str(), readInt()) }
    val shows = List(readInt()) { Show(str(), str(), str(), str(), str(), str(), readLong(), readInt(), str(), str()) }
    Catalog(live, movies, shows, guide)
}
