package org.drivine.cypher

/**
 * The compiler's refusal of a statement, or of a value it is given: [message] is what each library throws, as the spec's
 * refusals pin it.
 *
 * drivine4j throws its own `org.drivine.DrivineException`, which names the query it ran, and turns this into one at each of
 * its calls into the compiler, keeping the message, the causes and the stack trace.
 *
 * @param rootCause the error the refusal comes of, if any.
 * @param detail what the refusal says past its pinned message, which no library checks: null until the spec's refusals
 *   have one (drivine-cypher-spec#62). It is a parameter now, so that giving one later changes no signature.
 */
open class CompilerException(
    message: String? = null,
    val rootCause: Throwable? = null,
    val detail: String? = null,
) : RuntimeException(message ?: rootCause?.message ?: "An unknown error occurred", rootCause)
