package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.ListFilter
import org.junit.Test

/** An empty Downloads list names what hides the rows, so it is clear what to undo. */
class DownloadNoMatchTest {

    @Test
    fun `a search names its words`() {
        assertThat(downloadsNoMatchLine(" salt ", ListFilter.ALL)).isEqualTo("No downloads match “salt”.")
    }

    @Test
    fun `a filter names itself in this screen's words`() {
        assertThat(downloadsNoMatchLine("", ListFilter.IN_PROGRESS)).isEqualTo("No downloads in “Downloading”.")
    }

    @Test
    fun `both together name both`() {
        assertThat(downloadsNoMatchLine("salt", ListFilter.IN_PROGRESS))
            .isEqualTo("No downloads match “salt” in “Downloading”.")
    }
}
