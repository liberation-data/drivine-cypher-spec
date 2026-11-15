package org.drivine.cypher

import org.drivine.cypher.host.HostArgs
import org.drivine.cypher.host.HostFunction
import org.drivine.cypher.host.HostFunctions
import org.drivine.cypher.host.HostResult
import org.drivine.cypher.host.SqlType
import org.sqlite.Function
import org.sqlite.core.Codes
import java.sql.Connection
import java.sql.DriverManager

/** A SQLite in memory with the module's host functions registered as drivine4j registers them, and `drivine_fail`, for tests that run compiled SQL. */
internal object SqliteHost {

    fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { c ->
        HostFunctions.all.forEach { register(c, it) }
        // drivine_fail, which each library registers itself, as a refusal the SQL writes calls it (#263's among them).
        Function.create(c, org.drivine.cypher.store.SqliteFunctions.FAIL, object : Function() {
            override fun xFunc() = error(value_text(0) ?: "failed")
        }, 1, 0)
    }

    /** [function] registered on [connection], as drivine4j's `SqliteHostFunction` registers each. */
    private fun register(connection: Connection, function: HostFunction) =
        Function.create(connection, function.name, Host(function), function.arity, if (function.deterministic) Function.FLAG_DETERMINISTIC else 0)

    /** A host function as xerial's driver calls one: drivine4j's `SqliteHostFunction`, for the test. */
    private class Host(private val function: HostFunction) : Function() {
        override fun xFunc() {
            try {
                function.body.call(args, args(), result)
            } catch (e: Exception) {
                error(e.message ?: e.javaClass.simpleName)
            }
        }

        private val args = object : HostArgs {
            override fun type(i: Int) = when (value_type(i)) {
                Codes.SQLITE_INTEGER -> SqlType.INTEGER
                Codes.SQLITE_FLOAT -> SqlType.FLOAT
                Codes.SQLITE_TEXT -> SqlType.TEXT
                Codes.SQLITE_BLOB -> SqlType.BLOB
                else -> SqlType.NULL
            }
            override fun long(i: Int) = value_long(i)
            override fun int(i: Int) = value_int(i)
            override fun double(i: Int) = value_double(i)
            override fun text(i: Int): String? = value_text(i)
            override fun text16(i: Int): String? = value_text(i)
            override fun blob(i: Int): ByteArray? = value_blob(i)
        }

        private val result = object : HostResult {
            override fun nullValue() = result()
            override fun long(value: Long) = result(value)
            override fun int(value: Int) = result(value)
            override fun double(value: Double) = result(value)
            override fun text(value: String) = result(value)
            override fun text16(value: String) = result(value)
            override fun blob(value: ByteArray) = result(value)
            override fun error(message: String) = this@Host.error(message)
        }
    }
}
