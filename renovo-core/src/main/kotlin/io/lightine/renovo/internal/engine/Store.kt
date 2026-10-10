package io.lightine.renovo.internal.engine

/**
 * Persists the engine's [Snapshot] in the app's private storage.
 *
 * The implementing task must guarantee:
 * - [save] is durable when it returns (a process killed right after still finds the snapshot),
 *   and a crash during [save] leaves either the old or the new snapshot, never a mix.
 * - [load] returns exactly what the last [save] stored, including every [io.lightine.renovo.UpdateInfo]
 *   field and the release notes in their order, or null when nothing was saved or the stored data
 *   cannot be read (unreadable data is logged and treated as no snapshot, never a crash).
 * - Nothing outside the app can read it, and no personal data is stored.
 */
internal interface Store {
    fun load(): Snapshot?

    fun save(snapshot: Snapshot)

    fun clear()
}
