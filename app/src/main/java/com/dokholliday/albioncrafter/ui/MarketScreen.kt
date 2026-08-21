package com.dokholliday.albioncrafter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dokholliday.albioncrafter.AppStatus
import com.dokholliday.albioncrafter.PythonBridge
import org.json.JSONObject

private val OUTER_CITIES = listOf(
    "Bridgewatch", "Fort Sterling", "Lymhurst", "Martlock", "Thetford",
)

@Composable
fun MarketScreen(modifier: Modifier, appStatus: AppStatus) {
    var coverage by remember { mutableStateOf<JSONObject?>(null) }
    var syncCities by remember { mutableStateOf(OUTER_CITIES.toSet()) }
    var syncing by remember { mutableStateOf(false) }
    var syncOpId by remember { mutableStateOf<String?>(null) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var syncFraction by remember { mutableStateOf<Float?>(null) }
    var updatingData by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadCoverage() {
        PythonBridge.callAsync("market_coverage") { result ->
            result.onSuccess { coverage = it }
        }
    }

    LaunchedEffect(Unit) { loadCoverage() }

    val sink = remember {
        PythonBridge.EventSink { event ->
            val message = event.optString("message", "")
            if (message.isNotBlank()) syncMessage = message
            val planned = event.optInt("planned_batches", 0)
            val completed = event.optInt("completed_batches", 0)
            syncFraction = if (planned > 0) completed.toFloat() / planned else null
        }
    }

    val updateSink = remember {
        PythonBridge.EventSink { event ->
            updateMessage = event.optString("message", "")
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Market Data", style = MaterialTheme.typography.headlineSmall)

        appStatus.statusJson?.optJSONObject("catalog")?.let { catalog ->
            if (catalog.optInt("item_count", 0) == 0) {
                SectionCard("First run — static game data") {
                    Text(
                        "Import the game catalog (items + recipes) from the ao-bin-dumps source. " +
                            "WiFi recommended (~80MB download).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    Button(
                        onClick = {
                            updatingData = true
                            updateMessage = "Starting…"
                            PythonBridge.callAsync(
                                "update_static_data",
                                PythonBridge.newOpId(),
                                false,
                                updateSink,
                            ) { res ->
                                updatingData = false
                                res.onSuccess {
                                    updateMessage = "Imported ${it.optInt("item_count")} items"
                                    appStatus.refresh()
                                    loadCoverage()
                                }.onFailure { updateMessage = "Error: ${it.message}" }
                            }
                        },
                        enabled = !updatingData,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("UPDATE STATIC GAME DATA")
                    }
                }
            } else {
                Text(
                    "Catalog: ${catalog.optInt("item_count")} items · ${catalog.optInt("recipe_count")} recipes",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                OutlinedButton(onClick = {
                    updatingData = true
                    PythonBridge.callAsync(
                        "update_static_data",
                        PythonBridge.newOpId(),
                        false,
                        updateSink,
                    ) { res ->
                        updatingData = false
                        res.onSuccess {
                            updateMessage = "Imported ${it.optInt("item_count")} items"
                            appStatus.refresh()
                        }.onFailure { updateMessage = "Error: ${it.message}" }
                    }
                }, enabled = !updatingData) {
                    Text("Update static game data")
                }
            }
        }
        if (updatingData) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            updateMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        coverage?.let { c ->
            SectionCard("Coverage") {
                StatRow("Expected rows", c.optInt("expected_rows").toString())
                StatRow("Cached rows", c.optInt("market_rows").toString())
                StatRow("Observed ≤2h", c.optInt("observed_within_2h").toString(),
                    valueColor = GreenFresh)
                StatRow("Observed ≤4h", c.optInt("observed_within_4h").toString())
                StatRow("Observed ≤24h", c.optInt("observed_within_24h").toString())
                StatRow("Older than 24h", c.optInt("observed_older_than_24h").toString(),
                    valueColor = AmberAging)
                StatRow("No usable price", c.optInt("no_usable_price").toString(), valueColor = RedStale)
                c.optString("newest_observation_at").takeIf { it.isNotBlank() }?.let {
                    StatRow("Newest observation", it.take(16).replace('T', ' '))
                }
                val perCity = c.optJSONArray("per_city")
                if (perCity != null) {
                    Text("", style = MaterialTheme.typography.bodySmall)
                    (0 until perCity.length()).forEach { i ->
                        val entry = perCity.optJSONObject(i) ?: return@forEach
                        StatRow(
                            entry.optString("city"),
                            "≤4h: ${entry.optInt("observed_within_4h")} · ≤24h: ${entry.optInt("observed_within_24h")}",
                        )
                    }
                }
            }
        }

        SectionCard("Royal Market Sync") {
            Text(
                "Downloads current orders, then daily SELL history for missing keys. " +
                    "Sequential and cancellable; run on WiFi.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OUTER_CITIES.forEach { city ->
                    FilterChip(
                        selected = city in syncCities,
                        onClick = {
                            syncCities =
                                if (city in syncCities) syncCities - city else syncCities + city
                        },
                        label = { Text(city.take(4)) },
                    )
                }
            }
            Button(
                onClick = {
                    val opId = PythonBridge.newOpId()
                    syncOpId = opId
                    syncing = true
                    syncMessage = "Starting…"
                    error = null
                    val region = appStatus.statusJson?.optString("region", "americas") ?: "americas"
                    val citiesJson = org.json.JSONArray(syncCities.toList()).toString()
                    PythonBridge.callAsync("market_sync", opId, region, citiesJson, sink) { res ->
                        syncing = false
                        syncOpId = null
                        res.onSuccess {
                            syncMessage = "Sync finished: ${it.optInt("item_count")} items"
                            loadCoverage()
                        }.onFailure { error = it.message ?: it.toString() }
                    }
                },
                enabled = !syncing && syncCities.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("REFRESH ROYAL MARKETS")
            }
            if (syncing) {
                syncFraction?.let {
                    LinearProgressIndicator(
                        progress = { it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                syncMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
                OutlinedButton(onClick = { syncOpId?.let(PythonBridge::cancel) }) {
                    Text("Cancel")
                }
            }
        }

        error?.let { Banner("Error: $it", RedStale) }
    }
}
