@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)

package org.drivine.cypher.host.regex

import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JavaPatterns's cache under its spin lock, as SQLite may call a function from several of a host's threads at once. */
class JavaPatternsThreadsTest {

    @Test
    fun hostsMayMatchFromSeveralThreadsAtOnceAsTheCacheEvicts() {
        // More patterns than the cache keeps, so the threads overlap in eviction as well as in reads.
        val patterns = (0 until JavaPatterns.MAX * 2).map { "^p$it\\z" }
        val workers = (0 until 8).map { Worker.start() }
        try {
            val futures = workers.mapIndexed { w, worker ->
                worker.execute(TransferMode.SAFE, { w to patterns }) { (offset, mine) ->
                    (0 until 3).sumOf { round -> mine.indices.count { i -> ((i + offset * 31 + round) % mine.size).let { n -> JavaPatterns.of(mine[n]).find("p$n") != null } } }
                }
            }
            futures.forEach { assertEquals(3 * patterns.size, it.result) }
        } finally {
            workers.forEach { it.requestTermination().result }
        }
        assertTrue(JavaPatterns.size <= JavaPatterns.MAX)
    }
}
