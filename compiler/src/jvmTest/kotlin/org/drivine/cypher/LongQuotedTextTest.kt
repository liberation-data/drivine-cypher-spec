package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A long quoted key, literal or label is compiled on each store (#258). The compiler reads a quoted text in its SQL by
 * regex. Read as an alternation repeated once a character, `(?:[^']|'')*`, it recursed once a character in Java's regex
 * engine, and a key of 400,000 characters overflowed the stack: in a WHERE on SQLite and Oracle, in an ORDER BY on Oracle.
 */
class LongQuotedTextTest {

    private val long = "k".repeat(400_000)

    private val statements = listOf(
        "MATCH (n:L) WHERE n.`$long` = 1 RETURN n",
        "MATCH (n:L) RETURN n ORDER BY n.`$long`",
        "MATCH (n:L) RETURN n.`$long` AS v",
        "MATCH (n:L) SET n.`$long` = 1",
        "MATCH (n:L) WHERE n.a = '$long' RETURN n",
        "RETURN '$long' AS v",
        "MATCH (n:`$long`) RETURN n",
    )

    @Test
    fun `a statement with a long quoted key, literal or label is compiled on each store, not overflowing the stack`() {
        val stores = mapOf<String, (String) -> String>(
            "Postgres" to { PostgresCypherCompiler("g").compile(it).sql },
            "SQLite" to { SqliteCypherCompiler("g").compile(it).sql },
            "Oracle" to { OracleCypherCompiler("g").compile(it).sql },
        )
        for ((store, compile) in stores) for (cypher in statements) {
            val sql = compile(cypher)
            assertTrue(sql.isNotEmpty(), "$store: ${cypher.replace(long, "<400,000 characters>")}")
        }
    }
}
