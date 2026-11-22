package com.hopla.tv

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.widget.Toast
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MainActivity : ComponentActivity() {
    // One player for the whole app: preview, full screen and zapping only swap the media item.
    private lateinit var player: ExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val http = DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT).setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
        val prefs = getSharedPreferences("hopla", MODE_PRIVATE)
        applyLanguagePrefs(player, prefs)
        // Compose paints the whole background itself; the window's own fill would be one more full-screen layer per frame.
        window.setBackgroundDrawable(null)
        setContent { App(player, prefs) }
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onStart() {
        super.onStart()
        if (player.mediaItemCount > 0) player.play()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}

@Composable
fun App(player: ExoPlayer, prefs: SharedPreferences) {
    val context = LocalContext.current
    val history = remember { History(prefs) }
    var link by remember { mutableStateOf(prefs.readLink()) }
    var source by remember { mutableStateOf<Source?>(null) }
    var catalog by remember { mutableStateOf<Catalog?>(null) }
    // With a saved link the first frame is the loading screen, never the playlist form (reading the cache of a
    // big list takes seconds on a small Fire TV, and the form used to show during that time).
    var loading by remember { mutableStateOf(link.isNotBlank()) }
    var progress by remember { mutableStateOf<Pair<Float, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(Section.HOME) }
    var liveStart by remember { mutableStateOf<Channel?>(null) }
    var movie by remember { mutableStateOf<Movie?>(null) }
    var show by remember { mutableStateOf<Show?>(null) }
    var playing by remember { mutableStateOf<Triple<List<Playable>, Int, Boolean>?>(null) }
    var epg by remember { mutableStateOf(Epg.EMPTY) }
    var previewSound by remember { mutableStateOf(PreviewSound.entries.firstOrNull { it.name == prefs.getString("previewSound", null) } ?: PreviewSound.OFF) } // kept here so a film opened from the results comes back to them
    // One clock for the whole app: programme titles and progress bars move on by themselves.
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val scope = rememberCoroutineScope()
    var loadJob by remember { mutableStateOf<Job?>(null) }

    /**
     * Cached catalogue first (instant), then a fresh download in the background replaces it if it changed.
     * [refresh] is the side menu's "Rafraîchir la playlist": no cache step, and the result is announced.
     */
    fun load(userAction: Boolean, refresh: Boolean = false) {
        // One load at a time: a startup refresh still running when a new link is typed would otherwise finish
        // last and save the new, unchecked link with the old catalogue.
        loadJob?.cancel()
        val l = link
        val src = Source(l, context.filesDir)
        source = src
        error = null
        if (catalog == null) loading = true
        if (!userAction) history.bindTo(l, src.xtream) // same saved link: only sets the credential mask
        // Kept as soon as it is typed: a big list takes a while to load, and an app stopped before the end
        // (Home pressed, low memory on a small Fire TV) used to ask for the link again at the next start.
        if (userAction) prefs.saveLink(l)
        // ATLAS-style: the copy on the TV for the day, a full reload (with progress) once a day or on request.
        // No background refresh any more: with 240 000 titles, two catalogues at once filled the memory.
        loadJob = scope.launch {
            try {
                if (!userAction && !refresh && src.hasCache && src.cacheAge < 24 * 3_600_000L) {
                    val t0 = System.currentTimeMillis()
                    val cached = withContext(Dispatchers.IO) { src.catalog(fresh = false) }
                    android.util.Log.i("Hopla", "cached catalogue: ${cached.live.size} live, ${cached.movies.size} films, ${cached.shows.size} series in ${System.currentTimeMillis() - t0} ms")
                    catalog = cached
                    loading = false
                    return@launch
                }
                catalog = null // the old copy goes before the new one is read, never both in memory
                loading = true
                progress = 0f to "Connexion au serveur…"
                val fresh = withContext(Dispatchers.IO) { src.catalog(fresh = true) { p, m -> progress = p to m } }
                if (fresh.live.isEmpty()) error("Cette playlist ne contient aucune chaîne.")
                prefs.saveLink(l)
                history.bindTo(l, src.xtream)
                src.dropOtherCaches() // only now that this link has proven to work
                catalog = fresh
                settings = false
                if (refresh) Toast.makeText(context, "Playlist à jour : ${fresh.live.size} chaînes, ${fresh.movies.size} films, ${fresh.shows.size} séries", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                if (e is CancellationException) throw e // replaced by a newer load: not an error to show
                // A failed daily reload keeps the household watching on the copy it has.
                val cached = if (userAction || !src.hasCache) null else runCatching { withContext(Dispatchers.IO) { src.catalog(fresh = false) } }.getOrNull()
                if (cached != null) {
                    catalog = cached
                    Toast.makeText(context, "Mise à jour impossible : ${e.message ?: e.javaClass.simpleName}. La liste actuelle est gardée.", Toast.LENGTH_LONG).show()
                } else error = friendlyError(e)
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (link.isNotBlank()) load(userAction = false) }
    // A newer Hopla on GitHub shows up as one more row in the side menu, announced once; it never steals focus.
    var update by remember { mutableStateOf<Update?>(null) }
    LaunchedEffect(Unit) {
        update = withContext(Dispatchers.IO) { runCatching { checkUpdate(context) }.getOrNull() }
        update?.let { Toast.makeText(context, "Nouvelle version de Hopla (${it.name}) : menu hopla. › Mettre à jour", Toast.LENGTH_LONG).show() }
    }

    val cat = catalog
    val src = source
    if (cat == null || src == null || settings) {
        // From the settings, Back returns to the channels instead of quitting the app.
        BackHandler(enabled = settings && cat != null) { settings = false }
        Box(Modifier.fillMaxSize().background(Hopla.Ground)) {
            if (loading) Loading(progress) else SetupScreen(link, { link = it }, error, previewSound, { previewSound = it; prefs.edit().putString("previewSound", it.name).apply() }) { catalog = null; load(userAction = true) }
        }
        return
    }

    val numbers = remember(cat) { HashMap<Channel, Int>().apply { cat.live.forEachIndexed { i, ch -> putIfAbsent(ch, i + 1) } } }
    val sections = remember(cat) {
        listOfNotNull(Section.HOME, Section.LIVE, Section.MOVIES.takeIf { cat.movies.isNotEmpty() }, Section.SHOWS.takeIf { cat.shows.isNotEmpty() })
    }
    // The guide is big and slow to fetch: it arrives in the background and the screens fill in when it does.
    LaunchedEffect(cat.guide) {
        val wanted = cat.live.map { it.epgId }.filter { it.isNotEmpty() }.toSet()
        epg = withContext(Dispatchers.IO) { runCatching { src.guide(cat.guide, wanted, System.currentTimeMillis()) }.onFailure { android.util.Log.w("Hopla", "guide", it) }.getOrDefault(Epg.EMPTY) }
    }
    val nav = Nav(section, sections, go = { liveStart = null; section = it }, settings = { player.stop(); settings = true }, refresh = { load(userAction = false, refresh = true) },
        update = update?.let { u ->
            "Mettre à jour Hopla (${u.name})" to {
                Toast.makeText(context, "Téléchargement de la mise à jour…", Toast.LENGTH_SHORT).show()
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { installUpdate(context, u) } }
                        .onFailure { Toast.makeText(context, "Mise à jour impossible : ${it.message ?: it.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
                }
            }
        })
    // Films and series pages show no video, so the live preview must not keep streaming behind them.
    LaunchedEffect(section, movie, show) {
        if (section == Section.MOVIES || section == Section.SHOWS || movie != null || show != null) player.stop()
    }
    BackHandler(enabled = playing == null && (movie != null || show != null || section != Section.HOME)) {
        when {
            movie != null -> movie = null
            show != null -> show = null
            else -> { liveStart = null; section = Section.HOME }
        }
    }

    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    // Films and series tiles are built once per catalogue, in the background: rebuilding 10 000 of them (names
    // normalised for sorting, reading progress) on every visit made switching sections slow. Only the reading
    // progress is refreshed, when the player closes.
    val moviesById = remember(cat) { cat.movies.associateBy { it.id } }
    val showsById = remember(cat) { cat.shows.associateBy { it.id } }
    val movieBase by produceState<List<Pair<Tile, String>>?>(null, cat) {
        val t0 = System.currentTimeMillis()
        value = withContext(Dispatchers.Default) { cat.movies.map { Tile("m:${it.id}", it.name, it.group, it.poster, it.added, it.rating.toDoubleOrNull() ?: 0.0, it.year) to it.url } }
        android.util.Log.i("Hopla", "film tiles: ${cat.movies.size} in ${System.currentTimeMillis() - t0} ms")
    }
    val movieTiles by produceState<List<Tile>?>(null, movieBase, playing == null) {
        val base = movieBase ?: return@produceState
        if (playing != null) return@produceState
        value = withContext(Dispatchers.Default) {
            val started = history.progressAll() // only the titles actually started, not 161 000 lookups
            base.map { (t, url) -> (started[url] ?: 0f).let { p -> if (p == t.progress) t else t.copy(progress = p) } }
        }
    }
    // Shelves ready before the household gets to Films or Séries.
    LaunchedEffect(movieTiles) { movieTiles?.let { t -> withContext(Dispatchers.Default) { shelvesOf("gm:", t, t.filter { it.progress > 0f }) } } }
    val showTiles by produceState<List<Tile>?>(null, cat) {
        value = withContext(Dispatchers.Default) { cat.shows.map { Tile("s:${it.id}", it.name, it.group, it.cover, it.added, it.rating.toDoubleOrNull() ?: 0.0, it.year) } }
    }
    LaunchedEffect(showTiles) {
        showTiles?.let { t ->
            val ids = history.resume().map { it.showId }.toSet()
            withContext(Dispatchers.Default) { shelvesOf("gs:", t, t.filter { it.key.removePrefix("s:") in ids }) }
        }
    }
    Box(Modifier.fillMaxSize().background(Hopla.Ground)) {
        val p = playing
        when {
            p != null -> VodPlayer(player, prefs, history, p.first, p.second, p.third) { playing = null }
            movie != null -> MovieDetail(movie!!, src, history) { item, fromStart -> playing = Triple(listOf(item), 0, fromStart) }
            show != null -> ShowDetail(show!!, src, history) { queue, i -> playing = Triple(queue, i, false) }
            section == Section.HOME -> HomeScreen(
                player, cat, history, numbers, epg, now, previewSound, nav,
                onChannel = { liveStart = it; section = Section.LIVE },
                onPlay = { playing = Triple(listOf(it), 0, false) },
                onMovie = { movie = it }, onShow = { show = it },
            )
            section == Section.LIVE -> key(liveStart) { LiveScreen(player, cat.live, prefs, history, epg, now, previewSound, liveStart, nav) }
            section == Section.MOVIES -> movieTiles.let { list ->
                if (list == null) Loading() else saved.SaveableStateProvider("movies") {
                    VodHub(list, remember(list) { list.filter { it.progress > 0f } }, prefs, history, "gm:", "sortMovies", "Rechercher un film", spotOf = { t ->
                        val info = src.movieInfo(t.key.removePrefix("m:"))
                        Spot(info.backdrop, info.plot, info.genre, fmtDuration(info.duration))
                    }, nav = nav) { t -> movie = moviesById[t.key.removePrefix("m:")] }
                }
            }
            section == Section.SHOWS -> showTiles.let { list ->
                val resumeIds = remember(cat, playing == null) { history.resume().map { it.showId }.toSet() }
                if (list == null) Loading() else saved.SaveableStateProvider("shows") {
                    VodHub(list, remember(list, resumeIds) { list.filter { it.key.removePrefix("s:") in resumeIds } }, prefs, history, "gs:", "sortShows", "Rechercher une série", spotOf = { t ->
                        showsById[t.key.removePrefix("s:")]?.let { Spot(it.backdrop, it.plot, it.genre, "") }
                    }, nav = nav) { t -> show = showsById[t.key.removePrefix("s:")] }
                }
            }
        }
    }
}

@Composable
private fun Wordmark(size: Int) {
    Row {
        Text("hopla", color = Hopla.Text, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = size.sp)
        Text(".", color = Hopla.Accent, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = size.sp)
    }
}

/**
 * Loading screen. With [progress] (a first download, or a new playlist) the "hopla." wordmark fills with the
 * accent colour as the lists arrive, with the percentage and what is being done; without it, the plain wordmark.
 */
@Composable
private fun Loading(progress: Pair<Float, String>? = null) {
    val p by androidx.compose.animation.core.animateFloatAsState(progress?.first ?: 0f, androidx.compose.animation.core.tween(600), label = "loading")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Row {
                Text("hopla", color = Hopla.Chip, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 64.sp)
                Text(".", color = Hopla.Chip, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 64.sp)
            }
            // The same wordmark in colour, revealed from the left as far as the download has gone.
            Row(Modifier.drawWithContent { clipRect(right = size.width * (if (progress == null) 1f else p)) { this@drawWithContent.drawContent() } }) {
                Text("hopla", color = Hopla.Text, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 64.sp)
                Text(".", color = Hopla.Accent, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 64.sp)
            }
        }
        if (progress != null) Text("${(p * 100).toInt()} %", color = Hopla.Accent, fontFamily = Hopla.Display, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(progress?.second ?: "Chargement…", color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 14.sp)
    }
}

/** What went wrong, in the household's words: "title\nwhat to do" (see the setup screen). */
fun friendlyError(e: Exception): String = when {
    e.message?.contains("identifiants refusés") == true ->
        "$REFUSED\nLe serveur répond, mais ne reconnaît pas ce compte. Vérifie les majuscules du mot de passe."
    e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException || e is java.net.MalformedURLException ->
        "Serveur introuvable\nVérifie l'adresse et le port, par exemple monserveur.com:8080."
    else -> "Impossible de charger la playlist\n${e.message ?: e.javaClass.simpleName}"
}
