package org.drivine.cypher

import org.drivine.cypher.api.CompileJson
import org.drivine.cypher.json.TemporalValue
import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.sql.SqliteCypherCompiler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * drivine-cypher-spec#338: a conversion of a temporal value read as the statement runs writes the converted text once. Where
 * the text's fraction goes past the microsecond, the converted text takes it back (#302), and that choice was made around the
 * converted text, so where nothing binds it, as in a walk's sort term, it was written twice, and a statement of many
 * conversions outgrew SQLite's limit of 1,000,000 characters. The choice is made in its replacement now. What the stores give is
 * the spec's, in `temporal.yaml` ("A nanosecond past the microsecond"): this runs those conversions on SQLite, and holds the
 * statement that outgrew the limit within a ceiling on each store.
 */
class TemporalConvertOnceTest {

    /** What [cypher] gives on SQLite in memory: its first row's value, or `fails: <message>`. */
    private fun sqlite(cypher: String, parameters: Map<String, Any?> = emptyMap()): String = SqliteHost.connection().use { c ->
        runCatching {
            val compiled = SqliteCypherCompiler("g").compile(cypher, parameters)
            // SQLite numbers each name it binds by where it first comes: these statements bind one at most.
            c.prepareStatement(compiled.sql).use { s ->
                compiled.parameters.values.forEachIndexed { i, v -> s.setObject(i + 1, v) }
                s.executeQuery().use { r -> r.next(); r.getString(1) ?: "null" }
            }
        }.getOrElse { e -> "fails: ${e.message}" }
    }

    /** What `toString(<to>(x))` gives on SQLite, `x` a [kind] of the text [text] given as the statement runs, of a known kind and of one read off a list. */
    private fun converted(to: String, kind: Temporal, text: String): List<String> {
        val value = mapOf("v" to TemporalValue(kind, text))
        return listOf(
            sqlite("RETURN toString($to(\$v)) AS r", value),
            sqlite("UNWIND [\$v] AS x WITH collect(x) AS l RETURN toString($to(l[0])) AS r", value),
        ).map { Json.parseToJsonElement(it).jsonPrimitive.content }
    }

    private fun assertConverted(expected: String, to: String, kind: Temporal, text: String) =
        assertEquals(listOf(expected, expected), converted(to, kind, text), "$to of $kind $text")

    @Test
    fun `a conversion of a text past the microsecond keeps its fraction, beside an offset or none`() {
        // As Neo4j 2026.10.1 gives each (temporal.yaml).
        val nine = "2024-01-01T10:30:00.123456789+10:00"
        assertConverted("2024-01-01T10:30:00.123456789+10:00", "datetime", Temporal.DATE_TIME, nine)
        assertConverted("2024-01-01T10:30:00.123456789", "localdatetime", Temporal.DATE_TIME, nine)
        assertConverted("10:30:00.123456789+10:00", "time", Temporal.DATE_TIME, nine)
        assertConverted("10:30:00.123456789", "localtime", Temporal.DATE_TIME, nine)
        assertConverted("2024-01-01", "date", Temporal.DATE_TIME, nine)
        val one = "2024-01-01T10:30:00.000000001"
        assertConverted("2024-01-01T10:30:00.000000001Z", "datetime", Temporal.LOCAL_DATE_TIME, one)
        assertConverted("10:30:00.000000001Z", "time", Temporal.LOCAL_DATE_TIME, one)
        assertConverted("10:30:00.000000001", "localtime", Temporal.LOCAL_DATE_TIME, one)
        assertConverted("10:30:00.123456789", "localtime", Temporal.TIME, "10:30:00.123456789-05:30")
        // Seven digits, the text bound as the SQL stores write one, its digits in threes, where Neo4j writes 10:30:00.1234567Z (#253).
        assertConverted("10:30:00.123456700Z", "time", Temporal.LOCAL_DATE_TIME, "2024-01-01T10:30:00.1234567")
    }

    @Test
    fun `a conversion of a text with no fraction or one to the microsecond is the converted text as it was`() {
        val none = "2024-01-01T10:30:00"
        assertConverted("2024-01-01T10:30:00Z", "datetime", Temporal.LOCAL_DATE_TIME, none)
        assertConverted("10:30:00Z", "time", Temporal.LOCAL_DATE_TIME, none)
        assertConverted("10:30:00", "localtime", Temporal.LOCAL_DATE_TIME, none)
        assertConverted("2024-01-01T10:30:00Z", "datetime", Temporal.DATE_TIME, "2024-01-01T10:30:00Z")
        assertConverted("2024-01-01T10:30:00", "localdatetime", Temporal.DATE_TIME, "2024-01-01T10:30:00Z")
        assertConverted("10:30:00Z", "time", Temporal.DATE_TIME, "2024-01-01T10:30:00Z")
        val six = "2024-01-01T10:30:00.123456-05:30"
        assertConverted("2024-01-01T10:30:00.123456-05:30", "datetime", Temporal.DATE_TIME, six)
        assertConverted("2024-01-01T10:30:00.123456", "localdatetime", Temporal.DATE_TIME, six)
        assertConverted("10:30:00.123456-05:30", "time", Temporal.DATE_TIME, six)
        assertConverted("10:30:00.123456", "localtime", Temporal.DATE_TIME, six)
        assertConverted("10:30:00.123Z", "time", Temporal.LOCAL_TIME, "10:30:00.123")
        // Digits past the sixth that are 0 are no nanosecond past the microsecond: the converted text's own fraction.
        assertConverted("10:30:00.123456", "localtime", Temporal.LOCAL_DATE_TIME, "2024-01-01T10:30:00.123456000")
    }

    /** [cypher]'s SQL on [store], as a host is given it. */
    private fun sql(store: String, cypher: String): String {
        val request = """{"store":"$store","graph":{"name":"cypher"},"cypher":${JsonPrimitive(cypher)},"parameters":{}}"""
        val response = Json.parseToJsonElement(CompileJson.compile(request)).jsonObject
        return response["sql"]!!.jsonPrimitive.content
    }

    private fun length(store: String, cypher: String): Int = sql(store, cypher).length

    /**
     * Postgres's `regexp_replace` and Oracle's `REGEXP_REPLACE` read a group in the value the replacement's SQL gives, a
     * backslash and a digit, so the `CASE`'s `'\2'` gives the converted text's own fraction, none where the group didn't take
     * part. A backslash written twice there (`'\\2'`) would give the text `\2` itself. What each gives is the spec's
     * (temporal.yaml); this holds the replacement as the stores are given it, and that no converted text is bound and chosen
     * around.
     */
    @Test
    fun `the fraction is chosen in the replacement on Postgres and Oracle`() {
        val seconds = "'^([^:]*:[0-9]{2}:[0-9]{2})([.][0-9]+)?', ('\\1' || CASE WHEN "
        listOf("postgres", "oracle").forEach { store ->
            val written = sql(store, "MATCH (n:CyTemporal) RETURN localtime(n.at)")
            val replacements = Regex(Regex.escape(seconds)).findAll(written).count()
            assertTrue(replacements > 0, "$store: no fraction chosen in a replacement")
            assertEquals(replacements, Regex(Regex.escape(" ELSE '\\2' END))")).findAll(written).count(), "$store: $written")
            assertTrue(Regex("""REGEXP_REPLACE\(sh[0-9]+\.v1, '\^\(\[\^:]""").findAll(written).none(), "$store: a converted text bound and chosen around: $written")
        }
    }

    /**
     * The statement of #338, from drivine4j's `CypherTemporalCrossEngineTest`: its sort term reads an item through a map
     * projection, so it isn't bound once per row (#340), and `datetime(b.at)` is written out 24 times, each a conversion of a
     * stored text of any kind, 192 conversions in all. At 0.48.3 it was 645,780 characters on SQLite, 283,900 on Postgres and
     * 372,339 on Oracle; with the converted text written twice, 1,068,204, 629,356 and 813,593, past SQLite's 1,000,000.
     * Written once, it is 639,756, 370,594 and 498,641. Each ceiling leaves room for a conversion to grow a little, not to
     * double: SQLite's comfortably within its limit, the others about 1.3 times their length now. Until #340 binds the term,
     * any growth in a conversion is multiplied by 192 here.
     */
    @Test
    fun `a walk's sort term of many conversions keeps within a ceiling on each store`() {
        val cypher = "MATCH (a:CyAgg)-[:NEXT*1..2]->(b:CyAgg) RETURN a.id AS a, b.id AS bb ORDER BY b{.m, a}.a * datetime(b.at).minute DESC, a, bb"
        val ceilings = mapOf("sqlite" to 800_000, "postgres" to 480_000, "oracle" to 650_000)
        ceilings.forEach { (store, ceiling) ->
            val length = length(store, cypher)
            assertTrue(length < ceiling, "$store: $length characters, the ceiling $ceiling")
        }
    }
}
