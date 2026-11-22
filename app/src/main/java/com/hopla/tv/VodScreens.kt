package com.hopla.tv

import android.content.SharedPreferences
import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- details

@Composable
private fun Meta(vararg parts: String) {
    Text(parts.filter { it.isNotBlank() }.joinToString("  ·  "), color = Color.White, fontFamily = Hopla.Body,
        fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun stars(rating: String) = rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { "Note ${"%.1f".format(it).replace('.', ',')}/10" }.orEmpty()

/** Who is in it and who made it, when the provider says. */
@Composable
private fun Credits(cast: String, director: String) {
    if (cast.isNotBlank()) Text("Avec  $cast", color = Color.White.copy(alpha = 0.86f), fontFamily = Hopla.Body, fontSize = 13.sp,
        lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (director.isNotBlank()) Text("Réalisation  $director", color = Color.White.copy(alpha = 0.7f), fontFamily = Hopla.Body, fontSize = 12.5.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun MovieDetail(movie: Movie, source: Source, history: History, onPlay: (Playable, fromStart: Boolean) -> Unit) {
    val info by produceState<MovieInfo?>(null, movie) { value = withContext(Dispatchers.IO) { runCatching { source.movieInfo(movie.id) }.getOrNull() } }
    val playable = Playable(movie.url, movie.title, info?.year.orEmpty(), info?.backdrop?.ifEmpty { movie.poster } ?: movie.poster)
    val progress = history.progress(movie.url)
    val first = remember { FocusRequester() }
    val context = LocalContext.current
    LaunchedEffect(Unit) { first.focusSoon() }

    Box(Modifier.fillMaxSize()) {
        FullBackdrop(info?.backdrop?.ifEmpty { movie.poster } ?: movie.poster)
        Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 48.dp).width(600.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(movie.title, color = Color.White, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, lineHeight = 45.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Meta(stars(movie.rating), info?.year.orEmpty().ifEmpty { movie.year.takeIf { it > 0 }?.toString().orEmpty() }, info?.genre.orEmpty(), fmtDuration(info?.duration.orEmpty()), movie.tags)
            Text(info?.plot.orEmpty(), color = Color.White.copy(alpha = 0.86f), fontFamily = Hopla.Body, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Credits(info?.cast.orEmpty(), info?.director.orEmpty())
            if (progress > 0f) Text("Arrêté à ${fmt(history.position(movie.url))}", color = Hopla.Accent, fontFamily = Hopla.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RailButton(Icons.Play, if (progress > 0f) "Reprendre" else "Regarder", { onPlay(playable, false) }, Modifier.focusRequester(first))
            if (progress > 0f) RailButton(Icons.Restart, "Du début", { onPlay(playable, true) })
            ListRail(history, "m:${movie.id}")
            info?.trailer?.takeIf { it.isNotBlank() }?.let { url -> RailButton(Icons.Trailer, "Bande-annonce", { openLink(context, url) }) }
        }
    }
}

@Composable
fun ShowDetail(show: Show, source: Source, history: History, onPlay: (List<Playable>, Int) -> Unit) {
    val info by produceState<ShowInfo?>(null, show) { value = withContext(Dispatchers.IO) { runCatching { source.showInfo(show.id) }.getOrNull() } }
    val episodes = info?.episodes.orEmpty()
    val queue = remember(episodes) {
        episodes.map { Playable(it.url, show.title, "S${it.season} É${it.number}" + (if (it.title.isNotBlank()) " · ${it.title}" else ""), it.image.ifEmpty { show.cover }, show.id) }
    }
    val seasons = remember(episodes) { episodes.map { it.season }.distinct() }
    // Where to pick up: the last episode left in progress, else the first one.
    val resumeAt = remember(queue) { history.resume().firstOrNull { it.showId == show.id }?.let { r -> queue.indexOfFirst { it.url == r.url } }?.takeIf { it >= 0 } ?: 0 }
    var season by remember(seasons) { mutableStateOf(episodes.getOrNull(resumeAt)?.season ?: seasons.firstOrNull() ?: 1) }
    val inSeason = remember(episodes, season) { episodes.withIndex().filter { it.value.season == season } }
    val first = remember { FocusRequester() }
    val pillState = rememberLazyListState()
    val pillFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    val episodeRow = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // The episode to land on in this season: the one in progress if it is here, else the first.
    val landing = inSeason.indexOfFirst { it.index == resumeAt }.coerceAtLeast(0)
    fun toEpisodes() = scope.launch { episodeRow.scrollToItem(landing); episodeFocus.focusSoon() }
    LaunchedEffect(queue.isNotEmpty()) { first.focusSoon() }

    Box(Modifier.fillMaxSize()) {
        FullBackdrop(info?.backdrop?.ifEmpty { null } ?: show.backdrop.ifEmpty { show.cover })
        Column(Modifier.padding(start = 48.dp, top = 40.dp).width(620.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(show.title, color = Color.White, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 41.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Meta(stars(show.rating), show.year.takeIf { it > 0 }?.toString().orEmpty(), show.genre, if (seasons.size > 1) "${seasons.size} saisons" else "", show.tags)
            Text(show.plot, color = Color.White.copy(alpha = 0.86f), fontFamily = Hopla.Body, fontSize = 13.5.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Credits(info?.cast.orEmpty(), info?.director.orEmpty())
        }
        Column(Modifier.align(Alignment.TopEnd).padding(end = 40.dp, top = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (queue.isNotEmpty()) {
                val e = episodes[resumeAt]
                val label = (if (history.progress(queue[resumeAt].url) > 0f) "Reprendre" else "Regarder") + " S${e.season}·É${e.number}"
                RailButton(Icons.Play, label, { onPlay(queue, resumeAt) }, Modifier.focusRequester(first))
            }
            ListRail(history, "s:${show.id}", if (queue.isEmpty()) Modifier.focusRequester(first) else Modifier)
            info?.trailer?.takeIf { it.isNotBlank() }?.let { url -> RailButton(Icons.Trailer, "Bande-annonce", { openLink(context, url) }) }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 44.dp, bottom = 24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (queue.isEmpty()) {
                Text(if (info == null) "Chargement des épisodes…" else "Aucun épisode disponible.", Modifier.padding(start = 4.dp), color = Color.White.copy(alpha = 0.7f), fontFamily = Hopla.Body, fontSize = 13.sp)
            } else {
                // Down from the season tabs lands on the episodes (it used to be swallowed).
                if (seasons.size > 1) CategoryPills(seasons.map { "Saison $it" }, "Saison $season", { season = it.removePrefix("Saison ").toInt() }, pillState, pillFocus) { toEpisodes() }
                Pivot(0f, 8.dp) { LazyRow(state = episodeRow, horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 4.dp, end = 500.dp, top = 6.dp, bottom = 6.dp)) {
                    itemsIndexed(inSeason) { pos, (i, ep) ->
                        WideCard("É${ep.number}" + (if (ep.title.isNotBlank()) " · ${ep.title}" else ""), ep.plot, ep.image.ifEmpty { show.cover },
                            history.progress(ep.url), onClick = { onPlay(queue, i) },
                            focus = if (pos == landing) Modifier.focusRequester(episodeFocus) else Modifier)
                    }
                } }
            }
        }
    }
}

/** "Ma liste" toggle for a film ("m:id") or a series ("s:id"). */
@Composable
private fun ListRail(history: History, key: String, modifier: Modifier = Modifier) {
    var inList by remember(key) { mutableStateOf(history.isFavorite(key)) }
    RailButton(if (inList) Icons.Check else Icons.Plus, if (inList) "Dans ma liste" else "Ma liste", { inList = history.toggleFavorite(key) }, modifier, engaged = inList)
}

// ---------------------------------------------------------------- VOD player

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * Full-screen player for films and episodes. Picks up where the household left off, saves the position,
 * and rolls on to the next episode with the same language choices (the long-lived player keeps them).
 */
@Composable
fun VodPlayer(player: ExoPlayer, prefs: SharedPreferences, history: History, queue: List<Playable>, start: Int, fromStart: Boolean, onExit: () -> Unit) {
    var i by remember { mutableIntStateOf(start) }
    val item = queue[i]
    val current by rememberUpdatedState(item)
    var overlayKey by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(false) }
    var strip by remember { mutableStateOf(false) }
    val stripFocus = remember { FocusRequester() }
    val stripState = rememberLazyListState()
    val hasEpisodes = queue.size > 1
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var paused by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(i) {
        problem = null
        player.volume = 1f
        val resume = if (fromStart && i == start) 0L else history.position(item.url)
        player.setMediaItem(MediaItem.fromUri(item.url), resume)
        player.prepare()
        player.play()
        overlayKey++
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                history.finish(current)
                if (i + 1 < queue.size) i++ else onExit()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { paused = !isPlaying && player.playWhenReady.not() }
            override fun onPlayerError(error: PlaybackException) { problem = "Lecture impossible" }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l)
            history.save(current, player.currentPosition, player.duration)
            player.stop()
        }
    }
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0)
            if (++tick % 20 == 0) history.save(current, pos, dur) // every ~10 s, in case the box is unplugged
            delay(500)
        }
    }
    LaunchedEffect(overlayKey) { overlay = true; delay(4_000); if (player.isPlaying) overlay = false }
    LaunchedEffect(sheet, strip) {
        if (strip) { stripState.scrollToItem(i); stripFocus.focusSoon() } else if (!sheet) focus.focusSoon()
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onKeyEvent {
                // Back closes the sheet first, then hides the time bar, then leaves the player.
                if (it.key == Key.Back) {
                    if (it.type == KeyEventType.KeyUp) { if (strip) strip = false else if (sheet) sheet = false else if (overlay) overlay = false else onExit() }
                    return@onKeyEvent true
                }
                if (sheet || strip || it.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (it.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
                        if (player.isPlaying) player.pause() else player.play(); paused = !player.playWhenReady; overlayKey++; true
                    }
                    Key.DirectionLeft, Key.MediaRewind -> { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); overlayKey++; true }
                    Key.DirectionRight, Key.MediaFastForward -> { player.seekTo(player.currentPosition + 30_000); overlayKey++; true }
                    Key.DirectionUp -> { sheet = true; true }
                    Key.DirectionDown -> { if (hasEpisodes) strip = true else overlayKey++; true }
                    else -> false
                }
            }
            .focusRequester(focus).focusable(),
    ) {
        AndroidView(
            factory = { ctx -> (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).also { it.player = player } },
            // Lift subtitles above the info overlay while it is shown, so the two never overlap.
            update = { it.keepScreenOn = true; it.subtitleView?.setBottomPaddingFraction(if (overlay && !sheet) 0.30f else 0.08f) },
            modifier = Modifier.fillMaxSize(),
        )
        problem?.let {
            Text(it, Modifier.align(Alignment.Center).clip(CircleShape).background(Hopla.Glass).padding(horizontal = 18.dp, vertical = 9.dp),
                color = Hopla.Text, fontFamily = Hopla.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        AnimatedVisibility(overlay && !sheet && !strip, Modifier.align(Alignment.BottomStart), enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Hopla.Ground.copy(alpha = 0.85f)))
                    .padding(start = 42.dp, end = 42.dp, top = 60.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.subtitle.isNotBlank()) Text(item.subtitle, color = Hopla.TextDim, fontFamily = Hopla.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text(item.title, color = Hopla.Text, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Progress(if (dur > 0) pos.toFloat() / dur else 0f, Modifier.fillMaxWidth().padding(top = 6.dp))
                Row {
                    Text(fmt(pos), color = Hopla.TextDim, fontFamily = Hopla.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text((if (paused) "En pause  ·  " else "") + "OK pause  ·  Gauche −10 s  ·  Droite +30 s  ·  Haut langues" + (if (hasEpisodes) "  ·  Bas épisodes" else ""),
                        color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    // "Fin à 21:43" answers the real question better than a countdown alone.
                    Text(if (dur > 0) "−" + fmt(dur - pos) + "  ·  fin à " + hhmm(System.currentTimeMillis() + dur - pos) else "",
                        color = Hopla.TextDim, fontFamily = Hopla.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                }
                if (i + 1 < queue.size) Text("Ensuite : ${queue[i + 1].subtitle}", color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 11.sp)
            }
        }
        if (sheet) LanguageSheet(player, prefs, Modifier.align(Alignment.CenterEnd))
        if (strip) Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to Hopla.Ground.copy(alpha = 0.92f)))
                .padding(start = 42.dp, top = 50.dp, bottom = 24.dp)
                .onPreviewKeyEvent { if (it.key == Key.DirectionUp && it.type == KeyEventType.KeyDown) { strip = false; true } else false },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Épisodes")
            Pivot(0f, 8.dp) {
                LazyRow(state = stripState, horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 4.dp, end = 600.dp, top = 6.dp, bottom = 6.dp)) {
                    itemsIndexed(queue) { idx, p ->
                        WideCard(p.subtitle, if (idx == i) "En cours" else "", p.image, history.progress(p.url),
                            onClick = {
                                if (idx != i) { history.save(current, player.currentPosition, player.duration); i = idx }
                                strip = false
                            },
                            focus = if (idx == i) Modifier.focusRequester(stripFocus) else Modifier)
                    }
                }
            }
        }
    }
}
