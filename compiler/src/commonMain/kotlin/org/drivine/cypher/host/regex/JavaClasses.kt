package org.drivine.cypher.host.regex

/**
 * The code points each class escape and character property of Java's regular expressions holds with
 * `UNICODE_CHARACTER_CLASS`, as the pinned JDK's `Pattern` answers it ([RegexTables], written by gen/Generate.java), so a
 * class is the same set on every target, whatever Unicode release its own tables are of. [property] reads a property's
 * name as `Pattern.family` does in JDK 21: `\p{name=value}`, `\p{InBlock}`, `\p{IsName}` and `\p{Name}`, each through the
 * same tables of names, in the same order.
 */
internal object JavaClasses {

    /** Each set of [RegexTables.SETS], read once it is asked for. A race reads it twice, to the same ranges. */
    private val sets = arrayOfNulls<List<IntRange>>(RegexTables.SETS.size)

    private val names: Map<String, Int> by lazy {
        RegexTables.NAMES.split(' ').associate { entry -> entry.substringBefore('=') to entry.substringAfter('=').toInt() }
    }

    /** The set `\w`, `\d`, `\s`, `\h` or `\v` holds, by its letter. */
    fun escape(letter: Char): List<IntRange> = set("escape:$letter")!!

    /**
     * The set `\p{name}` holds, as Java reads [name] with `UNICODE_CHARACTER_CLASS` and, where [caseInsensitive], with
     * `CASE_INSENSITIVE` (which widens `Lu`, `Lower` and the like to every cased letter); null where Java knows no such name,
     * which it refuses.
     */
    fun property(name: String, caseInsensitive: Boolean): List<IntRange>? {
        val equals = name.indexOf('=')
        if (equals >= 0) {
            val value = name.substring(equals + 1)
            return when (name.substring(0, equals).lowercase()) {
                "sc", "script" -> script(value)
                "blk", "block" -> block(value)
                "gc", "general_category" -> named("property", value, caseInsensitive)
                else -> null
            }
        }
        if (name.startsWith("In")) return block(name.substring(2))
        if (name.startsWith("Is")) {
            val short = name.substring(2)
            return named("unicode", short.uppercase(), caseInsensitive) ?: named("property", short, caseInsensitive) ?: script(short)
        }
        // Pattern reads POSIX's names first with UNICODE_CHARACTER_CLASS, which drivine4j always compiles with.
        return named("posix", name.uppercase(), caseInsensitive) ?: named("property", name, caseInsensitive)
    }

    private fun script(name: String): List<IntRange>? = if (' ' in name || '+' in name) null else set("script:" + name.uppercase())

    /** A block's names hold spaces, which the table writes as `+`: no name Java takes holds a `+`. */
    private fun block(name: String): List<IntRange>? = if ('+' in name) null else set("block:" + name.uppercase().replace(' ', '+'))

    private fun named(family: String, key: String, caseInsensitive: Boolean): List<IntRange>? =
        (if (caseInsensitive) set("$family-ci:$key") else null) ?: set("$family:$key")

    private fun set(name: String): List<IntRange>? {
        val index = names[name] ?: return null
        return sets[index] ?: decode(RegexTables.SETS[index]).also { sets[index] = it }
    }

    /** Ranges as gen/Generate.java writes them: each by how far it starts past the last one's end and how many more it holds, in base 36. */
    private fun decode(text: String): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        var last = -1
        return text.split(' ').map { range ->
            val start = last + 1 + range.substringBefore('-').toInt(36)
            val end = start + (if ('-' in range) range.substringAfter('-').toInt(36) else 0)
            last = end
            start..end
        }
    }
}
