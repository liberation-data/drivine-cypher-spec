package org.drivine.model

/** SPIKE: the one constant of drivine4j's `Stamps` the compiler reads. */
object Stamps {
    const val PROPERTY = "__drivine.stamp"
}

/** SPIKE: drivine4j's `LowerCopies`, the part the schema specs use. */
object LowerCopies {
    const val PREFIX = "__drivine.lower."
    fun of(property: String): String = PREFIX + property
}
