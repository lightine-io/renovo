package io.lightine.renovo.internal.runtime

import io.lightine.renovo.internal.Downloader
import io.lightine.renovo.internal.ForegroundTracker
import io.lightine.renovo.internal.Installer
import io.lightine.renovo.internal.Notifier
import io.lightine.renovo.internal.PermissionGate
import io.lightine.renovo.internal.Scheduler
import io.lightine.renovo.internal.Verifier
import io.lightine.renovo.internal.engine.Store

/**
 * Every component [DefaultRuntime] drives, behind its interface, so tests can pass fakes.
 *
 * The version fetcher is not here: only the [scheduler] uses it, and it holds it itself.
 */
internal class Components(
    val store: Store,
    val foreground: ForegroundTracker,
    val notifier: Notifier,
    val scheduler: Scheduler,
    val downloader: Downloader,
    val verifier: Verifier,
    val installer: Installer,
    val permissionGate: PermissionGate,
    /** The version code of the running app, read once at start. A function so a fake needs no PackageManager. */
    val installedVersionCode: () -> Long,
)
