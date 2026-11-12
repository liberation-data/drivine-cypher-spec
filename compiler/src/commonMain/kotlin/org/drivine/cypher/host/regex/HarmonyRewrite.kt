package org.drivine.cypher.host.regex

import org.drivine.DrivineInternalApi

/**
 * A Java regular expression, read as `Pattern.compile(expression, UNICODE_CHARACTER_CLASS)` reads it, written for
 * `kotlin.text.Regex` on Kotlin/Native and Kotlin/Wasm so that it matches what Java's does ([HarmonyPattern]). Both
 * targets' `Regex` is the stdlib's own engine (`nativeWasmMain/kotlin/text/regex`, a port of Apache Harmony's), not the
 * platform's: it reads Java's syntax, but not as Java does with `UNICODE_CHARACTER_CLASS`, and has bugs of its own
 * (measured against Java in #108; `RegexOracleTest` checks every target against Java's answers).
 *
 * It takes a fixed grammar, the part of Java's that is simple and that Harmony can be made to match exactly, and refuses
 * the rest by name ([UnsupportedPatternException]):
 *
 * - literals and escapes; classes, `[…]`, POSIX's, `\p{…}`, `\w`, `\d`, `\s` and the others; `.`;
 * - greedy and lazy quantifiers on one of those; greedy quantifiers on a group, and `*?` and `??`;
 * - capturing, named and non-capturing groups, and alternatives;
 * - `^`, `$`, `\b`, `\B`, `\A`, `\z`, `\Z`, and the inline flags `(?i)`, `(?s)`, `(?m)`, `(?x)`, `(?d)`, `(?u)`, `(?U)`;
 * - a lookahead, `(?=…)` or `(?!…)`, of literals, classes, alternatives, `$` and `\z` alone, unquantified: a full-text
 *   search's `(?=$|[^[:alnum:]_])` (TextQuery.lookingAhead).
 *
 * Refused: a lookbehind, any other lookahead, a back reference (`\1`, `\k<name>`), a possessive quantifier, an atomic group,
 * `\G`, `\X`, `\R`, `\N{…}`, `\b{g}`, `(?-U)`, `(?-u)` and `(?c)`, a quantifier on what has no width (`^*`, `\b{2}`, `(?=a)+`,
 * `(\b)*`), a quantifier on a quantifier (`a{2}{3}`), a lazy quantifier on a group but `*?` and `??`, a capturing group in
 * a repeated group of fixed length and a group that can match nothing repeated in a repeated group ([Facts]), groups
 * repeated within each other more than [MAX_NESTED] deep, and a pattern written as more than [MAX_WRITTEN] characters.
 *
 * Within that grammar, Harmony is written so it reads as Java does:
 *
 * - Its class escapes (`\w`, `\d`, `\s`, `\b`) and POSIX classes (`\p{Alnum}` …) are ASCII's, its general categories
 *   (`\p{L}` …) wrong past the BMP, and it knows few of Java's property names: each is written out as the ranges Java's
 *   holds ([JavaClasses]), and a name Java doesn't know refused as Java refuses it.
 * - Its case-insensitivity is not Java's ([JavaCase]): under `(?i)` each literal and class is written as the code points
 *   Java's takes, and Harmony is given no `(?i)`.
 * - Its classes' nesting, intersections and negation are not Java's (`[[^a]b]`, `[^a&&b]`): each class is worked out here
 *   as the set of code points Java's holds, and written as one class of ranges.
 * - Its `.` misses a match after a line break in a search (`.+` in "\na"): written as the class Java's `.` is.
 * - Its `$` and `\Z` match between a `\r` and a `\n`: each written so it doesn't.
 * - Its lookbehind takes time proportional to how far into the text it is tried, so `$`, `\Z`, `\b` and `\B`, which look
 *   at the code point before, are written with one that takes the same time anywhere: see [Writer.behind].
 * - It reads `(?x)`'s whitespace and comments otherwise: they are taken out here.
 * - It refuses `\x{…}`: written as the code point.
 * - Two bugs in Kotlin 2.2.20 with code points past the BMP in a pattern: see [astral] and [write].
 * - Its lazy repetition of a group, and its repetition of a non-capturing group (which can overflow the stack), aren't
 *   Java's: see [Writer.groupQuantified] and [Writer]'s wrap.
 * - It keeps one saved state for all its lookarounds, which a group can keep a value from after a lookaround fails, or
 *   repeat at one place until the stack overflows: see [SNAPSHOT].
 * - Its lexer misreads a `\E` right before the `)` of a group of flags: see [Writer]'s close.
 * - A search's places between a pair's halves, and a run of literals under `(?i)`, are Java's: see HarmonyPattern's find
 *   and [Rewritten.supplementary], and [Writer]'s flush.
 */
internal object HarmonyRewrite {

    /** [java], written for kotlin.text.Regex on Kotlin/Native and Kotlin/Wasm; it throws where it can't be. */
    fun rewrite(java: String): String = rewritten(java).text

    /**
     * [java] written for kotlin.text.Regex ([text]), for [StackRoom]: the most of Harmony's nodes on a path through it
     * ([path]), and how deep its lookaheads nest at most ([scans]), each of which Harmony matches in frames of its own above
     * those of the path that reaches it; the capturing groups Harmony has that Java's hasn't ([hidden]); and whether Java's
     * Pattern has supplementary characters ([supplementary]), as its hasSupplementary says: a code point past the BMP or a
     * surrogate in the pattern, or a class, property or single literal whose predicate isn't BMP-only. Then Java's search
     * moves past a pair whole (StartS), and otherwise tries each character, between a pair's halves too (Start).
     *
     * And whether the stack Harmony takes grows with the text ([deepens]): whether what is written repeats a group, anything
     * in parentheses, more than once (`*`, `+`, `*?`, `{n,}`, or `{n,m}` with m over 1). Harmony repeats a group by recursion
     * (GroupQuantifierSet, which the group's end calls again), so the frames of the group's path pile up once a repetition:
     * `(a)*`, `(?:a)*` (written in a hidden group), `(?:.)*`, `(?:a|b)*` and `(?:a(?=a))*` over 100,000 characters each
     * crashed a release library on a 512 KiB thread before [StackRoom] guarded them. A quantifier on one character, greedy or
     * lazy, loops (LeafQuantifierSet, FixedLengthQuantifierSet and their reluctant kinds): on a literal, a code point past
     * the BMP, or a class, which `.`, `\w`, `\p{…}` and the literals of `(?i)` are written as. So does Harmony's search from
     * each start. Those, and alternatives, lookaheads, `\b`, `$` and [SNAPSHOT] outside a repeated group, and a group's `?`
     * and `??`, matched 300,000 characters and more on 512 KiB unguarded.
     */
    class Rewritten(
        val text: String,
        val path: Int,
        val scans: Int,
        val hidden: Set<Int> = emptySet(),
        val supplementary: Boolean = true,
        val deepens: Boolean = true,
    )

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
     * characters, `\b` 35,000), and Harmony takes about 40 bytes of memory and 60 ns to compile each (30 million
     * characters, a thousand `\b` as once written, took 1.2 GB and two seconds), where Java's takes none.
     */
    const val MAX_WRITTEN = 2_000_000

    /**
     * How deep groups repeated within each other may be: `((a)*)*` is two. The compiler's patterns take two (a time's
     * `(:[0-9]{2}([.][0-9]+)?)?`); each level deeper multiplies the backtracking Harmony does and the frames it takes for
     * each character, where its repetition of groups is the part of it least like Java's.
     */
    const val MAX_NESTED = 3

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

    /** A quantifier: [max] null where it has none (`*`, `+`, `{n,}`), and [mark] the `?` or `+` after it that makes it lazy or possessive. */
    private class Quantifier(val min: Int, val max: Int?, val mark: Char?) {
        /** As Harmony reads it, greedy. */
        val text: String = when {
            min == 0 && max == null -> "*"
            min == 1 && max == null -> "+"
            min == 0 && max == 1 -> "?"
            max == null -> "{$min,}"
            max == min -> "{$min}"
            else -> "{$min,$max}"
        }
    }

    /** What the atom just written is, for a quantifier after it. */
    private enum class Last { NONE, SINGLE, ZERO, GROUP, QUANTIFIED }

    /**
     * What Java reads a group's body or an atom as, for the quantifiers on a group that Harmony repeats otherwise than Java
     * ([Writer.quantified]): whether it takes a character ([wide]: a quantifier on a group that takes none is refused, as Java
     * repeats one otherwise, `b()*` in "b" giving null for the group in Java, an empty text in Harmony); whether its length
     * can vary ([variable]: an alternative, or a quantifier whose least and most differ, which make Java's study of it not
     * deterministic); whether it holds a capturing group ([captures]), or is one
     * ([capturing]); whether it can match nothing ([empty]: of a body, the
     * alternative being read, and [anyEmpty] those before it); and whether it holds a group that can match nothing, repeated
     * ([repeatsEmpty]). An atom's are never changed; a body's take each atom's ([add]) as the next begins.
     */
    private class Facts(
        var wide: Boolean = false,
        var variable: Boolean = false,
        var captures: Boolean = false,
        var empty: Boolean = true,
        var anyEmpty: Boolean = false,
        var repeatsEmpty: Boolean = false,
        val capturing: Boolean = false,
    ) {
        fun add(atom: Facts) {
            wide = wide || atom.wide
            variable = variable || atom.variable
            captures = captures || atom.captures || atom.capturing
            empty = empty && atom.empty
            repeatsEmpty = repeatsEmpty || atom.repeatsEmpty
        }

        /** An alternative ended: Java's study of an alternation is never deterministic. */
        fun alternative() {
            anyEmpty = anyEmpty || empty
            empty = true
            variable = true
        }

        /** This body, closed, as the group's atom. */
        fun group(capturing: Boolean) = Facts(wide, variable, captures, empty || anyEmpty, repeatsEmpty = repeatsEmpty, capturing = capturing)

        /** This atom with [q] after it, a [group]'s or not. */
        fun quantified(q: Quantifier, group: Boolean) =
            Facts(wide && q.max != 0, variable || q.min != q.max, captures, empty || q.min == 0, repeatsEmpty = repeatsEmpty || group && empty, capturing = capturing)

        companion object {
            /** A literal, a class or `.`: one character. */
            val ONE = Facts(wide = true, empty = false)
        }
    }

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

        /** What the atom just written is, and where it is a group, where in [out] it begins and how deep the groups repeated in it nest. */
        private var last = Last.NONE
        private var groupAt = -1
        private var groupNested = 0

        /** What the group open is so far ([body]), and the atom just written ([atom]); of each group around it, [body] saved as it opened. */
        private var body = Facts()
        private var atom = Facts()
        private val outerBodies = ArrayDeque<Facts>()

        /** How deep the groups repeated in the group open nest, so far; of each group around it, the same, saved as it opened. */
        private var nested = 0
        private val outerNested = ArrayDeque<Int>()

        /** Harmony's capturing groups so far, Java's and wraps; and the wraps'. */
        private var harmonyGroups = 0
        private val hidden = mutableSetOf<Int>()

        private val out = StringBuilder()
        private val stack = ArrayDeque<Flags>()

        /** What closes each group open, as [stack] holds its flags: `))` for a lookahead, written in a group of its own. */
        private val closers = ArrayDeque<String>()

        /** What kind each group open is: `(` non-capturing, `c` capturing, `=` a lookahead. */
        private val kinds = ArrayDeque<Char>()
        private var flags = Flags()
        private var at = 0

        /** Whether a lookahead is open, whose body takes only literals, classes, alternatives, `$` and `\z`. */
        private var ahead = false

        /**
         * The literals read since the last other token under `(?i)`, which Java reads as one atom: one alone is a single, two
         * or more a slice, which take different code points ([JavaCase]).
         */
        private val run = mutableListOf<Literal>()

        /** Whether Java's Pattern has hasSupplementary set, so far ([Rewritten.supplementary]). */
        private var supplementary = p.any { it.isSurrogate() }

        /**
         * Harmony's nodes on the longest path through the pattern written so far, as [StackRoom] counts them: in the group
         * open, the alternative being read ([nodes]) and the longest of those before it ([longest]); of each group around
         * it, the same, saved in [outer] as the group opened.
         */
        private var nodes = 0
        private var longest = 0
        private val outer = ArrayDeque<Int>()

        /** Whether a group is written repeated more than once, so far ([Rewritten.deepens]). */
        private var deepens = false

        /** The groups [behind] has written, and the name each is named by with its number after it: one no group of [p] begins with. */
        private var behinds = 0
        private val names = generateSequence("d") { it + "d" }.first { !p.contains("<$it") }

        /** The lookaheads open, whether each group open is one, and the most open at once, of [Rewritten.scans]. */
        private var scans = 0
        private val scanning = ArrayDeque<Boolean>()
        private var deepestScans = 0

        /** A group of [kind] opened. */
        private fun opened(kind: Char) {
            openings.addLast(opens)
            starts.addLast(out.length)
            if (opens++ in wrap) {
                out.append('(')
                hidden += ++harmonyGroups
            }
            kinds.addLast(kind)
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
            outerNested.addLast(nested)
            nested = 0
        }

        /** The group's path closed: its longest alternative, and the group itself, on its enclosing alternative's path. */
        private fun closePath() {
            if (outer.isEmpty()) return
            if (scanning.removeLast()) scans--
            val inner = maxOf(nodes, longest) + 2
            longest = outer.removeLast()
            nodes = outer.removeLast() + inner
            groupNested = nested
            nested = maxOf(outerNested.removeLast(), nested)
        }

        fun write(): Rewritten {
            while (at < p.length) atom()
            flush()
            while (outer.isNotEmpty()) closePath()
            node(0)
            return Rewritten(out.toString(), maxOf(nodes, longest), deepestScans, hidden, supplementary, deepens)
        }

        /**
         * The group just opened is a lookahead; or with [open] false, an anchor is written here (`\b`, `$`), whose lookbehind
         * holds a lookahead ([behind]): two nested.
         */
        private fun scan(open: Boolean = true) {
            if (open) {
                scanning[scanning.lastIndex] = true
                scans++
            }
            deepestScans = maxOf(deepestScans, scans + if (open) 0 else 2)
        }

        /** Refuses an anchor in a lookahead but `$` and `\z`. */
        private fun anchor() {
            if (ahead) throw UnsupportedPatternException("a lookahead holding an anchor but \$ and \\z")
        }

        /** Whether a quantifier begins at [i]: `*`, `+`, `?`, or a `{` and a digit, as Java's closure reads one. */
        private fun quantifierAt(i: Int) = p.getOrNull(i)?.let { it in "*+?" || it == '{' && p.getOrNull(i + 1) in '0'..'9' } == true

        private fun atom() {
            val c = p[at]
            if (flags.x && spaceOrComment(c)) {
                // Java reads past them, within a run of literals too.
                at = past(at)
                return
            }
            if (quantifierAt(at)) return quantified()
            body.add(atom)
            atom = Facts()
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
            // A literal joins the run before it, as Java's atom reads a slice; anything else ends the run.
            if (c in "{[().$^|") flush()
            last = Last.SINGLE
            when (c) {
                // Java's closure refuses a { before anything but a digit.
                '{' -> throw IllegalArgumentException("Illegal repetition")
                '[' -> characterClass()
                '(' -> {
                    group()
                    last = Last.NONE
                }
                ')' -> close()
                '.' -> {
                    atom = Facts.ONE
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
                    last = Last.ZERO
                    out.append(dollar("$"))
                    node(DOLLAR)
                    scan(open = false)
                    at++
                }
                '^' -> {
                    anchor()
                    last = Last.ZERO
                    out.append(c)
                    node()
                    at++
                }
                '|' -> {
                    body.alternative()
                    last = Last.NONE
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

        /** The group open closed, at [at]. */
        private fun close() {
            val kind = kinds.removeLastOrNull() ?: throw IllegalArgumentException("Unmatched closing ')'")
            val closer = closers.removeLast()
            val opening = openings.removeLast()
            val start = starts.removeLast()
            if (kind == '(' && quantifierAt(past(at + 1))) wrapping += opening
            // Harmony's lexer misreads a \E right before the ) that ends a group of flags ((?m:\Q𐀦\E) is refused): see astral.
            if (out.endsWith("\\E")) out.append("(?:)")
            out.append(closer)
            if (opening in wrap) out.append(')')
            body.add(atom)
            atom = if (kind == '=') Facts() else body.group(capturing = kind == 'c')
            body = outerBodies.removeLast()
            if (kind == '=') {
                node(SNAPSHOT_NODES)
                ahead = false
                last = Last.ZERO
            } else {
                last = Last.GROUP
                groupAt = start
            }
            flags = stack.removeLast()
            closePath()
            at++
        }

        /** The quantifier at [at], of the atom just written. */
        private fun quantified() {
            // Java refuses a quantifier with no atom before it, or after another but the { of a quantifier.
            if (last == Last.NONE || last == Last.QUANTIFIED) throw IllegalArgumentException("Dangling meta character '${p[at]}'")
            if (ahead) throw UnsupportedPatternException("a lookahead holding a quantifier")
            // Harmony's repetition of what has no width isn't Java's (`^{2}`, `$+?`, and a quantified lookaround crashes it).
            if (last == Last.ZERO || last == Last.GROUP && !atom.wide) throw UnsupportedPatternException("a quantifier on what has no width")
            if (last == Last.SINGLE) flushQuantified()
            val from = groupAt
            val quantifier = quantifier()
            if (quantifier.mark == '+') throw UnsupportedPatternException("a possessive quantifier")
            if (last == Last.GROUP) {
                // Java repeats a group whose length can't vary (its study deterministic) with GroupCurly, which leaves a group in
                // it as a repetition it gave back set it (`(?:(a))*a` in "a" gives `a` for the group, Harmony null); `?` with Ques.
                if (atom.captures && !atom.variable && !(quantifier.min == 0 && quantifier.max == 1)) {
                    throw UnsupportedPatternException("a capturing group in a repeated group of fixed length")
                }
                // Harmony's repetition of a group asks first whether the group's last repetition took nothing, and stops if so:
                // repeated again within a group repeated, it asks of the last repetition of the time before (Kotlin 2.2.20), so
                // `(?:a(?:b*)*){2}` matches "aba" of "abab".
                if (atom.repeatsEmpty && (quantifier.max ?: 2) > 1) throw UnsupportedPatternException("a group that can match nothing, repeated in a repeated group")
            }
            atom = atom.quantified(quantifier, group = last == Last.GROUP)
            // What Harmony repeats as a group: a group, or a class with nothing in it, written as one (written).
            if ((last == Last.GROUP || out.endsWith(NOTHING)) && (quantifier.max ?: 2) > 1) deepens = true
            if (last == Last.GROUP) groupQuantified(from, quantifier) else out.append(quantifier.text).append(quantifier.mark ?: "")
            last = Last.QUANTIFIED
        }

        /**
         * The quantifier of the group written at [from]. Harmony's lazy repetition of a group counts its repetitions wrongly,
         * as it doesn't count back one that fails (`(bc)+?` in "xbc" finds an empty match, `(?:bc){2}?` matches one `bc`):
         * `*?` counts none, and `??` is written as the alternatives it is, `(?:|…)`; any other is refused.
         */
        private fun groupQuantified(from: Int, quantifier: Quantifier) {
            val nesting = groupNested + 1
            if (nesting > MAX_NESTED) throw UnsupportedPatternException("groups repeated within each other more than $MAX_NESTED deep")
            nested = maxOf(nested, nesting)
            when {
                quantifier.mark == null -> out.append(quantifier.text)
                quantifier.min == 0 && quantifier.max == 1 -> {
                    out.insert(from, "(?:|")
                    out.append(')')
                }
                quantifier.min == 0 && quantifier.max == null -> out.append("*?")
                else -> throw UnsupportedPatternException("a lazy quantifier on a group but *? and ??")
            }
        }

        /**
         * The quantifier at [at], read past with its lazy or possessive mark, as Java's closure and curly read it: under `(?x)`,
         * a `{` takes its first digit right after it, and whitespace and comments anywhere after that.
         */
        private fun quantifier(): Quantifier {
            val c = p[at++]
            val min: Int
            var max: Int?
            when (c) {
                '*' -> { min = 0; max = null }
                '+' -> { min = 1; max = null }
                '?' -> { min = 0; max = 1 }
                else -> {
                    min = number()
                    max = min
                    if (p.getOrNull(at) == ',') {
                        at = past(at + 1)
                        max = if (p.getOrNull(at) == '}') null else number()
                    }
                    if (p.getOrNull(at) != '}') throw IllegalArgumentException("Unclosed counted closure")
                    at++
                    if (max != null && max < min) throw IllegalArgumentException("Illegal repetition range")
                }
            }
            val mark = p.getOrNull(past(at))?.takeIf { it == '?' || it == '+' }
            if (mark != null) at = past(at) + 1
            // Java reads a {n} after it as another quantifier, of the quantified atom; anything else Java refuses ([quantified]).
            if (p.getOrNull(past(at)) == '{' && quantifierAt(past(at))) throw UnsupportedPatternException("a quantifier on a quantifier")
            node()
            return Quantifier(min, max, mark)
        }

        /** The digits at [at], read past (?x)'s whitespace after each, as Java's curly reads them; none is 0. */
        private fun number(): Int {
            var n = 0L
            while (p.getOrNull(at) in '0'..'9') {
                n = n * 10 + (p[at] - '0')
                if (n > Int.MAX_VALUE) throw IllegalArgumentException("Illegal repetition range")
                at = past(at + 1)
            }
            return n.toInt()
        }

        /** A literal outside a class, kept for its run. */
        private fun literal(literal: Literal) {
            last = Last.SINGLE
            atom = Facts.ONE
            node()
            run += literal
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

        /**
         * The run of literals, each written as the code points Java takes for it: under `(?i)` alone a single's, of two or more
         * a slice's. Java makes a literal alone a predicate, which is BMP-only but past the BMP, a surrogate, or under `(?i)` with a case.
         */
        private fun flush() {
            if (run.isEmpty()) return
            for (literal in run) {
                val takes = if (!flags.i) listOf(literal.cp) else if (run.size == 1) JavaCase.single(literal.cp) else JavaCase.inSlice(literal.cp)
                out.append(if (takes.size == 1) literal.text else "[" + takes.joinToString("") { text(it) } + "]")
                if (run.size == 1 && (literal.cp > 0xFFFF || literal.cp in 0xD800..0xDFFF || takes.size > 1)) supplementary = true
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
         * `\u0085`, ` ` or ` `. The line break is left to be read, and so is a literal where it is no ASCII
         * whitespace, as it is in Java (`(?x)a#c b` matches "a b").
         */
        private fun past(from: Int): Int {
            var i = from
            while (flags.x && i < p.length && spaceOrComment(p[i])) {
                if (p[i] == '#') {
                    while (i < p.length && p[i] != '\n' && (flags.d || p[i] !in "\r\u0085  ")) i++
                } else {
                    i++
                }
            }
            return i
        }

        private fun group() {
            if (ahead) throw UnsupportedPatternException("a lookahead holding a group")
            stack.addLast(flags)
            closers.addLast(")")
            outerBodies.addLast(body)
            body = Facts()
            openPath()
            if (p.getOrNull(at + 1) != '?') {
                opened('c')
                harmonyGroups++
                out.append('(')
                at++
                return
            }
            val rest = p.substring(at, minOf(p.length, at + 4))
            when {
                rest.startsWith("(?:") -> {
                    opened('(')
                    out.append("(?:")
                    at += 3
                }
                rest.startsWith("(?>") -> throw UnsupportedPatternException("an atomic group")
                rest.startsWith("(?<=") || rest.startsWith("(?<!") -> throw UnsupportedPatternException("a lookbehind")
                // A lookahead in a group of its own: Harmony crashes on a lookaround quantified ((?=a)+), and in a group repeated.
                rest.startsWith("(?=") || rest.startsWith("(?!") -> {
                    scan()
                    opened('=')
                    ahead = true
                    out.append("(?:").append(rest, 0, 3)
                    closers[closers.lastIndex] = "))$SNAPSHOT"
                    at += 3
                }
                rest.startsWith("(?<") -> {
                    opened('c')
                    harmonyGroups++
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
            // Java reads each class and property after it by graphemes (Pattern.NFCCharProperty), as Harmony can't.
            if ('c' in on) throw UnsupportedPatternException("(?c)")
            var next = flags
            for (f in on) next = next.with(f, true)
            for (f in off) next = next.with(f, false)
            at = i
            val kept = on.filter { it in "dms" }
            val keptOff = off.filter { it in "dms" }
            val written = if (kept.isEmpty() && keptOff.isEmpty()) "" else kept + (if (keptOff.isEmpty()) "" else "-$keptOff")
            if (end == ")") {
                // Flags to the end of the enclosing group: no group of its own, and no atom for a quantifier, as in Java.
                stack.removeLast()
                closers.removeLast()
                body = outerBodies.removeLast()
                closePath()
                flags = next
                if (written.isNotEmpty()) out.append("(?").append(written).append(')')
            } else {
                flags = next
                opened('(')
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
            last = Last.SINGLE
            classEscape()?.let { (ranges, consumed) ->
                out.append(written(ranges))
                atom = Facts.ONE
                node()
                at += consumed
                return
            }
            when (e) {
                'G' -> throw UnsupportedPatternException("\\G")
                'X' -> throw UnsupportedPatternException("\\X")
                'R' -> throw UnsupportedPatternException("\\R")
                'N' -> throw UnsupportedPatternException("\\N{…}")
                'k', in '1'..'9' -> throw UnsupportedPatternException("a back reference")
            }
            at += 2
            node(BOUNDARY)
            scan(open = false)
            if (e in "bBAZz") last = Last.ZERO
            if (e in "bBAZ") anchor()
            when (e) {
                'b' -> {
                    // \b{g} alone: Java reads any other \b{ as \b and a quantifier, or refuses it.
                    if (p.startsWith("{g}", at)) throw UnsupportedPatternException("\\b{g}")
                    out.append(boundary(true))
                }
                'B' -> out.append(boundary(false))
                'Z' -> out.append(dollar("\\Z"))
                'A', 'z' -> out.append('\\').append(e)
                else -> {
                    illegal(e)
                    out.append('\\').append(e)
                }
            }
        }

        /**
         * `$` or `\Z`: Java's never matches between a `\r` and a `\n`, where Harmony's does. So before a `\n`, but at the
         * start, it is taken only where the character before is no `\r`, read through [behind]: `\r` or nothing captured,
         * which the `\n` matches only where it is nothing. Under `(?d)` only `\n` ends a line.
         */
        private fun dollar(anchor: String): String {
            if (flags.d) return anchor
            // Any character before will do, a pair's low half alone too, as only a \r is looked for: Harmony's own `.`, short.
            val (look, back) = behind("\\r?", "(?s:.)")
            return "(?:$anchor(?:(?!\\n)|\\A|(?=\\n)$look$back))$SNAPSHOT"
        }

        /**
         * A look at the code point before here, in time that doesn't grow with the text. Harmony's lookbehind (its
         * LookBehindSets) looks for its body ending here from each place back to the text's start (AbstractSet.findBack,
         * from here down to 0), so one that fails, or a negative one that holds, takes time proportional to how far into
         * the text it is, and a pattern holding one takes time quadratic in the text's length (#204): `(?<=b)a` counted in
         * 20,000 characters took 409 ms, in 10,000 102 ms, where `(?<=a)a` took 32 ms and 16 ms. So the lookbehind written
         * here holds at the code point before here ([before], [ANY] where none is given), wherever it is, and finds it at
         * once: Harmony takes no half of a pair alone in a class, so after a pair it is found at the pair's start, a place
         * further. It captures [ahead], a lookahead's body read from that code point, in a group of its own
         * ([Rewritten.hidden]), set there each time, to nothing where [ahead] takes nothing; and the back reference to the
         * group, written after it, reads it. The group is named, as Harmony reads a back reference by number to one digit
         * alone, by a name no group of Java's has ([names]). At the text's start nothing is before here, and the lookbehind
         * fails, as Java's does.
         */
        private fun behind(ahead: String, before: String = ANY): Pair<String, String> {
            hidden += ++harmonyGroups
            val name = names + behinds++
            return "(?<=(?=(?<$name>$ahead))$before)" to "\\k<$name>"
        }

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

        /**
         * `\b` or `\B` on Java's `\w`, which they are with UNICODE_CHARACTER_CLASS since JDK 19: whether the code points
         * before and after here differ in being one. At the start, by the one after alone; elsewhere through [behind], which
         * captures the code point before where they differ (and the one after, or the end), and nothing where they don't. The
         * capture, where it isn't nothing, begins with a code point that is not the one after here, so the back reference
         * matches here exactly where it is nothing: `\b` where it doesn't, `\B` where it does.
         */
        private fun boundary(at: Boolean): String {
            val w = written(JavaClasses.escape('w'))
            val other = written(complement(JavaClasses.escape('w')))
            val (look, back) = behind("$w(?:$other|\\z)|$other$w|")
            return (if (at) "(?:\\A(?=$w)|$look(?!$back))" else "(?:\\A(?!$w)|$look(?=$back))") + SNAPSHOT
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
                    // Java's \h and \v alone are BMP-only.
                    if (e in "wds") supplementary = true
                    ranges = JavaClasses.escape(e)
                    negative = false
                    consumed = 2
                }
                'W', 'D', 'S', 'H', 'V' -> {
                    supplementary = true
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
                    if (negative || !JavaClasses.bmp(name)) supplementary = true
                }
                else -> return null
            }
            return (if (negative) complement(ranges) else ranges) to consumed
        }

        /** A set of code points as a class Harmony reads as Java's, or a group that matches nothing where it is empty. */
        private fun written(ranges: List<IntRange>): String = if (ranges.isEmpty()) NOTHING else "[${write(ranges)}]"

        /**
         * A class, `[…]`, worked out as the set of code points Java's holds ([clazz]) and written as one class of its ranges:
         * Harmony's own nested classes, intersections and negation are not Java's (`[[^a]b]`, `[^a&&b]`).
         */
        private fun characterClass() {
            atom = Facts.ONE
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
            if (negative) {
                at++
                supplementary = true
            }
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
                // Java's Range is BMP-only where it holds no surrogate and nothing past the BMP; CIRangeU never is.
                if (flags.i || last >= 0xD800 && (first < 0xE000 || last > 0xFFFF)) supplementary = true
                val range = first..last
                return (if (flags.i) union(listOf(range), JavaCase.range(range).map { it..it }) else listOf(range)) to false
            }
            val takes = if (flags.i) JavaCase.inClass(first) else listOf(first)
            // Outside Java's BitClass, Pattern.single: BMP-only but past the BMP, a surrogate, or under (?i) with a case.
            if (!JavaCase.inBits(first, flags.i) && (first >= 0xD800 && first <= 0xDFFF || first > 0xFFFF || flags.i && JavaCase.single(first).size > 1)) supplementary = true
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

    /**
     * Written after each lookaround (a lookahead, `\b`, `\B`, `$`, `\Z`), as its continuation's last alternative: a lookaround
     * that fails, which saves the match's state as it is there. Harmony runs the rest of the pattern inside each lookaround,
     * which saves the match's state (its groups, and its counts of repetitions) as it begins and restores it where the rest
     * fails; but it keeps one state, not one for each lookaround, so a lookaround the rest passes through saves over it, and
     * the first restores the state the second saved, with the groups and counts set between them (Kotlin 2.2.20). Then a
     * group keeps what it took on a path that failed (`(?:\b(a)\b|a)` in "ab" gives `a` for the group, where Java gives
     * null), and a repetition counts from the wrong place, which can repeat a group at one place until the stack overflows
     * (`(a?(?!b))*8` in "a"). With this, the rest failing passes the lookaround that fails first, and the state restored is
     * the state there.
     */
    private const val SNAPSHOT = "(?:|(?!))"

    /** A class that holds nothing, as Harmony is given it: a group that matches nothing. */
    private const val NOTHING = "(?:(?!))"

    /** The nodes [SNAPSHOT] puts on the path. */
    private const val SNAPSHOT_NODES = 3

    /**
     * The nodes on the path of `$` or `\Z` written ([Writer.dollar]), and at most of any other escape (`\b`, [Writer.boundary]),
     * as [Writer.closePath] counts a group's: two for the group and its end, and those of its longest alternative.
     */
    private const val DOLLAR = 15 + SNAPSHOT_NODES
    private const val BOUNDARY = 16 + SNAPSHOT_NODES

    /** Any one code point, a pair whole, as [Writer.behind] takes the one before a place. */
    private val ANY = "[${write(listOf(0..0x10FFFF))}]"

    private val FLAG_GROUP = Regex("""\(\?([idmsuxUc]*)(?:-([idmsuxUc]*))?(:|\))""")
}

/** A pattern holding [construct], which this platform's regular expressions don't take: see HarmonyRewrite's grammar. */
@DrivineInternalApi
class UnsupportedPatternException(val construct: String) :
    IllegalArgumentException("$construct is not supported on this platform; rewrite the pattern without it.")
