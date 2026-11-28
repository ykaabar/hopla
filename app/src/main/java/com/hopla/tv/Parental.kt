package com.hopla.tv

import android.content.SharedPreferences
import org.json.JSONArray
import java.security.MessageDigest

/** The three lists a category can belong to; [prefix] keeps a "Sport" in Direct apart from a "Sport" in Films. */
enum class Kind(val prefix: String, val label: String) { LIVE("l:", "Direct"), MOVIES("m:", "Films"), SHOWS("s:", "Séries") }

/**
 * Words providers use for adult categories ("XXX", "ADULTES", "+18", "FOR ADULTS"…). Such a category is hidden
 * without the household doing anything, including one the provider adds later; only the category manager
 * (behind the parental code) shows it again.
 */
private val ADULT = Regex(
    """(?iu)(adult|xxx|porn|erot|érot|hentai|playboy|brazzers|hustler|penthouse|\bsex|\b18\s*\+|\+\s*18\b|\(18\)|\[18])""",
)

fun isAdult(group: String) = ADULT.containsMatchIn(group)

/**
 * Which categories the household sees. [hide] holds the categories hidden by hand, [show] the adult ones
 * shown again on purpose (keys are [Kind.prefix] + group name, as the provider writes it).
 */
data class GroupFilter(val hide: Set<String> = emptySet(), val show: Set<String> = emptySet()) {
    fun hidden(kind: Kind, group: String): Boolean {
        if (group.isBlank()) return false // titles without a category have nowhere to be shown again from
        val k = kind.prefix + group
        return k in hide || (k !in show && isAdult(group))
    }

    fun toggle(kind: Kind, group: String): GroupFilter {
        val k = kind.prefix + group
        return if (hidden(kind, group)) GroupFilter(hide - k, if (isAdult(group)) show + k else show)
        else GroupFilter(hide + k, show - k)
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit().putString("hideGroups", JSONArray(hide.toList()).toString()).putString("showGroups", JSONArray(show.toList()).toString()).apply()
    }

    companion object {
        fun load(prefs: SharedPreferences) = GroupFilter(prefs.stringSet("hideGroups"), prefs.stringSet("showGroups"))

        private fun SharedPreferences.stringSet(key: String): Set<String> = getString(key, null)?.let { s ->
            runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrNull()
        }.orEmpty()
    }
}

/**
 * The catalogue without its hidden categories. Every screen (home rows, lists, search, "Ma liste") works on
 * this copy, so a hidden category cannot come back through a side door. [Catalog.hidden] lists what was taken
 * out (channel and film addresses, "s:" + series id) for the history rows that keep their own copy of a title.
 */
fun Catalog.visible(f: GroupFilter): Catalog {
    // One decision per category, not one regex per title: 161 000 films sit in a few hundred categories.
    fun memo(kind: Kind): (String) -> Boolean {
        val seen = HashMap<String, Boolean>()
        return { g -> seen.getOrPut(g) { f.hidden(kind, g) } }
    }
    val offLive = memo(Kind.LIVE)
    val offMovie = memo(Kind.MOVIES)
    val offShow = memo(Kind.SHOWS)
    val out = HashSet<String>()
    val l = live.filter { ch -> !offLive(ch.group).also { if (it) out += ch.url } }
    val m = movies.filter { mv -> !offMovie(mv.group).also { if (it) out += mv.url } }
    val s = shows.filter { sh -> !offShow(sh.group).also { if (it) out += "s:${sh.id}" } }
    return if (out.isEmpty()) this else Catalog(l, m, s, guide, out)
}

/** Categories of one list in playlist order, with how many titles each holds (blank names left out). */
fun Catalog.groups(kind: Kind): List<Pair<String, Int>> {
    val counts = LinkedHashMap<String, Int>()
    val names = when (kind) {
        Kind.LIVE -> live.asSequence().map { it.group }
        Kind.MOVIES -> movies.asSequence().map { it.group }
        Kind.SHOWS -> shows.asSequence().map { it.group }
    }
    names.filter { it.isNotBlank() }.forEach { counts[it] = (counts[it] ?: 0) + 1 }
    return counts.toList()
}

// ---------------------------------------------------------------- parental code

private fun hash(pin: String) =
    MessageDigest.getInstance("SHA-256").digest("hopla-pin:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

fun SharedPreferences.hasPin() = getString("pinHash", null) != null
fun SharedPreferences.checkPin(pin: String) = getString("pinHash", null) == hash(pin)
fun SharedPreferences.setPin(pin: String?) {
    if (pin == null) edit().remove("pinHash").apply() else edit().putString("pinHash", hash(pin)).apply()
}
