package io.github.lightheaded.lugu.core.download

/** What to do about the downloads that are running when the cap is checked. */
internal enum class CapAction {
    CARRY_ON,

    /** Stop everything in flight, keep what it has already fetched, and say so. */
    STOP_IN_FLIGHT,
}

/**
 * The storage cap, enforced while bytes are moving rather than only before they start.
 *
 * ## The gap this closes
 *
 * `DownloadRepository.download` charges an *estimate* against the cap before enqueuing
 * anything, and the estimate is the server's own reported size or, failing that, a duration
 * at an assumed bitrate. Both can be wrong, and one of them is routinely wrong: a podcast
 * feed reports sizes it does not have, and a book whose files were re-encoded on the server
 * without the index being rebuilt reports the old ones. When the estimate is low, the
 * download overshoots the cap and nothing notices, because until now the only check ran
 * before the first byte.
 *
 * The check here reads the bytes actually on disk, so it cannot be wrong in that way at all.
 *
 * ## When it fires
 *
 * On the progress sweep that `DownloadEngine` already runs — every second while a file is
 * downloading, and on every state change. No new timer: the cap is only ever exceeded by
 * bytes arriving, and that sweep runs exactly when bytes are arriving and stops when they
 * stop.
 *
 * Once the bytes on disk *reach* the cap, not once they pass it by some margin. A margin
 * would make the in-flight rule stricter than the pre-flight one, so a download accepted a
 * second ago would be stopped for a reason the arithmetic on the screen contradicts. The
 * price is an overshoot bounded by one sweep, which on a fast connection is a few megabytes
 * against a cap measured in gigabytes.
 *
 * ## What happens to the download, and what the listener sees
 *
 * It is stopped, and what has already been fetched is kept. Deleting it would free the space
 * immediately, and that is the tempting version — but the bytes are a book somebody asked
 * for, and throwing them away to tidy up is the same class of decision as evicting a
 * download to make room for a stream, which `DownloadCache` refuses on principle. A stopped
 * download resumes for the price of raising the cap; a deleted one has to be fetched again
 * over the connection that was just spent on it.
 *
 * The row goes to `failed` and carries [stoppedMessage], which the Downloads screen already
 * renders under the title. The message states its arithmetic and names the fix, to the same
 * standard as `DownloadRefusedException` — a stop with no numbers in it reads as the app
 * having broken rather than as a cap having been reached.
 */
internal object StorageCap {

    /**
     * @param bytesOnDisk what the download cache actually holds.
     * @param capBytes the listener's cap. Zero or less means no cap, which is how the
     *   settings screen represents "unlimited".
     * @param anythingInFlight whether any row is still queued or downloading. A phone that
     *   is merely full has nothing to stop, and stopping nothing every second would fill the
     *   record with an event that never ends.
     */
    fun actionFor(bytesOnDisk: Long, capBytes: Long, anythingInFlight: Boolean): CapAction = when {
        !anythingInFlight -> CapAction.CARRY_ON
        capBytes <= 0 -> CapAction.CARRY_ON
        bytesOnDisk < capBytes -> CapAction.CARRY_ON
        else -> CapAction.STOP_IN_FLIGHT
    }

    /**
     * What the listener is told, verbatim.
     *
     * It says that nothing was lost, because the fear a stopped download raises is that the
     * gigabyte already fetched has been thrown away — and the answer to "start it again" is
     * different depending on whether it resumes or restarts.
     */
    fun stoppedMessage(bytesOnDisk: Long, capBytes: Long): String =
        "Stopped: downloads have reached the ${formatBytes(capBytes)} cap, with " +
            "${formatBytes(bytesOnDisk)} used. What has already been fetched is kept. Raise " +
            "the cap in Settings, or remove a download, then start this again."
}
