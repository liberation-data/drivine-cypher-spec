package org.drivine.cypher.store.schema

import org.drivine.DrivineInternalApi

/**
 * What a SQL store's catalog and dictionary say of an item a spec declares, beyond whether it is there
 * ([StoreSchema.matchesIdentity]) and in its shape ([StoreSchema.matchesShape]): whether what stands under the names Drivine
 * gives its objects is the item, and in which form it was made ([StoreSchema.formOf]).
 *
 * A form is how one version, or one library, writes an item's DDL: the same item, with the same label, properties, kind and
 * uniqueness, written with other expressions. Each record the module writes says its form
 * ([org.drivine.cypher.store.ddl.SqlStoreDdl.CURRENT_FORM]). A store made by an earlier version, or by Drivine for TypeScript
 * or drivine-python before they took the module's DDL, can hold an item of another form, which is kept and used as it is.
 *
 * Another item under the name Drivine gives (an index that isn't unique where a uniqueness constraint's should be, one over
 * other properties, or an object that isn't an index) is no form of it: it is a [Mismatch], which a schema manager takes as
 * drift, and never as made.
 */
@DrivineInternalApi
sealed interface ItemForm {

    /** The item is in the form this version makes it in, or in one this module can't tell from it. */
    data object Current : ItemForm

    /**
     * The item is in another form than [expected], the one this version makes: [found] says which, `form 0` where its record
     * says one, or the library whose form its expressions are. It is kept and used as it is, and a recreate makes it again.
     */
    data class Stale(val found: String, val expected: Int) : ItemForm

    /**
     * The item's record says form [found], a later one than [current], the one this version makes: a newer version made it,
     * as in a rolling deploy. It is kept and used as it is, and never made again here, which would make it in an older form.
     */
    data class Newer(val found: Int, val current: Int) : ItemForm

    /**
     * The item is in this version's form and its record says none: drivine4j's on Oracle, made before records said their
     * form. [statement] records it as made in its form, and touches nothing else; it is safe to run again.
     */
    data class Unrecorded(val statement: String) : ItemForm

    /**
     * What stands under [name], a name Drivine gives one of the item's objects, isn't the item: [reason] says what it is. It
     * is [droppable] where it is an index, which a recreate drops by its name before it makes the item; an object that isn't
     * an index is never dropped, and the item can't be made while it is there.
     */
    data class Mismatch(val name: String, val reason: String, val droppable: Boolean) : ItemForm
}
