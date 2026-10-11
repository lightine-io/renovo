package io.lightine.renovo.internal

/**
 * Holds the one runtime of the process and makes a second init harmless.
 *
 * Kept apart from the [io.lightine.renovo.Renovo] object, so the init rules can be tested without
 * an Android context and without the process-wide state of that object.
 */
internal class RuntimeHolder {
    private val lock = Any()

    @Volatile
    private var current: RenovoRuntime? = null

    /** The runtime, or [UninitializedRuntime] before [initialize] succeeded. */
    val runtime: RenovoRuntime
        get() = current ?: UninitializedRuntime

    /** The runtime, or null before [initialize] succeeded. For code the system starts without init. */
    val runtimeOrNull: RenovoRuntime?
        get() = current

    /**
     * Creates the runtime with [create] on the first call. Later calls do not call [create] and only
     * log a warning. Host apps sometimes call init from more than one place, for example an
     * Application and a test rule, and a crash there would be worse than the second call.
     *
     * Returns true when this call created the runtime. When [create] throws, nothing is stored, so a
     * corrected call can succeed.
     */
    fun initialize(create: () -> RenovoRuntime): Boolean = synchronized(lock) {
        val existing = current
        if (existing != null) {
            existing.logger?.log("Renovo.init was called again. The second call is ignored.", null)
            false
        } else {
            current = create()
            true
        }
    }
}
