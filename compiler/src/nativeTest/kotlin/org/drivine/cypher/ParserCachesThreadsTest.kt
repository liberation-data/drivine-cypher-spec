@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)

package org.drivine.cypher

import org.drivine.cypher.api.CompileJson
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The parse caches under several threads parsing at once from cold (#385). ANTLR adds to a prediction cache, a DFA's
 * states and its prediction contexts, as it learns the grammar, and every parse shares one set of them ([ParserCaches]):
 * on Kotlin/Native nothing kept two threads from growing one map at once, which could corrupt it, failing that compile and
 * every later one in the process ("Have object hashCodes changed?").
 */
class ParserCachesThreadsTest {

    /** Statements of many shapes, so the threads learn the grammar, adding to the caches, rather than read what one learnt. */
    private val shapes: List<(Int) -> String> = listOf(
        { i -> "MATCH (a:L$i) RETURN a.n AS n" },
        { i -> "MATCH (a:L$i)-[:R]->(b) WHERE a.n > $i RETURN a.n + b.n AS s ORDER BY s LIMIT ${i + 1}" },
        { i -> "MATCH (a:L$i) WITH a, count(*) AS c WHERE c > $i RETURN a.n, c" },
        { i -> "UNWIND range(0, $i) AS x RETURN x * 2 AS y" },
        { i -> "MATCH (a:L$i) OPTIONAL MATCH (a)<-[r:T]-(b) RETURN a.n, collect(b.n) AS bs" },
        { i -> "MATCH p = (a:L$i)-[:R*1..3]->(b) RETURN length(p) AS l" },
        { i -> "MATCH (a:L$i) RETURN CASE WHEN a.n > $i THEN 'big' ELSE 'small' END AS size" },
        { i -> "MATCH (a:L$i) WHERE a.s STARTS WITH 'x$i' OR a.s ENDS WITH 'y' RETURN a" },
        { i -> "MATCH (a:L$i) RETURN [x IN a.list WHERE x > $i | x * 2] AS xs" },
        { i -> "MATCH (a:L$i) RETURN a {.n, .s, extra: $i} AS m" },
        { i -> "CREATE (a:L$i {n: $i}) RETURN a.n" },
        { i -> "MATCH (a:L$i) SET a.n = a.n + $i RETURN a.n" },
        { i -> "MATCH (a:L$i) DETACH DELETE a" },
        { i -> "MERGE (a:L$i {k: $i}) ON CREATE SET a.c = 1 ON MATCH SET a.c = a.c + 1 RETURN a.c" },
        { i -> "MATCH (a:L$i) RETURN DISTINCT a.n ORDER BY a.n DESC SKIP 1 LIMIT 2" },
        { i -> "MATCH (a:L$i) WHERE exists { MATCH (a)-[:R]->(:M) } RETURN a.n" },
        { i -> "MATCH (a:L$i) CALL { WITH a MATCH (a)-[:R]->(b) RETURN count(b) AS c } RETURN a.n, c" },
        { i -> "MATCH (a:L$i) RETURN reduce(t = 0, x IN a.list | t + x) AS total" },
        { i -> "MATCH (a:L$i) RETURN a.n AS n UNION MATCH (b:M$i) RETURN b.n AS n" },
        { i -> "MATCH (a:L$i) WHERE a.d < date('2024-01-0${i % 9 + 1}') RETURN toString(a.d) AS d" },
    )

    private fun request(store: String, cypher: String) =
        "{\"store\": \"$store\", \"graph\": {\"name\": \"g\"}, \"cypher\": \"${cypher.replace("\"", "\\\"")}\", \"parameters\": {}}"

    @Test
    fun threadsParsingAtOnceFromColdCachesEachCompileAsAlone() {
        val requests = (0 until 8).flatMap { i ->
            shapes.mapIndexed { s, shape -> request(listOf("postgres", "oracle", "sqlite")[(i + s) % 3], shape(i)) }
        }
        val workers = (0 until 8).map { Worker.start() }
        try {
            repeat(4) { round ->
                // Cold each round: every thread learns the grammar into one new set at once.
                ParserCaches.reset()
                val futures = workers.mapIndexed { w, worker ->
                    worker.execute(TransferMode.SAFE, { requests.drop(w * 7 + round) + requests.take(w * 7 + round) }) { mine ->
                        mine.map { r -> r to CompileJson.compile(r) }
                    }
                }
                val answered = futures.flatMap { it.result }
                answered.forEach { (r, answer) -> assertEquals(CompileJson.compile(r), answer, r) }
            }
        } finally {
            workers.forEach { it.requestTermination().result }
        }
    }
}
