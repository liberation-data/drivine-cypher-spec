package org.drivine.cypher.store.ddl

import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A schema item over a property whose name begins with `$` indexes the key a statement holds it at, `$$x` (#161): on each SQL
 * store, a range index, a uniqueness constraint, a full-text and a vector index read that key, and the item's names and
 * records keep `$x`.
 */
class StoredKeysTest {

    private val items = listOf(
        RangeIndexSpec("L", listOf("\$x")),
        UniquenessConstraintSpec("L", listOf("\$x")),
        FullTextIndexSpec("L", listOf("\$x")),
        VectorIndexSpec("L", "\$x", 3),
    )

    private fun texts(statements: List<DdlStatement>) = statements.filterIsInstance<DdlStatement.Text>().joinToString("\n") { it.statement }

    private fun made(ddl: SqlStoreDdl) = items.map { item ->
        item to texts(if (item is UniquenessConstraintSpec) ddl.createConstraint(item) else ddl.createIndex(item as org.drivine.schema.IndexSpec, null))
    }

    @Test
    fun theStoredKeyOfAPropertyIsItsEscapedName() {
        assertEquals(listOf("\$\$x", "\$\$\$y", "z"), SqlStoreDdl.storedKeys(listOf("\$x", "\$\$y", "z")))
    }

    @Test
    fun postgresIndexesTheEscapedKey() {
        for ((item, sql) in made(PostgresDdl("g"))) {
            assertTrue("'\$\$x'" in sql, "$item:\n$sql")
            assertFalse(Regex("props -> '\\\$x'|->> '\\\$x'").containsMatchIn(sql), "$item:\n$sql")
            // The record keeps the property's own name.
            assertTrue("[\"\$x\"]" in sql, "$item:\n$sql")
        }
    }

    @Test
    fun sqliteIndexesTheEscapedKey() {
        for ((item, sql) in made(SqliteDdl("g")).filter { (item, _) -> item is RangeIndexSpec || item is UniquenessConstraintSpec }) {
            assertTrue("\"\$\$x\"" in sql, "$item:\n$sql")
            assertFalse("\$.\"\$x\"" in sql, "$item:\n$sql")
        }
    }

    @Test
    fun oracleIndexesTheEscapedKey() {
        for ((item, sql) in made(OracleDdl("g"))) {
            assertTrue("\$\$x" in sql, "$item:\n$sql")
            assertFalse(Regex("\\\$\\.\"?\\\$x").containsMatchIn(sql), "$item:\n$sql")
        }
    }
}
