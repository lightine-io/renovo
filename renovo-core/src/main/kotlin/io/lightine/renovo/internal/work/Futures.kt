package io.lightine.renovo.internal.work

import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Suspends until the future completes.
 *
 * Written here because the ready-made one lives in a library that is outside the dependencies
 * renovo-core may add. The listener runs on the thread that completes the future; resuming a
 * coroutine there is cheap, so no executor hop is needed.
 */
internal suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addListener(
        {
            try {
                continuation.resume(get())
            } catch (e: ExecutionException) {
                continuation.resumeWithException(e.cause ?: e)
            } catch (e: java.util.concurrent.CancellationException) {
                continuation.cancel(e)
            }
        },
        Runnable::run,
    )
    continuation.invokeOnCancellation { cancel(false) }
}
