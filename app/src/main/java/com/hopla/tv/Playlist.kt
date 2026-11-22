package com.hopla.tv

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

const val USER_AGENT = "Hopla/0.2"

data class Channel(val name: String, val group: String, val url: String, val logo: String = "", val epgId: String = "")
data class Movie(val id: String, val name: String, val group: String, val poster: String, val url: String, val added: Long, val rating: String, val year: Int = 0) {
    /** [name] as the provider wrote it stays the stored key; screens show [title] and [tags]. */
    // Computed when shown, never stored: two lazy holders per title cost 13 MB with 161 000 films.
    val title get() = cleanTitle(name)
    val tags get() = titleTags(name)
}
data class Show(
    val id: String, val name: String, val group: String, val cover: String, val plot: String, val rating: String, val added: Long,
    val year: Int = 0, val backdrop: String = "", val genre: String = "",
) {
    val title get() = cleanTitle(name)
    val tags get() = titleTags(name)
}
data class Episode(val id: String, val season: Int, val number: Int, val title: String, val plot: String, val image: String, val url: String)
data class MovieInfo(
    val plot: String, val genre: String, val year: String, val duration: String, val backdrop: String,
    val cast: String = "", val director: String = "", val trailer: String = "",
)
data class ShowInfo(val episodes: List<Episode>, val backdrop: String, val cast: String = "", val director: String = "", val trailer: String = "")

/** Everything one playlist offers. Films and series only exist for Xtream accounts. */
data class Catalog(
    val live: List<Channel>, val movies: List<Movie> = emptyList(), val shows: List<Show> = emptyList(),
    /** XMLTV guide address: the panel's xmltv.php, or the playlist's url-tvg header. */
    val guide: String = "",
)

/** An Xtream Codes account: server address plus credentials (public player_api.php protocol). */
data class Xtream(val base: String, val user: String, val pass: String) {
    fun api(action: String? = null, extra: String = "") =
        "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}" + (action?.let { "&action=$it" } ?: "") + extra
    private fun path(kind: String, id: Any?, ext: String) = "$base/$kind/${enc(user)}/${enc(pass)}/$id.$ext"
    fun live(id: Any?, ext: String = "ts") = path("live", id, ext)
    fun xmltv() = "$base/xmltv.php?username=${enc(user)}&password=${enc(pass)}"
    /** The credentials as they appear inside stream URLs; History masks them before saving anything. */
    val secret get() = "/${enc(user)}/${enc(pass)}/"
    fun movie(id: Any?, ext: String) = path("movie", id, ext)
    fun episode(id: Any?, ext: String) = path("series", id, ext)
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/** Xtream account if the link carries username + password (get.php / player_api.php), else null = plain M3U. */
fun parseXtream(link: String): Xtream? {
    val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https") || uri.rawAuthority == null) return null
    val q = uri.rawQuery.orEmpty().split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }
    val user = q["username"] ?: return null
    val pass = q["password"] ?: return null
    // Keep any folder before get.php, e.g. http://host/iptv/get.php -> http://host/iptv
    return Xtream("${uri.scheme}://${uri.rawAuthority}${uri.rawPath.orEmpty().substringBeforeLast('/', "")}", user, pass)
}

// ---------------------------------------------------------------- M3U

/** Streaming M3U parser: one pass over the lines, never holds the raw file in memory. */
fun parseM3u(lines: Sequence<String>): List<Channel> {
    val out = ArrayList<Channel>()
    var name = ""
    var group = ""
    var logo = ""
    var id = ""
    for (raw in lines) {
        val line = raw.trim()
        if (line.startsWith("#EXTINF")) {
            name = titleOf(line)
            group = GROUP.find(line)?.groupValues?.get(1).orEmpty()
            logo = LOGO.find(line)?.groupValues?.get(1).orEmpty()
            id = TVG_ID.find(line)?.groupValues?.get(1).orEmpty()
        } else if (line.isNotEmpty() && !line.startsWith("#")) {
            if (!isSeparator(name)) out += Channel(name.ifEmpty { line }, group, line, logo, guideId(id))
            name = ""; group = ""; logo = ""; id = ""
        }
    }
    return out
}

private val GROUP = Regex("""group-title="([^"]*)"""")
private val LOGO = Regex("""tvg-logo="([^"]*)"""")
private val TVG_ID = Regex("""tvg-id="([^"]*)"""")

/** Providers list headers such as "###### FORMULA 1 ######" as channels; they play nothing. */
fun isSeparator(name: String) = name.trim().let { it.length > 2 && it.startsWith("#") && it.endsWith("#") }

/** Guide ids are matched loosely: "6ter.fr@SD" in a playlist is "6ter.fr" in most guides. */
fun guideId(id: String) = id.substringBefore('@').trim().lowercase()

/** The guide address a playlist may declare in its header (url-tvg / x-tvg-url, first one if several). */
fun m3uGuideUrl(header: String): String =
    Regex("""(?:url-tvg|x-tvg-url)="([^"]+)"""").find(header)?.groupValues?.get(1)?.split(',')?.firstOrNull()?.trim().orEmpty()

/** Title = text after the first comma that is outside quoted attributes. */
private fun titleOf(extinf: String): String {
    var quoted = false
    extinf.forEachIndexed { i, c ->
        if (c == '"') quoted = !quoted
        else if (c == ',' && !quoted) return extinf.substring(i + 1).trim()
    }
    return ""
}

// ---------------------------------------------------------------- Xtream JSON

private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key).trim()

private fun readValue(r: JsonReader): Any? = when (r.peek()) {
    JsonToken.BEGIN_OBJECT -> JSONObject().also { o ->
        r.beginObject()
        while (r.hasNext()) o.put(r.nextName(), readValue(r) ?: JSONObject.NULL)
        r.endObject()
    }
    JsonToken.BEGIN_ARRAY -> JSONArray().also { a ->
        r.beginArray()
        while (r.hasNext()) a.put(readValue(r))
        r.endArray()
    }
    JsonToken.BOOLEAN -> r.nextBoolean()
    JsonToken.NULL -> { r.nextNull(); null }
    else -> r.nextString() // strings and numbers alike: the mapping below reads everything as text
}

/**
 * Streams the objects of a top-level array (or, as some panels send, of an object keyed by id) one at a time.
 * A large provider's film list is 40+ MB of JSON: reading it whole needed a 134 MB block and failed on a Fire TV.
 */
private fun <T> mapObjects(reader: Reader, map: (JSONObject) -> T): List<T> {
    val out = ArrayList<T>()
    JsonReader(reader).use { r ->
        r.isLenient = true
        when (r.peek()) {
            JsonToken.BEGIN_ARRAY -> { r.beginArray(); while (r.hasNext()) (readValue(r) as? JSONObject)?.let { out += map(it) }; r.endArray() }
            JsonToken.BEGIN_OBJECT -> { r.beginObject(); while (r.hasNext()) { r.nextName(); (readValue(r) as? JSONObject)?.let { out += map(it) } }; r.endObject() }
            else -> Unit
        }
    }
    return out
}

fun parseCategories(json: String): Map<String, String> = parseCategories(json.reader())
fun parseCategories(reader: Reader): Map<String, String> =
    mapObjects(reader) { it.str("category_id") to it.str("category_name") }.toMap()

/** .ts unless the account only allows HLS. */
fun liveExtension(userInfo: JSONObject?): String {
    val formats = userInfo?.optJSONArray("allowed_output_formats") ?: return "ts"
    val list = (0 until formats.length()).map { formats.optString(it) }
    return if ("ts" in list || list.isEmpty()) "ts" else if ("m3u8" in list) "m3u8" else list.first()
}

fun parseLive(json: Reader, cats: Map<String, String>, x: Xtream, ext: String): List<Channel> {
    // SD / HD / FHD variants repeat the same logo and guide id: keep one copy of each (57 000 channels add up).
    val pool = HashMap<String, String>()
    fun shared(s: String) = pool.getOrPut(s) { s }
    return mapObjects(json) {
        Channel(it.str("name"), cats[it.str("category_id")].orEmpty(), x.live(it.str("stream_id"), ext), shared(it.str("stream_icon")), shared(guideId(it.str("epg_channel_id"))))
    }.filterNot { isSeparator(it.name) }
}

private val YEAR = Regex("""(?:19|20)\d{2}""")
private val YEAR_AT_END = Regex("""[(\[ -]((?:19|20)\d{2})[)\]]?\s*$""")

/** Release year from whichever field the panel fills, else from a "Title (2019)" style name; 0 when unknown. */
fun yearOf(o: JSONObject, name: String): Int {
    for (k in listOf("year", "releaseDate", "release_date", "releasedate")) {
        YEAR.find(o.str(k))?.let { return it.value.toInt() }
    }
    return YEAR_AT_END.find(name)?.groupValues?.get(1)?.toInt() ?: 0
}

/** Rating out of 10 as text ("7.4"); panels send "rating" or only "rating_5based". */
private fun ratingOf(o: JSONObject): String {
    val r = o.str("rating").replace(',', '.').toDoubleOrNull()
    if (r != null && r > 0) return r.toString()
    return o.str("rating_5based").replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 2).toString() }.orEmpty()
}

fun parseMovies(json: String, cats: Map<String, String>, x: Xtream) = parseMovies(json.reader(), cats, x)
fun parseMovies(json: Reader, cats: Map<String, String>, x: Xtream) = mapObjects(json) {
    val id = it.str("stream_id")
    val name = it.str("name")
    Movie(id, name, cats[it.str("category_id")].orEmpty(), it.str("stream_icon"),
        x.movie(id, it.str("container_extension").ifEmpty { "mp4" }), it.str("added").toLongOrNull() ?: 0, ratingOf(it), yearOf(it, name))
}

fun parseShows(json: Reader, cats: Map<String, String>) = mapObjects(json) {
    val name = it.str("name")
    Show(it.str("series_id"), name, cats[it.str("category_id")].orEmpty(), it.str("cover"), it.str("plot"),
        ratingOf(it), (it.str("last_modified").ifEmpty { it.str("added") }).toLongOrNull() ?: 0,
        yearOf(it, name), firstBackdrop(it), it.str("genre"))
}

private fun firstBackdrop(info: JSONObject?): String =
    info?.optJSONArray("backdrop_path")?.optString(0).orEmpty().ifEmpty { info?.str("backdrop_path").orEmpty() }
        .takeIf { it.startsWith("http") }.orEmpty()

fun parseMovieInfo(json: String): MovieInfo {
    val info = JSONObject(json).optJSONObject("info")
    return MovieInfo(
        info?.str("plot").orEmpty().ifEmpty { info?.str("description").orEmpty() },
        info?.str("genre").orEmpty(),
        info?.str("releasedate").orEmpty().ifEmpty { info?.str("release_date").orEmpty() }.take(4),
        info?.str("duration").orEmpty(),
        firstBackdrop(info).ifEmpty { info?.str("movie_image").orEmpty() },
        castOf(info), info?.str("director").orEmpty(), trailerUrl(info),
    )
}

/** Actors as one readable line; panels fill "cast" or "actors". */
private fun castOf(info: JSONObject?): String =
    info?.str("cast").orEmpty().ifEmpty { info?.str("actors").orEmpty() }.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

private val YOUTUBE_ID = Regex("""[A-Za-z0-9_-]{11}""")

/** Trailer address: panels give a YouTube id ("dQw4w9WgXcQ"), a full link, or nothing. */
fun trailerUrl(info: JSONObject?): String {
    val t = info?.str("youtube_trailer").orEmpty().ifEmpty { info?.str("trailer").orEmpty() }
    return when {
        t.startsWith("http") -> t
        YOUTUBE_ID.matches(t) -> "https://www.youtube.com/watch?v=$t"
        else -> ""
    }
}

// Release tags providers put in front of titles: "VOSTFR/HDCAM - Titre", "[FR] Titre", "|4K| Titre", "FR: Titre".
private const val TAG = """(?:VOSTFR|VOSTA|VOST|TRUEFRENCH|FRENCH|MULTI|VFF|VFQ|VFI|VF|VO|FR|EN|ENG|HDCAM|CAM|HDTS|TS|HDRIP|WEBRIP|WEB|SD|HD|FHD|UHD|4K|HDR|3D)"""
private val TAGS_PREFIX = Regex("""^\s*(?:[\[|(]\s*$TAG(?:\s*[/ +]\s*$TAG)*\s*[\]|)]\s*[-:|]?\s*|$TAG(?:\s*[/ +]\s*$TAG)*\s*[-:|]\s*)+""", RegexOption.IGNORE_CASE)
private val TAGS_SUFFIX = Regex("""\s*[\[(|]\s*$TAG(?:\s*[/ +]\s*$TAG)*\s*[\])|]\s*$""", RegexOption.IGNORE_CASE)
private val YEAR_SUFFIX = Regex("""\s*[-(\[]\s*(?:19|20)\d{2}\s*[)\]]?\s*$""")

/** "VOSTFR/HDCAM - Comment braquer une banque (2026)" -> "Comment braquer une banque": the year and tags have their own place. */
fun cleanTitle(raw: String): String {
    val t = raw.replace(TAGS_PREFIX, "").replace(TAGS_SUFFIX, "").replace(YEAR_SUFFIX, "").trim()
    return t.ifEmpty { raw.trim() }
}

/** The release tags removed by [cleanTitle], for the info line: "VOSTFR · HDCAM". */
fun titleTags(raw: String): String = (TAGS_PREFIX.find(raw)?.value.orEmpty() + " " + TAGS_SUFFIX.find(raw)?.value.orEmpty())
    .split('[', ']', '|', '(', ')', '/', '+', '-', ':', ' ').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct().joinToString(" · ")

private val EPISODE_PREFIX = Regex("""^.*?\bS\d{1,2}\s?E\d{1,3}\b\s*[-–:·|]*\s*""", RegexOption.IGNORE_CASE)

/** "Les Thunderman - S03E01 - Le titre" -> "Le titre": the card already says which season and episode it is. */
fun cleanEpisodeTitle(title: String): String = title.replace(EPISODE_PREFIX, "").trim()

fun parseShowInfo(json: String, x: Xtream): ShowInfo {
    val root = JSONObject(json)
    val raw = root.opt("episodes")
    // Usually {"1": [...], "2": [...]}, sometimes a plain array of season arrays.
    val seasons: List<JSONArray> = when (raw) {
        is JSONObject -> raw.keys().asSequence().mapNotNull { raw.optJSONArray(it) }.toList()
        is JSONArray -> (0 until raw.length()).mapNotNull { raw.optJSONArray(it) }.ifEmpty { listOf(raw) }
        else -> emptyList()
    }
    val episodes = seasons.flatMap { it.objects() }.map {
        val info = it.optJSONObject("info")
        val id = it.str("id")
        Episode(id, it.str("season").toIntOrNull() ?: 1, it.str("episode_num").toIntOrNull() ?: 0,
            cleanEpisodeTitle(it.str("title")), info?.str("plot").orEmpty(), info?.str("movie_image").orEmpty(),
            x.episode(id, it.str("container_extension").ifEmpty { "mp4" }))
    }.sortedWith(compareBy({ it.season }, { it.number }))
    val info = root.optJSONObject("info")
    return ShowInfo(episodes, firstBackdrop(info), castOf(info), info?.str("director").orEmpty(), trailerUrl(info))
}

// ---------------------------------------------------------------- TV guide (XMLTV)

data class Programme(val start: Long, val stop: Long, val title: String, val desc: String)

class Epg(private val byId: Map<String, List<Programme>>) {
    fun now(ch: Channel, t: Long) = byId[ch.epgId]?.firstOrNull { it.start <= t && t < it.stop }
    fun next(ch: Channel, t: Long) = byId[ch.epgId]?.firstOrNull { it.start > t }
    fun upcoming(ch: Channel, t: Long, n: Int = 3) = byId[ch.epgId].orEmpty().filter { it.start > t }.take(n)
    val size get() = byId.size
    companion object { val EMPTY = Epg(emptyMap()) }
}

/** XMLTV time: "20251121203000 +0100" (the offset is optional, UTC when missing). */
internal class XmltvClock {
    private val withZone = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val utc = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    fun parse(s: String?): Long {
        val t = s?.trim().orEmpty()
        if (t.length < 14) return 0
        val zone = t.substring(14).trim()
        return runCatching { if (zone.isEmpty()) utc.parse(t.substring(0, 14))!!.time else withZone.parse(t.substring(0, 14) + " " + zone)!!.time }
            .getOrDefault(0)
    }
}

/**
 * Streaming XMLTV parser. Guides of big providers weigh hundreds of MB and cover tens of thousands of
 * channels, while the app only ever shows "now" and "next": keep the channels we have ([wanted], all when
 * empty), 1 h back to 6 h ahead, at most [PER_CHANNEL] programmes each, short descriptions, and one copy of
 * each repeated text. Keeping 39 hours with full descriptions ran a Fire TV out of memory.
 */
private const val PER_CHANNEL = 6
private const val DESC_MAX = 240

fun parseXmltv(p: XmlPullParser, wanted: Set<String>, now: Long): Epg {
    val out = HashMap<String, ArrayList<Programme>>()
    val pool = HashMap<String, String>()
    fun shared(s: String) = pool.getOrPut(s) { s }
    val from = now - 3_600_000L
    val to = now + 6 * 3_600_000L
    val clock = XmltvClock()
    var ch: String? = null
    var start = 0L
    var stop = 0L
    val title = StringBuilder()
    val desc = StringBuilder()
    var field: StringBuilder? = null
    // Real guides are not always valid XML (a bare "&" in a title): accept what the parser can, and if it
    // still gives up halfway, keep everything read so far instead of losing the whole guide.
    runCatching { p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
    var ev = p.eventType
    try { while (ev != XmlPullParser.END_DOCUMENT) {
        when (ev) {
            XmlPullParser.START_TAG -> when (p.name) {
                "programme" -> {
                    ch = guideId(p.getAttributeValue(null, "channel").orEmpty())
                    start = clock.parse(p.getAttributeValue(null, "start"))
                    stop = clock.parse(p.getAttributeValue(null, "stop"))
                    title.clear(); desc.clear()
                    if ((wanted.isNotEmpty() && ch !in wanted) || stop < from || start > to) ch = null
                }
                // Several titles in different languages: the first one wins.
                "title" -> if (ch != null && title.isEmpty()) field = title
                "desc" -> if (ch != null && desc.isEmpty()) field = desc
            }
            XmlPullParser.TEXT -> field?.append(p.text)
            XmlPullParser.END_TAG -> when (p.name) {
                "title", "desc" -> field = null
                "programme" -> {
                    val c = ch
                    val list = if (c != null && title.isNotBlank() && stop > start) out.getOrPut(c) { ArrayList(PER_CHANNEL) } else null
                    if (list != null && list.size < PER_CHANNEL) {
                        list += Programme(start, stop, shared(title.trim().toString()), shared(desc.trim().take(DESC_MAX).toString()))
                    }
                    ch = null
                }
            }
        }
        ev = p.next()
    } } catch (e: org.xmlpull.v1.XmlPullParserException) { /* partial guide is better than none */ } catch (e: OutOfMemoryError) {
        out.clear() // the guide is optional: never take the app down for it
    }
    out.values.forEach { list -> list.sortBy { it.start } }
    return Epg(out)
}

// ---------------------------------------------------------------- network + disk cache

/**
 * Downloads go through a per-playlist folder. With fresh=false a cached copy is used without touching the
 * network, which is what makes startup instant; a fresh pass then runs in the background.
 */
class Source(val link: String, root: File) {
    val xtream = parseXtream(link)
    private val dir = File(root, "catalog/" + Integer.toHexString(link.trim().hashCode())).apply { mkdirs() }

    /** A playlist change makes the old cache useless; call once the new link has loaded, never before. */
    fun dropOtherCaches() {
        dir.parentFile?.listFiles()?.filter { it != dir }?.forEach { it.deleteRecursively() }
    }

    private val store get() = File(dir, "catalog.bin")
    /** How old the copy on the TV is (very old when there is none). */
    val cacheAge get() = System.currentTimeMillis() - store.lastModified()
    val hasCache get() = store.exists() || File(dir, if (xtream == null) "playlist.m3u" else "live.json").exists()

    /** [step] hears (bytes so far, total or -1) about every 512 KB, for the loading screen. */
    private fun fetch(url: String, name: String, fresh: Boolean, step: ((Long, Long) -> Unit)? = null): File {
        val f = File(dir, name)
        if (!fresh && f.exists()) return f
        val tmp = File(dir, "$name.tmp")
        val c = connect(url)
        val total = c.contentLengthLong
        c.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var next = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (step != null && done >= next) { step(done, total); next = done + 512 * 1024 }
                }
            }
        }
        if (!tmp.renameTo(f)) throw IOException("cache write failed")
        return f
    }

    private fun text(url: String, name: String, fresh: Boolean) = fetch(url, name, fresh).readText()
    /** A big list, downloaded between [from] and 80 % of the way to [to]; reading it takes the rest. */
    private fun reader(url: String, name: String, fresh: Boolean, from: Float, to: Float, what: String, progress: (Float, String) -> Unit): Reader {
        val f = fetch(url, name, fresh) { done, total ->
            val p = if (total > 0) from + (to - from) * 0.8f * done / total else from
            progress(p, "Téléchargement des $what…" + if (total > 0) "" else " ${done / 1_000_000} Mo")
        }
        progress(from + (to - from) * 0.8f, "Lecture des $what…")
        return f.bufferedReader()
    }

    /** Blocking: call off the main thread. */
    fun catalog(fresh: Boolean, progress: (Float, String) -> Unit = { _, _ -> }): Catalog {
        val secret = xtream?.secret.orEmpty()
        // The Xtream guide address carries the password as a query parameter: never stored, rebuilt from the link.
        fun Catalog.withGuide() = if (xtream != null) copy(guide = xtream.xmltv()) else this
        if (!fresh) runCatching { store.inputStream().use { readCatalog(it, secret) } }.getOrNull()?.let { return it.withGuide() }
        val parsed = parseCatalog(fresh, progress)
        // A failed films or series download must not wipe what we had: reuse the previous copy for that part.
        val previous = if (parsed.movies == null || parsed.shows == null) runCatching { store.inputStream().use { readCatalog(it, secret) } }.getOrNull() else null
        val c = Catalog(parsed.live, parsed.movies ?: previous?.movies.orEmpty(), parsed.shows ?: previous?.shows.orEmpty(), parsed.guide)
        // Keep the fast binary copy for the next start; a failed write only costs that speed-up.
        progress(0.98f, "Préparation…")
        runCatching {
            val tmp = File(dir, "catalog.bin.tmp")
            tmp.outputStream().use { writeCatalog(if (xtream != null) c.copy(guide = "") else c, it, secret) }
            tmp.renameTo(store)
        }
        return c
    }

    /** Like [Catalog], with null for a section that failed (as opposed to one that is really empty). */
    private class Parsed(val live: List<Channel>, val movies: List<Movie>?, val shows: List<Show>?, val guide: String)

    private fun parseCatalog(fresh: Boolean, progress: (Float, String) -> Unit): Parsed {
        val x = xtream ?: return m3uCatalog(fresh, progress).let { Parsed(it.live, it.movies, it.shows, it.guide) }
        progress(0.02f, "Connexion au serveur…")
        val accountFile = fetch(x.api(), "account.json", fresh)
        val account = JSONObject(accountFile.readText())
        val info = account.optJSONObject("user_info")
        // The panel echoes the password back; the cached copy doesn't need it.
        if (fresh && info?.has("password") == true) { info.remove("password"); accountFile.writeText(account.toString()) }
        if (info?.optString("auth") != "1") {
            File(dir, "account.json").delete()
            throw IOException("identifiants refusés par le serveur")
        }
        val live = parseLive(reader(x.api("get_live_streams"), "live.json", fresh, 0.05f, 0.30f, "chaînes", progress),
            parseCategories(text(x.api("get_live_categories"), "live_cats.json", fresh)), x, liveExtension(info))
        // Films and series are a bonus: a server that fails them must not block live TV.
        val movies = runCatching {
            parseMovies(reader(x.api("get_vod_streams"), "vod.json", fresh, 0.30f, 0.70f, "films", progress), parseCategories(text(x.api("get_vod_categories"), "vod_cats.json", fresh)), x)
        }.getOrNull()
        val shows = runCatching {
            parseShows(reader(x.api("get_series"), "series.json", fresh, 0.70f, 0.97f, "séries", progress), parseCategories(text(x.api("get_series_categories"), "series_cats.json", fresh)))
        }.getOrNull()
        return Parsed(live, movies, shows, x.xmltv())
    }

    private fun m3uCatalog(fresh: Boolean, progress: (Float, String) -> Unit = { _, _ -> }): Catalog {
        val f = fetch(link.trim(), "playlist.m3u", fresh) { done, total ->
            progress(if (total > 0) 0.9f * done / total else 0.05f, "Téléchargement de la playlist…" + if (total > 0) "" else " ${done / 1_000_000} Mo")
        }
        progress(0.92f, "Lecture de la playlist…")
        val header = f.bufferedReader().use { r -> generateSequence { r.readLine() }.firstOrNull { it.isNotBlank() } }.orEmpty().trimStart('\uFEFF').trim()
        // An expired domain or a login page answers with HTML; don't turn that into fake "channels".
        if (!header.startsWith("#EXTM3U") && !header.startsWith("#EXTINF")) {
            f.delete()
            throw IOException("ce lien ne renvoie pas une playlist M3U")
        }
        return Catalog(f.bufferedReader().useLines { parseM3u(it) }, guide = m3uGuideUrl(header))
    }

    /** Guide from cache, refreshed once a day (providers publish it daily); a failed refresh keeps the old copy. */
    fun guide(url: String, wanted: Set<String>, now: Long): Epg {
        if (url.isBlank()) return Epg.EMPTY
        val f = File(dir, "epg.xml")
        if (!f.exists() || now - f.lastModified() > 24 * 3_600_000L) runCatching { fetch(url, "epg.xml", true) }
        if (!f.exists()) return Epg.EMPTY
        return BufferedInputStream(f.inputStream()).use { raw ->
            raw.mark(2)
            val gz = raw.read() == 0x1f && raw.read() == 0x8b
            raw.reset()
            val input = if (gz) GZIPInputStream(raw) else raw
            val p = android.util.Xml.newPullParser()
            p.setInput(input, null)
            parseXmltv(p, wanted, now)
        }
    }

    /** Film and series pages: refreshed when older than 12 h (a running series gets new episodes), old copy kept on failure. */
    private fun info(url: String, name: String): String {
        val f = File(dir, name)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 12 * 3_600_000L) runCatching { fetch(url, name, true) }
        return (if (f.exists()) f else fetch(url, name, true)).readText()
    }

    fun movieInfo(id: String): MovieInfo = parseMovieInfo(info(xtream!!.api("get_vod_info", "&vod_id=$id"), "vod_$id.json"))
    fun showInfo(id: String): ShowInfo = parseShowInfo(info(xtream!!.api("get_series_info", "&series_id=$id"), "series_$id.json"), xtream)
}

fun open(url: String): InputStream = connect(url).inputStream

/** An answered connection (HTTP 200), redirects followed. */
fun connect(url: String): HttpURLConnection {
    var u = URL(url)
    // Follow redirects ourselves: HttpURLConnection refuses http <-> https hops, which providers use a lot (HTTP 301/302).
    repeat(6) {
        val c = u.openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        val code = c.responseCode
        if (code in 300..399) {
            val location = c.getHeaderField("Location") ?: throw IOException("redirection sans adresse (HTTP $code)")
            c.disconnect()
            u = URL(u, location)
            return@repeat
        }
        // Message never contains the URL, so the password can't leak into the UI.
        if (code != 200) { c.disconnect(); throw IOException("le serveur a répondu HTTP $code") }
        return c
    }
    throw IOException("trop de redirections")
}
