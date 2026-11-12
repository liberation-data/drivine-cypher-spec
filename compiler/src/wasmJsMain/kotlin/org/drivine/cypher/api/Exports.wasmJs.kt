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
