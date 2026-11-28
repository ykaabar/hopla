package com.hopla.tv

import android.content.SharedPreferences
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the category manager is showing: the code to get in, the lists, or the steps of a new code. */
private sealed interface Step {
    data object Locked : Step
    data object Lists : Step
    data object NewPin : Step
    data class ConfirmPin(val first: String) : Step
}

/**
 * Category manager: every category of Direct, Films and Séries with OK to hide or show it again. Adult
 * categories start hidden. With a parental code set, the code is asked before anything is shown.
 * [catalog] is the whole catalogue (hidden categories included); [onDone] gets the filter to apply.
 */
@Composable
fun CategoriesScreen(catalog: Catalog, prefs: SharedPreferences, initial: GroupFilter, onDone: (GroupFilter) -> Unit) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf(initial) }
    var step by remember { mutableStateOf<Step>(if (prefs.hasPin()) Step.Locked else Step.Lists) }
    var hasPin by remember { mutableStateOf(prefs.hasPin()) }
    // Kept here, not in the prompt: a wrong confirmation goes back to the first prompt with its message.
    var pinError by remember { mutableStateOf<String?>(null) }
    fun go(next: Step, error: String? = null) { step = next; pinError = error }
    val kinds = remember(catalog) {
        listOfNotNull(Kind.LIVE, Kind.MOVIES.takeIf { catalog.movies.isNotEmpty() }, Kind.SHOWS.takeIf { catalog.shows.isNotEmpty() })
    }
    var kind by remember { mutableStateOf(Kind.LIVE) }
    // Counting 161 000 films by category takes a moment on a Fire TV: done off the main thread, once.
    val groups by produceState<Map<Kind, List<Pair<String, Int>>>?>(null, catalog) {
        value = withContext(Dispatchers.Default) { kinds.associateWith { catalog.groups(it) } }
    }

    fun leave() = onDone(filter)

    BackHandler {
        when (step) {
            Step.Locked -> onDone(initial) // no code, no change
            Step.Lists -> leave()
            else -> go(Step.Lists)
        }
    }

    Box(Modifier.fillMaxSize().background(Hopla.Ground)) {
        when (val s = step) {
            Step.Locked -> PinPrompt("Code parental", "Entre le code pour gérer les catégories.", pinError) { pin ->
                if (prefs.checkPin(pin)) go(Step.Lists) else go(Step.Locked, "Code faux, réessaie.")
            }
            Step.NewPin -> PinPrompt("Nouveau code", "Choisis 4 chiffres. Ils seront demandés pour gérer les catégories.", pinError) { pin ->
                go(Step.ConfirmPin(pin))
            }
            is Step.ConfirmPin -> PinPrompt("Confirme le code", "Tape les mêmes 4 chiffres une deuxième fois.", pinError) { pin ->
                if (pin == s.first) {
                    prefs.setPin(pin); hasPin = true; go(Step.Lists)
                    Toast.makeText(context, "Code parental enregistré", Toast.LENGTH_SHORT).show()
                } else go(Step.NewPin, "Les deux codes ne sont pas pareils, recommence.")
            }
            Step.Lists -> CategoryLists(
                kinds, kind, { kind = it }, groups?.get(kind), filter, hasPin,
                onToggle = { g ->
                    val next = filter.toggle(kind, g)
                    // Direct must keep at least one category: the home screen is built around a channel.
                    val liveLeft = groups?.get(Kind.LIVE).orEmpty().count { !next.hidden(Kind.LIVE, it.first) }
                    if (kind == Kind.LIVE && liveLeft == 0 && catalog.live.none { it.group.isBlank() }) {
                        Toast.makeText(context, "Il faut garder au moins une catégorie de chaînes.", Toast.LENGTH_SHORT).show()
                    } else {
                        filter = next
                        next.save(prefs) // kept at once: the app can be stopped before Back is pressed
                    }
                },
                onPin = { go(Step.NewPin) },
                onRemovePin = {
                    prefs.setPin(null); hasPin = false
                    Toast.makeText(context, "Code parental retiré", Toast.LENGTH_SHORT).show()
                },
                onDone = ::leave,
            )
        }
    }
}

@Composable
private fun CategoryLists(
    kinds: List<Kind>, kind: Kind, onKind: (Kind) -> Unit, groups: List<Pair<String, Int>>?, filter: GroupFilter, hasPin: Boolean,
    onToggle: (String) -> Unit, onPin: () -> Unit, onRemovePin: () -> Unit, onDone: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.focusSoon() }
    Row(Modifier.fillMaxSize().padding(horizontal = 60.dp, vertical = 40.dp)) {
        Column(Modifier.weight(1f).padding(end = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row {
                Text("hopla", color = Hopla.Text, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
                Text(".", color = Hopla.Accent, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
            }
            Text("Catégories masquées", color = Hopla.Text, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
            Text(
                "Une catégorie masquée disparaît partout : listes, accueil, recherche et « Reprendre ». " +
                    "Les catégories pour adultes sont masquées d'office, même celles que le fournisseur ajoutera plus tard.",
                color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 13.5.sp, lineHeight = 19.sp,
            )
            Text("OK : masquer ou afficher  ·  Retour : terminer", color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 12.sp)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.forEachIndexed { i, k ->
                    val on = k == kind
                    FocusRow(onClick = { onKind(k) }, onFocus = { onKind(k) }, shape = CircleShape, idle = if (on) Hopla.AccentSoft else Hopla.Chip,
                        modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).height(34.dp)) { f ->
                        Text(k.label, Modifier.padding(horizontal = 16.dp), color = if (f) Hopla.OnAccent else if (on) Hopla.Accent else Hopla.Text,
                            fontFamily = Hopla.Body, fontWeight = if (on || f) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Code parental", color = Hopla.Text, fontFamily = Hopla.Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (!hasPin) Text(
                "Sans code, n'importe qui (un enfant aussi) peut réafficher une catégorie depuis ce menu.",
                color = Color(0xFFFFB4A8), fontFamily = Hopla.Body, fontSize = 12.5.sp, lineHeight = 17.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(if (hasPin) "Changer le code" else "Choisir un code", onPin)
                if (hasPin) PillButton("Retirer le code", onRemovePin)
            }
            Spacer(Modifier.weight(1f))
            PillButton("Terminer", onDone, accent = true)
        }
        Column(
            Modifier.width(500.dp).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(Hopla.Glass).padding(vertical = 14.dp, horizontal = 12.dp),
        ) {
            when {
                groups == null -> Text("Chargement…", Modifier.padding(12.dp), color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 13.sp)
                groups.isEmpty() -> Text("Aucune catégorie dans cette liste.", Modifier.padding(12.dp), color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 13.sp)
                else -> Pivot(0.35f) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        itemsIndexed(groups, key = { _, g -> g.first }) { _, (g, n) ->
                            val off = filter.hidden(kind, g)
                            FocusRow(onClick = { onToggle(g) }, shape = RoundedCornerShape(11.dp), modifier = Modifier.fillMaxWidth().height(40.dp)) { f ->
                                Box(Modifier.padding(start = 12.dp, end = 10.dp).size(18.dp), contentAlignment = Alignment.Center) {
                                    if (!off) Icon(Icons.Check, "Visible", Modifier.size(16.dp), tint = if (f) Hopla.OnAccent else Hopla.Accent)
                                    else Icon(Icons.Lock, "Masquée", Modifier.size(14.dp), tint = if (f) Hopla.OnAccent else Hopla.TextDim)
                                }
                                Text(groupLabel(g).ifBlank { g }, Modifier.weight(1f), color = if (f) Hopla.OnAccent else if (off) Hopla.TextDim else Hopla.Text,
                                    fontFamily = Hopla.Body, fontWeight = if (f) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (isAdult(g)) Text("18+", Modifier.padding(start = 8.dp).clip(CircleShape).background(if (f) Hopla.OnAccentDim else Color(0x33FF5A4E))
                                    .padding(horizontal = 7.dp, vertical = 1.dp), color = if (f) Hopla.Accent else Hopla.Live,
                                    fontFamily = Hopla.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                Text(if (off) "masquée" else "$n", Modifier.padding(start = 10.dp, end = 12.dp).width(60.dp), textAlign = TextAlign.End,
                                    color = if (f) Hopla.OnAccent else Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 11.5.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit, accent: Boolean = false) {
    FocusRow(onClick = onClick, shape = CircleShape, idle = if (accent) Hopla.Accent else Hopla.Chip, modifier = Modifier.height(38.dp)) { f ->
        Text(label, Modifier.padding(horizontal = 18.dp), color = if (f || accent) Hopla.OnAccent else Hopla.Text,
            fontFamily = Hopla.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/**
 * Four-digit code. The number keyboard opens at once; the fourth digit submits to [onCode] and empties the
 * field. [error] is the message of the last wrong try, if any.
 */
@Composable
private fun PinPrompt(title: String, hint: String, error: String?, onCode: (String) -> Unit) {
    var code by remember(title, error) { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(title) { if (field.focusSoon()) keyboard?.show() }
    fun submit(v: String) {
        if (v.length < 4) return
        code = ""
        onCode(v)
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        Icon(Icons.Lock, null, Modifier.size(34.dp), tint = Hopla.Accent)
        Text(title, color = Hopla.Text, fontFamily = Hopla.Display, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
        Text(hint, Modifier.width(420.dp), color = Hopla.TextDim, fontFamily = Hopla.Body, fontSize = 14.sp, textAlign = TextAlign.Center)
        OutlinedTextField(
            code, { v -> val d = v.filter(Char::isDigit).take(4); code = d; if (d.length == 4) submit(d) },
            Modifier.width(220.dp).focusRequester(field),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            textStyle = TextStyle(fontFamily = Hopla.Display, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Hopla.Text, textAlign = TextAlign.Center, letterSpacing = 10.sp),
            shape = RoundedCornerShape(15.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onAny = { submit(code) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Hopla.Accent, unfocusedBorderColor = Hopla.Chip, cursorColor = Hopla.Accent,
                focusedTextColor = Hopla.Text, unfocusedTextColor = Hopla.Text,
            ),
        )
        Text(error ?: "Retour : annuler", color = if (error != null) Color(0xFFFFB4A8) else Hopla.TextDim, fontFamily = Hopla.Body,
            fontWeight = if (error != null) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
    }
}
