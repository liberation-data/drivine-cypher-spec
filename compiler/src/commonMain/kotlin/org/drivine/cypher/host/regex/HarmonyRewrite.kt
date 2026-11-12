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
 *
 * What it can't make match as Java's does, it refuses by name ([UnsupportedPatternException]): `\G`, `\X`, `\N{…}`,
 * `\b{g}`, a back reference under `(?i)` (Java folds each code point of it, Harmony its own way), a back reference to a
 * group the pattern opens only after it (which Harmony refuses, and Java matches in a repetition), and `(?-U)` and `(?-u)`,
 * which turn off what drivine4j's patterns always have on.
 */
internal object HarmonyRewrite {

    /** [java], written for kotlin.text.Regex on Kotlin/Native and Kotlin/Wasm; it throws where it can't be. */
    fun rewrite(java: String): String = Writer(unquote(java)).write()

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

    private class Writer(private val p: String) {
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
                at = past(at)
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
                    out.append(closers.removeLastOrNull() ?: ")")
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
                '$' -> {
                    flush()
                    out.append(dollar("$"))
                    at++
                }
                '^', '|' -> {
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
            if (p.getOrNull(at + 1) != '?') {
                groups++
                out.append('(')
                at++
                return
            }
            val rest = p.substring(at, minOf(p.length, at + 4))
            when {
                rest.startsWith("(?:") || rest.startsWith("(?>") -> {
                    out.append(rest, 0, 3)
                    at += 3
                }
                // A lookaround in a group of its own: Harmony crashes on one quantified ((?=a)+), which Java reads.
                rest.startsWith("(?=") || rest.startsWith("(?!") || rest.startsWith("(?<=") || rest.startsWith("(?<!") -> {
                    val length = if (rest[2] == '<') 4 else 3
                    out.append("(?:").append(rest, 0, length)
                    closers[closers.lastIndex] = "))"
                    at += length
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
            classEscape()?.let { (ranges, consumed) ->
                out.append(written(ranges))
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
                'Z' -> out.append(dollar("\\Z"))
                // Java's \R repeated takes \r\n whole each time, where Harmony's gives it back (\R{2} in "\r\n").
                'R' -> out.append(if (p.getOrNull(past(at))?.let { it in "*+?{" } == true) "(?>\\r\\n|[\\n\\u000B\\f\\r\\u0085\\u2028\\u2029])" else "\\R")
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
                out.append("(?!)")
                return
            }
            if (flags.i) throw UnsupportedPatternException("a back reference under (?i)")
            out.append('\\').append(n)
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
            at++
            out.append(written(clazz(consume = true)))
        }

        /**
         * The class at [at], past its `[` (or an intersection's right side, which takes no `]` where it ends), read as
         * Pattern.clazz reads it: `^` first, right after the `[`, the complement; each member ([member]) and nested class in a
         * union; `&&` the intersection of what is before it with what is after it, to the class's end. Java keeps the members
         * a BitClass holds (`bits`) apart from the others until an `&&` or the end, which its `]` alone, and `&&` after
         * nothing, depend on.
         */
        private fun clazz(consume: Boolean): List<IntRange> {
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

    private val QUANTIFIER = Regex("""\{[0-9]+(,[0-9]*)?\}""")
    private val FLAG_GROUP = Regex("""\(\?([idmsuxUc]*)(?:-([idmsuxUc]*))?(:|\))""")
}

/** A pattern holding [construct], which this platform's regular expressions can't match as Java's do. */
@DrivineInternalApi
class UnsupportedPatternException(val construct: String) :
    IllegalArgumentException("$construct is not supported on this platform; rewrite the pattern without it.")
