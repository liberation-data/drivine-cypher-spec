package org.drivine

/**
 * A declaration of the compiler that drivine4j's own code uses and nothing else should: public only so that drivine4j,
 * built apart from the compiler, can call it. It may change or go in any release, without notice.
 *
 * drivine4j opts in to it module-wide (`-opt-in=org.drivine.DrivineInternalApi`); any other caller is refused at compile
 * time unless it opts in too.
 */
@RequiresOptIn(
    message = "This is drivine4j's own use of the compiler, and may change or go in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class DrivineInternalApi
