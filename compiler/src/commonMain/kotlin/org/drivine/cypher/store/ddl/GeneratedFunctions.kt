package org.drivine.cypher.store.ddl

import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.Sha256
import org.drivine.cypher.sql.TemporalArithmeticFunction
import org.drivine.cypher.store.OracleSql

/**
 * The store functions whose bodies the module generates, as `store.yaml` holds them: by a marker, not their text, with a
 * digest of what each marker stands for (liberation-data/drivine4j#418). The function of temporal arithmetic is the compiler's
 * own inline arithmetic, written for each pair of kinds ([TemporalArithmeticFunction]), some 70 to 90 KB on each store, and
 * changes wherever the inline arithmetic does: nobody writes it by hand, and each library takes it from the module.
 *
 * A marker is `<TemporalArithmeticFunction.<form>(<graph>)>`, where the form is
 * - `postgres`: [TemporalArithmeticFunction.postgres], the `CREATE OR REPLACE FUNCTION` that [PostgresDdl.ddl] holds;
 * - `postgresEnsuring`: that statement as [PostgresDdl.ensuring] runs it, its lines indented within the block;
 * - `oracle`: the function as [OracleDdl.functions] holds it, `CREATE OR REPLACE` and its version comment
 *   ([OracleDdl.versions]) with it;
 * - `oracleClob`: that text as the `CLOB` literal [OracleDdl.ensuring] runs it as ([OracleSql.clob]).
 *
 * A runner of `store.yaml`'s statements expands each marker with [expand] before comparing, in every library: the one place
 * a marker is read.
 */
@DrivineInternalApi
object GeneratedFunctions {

    /** A marker: its form and its graph. */
    val MARKER = Regex("""<TemporalArithmeticFunction\.(postgres|postgresEnsuring|oracle|oracleClob)\(([A-Za-z_][A-Za-z0-9_]*)\)>""")

    /** What the marker of [form] for the graph [graph] stands for. */
    fun text(form: String, graph: String): String = when (form) {
        "postgres" -> TemporalArithmeticFunction.postgres(graph)
        "postgresEnsuring" -> postgresEnsuring(graph)
        "oracle" -> oracleFunction(graph)
        "oracleClob" -> OracleSql.clob(oracleFunction(graph))
        else -> throw IllegalArgumentException("No generated function's form is $form: postgres, postgresEnsuring, oracle or oracleClob.")
    }

    /** [text] with each marker in it expanded to what it stands for. */
    fun expand(text: String): String = MARKER.replace(text) { text(it.groupValues[1], it.groupValues[2]) }

    /**
     * [text], a statement the module wrote for the graph [graph], with what each marker stands for written as the marker:
     * how `store.yaml`'s statements are written down. The `oracleClob` form first, as it holds the `oracle` form's text, quoted.
     */
    fun marked(text: String, graph: String): String =
        FORMS.fold(text) { written, form -> written.replace(text(form, graph), "<TemporalArithmeticFunction.$form($graph)>") }

    /** Each form, in the order [marked] writes them. */
    val FORMS = listOf("oracleClob", "postgresEnsuring", "postgres", "oracle")

    /** The hex SHA-256 of what the marker of [form] for [graph] stands for, as `store.yaml` pins it. */
    fun digest(form: String, graph: String): String = hex(text(form, graph))

    /** The hex SHA-256 of [text]. */
    fun hex(text: String): String = Sha256.digest(text.encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /**
     * The function as [PostgresDdl.ensuring] runs it: from its `CREATE OR REPLACE FUNCTION` to the quote that closes its body,
     * cut from the block itself, so the marker stands for what the block sends, byte for byte.
     */
    private fun postgresEnsuring(graph: String): String {
        val block = PostgresDdl(graph).ensuring
        val start = block.indexOf("CREATE OR REPLACE FUNCTION ${TemporalArithmeticFunction.name(graph)}(")
        check(start >= 0) { "PostgresDdl.ensuring of $graph makes no ${TemporalArithmeticFunction.name(graph)}." }
        val end = block.indexOf("END;'", start)
        check(end >= 0) { "PostgresDdl.ensuring of $graph doesn't close ${TemporalArithmeticFunction.name(graph)}'s body." }
        return block.substring(start, end + "END;'".length)
    }

    private fun oracleFunction(graph: String): String {
        val ddl = OracleDdl(graph)
        return ddl.functions.single { it.startsWith("CREATE OR REPLACE FUNCTION ${TemporalArithmeticFunction.name(graph)}(") }
    }
}
