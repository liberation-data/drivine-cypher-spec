package org.drivine.cypher.spec

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.drivine.cypher.CompilerException
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqlCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The spec's `vector.yaml`, read with no store (liberation-data/drivine-cypher-spec#160): its graph, its indexes and the
 * shape of each step, and what each SQL store's compiler gives each step today, as the step's `today` says. A key the
 * file has and this does not read fails, so a misspelt one is caught.
 */
class VectorSpecTest {

    data class VectorSpec(val graph: String, val indexes: List<Index>, val cases: List<Case>)

    data class Index(val kind: String, val label: String, val properties: List<String>, val name: String, val options: Options) {
        data class Options(val dimensions: Int, val similarity: String)

        fun spec() = VectorIndexSpec(label, properties.single(), options.dimensions, SimilarityFunction.valueOf(options.similarity), name)
    }

    data class Case(val name: String, val description: String, val steps: List<Step>)

    data class Step(
        val cypher: String,
        val note: String? = null,
        val parameters: Map<String, Any?> = emptyMap(),
        val rows: List<Any?>? = null,
        val error: Any? = null,
        val today: List<Today> = emptyList(),
    )

    /** What a store gives today where it differs from the step, and the issue that makes it pass: not read by a runner. */
    data class Today(val stores: List<String>, val error: String? = null, val rows: List<Any?>? = null, val issue: String? = null, val unfiled: Boolean = false)

    private val spec: VectorSpec = ObjectMapper(YAMLFactory()).registerKotlinModule()
        .enable(DeserializationFeature.USE_LONG_FOR_INTS)
        .readValue(requireNotNull(VectorSpecTest::class.java.getResource("/cypher/vector.yaml")) { "No spec file cypher/vector.yaml" })

    private val compilers: Map<String, SqlCypherCompiler> = mapOf(
        "postgres" to PostgresCypherCompiler("g"),
        "oracle" to OracleCypherCompiler("g"),
        "sqlite" to SqliteCypherCompiler("g"),
    )

    private val stores = setOf("neo4j", "sql") + compilers.keys

    private fun steps() = spec.cases.flatMap { case -> case.steps.mapIndexed { i, step -> "${case.name}, step ${i + 1}" to step } }

    @Test
    fun `each case is named once and described, and each step has a statement and either rows or a failure`() {
        assertEquals(spec.cases.map { it.name }.distinct(), spec.cases.map { it.name })
        spec.cases.forEach { assertTrue(it.description.isNotBlank() && it.steps.isNotEmpty(), it.name) }
        steps().forEach { (where, step) ->
            assertTrue(step.cypher.isNotBlank(), where)
            assertTrue((step.rows == null) != (step.error == null), "$where: rows or an error, not both or neither")
            if (step.error != null) assertEquals(true, step.error, "$where: a failure is Neo4j's, error: true")
            step.today.forEach { today ->
                assertTrue(today.stores.isNotEmpty() && stores.containsAll(today.stores), "$where: today's stores ${today.stores}")
                assertTrue(today.unfiled || today.issue?.matches(Regex("liberation-data/[\\w-]+#\\d+")) == true, "$where: today's issue ${today.issue}")
            }
        }
    }

    @Test
    fun `the indexes are vector index specs, and each search names one`() {
        val names = spec.indexes.map { index ->
            assertEquals("VECTOR_INDEX", index.kind)
            index.spec().name!!
        }
        assertEquals(names.distinct(), names)
        val searched = Regex("""queryNodes\('(\w+)'|VECTOR INDEX (\w+)""")
        steps().filter { (_, step) -> step.rows != null }.forEach { (where, step) ->
            searched.findAll(step.cypher).map { it.groupValues.drop(1).first { name -> name.isNotEmpty() } }.forEach { name ->
                assertTrue(name in names, "$where searches $name, which is none of $names")
            }
        }
    }

    @Test
    fun `the graph compiles on each SQL store`() {
        compilers.values.forEach { it.compile(spec.graph) }
    }

    @Test
    fun `each step gives on each SQL store what its today says, or the step`() {
        compilers.forEach { (store, compiler) ->
            steps().forEach { (where, step) ->
                val today = step.today.firstOrNull { "sql" in it.stores || store in it.stores }
                val refused = try {
                    compiler.compile(step.cypher, step.parameters)
                    null
                } catch (e: CompilerException) {
                    e.message
                }
                when {
                    today?.error != null -> assertEquals(today.error, refused, "$where, on $store")
                    today != null && today.rows == null -> if (refused == null) fail("$where, on $store: held, and compiled")
                    step.error != null -> if (refused == null) fail("$where, on $store: expected a failure, and compiled")
                    else -> assertEquals(null, refused, "$where, on $store")
                }
            }
        }
    }

    @Test
    fun `a call is refused by its procedure's name as written, and a k not whole when negated too`() {
        val notCompiled = "A procedure call (CALL) is Cypher, and is not compiled to SQL yet."
        mapOf(
            // Not in the spec: Neo4j's answer for these was not captured.
            "CALL db.index.vector.queryNodes('chunk_cos', -1.5, [1.0, 0.0, 0.0])" to
                "db.index.vector.queryNodes() takes a whole number of neighbours, not -1.5, as in Neo4j.",
            "CALL db.create.setNodeVectorProperty" to
                "db.create.setNodeVectorProperty() is not compiled to SQL: set the embedding as a list with SET, as in SET n.embedding = [0.1, 0.2, 0.3].",
            // A word Cypher keeps names a part of any procedure's namespace, not only the refused one's.
            "CALL db.create.somethingElse()" to notCompiled,
            "CALL db.CREATE.setNodeVectorProperty()" to notCompiled,
            "CALL db.index.vector.queryNodes('chunk_cos', 'k', [1.0, 0.0, 0.0])" to notCompiled,
            "CALL db.index.vector.querynodes('chunk_cos', 1.5, [1.0, 0.0, 0.0])" to notCompiled,
        ).forEach { (cypher, message) ->
            compilers.forEach { (store, compiler) ->
                val refused = try {
                    compiler.compile(cypher)
                    null
                } catch (e: CompilerException) {
                    e.message
                }
                assertEquals(message, refused, "$cypher, on $store")
            }
        }
    }
}
