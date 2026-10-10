package io.lightine.renovo.internal.engine

/**
 * Where components report what happened. The runtime implements it and passes every event to
 * [UpdateEngine.handle] in arrival order, from a single consumer.
 *
 * [send] must return quickly and may be called from any thread.
 */
internal fun interface EventSink {
    fun send(event: EngineEvent)
}
