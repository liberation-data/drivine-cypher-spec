package org.drivine.cypher.api

import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The store layout's typed values compiled (liberation-data/drivine-cypher-spec#161), `typed-values-golden.json`
 * (`src/commonTest/resources`, which `./gradlew jvmOracle` copies beside the JVM's answers for every target): each
 * statement of the spec's cases of temporal values and of keys that begin with `$`, and each statement given a typed
 * parameter, compiled for Postgres, Oracle and SQLite, as [CompileJson.compile] answered it when the layout was made.
 * Every target gives each answer byte for byte: a change that moves how a typed value is held or compared moves the golden.
 */
class TypedValuesGoldenTest {

    private val golden: JsonNode = CypherJson.readTree(oracleFile("typed-values-golden.json"))

    @Test
    fun eachRequestIsAnsweredAsTheGoldenHoldsIt() {
        val entries = golden["entries"] as ArrayNode
        val wrong = entries.mapNotNull { entry ->
            val request = entry["request"]!!.textValue()!!
            val answer = entry["answer"]!!.textValue()!!
            val got = CompileJson.compile(request)
            if (got == answer) null else "$request:\nexpected\n${answer.take(600)}\nbut was\n${got.take(600)}"
        }
        assertTrue(entries.size() > 100, "the golden holds ${entries.size()} entries")
        assertTrue(wrong.isEmpty(), "on $target, ${wrong.size} of ${entries.size()} answers unlike the golden's, such as:\n" + wrong.take(3).joinToString("\n\n"))
    }
}
