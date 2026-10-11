package io.lightine.renovo.internal.work

import io.lightine.renovo.Renovo
import io.lightine.renovo.internal.Clock
import io.lightine.renovo.internal.RenovoRuntime
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Unique work name of the periodic check. */
internal const val CHECK_WORK_NAME = "renovo-check"

/** Unique work name of the download. */
internal const val DOWNLOAD_WORK_NAME = "renovo-download"

/** Opens a connection to [URL]. Tests replace it to serve a download without a network. */
internal fun interface ConnectionFactory {
    fun open(url: URL): HttpURLConnection

    companion object {
        val Default: ConnectionFactory =
            ConnectionFactory { url ->
                url.openConnection() as? HttpURLConnection ?: throw IOException("Not an HTTP connection: ${url.protocol}.")
            }
    }
}

/**
 * What the two workers need and cannot get through a constructor, because the system creates them.
 *
 * The host app calls `Renovo.init` in `Application.onCreate`, so the fields are normally filled
 * before WorkManager starts a worker. A worker that still finds an empty field asks WorkManager to
 * try again later. The fields are `@Volatile` because workers read them on WorkManager's threads.
 */
internal object WorkEnvironment {
    /** Where a worker finds the runtime. Tests replace it, because `Renovo` can be initialized only once per process. */
    @Volatile
    var runtimeProvider: () -> RenovoRuntime? = { Renovo.runtimeOrNull }

    @Volatile
    var connectionFactory: ConnectionFactory = ConnectionFactory.Default

    /** Used to throttle progress events. Wall clock, like the rest of the library. */
    @Volatile
    var clock: Clock = Clock { System.currentTimeMillis() }

    /** Replaced in tests; null means the implementation for the device's API level. */
    @Volatile
    var freeSpace: FreeSpace? = null

    /** Set by [WorkScheduler] when the runtime builds it. The periodic [CheckWorker] runs checks through it. */
    @Volatile
    var checkRunner: CheckRunner? = null

    /** Puts every field back to its default. For tests. */
    fun reset() {
        runtimeProvider = { Renovo.runtimeOrNull }
        connectionFactory = ConnectionFactory.Default
        clock = Clock { System.currentTimeMillis() }
        freeSpace = null
        checkRunner = null
    }
}
