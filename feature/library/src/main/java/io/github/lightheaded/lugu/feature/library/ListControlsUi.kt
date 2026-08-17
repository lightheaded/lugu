package io.github.lightheaded.lugu.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.lightheaded.lugu.core.model.ListFilter

/**
 * One choice in a sort menu, as the bar renders it.
 *
 * Deliberately not typed to `ItemSort` or `EpisodeSort`: the two lists sort by different
 * things, and a bar that knows about both would need a branch per screen. It takes ids
 * and labels, and the screen turns the id back into whatever enum it uses.
 */
data class SortOption(val id: String, val label: String)

/**
 * Search, sort and filter, in the one place every long list needs them.
 *
 * Built once and shared because these are the same three questions on the library grid,
 * the episode list and the downloads screen — and because three separate implementations
 * is how three screens end up disagreeing about what "in progress" means.
 *
 * Search is a plain box rather than an icon that expands: on a list you are filtering it
 * is the control used most, and hiding the most-used control behind a tap to save a row
 * of height is a bad trade.
 */
@Composable
fun ListControlsBar(
    query: String,
    onQueryChange: (String) -> Unit,
    searchPlaceholder: String,
    sortOptions: List<SortOption>,
    selectedSortId: String,
    onSortSelected: (String) -> Unit,
    filters: List<ListFilter>,
    selectedFilter: ListFilter,
    onFilterSelected: (ListFilter) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * What a filter is called *here*.
     *
     * The set is shared and the words are not always. "In progress" on a list of books
     * means part-listened; on the downloads screen the same filter is answering a question
     * about bytes, and a chip that reads the same and means something else is the kind of
     * ambiguity nobody reports and everybody misreads.
     */
    labelFor: (ListFilter) -> String = { it.label },
) {
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text(searchPlaceholder, maxLines = 1) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
            SortMenu(
                options = sortOptions,
                selectedId = selectedSortId,
                onSelected = onSortSelected,
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filters, key = { it.id }) { filter ->
                FilterChip(
                    selected = filter == selectedFilter,
                    onClick = { onFilterSelected(filter) },
                    label = { Text(labelFor(filter), maxLines = 1, softWrap = false) },
                )
            }
        }
    }
}

/** The current ordering is on the chip, so nobody has to open the menu to find out. */
@Composable
private fun SortMenu(options: List<SortOption>, selectedId: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.id == selectedId } ?: options.firstOrNull()

    Box {
        AssistChip(
            onClick = { expanded = true },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
            label = { Text(current?.label.orEmpty(), maxLines = 1, softWrap = false) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelected(option.id)
                    },
                )
            }
        }
    }
}

/** One thing that can be done to a selection. */
data class SelectionAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)

/**
 * The bar that appears once rows are selected.
 *
 * Selection is a property of a *list*, not of one screen: downloading eight episodes,
 * clearing five queue entries and marking three books finished are the same interaction
 * wearing three hats. This is that interaction, once.
 *
 * The count leads and the close button is on the left, where the back affordance lives —
 * getting out of selection mode has to be the easiest thing on the bar, because the most
 * common way into it is by accident.
 */
@Composable
fun SelectionBar(
    selectedCount: Int,
    onClear: () -> Unit,
    actions: List<SelectionAction>,
    modifier: Modifier = Modifier,
    onSelectAll: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = "Leave selection")
            }
            Text(
                "$selectedCount selected",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            onSelectAll?.let {
                IconButton(onClick = it) {
                    Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                }
            }
            actions.forEach { action ->
                IconButton(onClick = action.onClick, enabled = action.enabled) {
                    Icon(action.icon, contentDescription = action.label)
                }
            }
        }
    }
}
