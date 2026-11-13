package org.drivine.cypher.sql

/** What a regular expression of Java's writes, as `=~` reads it, read from its text where the statement gives it. */
internal object Patterns {

    /**
     * A pattern of `=~` that matches no text: a character before the text's start. The SQL stores ask it in place of a
     * pattern Java matches nothing with ([dangling]); on SQLite each library's regular expressions read it, so it is among
     * the compiler's patterns the tests check on every target (`DrivinePatterns`).
     */
    const val NO_MATCH = "a^"

    /**
     * A backreference, `\1` and on: its number, whether it is written in a character class, whether its group has
     * closed before it, as it must for Java to match it, and whether the pattern can match without it: in an alternative
     * (`(a)|\9`), in a lookaround, or where a quantifier lets it, or a group that holds it, match no times (`\1?(a)`).
     */
    data class Backreference(val number: Int, val inClass: Boolean, val closed: Boolean = false, val optional: Boolean = false)

    /**
     * The groups [pattern] captures, as Java numbers them: each `(` that no backslash escapes, out of a class and a quote,
     * that isn't `(?`, but for a named group, `(?<name>`.
     */
    fun groups(pattern: String): Int = scan(pattern).second

    /**
     * Whether [pattern] writes a backreference, out of a class, to a group that hasn't closed before it: one it doesn't have,
     * one that follows (`\1(a)`) or one that holds it (`(a\1)`). Java's matches nothing there, where Postgres refuses the
     * pattern.
     */
    fun dangling(pattern: String): Boolean = scan(pattern).first.any { !it.inClass && !it.closed }

    /**
     * Each backreference [pattern] writes, as Java reads it: a backslash that no backslash escapes, then a digit 1 to 9, and
     * each digit after it while the number still names a group opened before it, so `\10` is the tenth group's where ten
     * have opened, else the first's and a `0`. One quoted (`\Q…\E`) is none. A class is opened by `[` and closed by a `]`
     * that isn't its first character, and may hold another, as Java's do.
     */
    fun backreferences(pattern: String): List<Backreference> = scan(pattern).first

    /**
     * A group open as the pattern is read, the whole pattern the first: its number, null for one that captures nothing;
     * whether it is a lookaround; whether it holds an alternative; and the backreferences it holds, by their place.
     */
    private class Group(val number: Int?, val lookaround: Boolean, var alternated: Boolean = false, val held: MutableList<Int> = mutableListOf())

    /** Whether a quantifier at [at] in [pattern] lets what it follows match no times: `?`, `*` or `{0`. */
    private fun none(pattern: String, at: Int) =
        pattern.getOrNull(at) == '?' || pattern.getOrNull(at) == '*' || Regex("""\{0+[,}]""").find(pattern, at)?.range?.first == at

    private fun scan(pattern: String): Pair<List<Backreference>, Int> {
        var groups = 0
        // The groups open at each point; those closed, by number; and the backreferences the pattern can match without.
        val open = ArrayDeque(listOf(Group(null, false)))
        val closed = mutableSetOf<Int>()
        val optional = mutableSetOf<Int>()
        val found = mutableListOf<Backreference>()
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
                        // Java refuses one in a class ([SqlCypherCompiler.checkPattern]): the digits after it there are left.
                        while (depth == 0 && pattern.getOrNull(i) in '0'..'9' && number * 10 + (pattern[i] - '0') <= groups) {
                            number = number * 10 + (pattern[i++] - '0')
                        }
                        if (depth == 0) open.last().held += found.size
                        if (depth == 0 && none(pattern, i)) optional += found.size
                        found += Backreference(number, depth > 0, number in closed)
                        continue
                    }
                    i += 2
                    continue
                }
                c == '(' && depth == 0 -> {
                    val named = pattern.startsWith("?<", i + 1) && pattern.getOrNull(i + 3)?.isLetter() == true
                    val lookaround = listOf("?=", "?!", "?<=", "?<!").any { pattern.startsWith(it, i + 1) }
                    open.addLast(Group(if (!pattern.startsWith("?", i + 1) || named) ++groups else null, lookaround))
                }
                c == '|' && depth == 0 -> open.last().alternated = true
                c == ')' && depth == 0 && open.size > 1 -> {
                    val group = open.removeLast()
                    group.number?.let(closed::add)
                    if (group.alternated || group.lookaround || none(pattern, i + 1)) optional += group.held
                    open.last().held += group.held
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
        if (open.first().alternated) optional += open.first().held
        return found.mapIndexed { k, it -> if (k in optional) it.copy(optional = true) else it } to groups
    }
}
