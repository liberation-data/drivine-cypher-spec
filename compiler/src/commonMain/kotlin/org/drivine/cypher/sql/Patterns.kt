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
     * no times, nor in a group that is. A pattern with the flag `x` is refused whole: there a space or a `#` comment may stand
     * between a group and its quantifier, `(?x)(a) ?\1`, which the reading here doesn't step over. Postgres reads no
     * backreference in a lookaround, and no group there as one that captures, so the groups after it are numbered one less
     * than Java numbers them: a backreference in a lookaround is refused, and one past a group in a lookaround,
     * `(?=(a))(a)\2`. A group repeated that may match nothing, `(a?)+`, is one each engine repeats its own way once it has
     * matched nothing, so what it last captured, `(a?)+b\1` over `aba`, is too. And where a class holds `(`, `)` or `|`, the
     * stores may read them outside it, and so other groups than Java's: Postgres reads no class within a class, `[[a](b)]`,
     * and Oracle, as POSIX does, may read a backslash in one as itself, `[\](b)]`.
     */
    fun refusal(pattern: String): String? {
        val (found, groups, spaced, classed) = scan(pattern)
        for (it in found) {
            val why = when {
                spaced -> "in a pattern with the flag x, which reads spaces and # comments apart"
                it.inClass -> "in a character class, which Java refuses"
                it.number > 8 -> "past \\8"
                it.digitAfter -> "with a digit after it"
                classed -> "in a pattern with a character class that holds (, ) or |, which Postgres and Oracle may read outside it"
                it.number !in it.closed -> "to a group that hasn't closed before it"
                !groups[it.number - 1].always -> "to a group that may take no part in a match: in an alternative, a lookaround, or under a quantifier that lets it match no times"
                it.inLookaround -> "in a lookaround, where Postgres reads none"
                groups.take(it.number).any { group -> group.inLookaround } -> "past a group in a lookaround, which Postgres doesn't number"
                groups[it.number - 1].let { group -> group.empty && group.repeated } -> "to a group repeated that may match nothing, which each store repeats its own way"
                else -> null
            }
            if (why != null) {
                return "The regular expression '$pattern' writes a backreference, \\${it.number}, $why: the SQL stores answer a " +
                    "backreference as Java does only where it is \\1 to \\8, with no digit after it, to a group that closed before it and takes part in every match, " +
                    "not repeated where it may match nothing, outside a lookaround and past no group in one, in a pattern with no class that holds (, ) or |."
            }
        }
        return null
    }

    /** Whether [pattern] writes a backreference, as Java reads one. */
    fun holdsBackreference(pattern: String): Boolean = scan(pattern).found.isNotEmpty()

    /**
     * A backreference: its number, whether it is in a character class, whether a digit follows it, the groups closed before
     * it, and whether it is in a lookaround.
     */
    private class Backreference(val number: Int, val inClass: Boolean, val digitAfter: Boolean, val closed: Set<Int>, val inLookaround: Boolean)

    /**
     * A capturing group, and what is known of it once the pattern is read: whether it takes part in every match (none of it,
     * nor any group that holds it, is in an alternative or a lookaround, or under a quantifier that lets it match no times);
     * whether it may match nothing; and whether it, or a group that holds it, is repeated.
     */
    private class Group(val inLookaround: Boolean) {
        var always = true
        var empty = false
        var repeated = false
    }

    /**
     * A group open as the pattern is read: the capturing group it is, if it captures; whether it alternates; what it holds;
     * whether the alternative being read must match a character; and whether one read already needn't.
     */
    private class Open(val group: Group?, val parent: Open?, val lookaround: Boolean) {
        /** Whether this group, or one that holds it, is a lookaround. */
        val inLookaround: Boolean = lookaround || parent?.inLookaround == true
        var alternated = false
        val held = mutableListOf<Group>()
        var needs = false
        var empty = false
    }

    /** What [scan] reads: the backreferences, the capturing groups, whether the flag `x` is set, and whether a class holds `(`, `)` or `|`. */
    private class Scan(val found: List<Backreference>, val groups: List<Group>, val spaced: Boolean, val classed: Boolean) {
        operator fun component1() = found
        operator fun component2() = groups
        operator fun component3() = spaced
        operator fun component4() = classed
    }

    /** Whether a quantifier at [at] in [pattern] lets what it follows match no times: `?`, `*` or `{0`. */
    private fun none(pattern: String, at: Int) =
        pattern.getOrNull(at) == '?' || pattern.getOrNull(at) == '*' || Regex("""\{0+[,}]""").find(pattern, at)?.range?.first == at

    /** The quantifier `{n}`, `{n,}` or `{n,m}` at [at] in [pattern], or null. */
    private fun counted(pattern: String, at: Int) = Regex("""\{([0-9]+)(,([0-9]*))?}""").find(pattern, at)?.takeIf { it.range.first == at }

    /** Whether a quantifier at [at] in [pattern] lets what it follows match more than once: `+`, `*`, or a count past 1. */
    private fun repeats(pattern: String, at: Int) = pattern.getOrNull(at) == '+' || pattern.getOrNull(at) == '*' ||
        counted(pattern, at)?.groupValues?.let { (_, n, comma, m) -> (if (comma.isEmpty()) n else m).let { it.isEmpty() || it.toIntOrNull() != 1 && it.toIntOrNull() != 0 } } == true

    /**
     * Each backreference [pattern] writes, and its capturing groups by number, read as Java reads them: a backslash that no
     * backslash escapes, then a digit 1 to 9, and each digit after it while the number still names a group opened before it.
     * One quoted (`\Q…\E`) is none, nor `\cX`'s character. A class is opened by `[` and closed by a `]` that isn't its first
     * character, and may hold another, as Java's do. Whether a group may match nothing is read conservatively: only a
     * character, a class, `.`, or an escape of one character (`\t`, `\d`, `\.`) must match one, and none under a quantifier
     * that lets it match no times.
     */
    private fun scan(pattern: String): Scan {
        val groups = mutableListOf<Group>()
        val closed = mutableSetOf<Int>()
        val found = mutableListOf<Backreference>()
        val top = Open(null, null, false)
        var open = top
        var depth = 0
        var spaced = false
        var classed = false
        var i = 0
        // What ends at [end] must match a character, unless a quantifier there lets it match none.
        fun atom(end: Int) {
            if (depth == 0 && !none(pattern, end)) open.needs = true
        }
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
                        atom(i + 3)
                        i += 3
                        continue
                    }
                    if (next in '1'..'9') {
                        var number = next - '0'
                        i += 2
                        while (depth == 0 && pattern.getOrNull(i) in '0'..'9' && number * 10 + (pattern[i] - '0') <= groups.size) {
                            number = number * 10 + (pattern[i++] - '0')
                        }
                        found += Backreference(number, depth > 0, pattern.getOrNull(i) in '0'..'9', closed.toSet(), open.inLookaround)
                        continue
                    }
                    if (next in "tnrfaedDsSwWhHvV" || !next.isLetterOrDigit()) atom(i + 2)
                    i += 2
                    continue
                }
                (c == '(' || c == ')' || c == '|') && depth > 0 -> classed = true
                c == '(' -> {
                    val named = pattern.startsWith("?<", i + 1) && pattern.getOrNull(i + 3)?.isLetter() == true
                    val lookaround = listOf("?=", "?!", "?<=", "?<!").any { pattern.startsWith(it, i + 1) }
                    val group = if (!pattern.startsWith("?", i + 1) || named) Group(lookaround || open.inLookaround).also(groups::add) else null
                    if (pattern.startsWith("?", i + 1) && 'x' in pattern.drop(i + 2).takeWhile { it.isLetter() }) spaced = true
                    open = Open(group, open, lookaround)
                    // A group's own syntax, `?:`, `?=`, `?<name>` or `?i`, is no character to match.
                    Regex("""\?(<?[=!]|<[A-Za-z][A-Za-z0-9]*>|[A-Za-z-]*:?)""").find(pattern, i + 1)?.takeIf { it.range.first == i + 1 }?.let { i = it.range.last }
                }
                c == '|' -> {
                    if (!open.needs) open.empty = true
                    open.needs = false
                    open.alternated = true
                }
                c == ')' && open.parent != null -> {
                    val done = open
                    open = done.parent!!
                    if (!done.needs) done.empty = true
                    done.group?.let { closed += groups.indexOf(it) + 1; it.empty = done.empty }
                    if (!done.lookaround && !done.empty) atom(i + 1)
                    // An alternative in a group holds what's in it, not the group itself; a lookaround or a quantifier, both.
                    if (done.alternated) done.held.forEach { it.always = false }
                    val held = done.held + listOfNotNull(done.group)
                    if (done.lookaround || none(pattern, i + 1)) held.forEach { it.always = false }
                    if (repeats(pattern, i + 1)) held.forEach { it.repeated = true }
                    open.held += held
                }
                c == '[' -> {
                    depth++
                    // A class's first character, `]` or `^]`, is itself.
                    if (pattern.startsWith("^]", i + 1)) i += 2 else if (pattern.startsWith("]", i + 1)) i += 1
                }
                c == ']' && depth > 0 -> {
                    depth--
                    atom(i + 1)
                }
                depth > 0 || c in "^$?*+" -> {}
                // A count, `{2,3}`, is a quantifier, not characters to match.
                c == '{' && counted(pattern, i) != null -> {
                    i = counted(pattern, i)!!.range.last + 1
                    continue
                }
                else -> atom(i + 1)
            }
            i++
        }
        if (top.alternated) top.held.forEach { it.always = false }
        return Scan(found, groups, spaced, classed)
    }
}
