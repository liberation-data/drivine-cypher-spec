package org.drivine.cypher.store

import org.drivine.DrivineInternalApi

/** The stamp's property, which the compiler reads and drivine4j's `org.drivine.model.Stamps.PROPERTY` names. */
@DrivineInternalApi
object Stamps {
    const val PROPERTY = "__drivine.stamp"
}

/** The lower-case copies' property names, which the schema specs use and drivine4j's `org.drivine.model.LowerCopies` calls. */
@DrivineInternalApi
object LowerCopies {
    const val PREFIX = "__drivine.lower."
    fun of(property: String): String = PREFIX + property
}
