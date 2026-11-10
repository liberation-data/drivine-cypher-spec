package org.drivine.cypher

import org.drivine.cypher.api.CompileJson
import org.drivine.cypher.sql.ColumnKind
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.ResultColumn
import org.drivine.cypher.sql.SqlCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A compiled statement's columns, named as a graph engine names them and of the kind the compiler knows, and its nodes and
 * relationships tagged where it is compiled typed (liberation-data/drivine-cypher-spec#162). What the stores give for it is
 * the spec's, in `results.yaml`; that compiling untyped is unchanged is the corpus's.
 */
class TypedColumnsTest {

    private fun compilers(): List<SqlCypherCompiler> = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    private fun columns(cypher: String, typed: Boolean = false): List<List<ResultColumn>> =
        compilers().map { it.compile(cypher, emptyMap(), typed).columns }

    private fun assertColumns(cypher: String, vararg expected: Pair<String, ColumnKind>) {
        val want = expected.map { (name, kind) -> ResultColumn(name, kind) }
        listOf(false, true).forEach { typed -> columns(cypher, typed).forEach { assertEquals(want, it, "$cypher (typed: $typed)") } }
    }

    @Test
    fun `a RETURN's columns are its items' names, of the kind the compiler knows`() {
        assertColumns(
            "MATCH (p:Person)-[r:KNOWS]->(f) RETURN p, r, f.name AS name, count(*) AS n",
            "p" to ColumnKind.NODE, "r" to ColumnKind.RELATIONSHIP, "name" to ColumnKind.VALUE, "n" to ColumnKind.VALUE,
        )
        assertColumns("MATCH (p:Person) RETURN p.name", "p.name" to ColumnKind.VALUE)
        assertColumns("RETURN date('2024-01-02') AS d, duration('P1D') AS t", "d" to ColumnKind.DATE, "t" to ColumnKind.DURATION)
        assertColumns("MATCH (p:Person)-[r]->() RETURN collect(p) AS ps, collect(r) AS rs", "ps" to ColumnKind.NODES, "rs" to ColumnKind.RELATIONSHIPS)
    }

    @Test
    fun `RETURN star gives the variables in the order of their names`() {
        assertColumns("MATCH (b:Person)-[a:KNOWS]->(c) RETURN *", "a" to ColumnKind.RELATIONSHIP, "b" to ColumnKind.NODE, "c" to ColumnKind.NODE)
    }

    @Test
    fun `a UNION's columns are its first query's, a value where the queries' kinds differ`() {
        assertColumns("MATCH (p:Person) RETURN p AS x, 1 AS y UNION MATCH (m:Movie) RETURN m AS x, 2 AS y", "x" to ColumnKind.NODE, "y" to ColumnKind.VALUE)
        assertColumns("MATCH (p:Person) RETURN p AS x UNION ALL RETURN 1 AS x", "x" to ColumnKind.VALUE)
    }

    @Test
    fun `a CALL subquery's RETURN names no column of the statement, and what it gives keeps its kind`() {
        assertColumns("CALL { MATCH (p:Person) RETURN p } RETURN p.name AS name", "name" to ColumnKind.VALUE)
        assertColumns("MATCH (p:Person) CALL (p) { MATCH (p)-[:KNOWS]->(f) RETURN f UNION MATCH (f:Movie) RETURN f } RETURN p, f", "p" to ColumnKind.NODE, "f" to ColumnKind.NODE)
    }

    @Test
    fun `a write's RETURN names its columns, and one that returns nothing has none`() {
        assertColumns("CREATE (p:Person {name: 'Ada'}) RETURN p, p.name AS name", "p" to ColumnKind.NODE, "name" to ColumnKind.VALUE)
        assertColumns("CREATE (p:Person {name: 'Ada'})")
        assertColumns("MATCH (p:Person) SET p.seen = true")
    }

    @Test
    fun `compiled typed, a node and a relationship are tagged, and anything else is as untyped`() {
        compilers().zip(compilers()).forEach { (untyped, typed) ->
            val cypher = "MATCH (p:Person)-[r:KNOWS]->(f) RETURN p, r, collect(f) AS fs, f.name AS name"
            val plain = untyped.compile(cypher).sql
            val tagged = typed.compile(cypher, emptyMap(), typed = true).sql
            assertFalse("\$t" in plain, plain)
            assertTrue(tagged.contains("'\$t'"), tagged)
            listOf("'node'", "'relationship'", "'labels'", "'type'", "'properties'").forEach { assertTrue(it in tagged || it.trim('\'') in tagged, "$it in $tagged") }
        }
    }

    @Test
    fun `compiled typed or not, a statement that holds no node or relationship is the same SQL`() {
        listOf("RETURN 1 AS n", "MATCH (p:Person) RETURN p.name, count(*) ORDER BY p.name", "UNWIND [1, 2] AS x RETURN x").forEach { cypher ->
            compilers().zip(compilers()).forEach { (a, b) -> assertEquals(a.compile(cypher).sql, b.compile(cypher, emptyMap(), typed = true).sql, cypher) }
        }
    }

    @Test
    fun `typed is reset after each statement`() {
        compilers().forEach { compiler ->
            val typed = compiler.compile("MATCH (p:Person) RETURN p", emptyMap(), typed = true).sql
            val after = compiler.compile("MATCH (p:Person) RETURN p").sql
            assertTrue("\$t" in typed)
            assertFalse("\$t" in after, after)
        }
    }

    @Test
    fun `the JSON entry gives columns only where typed is asked`() {
        val request = """{"store": "sqlite", "graph": {"name": "g"}, "cypher": "MATCH (p:Person) RETURN p, p.name AS name"}"""
        assertFalse("\"columns\"" in CompileJson.compile(request))
        val typed = CompileJson.compile(request.replace("\"cypher\"", "\"typed\": true, \"cypher\""))
        assertTrue(typed.endsWith(""","columns":[{"name":"p","kind":"node"},{"name":"name","kind":"value"}]}"""), typed)
        assertTrue(CompileJson.compile(request.replace("\"cypher\"", "\"typed\": \"yes\", \"cypher\"")).startsWith("{\"error\":"))
    }
}
