package io.github.lightheaded.lugu.harness

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The one part of the harness that can be wrong quietly.
 *
 * Everything else it does fails loudly: a command that does not run, a process that does not
 * die. A dump it cannot read comes back as null instead, which is exactly what an app that is
 * not running looks like — so a format change would turn "lugu resumed the wrong book" into
 * "lugu is not running", and the tests that matter would go green having watched nothing.
 *
 * The two shapes here are both real, taken from an emulator: Android 16 writes
 * `state=PLAYING(3)` and Android 8 writes `state=3`. CI runs those two levels for the same
 * reason.
 *
 * The titles are invented, as everything in this repository is.
 */
class MediaSessionDumpTest {

    @Test
    fun `reads a playing session from the Android 16 shape`() {
        val snapshot = MediaSessionDump.parse(dump(state = "PLAYING(3)"), PACKAGE, readAt = 80_000)

        assertThat(snapshot).isNotNull()
        assertThat(snapshot!!.isPlaying).isTrue()
        assertThat(snapshot.speed).isEqualTo(1.5f)
    }

    @Test
    fun `reads a playing session from the older bare-integer shape`() {
        val snapshot = MediaSessionDump.parse(dump(state = "3"), PACKAGE, readAt = 80_000)

        assertThat(snapshot).isNotNull()
        assertThat(snapshot!!.isPlaying).isTrue()
        assertThat(snapshot.speed).isEqualTo(1.5f)
    }

    /**
     * `position` is a prefix of `buffered position`, and the buffered one is always further
     * along. Reading it instead would make every resumption look like it landed ahead.
     */
    @Test
    fun `does not mistake the buffered position for the position`() {
        val snapshot = MediaSessionDump.parse(dump(), PACKAGE, readAt = 73_178)

        assertThat(snapshot!!.reportedPositionMs).isEqualTo(612_000)
    }

    /**
     * A session publishes on events, not on a timer, so the position in a dump is as old as
     * the last thing that happened. Comparing raw stamps would let a stale reading look like
     * a resumption that jumped forward.
     */
    @Test
    fun `carries the published position forward to the moment it was read`() {
        val snapshot = MediaSessionDump.parse(dump(), PACKAGE, readAt = 83_178)

        // Ten seconds after the stamp, at 1.5x.
        assertThat(snapshot!!.positionMs).isEqualTo(612_000 + 15_000)
    }

    @Test
    fun `leaves a paused position exactly where it was published`() {
        val snapshot = MediaSessionDump.parse(
            dump(state = "PAUSED(2)", speed = "0.0"),
            PACKAGE,
            readAt = 999_999,
        )

        assertThat(snapshot!!.isPlaying).isFalse()
        assertThat(snapshot.positionMs).isEqualTo(612_000)
    }

    /**
     * lugu writes the chapter as the subtitle and it moves through the book, so the item's
     * identity has to be the title alone: a resumption that rewinds a few seconds over a
     * chapter boundary is correct behaviour and must not read as a different book.
     */
    @Test
    fun `identifies the item by its title and not by the chapter it is in`() {
        val fourth = MediaSessionDump.parse(dump(chapter = "Chapter Four"), PACKAGE, 0)
        val third = MediaSessionDump.parse(dump(chapter = "Chapter Three"), PACKAGE, 0)
        val other = MediaSessionDump.parse(dump(title = "Lighthouse Falls"), PACKAGE, 0)

        assertThat(fourth!!.identity).isEqualTo(third!!.identity)
        assertThat(fourth.identity).isNotEqualTo(other!!.identity)
    }

    /** A failure message goes into a CI log, and no log of this project names a real book. */
    @Test
    fun `never carries the title itself`() {
        val snapshot = MediaSessionDump.parse(dump(), PACKAGE, 0)

        assertThat(snapshot!!.identity).doesNotContain("Lighthouse")
        assertThat(snapshot.toString()).doesNotContain("Lighthouse")
    }

    /**
     * A service that is up with nothing in it, which is what lugu looks like from the moment
     * its UI opens until something is played. The harness waits past this state rather than
     * for it: a book that was left at its end loads, plays out whatever is left, and stops,
     * so "a session exists" and "a book is loaded" and "a book is playing" are three answers
     * and the middle one is the one worth waiting for.
     */
    @Test
    fun `tells a session holding nothing from a session holding a book`() {
        val empty = MediaSessionDump.parse(dump(metadata = "null"), PACKAGE, 0)
        val loaded = MediaSessionDump.parse(dump(), PACKAGE, 0)

        assertThat(empty).isNotNull()
        assertThat(empty!!.hasItem).isFalse()
        assertThat(loaded!!.hasItem).isTrue()
    }

    @Test
    fun `has no answer for a package that holds no session`() {
        assertThat(MediaSessionDump.parse(dump(), "io.github.lightheaded.lugu", 0)).isNull()
        assertThat(MediaSessionDump.parse("", PACKAGE, 0)).isNull()
    }

    /** Another app's session must not be read as lugu's, however it is arranged around it. */
    @Test
    fun `reads only the record belonging to the package it was asked about`() {
        val snapshot = MediaSessionDump.parse(OTHER_APP_FIRST + dump(), PACKAGE, readAt = 73_178)

        assertThat(snapshot!!.speed).isEqualTo(1.5f)
        assertThat(snapshot.reportedPositionMs).isEqualTo(612_000)
    }

    private fun dump(
        state: String = "PLAYING(3)",
        speed: String = "1.5",
        title: String = "Lighthouse Wakes",
        chapter: String = "Chapter Four",
        metadata: String = "size=9, description=$title, $chapter, $chapter",
    ) = """
        |Sessions Stack - have 1 sessions:
        |  androidx.media3.session.id. $PACKAGE/androidx.media3.session.id./6 (userId=0)
        |    ownerPid=4546, ownerUid=10213, userId=0
        |    package=$PACKAGE
        |    launchIntent=PendingIntent{b7f8873: PendingIntentRecord{cd25330 $PACKAGE startActivity}}
        |    mediaButtonReceiver=null
        |    active=true
        |    flags=7
        |    rating type=0
        |    controllers: 1
        |    state=PlaybackState {state=$state, position=612000, buffered position=640000, speed=$speed, updated=73178, actions=7339725, custom actions=[Action:mName='Back 15 seconds, mIcon=2131165246, mExtras=Bundle[mParcelledData.dataSize=140]], active item id=0, error=null}
        |    audioAttrs=AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC flags=0x800 tags= bundle=null
        |    volumeType=LOCAL, controlType=ABSOLUTE, max=0, current=0, volumeControlId=null
        |    metadata: $metadata
        |    queueTitle=null, size=0
    """.trimMargin()

    private companion object {
        const val PACKAGE = "io.github.lightheaded.lugu.debug"

        val OTHER_APP_FIRST = """
            |  HeadsetMediaButton com.android.server.telecom/HeadsetMediaButton/1 (userId=0)
            |    ownerPid=664, ownerUid=1000, userId=0
            |    package=com.android.server.telecom
            |    state=PlaybackState {state=PLAYING(3), position=1, buffered position=1, speed=2.0, updated=1, actions=0, custom actions=[], active item id=-1, error=null}
            |    metadata: size=1, description=The Breakwater, null, null
            |
        """.trimMargin()
    }
}
