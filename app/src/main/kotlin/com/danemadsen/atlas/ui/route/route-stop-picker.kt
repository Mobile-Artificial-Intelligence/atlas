package com.danemadsen.atlas.ui.route

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danemadsen.atlas.routing.GeoPoint
import com.danemadsen.atlas.search.PlaceHit
import com.danemadsen.atlas.ui.SearchUiState
import com.danemadsen.atlas.ui.savedlocations.SavedLocation

/** A focused place search, with GPS, map selection and saved places in the same picker. */
@Composable
fun RouteStopPicker(
    title: String,
    searchState: SearchUiState,
    savedLocations: List<SavedLocation>,
    onQuery: (String) -> Unit,
    onSelect: (GeoPoint, String) -> Unit,
    onCurrentLocation: () -> Unit,
    onChooseOnMap: () -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to directions") }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; onQuery(it) },
                    label = { Text(title) },
                    placeholder = { Text("Search places or addresses") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = ""; onQuery("") }) { Icon(Icons.Default.Close, "Clear search") }
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            PickerRow("Your location", "Use your current GPS location", Icons.Default.MyLocation) {
                focusManager.clearFocus(); onCurrentLocation()
            }
            PickerRow("Choose on map", "Tap the map to place a pin", Icons.Default.Map) {
                focusManager.clearFocus(); onChooseOnMap()
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f)) {
                if (query.isBlank()) {
                    item { Text("Saved places", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
                    if (savedLocations.isEmpty()) item {
                        Text("Search for a place or choose a point on the map.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    items(savedLocations, key = { it.id }) { place ->
                        PickerRow(place.name, null, Icons.Default.BookmarkBorder) {
                            focusManager.clearFocus(); onSelect(GeoPoint(place.lon, place.lat), place.name)
                        }
                    }
                } else {
                    val hits = (searchState as? SearchUiState.Results)?.hits
                    if (hits == null) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
                    else if (hits.isEmpty()) item {
                        Text("No places found in your offline maps. Try another name or choose on map.", Modifier.padding(16.dp))
                    }
                    else items(hits, key = { "${it.name}|${it.kind}|${it.lon}|${it.lat}" }) { place: PlaceHit ->
                        PickerRow(place.name, place.subclass ?: place.kind, Icons.Default.Place) {
                            focusManager.clearFocus(); onSelect(GeoPoint(place.lon, place.lat), place.name)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String?, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(20.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
