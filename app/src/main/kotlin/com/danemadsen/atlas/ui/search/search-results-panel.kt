package com.danemadsen.atlas.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.danemadsen.atlas.search.PlaceHit
import com.danemadsen.atlas.ui.SearchUiState
import com.danemadsen.atlas.ui.savedlocations.SavedLocation

/**
 * The search results popover: anchored directly under the search bar, one
 * row per place. Tapping a row opens the location menu — the same route
 * menu a long-press gets — for that place; the star on the far right
 * toggles the place's saved state.
 */
@Composable
fun SearchResultsPanel(
    searchState: SearchUiState,
    savedLocations: List<SavedLocation>,
    onOpenRouteMenu: (PlaceHit) -> Unit,
    onToggleSave: (PlaceHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hits = (searchState as? SearchUiState.Results)?.hits ?: return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            when {
                hits.isEmpty() -> {
                    Text(
                        "No places match that search",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                else -> {
                    LazyColumn(modifier = Modifier.heightIn(max = RESULTS_MAX_HEIGHT)) {
                        items(hits, key = { "${it.name}|${it.kind}|${it.lon}|${it.lat}" }) { hit ->
                            ResultRow(
                                hit = hit,
                                saved = savedLocations.any { it.lon == hit.lon && it.lat == hit.lat },
                                onOpen = { onOpenRouteMenu(hit) },
                                onToggleSave = { onToggleSave(hit) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    hit: PlaceHit,
    saved: Boolean,
    onOpen: () -> Unit,
    onToggleSave: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(hit.name, style = MaterialTheme.typography.titleSmall)
            Text(
                hit.subclass ?: hit.kind,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onToggleSave) {
            Icon(
                imageVector = if (saved) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (saved) "Unsave this place" else "Save this place",
                tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Caps the list without hiding that it is scrollable. */
private val RESULTS_MAX_HEIGHT = 320.dp
