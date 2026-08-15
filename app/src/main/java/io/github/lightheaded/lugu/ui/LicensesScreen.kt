package io.github.lightheaded.lugu.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import io.github.lightheaded.lugu.R

/**
 * Every library lugu ships with, and its license. The list is generated at build time
 * by the AboutLibraries plugin from the real dependency graph, so it can never go
 * stale the way a hand-maintained notices file would.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val libraries by produceLibraries(R.raw.aboutlibraries)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Open source licenses") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}
