package io.lightine.renovo

/**
 * Receives every change of the update state, for callers that do not use coroutines.
 *
 * It mirrors [Renovo.state]. It is called on the main thread, once with the current state when
 * it is added and then after every change.
 *
 * ```
 * val listener = UpdateListener { state -> render(state) }
 * Renovo.addListener(listener)
 * ```
 */
public fun interface UpdateListener {
    /**
     * Called with the new update state.
     *
     * ```
     * Renovo.addListener { state -> if (state is UpdateState.ReadyToInstall) showBanner() }
     * ```
     */
    public fun onStateChanged(state: UpdateState)
}
