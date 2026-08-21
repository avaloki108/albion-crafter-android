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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dokholliday.albioncrafter.PythonBridge
import org.json.JSONArray
import org.json.JSONObject

private val CITIES = listOf(
    "Bridgewatch", "Fort Sterling", "Lymhurst", "Martlock", "Thetford", "Caerleon",
)

@Composable
fun ScannerScreen(modifier: Modifier) {
    var text by remember { mutableStateOf("") }
    var craftCities by remember { mutableStateOf(setOf("Bridgewatch")) }
    var sellCities by remember { mutableStateOf(setOf("Bridgewatch")) }
    var tierMin by remember { mutableStateOf("4") }
    var tierMax by remember { mutableStateOf("8") }
    var premium by remember { mutableStateOf(true) }
    var scanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    var scanFraction by remember { mutableStateOf<Float?>(null) }
    var opportunities by remember { mutableStateOf<JSONArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val sink = remember {
        PythonBridge.EventSink { event ->
            scanMessage = event.optString("message", "")
            val completed = event.optInt("completed", 0)
            val total = event.optInt("total", 0)
            scanFraction = if (total > 0) completed.toFloat() / total else null
        }
    }

    fun runScan() {
        scanning = true
        error = null
        opportunities = null
        val constraints = JSONObject()
            .put("text", text)
            .put("craft_cities", JSONArray(craftCities.toList()))
            .put("sell_cities", JSONArray(sellCities.toList()))
            .put("tier_min", tierMin.toIntOrNull() ?: 4)
            .put("tier_max", tierMax.toIntOrNull() ?: 8)
            .put("premium", premium)
            .put("actionable_only", false)
        PythonBridge.callAsync("scanner_run", PythonBridge.newOpId(), constraints.toString(), sink) { res ->
            scanning = false
            res.onSuccess { payload ->
                opportunities = payload.optJSONArray("opportunities")
                scanMessage = "${payload.optInt("count")} opportunities"
            }.onFailure { error = it.message ?: it.toString() }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
            Text("Craft Scanner", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Cache-only scan of supported recipes. Refresh markets first for fresh prices.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Item text filter") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Text("Craft cities", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CITIES.take(5).forEach { city ->
                    FilterChip(
                        selected = city in craftCities,
                        onClick = {
                            craftCities = if (city in craftCities) craftCities - city else craftCities + city
                        },
                        label = { Text(city.take(4)) },
                    )
                }
            }
            Text("Sell cities", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CITIES.take(5).forEach { city ->
                    FilterChip(
                        selected = city in sellCities,
                        onClick = {
                            sellCities = if (city in sellCities) sellCities - city else sellCities + city
                        },
                        label = { Text(city.take(4)) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = tierMin,
                    onValueChange = { tierMin = it.filter { c -> c.isDigit() } },
                    label = { Text("Tier min") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = tierMax,
                    onValueChange = { tierMax = it.filter { c -> c.isDigit() } },
                    label = { Text("Tier max") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }

            Button(onClick = { runScan() }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                Text("SCAN")
            }
            if (scanning) {
                scanFraction?.let {
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                scanMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            error?.let { Banner("Error: $it", RedStale) }
            scanMessage?.takeIf { !scanning }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = GreenFresh)
            }
        }

        opportunities?.let { arr ->
            if (arr.length() == 0) {
                Text("No opportunities", color = TextMuted)
            } else {
                Text(
                    "Top ${minOf(arr.length(), 100)} of ${arr.length()} · sorted by profit",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted,
                )
                (0 until minOf(arr.length(), 100)).forEach { i ->
                    val opportunity = arr.optJSONObject(i) ?: return@forEach
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(opportunity.optString("name"), fontWeight = FontWeight.SemiBold)
                            Text(
                                "${opportunity.optString("craft_city")} → ${opportunity.optString("sell_city")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text(
                                opportunity.optDouble("profit", 0.0).toMoney(),
                                color = if (opportunity.optDouble("profit", 0.0) >= 0) GreenFresh else RedStale,
                            )
                            Text(
                                opportunity.optDouble("roi", 0.0).toPercent(),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                    }
                }
            }
        }
    }
