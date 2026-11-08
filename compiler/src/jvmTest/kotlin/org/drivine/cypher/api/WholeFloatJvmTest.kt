package org.drivine.cypher.api

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A whole `Float` a JVM host binds is written on Postgres as drivine4j writes it: Jackson's float node read as the double it
 * widens to, `BigDecimal.valueOf(that).setScale(1)`, so 1.0E20f is 100000002004087730000.0, not the float's own text.
 */
class WholeFloatJvmTest {
    @Test
    fun aWholeFloatIsWrittenAsTheDoubleItWidensTo() {
        val random = Random(646)
        val floats = listOf(0f, -0f, 3f, 16777217f, 1.0E20f, -1.0E20f, 3.4028235E38f, 1.0E10f) +
            List(500) { random.nextLong(-(1L shl 40), 1L shl 40).toFloat() } + List(500) { Float.fromBits(random.nextInt()) }.filter { it.isFinite() && it == kotlin.math.round(it) }
        for (f in floats) {
            val java = java.math.BigDecimal.valueOf(f.toDouble()).setScale(1).toPlainString()
            val result = CypherCompiler.compile(Store.POSTGRES, GraphOptions("g"), "CREATE (n:N {x: \$x}) RETURN n.x", mapOf("x" to f)) as Compiled
            assertEquals("{\"x\":$java}", result.parameters.values.single().toString(), "the float $f")
        }
    }
}
