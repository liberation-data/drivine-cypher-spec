package org.drivine.cypher.sql

import org.drivine.cypher.ast.Clause
import org.drivine.cypher.ast.Expression
import org.drivine.cypher.ast.NodePattern
import org.drivine.cypher.ast.Pattern
import org.drivine.cypher.ast.Query
import org.drivine.cypher.ast.Union

/**
 * A pattern comprehension in a property a `CREATE` or a `MERGE` makes binds anew what its pattern names, as Neo4j reads
 * it, a variable the same clause makes among them: in `CREATE (a), (m {f: size([(a)-->(x) | x])})` its `a` is any node,
 * not the node made (liberation-data/drivine4j#814). The compiler reads such a name as the node made, so each one is given
 * a name of its own here, unused in the statement, before the statement is compiled: `[(a_1)-->(x) | x]`. Read after
 * [VariableKinds] has checked the statement, so only the names each clause has bound are followed: what a `MATCH`, an
 * `UNWIND`, a `WITH`, a `CALL`, a vector search, a `FOREACH`, a `CREATE` or a `MERGE` binds. A statement with no such
 * comprehension is given back as it is.
 */
internal object MadeNames {

    fun rebound(query: Query): Query {
        val used = LinkedHashSet<String>()
        val fresh = { name: String -> generateSequence(1) { it + 1 }.map { "${name}_$it" }.first { it !in used }.also { used += it } }
        used += VariableKinds.namesIn(query)
        return Rebinder(fresh).query(query, emptySet()).first
    }

    private class Rebinder(val fresh: (String) -> String) {

        /** [query] rebound, over the names [outer] binds, and the names its first part returns. */
        fun query(query: Query, outer: Set<String>): Pair<Query, Set<String>> {
            val (clauses, returned) = clauses(query.clauses, outer)
            val unions = query.unions.map { it.copy(clauses = clauses(it.clauses, outer).first) }
            return Query(clauses, unions) to returned
        }

        fun clauses(clauses: List<Clause>, outer: Set<String>): Pair<List<Clause>, Set<String>> {
            var bound = outer
            var returned = emptySet<String>()
            val out = clauses.map { clause ->
                when (clause) {
                    is Clause.Match -> clause.also { bound = bound + clause.patterns.flatMap(::names) }
                    is Clause.Unwind -> clause.also { bound = bound + clause.variable }
                    is Clause.With -> clause.also { bound = (if (clause.projection.star) bound else emptySet()) + clause.projection.items.map { it.name } }
                    is Clause.Return -> clause.also { returned = clause.projection.items.map { it.name }.toSet() }
                    is Clause.VectorSearch -> clause.also { bound = bound + listOfNotNull(clause.node, clause.score) }
                    is Clause.Call -> query(clause.query, bound).let { (query, names) -> clause.copy(query = query).also { bound = bound + names } }
                    is Clause.Foreach -> clause.copy(clauses = clauses(clause.clauses, bound + clause.variable).first)
                    is Clause.Create -> {
                        val own = clause.patterns.flatMap(::names).filterTo(LinkedHashSet()) { it !in bound }
                        clause.copy(patterns = clause.patterns.map { made(it, own) }).also { bound = bound + own }
                    }
                    is Clause.Merge -> {
                        val own = names(clause.pattern).filterTo(LinkedHashSet()) { it !in bound }
                        clause.copy(pattern = made(clause.pattern, own)).also { bound = bound + own }
                    }
                    is Clause.Set, is Clause.Remove, is Clause.Delete -> clause
                }
            }
            return out to returned
        }

        /** What [pattern] names, its path among them. */
        fun names(pattern: Pattern) =
            pattern.nodes.mapNotNull { it.variable } + pattern.relationships.mapNotNull { it.variable } + listOfNotNull(pattern.variable)

        /** [pattern], each of its properties with what a pattern comprehension there names of [own] given a name of its own. */
        fun made(pattern: Pattern, own: Set<String>): Pattern {
            if (own.isEmpty()) return pattern
            return pattern.copy(
                nodes = pattern.nodes.map { node ->
                    val properties = node.properties?.let { rebind(it, own) }
                    if (properties == node.properties) node else NodePattern(node.variable, node.labels, properties).also { it.mapped = node.mapped }
                },
                relationships = pattern.relationships.map { relationship ->
                    relationship.properties?.let { rebind(it, own) }?.takeIf { it != relationship.properties }?.let { relationship.copy(properties = it) } ?: relationship
                },
            )
        }

        /** [expression] with each pattern comprehension's names of [own] in it given names of their own. */
        fun rebind(expression: Expression, own: Set<String>): Expression = transform(expression) { part ->
            if (part !is Expression.PatternComprehension) return@transform null
            val names = names(part.pattern).filter { it in own }.distinct()
            if (names.isEmpty()) return@transform null
            var comprehension: Expression = part
            names.forEach { name -> comprehension = renamed(comprehension, name, fresh(name)) }
            comprehension
        }

        /** [expression] with [from] named [to] where it is the name a pattern comprehension's pattern binds, and within it. */
        fun renamed(expression: Expression, from: String, to: String): Expression {
            fun pattern(pattern: Pattern) = pattern.copy(
                variable = pattern.variable?.let { if (it == from) to else it },
                nodes = pattern.nodes.map { node -> if (node.variable == from) NodePattern(to, node.labels, node.properties).also { it.mapped = node.mapped } else node },
                relationships = pattern.relationships.map { if (it.variable == from) it.copy(variable = to) else it },
            )
            val comprehension = expression as Expression.PatternComprehension
            return Expression.PatternComprehension(
                pattern(comprehension.pattern),
                comprehension.where?.let { references(it, from, to, ::pattern) },
                references(comprehension.projection, from, to, ::pattern),
            )
        }

        /** [expression] with each reading of [from] that nothing within it binds again read as [to]. */
        fun references(expression: Expression, from: String, to: String, pattern: (Pattern) -> Pattern): Expression = transform(expression) { part ->
            when (part) {
                is Expression.Variable -> if (part.name == from) Expression.Variable(to) else part
                is Expression.MapProjection -> if (part.variable == from) part.copy(variable = to, elements = part.elements.map { element ->
                    if (element is Expression.MapProjection.Element.Entry) element.copy(value = references(element.value, from, to, pattern)) else element
                }) else null
                is Expression.PatternPredicate -> Expression.PatternPredicate(pattern(part.pattern))
                is Expression.Exists -> Expression.Exists(part.patterns.map(pattern), part.where?.let { references(it, from, to, pattern) })
                // A name bound again within is another variable there.
                is Expression.ListComprehension -> if (part.variable == from) part.copy(list = references(part.list, from, to, pattern)) else null
                is Expression.Quantifier -> if (part.variable == from) part.copy(list = references(part.list, from, to, pattern)) else null
                is Expression.Reduce -> if (part.variable == from || part.accumulator == from) {
                    part.copy(initial = references(part.initial, from, to, pattern), list = references(part.list, from, to, pattern))
                } else null
                is Expression.PatternComprehension -> if (from in names(part.pattern)) part else null
                else -> null
            }
        }
    }

    /**
     * [expression] with each part [replace] gives a replacement for replaced, and the rest rebuilt from its parts: a part
     * it gives none for (null) is gone into. Not into a subquery's query.
     */
    private fun transform(expression: Expression, replace: (Expression) -> Expression?): Expression {
        replace(expression)?.let { return it }
        fun t(part: Expression) = transform(part, replace)
        return when (expression) {
            is Expression.Literal, is Expression.Parameter, is Expression.Variable, Expression.CountStar, is Expression.PatternPredicate, is Expression.Subquery -> expression
            is Expression.Property -> expression.copy(subject = t(expression.subject))
            is Expression.HasLabels -> expression.copy(subject = t(expression.subject))
            is Expression.ListLiteral -> expression.copy(items = expression.items.map(::t))
            is Expression.MapLiteral -> expression.copy(entries = expression.entries.map { (key, value) -> key to t(value) })
            is Expression.MapProjection -> expression.copy(elements = expression.elements.map { if (it is Expression.MapProjection.Element.Entry) it.copy(value = t(it.value)) else it })
            is Expression.Binary -> expression.copy(left = t(expression.left), right = t(expression.right))
            is Expression.Unary -> expression.copy(operand = t(expression.operand))
            is Expression.Index -> expression.copy(subject = t(expression.subject), index = t(expression.index))
            is Expression.Slice -> expression.copy(subject = t(expression.subject), from = expression.from?.let(::t), to = expression.to?.let(::t))
            is Expression.Function -> expression.copy(arguments = expression.arguments.map(::t))
            is Expression.Case -> expression.copy(
                subject = expression.subject?.let(::t),
                alternatives = expression.alternatives.map { (key, value) -> t(key) to t(value) },
                otherwise = expression.otherwise?.let(::t),
            )
            is Expression.ListComprehension -> expression.copy(list = t(expression.list), where = expression.where?.let(::t), projection = expression.projection?.let(::t))
            is Expression.Quantifier -> expression.copy(list = t(expression.list), where = expression.where?.let(::t))
            is Expression.Reduce -> expression.copy(initial = t(expression.initial), list = t(expression.list), expression = t(expression.expression))
            is Expression.PatternComprehension -> expression.copy(where = expression.where?.let(::t), projection = t(expression.projection))
            is Expression.Exists -> expression.copy(where = expression.where?.let(::t))
        }
    }
}
