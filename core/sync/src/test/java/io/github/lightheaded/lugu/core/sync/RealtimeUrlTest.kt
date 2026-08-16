package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Audiobookshelf is very often reverse-proxied onto a subpath, and Socket.IO wants that
 * subpath as its `path` option rather than as part of the address. Getting it wrong
 * looks exactly like a server that is down, so it is worth pinning.
 */
class RealtimeUrlTest {

    @Test
    fun `a plain host connects to the default endpoint`() {
        assertThat(RealtimeUrl.originOf("https://books.example")).isEqualTo("https://books.example")
        assertThat(RealtimeUrl.pathOf("https://books.example")).isEqualTo("/socket.io")
    }

    @Test
    fun `a port is part of the origin`() {
        assertThat(RealtimeUrl.originOf("http://192.168.1.10:13378")).isEqualTo("http://192.168.1.10:13378")
        assertThat(RealtimeUrl.pathOf("http://192.168.1.10:13378")).isEqualTo("/socket.io")
    }

    @Test
    fun `a subpath moves to the path option and out of the origin`() {
        assertThat(RealtimeUrl.originOf("https://home.example/audiobookshelf"))
            .isEqualTo("https://home.example")
        assertThat(RealtimeUrl.pathOf("https://home.example/audiobookshelf"))
            .isEqualTo("/audiobookshelf/socket.io")
    }

    @Test
    fun `a nested subpath is kept whole`() {
        assertThat(RealtimeUrl.pathOf("https://home.example/media/abs"))
            .isEqualTo("/media/abs/socket.io")
    }

    /** ServerUrl.normalise already strips these, but a stray slash must not double up. */
    @Test
    fun `a trailing slash changes nothing`() {
        assertThat(RealtimeUrl.originOf("https://books.example/")).isEqualTo("https://books.example")
        assertThat(RealtimeUrl.pathOf("https://books.example/")).isEqualTo("/socket.io")
        assertThat(RealtimeUrl.pathOf("https://home.example/abs/")).isEqualTo("/abs/socket.io")
    }
}
