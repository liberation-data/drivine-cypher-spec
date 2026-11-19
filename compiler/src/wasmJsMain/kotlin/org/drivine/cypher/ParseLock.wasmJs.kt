package org.drivine.cypher

/** None: wasm runs one thread (see [ParserCaches.parsing]). */
internal actual class ParseLock actual constructor() {
    actual fun <T> locked(block: () -> T): T = block()
}
