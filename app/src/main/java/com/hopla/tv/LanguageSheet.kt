package com.hopla.tv

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

private const val AUDIO = "audioLang"
private const val TEXT = "textLang" // a language tag, or "off"

/**
 * Re-applies the saved language choices. They are preferences on the long-lived player, not per-stream
 * overrides, so they survive every channel change and episode (ATLAS reset audio to French after each episode).
 */
fun applyLanguagePrefs(player: ExoPlayer, prefs: SharedPreferences) {
    val b = player.trackSelectionParameters.buildUpon()
    prefs.getString(AUDIO, null)?.let { b.setPreferredAudioLanguage(it) }
    when (val t = prefs.getString(TEXT, null)) {
        null -> Unit
        "off" -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        else -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(t)
    }
    player.trackSelectionParameters = b.build()
}

private data class Choice(val label: String, val selected: Boolean, val pick: () -> Unit)

private fun languageName(lang: String?): String? =
    lang?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.FRENCH) }
        ?.takeIf { it.isNotBlank() }
        ?.replaceFirstChar { it.uppercase(Locale.FRENCH) }

private fun choices(player: ExoPlayer, prefs: SharedPreferences, tracks: Tracks, type: Int): List<Choice> {
    val out = ArrayList<Choice>()
    var n = 0
    for (g in tracks.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            n++
            val f = g.getTrackFormat(i)
            val lang = f.language?.takeIf { it != "und" }
            val label = f.label ?: languageName(lang) ?: "Piste $n"
            out += Choice(label, g.isTrackSelected(i)) {
                val b = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false).clearOverridesOfType(type)
                // Two tracks in the same language (e.g. stereo and 5.1) can only be told apart by an override.
                val sameLang = lang != null && (0 until g.length).count { g.getTrackFormat(it).language == lang } > 1
                if (lang != null && !sameLang) {
                    // A language preference carries over to the next channel or episode; an override would not.
                    if (type == C.TRACK_TYPE_AUDIO) b.setPreferredAudioLanguage(lang) else b.setPreferredTextLanguage(lang)
                    prefs.edit().putString(if (type == C.TRACK_TYPE_AUDIO) AUDIO else TEXT, lang).apply()
                } else {
                    b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                }
                player.trackSelectionParameters = b.build()
            }
        }
    }
    // A lone untagged track reads better as "Piste unique" than "Piste 1".
    if (out.size == 1 && out[0].label == "Piste 1") out[0] = out[0].copy(label = "Piste unique")
    return out
}

@Composable
/** Options sheet: [top] adds screen-specific actions (the live screen puts "favourite" there). */
fun LanguageSheet(player: ExoPlayer, prefs: SharedPreferences, modifier: Modifier, top: @Composable () -> Unit = {}) {
    var tracks by remember { mutableStateOf(player.currentTracks) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) { tracks = t }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    val audio = choices(player, prefs, tracks, C.TRACK_TYPE_AUDIO)
    val text = choices(player, prefs, tracks, C.TRACK_TYPE_TEXT)
    val textOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || text.none { it.selected }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.focusSoon() }

    Column(
        modifier.padding(18.dp).width(300.dp).fillMaxHeight().clip(RoundedCornerShape(21.dp)).background(Hopla.Glass)
            .verticalScroll(rememberScrollState()).padding(horizontal = 15.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        top()
        Header("Audio")
        if (audio.isEmpty()) Note("Une seule piste audio sur cette chaîne.")
        audio.forEachIndexed { i, c -> SheetOption(c.label, c.selected, if (i == 0) Modifier.focusRequester(first) else Modifier, c.pick) }

        Spacer(Modifier.height(12.dp))
        Header("Sous-titres")
        SheetOption("Désactivés", textOff, if (audio.isEmpty()) Modifier.focusRequester(first) else Modifier) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
            prefs.edit().putString(TEXT, "off").apply()
        }
        text.forEach { c -> SheetOption(c.label, c.selected && !textOff, Modifier, c.pick) }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Hopla.AccentSoft).padding(12.dp),
        ) {
            Text("Hopla garde ces choix pour les prochaines chaînes et les prochains épisodes.",
                color = Hopla.Text, fontFamily = Hopla.Body, fontSize = 11.5.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, Modifier.padding(start = 8.dp, bottom = 2.dp), color = Hopla.Text, fontFamily = Hopla.Display,
        fontWeight = FontWeight.ExtraBold, fontSize = 19.5.sp)
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(horizontal = 8.dp), color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 12.sp)
}

@Composable
fun SheetOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FocusRow(
        onClick = onClick,
        shape = RoundedCornerShape(13.5.dp),
        idle = if (selected) Hopla.Chip else androidx.compose.ui.graphics.Color.Transparent,
        modifier = modifier.fillMaxWidth().height(42.dp),
    ) { focused ->
        Text(label, Modifier.weight(1f).padding(start = 12.dp), color = if (focused) Hopla.OnAccent else Hopla.Text,
            fontFamily = Hopla.Body, fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp)
        if (selected) Icon(Icons.Check, "Sélectionné", Modifier.padding(end = 12.dp).size(16.dp), tint = if (focused) Hopla.OnAccent else Hopla.Accent)
    }
}
