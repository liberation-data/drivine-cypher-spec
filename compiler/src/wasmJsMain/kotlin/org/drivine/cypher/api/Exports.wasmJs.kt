@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package org.drivine.cypher.api

/** The wasm build's one export: a request as JSON text, and back the answer as JSON text (see [CompileJson]). */
@JsExport
fun compile(request: String): String = CompileJson.compile(request)
