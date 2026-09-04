package com.danemadsen.atlas.ui.search

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.danemadsen.atlas.ui.SearchUiState

/**
 * The top search bar: rounded pill over the map. The right-hand button is
 * a clear (X) while the field holds text, the mic while it is empty.
 * Settings is NOT here — it lives on the bottom navigation's Settings tab.
 *
 * Voice input goes through the system speech recognizer activity
 * (ACTION_RECOGNIZE_SPEECH, offline preferred) — the recognizer runs in its
 * own process and holds the mic itself, so Atlas needs no RECORD_AUDIO.
 * Devices without any recognizer get a toast, and the text field stays
 * primary either way.
 */
@Composable
fun SearchBar(
    query: String,
    searchState: SearchUiState,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // The recognizer's first result becomes the query, verbatim — the
    // debounced FTS search in the view model handles the rest.
    val voice_launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val results = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            .orEmpty()
        results.firstOrNull()?.let(onQueryChange)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search places") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            // With text in the field the button clears it (the common
            // next action while typing); the empty field keeps the mic.
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            )
                            // Ask, don't require: a device with only a network
                            // recognizer should still offer what it has rather
                            // than nothing — Atlas itself never talks to it.
                            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        }
                        try {
                            voice_launcher.launch(intent)
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(
                                context,
                                "No speech recognizer is installed on this device",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = "Search by voice",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    // The index build can outlive the import (tens of seconds on a metro
    // archive): say so in the bar's supporting line instead of silently
    // returning zero hits.
    if (searchState is SearchUiState.Indexing) {
        Surface(
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                "Indexing places for search — one-time per archive",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}