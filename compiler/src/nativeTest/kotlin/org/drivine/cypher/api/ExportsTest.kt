@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.native.concurrent.ObsoleteWorkersApi::class)

package org.drivine.cypher.api

import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared library's C ABI, as a host calls it: `drivine_compile`, and `drivine_free` of what it gives. */
class ExportsTest {

    /** [request] through the C ABI: in as a C string, out as one, which is freed. */
    private fun viaC(request: String?): String = memScoped {
        val answer = drivineCompile(request?.cstr?.ptr) ?: error("drivine_compile gave no answer")
        try {
            answer.toKString()
        } finally {
            drivineFree(answer)
        }
    }

    private fun request(store: String, cypher: String, parameters: String = "{}") =
        "{\"store\": \"$store\", \"graph\": {\"name\": \"g\"}, \"cypher\": \"$cypher\", \"parameters\": $parameters}"

    @Test
    fun aRequestGoesThroughTheCAbiAsCompileJsonAnswersIt() {
        val request = request("postgres", "MATCH (p:Person {name: \$name}) RETURN p", "{\"name\": \"Ada\"}")
        assertEquals(CompileJson.compile(request), viaC(request))
    }

    @Test
    fun noRequestIsAnError() {
        assertTrue(viaC(null).startsWith("{\"error\":"), viaC(null))
        assertTrue(viaC("").startsWith("{\"error\":"), viaC(""))
        drivineFree(null)
    }

    @Test
    fun anAnswerHoldsNoNulAndKeepsALoneSurrogate() {
        // The NUL is escaped, so the C string isn't cut short; the lone surrogate is escaped, so UTF-8 can carry it.
        val answer = viaC(request("sqlite", "RETURN \$x", "{\"x\": \"a\\u0000b\\ud800c\"}"))
        assertEquals(CompileJson.compile(request("sqlite", "RETURN \$x", "{\"x\": \"a\\u0000b\\ud800c\"}")), answer)
        // The bound text is JSON, quoted again in the answer: its \u0000 doubly escaped, the surrogate once.
        assertTrue("\\\\u0000" in answer && "\\uD800" in answer && '\u0000' !in answer, answer)
    }

    @Test
    fun hostsMayCallItFromSeveralThreadsAtOnce() {
        val requests = (0 until 64).map { i ->
            val store = listOf("postgres", "oracle", "sqlite")[i % 3]
            request(store, "MATCH (a:L$i)-[:R]->(b) WHERE a.n > $i RETURN a.n + b.n AS s ORDER BY s LIMIT ${i + 1}")
        }
        val expected = requests.map(CompileJson::compile)
        val workers = (0 until 8).map { Worker.start() }
        try {
            val futures = workers.mapIndexed { w, worker ->
                worker.execute(TransferMode.SAFE, { requests.filterIndexed { i, _ -> i % 8 == w } }) { mine ->
                    // Each thread compiles its share several times over, so the threads overlap in the parser.
                    (0 until 5).flatMap { mine.map { r -> CompileJson.compile(r) } }
                }
            }
            futures.forEachIndexed { w, future ->
                val mine = expected.filterIndexed { i, _ -> i % 8 == w }
                assertEquals((0 until 5).flatMap { mine }, future.result)
            }
        } finally {
            workers.forEach { it.requestTermination().result }
        }
    }

    @Test
    fun aDeepRequestIsAnErrorNotACrashOnAnyThread() {
        // The deepest a request may nest compiles, and far deeper is refused, on the main thread and on a Worker's, whose
        // stack is smaller (512 KiB on macOS).
        val deepest = request("oracle", "RETURN \$x", "{\"x\": ${"[".repeat(62)}1${"]".repeat(62)}}")
        val deep = request("sqlite", "RETURN \$x", "{\"x\": ${"[".repeat(100_000)}${"]".repeat(100_000)}}")
        val refused = "{\"error\":\"A request nests its arrays and objects at most 64 deep.\"}"
        assertTrue(viaC(deepest).startsWith("{\"sql\":"), viaC(deepest).take(200))
        assertEquals(refused, viaC(deep))
        val worker = Worker.start()
        try {
            val answers = worker.execute(TransferMode.SAFE, { listOf(deepest, deep) }) { it.map(CompileJson::compile) }.result
            assertTrue(answers[0].startsWith("{\"sql\":"), answers[0].take(200))
            assertEquals(refused, answers[1])
        } finally {
            worker.requestTermination().result
        }
    }
}
