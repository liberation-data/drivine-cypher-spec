package org.drivine.cypher

import org.drivine.cypher.ast.Expression

/**
 * Neo4j's vector search in Cypher that a SQL store will not take, refused by name as the statement is read, before it is
 * compiled (liberation-data/drivine-cypher-spec#160): what needs a vector index on relationships, or Neo4j's VECTOR
 * type, neither of which a SQL store has, the procedure that sets an embedding, which a SET does, and a call of
 * `db.index.vector.queryNodes` with a number of neighbours written as a float, `1.5` or `2.0`, as Neo4j refuses it before
 * it runs (22NB1). The search itself, `db.index.vector.queryNodes` with a whole number or a parameter, is read to
 * [org.drivine.cypher.ast.Clause.VectorSearch] and compiled.
 */
internal object VectorCypher {

    /** The functions of Neo4j's VECTOR type, by their names in lower case: Cypher takes a function's name in any case. */
    private val VECTOR_FUNCTIONS = setOf("vector", "vector_distance", "vector_norm", "vector_dimension_count")

    /**
     * The message a call of the procedure [name] (as written, as Neo4j matches it) is refused with, or null; [k] reads its
     * second argument, the number of neighbours, only where that is checked.
     */
    fun refusal(name: String, k: () -> Expression?): String? = when (name) {
        "db.index.vector.queryRelationships" ->
            "A vector index on relationships is not made on a SQL store, so db.index.vector.queryRelationships() has none to search."
        "db.create.setNodeVectorProperty" ->
            "db.create.setNodeVectorProperty() is not compiled to SQL: set the embedding as a list with SET, as in SET n.embedding = [0.1, 0.2, 0.3]."
        "db.index.vector.queryNodes" -> k()?.let(::fraction)?.let { written ->
            "db.index.vector.queryNodes() takes a whole number of neighbours, not $written, as in Neo4j."
        }
        else -> null
    }

    /** The message a call of the function [name] (with its namespace, in any case) is refused with, or null. */
    fun functionRefusal(name: String): String? = name.lowercase().takeIf { it in VECTOR_FUNCTIONS }?.let {
        "$it() is of Neo4j's VECTOR type, which a SQL store doesn't hold: an embedding there is a list of numbers, as vector.similarity.cosine() and vector.similarity.euclidean() take."
    }

    /** [k] as Java writes it, where it is a float literal, negated or not, or null: a whole number or anything else is not checked. */
    private fun fraction(k: Expression): String? = when {
        k is Expression.Literal && k.value is Double -> org.drivine.cypher.text.JavaText.of(k.value)
        k is Expression.Unary && k.operator == Expression.Unary.Operator.NEGATE -> fraction(k.operand)?.let { "-$it" }
        else -> null
    }
}
