package org.drivine.cypher.host

import org.drivine.DrivineInternalApi
import org.drivine.cypher.store.SqliteFunctions

/**
 * The check a host runs once on each SQLite connection it registers the host functions on, right after registering
 * them: [SQL] gives the version of the functions it registered (null where it registered none of the module's, or
 * drivine4j's older ones, which have no `drivine_host_version`), and [verify] fails where it is not the compiler's,
 * [SqliteFunctions.HOST_VERSION]. Then the compiled SQL would call the functions in forms they don't take, and fail, or
 * worse give another answer, mid-statement. A host with no Kotlin types reads the compiler's version from the native
 * library's C export `drivine_host_version()` or the wasm build's `hostVersion()`, and words the failure alike.
 */
@DrivineInternalApi
object HostCheck {

    /** The statement that reads the registered functions' version: one row, one whole number. */
    const val SQL = "SELECT ${SqliteFunctions.HOST_VERSION_FUNCTION}()"

    /** Throws where [reported], what [SQL] gave (null where SQLite has no such function), is not [SqliteFunctions.HOST_VERSION]. */
    fun verify(reported: Long?) {
        problem(reported)?.let { throw IllegalStateException(it) }
    }

    /** What [verify] would throw with, or null where [reported] is the compiler's version. */
    fun problem(reported: Long?): String? {
        val needed = SqliteFunctions.HOST_VERSION
        return when (reported) {
            needed.toLong() -> null
            null -> "SQLite has no ${SqliteFunctions.HOST_VERSION_FUNCTION}(): its drivine_ functions are older than version $needed, " +
                "the compiler's: register the module's host functions"
            else -> "SQLite's drivine_ functions are version $reported, the compiler needs $needed: register the module's host functions"
        }
    }
}
