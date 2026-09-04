package com.danemadsen.atlas.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.danemadsen.atlas.search.PlaceHit
import com.danemadsen.atlas.ui.SearchUiState

/**
 * The search results drawer: top ranked hits below the route drawer, one
 * row per place. Tapping a row flies the map there; Route runs the same
 * flow as a long-press on that point.
 */
@Composable
fun SearchResultsPanel(
    searchState: SearchUiState,
    onSelectPlace: (PlaceHit) -> Unit,
    onRouteToPlace: (PlaceHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hits = (searchState as? SearchUiState.Results)?.hits ?: return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            when {
                hits.isEmpty() -> {
                    Text(
                        "No places match that search",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                else -> {
                    LazyColumn(modifier = Modifier.heightIn(max = RESULTS_MAX_HEIGHT)) {
                        items(hits, key = { "${it.name}|${it.kind}|${it.lon}|${it.lat}" }) { hit ->
                            ResultRow(
                                hit = hit,
                                onSelect = { onSelectPlace(hit) },
                                onRoute = { onRouteToPlace(hit) },
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
    onSelect: () -> Unit,
    onRoute: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(start = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(hit.name, style = MaterialTheme.typography.titleSmall)
            Text(
                hit.subclass ?: hit.kind,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRoute) { Text("Route") }
    }
}

/** Caps the list without hiding that it is scrollable. */
private val RESULTS_MAX_HEIGHT = 320.dp