package org.drivine.cypher.oracle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.drivine.cypher.search.TextQuery
import java.io.File
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Java's answers for the regular expressions SQLite's host functions see (`regex.json`), which `RegexOracleTest` checks
 * every target's `JavaPattern` against (#108): drivine4j's `regexp`, `drivine_regex_group`, `drivine_regexp_replace` and
 * `drivine_regexp_count` read an expression as its `SqlitePatterns` does, Java's with POSIX's classes named as Java names
 * them, compiled with `UNICODE_CHARACTER_CLASS`.
 *
 * - `patterns`: for each pattern, over every text of [TEXTS] and host.yaml's, whether it is found, the groups of its first
 *   match, how many times it matches, and every match replaced by `<\1>` (`<>` where it has no group), or Java's refusal
 *   of it. The patterns: each the compiler writes on SQLite over the corpus and the mutants (`compiler`), each a full-text
 *   search gives `drivine_regexp_count` and `REGEXP` (`search`), each of host.yaml's steps (`host`), and users' `=~`
 *   patterns ([USER]), as written (`user`) and as `=~` wraps them (`user=~`).
 * - `caseFold`: for each code point case-insensitive matching takes another for, whether `(?i)` and it, alone and in a
 *   class, matches each code point its case mappings and its fold give.
 */
object Regexes {

    private val json = ObjectMapper()
    private val yaml = ObjectMapper(YAMLFactory())

    /** drivine4j's `SqlitePatterns.POSIX`. */
    val POSIX = linkedMapOf(
        "[:alnum:]" to "\\p{Alnum}", "[:alpha:]" to "\\p{Alpha}", "[:digit:]" to "\\p{Digit}", "[:space:]" to "\\p{Space}",
        "[:upper:]" to "\\p{Upper}", "[:lower:]" to "\\p{Lower}", "[:punct:]" to "\\p{Punct}",
    )

    fun java(expression: String): Pattern =
        Pattern.compile(POSIX.entries.fold(expression) { text, (posix, java) -> text.replace(posix, java) }, Pattern.UNICODE_CHARACTER_CLASS)

    /** drivine4j's `SqliteRegexReplaceFunction.java`: `\1` names a group, anything else is itself. */
    fun javaReplacement(replacement: String): String {
        val written = StringBuilder()
        var at = 0
        Regex("""\\(\d)""").findAll(replacement).forEach { match ->
            written.append(Matcher.quoteReplacement(replacement.substring(at, match.range.first))).append('$').append(match.groupValues[1])
            at = match.range.last + 1
        }
        return written.append(Matcher.quoteReplacement(replacement.substring(at))).toString()
    }

    fun write(spec: File, out: File) {
        val host = yaml.readTree(File(spec, "host.yaml"))["cases"].filter { it["name"].textValue() in FUNCTIONS }
            .flatMap { case -> case["steps"].toList() }
        val patterns = linkedMapOf<String, Source>()
        fun add(pattern: String, source: String, label: String?, of: String? = null) {
            val known = patterns.getOrPut(pattern) { Source(label, of) }
            known.sources += source
        }
        compilerPatterns(out).forEach { (pattern, cypher) -> add(pattern, "compiler", null, cypher) }
        searchPatterns(out).forEach { (pattern, query) -> add(pattern, "search", null, query) }
        for (step in host) {
            val args = step["args"]
            val pattern = (if (step["function"].textValue() == "regexp") args[0] else args[1]).textValue() ?: continue
            add(pattern, "host", null)
        }
        for ((pattern, label) in USER) {
            add(pattern, "user", label)
            add("^(?:$pattern)\\z", "user=~", label)
        }
        val hostTexts = host.mapNotNull { step -> (if (step["function"].textValue() == "regexp") step["args"][1] else step["args"][0]).textValue() }
        val texts = (TEXTS + hostTexts).distinct()
        val cases = patterns.map { (pattern, source) -> case(pattern, source, texts) }
        val written = linkedMapOf(
            "texts" to texts,
            "patterns" to cases,
            "caseFold" to caseFold(),
        )
        // Every character outside ASCII escaped, so a lone surrogate (Java's replaceAll of an empty match splits a pair) is itself.
        val ascii = ObjectMapper().also { it.factory.enable(com.fasterxml.jackson.core.JsonGenerator.Feature.ESCAPE_NON_ASCII) }
        File(out, "regex.json").writeText(ascii.writeValueAsString(written))
        println("regex.json: ${cases.size} patterns (${patterns.values.flatMap { it.sources }.groupingBy { it }.eachCount()}), ${texts.size} texts.")
    }

    private class Source(val label: String?, val of: String?) {
        val sources = linkedSetOf<String>()
    }

    private fun case(pattern: String, source: Source, texts: List<String>): Map<String, Any?> {
        val compiled = try {
            java(pattern)
        } catch (e: PatternSyntaxException) {
            return linkedMapOf("pattern" to pattern, "sources" to source.sources, "label" to source.label, "of" to source.of, "error" to e.description)
        }
        val groupCount = compiled.matcher("").groupCount()
        val replacement = if (groupCount >= 1) "<\\1>" else "<>"
        val results = texts.map { text ->
            val matcher = compiled.matcher(text)
            val found = matcher.find()
            val groups = if (found) (0..groupCount).map { matcher.group(it) } else null
            matcher.reset()
            var count = 0
            while (matcher.find()) count++
            val replaced = compiled.matcher(text).replaceAll(javaReplacement(replacement))
            listOf(found, groups, count, replaced)
        }
        return linkedMapOf(
            "pattern" to pattern, "sources" to source.sources, "label" to source.label, "of" to source.of,
            "groupCount" to groupCount, "replacement" to replacement, "results" to results,
        )
    }

    /** Each pattern of the SQL of each SQLite statement in the corpus and the mutants, as `./gradlew jvmOracle` wrote them, with its Cypher. */
    private fun compilerPatterns(out: File): Map<String, String> {
        val patterns = linkedMapOf<String, String>()
        for (file in listOf("corpus.jsonl", "mutants.jsonl")) for (line in File(out, file).readLines()) {
            val entry = json.readTree(line)
            val request = runCatching { json.readTree(entry["request"].textValue()) }.getOrNull()?.takeIf { it.isObject } ?: continue
            if (request["store"]?.textValue() != "sqlite" || !request.has("cypher")) continue
            val answer = json.readTree(entry["answer"].textValue())
            val sql = answer["sql"]?.textValue() ?: continue
            val all = (listOf(sql) + (answer["setup"]?.map { it.textValue() } ?: emptyList())).joinToString("\n")
            for (pattern in SqliteSqlPatterns.of(all)) patterns.putIfAbsent(pattern, request["cypher"].textValue())
        }
        // =~'s pieces around a user's pattern are measured whole, around users' patterns (USER).
        patterns.remove("^(?:")
        patterns.remove(")\\z")
        // A pattern as a nested statement's JSON holds it, its backslashes doubled, is no pattern a host is given.
        return patterns.filterKeys { pattern -> pattern.replace("\\\\", "\\").let { it == pattern || it !in patterns } }
    }

    /**
     * Each full-text search pattern: the parameters (`_cy_ft…`) of each SQLite search in the corpus, and [TextQuery]'s terms,
     * as written and looking ahead, for [QUERIES].
     */
    private fun searchPatterns(out: File): Map<String, String> {
        val patterns = linkedMapOf<String, String>()
        for (line in File(out, "corpus.jsonl").readLines()) {
            val entry = json.readTree(line)
            val request = runCatching { json.readTree(entry["request"].textValue()) }.getOrNull()?.takeIf { it.isObject } ?: continue
            if (request["store"]?.textValue() != "sqlite" || !request.has("search")) continue
            val parameters = json.readTree(entry["answer"].textValue())["parameters"] ?: continue
            for ((key, value) in parameters.properties()) if (key.startsWith("_cy_ft") && value.isTextual) {
                patterns.putIfAbsent(value.textValue(), request["search"]["query"]?.asText() ?: "")
            }
        }
        for (query in QUERIES) {
            val parsed = runCatching { TextQuery.parse(query) }.getOrNull() ?: continue
            for (term in parsed.scored + parsed.required + parsed.excluded) {
                patterns.putIfAbsent(term, query)
                patterns.putIfAbsent(TextQuery.lookingAhead(term), query)
            }
        }
        return patterns
    }

    /**
     * Each code point case-insensitive matching takes another for (its upper case's lower case not itself, or another's
     * that is it), with the code points its case mappings and its fold give, and whether `(?i)` and the code point, alone
     * and in a class, matches each of them, as Java answers.
     */
    private fun caseFold(): List<Map<String, Any>> {
        fun fold(cp: Int) = Character.toLowerCase(Character.toUpperCase(cp))
        val groups = (0..Character.MAX_CODE_POINT).filter { it !in 0xD800..0xDFFF }.groupBy(::fold)
        val out = mutableListOf<Map<String, Any>>()
        for (cp in 0..Character.MAX_CODE_POINT) {
            if (cp in 0xD800..0xDFFF) continue
            val group = groups[fold(cp)].orEmpty()
            val candidates = (group + listOf(cp, Character.toUpperCase(cp), Character.toLowerCase(cp), Character.toTitleCase(cp), fold(cp)))
                .distinct().sorted()
            if (candidates.size < 2) continue
            val text = String(Character.toChars(cp))
            val alone = Pattern.compile("(?i)" + Pattern.quote(text), Pattern.UNICODE_CHARACTER_CLASS)
            val inClass = Pattern.compile("(?i)[" + (if (cp < 0x80 && !Character.isLetterOrDigit(cp)) "\\" else "") + text + "]", Pattern.UNICODE_CHARACTER_CLASS)
            out += linkedMapOf(
                "cp" to cp,
                "candidates" to candidates,
                "alone" to candidates.map { alone.matcher(String(Character.toChars(it))).matches() },
                "inClass" to candidates.map { inClass.matcher(String(Character.toChars(it))).matches() },
            )
        }
        return out
    }

    private val FUNCTIONS = setOf("regexp", "drivine_regex_group", "drivine_regexp_replace")

    /** Queries for [TextQuery], in many scripts. */
    private val QUERIES = listOf(
        "Graph databases", "kiwi", "kiw*", "\"of soup\"", "\"la la\"", "café", "naïve résumé", "кот", "東京タワー", "한국어",
        "😀 emoji", "𝐀𝐁𝐂", "क्षत्रिय", "कि", "data²", "PN-4471-B", "C++", "straße", "İstanbul", "ⓐbⓐ", "x1 y22", "foo_bar",
        "Ⅻ", "٢٠٢٤", "２０２４", "a.b", "cafe\u0301", "e\u0301\u0301", "Σίσυφος", "+kotlin graph -python", "pre*", "domain\\user",
        "https://example.com/docs", "a\u200Db", "𐐀𐐨", "👍🏽",
    )

    /** Texts every pattern is run over, beside host.yaml's. */
    val TEXTS: List<String> = buildList {
        addAll(listOf("", "a", "b", "ab", "abc", "ABC", "aBc", "aa", "aab", "abab", "xyz", "hello world", "Hello, World!", "foo_bar baz-qux",
            "x1 y22 z333", "  padded  ", "tab\there", "{x}", "a{3}", "aaa", "aaaa"))
        addAll(listOf("café", "CAFÉ", "naïve résumé", "Ærøskøbing", "straße", "STRASSE", "İstanbul ıi", "Σίσυφος ς", "ﬁle", "Kelvin \u212A k",
            "cafe\u0301", "e\u0301\u0301", "a\u20DD", "क्षत्रिय", "कि", "हिन्दी", "東京タワー", "한국어", "😀", "a😀b", "𝐀𝐁𝐂 bold",
            "𝟘𝟙 math", "𐐀𐐨", "👍🏽", "👨\u200D👩\u200D👧", "٢٠٢٤", "２０２４", "१२३", "Ⅻ ⅻ", "²³", "½", "a\u200Db", "a\u200Cb", "ⓐbⓐ"))
        for (end in listOf("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029", "\u000B", "\u000C")) addAll(listOf("a$end", "${end}a", "a${end}b", "ab$end"))
        addAll(listOf("a\u00A0b", "\u2003x", "\u3000y", "a\u200Bb", "\uFEFFz", "\u180E1", "\u16801"))
        addAll(listOf("a.b", "a+b", "(x)", "[y]", "\$5.00", "50%", "«quoted»", "—dash—", "¿qué?", "a\\b", "a'b\"c", ".*", "a(b", "a]", "a}",
            "x{", "-", "'", "#", "@", "\u0007\u001B", "A", "\n", "\u0000"))
        addAll(listOf("2024-03-15", "-0044-03-15", "2024-03-15T10:30:00Z", "10:15:00.5-0530", "+00:00", "-0000", "+02:00", "-0530",
            "P1Y2M3DT4H5M6.5S", "PT0S", "NaN", " -NaN ", "Infinity", "+Infinity", "1.5e3", "1x5", "0x1F", "007", "1000", "  42  ", "true", "null",
            "2024-2024", "12-12"))
        addAll(listOf("Keanu", "keanu", "KEANU", "Carrie-Anne", "Laurence", "Hugo", "cat", "dog", "catalog"))
        addAll(listOf("graph databases store data", "kiwi kiwi kiwi", "kiwi,kiwi.kiwi!", "kiwis kiwi_kiwi kiwi2 akiwi", "kiwi\nkiwi\n",
            "of soup of soup", "la la land", "café café cafés caféé", "кот кот котик", "graph data\u0085store", "pn-4471-b order", "c++ (.*)",
            "https://example.com/docs", "domain\\user", "क्षत्रिय कि", "data2 data_ data²", "graph\u2028data", "東京タワー 東京", "😀 emoji😀",
            "𝐀𝐁𝐂 x", "straße strasse", "σίσυφος", "naïve résumé naive", "foo_bar foo", "x1 y22", "a\u200Db a"))
        // Code points past the BMP whose low 16 bits are syntax (Harmony misreads them in a pattern), and case pairs.
        addAll(listOf("\uD800\uDC26", "\uD800\uDC28 (", "\uD800\uDC5B[", "\uD800\uDC5D", "x\uD800\uDC00\uD800\uDC0Dy", "\uD801\uDC00\uD801\uDC28\uD801\uDC4F",
            "\uD83D\uDE4F\uD83D\uDE50", "\u01C5 \u01C4 \u01C6", "\u1E9E ß", "\u03A3 \u03C3 \u03C2", "a b\n#c", "LATIN"))
    }

    /** Users' `=~` patterns, each with the construct it exercises. */
    val USER: List<Pair<String, String>> = listOf(
        "^abc$" to "$", "abc$" to "$", "b$" to "$", "^$" to "$", "^a\\z" to "\\z", "a\\Z" to "\\Z", "\\Aab" to "\\A",
        "cat|dog" to "alternation", "^(?:a|ab)(c|bcd)$" to "alternation",
        "a{2,3}" to "{m,n}", "ab*?c" to "lazy", "a.*?b" to "lazy", "a*+a" to "possessive", "a++b" to "possessive", "x{2}+" to "possessive",
        "(?>a+)b" to "atomic group", "a{,3}" to "{,n}",
        "(?i)keanu" to "(?i)", "(?i)straße" to "(?i) Unicode", "(?i)café" to "(?i) Unicode", "(?i)σίσυφος" to "(?i) Unicode",
        "(?i)k" to "(?i) Kelvin sign", "(?i)i" to "(?i) dotless/dotted i", "(?i)𐐨" to "(?i) astral", "(?i)[a-c]+" to "(?i) class",
        "(?i)[à-ÿ]+" to "(?i) class Unicode", "a(?i)b" to "(?i) mid-pattern", "(?i:a)b" to "(?i:…)", "(?-i)a" to "(?-i)",
        "(?s)a.b" to "(?s)", "(?m)^b$" to "(?m)", "(?x) a b # c" to "(?x)", "(?U)\\w+" to "(?U)", "(?u)(?i)é" to "(?u)", "(?d)a.b" to "(?d)",
        "\\d+" to "\\d", "\\D" to "\\D", "\\w+" to "\\w", "\\W" to "\\W", "\\s" to "\\s", "\\S+" to "\\S", "\\bkiwi\\b" to "\\b",
        "\\b\\w+\\b" to "\\b", "\\Bi\\B" to "\\B", "\\h" to "\\h", "\\v" to "\\v", "a\\Rb" to "\\R", "\\X" to "\\X",
        "[\\w-]+" to "\\w in a class", "[^\\W_]+" to "\\W in a class", "[\\d.]+" to "\\d in a class", "[\\s,]+" to "\\s in a class",
        "\\p{L}+" to "\\p{L}", "\\p{Lu}" to "\\p{Lu}", "\\pL" to "\\pL", "\\P{L}" to "\\P{L}", "\\p{IsAlphabetic}" to "\\p{IsAlphabetic}",
        "\\p{IsL}" to "\\p{IsL}", "\\p{Alpha}" to "\\p{Alpha}", "\\p{Alnum}+" to "\\p{Alnum}", "\\p{Punct}" to "\\p{Punct}",
        "\\p{Upper}" to "\\p{Upper}", "\\p{Lower}" to "\\p{Lower}", "\\p{Space}" to "\\p{Space}", "\\p{IsLatin}" to "\\p{IsLatin}",
        "\\p{InGreek}" to "\\p{InGreek}", "\\p{javaLowerCase}" to "\\p{javaLowerCase}", "\\p{Nd}" to "\\p{Nd}", "\\p{IsEmoji}" to "\\p{IsEmoji}",
        "\\p{So}" to "\\p{So}", "\\p{M}" to "\\p{M}", "\\p{XDigit}" to "\\p{XDigit}", "\\p{Sc}" to "\\p{Sc}", "\\p{IsDigit}" to "\\p{IsDigit}",
        "[\\p{L}\\p{N}]+" to "\\p{…} in a class",
        "[[:alpha:]]+" to "POSIX class", "[[:digit:]]" to "POSIX class", "[[:space:]]" to "POSIX class", "[[:upper:]]" to "POSIX class",
        "[[:lower:]]" to "POSIX class", "[[:punct:]]" to "POSIX class", "[^[:alnum:]_]" to "POSIX class",
        "." to ".", "^.$" to ".", "a.b" to ".", "^.{3}$" to ".", ".+" to ".",
        "(a)\\1" to "backreference", "(\\w)\\1" to "backreference", "(?<y>[0-9]{4})-\\k<y>" to "named group", "(?<y>\\d{4})" to "named group",
        "(a)|b" to "group taking no part", "(a(b)?)+" to "repeated group", "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)\\10" to "backreference \\10",
        "(?<=a)b" to "lookbehind", "(?<!a)b" to "lookbehind", "a(?=b)" to "lookahead", "a(?!b)" to "lookahead", "(?<=a+)b" to "unbounded lookbehind",
        "(?<=\\w{1,3})b" to "lookbehind",
        "\\Q.*\\E" to "\\Q…\\E", "a\\Q(b\\E" to "\\Q…\\E",
        "[a-z&&[^aeiou]]+" to "class intersection &&", "[a-c[x-z]]+" to "class union (nested class)", "[\\p{L}&&\\p{Lu}]" to "class intersection &&",
        "\\x{1F600}" to "\\x{…}", "\\x41" to "\\xNN", "\\u00e9" to "\\uNNNN", "\\uD83D\\uDE00" to "surrogate pair escape", "\\0101" to "octal escape",
        "\\cJ" to "\\cX", "\\e" to "\\e", "\\a" to "\\a", "\\-" to "escaped punctuation", "\\'" to "escaped punctuation", "\\#" to "escaped punctuation",
        "\\@" to "escaped punctuation", "\\ " to "escaped punctuation",
        "😀" to "astral literal", "[😀-😂]" to "astral range", "^[^a]$" to "negated class, astral", "a]" to "lone ]", "a}" to "lone }",
        "[]a]" to "] first in a class", "[^]a]" to "] first in a class", "[a-]" to "- last in a class", "x{" to "lone {", "[a\\]]" to "escaped ] in a class",
        "" to "empty pattern", "x*" to "empty matches", "\\b" to "empty matches", "(?=a)" to "empty matches",
        "é" to "precomposed literal", "e\u0301" to "decomposed literal", "[é]" to "class literal", "ß" to "literal ß",
        "(" to "syntax error", "[a" to "syntax error", "a**" to "syntax error", "\\" to "syntax error", "\\k<nope>" to "syntax error",
        "\\2(a)" to "forward backreference", "(a)\\2" to "backreference past the groups", "\\y" to "unknown escape", "a{3,2}" to "syntax error",
        "*a" to "syntax error", "(?<1a>x)" to "syntax error", "\\G" to "\\G",
        // Refused off the JVM by name, with what Java reads them as.
        "\\N{LATIN SMALL LETTER A}" to "\\N{…}", "\\b{g}" to "\\b{g}", "(?-U)\\w+" to "(?-U)", "(?-u)(?i)é" to "(?-u)",
        // Properties, by each name Java reads them by, and with case-insensitivity.
        "\\p{IsLatn}+" to "script alias", "\\p{sc=Greek}+" to "\\p{sc=…}", "\\p{script=greek}" to "\\p{script=…}",
        "\\p{blk=Basic Latin}+" to "\\p{blk=…}", "\\p{InBasic_Latin}+" to "block", "\\p{InBasicLatin}" to "block", "\\p{block=Greek}" to "block alias",
        "\\p{InBasic+Latin}" to "unknown property", "\\p{Foo}" to "unknown property", "\\p{IsFoo}" to "unknown property", "\\p{}" to "unknown property",
        "\\p{IsWhite_Space}" to "\\p{IsWhite_Space}", "\\p{gc=Lu}" to "\\p{gc=…}", "\\p{IsAlpha}" to "\\p{IsAlpha}", "\\p{alpha}" to "\\p{alpha}",
        "\\p{javaMirrored}" to "\\p{java…}", "\\p{IsUnknown}" to "script", "\\p{IsEmoji_Presentation}" to "\\p{IsEmoji…}", "\\p{LC}" to "\\p{LC}",
        "\\p{IsLu}" to "\\p{IsLu}", "\\p{Lt}" to "\\p{Lt}", "\\P{Alnum}+" to "\\P{…}", "[^\\p{L}\\d]+" to "negated class",
        "(?i)\\p{Lu}+" to "(?i) property", "(?i)\\p{Lower}" to "(?i) property", "(?i)[\\p{Lu}]" to "(?i) property in a class", "(?i)\\p{IsLowercase}" to "(?i) property",
        "(?i)\\p{javaLowerCase}" to "(?i) property", "(?i)\\w+" to "(?i) class escape", "(?i:\\p{Lu})x" to "(?i) property",
        // Code points past the BMP: case-insensitive, and those whose low 16 bits are syntax.
        "(?i)\uD801\uDC00+" to "(?i) astral", "(?i)[\uD801\uDC00-\uD801\uDC27]+" to "(?i) astral range", "(?i)\\x{10400}" to "(?i) astral",
        "\\x{10026}" to "astral misread", "\uD800\uDC26" to "astral misread", "[\uD800\uDC26\uD800\uDC5B]" to "astral misread",
        "\uD800\uDC28+" to "astral misread", "[\\x{10000}-\\x{1005D}]+" to "astral misread", "\\Q\uD800\uDC5B\\E" to "astral misread",
        "[\\x{1F600}-\\x{1F64F}]" to "astral range",
        // Comments mode in a class, flags off and on, and (?U) on.
        "(?x)[ a b ]+ # c" to "(?x) class", "(?x)a\\ b" to "(?x)", "(?i)a(?-i)b" to "(?-i)", "(?s:.)+" to "(?s:…)", "(?U)\\d" to "(?U)",
    )

    /** The spec's directory, and the oracle's (corpus.jsonl and mutants.jsonl already written there). */
    @JvmStatic
    fun main(args: Array<String>) = write(File(args[0]), File(args[1]))
}
