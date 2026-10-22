package io.github.lightheaded.lugu

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.feature.player.PlayerLink
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * That each place the player links to becomes the route of that page (issue #2).
 *
 * Robolectric because a browse route carries its name as base64 through `android.util`.
 * A plain Application, as in [NewEpisodeTapTest], so that no Hilt graph starts.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PlayerLinkRoutesTest {

    @Test
    fun `an episode link opens the episode page`() {
        assertThat(Routes.of(PlayerLink.Episode("li_show", "ep_7")))
            .isEqualTo(Routes.episode("li_show", "ep_7"))
    }

    @Test
    fun `an item link opens the item page`() {
        assertThat(Routes.of(PlayerLink.Item("li_book"))).isEqualTo(Routes.item("li_book"))
    }

    @Test
    fun `a group link opens the author page`() {
        assertThat(Routes.of(PlayerLink.Group("authors", "Ada Example")))
            .isEqualTo(Routes.browseGroup("authors", "Ada Example"))
    }
}
