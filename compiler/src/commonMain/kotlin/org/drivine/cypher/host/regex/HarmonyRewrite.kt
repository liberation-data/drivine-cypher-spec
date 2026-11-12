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
 * - Its `.` misses a match after a line break in a search (`.+` in "\na"): written as the class Java's `.` is.
 * - It reads `(?x)`'s whitespace and comments otherwise: they are taken out here.
 * - It refuses `\x{…}`, and a back reference to a group the pattern never opens (which never matches in Java): written as
 *   the code point, and as `(?!)`.
 * - Two bugs in Kotlin 2.2.20 with code points past the BMP in a pattern: see [astral] and [write].
 *
 * What it can't make match as Java's does, it refuses by name ([UnsupportedPatternException]): `\G`, `\X`, `\N{…}`,
 * `\b{g}`, a back reference under `(?i)` (Java folds each code point of it, Harmony its own way), a back reference to a
 * group the pattern opens only after it (which Harmony refuses, and Java matches in a repetition), and `(?-U)` and `(?-u)`,
 * which turn off what drivine4j's patterns always have on.
 */
internal object HarmonyRewrite {

    /** [java], written for kotlin.text.Regex on Kotlin/Native and Kotlin/Wasm; it throws where it can't be. */
    fun rewrite(java: String): String = Writer(java).write()

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

    private class Writer(private val p: String) {
        private val out = StringBuilder()
        private val stack = ArrayDeque<Flags>()
        private var flags = Flags()
        private var at = 0

        /** The capturing groups opened so far, as Java counts them for a back reference. */
        private var groups = 0

        /**
         * The literals read since the last other token under `(?i)`, which Java reads as one atom: one alone is a single, two
         * or more a slice, which take different code points ([JavaCase]).
         */
        private val run = mutableListOf<Literal>()

        /** The first code point past the BMP the class being written holds: see [characterClass]. */
        private var firstAstral: Int? = null

        /** The smallest number of a back reference to a group not yet opened where it is, 0 where none is: see [backReference]. */
        private var forward = 0

        fun write(): String {
            while (at < p.length) atom()
            flush()
            if (forward in 1..groups) throw UnsupportedPatternException("a back reference to a later group")
            return out.toString()
        }

        private fun atom() {
            val c = p[at]
            if (flags.x && spaceOrComment(c)) {
                // Java reads past them, within a run of literals too.
                skipComment()
                return
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
                    out.append(c)
                    at++
                }
                '{' -> {
                    val quantifier = QUANTIFIER.matchAt(p, at)
                    if (quantifier != null) {
                        flushQuantified()
                        out.append(quantifier.value)
                        at += quantifier.value.length
                    } else {
                        // No quantifier: Java refuses it, as Harmony does.
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
                    out.append(')')
                    flags = stack.removeLastOrNull() ?: flags
                    at++
                }
                '.' -> {
                    flush()
                    out.append(
                        when {
                            flags.s -> "[\\s\\S]"
                            flags.d -> "[^\\n]"
                            else -> "[^\\n\\r\\u0085\\u2028\\u2029]"
                        },
                    )
                    at++
                }
                '$', '^', '|' -> {
                    flush()
                    out.append(c)
                    at++
                }
                else -> {
                    val cp = codePoint(p, at)
                    at += if (cp > 0xFFFF) 2 else 1
                    literal(Literal(cp, if (cp > 0xFFFF) astral(cp) else c.toString()))
                }
            }
        }

        /** A literal outside a class: written now, or under `(?i)` kept for its run. */
        private fun literal(literal: Literal) {
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
         * Whitespace, or a comment to the line's end, as Pattern's isLineSeparator ends one: before a `\n`, or under no `(?d)`
         * a `\r`, `\u0085`, `\u2028` or `\u2029`. The line break is left to be read, and so is a literal where it is no ASCII
         * whitespace, as it is in Java (`(?x)a#c\u2028b` matches "a\u2028b").
         */
        private fun skipComment() {
            if (p[at] == '#') {
                while (at < p.length && p[at] != '\n' && (flags.d || p[at] !in "\r\u0085\u2028\u2029")) at++
            } else {
                at++
            }
        }

        /** Under `(?x)`, past what Java's comments mode takes out at [at], as its `peek` reads past it. */
        private fun skipSpaces() {
            while (flags.x && at < p.length && spaceOrComment(p[at])) skipComment()
        }

        private fun group() {
            stack.addLast(flags)
            if (p.getOrNull(at + 1) != '?') {
                groups++
                out.append('(')
                at++
                return
            }
            val rest = p.substring(at, minOf(p.length, at + 4))
            when {
                rest.startsWith("(?:") || rest.startsWith("(?=") || rest.startsWith("(?!") || rest.startsWith("(?>") -> {
                    out.append(rest, 0, 3)
                    at += 3
                }
                rest.startsWith("(?<=") || rest.startsWith("(?<!") -> {
                    out.append(rest, 0, 4)
                    at += 4
                }
                rest.startsWith("(?<") -> {
                    groups++
                    val end = p.indexOf('>', at).let { if (it < 0) p.length - 1 else it }
                    out.append(p, at, end + 1)
                    at = end + 1
                }
                else -> flagGroup()
            }
        }

        /** `(?flags)` or `(?flags:`, written with only what Harmony reads as Java does (`d`, `m`, `s`): `i` and `x` are done here. */
        private fun flagGroup() {
            val match = FLAG_GROUP.matchAt(p, at)
            if (match == null) {
                // Neither Java nor Harmony reads it: left for Harmony to refuse.
                out.append("(?")
                at += 2
                return
            }
            val (on, off, end) = match.destructured
            if ('U' in off) throw UnsupportedPatternException("(?-U)")
            if ('u' in off) throw UnsupportedPatternException("(?-u)")
            var next = flags
            for (f in on) next = next.with(f, true)
            for (f in off) next = next.with(f, false)
            at += match.value.length
            val kept = on.filter { it in "dms" }
            val keptOff = off.filter { it in "dms" }
            val written = if (kept.isEmpty() && keptOff.isEmpty()) "" else kept + (if (keptOff.isEmpty()) "" else "-$keptOff")
            if (end == ")") {
                // Flags to the end of the enclosing group: no group of its own.
                stack.removeLast()
                flags = next
                if (written.isNotEmpty()) out.append("(?").append(written).append(')')
            } else {
                flags = next
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
            classEscape(inClass = false)?.let { (text, consumed) ->
                out.append(text)
                at += consumed
                return
            }
            at += 2
            when (e) {
                'b' -> {
                    // \b{g} alone: Java reads any other \b{ as \b and a quantifier, or refuses it.
                    if (p.startsWith("{g}", at)) throw UnsupportedPatternException("\\b{g}")
                    out.append(boundary(true))
                }
                'B' -> out.append(boundary(false))
                'G' -> throw UnsupportedPatternException("\\G")
                'X' -> throw UnsupportedPatternException("\\X")
                'N' -> throw UnsupportedPatternException("\\N{…}")
                'Q' -> {
                    val end = p.indexOf("\\E", at).let { if (it < 0) p.length else it }
                    // Java reads each quoted character as a literal of the run around it.
                    var i = at
                    while (i < end) {
                        val cp = codePoint(p, i, end)
                        i += if (cp > 0xFFFF) 2 else 1
                        literal(Literal(cp, text(cp)))
                    }
                    at = if (end == p.length) end else end + 2
                }
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
                out.append("(?!)")
                return
            }
            if (flags.i) throw UnsupportedPatternException("a back reference under (?i)")
            out.append('\\').append(n)
            // A digit after it is itself, where Harmony would read it as more of the number.
            if (at < p.length && p[at] in '0'..'9') out.append("(?:)")
        }

        /**
         * The escape at [at] that is a literal code point, as Java's `Pattern.escape` reads one, and [at] past it; null, [at]
         * left, where it is none: `\t`, `\n`, `\r`, `\f`, `\a`, `\e`, `\cX`, `\0` and octal digits, `\xNN`, `\x{…}`, `\uNNNN`
         * (two of them a pair), and a backslash before any character that is not a letter or a digit.
         */
        private fun charEscape(): Literal? {
            val e = p.getOrNull(at + 1) ?: return null
            val start = at
            var end = at + 2
            val cp: Int = when (e) {
                't' -> 9
                'n' -> 10
                'r' -> 13
                'f' -> 12
                'a' -> 7
                'e' -> 27
                'c' -> {
                    val control = p.getOrNull(at + 2) ?: return null
                    end++
                    control.code xor 64
                }
                '0' -> {
                    val digits = OCTAL.matchAt(p, at + 2)?.value ?: return null
                    end += digits.length
                    digits.toInt(8)
                }
                'x' -> if (p.getOrNull(at + 2) == '{') {
                    val close = p.indexOf('}', at + 3)
                    val value = (if (close < 0) null else hex(at + 3, close - at - 3)) ?: return null
                    end = close + 1
                    value
                } else {
                    val value = hex(at + 2, 2) ?: return null
                    end += 2
                    value
                }
                'u' -> {
                    val high = hex(at + 2, 4) ?: return null
                    end += 4
                    val low = if (high in 0xD800..0xDBFF && p.startsWith("\\u", end)) hex(end + 2, 4) else null
                    if (low != null && low in 0xDC00..0xDFFF) {
                        end += 6
                        0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
                    } else {
                        high
                    }
                }
                // Java's escape refuses an ASCII letter or digit it doesn't name; any other character is itself.
                else -> if (e < '\u0080' && e.isLetterOrDigit()) {
                    return null
                } else {
                    codePoint(p, at + 1).also { if (it > 0xFFFF) end++ }
                }
            }
            at = end
            // As written where Harmony reads it alike; \x{…}, a pair of \uNNNN and a character past ASCII, which it may not, as
            // the code point.
            return Literal(cp, if (e == 'x' || e == 'u' || cp >= 0x80) text(cp) else p.substring(start, end))
        }

        /**
         * The [digits] hexadecimal digits at [from] as a code point; null where they aren't, there are none, or it is past
         * U+10FFFF. ASCII's alone, as Java reads them: Kotlin's `isDigit` and `toInt(16)` take other scripts' digits, and the
         * latter a sign.
         */
        private fun hex(from: Int, digits: Int): Int? {
            if (digits < 1 || from + digits > p.length) return null
            var value = 0
            for (i in from until from + digits) {
                val c = p[i]
                val digit = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> return null
                }
                value = value * 16 + digit
                if (value > 0x10FFFF) return null
            }
            return value
        }

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
         * A class escape at [at] (`\w`, `\p{L}` …), written out as Java reads it, case-insensitively where `(?i)` is in force:
         * in a class its ranges alone, outside one a class of them. The text and how many characters of the pattern it took;
         * null where [at] holds none.
         */
        private fun classEscape(inClass: Boolean): Pair<String, Int>? {
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
            val text = write(if (negative) complement(ranges) else ranges)
            return (if (inClass) text else "[$text]") to consumed
        }

        /**
         * A class, `[…]`: its escapes written out, `]` first as itself, as Java reads it there, ranges of code points past the
         * BMP written as [write] writes Java's, and under `(?i)` each member as the code points Java takes for it.
         */
        private fun characterClass() {
            out.append('[')
            at++
            if (p.getOrNull(at) == '^') {
                out.append('^')
                at++
            }
            val outer = firstAstral
            firstAstral = null
            if (p.getOrNull(at) == ']') {
                at++
                member(Literal(']'.code, "\\]"))
            }
            var depth = 0
            while (at < p.length) {
                val c = p[at]
                when {
                    flags.x && spaceOrComment(c) -> skipComment()
                    c == '\\' -> {
                        val escaped = classEscape(inClass = true)
                        if (escaped != null) {
                            out.append(escaped.first)
                            at += escaped.second
                            continue
                        }
                        when (p.getOrNull(at + 1)) {
                            'Q' -> {
                                val end = p.indexOf("\\E", at + 2).let { if (it < 0) p.length else it }
                                var i = at + 2
                                while (i < end) {
                                    val cp = codePoint(p, i, end)
                                    i += if (cp > 0xFFFF) 2 else 1
                                    out.append(single(cp, text(cp)))
                                }
                                at = if (end == p.length) end else end + 2
                            }
                            'N' -> throw UnsupportedPatternException("\\N{…}")
                            else -> {
                                val literal = charEscape()
                                if (literal != null) {
                                    member(literal)
                                } else {
                                    // No escape Java reads in a class (\b, a letter of none): Harmony refuses it as Java does.
                                    p.getOrNull(at + 1)?.let(::illegal)
                                    out.append(p, at, minOf(p.length, at + 2))
                                    at += 2
                                }
                            }
                        }
                    }
                    c == '[' -> {
                        depth++
                        out.append(c)
                        at++
                    }
                    c == '&' && p.getOrNull(at + 1) == '&' -> {
                        out.append("&&")
                        at += 2
                    }
                    c == ']' -> {
                        if (depth == 0) {
                            // Harmony's CharClass.add(start, end) marks no code point past the BMP (Kotlin 2.2.20): a class's
                            // range of them matches nothing unless a code point past the BMP is in it alone too. The first one
                            // the class holds is, again. Out when Kotlin fixes it.
                            firstAstral?.let { out.append(text(it)) }
                            firstAstral = outer
                        }
                        out.append(']')
                        at++
                        if (depth-- == 0) return
                    }
                    else -> {
                        val cp = codePoint(p, at)
                        at += if (cp > 0xFFFF) 2 else 1
                        // A - that is no range is written escaped: after a class escape written out, or a member (?i) writes as
                        // several, Harmony would read it as a range from the last code point written.
                        member(Literal(cp, if (cp > 0xFFFF) astral(cp) else if (c == '-') "\\-" else c.toString()))
                    }
                }
            }
            // Unclosed: left as it is, for Harmony to refuse, as Java does.
        }

        /** A class's member, [first] (read already), and the range it begins where a `-` and another literal follow. */
        private fun member(first: Literal) {
            // Java's range reads past (?x)'s whitespace to the - and past it, but looks at the character after the - as it is.
            skipSpaces()
            if (p.getOrNull(at) == '-' && at + 1 < p.length && p[at + 1] != ']' && p[at + 1] != '[') {
                val saved = at
                at++
                skipSpaces()
                val last = if (at >= p.length) {
                    null
                } else if (p[at] == '\\') {
                    charEscape()
                } else {
                    val cp = codePoint(p, at)
                    at += if (cp > 0xFFFF) 2 else 1
                    Literal(cp, if (cp > 0xFFFF) astral(cp) else p[at - 1].toString())
                }
                if (last != null) {
                    out.append(range(first, last))
                    return
                }
                at = saved
            }
            out.append(single(first.cp, first.text))
        }

        /** A class's single member: itself, or under `(?i)` the code points Java takes for it in a class. */
        private fun single(cp: Int, written: String): String {
            if (cp > 0xFFFF && firstAstral == null) firstAstral = cp
            if (!flags.i) return written
            val takes = JavaCase.inClass(cp)
            return if (takes.size == 1) written else takes.joinToString("") { text(it) }
        }

        /** A class's range: as Java's ranges are written ([write]), and under `(?i)` with the code points Java takes beside it. */
        private fun range(first: Literal, last: Literal): String {
            if (last.cp < first.cp) throw IllegalArgumentException("Illegal character range")
            val range = first.cp..last.cp
            if (range.last > 0xFFFF && firstAstral == null) firstAstral = maxOf(range.first, 0x10000)
            val extra = if (flags.i) JavaCase.range(range).joinToString("") { text(it) } else ""
            return write(listOf(range)) + extra
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

    /** The code point at [at] of [text], a pair's where a pair ends before [end]. */
    private fun codePoint(text: String, at: Int, end: Int = text.length): Int =
        if (text[at].isHighSurrogate() && at + 1 < end && text[at + 1].isLowSurrogate()) {
            0x10000 + ((text[at].code - 0xD800) shl 10) + (text[at + 1].code - 0xDC00)
        } else {
            text[at].code
        }

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

    private val QUANTIFIER = Regex("""\{[0-9]+(,[0-9]*)?\}""")
    private val FLAG_GROUP = Regex("""\(\?([idmsuxU]*)(?:-([idmsuxU]*))?(:|\))""")
    private val OCTAL = Regex("""[0-3][0-7]{2}|[0-7]{1,2}""")
}

/** A pattern holding [construct], which this platform's regular expressions can't match as Java's do. */
@DrivineInternalApi
class UnsupportedPatternException(val construct: String) :
    IllegalArgumentException("$construct is not supported on this platform; rewrite the pattern without it.")
