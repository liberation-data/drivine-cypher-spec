package org.drivine.cypher.store.ddl

import org.drivine.cypher.api.FullTextSearch
import org.drivine.cypher.api.GraphOptions
import org.drivine.cypher.api.SearchIndex
import org.drivine.cypher.api.SearchPlan
import org.drivine.cypher.api.SearchRequest
import org.drivine.cypher.api.SearchStore
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.VectorIndexSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How a name is written into a graph engine's Cypher as a string literal (liberation-data/drivine4j#767). */
class CypherNamesTest {

    @Test
    fun aStringIsOneLiteralWhateverItHolds() {
        assertEquals("'plain'", CypherNames.string("plain"))
        assertEquals("'it\\'s'", CypherNames.string("it's"))
        assertEquals("'a\\\\b'", CypherNames.string("a\\b"))
        // A backslash before a quote is escaped on its own, so it cannot escape the quote that follows.
        assertEquals("'a\\\\\\'b'", CypherNames.string("a\\'b"))
        assertEquals("'ends\\\\'", CypherNames.string("ends\\"))
        // A backtick and a double quote are text inside single quotes.
        assertEquals("'b`a\"d'", CypherNames.string("b`a\"d"))
        // The Unicode escape of a quote, which Neo4j decodes before it reads the statement, is the quote it is.
        assertEquals("'x\\'y'", CypherNames.string("x\\u0027y"))
        assertEquals("'x\\'y'", CypherNames.string("x\\uuu0027y"))
    }

    @Test
    fun aGraphEnginesSearchWritesItsIndexNameOrLabelAsAString() {
        fun cypher(store: SearchStore, request: SearchRequest) =
            requireNotNull((FullTextSearch.plan(store, GraphOptions("g"), request) as SearchPlan).cypher).lineSequence().first()
        val odd = "it's\\"
        for (store in listOf(SearchStore.NEO4J, SearchStore.MEMGRAPH)) {
            val named = cypher(store, SearchRequest(listOf("L"), listOf("p"), "q", SearchIndex(name = odd), topK = 5))
            assertTrue("('it\\'s\\\\', \$" in named, "$store: $named")
            val derived = cypher(store, SearchRequest(listOf(odd), listOf("p"), "q", topK = 5))
            assertTrue("('it\\'s\\\\_p_fulltext', \$" in derived, "$store: $derived")
        }
        val falkor = cypher(SearchStore.FALKORDB, SearchRequest(listOf(odd), listOf("p"), "q", topK = 5))
        assertTrue("queryNodes('it\\'s\\\\', \$" in falkor, falkor)
    }

    @Test
    fun theGraphEnginesDdlQuotesAnIndexNameThatIsNoPlainIdentifier() {
        fun text(statements: List<DdlStatement>) = statements.joinToString("\n") { (it as DdlStatement.Text).statement }
        val memgraph = MemgraphDdl()
        assertEquals("CREATE TEXT INDEX `it's \\x_body_fulltext` ON :`it's \\x`(body)", text(memgraph.createIndex(FullTextIndexSpec("it's \\x", listOf("body")), null)))
        assertEquals("DROP TEXT INDEX `it's \\x_body_fulltext`", text(memgraph.dropIndex(CatalogItem(SchemaItemKind.FULLTEXT_INDEX, "it's \\x", listOf("body")))))
        assertEquals("DROP TEXT INDEX `a``b`", text(memgraph.dropIndex(CatalogItem(SchemaItemKind.FULLTEXT_INDEX, "L", listOf("p"), "a`b"))))
        assertTrue(text(memgraph.createIndex(VectorIndexSpec("L", "p", 3, name = "a b"), null)).startsWith("CREATE VECTOR INDEX `a b` ON :L(p)"))
        assertEquals("DROP VECTOR INDEX `it's_p_vector`", text(memgraph.dropIndex(CatalogItem(SchemaItemKind.VECTOR_INDEX, "it's", listOf("p")))))
        // A plain name is written bare, as it always was.
        assertEquals("CREATE TEXT INDEX Doc_body_fulltext ON :Doc(body)", text(memgraph.createIndex(FullTextIndexSpec("Doc", listOf("body")), null)))
        assertEquals(
            "CREATE TEXT INDEX `a``b` IF NOT EXISTS FOR (n:`L``x`) ON (n.`p``y`)",
            text(Neo4jDdl.createIndex(TextIndexSpec("L`x", "p`y", name = "a`b"), null)),
        )
        // A full-text index's analyzer is a string, escaped as the search's index name is.
        val analyzed = text(Neo4jDdl.createIndex(FullTextIndexSpec("Doc", listOf("body"), analyzer = "it's"), null))
        assertTrue("{`fulltext.analyzer`: 'it\\'s'}" in analyzed, analyzed)
    }
}
