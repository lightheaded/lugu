package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The event names and payload shapes are the whole feature.
 *
 * A wrong name or a missed field does not fail, throw or log — it produces a socket
 * that connects, stays connected and never acts on anything, which is indistinguishable
 * from a quiet server. These fixtures are the shapes Audiobookshelf actually emits, so a
 * rename upstream fails here rather than silently in the field.
 */
class RealtimeEventsTest {

    @Test
    fun `item_updated names the item it carries`() {
        val event = RealtimeEvents.parse(
            RealtimeNames.ITEM_UPDATED,
            """{"id":"li_abc","libraryId":"lib_1","media":{"metadata":{"title":"A Book"}}}""",
        )
        assertThat(event).isEqualTo(RealtimeEvent.ItemsChanged(listOf("li_abc")))
    }

    @Test
    fun `items_updated carries an array of items`() {
        val event = RealtimeEvents.parse(
            RealtimeNames.ITEMS_UPDATED,
            """[{"id":"li_a","libraryId":"lib_1"},{"id":"li_b","libraryId":"lib_1"}]""",
        )
        assertThat(event).isEqualTo(RealtimeEvent.ItemsChanged(listOf("li_a", "li_b")))
    }

    @Test
    fun `item_added is treated the same as an edit`() {
        val event = RealtimeEvents.parse(RealtimeNames.ITEM_ADDED, """{"id":"li_new"}""")
        assertThat(event).isEqualTo(RealtimeEvent.ItemsChanged(listOf("li_new")))
    }

    /** The server sends `{ id, libraryId }` here rather than the item, and there is no batch form. */
    @Test
    fun `item_removed names the item that went`() {
        val event = RealtimeEvents.parse(
            RealtimeNames.ITEM_REMOVED,
            """{"id":"li_gone","libraryId":"lib_1"}""",
        )
        assertThat(event).isEqualTo(RealtimeEvent.ItemsRemoved(listOf("li_gone")))
    }

    @Test
    fun `a removal sent as a bare id string is still understood`() {
        val event = RealtimeEvents.parse(RealtimeNames.ITEM_REMOVED, "li_gone")
        assertThat(event).isEqualTo(RealtimeEvent.ItemsRemoved(listOf("li_gone")))
    }

    @Test
    fun `every library event means the library list is stale`() {
        val names = listOf(
            RealtimeNames.LIBRARY_ADDED,
            RealtimeNames.LIBRARY_UPDATED,
            RealtimeNames.LIBRARY_REMOVED,
        )
        for (name in names) {
            assertThat(RealtimeEvents.parse(name, """{"id":"lib_1","name":"Books"}"""))
                .isEqualTo(RealtimeEvent.LibrariesChanged)
        }
    }

    /**
     * The real shape: the outer `id` is the progress row's id, not the item's, so
     * reading it as an item id would send every re-fetch to a non-existent item.
     */
    @Test
    fun `user_item_progress_updated reads the item from the nested data`() {
        val event = RealtimeEvents.parse(
            RealtimeNames.USER_ITEM_PROGRESS_UPDATED,
            """
            {
              "id":"mp_1",
              "sessionId":"play_1",
              "deviceDescription":"Firefox",
              "data":{
                "id":"mp_1","userId":"u_1","libraryItemId":"li_abc","episodeId":null,
                "duration":3600.0,"progress":0.5,"currentTime":1800.0,
                "isFinished":false,"lastUpdate":1700000000000,"startedAt":1699000000000
              }
            }
            """.trimIndent(),
        )
        assertThat(event).isEqualTo(RealtimeEvent.ProgressChanged("li_abc", null))
    }

    @Test
    fun `a podcast progress event keeps the episode`() {
        val event = RealtimeEvents.parse(
            RealtimeNames.USER_ITEM_PROGRESS_UPDATED,
            """{"id":"mp_2","data":{"libraryItemId":"li_pod","episodeId":"ep_9","currentTime":12.0}}""",
        )
        assertThat(event).isEqualTo(RealtimeEvent.ProgressChanged("li_pod", "ep_9"))
    }

    @Test
    fun `user_updated says only that progress may have moved`() {
        val event = RealtimeEvents.parse(
            RealtimeNames.USER_UPDATED,
            """{"id":"u_1","username":"tom","mediaProgress":[{"libraryItemId":"li_a"}]}""",
        )
        assertThat(event).isEqualTo(RealtimeEvent.UserChanged)
    }

    @Test
    fun `an unsubscribed event is ignored`() {
        assertThat(RealtimeEvents.parse("author_added", """{"id":"aut_1"}""")).isNull()
        assertThat(RealtimeEvents.parse("scan_complete", """{"id":"task_1"}""")).isNull()
    }

    /**
     * A server one version ahead will send shapes this client has never seen. That is
     * not an error, and it must not become one on a background socket thread.
     */
    @Test
    fun `an unreadable payload produces nothing rather than an exception`() {
        assertThat(RealtimeEvents.parse(RealtimeNames.ITEM_UPDATED, null)).isNull()
        assertThat(RealtimeEvents.parse(RealtimeNames.ITEM_UPDATED, "")).isNull()
        assertThat(RealtimeEvents.parse(RealtimeNames.ITEM_UPDATED, "{")).isNull()
        assertThat(RealtimeEvents.parse(RealtimeNames.ITEM_UPDATED, """{"libraryId":"lib_1"}""")).isNull()
        assertThat(RealtimeEvents.parse(RealtimeNames.USER_ITEM_PROGRESS_UPDATED, """{"id":"mp"}"""))
            .isNull()
    }

    @Test
    fun `a null id is not mistaken for an item`() {
        assertThat(RealtimeEvents.parse(RealtimeNames.ITEM_UPDATED, """{"id":null}""")).isNull()
        assertThat(RealtimeEvents.parse(RealtimeNames.ITEM_UPDATED, """{"id":""}""")).isNull()
    }

    @Test
    fun `a repeated id in a batch is fetched once`() {
        val event = RealtimeEvents.parse(
            RealtimeNames.ITEMS_ADDED,
            """[{"id":"li_a"},{"id":"li_a"},{"id":"li_b"}]""",
        )
        assertThat(event).isEqualTo(RealtimeEvent.ItemsChanged(listOf("li_a", "li_b")))
    }
}
