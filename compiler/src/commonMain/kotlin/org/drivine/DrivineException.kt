package org.drivine

/** SPIKE: drivine4j's exception without its `QuerySpecification`, which belongs to the library, not the compiler. */
open class DrivineException(
    message: String? = null,
    val rootCause: Throwable? = null,
) : RuntimeException(message ?: rootCause?.message ?: "An unknown error occurred", rootCause)
