@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package org.drivine.cypher.api

import org.drivine.cypher.store.SqliteFunctions

/** The wasm build's compiler: a request as JSON text, and back the answer as JSON text (see [CompileJson]). */
@JsExport
fun compile(request: String): String = CompileJson.compile(request)

/**
 * The version of the SQLite host functions' forms the compiler's SQL calls, [SqliteFunctions.HOST_VERSION]: a host compares
 * it with what `SELECT drivine_host_version()` gives on each connection it registers the functions on, and fails where
 * they differ, as [org.drivine.cypher.host.HostCheck] words it.
 */
@JsExport
fun hostVersion(): Int = SqliteFunctions.HOST_VERSION

/**
 * SQLite's host function of temporal arithmetic, `drivine_temporal_arithmetic(operator, x, y)`, as the module works it out
 * (`HostTemporalArithmetic`, drivine4j#418): a host whose own SQLite functions are in JavaScript registers it as a call of this,
 * so the module's one copy is the one every library runs. [x] and [y] are each a JSON text, or null for SQL's null; a number
 * SQLite hands the function is given as its text. The tag the arithmetic makes, as JSON text, or null; a pair Cypher has no
 * arithmetic of throws, naming it, as the function fails its statement.
 */
@JsExport
fun temporalArithmetic(operator: String, x: String?, y: String?): String? {
    val args = listOf<String?>(operator, x, y)
    var answer: String? = null
    var failure: String? = null
    org.drivine.cypher.host.HostFunctions.temporalArithmetic.body.call(object : org.drivine.cypher.host.HostArgs {
        override fun type(i: Int) = if (args[i] == null) org.drivine.cypher.host.SqlType.NULL else org.drivine.cypher.host.SqlType.TEXT
        override fun long(i: Int) = args[i]!!.toLong()
        override fun int(i: Int) = args[i]!!.toInt()
        override fun double(i: Int) = args[i]!!.toDouble()
        override fun text(i: Int) = args[i]
        override fun text16(i: Int) = args[i]
        override fun blob(i: Int): ByteArray? = null
    }, 3, object : org.drivine.cypher.host.HostResult {
        override fun nullValue() { answer = null }
        override fun long(value: Long) { answer = value.toString() }
        override fun int(value: Int) { answer = value.toString() }
        override fun double(value: Double) { answer = value.toString() }
        override fun text(value: String) { answer = value }
        override fun text16(value: String) { answer = value }
        override fun blob(value: ByteArray) {}
        override fun error(message: String) { failure = message }
    })
    failure?.let { throw IllegalArgumentException(it) }
    return answer
}
