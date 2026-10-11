package io.lightine.renovo.internal.engine

/**
 * Persists the engine's [Snapshot] in the app's private storage.
 *
 * An implementation guarantees:
 * - When [save] returns, the snapshot is on disk: a process killed right after still finds it.
 *   A crash during [save] leaves either the old or the new snapshot, never a mix of both.
 * - [load] returns exactly what the last [save] stored, including every [io.lightine.renovo.UpdateInfo]
 *   field and the release notes in their order. It returns null when nothing was saved or the
 *   stored data cannot be read. Unreadable data is logged and treated as no snapshot, never a crash.
 * - Nothing outside the app can read it, and no personal data is stored.
 */
internal interface Store {
    fun load(): Snapshot?

    fun save(snapshot: Snapshot)

    fun clear()
}
