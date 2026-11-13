package org.drivine.cypher.sql

/**
 * The backreferences a regular expression of `=~` writes, read as Java reads them, and those of them the SQL stores answer as
 * Java does (#236). The stores hold a pattern to the whole text in a group of their own, and each numbers and captures groups
 * its own way past the simplest forms, so the forms they answer are a small closed set, and every other is refused by name.
 */
internal object Patterns {

    /**
     * A pattern that finds a backreference, `\1` to `\9`, that no backslash escapes, as each store's regular expressions read
     * it: a pattern worked out as the statement runs, which the compiler can't read, is refused then where it writes one
     * (`SqlCypherCompiler.holdsBackreference`). On SQLite each library's regular expressions read it, so it is among the
     * compiler's patterns the tests check on every target (`DrivinePatterns`).
     */
    const val BACKREFERENCE = """(^|[^\\])(\\\\)*\\[1-9]"""

    /** What a backreference the stores answer is, said where one is refused. */
    private const val ANSWERED = "the SQL stores answer a backreference as Java does only where it is \\1 to \\8, with no digit after it, " +
        "outside a character class, to a simple group that closed before it: one that holds no quantifier, alternative or group, " +
        "is not repeated, and is in no alternative or repeated group, in a pattern with no lookaround, no class within a class and no flag x."

    /**
     * Why the SQL stores can't answer [pattern]'s backreferences as Java does, or null where they can, or it writes none. A
     * backreference is answered where it is `\1` to `\8`, outside a character class, with no digit after it, to a simple group
     * that closed before it and takes part in every match ([ANSWERED]). Past that, each store reads a group its own way:
     * Postgres numbers no group in a lookaround and reads no backreference there; it and Oracle read no class within a class,
     * so a `(` in one may open a group for them; a group repeated, or one that holds a quantifier or an alternative, captures
     * what each engine last kept, which differ where it may match nothing (`(a?)+b\1`); and under the flag `x` a space may
     * stand between a group and its quantifier. So none of those is answered, rather than each told apart.
     */
    fun refusal(pattern: String): String? {
        val read = scan(pattern)
        val why = read.found.firstNotNullOfOrNull { it.refused(read) } ?: return null
        return "The regular expression '$pattern' writes a backreference ${why.first}, ${why.second}: $ANSWERED"
    }

    /** Whether [pattern] writes a backreference, as Java reads one. */
    fun holdsBackreference(pattern: String): Boolean = scan(pattern).found.isNotEmpty()

    /** A backreference: its number, whether it is in a character class, whether a digit follows it, and the groups closed before it. */
    private class Backreference(val number: Int, val inClass: Boolean, val digitAfter: Boolean, val closed: Set<Int>) {
        /** Why it is refused, as `\n` and the reason, or null where it is answered. */
        fun refused(read: Read): Pair<String, String>? {
            val group = read.groups.getOrNull(number - 1)
            val why = when {
                inClass -> "in a character class, which Java refuses"
                number > 8 -> "past \\8"
                digitAfter -> "with a digit after it"
                read.flagX -> "in a pattern with the flag x"
                read.lookaround -> "in a pattern with a lookaround"
                read.classInClass -> "in a pattern with a class within a class"
                group == null || number !in closed -> "to a group that hasn't closed before it"
                !group.simple -> "to a group that holds a quantifier, an alternative or a group, or is repeated"
                !group.always -> "to a group in an alternative or a repeated group"
                else -> null
            }
            return why?.let { "\\$number" to it }
        }
    }

    /**
     * A capturing group: whether it is simple, holding no quantifier, alternative or group and not repeated itself; and whether
     * it takes part in every match, in no alternative and no repeated group, which is known once the pattern is read.
     */
    private class Group {
        var simple = true
        var always = true
    }

    /** A group open as the pattern is read: the capturing group it is, if it captures; whether it alternates, and what it holds. */
    private class Open(val group: Group?) {
        var alternated = false
        var quantified = false
        var nested = false
        val held = mutableListOf<Group>()
    }

    /** What [scan] reads of a pattern. */
    private class Read(
        val found: List<Backreference>, val groups: List<Group>, val flagX: Boolean, val lookaround: Boolean, val classInClass: Boolean,
    )

    /** A flag group that turns `x` on: `(?x)`, `(?ix)`, `(?x:…)`, not `(?i-x)`. */
    private val FLAG_X = Regex("""\(\?[a-zA-Z]*x[a-zA-Z]*(-[a-zA-Z]*)?[:)]""")

    /**
     * Each backreference [pattern] writes, and its capturing groups by number, read as Java reads them: a backslash that no
     * backslash escapes, then a digit 1 to 9, and each digit after it while the number still names a group opened before it.
     * One quoted (`\Q…\E`) is none, nor `\cX`'s character. A class is opened by `[` and closed by a `]` that isn't its first
     * character, and may hold another, as Java's do.
     */
    private fun scan(pattern: String): Read {
        val groups = mutableListOf<Group>()
        val closed = mutableSetOf<Int>()
        val found = mutableListOf<Backreference>()
        val stack = ArrayDeque(listOf(Open(null)))
        var lookaround = false
        var classInClass = false
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
                c == '[' -> {
                    if (depth > 0) classInClass = true
                    depth++
                    // A class's first character, `]` or `^]`, is itself.
                    if (pattern.startsWith("^]", i + 1)) i += 2 else if (pattern.startsWith("]", i + 1)) i += 1
                }
                c == ']' && depth > 0 -> depth--
                depth > 0 -> {}
                c == '(' -> {
                    stack.last().nested = true
                    if (listOf("?=", "?!", "?<=", "?<!").any { pattern.startsWith(it, i + 1) }) lookaround = true
                    val named = pattern.startsWith("?<", i + 1) && pattern.getOrNull(i + 3)?.isLetter() == true
                    val group = if (!pattern.startsWith("?", i + 1) || named) Group().also(groups::add) else null
                    stack.addLast(Open(group))
                    // A group's own syntax, `(?:`, `(?i)`, `(?<name>`, is no quantifier.
                    if (pattern.startsWith("?", i + 1)) i++
                }
                c == '|' -> stack.last().alternated = true
                c in "?*+{" -> stack.last().quantified = true
                c == ')' && stack.size > 1 -> {
                    val done = stack.removeLast()
                    done.group?.let { group ->
                        closed += groups.indexOf(group) + 1
                        if (done.alternated || done.quantified || done.nested) group.simple = false
                    }
                    val repeated = pattern.getOrNull(i + 1)?.let { it in "?*+{" } == true
                    done.group?.takeIf { repeated }?.simple = false
                    // What an alternative or a repeated group holds takes part in no match of its own.
                    if (done.alternated || repeated) done.held.forEach { it.always = false }
                    stack.last().held += done.held + listOfNotNull(done.group)
                }
            }
            i++
        }
        if (stack.first().alternated) stack.first().held.forEach { it.always = false }
        return Read(found, groups, FLAG_X.containsMatchIn(pattern), lookaround, classInClass)
    }
}
