package org.drivine.cypher.host

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class HostFunctionTest {

    @Test
    fun `a function that is both innocuous and direct-only is refused`() {
        val e = assertFailsWith<IllegalArgumentException> {
            HostFunction("drivine_both", 0, deterministic = true, innocuous = true, directOnly = true) { _, _, result -> result.nullValue() }
        }
        assertContains(e.message!!, "drivine_both can't be both innocuous and direct-only.")
    }
}
