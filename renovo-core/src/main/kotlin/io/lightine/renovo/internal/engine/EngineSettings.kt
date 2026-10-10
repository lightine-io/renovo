package io.lightine.renovo.internal.engine

import io.lightine.renovo.Network
import io.lightine.renovo.RenovoLogger

/**
 * The part of the configuration the engine decides with.
 *
 * Separate from [io.lightine.renovo.RenovoConfig] so engine tests need no version URL and no
 * builder validation.
 */
internal data class EngineSettings(
    val autoDownload: Boolean = true,
    val optionalDownloadNetwork: Network = Network.Any,
    val forcedDownloadNetwork: Network = Network.Any,
    val allowMobileDataOverride: Boolean = true,
    val notifications: Boolean = true,
    val logger: RenovoLogger? = null,
)
