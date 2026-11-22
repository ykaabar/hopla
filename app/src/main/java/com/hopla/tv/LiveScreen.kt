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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private const val ALL = "Toutes"
private const val FAVS = "Favoris"

@Composable
fun LiveScreen(
    player: ExoPlayer, channels: List<Channel>, prefs: SharedPreferences, history: History,
    epg: Epg, now: Long, previewSound: PreviewSound, startAt: Channel?, nav: Nav,
) {
    var favs by remember { mutableStateOf(history.favorites().filter { it.startsWith("c:") }.map { it.removePrefix("c:") }.toSet()) }
    var favGroups by remember { mutableStateOf(history.favorites().filter { it.startsWith("g:") }.map { it.removePrefix("g:") }.toSet()) }
    // Favourite categories come right after Toutes/Favoris, so the household's few never hide among hundreds.
    val groups = remember(channels, favs.isNotEmpty(), favGroups) {
        val all = channels.map { it.group }.filter { it.isNotBlank() }.distinct()
        listOfNotNull(ALL, FAVS.takeIf { favs.isNotEmpty() }) + all.filter { it in favGroups } + all.filterNot { it in favGroups }
    }
    var group by remember { mutableStateOf(if (startAt != null) ALL else history.lastGroup?.takeIf { it in groups } ?: ALL) }
    LaunchedEffect(groups) { if (group !in groups) group = ALL }
    var query by remember { mutableStateOf("") }
    // Search keys for every channel, built once off the main thread (tens of thousands of names).
    val keys by produceState<List<Pair<Channel, String>>?>(null, channels) {
        value = withContext(Dispatchers.Default) { channels.map { it to searchKey(it.name) } }
    }
    val found by produceState<List<Channel>?>(null, query, keys) {
        val k = keys ?: return@produceState
        delay(200)
        value = withContext(Dispatchers.Default) { searchIn(k, query, limit = 300) }
    }
    val searching = isSearching(query)
    val byGroup = remember(channels, group, favs) {
        when (group) {
            ALL -> channels
            FAVS -> channels.filter { it.url in favs }
            else -> channels.filter { it.group == group }
        }
    }
    val shown = if (searching) found.orEmpty() else byGroup
    val lastUrl = remember { startAt?.url ?: history.lastChannel }
    // Channel numbers follow the playlist order, like a TV remote.
    val numbers = remember(channels) { HashMap<Channel, Int>().apply { channels.forEachIndexed { i, ch -> putIfAbsent(ch, i + 1) } } }
    var index by remember(shown) { mutableIntStateOf(shown.indexOfFirst { it.url == lastUrl }.coerceAtLeast(0)) }
    var current by remember { mutableStateOf<Channel?>(null) }
    var full by remember { mutableStateOf(channels.isNotEmpty()) }
    var rail by remember { mutableStateOf(false) }
    val railFocus = remember { FocusRequester() }
    var sheet by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var bannerKey by remember { mutableIntStateOf(0) }
    var banner by remember { mutableStateOf(false) }

    val listState = rememberLazyListState(index)
    val rowFocus = remember { FocusRequester() }
    val fullFocus = remember { FocusRequester() }
    val menu = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    fun toList() = scope.launch { listState.scrollToItem(index); rowFocus.focusSoon(); full = false }

    fun toggleFavorite(ch: Channel) {
        val added = history.toggleFavorite("c:" + ch.url)
        favs = if (added) favs + ch.url else favs - ch.url
        notice = if (added) "${ch.name} ajoutée aux favoris" else "${ch.name} retirée des favoris"
    }

    fun toggleGroup(g: String) {
        if (g == ALL || g == FAVS) return
        val added = history.toggleFavorite("g:$g")
        favGroups = if (added) favGroups + g else favGroups - g
        notice = if (added) "${groupLabel(g)} épinglée en haut" else "${groupLabel(g)} n'est plus épinglée"
    }

    fun play(ch: Channel) {
        if (ch == current) return
        current = ch
        history.addChannel(ch.url)
        history.lastGroup = group
        problem = null
        // Coming from the home screen the stream is often already running: don't restart it.
        if (player.currentMediaItem?.localConfiguration?.uri?.toString() == ch.url && player.playbackState != Player.STATE_IDLE) return
        player.setMediaItem(MediaItem.fromUri(ch.url))
        player.prepare()
        player.play()
    }

    fun zap(step: Int) {
        if (shown.isEmpty()) return
        index = (index + step + shown.size) % shown.size
        play(shown[index])
        bannerKey++
    }

    // Preview follows the focus, after a short pause so scrolling through the list stays smooth.
    LaunchedEffect(index, shown) {
        delay(if (full) 0 else 450)
        shown.getOrNull(index)?.let(::play)
    }
    // Full screen always has sound; the preview follows the household's setting, restarting on each channel.
    LaunchedEffect(full, current, previewSound) { if (full) player.volume = 1f else player.previewVolume(previewSound) }
    // A stream that never starts shows a clear message instead of a black, frozen screen.
    LaunchedEffect(current) {
        delay(10_000)
        if (current != null && !player.isPlaying && problem == null) problem = "Pas de signal pour le moment"
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) problem = null }
            override fun onPlayerError(error: PlaybackException) { problem = "Chaîne indisponible" }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(bannerKey, rail) {
        banner = true
        if (rail) return@LaunchedEffect // the caption stays while the actions are open
        delay(5_000)
        banner = false
    }
    LaunchedEffect(notice) { if (notice != null) { delay(2_500); notice = null } }
    LaunchedEffect(full, sheet, rail) {
        listState.scrollToItem(index)
        // Retried: a lost request left the focus on the hidden list, where Back opened an invisible menu (stuck).
        if (full) { if (rail) railFocus.focusSoon() else if (!sheet) fullFocus.focusSoon() } else { rail = false; rowFocus.focusSoon() }
    }

    // Full screen owns Back wherever the focus happens to be: close the sheet, then hide the banner, then leave.
    val fullBack = Modifier.onPreviewKeyEvent {
        if (!full || it.key != Key.Back) return@onPreviewKeyEvent false
        if (it.type == KeyEventType.KeyUp) when {
            sheet -> sheet = false
            rail -> rail = false
            banner -> banner = false
            else -> toList()
        }
        true
    }

    Pivot(0.45f) { Box(Modifier.fillMaxSize().then(fullBack).background(Hopla.Ground)) {
        Row(Modifier.fillMaxSize().padding(18.dp).graphicsLayer { alpha = if (full) 0f else 1f }) {
            SideMenu(nav, query, { query = it }, "Rechercher une chaîne", groups, group, { group = it }, menu,
                toContent = { scope.launch { listState.scrollToItem(index); rowFocus.focusSoon() } },
                pinned = favGroups, onPin = ::toggleGroup)
            Spacer(Modifier.width(18.dp))
            Column(
                Modifier.width(315.dp).fillMaxHeight().clip(RoundedCornerShape(21.dp)).background(Hopla.Glass)
                    .padding(horizontal = 13.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.Bottom) {
                    Text(if (searching) "« ${query.trim()} »" else groupLabel(group), Modifier.weight(1f), color = Hopla.Text, fontFamily = Hopla.Display,
                        fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${shown.size} chaînes", color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 11.sp)
                }
                if (searching && found != null && shown.isEmpty()) Text("Aucune chaîne ne correspond.", Modifier.padding(6.dp), color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    Modifier.onPreviewKeyEvent {
                        // Back in a long list opens the menu (categories, search); Left gets there by itself.
                        if (it.key == Key.Back) { if (it.type == KeyEventType.KeyUp) menu.requestFocus(); true } else false
                    },
                    state = listState, verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    itemsIndexed(shown) { i, ch ->
                        ChannelRow(
                            number = numbers[ch] ?: 0, channel = ch, programme = epg.now(ch, now), now = now, favorite = ch.url in favs,
                            showGroup = searching || group == ALL || group == FAVS,
                            modifier = if (i == index) Modifier.focusRequester(rowFocus) else Modifier,
                            onFocus = { index = i },
                            onClick = { index = i; play(ch); full = true; bannerKey++ },
                            onFavorite = { toggleFavorite(ch) },
                        )
                    }
                }
            }
        }

        val videoShape = RoundedCornerShape(if (full) 0.dp else 21.dp)
        val videoModifier = if (full) Modifier.fillMaxSize()
        else Modifier.padding(start = 435.dp, top = 18.dp, end = 27.dp).fillMaxWidth().aspectRatio(16f / 9f).shadow(18.dp, videoShape)
        Box(videoModifier.clip(videoShape).background(Color.Black)) {
            AndroidView(
                factory = { ctx -> (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).also { it.player = player } },
                // Full screen keeps the TV awake (no screensaver mid-match); the muted preview does not.
                update = { it.keepScreenOn = full },
                modifier = Modifier.fillMaxSize(),
            )
            if (!full) LivePill(Modifier.padding(12.dp))
            problem?.let {
                Text(it, Modifier.align(Alignment.Center).clip(CircleShape).background(Hopla.Glass).padding(horizontal = 18.dp, vertical = 9.dp),
                    color = Hopla.Text, fontFamily = Hopla.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }

        if (!full) shown.getOrNull(index)?.let { ch -> NowPanel(ch, numbers[ch] ?: 0, epg, now) }

        if (full) {
            Box(
                Modifier.fillMaxSize()
                    .onKeyEvent {
                        // Back is handled by fullBack on the screen's root.
                        if (sheet || it.type != KeyEventType.KeyDown) return@onKeyEvent false
                        // Inside the actions rail the arrows move between its buttons; Left closes it.
                        if (rail) return@onKeyEvent if (it.key == Key.DirectionLeft) { rail = false; true } else false
                        when (it.key) {
                            Key.DirectionUp, Key.ChannelUp -> { zap(-1); true }
                            Key.DirectionDown, Key.ChannelDown -> { zap(1); true }
                            // OK opens the channel list on the current channel: the list is never more than a press away.
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { toList(); true }
                            Key.Menu -> { current?.let(::toggleFavorite); true }
                            Key.DirectionRight -> { rail = true; bannerKey++; true }
                            Key.DirectionLeft -> { bannerKey++; true }
                            else -> false
                        }
                    }
                    .focusRequester(fullFocus).focusable(),
            ) {
                AnimatedVisibility(banner && !sheet, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
                    current?.let { ch ->
                        val n = shown.size
                        val prev = if (n > 1) shown[(index - 1 + n) % n] else null
                        val next = if (n > 1) shown[(index + 1) % n] else null
                        LiveFrame(numbers[ch] ?: 0, ch, prev?.let { (numbers[it] ?: 0) to it }, next?.let { (numbers[it] ?: 0) to it }, epg, now,
                            if (searching) "« ${query.trim()} »" else groupLabel(group), index + 1, n)
                    }
                }
                if (rail && !sheet) Column(Modifier.align(Alignment.CenterEnd).padding(end = 34.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val fav = current?.url in favs
                    RailButton(Icons.Star, if (fav) "Favori" else "Ajouter", { current?.let(::toggleFavorite) }, Modifier.focusRequester(railFocus), engaged = fav)
                    RailButton(Icons.Lang, "Langues", { rail = false; sheet = true })
                    RailButton(Icons.List, "Chaînes", { rail = false; toList() })
                }
                if (sheet) LanguageSheet(player, prefs, Modifier.align(Alignment.CenterEnd)) {
                    current?.let { ch ->
                        val fav = ch.url in favs
                        SheetOption(if (fav) "Retirer des favoris" else "Ajouter aux favoris", fav, Modifier) { toggleFavorite(ch) }
                    }
                }
            }
        }

        notice?.let {
            Text(it, Modifier.align(Alignment.TopCenter).padding(top = 24.dp).clip(CircleShape).background(Hopla.Accent)
                .padding(horizontal = 18.dp, vertical = 9.dp), color = Hopla.OnAccent, fontFamily = Hopla.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    } }
}

/** What's on, under the preview: the programme when the guide knows it, else the channel. */
@Composable
private fun NowPanel(ch: Channel, number: Int, epg: Epg, now: Long) {
    val prog = epg.now(ch, now)
    Column(Modifier.padding(start = 435.dp, top = 312.dp, end = 27.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(listOf("Canal $number", if (prog != null) ch.name else groupLabel(ch.group)).filter { it.isNotBlank() }.joinToString(" · "),
            color = Hopla.TextDim, fontFamily = Hopla.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(prog?.title ?: ch.name, color = Hopla.Text, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp,
            lineHeight = 30.sp, maxLines = if (prog != null) 1 else 2, overflow = TextOverflow.Ellipsis)
        if (prog != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${hhmm(prog.start)} – ${hhmm(prog.stop)}", color = Hopla.TextDim, fontFamily = Hopla.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Spacer(Modifier.width(10.dp))
                Progress(prog.progress(now), Modifier.weight(1f))
            }
            if (prog.desc.isNotBlank()) Text(prog.desc, color = Hopla.Text.copy(alpha = 0.8f), fontFamily = Hopla.Body, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // The free space under the preview tells what comes next on this channel.
            epg.upcoming(ch, now).forEach { p ->
                Row(Modifier.padding(top = 2.dp)) {
                    Text(hhmm(p.start), Modifier.width(52.dp), color = Hopla.TextDim, fontFamily = Hopla.Display, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(p.title, color = Hopla.Text, fontFamily = Hopla.Body, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text("OK : plein écran  ·  Gauche : menu  ·  Appui long OK : favori", color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 11.sp)
    }
}

@Composable
private fun ChannelRow(
    number: Int, channel: Channel, programme: Programme?, now: Long, favorite: Boolean, showGroup: Boolean,
    modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onFavorite: () -> Unit,
) {
    val keys = favoriteKeys(onFavorite)
    FocusRow(onClick = onClick, onFocus = onFocus, modifier = modifier.then(keys).fillMaxWidth().height(51.dp).padding(horizontal = 2.dp)) { focused ->
        Text("$number", Modifier.width(34.dp).padding(start = 8.dp), color = if (focused) Hopla.OnAccent else Hopla.TextDim,
            fontFamily = Hopla.Body, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
        Box(
            Modifier.width(44.dp).height(33.dp).clip(RoundedCornerShape(9.dp))
                .background(if (focused) Hopla.OnAccent.copy(alpha = 0.85f) else Hopla.LogoTile),
            contentAlignment = Alignment.Center,
        ) {
            Img(channel.logo, Modifier.padding(4.dp).fillMaxSize(), ContentScale.Fit, fallback = initials(channel.name))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (favorite) {
                    Icon(Icons.Star, "Favori", Modifier.size(11.dp), tint = if (focused) Hopla.OnAccent else Hopla.Accent)
                    Spacer(Modifier.width(4.dp))
                }
                Text(channel.name, color = if (focused) Hopla.OnAccent else Hopla.Text, fontFamily = Hopla.Body,
                    fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val sub = programme?.title ?: if (showGroup) groupLabel(channel.group) else ""
            if (sub.isNotBlank()) Text(sub, color = if (focused) Hopla.OnAccentDim else Hopla.TextDim,
                fontFamily = Hopla.Body, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (programme != null) Progress(programme.progress(now), Modifier.padding(top = 3.dp).width(110.dp),
                track = if (focused) Hopla.OnAccent.copy(alpha = 0.2f) else Color(0x33FFFFFF))
        }
        Spacer(Modifier.width(10.dp))
    }
}

@Composable
private fun LivePill(modifier: Modifier) {
    Row(
        modifier.clip(CircleShape).background(Hopla.Ground.copy(alpha = 0.6f)).padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Hopla.Live))
        Text("EN DIRECT", color = Hopla.Text, fontFamily = Hopla.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.4.sp)
    }
}

/** "ALB: ART SPORT 1" -> "AS": a readable stand-in when the provider sends no logo. */
fun initials(name: String): String {
    val words = name.substringAfter(':').split(' ', '-', '|', '.').map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
    return words.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { name.take(2).uppercase() }
}

/**
 * Plein cadre caption over the live picture: where you are in the list, what is on, and the channels one press
 * above and below.
 */
@Composable
private fun LiveFrame(
    number: Int, channel: Channel, prev: Pair<Int, Channel>?, next: Pair<Int, Channel>?, epg: Epg, now: Long,
    where: String, position: Int, total: Int,
) {
    val prog = epg.now(channel, now)
    val after = epg.next(channel, now)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.2f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Row(Modifier.padding(start = 40.dp, top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("hopla", color = Color.White, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            Text(".", color = Hopla.Accent, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Text("$where · $position sur $total", Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 5.dp),
                color = Color.White, fontFamily = Hopla.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(hhmm(now), Modifier.align(Alignment.TopEnd).padding(end = 40.dp, top = 20.dp), color = Color.White, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        prev?.let { (n, ch) -> Peek(n, ch, epg, now, up = true, Modifier.align(Alignment.TopCenter).padding(top = 64.dp)) }
        next?.let { (n, ch) -> Peek(n, ch, epg, now, up = false, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)) }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 62.dp).width(640.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(Hopla.Accent), contentAlignment = Alignment.Center) {
                    Text("$number", color = Hopla.OnAccent, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = if (number > 999) 16.sp else 20.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(channel.name, color = Color.White, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Hopla.Live))
                        Spacer(Modifier.width(6.dp))
                        Text("EN DIRECT", color = Color.White, fontFamily = Hopla.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 0.4.sp)
                    }
                }
            }
            if (prog != null) {
                Text(prog.title, Modifier.padding(top = 6.dp), color = Color.White, fontFamily = Hopla.Body, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${hhmm(prog.start)} – ${hhmm(prog.stop)}" + (after?.let { "  ·  Ensuite : ${it.title}" } ?: ""), color = Color.White.copy(alpha = 0.75f),
                    fontFamily = Hopla.Body, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Progress(prog.progress(now), Modifier.padding(top = 4.dp).width(420.dp))
            } else if (channel.group.isNotBlank()) {
                Text(groupLabel(channel.group), Modifier.padding(top = 4.dp), color = Color.White.copy(alpha = 0.75f), fontFamily = Hopla.Body, fontSize = 14.sp)
            }
            Text("Haut/Bas : chaînes  ·  OK : liste  ·  Droite : actions", Modifier.padding(top = 4.dp), color = Color.White.copy(alpha = 0.6f),
                fontFamily = Hopla.Body, fontSize = 11.5.sp)
        }
    }
}

/** The channel one press above or below, named before you get there. */
@Composable
private fun Peek(number: Int, channel: Channel, epg: Epg, now: Long, up: Boolean, modifier: Modifier) {
    Row(modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Chevron, null, Modifier.size(16.dp).graphicsLayer { rotationZ = if (up) 180f else 0f }, tint = Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.width(8.dp))
        Text("$number", color = Color.White, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(listOfNotNull(channel.name, epg.now(channel, now)?.title).joinToString(" · "), Modifier.width(IntrinsicSize.Max).widthIn(max = 520.dp),
            color = Color.White.copy(alpha = 0.85f), fontFamily = Hopla.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
