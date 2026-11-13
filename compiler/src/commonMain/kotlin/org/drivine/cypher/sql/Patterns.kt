package org.drivine.cypher.sql

/** What a regular expression of Java's writes, as `=~` reads it, read from its text where the statement gives it. */
internal object Patterns {

    /**
     * A backreference, `\1` to `\9`: its number, whether it is written in a character class, and whether its group has
     * closed before it, as it must for Java to match it.
     */
    data class Backreference(val number: Int, val inClass: Boolean, val closed: Boolean = false)

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
     * Each backreference [pattern] writes, as Java reads it: a backslash that no backslash escapes, then a digit 1 to 9. One
     * quoted (`\Q…\E`) is none. A class is opened by `[` and closed by a `]` that isn't its first character, and may hold
     * another, as Java's do.
     */
    fun backreferences(pattern: String): List<Backreference> = scan(pattern).first

    private fun scan(pattern: String): Pair<List<Backreference>, Int> {
        var groups = 0
        // The groups open at each point, by number, null for one that captures nothing; and those closed.
        val open = ArrayDeque<Int?>()
        val closed = mutableSetOf<Int>()
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
                    if (next in '1'..'9') found += Backreference(next - '0', depth > 0, (next - '0') in closed)
                    i += 2
                    continue
                }
                c == '(' && depth == 0 -> {
                    val named = pattern.startsWith("?<", i + 1) && pattern.getOrNull(i + 3)?.isLetter() == true
                    if (!pattern.startsWith("?", i + 1) || named) open.addLast(++groups) else open.addLast(null)
                }
                c == ')' && depth == 0 -> open.removeLastOrNull()?.let(closed::add)
                c == '[' -> {
                    depth++
                    // A class's first character, `]` or `^]`, is itself.
                    if (pattern.startsWith("^]", i + 1)) i += 2 else if (pattern.startsWith("]", i + 1)) i += 1
                }
                c == ']' && depth > 0 -> depth--
            }
            i++
        }
        return found to groups
    }
}
