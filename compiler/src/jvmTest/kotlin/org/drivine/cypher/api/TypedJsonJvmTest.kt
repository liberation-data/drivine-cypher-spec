package org.drivine.cypher.api

import kotlin.test.Test
import kotlin.test.assertEquals

/** [TypedJson] of the JVM's own types, which reach it through its fallback to [org.drivine.cypher.json.CypherJson]. */
class TypedJsonJvmTest {

    private fun written(value: Any?) = StringBuilder().also { TypedJson.write(value, it) }.toString()

    @Test
    fun aJavaDecimalIsTaggedWithItsPlainText() {
        assertEquals("{\"\$decimal\":\"2.50\"}", written(java.math.BigDecimal("2.50")))
        assertEquals("{\"\$decimal\":\"1000\"}", written(java.math.BigDecimal("1E+3")))
        assertEquals("12345678901234567890", written(java.math.BigInteger("12345678901234567890")))
    }
}
