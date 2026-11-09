package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Oracle takes no string literal past 4,000 bytes (ORA-01704), so a text the statement writes whose JSON is longer is
 * written as `TO_CLOB` pieces of at most 4,000 bytes each, joined with `||` (drivine4j#419). A shorter one is written as
 * before, in one literal.
 */
class OracleLongTextLiteralTest {

    private fun sql(cypher: String) = OracleCypherCompiler("g").compile(cypher, emptyMap()).sql

    private val piece = Regex("TO_CLOB\\('([^']*(?:''[^']*)*)'\\)")

    private fun bytes(literal: String) = literal.toByteArray(Charsets.UTF_8).size

    /** The CLOB pieces of the one long literal in [sql], each as written between its quotes. */
    private fun pieces(sql: String): List<String> {
        val joined = Regex("JSON\\((TO_CLOB\\('[^']*(?:''[^']*)*'\\)(?: \\|\\| TO_CLOB\\('[^']*(?:''[^']*)*'\\))*)\\)").find(sql)
        assertTrue(joined != null, sql)
        return piece.findAll(joined.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    /** The places in the JSON text [json] a piece may end at: between two characters, outside an escape and a surrogate pair. */
    private fun boundaries(json: String): Set<Int> {
        val at = mutableSetOf(0)
        var i = 0
        while (i < json.length) {
            i += when {
                json[i] == '\\' -> if (json[i + 1] == 'u') 6 else 2
                json[i].isHighSurrogate() -> 2
                else -> 1
            }
            at += i
        }
        return at
    }

    @Test
    fun `a text written in 4,000 bytes or fewer is one literal, as before`() {
        // The JSON text, its quotes among them, is 4,000 bytes.
        val text = "x".repeat(3998)
        assertTrue(sql("RETURN '$text'").contains("SELECT JSON('\"$text\"') AS c1"))
        // 4,000 bytes as written, a quote doubled.
        val quoted = "x".repeat(3994) + "''"
        assertTrue(sql("RETURN \"${quoted.replace("''", "'")}\"").contains("SELECT JSON('\"$quoted\"') AS c1"))
    }

    @Test
    fun `a text written in more than 4,000 bytes is CLOB pieces of at most 4,000 bytes each`() {
        val text = "x".repeat(3999)
        val sql = sql("RETURN '$text'")
        assertTrue(sql.contains("SELECT JSON(TO_CLOB('\"${"x".repeat(3999)}') || TO_CLOB('\"')) AS c1"), sql)
        val long = pieces(sql("RETURN '${"x".repeat(10000)}'"))
        assertEquals(listOf(4000, 4000, 2002), long.map(::bytes))
    }

    @Test
    fun `no piece ends within a doubled quote, a surrogate pair, a character of several bytes or a JSON escape`() {
        val unit = "😀'é\n\u0001\\x"
        for (shift in 0 until 16) {
            val text = "x".repeat(shift) + unit.repeat(800)
            val cypher = "RETURN '${text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\u0001", "\\u0001")}'"
            val written = pieces(sql(cypher))
            assertTrue(written.size > 1)
            written.forEach { assertTrue(bytes(it) <= 4000, "shift $shift: a piece of ${bytes(it)} bytes") }
            // No quote is cut from its double: each piece's quotes are in pairs.
            written.forEach { assertTrue(!it.replace("''", "").contains('\''), "shift $shift: $it") }
            val json = written.joinToString("") { it.replace("''", "'") }
            assertEquals(org.drivine.cypher.json.CypherJson.writeValueAsString(text), json)
            val allowed = boundaries(json)
            written.map { it.replace("''", "'").length }.runningReduce(Int::plus).forEach {
                assertTrue(it in allowed, "shift $shift: a piece ends at $it, within a character or an escape")
            }
        }
    }

    @Test
    fun `a long text in a list, a map or a write is written in pieces too`() {
        val text = "y".repeat(5000)
        val pieced = "JSON(TO_CLOB('\"${"y".repeat(3999)}') || TO_CLOB('${"y".repeat(1001)}\"'))"
        assertTrue(sql("RETURN ['$text', 1]").contains("JSON_ARRAY($pieced, JSON('1') NULL ON NULL RETURNING JSON)"), "list")
        assertTrue(sql("RETURN {k: '$text'}").contains("JSON_OBJECT('k' VALUE $pieced NULL ON NULL RETURNING JSON)"), "map")
        val write = sql("CREATE (n:N {t: '$text'}) RETURN size(n.t)")
        assertTrue(write.contains("'t' VALUE $pieced"), write)
    }
}
