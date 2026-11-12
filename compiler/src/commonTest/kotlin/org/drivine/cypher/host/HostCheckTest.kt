package org.drivine.cypher.host

import org.drivine.cypher.store.SqliteFunctions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** [HostCheck], and `drivine_host_version()`, which gives what it checks. */
class HostCheckTest {

    @Test
    fun `drivine_host_version gives the compiler's version`() {
        assertEquals(SqliteFunctions.HOST_VERSION.toLong(), callHost(listOf(HostFunctions.hostVersion), emptyList(), "hostVersion"))
        assertEquals("SELECT drivine_host_version()", HostCheck.SQL)
    }

    @Test
    fun `the compiler's version passes`() {
        HostCheck.verify(SqliteFunctions.HOST_VERSION.toLong())
        assertNull(HostCheck.problem(SqliteFunctions.HOST_VERSION.toLong()))
    }

    @Test
    fun `another version fails naming both`() {
        val other = SqliteFunctions.HOST_VERSION + 1L
        assertEquals(
            "SQLite's drivine_ functions are version $other, the compiler needs ${SqliteFunctions.HOST_VERSION}: register the module's host functions",
            assertFailsWith<IllegalStateException> { HostCheck.verify(other) }.message,
        )
        assertFailsWith<IllegalStateException> { HostCheck.verify(0) }
    }

    @Test
    fun `no function fails as older functions`() {
        assertEquals(
            "SQLite has no drivine_host_version(): its drivine_ functions are older than version ${SqliteFunctions.HOST_VERSION}, the compiler's: " +
                "register the module's host functions",
            assertFailsWith<IllegalStateException> { HostCheck.verify(null) }.message,
        )
    }
}
