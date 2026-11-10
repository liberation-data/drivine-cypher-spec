package org.drivine.schema

/**
 * How a schema manager (drivine4j's `SchemaManager`) treats outcomes that need attention (drift, constraint violations).
 * In common Kotlin, beside [SchemaPolicy], so that every library reads a policy alike.
 */
enum class SchemaMode {

    /** Fail with an exception when drift or a violation is found (default). */
    FAIL_FAST,

    /** Log a warning and continue. */
    WARN,
}