package io.github.lightheaded.lugu.core.download

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.api.AudioFileDto
import io.github.lightheaded.lugu.core.api.AudioTrackDto
import io.github.lightheaded.lugu.core.api.EpisodeDto
import io.github.lightheaded.lugu.core.api.LibraryItemDto
import io.github.lightheaded.lugu.core.api.MediaDto
import org.junit.Test

/**
 * The shapes here are taken from a real 2.36.0 capture (docs/research/05-api-live-notes.md):
 * 1-based track indices, `startOffset` already accumulated by the server, a null
 * `index` on podcast episode files, and `exclude` flags on audio files.
 */
class ManifestBuilderTest {

    private val baseUrl = "https://books.example"

    private fun track(index: Int, startOffset: Double, duration: Double, ino: String) = AudioTrackDto(
        index = index,
        startOffset = startOffset,
        duration = duration,
        contentUrl = "/api/items/li_1/file/$ino",
        mimeType = "audio/mp4",
    )

    @Test
    fun `a multi-file book keeps the server's own offsets`() {
        val dto = LibraryItemDto(
            id = "li_1",
            media = MediaDto(
                duration = 8298.485,
                tracks = listOf(
                    track(1, 0.0, 1433.6, "100000001"),
                    track(2, 1433.6, 4064.885, "100000002"),
                    track(3, 5498.485, 2800.0, "100000003"),
                ),
            ),
        )

        val manifest = ManifestBuilder.forBook(dto, baseUrl)

        assertThat(manifest.tracks.map { it.startOffsetSec })
            .containsExactly(0.0, 1433.6, 5498.485)
            .inOrder()
        assertThat(manifest.tracks.first().url)
            .isEqualTo("https://books.example/api/items/li_1/file/100000001")
        assertThat(manifest.tracks.map { it.cacheKey })
            .containsExactly("li_1||1", "li_1||2", "li_1||3")
            .inOrder()
    }

    /**
     * The reason `media.tracks` is preferred. The server drops excluded files from
     * `tracks`; rebuilding from `audioFiles` would splice a file it refuses to play into
     * the middle of the book, and every offset after it would be wrong.
     */
    @Test
    fun `an excluded audio file never makes it into a download`() {
        val dto = LibraryItemDto(
            id = "li_1",
            media = MediaDto(
                tracks = listOf(track(1, 0.0, 100.0, "a"), track(2, 100.0, 200.0, "c")),
                audioFiles = listOf(
                    AudioFileDto(index = 1, ino = "a", duration = 100.0, mimeType = "audio/mp4"),
                    AudioFileDto(index = 2, ino = "b", duration = 50.0, mimeType = "audio/mp4", exclude = true),
                    AudioFileDto(index = 3, ino = "c", duration = 200.0, mimeType = "audio/mp4"),
                ),
            ),
        )

        val fromTracks = ManifestBuilder.forBook(dto, baseUrl)
        assertThat(fromTracks.tracks.map { it.url.substringAfterLast('/') }).containsExactly("a", "c").inOrder()

        // And the fallback path, used when a payload has no tracks, must agree.
        val noTracks = dto.copy(media = dto.media?.copy(tracks = emptyList()))
        val fromFiles = ManifestBuilder.forBook(noTracks, baseUrl)
        assertThat(fromFiles.tracks.map { it.url.substringAfterLast('/') }).containsExactly("a", "c").inOrder()
        assertThat(fromFiles.tracks.map { it.startOffsetSec }).containsExactly(0.0, 100.0).inOrder()
    }

    @Test
    fun `a podcast episode uses the content url it already carries`() {
        val dto = LibraryItemDto(
            id = "li_pod",
            media = MediaDto(
                episodes = listOf(
                    EpisodeDto(
                        id = "ep_1",
                        title = "One",
                        audioFile = AudioFileDto(ino = "200000001", duration = 2716.77, mimeType = "audio/mpeg"),
                        audioTrack = AudioTrackDto(
                            index = 1,
                            startOffset = 0.0,
                            duration = 2716.773878,
                            contentUrl = "/api/items/li_pod/file/200000001",
                            mimeType = "audio/mpeg",
                        ),
                    ),
                ),
            ),
        )

        val manifest = ManifestBuilder.forEpisode(dto, "ep_1", baseUrl)

        assertThat(manifest).isNotNull()
        assertThat(manifest!!.tracks).hasSize(1)
        assertThat(manifest.tracks.single().url)
            .isEqualTo("https://books.example/api/items/li_pod/file/200000001")
        assertThat(manifest.tracks.single().durationSec).isEqualTo(2716.773878)
        // Keyed on the episode, so two episodes of one podcast never collide.
        assertThat(manifest.tracks.single().cacheKey).isEqualTo("li_pod|ep_1|0")
    }

    /** Episode audio files come back with a null index, which must not become a URL of "null". */
    @Test
    fun `an episode with only an audio file still resolves`() {
        val dto = LibraryItemDto(
            id = "li_pod",
            media = MediaDto(
                episodes = listOf(
                    EpisodeDto(
                        id = "ep_1",
                        title = "One",
                        audioFile = AudioFileDto(ino = "999", duration = 60.0, mimeType = "audio/mpeg"),
                    ),
                ),
            ),
        )

        val manifest = ManifestBuilder.forEpisode(dto, "ep_1", baseUrl)

        assertThat(manifest?.tracks?.single()?.url)
            .isEqualTo("https://books.example/api/items/li_pod/file/999")
    }

    @Test
    fun `an unknown episode resolves to nothing rather than to an empty download`() {
        val dto = LibraryItemDto(id = "li_pod", media = MediaDto(episodes = emptyList()))

        assertThat(ManifestBuilder.forEpisode(dto, "ep_missing", baseUrl)).isNull()
    }

    @Test
    fun `cache keys survive a round trip`() {
        val key = DownloadKeys.cacheKey("li_1", "ep_2", 7)

        assertThat(DownloadKeys.parse(key)).isEqualTo(Triple("li_1", "ep_2", 7))
        assertThat(DownloadKeys.parse("not-ours")).isNull()
    }

    /**
     * A download is found by its key, not by its URL, so moving a server to a new address
     * must not orphan every book already on the phone.
     */
    @Test
    fun `cache keys do not depend on the server address`() {
        val dto = LibraryItemDto(
            id = "li_1",
            media = MediaDto(tracks = listOf(track(1, 0.0, 100.0, "a"))),
        )

        val here = ManifestBuilder.forBook(dto, "https://books.example")
        val moved = ManifestBuilder.forBook(dto, "https://new-address.example:8443")

        assertThat(moved.tracks.single().cacheKey).isEqualTo(here.tracks.single().cacheKey)
        assertThat(moved.tracks.single().url).isNotEqualTo(here.tracks.single().url)
    }
}
