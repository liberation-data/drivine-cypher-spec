package org.drivine.cypher

/** None: on the JVM, ANTLR's own `synchronized` guards its prediction caches' additions (see [ParserCaches.parsing]). */
internal actual class ParseLock actual constructor() {
    actual fun <T> locked(block: () -> T): T = block()
}
