# The port's mechanical rewrites of drivine4j's Kotlin, which scripts/sync-from-drivine4j.sh makes to drivine4j's files
# before it merges them, so that a line the port changed by these alone merges cleanly. Each maps a JVM library's name to
# the module's common one of the same name and shape; an edit of anything more is the port's own, and merges as one.
# sed -E, one rule a line.

# antlr-kotlin's runtime, in imports and in names written out in full.
s/org\.antlr\.v4\.runtime\./org.antlr.v4.kotlinruntime./g
# Jackson's tree, as the module's of the same names (org.drivine.cypher.json, see JsonTree.kt).
s/^import com\.fasterxml\.jackson\.databind\.JsonNode$/import org.drivine.cypher.json.JsonNode/
s/^import com\.fasterxml\.jackson\.databind\.node\.(ArrayNode|BigIntegerNode|BooleanNode|DecimalNode|DoubleNode|FloatNode|JsonNodeFactory|LongNode|NullNode|ObjectNode|TextNode)$/import org.drivine.cypher.json.\1/
# bignum's for java.math's.
s/^import java\.math\.BigDecimal$/import com.ionspin.kotlin.bignum.decimal.BigDecimal/
s/^import java\.math\.BigInteger$/import com.ionspin.kotlin.bignum.integer.BigInteger/
# ANTLR's error listener as antlr-kotlin declares it: syntaxError's recognizer and message are not null there, and an
# override must say so. Only that parameter list is matched.
s/recognizer: Recognizer<\*, \*>\?, offendingSymbol: Any\?, line: Int, charPositionInLine: Int, msg: String\?, e: RecognitionException\?/recognizer: Recognizer<*, *>, offendingSymbol: Any?, line: Int, charPositionInLine: Int, msg: String, e: RecognitionException?/
