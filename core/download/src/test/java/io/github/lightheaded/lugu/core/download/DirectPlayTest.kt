package io.github.lightheaded.lugu.core.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The formats here are the ones Audiobookshelf's own mime table can emit
 * (`server/utils/fileUtils.js`), which is the only list the server compares
 * `supportedMimeTypes` against when it picks direct play.
 */
class DirectPlayTest {

    private fun manifest(vararg mimeTypes: String) = DownloadManifest(
        mimeTypes.mapIndexed { index, mime ->
            DownloadTrack(
                index = index,
                startOffsetSec = index * 100.0,
                durationSec = 100.0,
                url = "https://books.example/api/items/li_1/file/$index",
                mimeType = mime,
                cacheKey = DownloadKeys.cacheKey("li_1", "", index),
            )
        },
    )

    @Test
    fun `every format the server can serve is either claimed or knowingly refused`() {
        val serverEmits = listOf(
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
            "audio/x-ms-wma",
            "audio/x-caf",
        )

        val refused = serverEmits.filterNot { DirectPlay.canDirectPlay(it) }

        // Only the two Media3 genuinely cannot decode. Anything else appearing here is
        // an item being transcoded for no reason, which is the expensive kind of wrong.
        assertThat(refused).containsExactly("audio/x-ms-wma", "audio/x-caf")
    }

    /**
     * The list used to omit Matroska and AMR-WB, both of which Media3 has extractors for.
     * An mka book was transcoded end to end — losing byte-accurate seeking and the ability
     * to download it — for want of one line.
     */
    @Test
    fun `matroska and amr-wb are claimed rather than given away`() {
        assertThat(DirectPlay.canDirectPlay("audio/x-matroska")).isTrue()
        assertThat(DirectPlay.canDirectPlay("audio/amr-wb")).isTrue()
    }

    /** xHE-AAC arrives inside an MP4 container, so claiming `audio/mp4` is what carries it. */
    @Test
    fun `an mp4 container is claimed whatever codec is inside it`() {
        assertThat(DirectPlay.canDirectPlay("audio/mp4")).isTrue()
        assertThat(DirectPlay.canDirectPlay("audio/mp4; codecs=\"mp4a.40.42\"")).isTrue()
    }

    @Test
    fun `case and parameters do not decide whether a book can be kept`() {
        assertThat(DirectPlay.canDirectPlay("AUDIO/FLAC")).isTrue()
        assertThat(DirectPlay.canDirectPlay(" audio/flac ")).isTrue()
        assertThat(DirectPlay.canDirectPlay(null)).isFalse()
        assertThat(DirectPlay.canDirectPlay("")).isFalse()
    }

    @Test
    fun `a book of playable files reports nothing to refuse`() {
        assertThat(DirectPlay.transcodeOnlyMimeTypes(manifest("audio/mp4", "audio/mpeg"))).isEmpty()
    }

    /**
     * The server's `checkCanDirectPlay` fails the whole item if any one track fails, so
     * one stray file means the entire book is transcoded — and the refusal must say so
     * rather than pretend the rest is downloadable.
     */
    @Test
    fun `one undecodable file makes the whole item a transcode`() {
        val mixed = manifest("audio/mpeg", "audio/x-ms-wma", "audio/mpeg")

        assertThat(DirectPlay.transcodeOnlyMimeTypes(mixed)).containsExactly("audio/x-ms-wma")
    }

    @Test
    fun `each offending format is named once however many files carry it`() {
        val mixed = manifest("audio/x-ms-wma", "audio/x-ms-wma", "audio/x-caf")

        assertThat(DirectPlay.transcodeOnlyMimeTypes(mixed))
            .containsExactly("audio/x-ms-wma", "audio/x-caf")
            .inOrder()
    }

    @Test
    fun `a refused format is named the way the listener would name it`() {
        assertThat(DirectPlay.describe("audio/x-ms-wma")).isEqualTo("WMA (audio/x-ms-wma)")
        assertThat(DirectPlay.describe("audio/x-caf")).isEqualTo("CAF (audio/x-caf)")
        assertThat(DirectPlay.describe("audio/something-new")).isEqualTo("audio/something-new")
    }
}
