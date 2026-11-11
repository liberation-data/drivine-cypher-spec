package org.drivine.cypher.store.ddl

import org.drivine.DrivineInternalApi
import org.drivine.cypher.CompilerException
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.IndexSpec
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.VectorIndexSpec

/**
 * A statement that makes, drops or lists part of a store's schema: text the store runs as it runs any statement, or, on
 * FalkorDB, a command its driver sends as it is ([Native]).
 */
@DrivineInternalApi
sealed interface DdlStatement {

    /** A statement in the store's own language: Cypher on a graph engine, SQL or a PL/SQL block on a SQL store. */
    data class Text(val statement: String) : DdlStatement

    /**
     * A command of the store's driver, not of its query language: FalkorDB's `GRAPH.CONSTRAINT`. [GRAPH_NAME] in it
     * stands for the graph's name, which the library that runs it puts in.
     */
    data class Native(val args: List<String>) : DdlStatement {
        companion object {
            const val GRAPH_NAME = "{graphName}"
        }
    }
}

/**
 * An index or a constraint as a store has it, as the statements that drop it need it: its [kind], [label] and
 * [properties], and the [name] it was made under, where the store names it. What a library reads back from the store's
 * catalog, not the spec it was made from: an item is dropped as it was made.
 */
@DrivineInternalApi
data class CatalogItem(
    val kind: SchemaItemKind,
    val label: String,
    val properties: List<String>,
    val name: String? = null,
)

/**
 * How a SQL store makes a uniqueness constraint again without leaving it with no index where the data refuses it
 * (drivine-cypher-spec#135 §4), each a list of statements run in order:
 *
 * - [leftover] drops the unique index [temporary], where a recreate that stopped short left it;
 * - [build] makes the unique index anew under [temporary], over the expressions the constraint's `create` makes it over,
 *   while the old one stands: data that repeats a key refuses it, and the old index and its record are kept;
 * - [swap] puts it in the old index's place, under [name]: on Postgres one block that drops the old and renames it, on
 *   Oracle the drop and then the rename, and on SQLite, which renames no index, the drop, the index made again under
 *   [name] (which the data, held unique by [temporary], can't refuse), and [temporary] dropped;
 * - [remade] records the record as made in this version's form as [name], keeping its aliases, after the constraint's
 *   `create`, which finds the index there and records the item where no record is.
 */
@DrivineInternalApi
data class UniqueRecreation(
    val name: String,
    val temporary: String,
    val leftover: List<DdlStatement>,
    val build: List<DdlStatement>,
    val swap: List<DdlStatement>,
    val remade: String,
)

/**
 * The statements that make, drop and list a store's indexes and constraints, for a graph and the schema specs: what the
 * three libraries' schema grammars each wrote, written once here so that a store one library makes is the store another
 * reads. Each is a pure function of its arguments. Running them, and deciding what to run, stays each library's.
 *
 * A spec is given as it is made: one that ignores case as [org.drivine.schema.made] gives it, over the lowercased copies.
 */
@DrivineInternalApi
interface StoreDdl {

    /** The store's name, as a refusal names it. */
    val engine: String

    /**
     * What makes [spec]. [existing] is what the store has already of the same kind on the label, where a library read
     * it: FalkorDB indexes a label's properties one at a time, and refuses one indexed already.
     */
    fun createIndex(spec: IndexSpec, existing: CatalogItem? = null): List<DdlStatement>

    /** What makes [spec]. */
    fun createConstraint(spec: ConstraintSpec): List<DdlStatement>

    /** What drops the index [item]. */
    fun dropIndex(item: CatalogItem): List<DdlStatement>

    /** What drops the constraint [item]. */
    fun dropConstraint(item: CatalogItem): List<DdlStatement>

    /** The query that lists the store's indexes of [kind] (a graph engine's lists every kind; a library keeps those it asked for). */
    fun listIndexesQuery(kind: SchemaItemKind): String

    /** The query that lists the store's uniqueness constraints. */
    fun listConstraintsQuery(): String

    /** What makes [spec], an index or a constraint. */
    fun create(spec: SchemaItemSpec, existing: CatalogItem? = null): List<DdlStatement> = when (spec) {
        is IndexSpec -> createIndex(spec, existing)
        is ConstraintSpec -> createConstraint(spec)
    }

    /** What drops [item], an index or a constraint by its kind. */
    fun drop(item: CatalogItem): List<DdlStatement> =
        if (item.kind == SchemaItemKind.UNIQUENESS_CONSTRAINT) dropConstraint(item) else dropIndex(item)
}

/**
 * The refusal of a TEXT index on a store without one: Neo4j alone has it. The message is drivine4j's, which each library
 * gives.
 */
internal fun unsupportedTextIndex(spec: TextIndexSpec, engine: String): CompilerException = CompilerException(
    "TEXT index ${spec.effectiveName} on ${spec.label}${spec.properties} cannot be made on $engine: " +
        "a TEXT index is Neo4j's, and $engine has none. For tokenized search declare a FullTextIndexSpec " +
        "(@FullTextIndex), which is also what Memgraph calls a TEXT INDEX; for exact lookups, a RangeIndexSpec."
)

/**
 * A graph as a SQL store keeps it: a table of nodes, a table of relationships, and a catalog of what Drivine made of
 * indexes and constraints. Oracle, Postgres and SQLite keep the same tables, so a store written from one library reads the
 * same from another:
 *
 * - `<name>_node`: a row for each node, keyed by `nid`, with its labels (a JSON array), its properties (a JSON object keyed
 *   by the names the graph engines store them under) and its stamp;
 * - `<name>_edge`: a row for each relationship, keyed by `eid`, from the node `src` to the node `dst`, with its type and its
 *   properties, removed with either node;
 * - `<name>_schema`: the catalog, a row for each index or constraint made, keyed by its kind, label and properties.
 *
 * What a store makes beside the tables is named by [objectName], within the [maxObjectName] it allows.
 *
 * @param engine the store's name, for what is said of a name it cannot take
 */
@DrivineInternalApi
abstract class SqlStoreDdl(val name: String, final override val engine: String, private val maxObjectName: Int) : StoreDdl {

    init {
        refusal(name, engine, maxObjectName)?.let { throw IllegalArgumentException(it) }
    }

    /** The table of nodes: a row each, with its labels, its properties and its stamp. */
    val nodes = "${name}_node"

    /** The table of relationships: a row each, from the node `src` to the node `dst`, with its type and its properties. */
    val edges = "${name}_edge"

    /** What Drivine made of indexes and constraints. */
    val catalog = "${name}_schema"

    /** The table that holds the text a full-text index on [label]'s [properties] is over. */
    fun texts(label: String, properties: List<String>): String = objectName("t", listOf(label) + properties)

    /** The table that holds the embeddings a vector index on [label]'s [property] is over. */
    fun vectors(label: String, property: String): String = objectName("v", listOf(label, property))

    /**
     * A name for an object of [kind] made for [parts], a label and properties: the graph's name, the kind, what of the parts
     * reads as a name, and a hash of the parts whole, which tells apart two objects whose parts read as one name: a label
     * and a property that run together as another pair does, or names that differ only in what a name cannot hold. It is a
     * hash of 32 bits and not a proof: two sets of parts that read alike and hash alike would share a name. It leaves room
     * for the `_t` or `_i` an object made beside it adds, within the [maxObjectName] the store allows.
     *
     * The hash is Java's `String.hashCode`, as drivine4j first named every object by it ([javaHashCode]), so a store made by
     * any library on any platform names its objects alike. The name is written to the graph's catalog with what it was
     * made for, and an object is dropped by the name recorded there.
     */
    fun objectName(kind: String, parts: List<String>): String {
        val hash = javaHashCode(parts.joinToString("\u0000")).toUInt().toString(16)
        val fixed = "${name}_${kind}__${hash}".length + BESIDE.length
        val readable = parts.joinToString("") { it.replace(NOT_READABLE, "") }.take((maxObjectName - fixed).coerceIn(0, 40))
        return "${name}_${kind}_${readable}_$hash"
    }

    /** The properties of an item as its record holds them: part of the catalog's key, so written byte for byte the same each time. */
    protected fun json(values: List<String>): String = values.joinToString(",", "[", "]") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }

    /** A full-text index's details, as its record holds them: its analyzer, where it names one. */
    protected fun analyzerDetails(analyzer: String?): String = analyzer?.let { "{\"analyzer\": ${json(listOf(it)).removeSurrounding("[", "]")}}" } ?: "{}"

    /** A vector index's details, as its record holds them: its size and its similarity. */
    protected fun vectorDetails(spec: VectorIndexSpec): String = """{"dimensions": ${spec.dimensions}, "similarity": "${spec.similarity.name}"}"""

    /** [aliases] as the JSON array a record's details keep them in, written byte for byte the same each time. */
    protected fun aliasesJson(aliases: List<String>): String =
        aliases.joinToString(",", "[", "]") { alias ->
            alias.map { c ->
                when {
                    c == '\\' -> "\\\\"
                    c == '"' -> "\\\""
                    c < ' ' -> "\\u" + c.code.toString(16).padStart(4, '0')
                    else -> c.toString()
                }
            }.joinToString("", "\"", "\"")
        }

    /**
     * [details], a record's, with the form this version makes its item in ([CURRENT_FORM]) first among them: every record
     * the module writes says the form it was made in, so a later version, or another library, tells an item made in
     * another form from one made as it makes it (see `org.drivine.cypher.store.schema.ItemForm`).
     */
    protected fun formed(details: String): String {
        val body = details.trim().removePrefix("{").removeSuffix("}").trim()
        return if (body.isEmpty()) "{\"$FORM\": $CURRENT_FORM}" else "{\"$FORM\": $CURRENT_FORM, $body}"
    }

    /**
     * The test that a name [column] holds is one Drivine gives an object of one of [kinds] (`ix`, `uq`, …), for this graph:
     * `<graph>_<kind>_…`, matched in any case on SQLite, and in the case the store folds an unquoted name to elsewhere
     * ([upper]).
     */
    protected fun namedAs(column: String, kinds: List<String>, upper: Boolean): String = kinds.joinToString(" OR ") { kind ->
        val prefix = "${name}_${kind}_".let { if (upper) it.uppercase() else it.lowercase() }
        "$column LIKE ${literal(prefix.replace("_", "\\_") + "%")} ESCAPE '\\'"
    }

    /** The SQL literal of [text]: every SQL store reads a quote doubled, and a backslash as itself. */
    protected fun literal(text: String): String = "'${text.replace("'", "''")}'"

    /** The test of the catalog's key that finds the record of [item]. */
    protected fun recordOf(item: CatalogItem): String =
        "kind = ${literal(item.kind.name)} AND label = ${literal(item.label)} AND properties = ${literal(json(item.properties))}"

    /**
     * What sets the aliases [item]'s record keeps, the names other than its own that a `DROP` finds it by (an empty list
     * takes them away): Neo4j's schema DDL names an item as it likes, and a SQL store makes it under the schema API's name.
     */
    abstract fun aliased(item: CatalogItem, aliases: List<String>): String

    /**
     * What records [item]'s record as made in [CURRENT_FORM], and changes nothing else: for an item whose record says no
     * form, made in this version's form before records said one. Safe to run again.
     */
    abstract fun formRecorded(item: CatalogItem): String

    /**
     * What drops the index [name], by its name: one that stands under a name Drivine gives and isn't the item the name is
     * for, which a recreate drops before it makes the item, as it was never recorded as one.
     */
    fun droppedIndex(name: String): String = "DROP INDEX IF EXISTS $name"

    /**
     * The name a uniqueness constraint's unique index named [name] is built under first, when it is made again
     * ([recreation]): [name] and `_r`. [objectName] leaves two characters for it, so it fits within the [maxObjectName]
     * the store allows, but for a graph whose name is as long as a graph's may be ([refusal]), whose `uq` names are one
     * character longer: there it is cut by one, and its last character, of the hash, gives way. No name Drivine gives
     * an object ends in `_r`, the hash being hexadecimal, so it is never another object's.
     */
    fun temporaryName(name: String): String = name.take(maxObjectName - TEMPORARY.length) + TEMPORARY

    /**
     * What makes the uniqueness constraint [spec] again so that data it refuses leaves the index it had (#135 §4): the
     * unique index is built under a name of its own first ([temporaryName]), and only once it holds is it put in the
     * place of the old one. A library's `create` follows it, which finds the index made, and then the record is made the
     * current form's ([UniqueRecreation.remade]).
     */
    abstract fun recreation(spec: ConstraintSpec): UniqueRecreation

    /**
     * What records [item]'s record as made in [CURRENT_FORM] as the object [objectName], and changes nothing else (its
     * aliases are kept): for a uniqueness constraint made again in its place ([recreation]). Safe to run again.
     */
    abstract fun remade(item: CatalogItem, objectName: String): String

    companion object {
        /** The key in a record's details its aliases are kept under. */
        const val ALIASES = "aliases"

        /** The key in a record's details the form its item was made in is kept under. */
        const val FORM = "form"

        /**
         * The form this version makes every item in, on Postgres, Oracle and SQLite, as its record says. A change to what
         * an item's create makes is a new form, and its record says so: `store.yaml` pins each case's form, and a digest
         * of its create statements, so a change of the DDL that keeps the form fails its tests.
         */
        const val CURRENT_FORM = 1

        /** The longest an object made beside another adds to its name: a trigger's `_t`, an index's `_i`. */
        private const val BESIDE = "_t"

        /** What follows a unique index's name in the name it is built under first, when it is made again. */
        const val TEMPORARY = "_r"

        private val NOT_READABLE = Regex("[^A-Za-z0-9]")

        /**
         * Java's `String.hashCode` of [text]: `31 * h + c` over its UTF-16 code units, in 32 bits. Written out, and not the
         * platform's `hashCode`, which Kotlin promises nothing of off the JVM: an object's name holds it.
         */
        fun javaHashCode(text: String): Int {
            var h = 0
            for (c in text) h = 31 * h + c.code
            return h
        }

        /**
         * Why [name] cannot name a graph on [engine], whose names are at most [maxObjectName] long; null where it can. A
         * name that cannot is refused by the graph, and by a Cypher statement compiled over its tables.
         */
        fun refusal(name: String, engine: String, maxObjectName: Int): String? {
            // The longest a name adds is a table beside the nodes, `_v__` and a hash with nothing readable, and that table's trigger.
            val longest = maxObjectName - ("_v__".length + 8 + BESIDE.length)
            if (Regex("[A-Za-z][A-Za-z0-9_]{0,${longest - 1}}").matches(name)) return null
            return "'$name' cannot name a graph on $engine: it names tables, so it is letters, digits and underscores, begins with a letter, and is at most $longest long, so that what it names fits in $engine's $maxObjectName."
        }
    }
}
