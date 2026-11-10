package org.drivine.cypher.store

import org.drivine.DrivineInternalApi
import kotlin.random.Random

/**
 * The stamp Drivine keeps on a node, so a later save can tell whether the node is as it was when the object was loaded:
 * the property the compiler reads, and the stamp's form, which drivine4j's `org.drivine.model.Stamps` gives and the spec's
 * `marker.yaml` pins. Each library reads a stamp another wrote, so the form is written once, here.
 *
 * A stamp is two tokens of [TOKEN] lower-case hexadecimal digits joined by a colon, as `3fa9c1d27b40e8a6:91d0f4b2c7ee5a13`:
 *
 * - the node token, replaced when a save changes the node's properties or labels;
 * - the links token, replaced when a relationship of the node is added or removed, or its properties change.
 *
 * A save compares the part it would overwrite. A stored stamp of another shape, which only something other than a save
 * leaves, is compared whole and replaced whole.
 */
@DrivineInternalApi
object Stamps {
    const val PROPERTY = "__drivine.stamp"

    /** The length of one token; a stamp is two, and the colon between them. */
    const val TOKEN = 16

    /** What separates the node token from the links token. */
    const val SEPARATOR = ':'

    /** What a statement reports as the stamp it found on a node that was there and had none. A node it made reports none at all. */
    const val NEVER_STAMPED = "-"

    /**
     * A token: the 64 bits of [bits] as [TOKEN] lower-case hexadecimal digits, as Java's `String.format("%016x", bits)`
     * writes them, a negative number as its two's complement. Written out, not by a platform's formatter, so every target
     * gives the same text.
     */
    fun token(bits: Long): String = bits.toULong().toString(16).padStart(TOKEN, '0')

    /** The stamp of the tokens [node] and [links]: [links] left empty where it is null, as a stamp with a node token alone is. */
    fun of(node: String, links: String?): String = "$node$SEPARATOR${links.orEmpty()}"

    /** A new stamp, both tokens new, from [random]: a test gives one, so that its stamps are known. */
    fun fresh(random: Random = Random.Default): String = of(token(random.nextLong()), token(random.nextLong()))

    /** The token of [stamp] that speaks for the node's properties and labels: all of it, where it has no colon. */
    fun nodeToken(stamp: String): String = stamp.substringBefore(SEPARATOR)

    /** The token of [stamp] that speaks for the node's relationships, what follows its first colon; null where nothing does. */
    fun linksToken(stamp: String): String? = stamp.substringAfter(SEPARATOR, "").ifEmpty { null }

    /** [stamp] with its node token alone: it speaks for the node's own data and for none of its relationships. */
    fun withoutLinks(stamp: String): String = of(nodeToken(stamp), null)

    /**
     * Whether [stamp] is two tokens joined by a colon, as every stamp a save leaves is. Told by its length and where its
     * colon is, not by its digits, as drivine4j and the Cypher a save writes test it, so a library that checked the digits
     * too would take a stamp the others keep for one they replace.
     */
    fun isTwoTokens(stamp: String): Boolean = stamp.length == 2 * TOKEN + 1 && stamp[TOKEN] == SEPARATOR

    /**
     * Whether [stamp] has a node token to compare on its own: a token and a colon, whatever follows. A stamp of another
     * shape is compared whole.
     */
    fun hasNodeToken(stamp: String): Boolean = stamp.length > TOKEN && stamp[TOKEN] == SEPARATOR
}

/** The lower-case copies' property names, which the schema specs use and drivine4j's `org.drivine.model.LowerCopies` calls. */
@DrivineInternalApi
object LowerCopies {
    const val PREFIX = "__drivine.lower."
    fun of(property: String): String = PREFIX + property
}
