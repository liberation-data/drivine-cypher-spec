package org.drivine.cypher.store.schema

import org.drivine.cypher.store.Stamps
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.schema.SqlText.Escape
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.RangeIndexSpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [SqlText] reads the columns of an index's text past what is quoted: a property or a label may hold a comma, a
 * parenthesis or a quote, and a column read wrong there would make an index Drivine made look like another item.
 *
 * It reads a quoted text with a loop, [SqlText.scanQuoted], not a regex, which recursed and overflowed the stack on a long
 * or quote-heavy one (drivine-cypher-spec#258): the loop is tested on the edges of each form, and each reading built on it
 * against the regex it stands for, on texts short enough for the regex.
 */
class SqlTextTest {

    @Test
    fun aColumnListIsSplitOnlyAtTheCommasOutsideQuotesAndParentheses() {
        val columns = """(CASE json_type(props, '$."a,b"') WHEN 'text' THEN 1 END), (props ->> 'it''s, (x)'), "c,d""""
        assertEquals(
            listOf("""(CASE json_type(props, '$."a,b"') WHEN 'text' THEN 1 END)""", "(props ->> 'it''s, (x)')", "\"c,d\""),
            SqlText.split(columns),
        )
    }

    @Test
    fun aParenthesisClosesPastWhatIsQuoted() {
        val text = "ON t ((props ->> ')''(') , x) WHERE y"
        val open = text.indexOf('(')
        assertEquals(text.indexOf(" WHERE") - 1, SqlText.closing(text, open))
        assertNull(SqlText.closing("(props ->> 'x)", 0), "a quote that never closes")
    }

    @Test
    fun onlyParenthesesThatWrapTheWholeAreTakenOff() {
        assertEquals("props ->> 'name'::text", SqlText.unwrapped(" ((props ->> 'name'::text)) "))
        assertEquals("(a) || (b)", SqlText.unwrapped("(a) || (b)"))
        assertEquals("lower(')')", SqlText.unwrapped("(lower(')'))"))
    }

    @Test
    fun aLiteralsBodyAndItsJsonAreRead() {
        assertEquals("it's", SqlText.unquoted("it''s"))
        assertEquals("a\"b", SqlText.jsonString("\"a\\\"b\""))
        assertEquals("Person", SqlText.jsonLabel("[\"Person\"]"))
        assertNull(SqlText.jsonString("name"), "not a JSON string")
        assertNull(SqlText.jsonLabel("[\"A\", \"B\"]"), "not one label")
    }

    private fun sql(text: String, at: Int = 0) = SqlText.scanQuoted(text, at, '\'', Escape.DOUBLED)
    private fun json(text: String, at: Int = 0) = SqlText.scanQuoted(text, at, '"', Escape.BACKSLASH)
    private fun key(text: String, at: Int = 0) = SqlText.scanQuoted(text, at, '"', Escape.BACKSLASH_IN_LITERAL)

    @Test
    fun anEmptyQuotedTextClosesAtOnce() {
        assertEquals(2, sql("''"))
        assertEquals(2, json("\"\""))
        assertEquals(2, key("\"\""))
        assertEquals(2, sql("''x"))
    }

    @Test
    fun aDoubledQuoteAtTheStartAndTheEndIsPartOfTheText() {
        assertEquals(4, sql("''''"))
        assertEquals(7, sql("'''a'''"))
        assertEquals(7, sql("'''a''' x"))
        assertEquals(7, key("\"''a''\" x"))
        assertEquals(-1, sql("'''"), "a doubled quote with no close after it")
    }

    @Test
    fun anEscapedBackslashBeforeTheClosingQuoteDoesNotEscapeIt() {
        assertEquals(5, json("\"a\\\\\""))
        assertEquals(5, key("\"a\\\\\""))
        assertEquals(-1, json("\"a\\\""), "an escaped quote is no close")
        assertEquals(5, json("\"a\\\"\""))
        assertEquals(5, key("\"a\\'\""), "a backslash escapes a single quote in a literal too")
        assertEquals(4, sql("'a\\'"), "a backslash escapes nothing in SQL")
        assertEquals(6, json("\"it's\""), "a single quote is any character in JSON")
    }

    @Test
    fun anUnclosedQuotedTextIsMinusOne() {
        assertEquals(-1, sql("'abc"))
        assertEquals(-1, sql("'ab''"))
        assertEquals(-1, json("\"abc"))
        assertEquals(-1, json("\"ab\\"), "a backslash at the end escapes nothing")
        assertEquals(-1, key("\"it's\""), "a lone single quote ends the literal the key is in")
        assertEquals(-1, key("\"it''s"))
    }

    @Test
    fun aQuotedTextIsReadOnlyFromItsOpeningQuote() {
        assertEquals(-1, sql("'a'", 3), "at the end")
        assertEquals(-1, sql("'a'", 10), "past the end")
        assertEquals(-1, sql("'a'", -1))
        assertEquals(-1, sql("", 0))
        assertEquals(-1, sql("x'a'", 0), "no quote at at")
        assertEquals(-1, json("'a'", 0), "another quote at at")
        assertEquals(7, sql("x = 'a' || 'b'", 4))
        assertEquals(14, sql("x = 'a' || 'b'", 11))
    }

    @Test
    fun aLongOrQuoteHeavyTextIsReadWithoutOverflowing() {
        assertEquals(400_002, sql("'" + "k".repeat(400_000) + "'"))
        assertEquals(20_002, sql("'" + "''".repeat(10_000) + "'"))
        assertEquals(20_002, json("\"" + "\\\"".repeat(10_000) + "\""))
        assertEquals(20_002, json("\"" + "\\\\".repeat(10_000) + "\""))
        assertEquals(20_002, key("\"" + "''".repeat(10_000) + "\""))
        assertEquals(20_001, SqlText.greedyClose("'" + "''".repeat(10_000) + "'", 0))
        assertEquals(19_999, SqlText.greedyClose("'" + "''".repeat(10_000), 0))
    }

    @Test
    fun greedyCloseIsWhereTheRegexClosedALiteralWithNothingAfterIt() {
        assertEquals(5, SqlText.greedyClose("'a''b'", 0))
        assertEquals(2, SqlText.greedyClose("'a''b", 0), "unclosed: the first quote of its last doubled one")
        assertEquals(-1, SqlText.greedyClose("'ab", 0))
        assertEquals(-1, SqlText.greedyClose("'ab'", 4))
    }

    /** [count] texts of [alphabet]'s pieces, short enough for the regexes the readings stand for. */
    private fun texts(alphabet: List<String>, count: Int, seed: Int): List<String> {
        val random = Random(seed)
        return List(count) { buildString { repeat(random.nextInt(0, 14)) { append(alphabet[random.nextInt(alphabet.size)]) } } }
    }

    /** The regexes the compiler read a SQL literal's body and a JSON string's with, before #258. */
    private val body = "[^']*(?:''[^']*)*"
    private val jsonBody = """[^"\\]*(?:\\.[^"\\]*)*"""

    /**
     * A SQL literal's regex, backtracking, could close it at the first quote of a doubled one where nothing in particular
     * follows: its close is the one quote not followed by another, as [SqlText.scanQuoted] reads it; and with nothing after,
     * the regex's own answer is [SqlText.greedyClose]'s.
     */
    @Test
    fun scanQuotedReadsWhatTheRegexOfEachFormRead() {
        val forms = listOf(
            Triple('\'', Escape.DOUBLED, Regex("^'$body'(?!')")),
            Triple('"', Escape.BACKSLASH, Regex("^\"$jsonBody\"")),
            Triple('"', Escape.BACKSLASH_IN_LITERAL, Regex("""^"[^"'\\]*(?:(?:\\.|'')[^"'\\]*)*"""")),
        )
        for (text in texts(listOf("'", "''", "\"", "\\", "a", " "), 5_000, 258)) for (at in text.indices) for ((quote, escape, regex) in forms) {
            val expected = if (text[at] == quote) regex.find(text.substring(at))?.let { at + it.value.length } ?: -1 else -1
            assertEquals(expected, SqlText.scanQuoted(text, at, quote, escape), "$escape at $at of <$text>")
            if (escape == Escape.DOUBLED) {
                val greedy = if (text[at] == quote) Regex("^'$body'").find(text.substring(at))?.let { at + it.value.length - 1 } ?: -1 else -1
                assertEquals(greedy, SqlText.greedyClose(text, at), "greedy at $at of <$text>")
            }
        }
    }

    @Test
    fun literalFindsWhatTheRegexFound() {
        val alphabet = listOf("'", "''", "x ", "'::text", "props ->> '", "props -> '", "::text", ") > 0", "a", "JSON_VALUE(PROPS, '", "\"")
        val readings = listOf(
            Regex("props ->>? '") to "::text",
            Regex("props ->> '") to "::text",
            Regex("""instr\(labels, '""") to ") > 0",
            Regex("""JSON_VALUE\(\s*"?PROPS"?[^']*'""", RegexOption.IGNORE_CASE) to "",
            Regex("'") to "",
        )
        for (text in texts(alphabet, 10_000, 2580)) for ((head, tail) in readings) {
            val regex = Regex(head.pattern + "($body)'" + Regex.escape(tail), head.options)
            val expected = regex.findAll(text).map { Triple(it.range.first, it.groupValues[1], it.range.last + 1) }.toList()
            val found = SqlText.literals(text, head, tail).map { Triple(it.head.range.first, it.body, it.end) }.toList()
            assertEquals(expected, found, "${head.pattern} … $tail in <$text>")
        }
    }

    @Test
    fun arrowReadReadsWhatTheRegexRead() {
        val regex = Regex("""^\(([\w.]+) -> ('$body')\)$""")
        val alphabet = listOf("(", ")", "n1.props", " -> '", "'", "''", "a", ".", " ", "é", "\n")
        for (text in texts(alphabet, 20_000, 25_800) + listOf("(n1.props -> 'a''b')", "(n1.props -> '')", "(n1.props -> 'a')\n")) {
            val expected = regex.matchEntire(text)?.let { it.groupValues[1] to it.groupValues[2] }
            assertEquals(expected, SqlText.arrowRead(text), "<$text>")
        }
    }

    /**
     * The keys a full-text index's column reads on Postgres, each key's read and the stamp in turn, are those the regex
     * `props ->>? '…'::text|(?<![\w'".])stamp(?![\w'"])` found: the stamp only where no word, quote or `.` is before it and
     * no word or quote after it, at the column's ends too, and a read whose literal no `::text` follows passed over.
     */
    @Test
    fun aPostgresFullTextIndexReadsTheKeysTheRegexFound() {
        val regex = Regex("""props ->>? '($body)'::text|(?<![\w'".])stamp(?![\w'"])""")
        val ddl = PostgresDdl("g")
        val schema = PostgresSchema(ddl)
        val spec = FullTextIndexSpec("Doc", listOf("never"))
        val name = ddl.objectName(PostgresDdl.FULLTEXT, listOf("Doc", "never"))
        val alphabet = listOf("props ->> '", "props -> '", "'::text", "'::jsonb", "'", "''", "stamp", "a", ".", "\"", " ", "_", "(", ")")
        val cases = listOf(
            "stamp", "(stamp)", "xstamp", "stampx", "\"stamp\"", "n.stamp", "'stamp'", "stamp.x", "props ->> 'stamp'::text",
            "props -> 'a'::jsonb || props ->> 'b'::text", "COALESCE(stamp, ''::text) || COALESCE((props ->> 'a stamp'::text), ''::text)",
        )
        for (column in cases + texts(alphabet, 5_000, 2581)) {
            val keys = regex.findAll(column).map { if (it.value == "stamp") Stamps.PROPERTY else SqlText.unquoted(it.groupValues[1]) }.distinct().toList()
            val rows = listOf(
                mapOf("object" to name, "type" to "i", "table" to "g_node", "unique" to false, "method" to "gin", "where" to "labels @> '[\"Doc\"]'::jsonb", "columns" to listOf(column)),
            )
            assertEquals(ItemForm.Mismatch(name, "an index over $keys", true), schema.formOf(spec, rows), "<$column>")
        }
    }

    /**
     * The label a column of an index on Oracle tests, `$[*]?(@ == "Person")` or `$?(@ == "Person")` in its `JSON_EXISTS2`'s
     * literal, is the one the regex `\$(?:\[\*])?\?\(@ == ("…")\)` read whole: none where anything else is in the path. The
     * index read is the one over the key alone, of every node, which tests no label.
     */
    @Test
    fun anOracleIndexTestsTheLabelTheRegexRead() {
        val regex = Regex("""\$(?:\[\*])?\?\(@ == ("$jsonBody")\)""")
        val schema = OracleSchema(OracleDdl("g"))
        val spec = RangeIndexSpec("Person", listOf("name"))
        val name = schema.expectedColumns(spec).keys.first()
        val alphabet = listOf("$[*]?(@ == \"", "$?(@ == \"", "\"", "\\", "a", ")", "\")", "'", " ")
        for (path in texts(alphabet, 5_000, 2582) + listOf("$?(@ == \"A\")", "$[*]?(@ == \"A\")", "$[*]?(@ == \"A\") ", "$?(@ == \"A\\\")\")")) {
            val label = regex.matchEntire(path)?.let { SqlText.jsonString(it.groupValues[1]) }
            if (label == "Person") continue
            val column = "JSON_EXISTS2(\"LABELS\" FORMAT OSON , '${path.replace("'", "''")}' FALSE ON ERROR TYPE(LAX) )"
            val rows = listOf(mapOf("object" to name, "type" to "INDEX", "table" to "G_NODE", "uniqueness" to "NONUNIQUE", "columns" to listOf(column)))
            // The index over the key alone, of every node: a column that tests no label is its own.
            val form = if (label == null) ItemForm.Current else ItemForm.Mismatch(name, "an index over the nodes of $label", true)
            assertEquals(form, schema.formOf(spec, rows), "<$path>")
        }
    }
}
