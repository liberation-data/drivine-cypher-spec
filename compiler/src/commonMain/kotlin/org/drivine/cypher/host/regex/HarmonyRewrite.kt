package org.drivine.cypher.host.regex

import org.drivine.DrivineInternalApi

/**
 * A Java regular expression, read as `Pattern.compile(expression, UNICODE_CHARACTER_CLASS)` reads it, written for
 * `kotlin.text.Regex` on Kotlin/Native and Kotlin/Wasm so that it matches what Java's does ([HarmonyPattern]). Both
 * targets' `Regex` is the stdlib's own engine (`nativeWasmMain/kotlin/text/regex`, a port of Apache Harmony's), not the
 * platform's: it reads Java's syntax, but not as Java does with `UNICODE_CHARACTER_CLASS`, and has bugs of its own
 * (measured against Java in #108; `RegexOracleTest` checks every target against Java's answers):
 *
 * - Its class escapes (`\w`, `\d`, `\s`, `\b`) and POSIX classes (`\p{Alnum}` …) are ASCII's, its general categories
 *   (`\p{L}` …) wrong past the BMP, and it knows few of Java's property names: each is written out as the ranges Java's
 *   holds ([JavaClasses]), and a name Java doesn't know refused as Java refuses it.
 * - Its case-insensitivity is not Java's ([JavaCase]): under `(?i)` each literal and class is written as the code points
 *   Java's takes, and Harmony is given no `(?i)`.
 * - Its classes' nesting, intersections and negation are not Java's (`[[^a]b]`, `[^a&&b]`): each class is worked out here
 *   as the set of code points Java's holds, and written as one class of ranges.
 * - Its `.` misses a match after a line break in a search (`.+` in "\na"): written as the class Java's `.` is.
 * - Its `$` and `\Z` match between a `\r` and a `\n`, and a quantifier on a lookaround crashes it: each written so it
 *   doesn't.
 * - It reads `(?x)`'s whitespace and comments otherwise: they are taken out here.
 * - It refuses `\x{…}`, and a back reference to a group the pattern never opens (which never matches in Java): written as
 *   the code point, and as `(?!)`.
 * - Two bugs in Kotlin 2.2.20 with code points past the BMP in a pattern: see [astral] and [write].
 * - Its repetition of what has no width, its lazy repetition of a group, and its repetition of a
 *   non-capturing group (which can overflow the stack) aren't Java's: see [Writer.zeroWidthQuantified],
 *   [Writer.groupQuantified] and [Writer]'s wrap.
 * - It measures no lookbehind's length as Java does: [Behind] does, refusing what Java refuses.
 *
 * What it can't make match as Java's does, it refuses by name ([UnsupportedPatternException]): `\G`, `\X`, `\N{…}`,
 * `\b{g}`, a back reference under `(?i)` (Java folds each code point of it, Harmony its own way), a back reference to a
 * group the pattern opens only after it (which Harmony refuses, and Java matches in a repetition), and `(?-U)` and `(?-u)`,
 * which turn off what drivine4j's patterns always have on; a lazy quantifier on a group but `*?` and `??`; a possessive
 * quantifier on a group; and a pattern
 * written as more than [MAX_WRITTEN] characters.
 */
internal object HarmonyRewrite {

    /** [java], written for kotlin.text.Regex on Kotlin/Native and Kotlin/Wasm; it throws where it can't be. */
    fun rewrite(java: String): String = rewritten(java).text

    /**
     * [java] written for kotlin.text.Regex ([text]), for [StackRoom]: the most of Harmony's nodes on a path through it
     * ([path]), and how deep its lookarounds and atomic groups nest at most ([scans]), each of which Harmony matches in
     * frames of its own above those of the path that reaches it.
     */
    class Rewritten(val text: String, val path: Int, val scans: Int, val hidden: Set<Int> = emptySet())

    /** [java], written as [rewrite] writes it, with its path. */
    fun rewritten(java: String): Rewritten {
        val unquoted = unquote(java)
        val first = Writer(unquoted, emptySet())
        val written = first.write()
        // Written again with the groups to wrap, where there are any: which they are is known only past each.
        return if (first.wrapping.isEmpty()) written else Writer(unquoted, first.wrapping).write()
    }

    /**
     * The most characters a pattern is written as: each class is written out as the ranges Java's holds (`\w` some 7,500
     * characters, `\b` 30,000), and Harmony takes about 40 bytes of memory and 60 ns to compile each (`\b` 1,000 times
     * is 30 million, 1.2 GB and two seconds), where Java's takes none.
     */
    const val MAX_WRITTEN = 2_000_000

    /**
     * [p] with each `\Q…\E`'s characters written as literals, as Java's Pattern.RemoveQEQuoting writes them before it reads
     * the pattern: a letter or a character past ASCII itself, a digit itself (`\x3` before the first, so no escape before
     * the quote reads it), any other character after a backslash. So a quoted character is a literal of a run, or a class's
     * range (`[\Qa\E-z]`), as it is in Java.
     */
    private fun unquote(p: String): String {
        var i = 0
        while (i < p.length - 1 && !(p[i] == '\\' && p[i + 1] == 'Q')) i += if (p[i] == '\\') 2 else 1
        if (i >= p.length - 1) return p
        val out = StringBuilder(p.substring(0, i))
        i += 2
        var quoted = true
        var begin = true
        while (i < p.length) {
            val c = p[i++]
            when {
                c >= '\u0080' || c in 'a'..'z' || c in 'A'..'Z' -> out.append(c)
                c in '0'..'9' -> out.append(if (begin) "\\x3" else "").append(c)
                c != '\\' -> out.append(if (quoted) "\\" else "").append(c)
                quoted -> if (p.getOrNull(i) == 'E') {
                    i++
                    quoted = false
                } else {
                    out.append("\\\\")
                }
                p.getOrNull(i) == 'Q' -> {
                    i++
                    quoted = true
                    begin = true
                    continue
                }
                else -> {
                    out.append(c)
                    if (i < p.length) out.append(p[i++])
                }
            }
            begin = false
        }
        return out.toString()
    }

    private class Flags(val i: Boolean = false, val s: Boolean = false, val m: Boolean = false, val x: Boolean = false, val d: Boolean = false) {
        fun with(flag: Char, on: Boolean): Flags = when (flag) {
            'i' -> Flags(on, s, m, x, d)
            's' -> Flags(i, on, m, x, d)
            'm' -> Flags(i, s, on, x, d)
            'x' -> Flags(i, s, m, on, d)
            'd' -> Flags(i, s, m, x, on)
            else -> this
        }
    }

    /** A literal code point, and how it is written where nothing need be made of it. */
    private class Literal(val cp: Int, val text: String)

    /**
     * [wrap]: the groups, by the order they open in, to write in a capturing group of their own, which [Rewritten.hidden]
     * names: each non-capturing group that is repeated. Harmony's NonCapturingJointSet keeps where the group began in the
     * match, and its end node takes the length matched from it, which a backtrack into the group (another alternative, a
     * quantifier giving back) doesn't set back: so its quantifier takes an empty match for a length, and repeats the group at
     * the same place until the stack overflows (`(?:b|)*a` and `(?:(|))*a` in "b", Kotlin 2.2.20). A capturing group's
     * quantifier reads the group's own start and end, which are right.
     */
    private class Writer(private val p: String, private val wrap: Set<Int>) {
        /** The groups found to wrap, for the second writing. */
        val wrapping = mutableSetOf<Int>()

        /** The groups opened so far, and of each open its number in that order and where in [out] it begins. */
        private var opens = 0
        private val openings = ArrayDeque<Int>()
        private val starts = ArrayDeque<Int>()

        /**
         * Where in [out] the atom just written begins, where it is one of no width (`^`, `\b`, a lookaround) ([zeroAt]) or a
         * group ([groupAt]); else -1.
         */
        private var zeroAt = -1
        private var groupAt = -1

        /** Whether the group just written is `\R`'s, which holds no capturing group, so may be written twice. */
        private var line = false

        /** Harmony's capturing groups so far, Java's and wraps; each Java group's number in Harmony's; and the wraps'. */
        private var harmonyGroups = 0
        private val harmonyNumbers = mutableListOf(0)
        private val hidden = mutableSetOf<Int>()

        private val out = StringBuilder()
        private val stack = ArrayDeque<Flags>()

        /** What closes each group open, as [stack] holds its flags: `))` for a lookaround, written in a group of its own. */
        private val closers = ArrayDeque<String>()
        private var flags = Flags()
        private var at = 0

        /** The capturing groups opened so far, as Java counts them for a back reference. */
        private var groups = 0

        /**
         * The literals read since the last other token under `(?i)`, which Java reads as one atom: one alone is a single, two
         * or more a slice, which take different code points ([JavaCase]).
         */
        private val run = mutableListOf<Literal>()

        /** The smallest number of a back reference to a group not yet opened where it is, 0 where none is: see [backReference]. */
        private var forward = 0

        /**
         * Harmony's nodes on the longest path through the pattern written so far, as [StackRoom] counts them: in the group
         * open, the alternative being read ([nodes]) and the longest of those before it ([longest]); of each group around
         * it, the same, saved in [outer] as the group opened.
         */
        private var nodes = 0
        private var longest = 0
        private val outer = ArrayDeque<Int>()

        /** The lookarounds and atomic groups open, whether each group open is one, and the most open at once, of [Rewritten.scans]. */
        private var scans = 0
        private val scanning = ArrayDeque<Boolean>()
        private var deepestScans = 0

        /**
         * For each group open, the lookbehind whose length Java measures the tokens in it for ([Behind]): the group's own where
         * it is a lookbehind, none in a lookahead, else its enclosing group's. And what kind each group is: `(` a group, `>` an
         * atomic group, `=` a lookahead, `<` a lookbehind.
         */
        private val behinds = ArrayDeque<Behind?>()
        private val kinds = ArrayDeque<Char>()

        /** Whether the last token was a quantifier, so a `?` or `+` after it is its lazy or possessive mark. */
        private var quantified = false

        /** [token] to the lookbehind measuring where it is. */
        private fun java(token: Behind.Token) {
            behinds.lastOrNull()?.tokens?.add(token)
            quantified = token is Behind.Quantifier
        }

        /** A group of [kind] opened, measured by [behind]. */
        private fun opened(kind: Char, behind: Behind?) {
            openings.addLast(opens)
            starts.addLast(out.length)
            if (opens++ in wrap) {
                out.append('(')
                hidden += ++harmonyGroups
            }
            kinds.addLast(kind)
            behinds.addLast(behind)
        }

        /** [n] more nodes on the path: a node a token is in Harmony, or the few one written as a lookaround is. */
        private fun node(n: Int = 1) {
            nodes += n
            if (out.length > MAX_WRITTEN) throw UnsupportedPatternException("a pattern written as more than $MAX_WRITTEN characters")
        }

        private fun openPath() {
            scanning.addLast(false)
            outer.addLast(nodes)
            outer.addLast(longest)
            nodes = 0
            longest = 0
        }

        /** The group's path closed: its longest alternative, and the group itself, on its enclosing alternative's path. */
        private fun closePath() {
            if (outer.isEmpty()) return
            if (scanning.removeLast()) scans--
            val inner = maxOf(nodes, longest) + 2
            longest = outer.removeLast()
            nodes = outer.removeLast() + inner
        }

        fun write(): Rewritten {
            while (at < p.length) atom()
            flush()
            if (forward in 1..groups) throw UnsupportedPatternException("a back reference to a later group")
            while (outer.isNotEmpty()) closePath()
            node(0)
            return Rewritten(out.toString(), maxOf(nodes, longest), deepestScans, hidden)
        }

        /** The group just opened is a lookaround or an atomic group; or with [open] false, one is written alone here (`\b`). */
        private fun scan(open: Boolean = true) {
            if (open) {
                scanning[scanning.lastIndex] = true
                scans++
            }
            deepestScans = maxOf(deepestScans, scans + if (open) 0 else 1)
        }

        private fun atom() {
            val c = p[at]
            if (flags.x && spaceOrComment(c)) {
                // Java reads past them, within a run of literals too.
                at = past(at)
                return
            }
            val zero = zeroAt
            val group = groupAt
            val twice = line
            zeroAt = -1
            groupAt = -1
            line = false
            if (c in "*+?" || c == '{' && QUANTIFIER.matchAt(p, at) != null) {
                if (zero >= 0) return zeroWidthQuantified(zero)
                if (group >= 0) return groupQuantified(group, twice)
            }
            if (c == '\\') {
                val literal = charEscape()
                if (literal != null) {
                    literal(literal)
                } else {
                    flush()
                    escape()
                }
                return
            }
            when (c) {
                '*', '+', '?' -> {
                    flushQuantified()
                    if (quantified && c != '*') {
                        (behinds.lastOrNull()?.tokens?.lastOrNull() as? Behind.Quantifier)?.mark = c
                        quantified = false
                    } else {
                        java(Behind.Quantifier(if (c == '+') 1 else 0, if (c == '?') 1 else Behind.MAX_REPS))
                    }
                    out.append(c)
                    node()
                    at++
                }
                '{' -> {
                    val quantifier = QUANTIFIER.matchAt(p, at)
                    if (quantifier != null) {
                        flushQuantified()
                        val bounds = quantifier.value.trim('{', '}').split(',')
                        val min = bounds[0].toIntOrNull() ?: Behind.MAX_REPS
                        val max = if (bounds.size == 1) min else bounds[1].takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: Behind.MAX_REPS }
                        java(Behind.Quantifier(min, max ?: Behind.MAX_REPS, open = max == null))
                        out.append(quantifier.value)
                        node()
                        at += quantifier.value.length
                    } else {
                        // Java refuses a { before anything but a digit; past the digit, (?x)'s whitespace, which is taken out.
                        if (p.getOrNull(at + 1) !in '0'..'9') throw IllegalArgumentException("Illegal repetition")
                        flush()
                        out.append(c)
                        at++
                    }
                }
                '[' -> {
                    flush()
                    characterClass()
                }
                '(' -> {
                    flush()
                    group()
                }
                ')' -> {
                    flush()
                    val closer = closers.removeLastOrNull() ?: ")"
                    val kind = kinds.removeLastOrNull()
                    val opening = openings.removeLastOrNull()
                    val start = starts.removeLastOrNull()
                    if (kind == '(' && p.getOrNull(past(at + 1))?.let { it in "*+?" || it == '{' && QUANTIFIER.matchAt(p, past(at + 1)) != null } == true) {
                        wrapping += opening!!
                    }
                    val behind = behinds.removeLastOrNull()
                    if (kind == '(' || kind == 'c' || kind == '>') java(Behind.Close)
                    // A lookbehind Java's never matches: a positive one fails, and a negative one holds.
                    val never = kind == '<' && behind!!.never()
                    if (never && !behind!!.negative) out.insert(behind.at, "(?!)")
                    out.append(if (never && behind!!.negative) ")|)" else closer)
                    if (opening in wrap) out.append(')')
                    if (kind == '=' || kind == '<') zeroAt = start!!
                    if (kind == '(' || kind == 'c' || kind == '>') groupAt = start!!
                    quantified = false
                    flags = stack.removeLastOrNull() ?: flags
                    closePath()
                    at++
                }
                '.' -> {
                    flush()
                    java(Behind.Leaf.CHAR)
                    node()
                    out.append(
                        when {
                            flags.s -> "[\\s\\S]"
                            flags.d -> "[^\\n]"
                            else -> "[^\\n\\r\\u0085\\u2028\\u2029]"
                        },
                    )
                    at++
                }
                '$' -> {
                    flush()
                    java(Behind.Leaf.ZERO)
                    zeroAt = out.length
                    out.append(dollar("$"))
                    node(DOLLAR)
                    scan(open = false)
                    at++
                }
                '^' -> {
                    flush()
                    java(Behind.Leaf.ZERO)
                    zeroAt = out.length
                    out.append(c)
                    node()
                    at++
                }
                '|' -> {
                    flush()
                    java(Behind.Alternative)
                    out.append(c)
                    longest = maxOf(longest, nodes)
                    nodes = 0
                    at++
                }
                else -> {
                    val cp = codePoint(p, at)
                    at += if (cp > 0xFFFF) 2 else 1
                    literal(Literal(cp, if (cp > 0xFFFF) astral(cp) else c.toString()))
                }
            }
        }

        /**
         * The quantifier at [at], of the atom of no width written at [from]: as Java repeats one, which matches it once and
         * stops, as it makes no progress. Harmony's repetition of one (`^{2}`, `^++`, `\B{1,2}+`, `$+?` …) isn't Java's: so
         * with no minimum it is written `?` (`??` lazy), and with one, not at all.
         */
        private fun zeroWidthQuantified(from: Int) {
            val quantifier = quantifier()
            if (quantifier.min == 0) {
                out.insert(from, "(?:")
                out.append(if (quantifier.mark == '?') ")??" else ")?")
            }
        }

        /**
         * The quantifier at [at], of the group written at [from]. Harmony's lazy repetition of a group counts its repetitions
         * wrongly, as it doesn't count back one that fails (`(bc)+?` in "xbc" finds an empty match, `(?:bc){2}?` matches one
         * `bc`): `*?` counts none, and `??` is written as the alternatives it is, `(?:|…)`; any other is refused. Its
         * possessive one stops at an empty repetition before the minimum, where Java counts it (`(a?){1,2}+` in "b"), and
         * the same greedy one in an atomic group, `(?>(a?){1,2})b`, misses Java's match in "tab" from the second character
         * (Kotlin 2.2.20): refused.
         */
        private fun groupQuantified(from: Int, twice: Boolean) {
            val written = at
            val quantifier = quantifier()
            val text = p.substring(written, at).filterNot { flags.x && spaceOrComment(it) }.let { if (quantifier.mark != null) it.dropLast(1) else it }
            when (quantifier.mark) {
                '+' -> throw UnsupportedPatternException("a possessive quantifier on a group")
                '?' -> when {
                    quantifier.min == 0 && quantifier.max == 1 -> {
                        out.insert(from, "(?:|")
                        out.append(')')
                    }
                    quantifier.min == 0 && quantifier.open -> out.append("*?")
                    // \R+?, \R{2,}?: \R as many times as the minimum, then as many more as it takes.
                    twice && quantifier.open -> {
                        val atom = out.substring(from)
                        out.append('{').append(quantifier.min).append('}').append(atom).append("*?")
                    }
                    else -> throw UnsupportedPatternException("a lazy quantifier on a group but *? and ??")
                }
                else -> out.append(text)
            }
        }

        /** The quantifier at [at], read past (with its lazy or possessive mark), as a token of the lookbehind measuring. */
        private fun quantifier(): Behind.Quantifier {
            val c = p[at]
            val min: Int
            val max: Int
            var open = false
            if (c == '{') {
                val bounds = QUANTIFIER.matchAt(p, at)!!.value.also { at += it.length }.trim('{', '}').split(',')
                min = bounds[0].toIntOrNull() ?: Behind.MAX_REPS
                open = bounds.size == 2 && bounds[1].isEmpty()
                max = if (bounds.size == 1) min else if (open) Behind.MAX_REPS else bounds[1].toIntOrNull() ?: Behind.MAX_REPS
            } else {
                at++
                min = if (c == '+') 1 else 0
                max = if (c == '?') 1 else Behind.MAX_REPS
                open = c != '?'
            }
            val mark = p.getOrNull(past(at))?.takeIf { it == '?' || it == '+' }
            if (mark != null) at = past(at) + 1
            // Java refuses a quantifier after it, which the group it is written as here would take.
            if (p.getOrNull(past(at))?.let { it in "*+?" || it == '{' && QUANTIFIER.matchAt(p, past(at)) != null } == true) {
                throw IllegalArgumentException("Dangling meta character")
            }
            val quantifier = Behind.Quantifier(min, max, open).also { it.mark = mark }
            java(quantifier)
            quantified = false
            node()
            return quantifier
        }

        /** A literal outside a class: written now, or under `(?i)` kept for its run. */
        private fun literal(literal: Literal) {
            java(Behind.Leaf.CHAR)
            node()
            if (flags.i) run += literal else out.append(literal.text)
        }

        /** The run of literals before a quantifier: Java takes the last one from it, as the quantifier's own single. */
        private fun flushQuantified() {
            if (run.size > 1) {
                val last = run.removeLast()
                flush()
                run += last
            }
            flush()
        }

        /** The run of literals, each written as the code points Java takes for it: alone a single's, of two or more a slice's. */
        private fun flush() {
            if (run.isEmpty()) return
            for (literal in run) {
                val takes = if (run.size == 1) JavaCase.single(literal.cp) else JavaCase.inSlice(literal.cp)
                out.append(if (takes.size == 1) literal.text else "[" + takes.joinToString("") { text(it) } + "]")
            }
            run.clear()
        }

        /**
         * Whether [c] begins what Java's comments mode takes out: ASCII's whitespace alone (Pattern's `ASCII.isSpace`, not
         * Kotlin's `isWhitespace`, which takes a no-break space too), or a comment's `#`.
         */
        private fun spaceOrComment(c: Char) = c == ' ' || c in '\t'..'\r' || c == '#'

        /**
         * Under `(?x)`, past what Java's comments mode takes out at [from], as its `peek` and `read` read past it: whitespace,
         * and a comment to the line's end, as Pattern's isLineSeparator ends one: before a `\n`, or under no `(?d)` a `\r`,
         * `\u0085`, `\u2028` or `\u2029`. The line break is left to be read, and so is a literal where it is no ASCII
         * whitespace, as it is in Java (`(?x)a#c\u2028b` matches "a\u2028b").
         */
        private fun past(from: Int): Int {
            var i = from
            while (flags.x && i < p.length && spaceOrComment(p[i])) {
                if (p[i] == '#') {
                    while (i < p.length && p[i] != '\n' && (flags.d || p[i] !in "\r\u0085\u2028\u2029")) i++
                } else {
                    i++
                }
            }
            return i
        }

        private fun group() {
            stack.addLast(flags)
            closers.addLast(")")
            openPath()
            val behind = behinds.lastOrNull()
            if (p.getOrNull(at + 1) != '?') {
                java(Behind.Open(atomic = false))
                opened('c', behind)
                groups++
                harmonyNumbers += ++harmonyGroups
                out.append('(')
                at++
                return
            }
            val rest = p.substring(at, minOf(p.length, at + 4))
            when {
                rest.startsWith("(?:") || rest.startsWith("(?>") -> {
                    if (rest[2] == '>') scan()
                    java(Behind.Open(atomic = rest[2] == '>'))
                    opened(rest[2].takeIf { it == '>' } ?: '(', behind)
                    out.append(rest, 0, 3)
                    at += 3
                }
                // A lookaround in a group of its own: Harmony crashes on one quantified ((?=a)+), which Java reads.
                rest.startsWith("(?=") || rest.startsWith("(?!") || rest.startsWith("(?<=") || rest.startsWith("(?<!") -> {
                    val length = if (rest[2] == '<') 4 else 3
                    scan()
                    java(Behind.Leaf.ZERO)
                    val lookbehind = rest[2] == '<'
                    opened(if (lookbehind) '<' else '=', if (lookbehind) Behind(out.length + 3, negative = rest[3] == '!') else null)
                    out.append("(?:").append(rest, 0, length)
                    closers[closers.lastIndex] = "))"
                    at += length
                }
                rest.startsWith("(?<") -> {
                    java(Behind.Open(atomic = false))
                    opened('c', behind)
                    groups++
                    harmonyNumbers += ++harmonyGroups
                    val end = p.indexOf('>', at).let { if (it < 0) p.length - 1 else it }
                    out.append(p, at, end + 1)
                    at = end + 1
                }
                else -> flagGroup()
            }
        }

        /** `(?flags)` or `(?flags:`, written with only what Harmony reads as Java does (`d`, `m`, `s`): `i` and `x` are done here. */
        private fun flagGroup() {
            // The flags to the : or ), read as Java's addFlag reads them, past (?x)'s whitespace ((? i)); anything else Java refuses.
            val read = StringBuilder("(?")
            var i = at + 2
            while (true) {
                i = past(i)
                val c = p.getOrNull(i++) ?: break
                read.append(c)
                if (c !in "idmsuxUc-") break
            }
            val match = FLAG_GROUP.matchEntire(read) ?: throw IllegalArgumentException("Unknown inline modifier")
            val (on, off, end) = match.destructured
            if ('U' in off) throw UnsupportedPatternException("(?-U)")
            if ('u' in off) throw UnsupportedPatternException("(?-u)")
            var next = flags
            for (f in on) next = next.with(f, true)
            for (f in off) next = next.with(f, false)
            at = i
            val kept = on.filter { it in "dms" }
            val keptOff = off.filter { it in "dms" }
            val written = if (kept.isEmpty() && keptOff.isEmpty()) "" else kept + (if (keptOff.isEmpty()) "" else "-$keptOff")
            if (end == ")") {
                // Flags to the end of the enclosing group: no group of its own.
                stack.removeLast()
                closers.removeLast()
                closePath()
                flags = next
                if (written.isNotEmpty()) out.append("(?").append(written).append(')')
            } else {
                flags = next
                java(Behind.Open(atomic = false))
                opened('(', behinds.lastOrNull())
                out.append("(?").append(written).append(':')
            }
        }

        /** An escape outside a class that is no literal (see [charEscape]). */
        private fun escape() {
            val e = p.getOrNull(at + 1) ?: run {
                // Java refuses a trailing backslash; so does Harmony.
                out.append('\\')
                at++
                return
            }
            classEscape()?.let { (ranges, consumed) ->
                java(Behind.Leaf.CHAR)
                out.append(written(ranges))
                node()
                at += consumed
                return
            }
            at += 2
            node(BOUNDARY)
            scan(open = false)
            java(
                when (e) {
                    'R' -> Behind.Leaf.LINE
                    'k', in '1'..'9' -> Behind.Leaf.BACK_REFERENCE
                    else -> Behind.Leaf.ZERO
                },
            )
            if (e in "bBZAz") zeroAt = out.length
            when (e) {
                'b' -> {
                    // \b{g} alone: Java reads any other \b{ as \b and a quantifier, or refuses it.
                    if (p.startsWith("{g}", at)) throw UnsupportedPatternException("\\b{g}")
                    out.append(boundary(true))
                }
                'B' -> out.append(boundary(false))
                'Z' -> out.append(dollar("\\Z"))
                // Java's \R repeated takes \r\n whole each time, where Harmony's gives it back (\R{2} in "\r\n").
                'R' -> if (p.getOrNull(past(at))?.let { it in "*+?{" } == true) {
                    groupAt = out.length
                    line = true
                    out.append("(?>\\r\\n|[\\n\\u000B\\f\\r\\u0085\\u2028\\u2029])")
                } else {
                    out.append("\\R")
                }
                'G' -> throw UnsupportedPatternException("\\G")
                'X' -> throw UnsupportedPatternException("\\X")
                'N' -> throw UnsupportedPatternException("\\N{…}")
                'k' -> {
                    if (flags.i) throw UnsupportedPatternException("a back reference under (?i)")
                    val end = p.indexOf('>', at).let { if (it < 0) at - 1 else it }
                    out.append("\\k").append(p, at, end + 1)
                    at = end + 1
                }
                in '1'..'9' -> backReference(e)
                else -> {
                    illegal(e)
                    out.append('\\').append(e)
                }
            }
        }

        /**
         * A back reference, as Java reads one: more digits while there are that many groups so far. One to a group not yet
         * opened, which Harmony refuses, is `(?!)`: it never matches in Java where the pattern never opens that group; where
         * it opens it later, a repetition can match it (`(?:\1b|(a))+` in "aab"), so [write] refuses it.
         */
        private fun backReference(first: Char) {
            var n = first.digitToInt()
            while (at < p.length && p[at] in '0'..'9' && n * 10 + p[at].digitToInt() <= groups) n = n * 10 + p[at++].digitToInt()
            if (n > groups) {
                if (forward == 0 || n < forward) forward = n
                // No width, as it never matches: repeated as Java repeats it ([zeroWidthQuantified]).
                zeroAt = out.length
                out.append("(?!)")
                return
            }
            if (flags.i) throw UnsupportedPatternException("a back reference under (?i)")
            out.append('\\').append(harmonyNumbers[n])
            // A digit after it is itself, where Harmony would read it as more of the number.
            if (at < p.length && p[at] in '0'..'9') out.append("(?:)")
        }

        /** `$` or `\Z`: Java's never matches between a `\r` and a `\n`, where Harmony's does. Under `(?d)` only `\n` ends a line. */
        private fun dollar(anchor: String) = if (flags.d) anchor else "(?:(?!(?<=\\r)\\n)$anchor)"

        /**
         * The escape at [at] that is a literal code point, as Java's `Pattern.escape` reads one, and [at] past it; null, [at]
         * left, where it is none: `\t`, `\n`, `\r`, `\f`, `\a`, `\e`, `\cX`, `\0` and octal digits, `\xNN`, `\x{…}`, `\uNNNN`
         * (two of them a pair), and a backslash before any character that is not a letter or a digit. Under `(?x)` Java reads
         * the characters after `\c`, `\0`, `\x` and `\u` past whitespace and comments (`\x4 1` is `A`), as it reads the
         * pattern's; so does this.
         */
        private fun charEscape(): Literal? {
            val e = p.getOrNull(at + 1) ?: return null
            val start = at
            var i = at + 2

            // Pattern's read() and peek(): the code point at i past (?x)'s whitespace, -1 at the end.
            fun peek(): Int {
                i = past(i)
                return if (i < p.length) codePoint(p, i) else -1
            }

            fun read(): Int = peek().also { if (it >= 0) i += if (it > 0xFFFF) 2 else 1 }

            // Four hexadecimal digits, as Pattern.uxxxx reads them.
            fun u4(): Int? {
                var value = 0
                repeat(4) {
                    val digit = hexDigit(read())
                    if (digit < 0) return null
                    value = value * 16 + digit
                }
                return value
            }

            val cp: Int = when (e) {
                't' -> 9
                'n' -> 10
                'r' -> 13
                'f' -> 12
                'a' -> 7
                'e' -> 27
                'c' -> read().takeIf { it >= 0 }?.xor(64) ?: return null
                '0' -> {
                    // Pattern.o: one or two octal digits, or three where the first is at most 3.
                    val n = octal(read())
                    if (n < 0) return null
                    val afterN = i
                    val m = octal(read())
                    if (m < 0) {
                        i = afterN
                        n
                    } else {
                        val afterM = i
                        val o = octal(read())
                        if (o >= 0 && n <= 3) {
                            n * 64 + m * 8 + o
                        } else {
                            i = afterM
                            n * 8 + m
                        }
                    }
                }
                'x' -> {
                    val n = read()
                    if (hexDigit(n) >= 0) {
                        val m = hexDigit(read())
                        if (m < 0) return null
                        hexDigit(n) * 16 + m
                    } else if (n == '{'.code && hexDigit(peek()) >= 0) {
                        var value = 0
                        var c = read()
                        while (hexDigit(c) >= 0) {
                            value = value * 16 + hexDigit(c)
                            if (value > 0x10FFFF) return null
                            c = read()
                        }
                        if (c != '}'.code) return null
                        value
                    } else {
                        return null
                    }
                }
                'u' -> {
                    val high = u4() ?: return null
                    val afterHigh = i
                    val low = if (high in 0xD800..0xDBFF && read() == '\\'.code && read() == 'u'.code) u4() else null
                    if (low != null && low in 0xDC00..0xDFFF) {
                        0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
                    } else {
                        i = afterHigh
                        high
                    }
                }
                // Java's escape refuses an ASCII letter or digit it doesn't name; any other character is itself.
                else -> if (e < '\u0080' && e.isLetterOrDigit()) {
                    return null
                } else {
                    codePoint(p, at + 1).also { i = at + 1 + if (it > 0xFFFF) 2 else 1 }
                }
            }
            at = i
            // As written where Harmony reads it alike; the escapes of a code point by number, and a character past ASCII, which
            // it may not, as the code point.
            return Literal(cp, if (e in "cux0" || cp >= 0x80) text(cp) else p.substring(start, i))
        }

        /** An ASCII hexadecimal digit's value, as Java reads one (Kotlin's `digitToInt` takes other scripts' digits); -1 of any other. */
        private fun hexDigit(c: Int): Int = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }

        private fun octal(c: Int): Int = if (c in '0'.code..'7'.code) c - '0'.code else -1

        /**
         * Refuses, as Java does, a `\u`, `\x`, `\0` or `\c` that [charEscape] couldn't read: Harmony reads other scripts' digits
         * in some (`\u٣٣٣٣`), so it isn't left to refuse them.
         */
        private fun illegal(e: Char) {
            val kind = when (e) {
                'u' -> "Unicode"
                'x' -> "hexadecimal"
                '0' -> "octal"
                'c' -> "control"
                else -> return
            }
            throw IllegalArgumentException("Illegal $kind escape sequence")
        }

        /** `\b` or `\B` as lookarounds on Java's `\w`, which they are with UNICODE_CHARACTER_CLASS since JDK 19. */
        private fun boundary(at: Boolean): String {
            val w = "[${write(JavaClasses.escape('w'))}]"
            return if (at) "(?:(?<=$w)(?!$w)|(?<!$w)(?=$w))" else "(?:(?<=$w)(?=$w)|(?<!$w)(?!$w))"
        }

        /**
         * The class escape at [at] (`\w`, `\p{L}` …): the code points Java's holds, case-insensitively where `(?i)` is in force,
         * and how many characters of the pattern it took; null where [at] holds none.
         */
        private fun classEscape(): Pair<List<IntRange>, Int>? {
            val e = p.getOrNull(at + 1) ?: return null
            val ranges: List<IntRange>
            val negative: Boolean
            val consumed: Int
            when (e) {
                'w', 'd', 's', 'h', 'v' -> {
                    ranges = JavaClasses.escape(e)
                    negative = false
                    consumed = 2
                }
                'W', 'D', 'S', 'H', 'V' -> {
                    ranges = JavaClasses.escape(e.lowercaseChar())
                    negative = true
                    consumed = 2
                }
                'p', 'P' -> {
                    val name: String
                    if (p.getOrNull(at + 2) == '{') {
                        val end = p.indexOf('}', at + 3)
                        // Java refuses an unclosed or empty one; Harmony refuses them too.
                        if (end < 0 || end == at + 3) return null
                        name = p.substring(at + 3, end)
                        consumed = end + 1 - at
                    } else {
                        val letter = p.getOrNull(at + 2) ?: return null
                        name = letter.toString()
                        consumed = 3
                    }
                    ranges = JavaClasses.property(name, flags.i) ?: throw IllegalArgumentException("Unknown character property name {$name}")
                    negative = e == 'P'
                }
                else -> return null
            }
            return (if (negative) complement(ranges) else ranges) to consumed
        }

        /** A set of code points as a class Harmony reads as Java's, or a group that matches nothing where it is empty. */
        private fun written(ranges: List<IntRange>): String = if (ranges.isEmpty()) "(?:(?!))" else "[${write(ranges)}]"

        /**
         * A class, `[…]`, worked out as the set of code points Java's holds ([clazz]) and written as one class of its ranges:
         * Harmony's own nested classes, intersections and negation are not Java's (`[[^a]b]`, `[^a&&b]`).
         */
        private fun characterClass() {
            java(Behind.Leaf.CHAR)
            at++
            out.append(written(clazz(consume = true)))
            node()
        }

        /**
         * The class at [at], past its `[` (or an intersection's right side, which takes no `]` where it ends), read as
         * Pattern.clazz reads it: `^` first, right after the `[`, the complement; each member ([member]) and nested class in a
         * union; `&&` the intersection of what is before it with what is after it, to the class's end. Java keeps the members
         * a BitClass holds (`bits`) apart from the others until an `&&` or the end, which its `]` alone, and `&&` after
         * nothing, depend on.
         */
        private fun clazz(consume: Boolean): List<IntRange> {
            // Nested classes recurse here.
            StackRoom.check(StackRoom.reserve(0))
            var prev: List<IntRange>? = null
            var curr: List<IntRange>? = null
            var bits: List<IntRange> = emptyList()
            var hasBits = false
            at = past(at)
            val negative = p.getOrNull(at) == '^' && p.getOrNull(at - 1) == '['
            if (negative) at++
            while (true) {
                at = past(at)
                val c = p.getOrNull(at) ?: throw IllegalArgumentException("Unclosed character class")
                if (c == '[') {
                    at++
                    val nested = clazz(consume = true)
                    curr = nested
                    prev = prev?.let { union(it, nested) } ?: nested
                } else if (c == '&' && p.getOrNull(past(at + 1)) == '&') {
                    at = past(at + 1) + 1
                    var right: List<IntRange>? = null
                    while (true) {
                        at = past(at)
                        val d = p.getOrNull(at) ?: throw IllegalArgumentException("Unclosed character class")
                        if (d == ']' || d == '&') break
                        val next = if (d == '[') {
                            at++
                            clazz(consume = true)
                        } else {
                            clazz(consume = false)
                        }
                        right = right?.let { union(it, next) } ?: next
                    }
                    if (hasBits) {
                        if (prev == null) curr = bits
                        prev = prev?.let { union(it, bits) } ?: bits
                        hasBits = false
                    }
                    if (right != null) curr = right
                    prev = if (prev == null) {
                        right ?: throw IllegalArgumentException("Bad class syntax")
                    } else {
                        intersect(prev, curr ?: throw IllegalArgumentException("Bad intersection syntax"))
                    }
                } else if (c == ']' && (prev != null || hasBits)) {
                    if (consume) at++
                    val set = if (prev == null) bits else if (hasBits) union(prev, bits) else prev
                    return if (negative) complement(set) else set
                } else {
                    // A ] with nothing before it is itself, as is a single &.
                    val (member, inBits) = member()
                    if (inBits) {
                        bits = union(bits, member)
                        hasBits = true
                        curr = null
                    } else {
                        curr = member
                        prev = prev?.let { union(it, member) } ?: member
                    }
                }
            }
        }

        /**
         * The class's member at [at], as Pattern.range reads it: a class escape or property; or a code point, or the range it
         * begins where a `-` follows and no `[` or `]` after it, under `(?i)` with the code points Java takes for each. Whether
         * Java holds it in the class's BitClass, as it does a single code point of Latin-1's.
         */
        private fun member(): Pair<List<IntRange>, Boolean> {
            val first = if (p[at] == '\\') {
                classEscape()?.let { (ranges, consumed) ->
                    at += consumed
                    return ranges to false
                }
                literalInClass("Illegal/unsupported escape sequence")
            } else {
                codePoint(p, at).also { at += if (it > 0xFFFF) 2 else 1 }
            }
            // Java's range reads past (?x)'s whitespace to the - and past it, but looks at the character after the - as it is.
            at = past(at)
            if (p.getOrNull(at) == '-' && p.getOrNull(at + 1) != '[' && p.getOrNull(at + 1) != ']') {
                at = past(at + 1)
                val last = when {
                    at >= p.length -> throw IllegalArgumentException("Unclosed character class")
                    // A class escape there is no code point (\w), which Java refuses as a range's end.
                    p[at] == '\\' -> literalInClass("Illegal character range")
                    else -> codePoint(p, at).also { at += if (it > 0xFFFF) 2 else 1 }
                }
                if (last < first) throw IllegalArgumentException("Illegal character range")
                val range = first..last
                return (if (flags.i) union(listOf(range), JavaCase.range(range).map { it..it }) else listOf(range)) to false
            }
            val takes = if (flags.i) JavaCase.inClass(first) else listOf(first)
            return normalize(takes.map { it..it }) to JavaCase.inBits(first, flags.i)
        }

        /** The escape at [at] in a class, a code point ([charEscape]); Java refuses any other there (`\b`, `\1`) with [refusal]. */
        private fun literalInClass(refusal: String): Int {
            charEscape()?.let { return it.cp }
            val e = p.getOrNull(at + 1)
            if (e == 'N') throw UnsupportedPatternException("\\N{…}")
            e?.let(::illegal)
            throw IllegalArgumentException(refusal)
        }
    }

    /**
     * [ranges] as a class's ranges, split where they cross from the BMP past it. Two workarounds for Harmony's bugs with code
     * points past the BMP (Kotlin 2.2.20), to come out when Kotlin fixes them:
     *
     * - A range's end that its Lexer misreads (see [astral]) is taken off the range and written alone in `\Q…\E`, which a
     *   range's end can't be.
     * - Its CharClass.add(start, end) marks no code point past the BMP (only add(cp) does), so a range of them alone matches
     *   nothing: the first such code point is written alone too.
     */
    fun write(ranges: List<IntRange>): String = buildString {
        var single: Int? = null
        for (range in ranges) {
            val parts = if (range.first <= 0xFFFF && range.last > 0xFFFF) listOf(range.first..0xFFFF, 0x10000..range.last) else listOf(range)
            for (part in parts) {
                var first = part.first
                var last = part.last
                while (first <= last && misread(first)) append(astral(first++))
                while (last >= first && misread(last)) append(astral(last--))
                if (first > last) continue
                if (first > 0xFFFF && single == null) single = first
                append(text(first))
                if (last > first) append('-').append(text(last))
            }
        }
        single?.let { append(astral(it)) }
    }

    /** A code point in the BMP as `\uNNNN`, past it as [astral]. */
    fun text(cp: Int): String = if (cp <= 0xFFFF) "\\u" + cp.toString(16).padStart(4, '0') else astral(cp)

    /**
     * A code point past the BMP as Harmony reads it: the character, or where its low 16 bits are a character of the syntax,
     * the character in `\Q…\E`. Harmony's Lexer.isSurrogatePair looks for a pair packed as `(high shl 16) or low`, never the
     * code point its nextCodePoint gives, so it truncates the code point to 16 bits and reads U+10026 as `&` and U+1005B as
     * `[` (refusing U+10028, `(`, outside a class) in Kotlin 2.2.20. Out when Kotlin fixes it.
     */
    fun astral(cp: Int): String {
        val v = cp - 0x10000
        val text = charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        return if (misread(cp)) "\\Q$text\\E" else text
    }

    private fun misread(cp: Int) = cp > 0xFFFF && (cp and 0xFFFF) < 0x80 && !(cp and 0xFFFF).toChar().isLetterOrDigit()

    /** The code point at [at] of [text], a pair's where a pair begins there. */
    private fun codePoint(text: String, at: Int): Int =
        if (text[at].isHighSurrogate() && at + 1 < text.length && text[at + 1].isLowSurrogate()) {
            0x10000 + ((text[at].code - 0xD800) shl 10) + (text[at + 1].code - 0xDC00)
        } else {
            text[at].code
        }

    /** [ranges] in order, those that overlap or meet joined. */
    private fun normalize(ranges: List<IntRange>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        for (range in ranges.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && range.first <= last.last + 1) out[out.lastIndex] = last.first..maxOf(last.last, range.last) else out += range
        }
        return out
    }

    private fun union(a: List<IntRange>, b: List<IntRange>): List<IntRange> = normalize(a + b)

    private fun intersect(a: List<IntRange>, b: List<IntRange>): List<IntRange> = complement(union(complement(a), complement(b)))

    /** The code points [ranges] (in order) doesn't hold. */
    private fun complement(ranges: List<IntRange>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var next = 0
        for (r in ranges) {
            if (r.first > next) out += next until r.first
            next = maxOf(next, r.last + 1)
        }
        if (next <= 0x10FFFF) out += next..0x10FFFF
        return out
    }

    /** The nodes on the path of `$` or `\Z` written ([Writer.dollar]), and at most of any other escape (`\b`, [Writer.boundary]). */
    private const val DOLLAR = 6
    private const val BOUNDARY = 8

    private val QUANTIFIER = Regex("""\{[0-9]+(,[0-9]*)?\}""")
    private val FLAG_GROUP = Regex("""\(\?([idmsuxUc]*)(?:-([idmsuxUc]*))?(:|\))""")
}

/** A pattern holding [construct], which this platform's regular expressions can't match as Java's do. */
@DrivineInternalApi
class UnsupportedPatternException(val construct: String) :
    IllegalArgumentException("$construct is not supported on this platform; rewrite the pattern without it.")

/**
 * A lookbehind's length as Java measures it (Pattern.group0, `head.study(info)`): its tokens, as the Writer reads them,
 * built into Java's nodes as Pattern builds them, each node's `study` as Java's, in Java's int arithmetic. Java refuses a
 * lookbehind whose length it finds unbounded ("Look-behind group does not have an obvious maximum length": a back reference
 * in it, a group repeated whose contents vary, `(?:ab)*` whose maximum overflows), and never matches one whose maximum it
 * finds below its minimum (`(?<=a+b+)`, where two maxima of 2^31 - 1 overflow); Harmony matches each. Within those, Java's
 * lookbehind matches as Harmony's does. [at] is where in the pattern written the lookbehind's group begins.
 */
internal class Behind(val at: Int, val negative: Boolean) {

    sealed interface Token

    /** A node of one character (CharProperty), of a line break (`\R`), a back reference, or of none (an anchor, a lookaround). */
    enum class Leaf : Token { CHAR, LINE, BACK_REFERENCE, ZERO }

    class Open(val atomic: Boolean) : Token

    object Close : Token

    object Alternative : Token

    /**
     * `*`, `+`, `?` or `{n,m}`, [open] where it has no maximum written (`*`, `+`, `{n,}`), and [mark] the `?` or `+` after it
     * that makes it lazy or possessive.
     */
    class Quantifier(val min: Int, val max: Int, val open: Boolean = max == MAX_REPS) : Token {
        var mark: Char? = null
    }

    val tokens = mutableListOf<Token>()

    /** Whether Java's never matches; it throws as Java does where Java refuses the lookbehind. */
    fun never(): Boolean {
        position = 0
        val tail = Node()
        val head = Node().also { it.next = expression(tail) }
        val info = Info()
        head.study(info)
        if (!info.valid) throw IllegalArgumentException("Look-behind group does not have an obvious maximum length")
        // Java's Behind tries each start from i - min back to i - max (no further back than 0), in int arithmetic.
        if (info.max >= 0) return info.max < info.min
        // A maximum overflowed past 2^31 - 1: i - max overflows too, to below 0, where i is at least k, else it is past i.
        val k = info.max.toLong() - Int.MIN_VALUE
        if (k <= info.min) return false
        if (k >= 1L shl 30) return true
        throw UnsupportedPatternException("a lookbehind whose maximum length overflows")
    }

    /** Pattern.TreeInfo. */
    private class Info {
        var min = 0
        var max = 0
        var valid = true
        var deterministic = true

        fun reset() {
            min = 0
            max = 0
            valid = true
            deterministic = true
        }
    }

    /** Pattern.Node, and those of its subclasses a lookbehind's study meets, each by its own `study`. */
    private open class Node {
        var next: Node? = null

        open fun study(info: Info): Boolean = next?.study(info) ?: info.deterministic
    }

    private class CharNode(val line: Boolean) : Node() {
        override fun study(info: Info): Boolean {
            info.min++
            info.max += if (line) 2 else 1
            return super.study(info)
        }
    }

    private class BackReference : Node() {
        override fun study(info: Info): Boolean {
            info.valid = false
            return super.study(info)
        }
    }

    private class Ques(private val atom: Node, private val independent: Boolean) : Node() {
        override fun study(info: Info): Boolean {
            val min = info.min
            atom.study(info)
            if (!independent) {
                info.min = min
                info.deterministic = false
            }
            return super.study(info)
        }
    }

    private class CharGreedy(private val cmin: Int) : Node() {
        override fun study(info: Info): Boolean {
            info.min += cmin
            if (info.valid) info.max += MAX_REPS
            info.deterministic = false
            return super.study(info)
        }
    }

    /** Curly and GroupCurly, whose study is one. */
    private class Curly(private val atom: Node, private val cmin: Int, private val cmax: Int) : Node() {
        override fun study(info: Info): Boolean {
            val minL = info.min
            val maxL = info.max
            val maxV = info.valid
            val detm = info.deterministic
            info.reset()
            atom.study(info)
            var temp = info.min * cmin + minL
            if (temp < minL) temp = 0xFFFFFFF
            info.min = temp
            if (maxV && info.valid) {
                temp = info.max * cmax + maxL
                info.max = temp
                if (temp < maxL) info.valid = false
            } else {
                info.valid = false
            }
            info.deterministic = if (info.deterministic && cmin == cmax) detm else false
            return super.study(info)
        }
    }

    private class Loop : Node() {
        override fun study(info: Info): Boolean {
            info.valid = false
            info.deterministic = false
            return false
        }
    }

    private class BranchConn : Node() {
        override fun study(info: Info): Boolean = info.deterministic
    }

    private class Branch(private val atoms: List<Node?>, private val conn: BranchConn) : Node() {
        override fun study(info: Info): Boolean {
            var minL = info.min
            var maxL = info.max
            var maxV = info.valid
            var minL2 = Int.MAX_VALUE
            var maxL2 = -1
            for (atom in atoms) {
                info.reset()
                atom?.study(info)
                minL2 = minOf(minL2, info.min)
                maxL2 = maxOf(maxL2, info.max)
                maxV = maxV && info.valid
            }
            minL += minL2
            maxL += maxL2
            info.reset()
            conn.next?.study(info)
            info.min += minL
            info.max += maxL
            info.valid = info.valid && maxV
            info.deterministic = false
            return false
        }
    }

    private var position = 0

    /** Pattern.expr: the alternatives to a [Close] or the end, as one chain whose last node's next is [end]. */
    private fun expression(end: Node): Node {
        val alternatives = mutableListOf<Node?>()
        val conn = BranchConn().also { it.next = end }
        while (true) {
            val (head, tail) = sequence() ?: (null to null)
            alternatives += head
            val more = tokens.getOrNull(position) == Alternative
            if (!more && alternatives.size == 1) {
                tail?.next = end
                return head ?: end
            }
            tail?.next = conn
            if (!more) return Branch(alternatives, conn)
            position++
        }
    }

    /** Pattern.sequence: the atoms to an [Alternative], a [Close] or the end, each with its quantifier; null where none. */
    private fun sequence(): Pair<Node, Node>? {
        var head: Node? = null
        var tail: Node? = null
        while (position < tokens.size && tokens[position] != Alternative && tokens[position] != Close) {
            val (first, last) = atom()
            if (head == null) head = first else tail!!.next = first
            tail = last
        }
        return head?.let { it to tail!! }
    }

    /** An atom and its quantifier, as Pattern.closure and group0 build them. */
    private fun atom(): Pair<Node, Node> {
        when (val token = tokens[position++]) {
            is Open -> {
                val tail = Node()
                val head = Node().also { it.next = expression(tail) }
                if (tokens.getOrNull(position) == Close) position++
                val quantifier = quantifier()
                if (token.atomic) return closure(Ques(head, independent = true), quantifier)
                if (quantifier == null) return head to tail
                if (quantifier.min == 0 && quantifier.max == 1) {
                    if (quantifier.mark == '+') return Ques(head, independent = false).let { it to it }
                    val conn = BranchConn()
                    tail.next = conn
                    return Branch(if (quantifier.mark == '?') listOf(null, head) else listOf(head, null), conn) to conn
                }
                if (quantifier.mark == '+') return Curly(head, quantifier.min, quantifier.max).let { it to it }
                val node = if (head.study(Info())) Curly(head, quantifier.min, quantifier.max) else Loop()
                return node to node
            }
            is Leaf -> {
                val node = when (token) {
                    Leaf.CHAR -> CharNode(line = false)
                    Leaf.LINE -> CharNode(line = true)
                    Leaf.BACK_REFERENCE -> BackReference()
                    Leaf.ZERO -> Node()
                }
                return closure(node, quantifier())
            }
            // A quantifier with nothing before it, or a close: Java and Harmony refuse the pattern.
            else -> return Node().let { it to it }
        }
    }

    private fun quantifier(): Quantifier? = (tokens.getOrNull(position) as? Quantifier)?.also { position++ }

    /** Pattern.closure and curly of an atom that is no group. */
    private fun closure(atom: Node, quantifier: Quantifier?): Pair<Node, Node> {
        val node = when {
            quantifier == null -> atom
            quantifier.min == 0 && quantifier.max == 1 -> Ques(atom, independent = false)
            quantifier.open && quantifier.mark == null && atom is CharNode && !atom.line -> CharGreedy(quantifier.min)
            else -> Curly(atom, quantifier.min, quantifier.max)
        }
        return node to node
    }

    companion object {
        const val MAX_REPS = 0x7FFFFFFF
    }
}
