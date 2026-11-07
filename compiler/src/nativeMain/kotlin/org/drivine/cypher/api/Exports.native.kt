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

/**
 * The shared library's C ABI:
 *
 * ```c
 * char *drivine_compile(const char *request);  // NUL-terminated UTF-8 JSON in, and out: see CompileJson
 * void drivine_free(char *answer);             // frees what drivine_compile gave
 * ```
 *
 * Each call is independent of every other, so a host may call it from several threads at once.
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
