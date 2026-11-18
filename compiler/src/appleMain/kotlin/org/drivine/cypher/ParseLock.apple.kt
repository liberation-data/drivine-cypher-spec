@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.drivine.cypher

import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

/**
 * A mutex, as a statement is read for as long as a parse takes, which a spin lock would burn a waiting thread's core
 * through (see [ParserCaches.parsing]): as on Linux (ParseLock.linux.kt).
 */
internal actual class ParseLock actual constructor() {
    private val mutex = nativeHeap.alloc<pthread_mutex_t>().also { pthread_mutex_init(it.ptr, null) }

    actual fun <T> locked(block: () -> T): T {
        pthread_mutex_lock(mutex.ptr)
        try {
            return block()
        } finally {
            pthread_mutex_unlock(mutex.ptr)
        }
    }
}
