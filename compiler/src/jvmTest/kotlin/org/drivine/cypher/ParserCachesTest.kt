package org.drivine.cypher

import org.drivine.cypher.api.CompileJson
import org.drivine.cypher.api.CypherCompiler
import org.drivine.cypher.oracle.Oracle
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The parser's prediction caches are bounded, and making them anew changes no answer (#179). */
class ParserCachesTest {

    private val requests: List<String> by lazy {
        val spec = File(requireNotNull(javaClass.getResource("/cypher/queries.yaml")) { "No spec file cypher/queries.yaml" }.toURI()).parentFile
        Oracle.corpusRequests(spec).filter { "\"cypher\"" in it }.shuffled(kotlin.random.Random(179)).take(2_000)
    }

    @AfterTest
    fun restore() {
        ParserCaches.cap = DEFAULT_CAP
        ParserCaches.reset()
    }

    private fun answers(cap: Int): List<String> {
        ParserCaches.cap = cap
        ParserCaches.reset()
        return requests.map { CompileJson.compile(it) }
    }

    @Test
    fun theCachesStayNearTheirCapHoweverManyDistinctStatementsAreParsed() {
        ParserCaches.cap = 500
        ParserCaches.reset()
        var most = 0
        for (request in requests) {
            CompileJson.compile(request)
            most = maxOf(most, ParserCaches.states())
        }
        // Counted after each parse, so a set passes its cap by what one parse learns, and is then made anew.
        assertTrue(most < 500 * 2, "the caches held $most states at most, past a cap of 500")
        ParserCaches.cap = Int.MAX_VALUE
        ParserCaches.reset()
        requests.forEach { CompileJson.compile(it) }
        assertTrue(ParserCaches.states() > 500 * 3, "unbounded, the same statements fill more: ${ParserCaches.states()}")
    }

    @Test
    fun makingTheCachesAnewChangesNoAnswer() {
        assertEquals(answers(Int.MAX_VALUE), answers(200))
    }

    @Test
    fun threadsCompilingAtOnceAsTheCachesAreMadeAnewGiveTheAnswersOneThreadDoes() {
        val alone = answers(Int.MAX_VALUE)
        ParserCaches.cap = 100
        ParserCaches.reset()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = requests.indices.map { i -> pool.submit<String> { CompileJson.compile(requests[i]) } }
            assertEquals(alone, futures.map { it.get(60, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun theSettingsRequestSetsTheCapAndAnswersTheRenewalsAndTheStatesHeld() {
        val before = CypherCompiler.parserCacheRenewals
        assertEquals(
            "{\"settings\":{\"parserCacheStates\":300,\"parserCacheRenewals\":$before,\"parserCacheHeld\":${CypherCompiler.parserCacheHeld}}}",
            CompileJson.compile("{\"settings\": {\"parserCacheStates\": 300}}"),
        )
        assertEquals(300, CypherCompiler.parserCacheStates)
        requests.take(500).forEach { CompileJson.compile(it) }
        val asked = CompileJson.compile("{\"settings\": {}}")
        assertTrue(CypherCompiler.parserCacheRenewals > before, asked)
        assertTrue("\"parserCacheStates\":300" in asked, asked)
    }
}
