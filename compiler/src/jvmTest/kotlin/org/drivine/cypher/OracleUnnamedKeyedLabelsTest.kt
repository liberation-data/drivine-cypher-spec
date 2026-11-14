package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Oracle, a node a pattern finds with no variable, as `(:Person)`, is tested for its labels as the same node named
 * would be: by their text ([OracleCypherCompiler.keyedLabels]) where it is read by its key, beside a node found by a
 * unique property or as the later end of a relationship, and as the index over labels answers them elsewhere. Tested as
 * the index answers them, `MATCH (:Person)-[:FOLLOWS]->(p:Person {id: $id})` drove from every `Person` and read the key's
 * index once for each edge: 2.1 s over 1.5M edges, where `(a:Person)` took 0.5 ms (#269).
 */
class OracleUnnamedKeyedLabelsTest {

    private fun oracle(cypher: String, indexed: Set<Pair<String, String>> = setOf("Person" to "id")) =
        OracleCypherCompiler("g", indexedProperties = indexed).compile(cypher, mapOf("id" to 1, "n" to "x")).sql

    /** The test of [label] among [alias]'s labels by their text, the form the index over labels doesn't answer. */
    private fun byText(alias: String, label: String = "Person") =
        "INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE($alias.labels), ']'), '[') || ',\"', ',\"$label\",\"') > 0"

    /** The test of [label] among [alias]'s labels that the index over labels answers. */
    private fun byIndex(alias: String, label: String = "Person") = "JSON_EXISTS($alias.labels, '\$[*]?(@ == \"$label\")')"

    /**
     * Whether [sql] tests [alias]'s labels as the index over them answers, as a condition of its own: a keyed node's index
     * expression holds that test too, inside it.
     */
    private fun testedByIndex(sql: String, alias: String, label: String = "Person") =
        Regex("""(WHERE|\n\s+AND) ${Regex.escape(byIndex(alias, label))}""").containsMatchIn(sql)

    /** [unnamed] compiles as [named] does, and its unnamed node, [alias], is tested for its labels by their text. */
    private fun keyedAsNamed(unnamed: String, named: String, alias: String) {
        val sql = oracle(unnamed)
        assertEquals(oracle(named), sql, unnamed)
        assertTrue(byText(alias) in sql && !testedByIndex(sql, alias), sql)
    }

    @Test
    fun `the other end of one relationship to a keyed node, either way round and either direction`() {
        keyedAsNamed(
            "MATCH (:Person)-[:FOLLOWS]->(p:Person {id: \$id}) RETURN count(*)",
            "MATCH (a:Person)-[:FOLLOWS]->(p:Person {id: \$id}) RETURN count(*)", "n1",
        )
        keyedAsNamed(
            "MATCH (:Person)<-[:FOLLOWS]-(p:Person {id: \$id}) RETURN count(*)",
            "MATCH (a:Person)<-[:FOLLOWS]-(p:Person {id: \$id}) RETURN count(*)", "n1",
        )
        keyedAsNamed(
            "MATCH (:Person)-[:FOLLOWS]-(p:Person {id: \$id}) RETURN count(*)",
            "MATCH (a:Person)-[:FOLLOWS]-(p:Person {id: \$id}) RETURN count(*)", "n1",
        )
        keyedAsNamed(
            "MATCH (p:Person {id: \$id})<-[:FOLLOWS]-(:Person) RETURN count(*)",
            "MATCH (p:Person {id: \$id})<-[:FOLLOWS]-(a:Person) RETURN count(*)", "n2",
        )
        keyedAsNamed(
            "MATCH (p:Person {id: \$id})-[:FOLLOWS]->(:Person) RETURN count(*)",
            "MATCH (p:Person {id: \$id})-[:FOLLOWS]->(a:Person) RETURN count(*)", "n2",
        )
    }

    @Test
    fun `the middle of a chain`() {
        keyedAsNamed(
            "MATCH (x:Person)-[:FOLLOWS]->(:Person)-[:FOLLOWS]->(p:Person {id: \$id}) RETURN count(*)",
            "MATCH (x:Person)-[:FOLLOWS]->(m:Person)-[:FOLLOWS]->(p:Person {id: \$id}) RETURN count(*)", "n2",
        )
        keyedAsNamed(
            "MATCH (p:Person {id: \$id})-[:FOLLOWS]->(:Person)-[:FOLLOWS]->(x:Person) RETURN count(*)",
            "MATCH (p:Person {id: \$id})-[:FOLLOWS]->(m:Person)-[:FOLLOWS]->(x:Person) RETURN count(*)", "n2",
        )
        // An unnamed node beside the keyed one left every node past it found through the index over labels too, named or not:
        // an org's top topics took 391 s on Oracle. Each of the bench's walks from a keyed node, as it runs them, compiles as it
        // would with every node named, and tests every node past the keyed one by its text.
        val indexed = setOf("Org" to "id", "Repo" to "id", "Issue" to "id", "Topic" to "id", "Person" to "id")
        fun bench(cypher: String) = OracleCypherCompiler("g", indexedProperties = indexed).compile(cypher, mapOf("id" to "k1")).sql
        for ((walk, named, past) in listOf(
            Triple(
                "MATCH (o:Org {id: \$id})<-[:OWNED_BY]-(:Repo)<-[:IN_REPO]-(i:Issue)-[:TAGGED]->(t:Topic) WHERE i.state = 'open' " +
                    "WITH t.id AS topic, count(*) AS n ORDER BY n DESC, topic LIMIT 10 RETURN {topic: topic, n: n} AS r",
                listOf("(:Repo)" to "(x:Repo)"), listOf("n2" to "Repo", "n3" to "Issue", "n4" to "Topic"),
            ),
            Triple(
                "MATCH (r:Repo {id: \$id})<-[:IN_REPO]-(:Issue)<-[:ON]-(:Comment)-[:WRITTEN_BY]->(p:Person) " +
                    "WITH p.id AS person, count(*) AS n ORDER BY n DESC, person LIMIT 10 RETURN {person: person, n: n} AS r",
                listOf("(:Issue)" to "(x:Issue)", "(:Comment)" to "(y:Comment)"), listOf("n2" to "Issue", "n3" to "Comment", "n4" to "Person"),
            ),
            Triple(
                "MATCH (p:Person {id: \$id})-[:FOLLOWS]->(:Person)<-[:OPENED_BY]-(:Issue)-[:IN_REPO]->(r:Repo) " +
                    "WITH r.id AS repo, count(*) AS n ORDER BY n DESC, repo LIMIT 10 RETURN {repo: repo, n: n} AS r",
                listOf("(:Person)" to "(x:Person)", "(:Issue)" to "(y:Issue)"), listOf("n2" to "Person", "n3" to "Issue", "n4" to "Repo"),
            ),
        )) {
            val sql = bench(walk)
            assertEquals(bench(named.fold(walk) { cypher, (from, to) -> cypher.replace(from, to) }), sql, walk)
            for ((alias, label) in past) assertTrue(byText(alias, label) in sql && !testedByIndex(sql, alias, label), sql)
        }
    }

    @Test
    fun `an unnamed node with properties is read by its key as a named one is`() {
        keyedAsNamed(
            "MATCH (:Person)-[:FOLLOWS]->(:Person {id: \$id}) RETURN count(*)",
            "MATCH (a:Person)-[:FOLLOWS]->(b:Person {id: \$id}) RETURN count(*)", "n2",
        )
        assertTrue(byText("n1") in oracle("MATCH (:Person)-[:FOLLOWS]->(:Person {id: \$id}) RETURN count(*)"))
    }

    @Test
    fun `every label of an unnamed node with more than one`() {
        val unnamed = "MATCH (:Person:Admin)-[:FOLLOWS]->(p:Person {id: \$id}) RETURN count(*)"
        keyedAsNamed(unnamed, "MATCH (a:Person:Admin)-[:FOLLOWS]->(p:Person {id: \$id}) RETURN count(*)", "n1")
        assertTrue(byText("n1", "Admin") in oracle(unnamed))
    }

    @Test
    fun `a write's read of unnamed nodes is planned as a named one's is`() {
        // A delete's read tests every label by its text already (#597); its later end is now read by its key, so the
        // statement is planned without statistics feedback, as it is with the ends named.
        val unnamed = oracle("MATCH (:Person)-[r:FOLLOWS]->(:Person) DELETE r")
        assertEquals(oracle("MATCH (a:Person)-[r:FOLLOWS]->(b:Person) DELETE r"), unnamed)
        assertTrue(OracleCypherCompiler.NO_FEEDBACK in unnamed, unnamed)
    }

    @Test
    fun `a second MATCH from a node the first found by its key`() {
        keyedAsNamed(
            "MATCH (p:Person {id: \$id}) MATCH (:Person)-[:FOLLOWS]->(p) RETURN count(*)",
            "MATCH (p:Person {id: \$id}) MATCH (a:Person)-[:FOLLOWS]->(p) RETURN count(*)", "n2",
        )
    }

    @Test
    fun `an OPTIONAL MATCH, an EXISTS subquery and a pattern comprehension that find the keyed node themselves`() {
        // The named form returns its node from the OPTIONAL MATCH's lateral query, so only the label tests are the same.
        val optional = oracle("OPTIONAL MATCH (:Person)-[:FOLLOWS]->(p:Person {id: \$id}) RETURN count(*)")
        assertTrue(byText("n1") in optional && !testedByIndex(optional, "n1"), optional)
        keyedAsNamed(
            "MATCH (p:Person {id: \$id}) WHERE EXISTS { MATCH (:Person)-[:FOLLOWS]->(q:Person {id: \$id}) } RETURN count(*)",
            "MATCH (p:Person {id: \$id}) WHERE EXISTS { MATCH (a:Person)-[:FOLLOWS]->(q:Person {id: \$id}) } RETURN count(*)", "n2",
        )
        keyedAsNamed(
            "RETURN [(:Person)-[:FOLLOWS]->(p:Person {id: \$id}) | p.id]",
            "RETURN [(a:Person)-[:FOLLOWS]->(p:Person {id: \$id}) | p.id]", "n1",
        )
    }

    @Test
    fun `where the keyed node is bound outside the pattern, or the walk is of many relationships, the unnamed node is tested as a named one is`() {
        // Neither form is read by its key here: the keyed node is bound in the query around, or a walk leaves the tests as
        // they are (see keyedEnd). The unnamed node keeps the test the index over labels answers, as the named one does.
        for ((unnamed, named) in listOf(
            "MATCH (:Person)-[:FOLLOWS*1..2]->(p:Person {id: \$id}) RETURN count(*)" to "MATCH (a:Person)-[:FOLLOWS*1..2]->(p:Person {id: \$id}) RETURN count(*)",
            "MATCH (p:Person {id: \$id}) WHERE EXISTS { (p)<-[:FOLLOWS]-(:Person) } RETURN count(*)" to "MATCH (p:Person {id: \$id}) WHERE EXISTS { (p)<-[:FOLLOWS]-(a:Person) } RETURN count(*)",
            "MATCH (p:Person {id: \$id}) RETURN COUNT { (p)<-[:FOLLOWS]-(:Person) }" to "MATCH (p:Person {id: \$id}) RETURN COUNT { (p)<-[:FOLLOWS]-(a:Person) }",
        )) {
            val sql = oracle(unnamed)
            assertEquals(oracle(named), sql, unnamed)
            val alias = if (unnamed.startsWith("MATCH (:")) "n1" else "n2"
            assertTrue(testedByIndex(sql, alias) && byText(alias) !in sql, sql)
        }
        for (cypher in listOf(
            "MATCH (p:Person {id: \$id}) WHERE (p)<-[:FOLLOWS]-(:Person) RETURN count(*)",
            "MATCH (p:Person {id: \$id}) OPTIONAL MATCH (:Person)-[:FOLLOWS]->(p) RETURN count(*)",
            "MATCH (p:Person {id: \$id}) RETURN [(p)<-[:FOLLOWS]-(:Person) | 1]",
        )) {
            val sql = oracle(cypher)
            assertTrue(testedByIndex(sql, "n2") && byText("n2") !in sql, sql)
        }
    }

    @Test
    fun `where nothing is keyed, an unnamed node keeps the test the index over labels answers`() {
        // The earlier end of a relationship keeps its test, so the index still finds it; the later is read by the edge's key,
        // named or not (#581).
        val walked = oracle("MATCH (:Person)-[:FOLLOWS]->(:Person) RETURN count(*)")
        assertEquals(oracle("MATCH (a:Person)-[:FOLLOWS]->(b:Person) RETURN count(*)"), walked)
        assertTrue(testedByIndex(walked, "n1") && byText("n1") !in walked && byText("n2") in walked, walked)
        // A node alone, or given a property no index is over, is found through the index over labels.
        for (cypher in listOf("MATCH (:Person) RETURN count(*)", "MATCH (:Person {name: \$n}) RETURN count(*)")) {
            val sql = oracle(cypher)
            assertTrue(testedByIndex(sql, "n1") && "INSTR(" !in sql && OracleCypherCompiler.NO_FEEDBACK !in sql, sql)
        }
        val unindexed = oracle("MATCH (:Person {id: \$id}) RETURN count(*)", emptySet())
        assertTrue(testedByIndex(unindexed, "n1") && "INSTR(" !in unindexed, unindexed)
    }

    @Test
    fun `on Postgres an unnamed node is tested as it was`() {
        val cypher = "MATCH (:Person)-[:FOLLOWS]->(p:Person {id: \$id}) RETURN count(*)"
        val sql = PostgresCypherCompiler("g").compile(cypher, mapOf("id" to 1)).sql
        assertEquals(PostgresCypherCompiler("g").compile(cypher.replace("(:Person)-", "(a:Person)-"), mapOf("id" to 1)).sql, sql)
    }
}
