package io.lightine.renovo.internal

import io.lightine.renovo.FailureReason
import io.lightine.renovo.UpdateInfo
import java.io.File

/**
 * Checks a downloaded APK before it may be installed.
 *
 * The implementing task must guarantee, in this order, stopping at the first failure:
 * 1. The SHA-256 of `file` equals `info.sha256` when that is set ([FailureReason.HashMismatch]).
 * 2. The APK's package name equals the host app's ([FailureReason.SignatureMismatch]).
 * 3. The APK's version code is higher than `installedVersionCode` ([FailureReason.NotNewer]).
 * 4. The APK's signing certificates (schemes v2, v3, and v3.1) match the installed app's, and the
 *    signature over the signed data is valid ([FailureReason.SignatureMismatch]).
 *
 * A file that cannot be read is [FailureReason.Storage]; an archive that is not a valid APK is
 * [FailureReason.HashMismatch]. It never throws (except for coroutine cancellation) and never
 * deletes the file; the engine decides that.
 */
internal interface Verifier {
    suspend fun verify(
        file: File,
        info: UpdateInfo,
        installedVersionCode: Long,
    ): VerificationResult
}

/** The outcome of [Verifier.verify]. */
internal sealed interface VerificationResult {
    data object Passed : VerificationResult

    data class Failed(
        val reason: FailureReason,
    ) : VerificationResult
}
