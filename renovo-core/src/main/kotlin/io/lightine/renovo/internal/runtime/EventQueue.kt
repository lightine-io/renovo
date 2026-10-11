package io.lightine.renovo.internal.runtime

import io.lightine.renovo.internal.engine.EngineEvent
import io.lightine.renovo.internal.engine.EventSink
import kotlinx.coroutines.channels.Channel

/**
 * The one line every event waits in. Components send into it from any thread; the runtime's loop is
 * the only reader, which is what keeps the engine's events in arrival order.
 *
 * A separate class from [DefaultRuntime] because the components need the sink when they are built,
 * and the runtime needs the components when it is built.
 */
internal class EventQueue : EventSink {
    val channel = Channel<EngineEvent>(Channel.UNLIMITED)

    override fun send(event: EngineEvent) {
        // An unlimited channel that is never closed accepts every element, so the result is always success.
        channel.trySend(event)
    }
}
