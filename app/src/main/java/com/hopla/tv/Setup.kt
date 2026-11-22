package com.hopla.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.net.URLEncoder

/**
 * Xtream address from what providers actually send: a server ("panel.tv:8080", with or without http://,
 * sometimes a folder), a username and a password. Hopla only talks to player_api.php, and picks the stream
 * format (ts or m3u8) from the account's allowed formats, so nothing else is asked.
 */
fun xtreamLink(server: String, user: String, pass: String): String {
    val s = server.trim().trimEnd('/')
    parseXtream(s)?.let { return s } // a whole Xtream link pasted into the server field
    val base = if (s.startsWith("http://") || s.startsWith("https://")) s else "http://$s"
    fun enc(v: String) = URLEncoder.encode(v.trim(), "UTF-8").replace("+", "%20")
    return "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}"
}

/** The server as typed back into the form: "panel.tv:8080", no http:// when it is the default. */
private fun serverOf(x: Xtream) = x.base.removePrefix("http://")

/**
 * Connection screen (also the settings): server, username and password, or a whole M3U / Xtream link for
 * providers that only send that. [link] stays the one value the app stores (sealed in the Keystore).
 */
@Composable
fun SetupScreen(link: String, onLink: (String) -> Unit, error: String?, sound: PreviewSound, onSound: (PreviewSound) -> Unit, onLoad: () -> Unit) {
    val known = remember { parseXtream(link) }
    var byLink by remember { mutableStateOf(link.isNotBlank() && known == null) }
    var server by remember { mutableStateOf(known?.let(::serverOf).orEmpty()) }
    var user by remember { mutableStateOf(known?.user.orEmpty()) }
    var pass by remember { mutableStateOf(known?.pass.orEmpty()) }
    var showPass by remember { mutableStateOf(false) }
    val fServer = remember { FocusRequester() }
    val fUser = remember { FocusRequester() }
    val fPass = remember { FocusRequester() }
    val fLink = remember { FocusRequester() }
    val fGo = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val ready = if (byLink) link.isNotBlank() else server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
    val original = remember { link }
    // Same account as before: keep the saved link exactly. A rewritten link would count as a new playlist,
    // which clears the favourites and history and rebuilds the cache.
    fun syncLink() {
        if (byLink) return
        onLink(when {
            server.isBlank() -> ""
            known != null && server == serverOf(known) && user == known.user && pass == known.pass -> original
            else -> xtreamLink(server, user, pass)
        })
    }
    val go = { keyboard?.hide(); if (ready) { syncLink(); onLoad() } }
    val refused = error?.startsWith(REFUSED) == true

    // Land where there is something to do: a refused password, the first empty field, else "Se connecter".
    LaunchedEffect(byLink, error) {
        when {
            byLink -> (if (link.isBlank()) fLink else fGo).focusSoon()
            refused -> { showPass = true; fPass.focusSoon() }
            server.isBlank() -> fServer.focusSoon()
            user.isBlank() -> fUser.focusSoon()
            pass.isBlank() -> fPass.focusSoon()
            else -> fGo.focusSoon()
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF1D2030), Hopla.Ground), radius = 1400f))) {
        Row(Modifier.fillMaxSize().padding(horizontal = 60.dp, vertical = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row {
                    Text("hopla", color = Hopla.Text, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp)
                    Text(".", color = Hopla.Accent, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp)
                }
                Text(if (byLink) "Connexion par lien" else "Connecte ton abonnement", Modifier.padding(top = 8.dp), color = Hopla.Text,
                    fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 33.sp)
                Text(
                    if (byLink) "Pour les fournisseurs qui n'envoient qu'une liste M3U. Un lien Xtream complet marche aussi : Hopla y lit le serveur, l'identifiant et le mot de passe."
                    else "Les trois informations envoyées par ton fournisseur. Hopla trouve tout seul le reste, y compris le format des flux.",
                    color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 14.sp, lineHeight = 20.sp,
                )
                Text("OK : saisir  ·  Bas : champ suivant  ·  Retour : fermer le clavier", Modifier.padding(top = 6.dp), color = Hopla.TextDim,
                    fontFamily = Hopla.Body, fontSize = 12.sp)
                Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Son des aperçus", color = Hopla.Text, fontFamily = Hopla.Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PreviewSound.entries.forEach { m ->
                            val selected = m == sound
                            FocusRow(onClick = { onSound(m) }, shape = CircleShape, idle = if (selected) Hopla.AccentSoft else Hopla.Chip, modifier = Modifier.height(34.dp)) { focused ->
                                Text(m.label, Modifier.padding(horizontal = 14.dp), color = if (focused) Hopla.OnAccent else if (selected) Hopla.Accent else Hopla.Text,
                                    fontFamily = Hopla.Body, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            Column(
                Modifier.width(470.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xEB1C1E26)).padding(horizontal = 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                error?.let { ErrorBox(it) }
                if (byLink) {
                    Field("Lien de la playlist", link, onLink, fLink, next = fGo, keyboardType = KeyboardType.Uri, placeholder = "http://serveur:port/get.php?…")
                    Text("Le lien est enregistré chiffré sur cette TV, jamais en clair.", color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 12.sp)
                } else {
                    Field("Serveur", server, { server = it; syncLink() }, fServer, next = fUser, keyboardType = KeyboardType.Uri, placeholder = "monserveur.com:8080")
                    Field("Identifiant", user, { user = it; syncLink() }, fUser, next = fPass)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field("Mot de passe", pass, { pass = it; syncLink() }, fPass, next = fGo, modifier = Modifier.weight(1f), password = !showPass)
                        FocusRow(onClick = { showPass = !showPass }, shape = RoundedCornerShape(13.dp), idle = Color(0xFF2B2E3A), modifier = Modifier.height(50.dp)) { f ->
                            Text(if (showPass) "Masquer" else "Afficher", Modifier.padding(horizontal = 14.dp), color = if (f) Hopla.OnAccent else Hopla.Text,
                                fontFamily = Hopla.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
                FocusRow(onClick = go, shape = CircleShape, idle = if (ready) Hopla.Accent else Color(0xFF2B2E3A),
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(50.dp).focusRequester(fGo)) { f ->
                    Spacer(Modifier.weight(1f))
                    Text("Se connecter", color = if (f || ready) Hopla.OnAccent else Hopla.TextDim, fontFamily = Hopla.Body, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                }
                FocusRow(onClick = {
                    // Switching keeps what was typed: a pasted Xtream link fills the three fields, and back.
                    if (byLink) parseXtream(link)?.let { x -> server = serverOf(x); user = x.user; pass = x.pass }
                    else syncLink()
                    byLink = !byLink
                }, shape = CircleShape, idle = Color.Transparent, modifier = Modifier.fillMaxWidth().height(40.dp)) { f ->
                    Spacer(Modifier.weight(1f))
                    Text(if (byLink) "Revenir à serveur, identifiant et mot de passe" else "J'ai un lien M3U ou Xtream",
                        color = if (f) Hopla.OnAccent else Hopla.TextDim, fontFamily = Hopla.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** First line of the error the setup screen shows when the server refuses the account. */
const val REFUSED = "Identifiant ou mot de passe refusés"

/** "Title\nwhat to do": the first line in bold. */
@Composable
private fun ErrorBox(error: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Color(0x24FF5E5E)).padding(horizontal = 14.dp, vertical = 11.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(error.substringBefore('\n'), color = Hopla.Text, fontFamily = Hopla.Body, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            error.substringAfter('\n', "").takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color(0xFFE2C9C9), fontFamily = Hopla.Body, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }
    }
}

/**
 * One form field for the remote. Focus alone never opens the Fire TV keyboard (it covers the whole screen):
 * the field shows its value, OK starts typing, and the keyboard's Next / Done moves on to [next].
 */
@Composable
private fun Field(
    label: String, value: String, onValue: (String) -> Unit, focus: FocusRequester, next: FocusRequester,
    modifier: Modifier = Modifier, password: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text, placeholder: String = "",
) {
    var editing by remember { mutableStateOf(false) }
    val edit = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    LaunchedEffect(editing) { if (editing) edit.focusSoon() }
    fun done() { keyboard?.hide(); editing = false; scope.launch { next.focusSoon() } }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Hopla.TextDim, fontFamily = Hopla.Body, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
        if (editing) OutlinedTextField(
            value, onValue, Modifier.fillMaxWidth().focusRequester(edit)
                .onKeyEvent { if (it.key == Key.Back && it.type == KeyEventType.KeyUp) { editing = false; scope.launch { focus.focusSoon() }; true } else false },
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = TextStyle(fontFamily = Hopla.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Hopla.Text),
            shape = RoundedCornerShape(13.dp),
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onAny = { done() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Hopla.Accent, unfocusedBorderColor = Hopla.Chip, cursorColor = Hopla.Accent,
                focusedTextColor = Hopla.Text, unfocusedTextColor = Hopla.Text,
            ),
        ) else Box(
            Modifier.fillMaxWidth().height(50.dp).focusRequester(focus)
                .onKeyEvent {
                    val ok = it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter
                    if (ok && it.type == KeyEventType.KeyUp) { editing = true; true } else ok
                }
                .focusable(interactionSource = source)
                .clip(RoundedCornerShape(13.dp)).background(if (focused) Hopla.Text else Color(0xFF2B2E3A))
                .then(if (focused) Modifier.border(3.dp, Hopla.Accent, RoundedCornerShape(13.dp)) else Modifier)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val shown = if (password && value.isNotEmpty()) "•".repeat(value.length.coerceAtMost(16)) else value
            Text(shown.ifEmpty { placeholder }, color = when { focused -> Color(0xFF15120A); value.isEmpty() -> Hopla.TextDim; else -> Hopla.Text },
                fontFamily = Hopla.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                letterSpacing = if (password && value.isNotEmpty()) 2.sp else 0.sp)
        }
    }
}
