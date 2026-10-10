package io.lightine.renovo.internal.store

import io.lightine.renovo.internal.Clock

/** The device's wall clock. Tests replace it, so nothing else in the library reads the time directly. */
internal object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
