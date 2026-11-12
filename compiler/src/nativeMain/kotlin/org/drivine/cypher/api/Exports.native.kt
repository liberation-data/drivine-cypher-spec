@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package org.drivine.cypher.api

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.free
import platform.posix.malloc
import platform.posix.memcpy
import org.drivine.cypher.store.SqliteFunctions

/**
 * The shared library's C ABI:
 *
 * ```c
 * char *drivine_compile(const char *request);  // NUL-terminated UTF-8 JSON in, and out: see CompileJson
 * void drivine_free(char *answer);             // frees what drivine_compile gave
 * int drivine_host_version(void);              // the host functions' version the compiler's SQL needs: see HostCheck
 * ```
 *
 * Each call is independent of every other, so a host may call it from several threads at once. A NULL request is read as
 * an empty one, which is answered with an error; the answer is NULL only where malloc fails, and is the host's to free
 * with drivine_free, as it is malloc's.
 */
@CName("drivine_compile")
fun drivineCompile(request: CPointer<ByteVar>?): CPointer<ByteVar>? {
    val answer = CompileJson.compile(request?.toKString() ?: "").encodeToByteArray()
    val out = malloc((answer.size + 1).convert())?.reinterpret<ByteVar>() ?: return null
    if (answer.isNotEmpty()) answer.usePinned { memcpy(out, it.addressOf(0), answer.size.convert()) }
    out[answer.size] = 0
    return out
}

@CName("drivine_free")
fun drivineFree(answer: CPointer<ByteVar>?) = free(answer)

/**
 * The version of the SQLite host functions' forms the compiler's SQL calls, [SqliteFunctions.HOST_VERSION]: a host that
 * registers its own functions compares it with what `SELECT drivine_host_version()` gives on each connection, as
 * [org.drivine.cypher.host.HostCheck] does. The C symbol and the SQL function share the name, as they give the same number
 * where the extension's functions are registered.
 */
@CName("drivine_host_version")
fun drivineHostVersion(): Int = SqliteFunctions.HOST_VERSION
