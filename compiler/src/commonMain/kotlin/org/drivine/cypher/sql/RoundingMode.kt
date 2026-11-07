package org.drivine.cypher.sql

/** SPIKE: the rounding modes `round()` names, as Java's `java.math.RoundingMode` names them, for Neo4j takes those names. */
enum class RoundingMode { UP, DOWN, CEILING, FLOOR, HALF_UP, HALF_DOWN, HALF_EVEN, UNNECESSARY }
