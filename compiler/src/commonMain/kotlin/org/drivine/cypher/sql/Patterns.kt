package org.drivine.cypher.sql

/**
 * The backreferences a regular expression of `=~` writes, read as Java reads them, and those of them the SQL stores answer as
 * Java does (#236). The stores hold a pattern to the whole text in a group of their own, and each reads a backreference its
 * own way past the simplest forms, so the forms they answer are a closed set, and every other is refused by name.
 */
internal object Patterns {

    /**
     * A pattern that finds a backreference, `\1` to `\9`, that no backslash escapes, as each store's regular expressions read
     * it: a pattern worked out as the statement runs, which the compiler can't read, is refused then where it writes one
     * (`SqlCypherCompiler.holdsBackreference`). On SQLite each library's regular expressions read it, so it is among the
     * compiler's patterns the tests check on every target (`DrivinePatterns`).
     */
    const val BACKREFERENCE = """(^|[^\\])(\\\\)*\\[1-9]"""

    /**
     * Why the SQL stores can't answer [pattern]'s backreferences as Java does, or null where they can, or it writes none. A
     * backreference is answered where it is `\1` to `\8`, outside a character class, with no digit after it, to a group that
     * has closed before it and takes part in every match: one in no alternative, lookaround or quantifier that lets it match
     * no times, nor in a group that is.
     */
    fun refusal(pattern: String): String? {
        val (found, groups) = scan(pattern)
        for (it in found) {
            val why = when {
                it.inClass -> "in a character class, which Java refuses"
                it.number > 8 -> "past \\8"
                it.digitAfter -> "with a digit after it"
                it.number !in it.closed -> "to a group that hasn't closed before it"
                !groups[it.number - 1].always -> "to a group that may take no part in a match: in an alternative, a lookaround, or under a quantifier that lets it match no times"
                else -> null
            }
            if (why != null) {
                return "The regular expression '$pattern' writes a backreference, \\${it.number}, $why: the SQL stores answer a " +
                    "backreference as Java does only where it is \\1 to \\8, with no digit after it, to a group that closed before it and takes part in every match."
            }
        }
        return null
    }

    /** Whether [pattern] writes a backreference, as Java reads one. */
    fun holdsBackreference(pattern: String): Boolean = scan(pattern).first.isNotEmpty()

    /** A backreference: its number, whether it is in a character class, whether a digit follows it, and the groups closed before it. */
    private class Backreference(val number: Int, val inClass: Boolean, val digitAfter: Boolean, val closed: Set<Int>)

    /**
     * A capturing group, and whether it takes part in every match, which is known once the pattern is read: none of it, nor
     * any group that holds it, is in an alternative or a lookaround, or under a quantifier that lets it match no times.
     */
    private class Group {
        var always = true
    }

    /** A group open as the pattern is read: the capturing group it is, if it captures; whether it alternates; what it holds. */
    private class Open(val group: Group?, val parent: Open?, val lookaround: Boolean) {
        var alternated = false
        val held = mutableListOf<Group>()
    }

    /** Whether a quantifier at [at] in [pattern] lets what it follows match no times: `?`, `*` or `{0`. */
    private fun none(pattern: String, at: Int) =
        pattern.getOrNull(at) == '?' || pattern.getOrNull(at) == '*' || Regex("""\{0+[,}]""").find(pattern, at)?.range?.first == at

    /**
     * Each backreference [pattern] writes, and its capturing groups by number, read as Java reads them: a backslash that no
     * backslash escapes, then a digit 1 to 9, and each digit after it while the number still names a group opened before it.
     * One quoted (`\Q…\E`) is none, nor `\cX`'s character. A class is opened by `[` and closed by a `]` that isn't its first
     * character, and may hold another, as Java's do.
     */
    private fun scan(pattern: String): Pair<List<Backreference>, List<Group>> {
        val groups = mutableListOf<Group>()
        val closed = mutableSetOf<Int>()
        val found = mutableListOf<Backreference>()
        val top = Open(null, null, false)
        var open = top
        var depth = 0
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> {
                    val next = pattern[i + 1]
                    if (next == 'Q') {
                        val end = pattern.indexOf("\\E", i + 2)
                        i = if (end < 0) pattern.length else end + 2
                        continue
                    }
                    // A control character, `\cX`, is the next character's: `\c[` opens no class.
                    if (next == 'c') {
                        i += 3
                        continue
                    }
                    if (next in '1'..'9') {
                        var number = next - '0'
                        i += 2
                        while (depth == 0 && pattern.getOrNull(i) in '0'..'9' && number * 10 + (pattern[i] - '0') <= groups.size) {
                            number = number * 10 + (pattern[i++] - '0')
                        }
                        found += Backreference(number, depth > 0, pattern.getOrNull(i) in '0'..'9', closed.toSet())
                        continue
                    }
                    i += 2
                    continue
                }
                c == '(' && depth == 0 -> {
                    val named = pattern.startsWith("?<", i + 1) && pattern.getOrNull(i + 3)?.isLetter() == true
                    val lookaround = listOf("?=", "?!", "?<=", "?<!").any { pattern.startsWith(it, i + 1) }
                    val group = if (!pattern.startsWith("?", i + 1) || named) Group().also(groups::add) else null
                    open = Open(group, open, lookaround)
                }
                c == '|' && depth == 0 -> open.alternated = true
                c == ')' && depth == 0 && open.parent != null -> {
                    val done = open
                    open = done.parent!!
                    done.group?.let { closed += groups.indexOf(it) + 1 }
                    // An alternative in a group holds what's in it, not the group itself; a lookaround or a quantifier, both.
                    if (done.alternated) done.held.forEach { it.always = false }
                    val held = done.held + listOfNotNull(done.group)
                    if (done.lookaround || none(pattern, i + 1)) held.forEach { it.always = false }
                    open.held += held
                }
                c == '[' -> {
                    depth++
                    // A class's first character, `]` or `^]`, is itself.
                    if (pattern.startsWith("^]", i + 1)) i += 2 else if (pattern.startsWith("]", i + 1)) i += 1
                }
                c == ']' && depth > 0 -> depth--
            }
            i++
        }
        if (top.alternated) top.held.forEach { it.always = false }
        return found to groups
    }
}
