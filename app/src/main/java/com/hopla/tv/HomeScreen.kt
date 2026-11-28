package com.hopla.tv

import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Home: the last channel keeps playing (muted) in the hero, then what to pick up, your channels,
 * and what's new in films and series. Rows only appear when they have something in them.
 */
@Composable
fun HomeScreen(
    player: ExoPlayer, catalog: Catalog, history: History, numbers: Map<Channel, Int>, epg: Epg, now: Long,
    previewSound: PreviewSound, nav: Nav,
    onChannel: (Channel) -> Unit, onPlay: (Playable) -> Unit, onMovie: (Movie) -> Unit, onShow: (Show) -> Unit,
) {
    val byUrl = remember(catalog) { catalog.live.associateBy { it.url } }
    val recent = remember(catalog) { history.recentChannels().mapNotNull { byUrl[it] } }
    val hero = recent.firstOrNull { !isSeparator(it.name) } ?: catalog.live.firstOrNull { !isSeparator(it.name) } ?: catalog.live.first()
    val channels = recent.ifEmpty { catalog.live.take(12) }
    val favorites = remember(catalog) { history.favorites() }
    val favChannels = remember(catalog) { favorites.filter { it.startsWith("c:") }.mapNotNull { byUrl[it.removePrefix("c:")] } }
    val moviesById = remember(catalog) { catalog.movies.associateBy { it.id } }
    val showsById = remember(catalog) { catalog.shows.associateBy { it.id } }
    // "Ma liste" mixes films and series in the order they were added.
    val myList: List<Any> = remember(catalog) {
        favorites.mapNotNull { k -> if (k.startsWith("m:")) moviesById[k.removePrefix("m:")] else if (k.startsWith("s:")) showsById[k.removePrefix("s:")] else null }
    }
    val heroProg = epg.now(hero, now)
    // "Reprendre" keeps its own copy of each title: one from a hidden category must not show up here.
    val resume = remember(catalog) { history.resume().filter { it.url !in catalog.hidden && "s:${it.showId}" !in catalog.hidden } }
    // Sorting 10 000 films in the middle of drawing dropped frames: done off the main thread.
    val movies by produceState(emptyList<Movie>(), catalog) { value = withContext(Dispatchers.Default) { catalog.movies.sortedByDescending { it.added }.take(15) } }
    val shows by produceState(emptyList<Show>(), catalog) { value = withContext(Dispatchers.Default) { catalog.shows.sortedByDescending { it.added }.take(15) } }
    val first = remember { FocusRequester() }
    val results = remember { FocusRequester() }
    val handle = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(hero, previewSound) {
        player.volume = 0f
        if (player.currentMediaItem?.localConfiguration?.uri?.toString() != hero.url || player.playbackState == Player.STATE_IDLE) {
            player.setMediaItem(MediaItem.fromUri(hero.url))
            player.prepare()
        }
        player.play()
        player.previewVolume(previewSound)
    }
    LaunchedEffect(Unit) { first.focusSoon() }

    Row(Modifier.fillMaxSize().padding(18.dp)) {
        // Home has no categories: its menu is the section switch and a search across everything.
        SideMenu(nav, query, { query = it }, "Rechercher partout", emptyList(), null, {}, handle,
            toContent = { scope.launch { if (isSearching(query)) results.focusSoon() else first.focusSoon() } })
        Spacer(Modifier.width(18.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) { if (isSearching(query)) SearchResults(catalog, numbers, query, results, onChannel, onMovie, onShow) else Pivot(0f, 30.dp) { LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 300.dp)) {
            item {
                // The whole hero takes the focus (OK = Regarder): when only its button did, the list scrolled that
                // button to the top of the screen and pushed the live picture out of view.
                var heroFocused by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxWidth().height(300.dp).focusRequester(first)
                    .onFocusChanged { heroFocused = it.isFocused }
                    .onKeyEvent {
                        val ok = it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter
                        if (ok && it.type == KeyEventType.KeyUp) { onChannel(hero); true } else ok
                    }
                    .focusable()) {
                    Box(Modifier.align(Alignment.TopEnd).width(520.dp).height(293.dp).clip(RoundedCornerShape(21.dp))) {
                        AndroidView(
                            factory = { ctx -> (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).also { it.player = player } },
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Hopla.Ground, 0.4f to Hopla.Ground.copy(alpha = 0.35f), 0.7f to Color.Transparent)))
                    }
                    Column(Modifier.align(Alignment.CenterStart).width(380.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(Hopla.Live))
                            Text("En direct · Canal ${numbers[hero] ?: ""}", color = Hopla.TextDim, fontFamily = Hopla.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                        }
                        Text(hero.name, color = Hopla.Text, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 42.sp, lineHeight = 42.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (heroProg != null) {
                            Text("${heroProg.title}  ·  jusqu'à ${hhmm(heroProg.stop)}", color = Hopla.Text, fontFamily = Hopla.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Progress(heroProg.progress(now), Modifier.width(220.dp))
                        } else if (hero.group.isNotBlank()) Text(groupLabel(hero.group), color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 13.sp)
                        Row(Modifier.padding(top = 8.dp).height(40.dp).clip(CircleShape).background(if (heroFocused) Hopla.Accent else Hopla.AccentSoft)
                            .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Play, null, Modifier.size(15.dp), tint = if (heroFocused) Hopla.OnAccent else Hopla.Accent)
                            Spacer(Modifier.width(8.dp))
                            Text("Regarder", color = if (heroFocused) Hopla.OnAccent else Hopla.Text, fontFamily = Hopla.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (resume.isNotEmpty()) item {
                Shelf("Reprendre") {
                    items(resume) { p -> WideCard(p.title, p.subtitle, p.image, history.progress(p.url), onClick = { onPlay(p) }) }
                }
            }
            if (myList.isNotEmpty()) item {
                Shelf("Ma liste") {
                    items(myList) { item ->
                        when (item) {
                            is Movie -> PosterCard(item.title, item.poster, onClick = { onMovie(item) }, progress = history.progress(item.url))
                            is Show -> PosterCard(item.title, item.cover, onClick = { onShow(item) })
                        }
                    }
                }
            }
            if (favChannels.isNotEmpty()) item {
                Shelf("Favoris") { items(favChannels) { ch -> ChannelCard(numbers[ch] ?: 0, ch, onClick = { onChannel(ch) }) } }
            }
            item {
                Shelf(if (recent.isEmpty()) "Chaînes" else "Vos chaînes") {
                    items(channels) { ch -> ChannelCard(numbers[ch] ?: 0, ch, onClick = { onChannel(ch) }) }
                }
            }
            if (movies.isNotEmpty()) item {
                Shelf("Films ajoutés récemment") { items(movies) { m -> PosterCard(m.title, m.poster, onClick = { onMovie(m) }, progress = history.progress(m.url)) } }
            }
            if (shows.isNotEmpty()) item {
                Shelf("Séries") { items(shows) { s -> PosterCard(s.title, s.cover, onClick = { onShow(s) }) } }
            }
        } } }
    }
}

@Composable
fun Shelf(title: String, modifier: Modifier = Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        Pivot(0f, 8.dp) { LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(start = 4.dp, end = 400.dp, top = 6.dp, bottom = 6.dp), content = content) }
    }
}
