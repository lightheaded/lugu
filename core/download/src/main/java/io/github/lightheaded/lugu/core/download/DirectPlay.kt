package io.github.lightheaded.lugu.core.download

/**
 * What lugu declares it can decode, and therefore what the server will hand over as a file.
 *
 * Audiobookshelf decides direct play against exactly one thing: the `supportedMimeTypes`
 * list on the play request. `checkCanDirectPlay` returns true only when *every* track's
 * mime type appears in that list, and otherwise the item is served as an HLS transcode.
 * There is no codec inspection and no negotiation — the client's own list is the whole
 * rule, which is why the list is the fix and the refusal is only the fallback.
 *
 * The list is therefore built from the server's own table (`AudioMimeType` in
 * `server/utils/fileUtils.js`), keeping every entry Media3 can actually decode:
 *
 *  - `audio/mpeg` for mp3, mpeg and mpg;
 *  - `audio/mp4` for m4b, m4a and mp4, which includes xHE-AAC — Android decodes that
 *    natively from API 29, and lugu's minimum is 26, so the few older devices fall back
 *    to a transcode while everyone else plays the original;
 *  - `audio/flac`, decoded by Media3's own FLAC extractor on every supported version;
 *  - `audio/ogg` for ogg, oga and opus alike, covering both Vorbis and Opus;
 *  - `audio/wav` and `audio/webm`;
 *  - `audio/x-matroska` for mka, which Media3's Matroska extractor reads;
 *  - `audio/amr-wb` for awb, which Media3's AMR extractor reads.
 *
 * `audio/x-aiff` is inherited from the list `:playback` has always sent, and is the one
 * entry not confirmed against Media3's published extractor set. It is kept because
 * dropping it would change what plays today on the strength of a guess; if an AIFF
 * audiobook ever turns up and neither streams nor plays after downloading, this is the
 * line to remove.
 *
 * Two of the server's types are deliberately absent, and their absence is the reason a
 * refusal exists at all. `audio/x-ms-wma` has no Media3 decoder, and `audio/x-caf` has no
 * Media3 extractor; claiming either would mean the server sends bytes this device cannot
 * turn into sound.
 *
 * The aliases at the end are not types the server emits today. They cost nothing, and a
 * reverse proxy or a future server version reporting an m4b as `audio/x-m4b` should get
 * direct play rather than a transcode.
 */
object DirectPlay {
    /**
     * Sent on every play request, and read here to predict what the server would do.
     *
     * `:playback` sends its own copy on `POST /api/items/:id/play`. That module already
     * depends on this one, so the two lists should be this one — a download refusing what
     * playback then direct-plays, or the reverse, would be a bug nobody could reproduce.
     */
    val SUPPORTED_MIME_TYPES: List<String> = listOf(
        "audio/mpeg",
        "audio/mp4",
        "audio/aac",
        "audio/flac",
        "audio/ogg",
        "audio/wav",
        "audio/webm",
        "audio/x-aiff",
        "audio/x-matroska",
        "audio/amr-wb",
        // Aliases, for a server or proxy that names the same file differently.
        "audio/mp3",
        "audio/opus",
        "audio/x-wav",
        "audio/aiff",
        "audio/x-m4a",
        "audio/x-m4b",
    )

    private val supported: Set<String> = SUPPORTED_MIME_TYPES.toSet()

    /**
     * Human-readable names for the formats that are not on the list.
     *
     * Only these two, because only these two can be refused: a name the listener will
     * recognise from their own file is worth more than the mime type alone, which is
     * printed alongside it so the claim can be checked against the server.
     */
    private val labels: Map<String, String> = mapOf(
        "audio/x-ms-wma" to "WMA",
        "audio/x-caf" to "CAF",
    )

    /** True when the server would hand this file over whole rather than transcode it. */
    fun canDirectPlay(mimeType: String?): Boolean = normalise(mimeType) in supported

    /**
     * The mime types in this manifest that the server would refuse to serve as files.
     *
     * Empty means the whole item direct-plays. Non-empty means the server transcodes the
     * item — all of it, because one unplayable track is enough to fail
     * `checkCanDirectPlay` for the lot.
     */
    fun transcodeOnlyMimeTypes(manifest: DownloadManifest): List<String> = manifest.tracks
        .map { normalise(it.mimeType) }
        .filterNot { it in supported }
        .distinct()

    /** "WMA (audio/x-ms-wma)", or just the mime type when there is no better name for it. */
    fun describe(mimeType: String): String =
        labels[mimeType]?.let { "$it ($mimeType)" } ?: mimeType

    /**
     * Mime types arrive from a scanner and go through a proxy, so neither case nor a
     * trailing `; codecs=…` parameter should decide whether a book can be downloaded.
     */
    private fun normalise(mimeType: String?): String =
        mimeType.orEmpty().substringBefore(';').trim().lowercase()
}
