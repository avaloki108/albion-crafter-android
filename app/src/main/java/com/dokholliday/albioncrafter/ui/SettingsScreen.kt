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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dokholliday.albioncrafter.AppStatus
import com.dokholliday.albioncrafter.PythonBridge
import org.json.JSONArray
import org.json.JSONObject

private val STATIONS = listOf(
    "blacksmith" to "Blacksmith",
    "weapon_smith" to "Weapon Smith",
    "armorsmith" to "Armorsmith",
    "tailor" to "Tailor",
    "cobblery" to "Cobblery",
    "tanner" to "Tanner",
    "carpenter" to "Carpenter",
    "toolmaker" to "Toolmaker",
    "furnace" to "Furnace",
    "smelter" to "Smelter",
    "mill" to "Mill",
    "weaver" to "Weaver",
    "tannery" to "Tannery",
    "stonemason" to "Stonemason",
    "alchemist_lab" to "Alchemist's Lab",
)

private val REGIONS = listOf("americas", "europe", "asia", "australia")

@Composable
fun SettingsScreen(modifier: Modifier, appStatus: AppStatus) {
    var settings by remember { mutableStateOf<JSONObject?>(null) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var fees by remember { mutableStateOf<JSONArray?>(null) }
    var matrix by remember { mutableStateOf<JSONObject?>(null) }
    var showAddFee by remember { mutableStateOf(false) }

    // edit states
    var region by remember { mutableStateOf("americas") }
    var premium by remember { mutableStateOf(true) }
    var focusEnabled by remember { mutableStateOf(false) }
    var availableFocus by remember { mutableStateOf("10000") }
    var marketAge by remember { mutableStateOf("4") }
    var stationAge by remember { mutableStateOf("24") }

    var feeCity by remember { mutableStateOf("Bridgewatch") }
    var feeStation by remember { mutableStateOf("blacksmith") }
    var feeValue by remember { mutableStateOf("") }

    var matrixLevels by remember { mutableStateOf(mapOf<String, String>()) }
    var matrixComplete by remember { mutableStateOf(setOf<String>()) }

    fun loadAll() {
        PythonBridge.callAsync("list_settings") { res ->
            res.onSuccess {
                settings = it.optJSONObject("settings")
                settings?.let { s ->
                    region = s.optString("region", "americas")
                    premium = s.optBoolean("premium", true)
                    focusEnabled = s.optBoolean("focus_enabled", false)
                    availableFocus = s.optInt("available_focus", 10000).toString()
                    marketAge = s.optInt("max_market_age_hours", 4).toString()
                    stationAge = s.optInt("max_station_fee_age_hours", 24).toString()
                }
            }
        }
        PythonBridge.callAsync("station_fees_list", region) { res ->
            res.onSuccess { fees = it.optJSONArray("fees") }
        }
        PythonBridge.callAsync("refining_matrix_get") { res ->
            res.onSuccess {
                matrix = it
                val levels = it.optJSONObject("levels") ?: JSONObject()
                matrixLevels = levels.keys().asSequence().associateWith { key ->
                    levels.opt(key)?.toString() ?: ""
                }
                matrixComplete = buildSet {
                    val arr = it.optJSONArray("complete_families") ?: JSONArray()
                    (0 until arr.length()).forEach { i -> add(arr.optString(i)) }
                }
            }
        }
    }

    LaunchedEffect(Unit) { loadAll() }

    fun saveSettings() {
        val payload = JSONObject()
            .put("region", region)
            .put("premium", premium)
            .put("focus_enabled", focusEnabled)
            .put("available_focus", availableFocus.toIntOrNull() ?: 10000)
            .put("max_market_age_hours", marketAge.toIntOrNull() ?: 4)
            .put("max_station_fee_age_hours", stationAge.toIntOrNull() ?: 24)
        PythonBridge.callAsync("save_settings", payload.toString()) { res ->
            res.onSuccess {
                savedMessage = "Settings saved"
                appStatus.refresh()
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        SectionCard("General") {
            CityPicker("Region", region, REGIONS) { region = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = premium, onCheckedChange = { premium = it })
                Text("Premium")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = focusEnabled, onCheckedChange = { focusEnabled = it })
                Text("Focus enabled")
            }
            OutlinedTextField(
                value = availableFocus,
                onValueChange = { availableFocus = it.filter { c -> c.isDigit() } },
                label = { Text("Available Focus") },
                singleLine = true,
            )
            OutlinedTextField(
                value = marketAge,
                onValueChange = { marketAge = it.filter { c -> c.isDigit() } },
                label = { Text("Max market age (hours)") },
                singleLine = true,
            )
            OutlinedTextField(
                value = stationAge,
                onValueChange = { stationAge = it.filter { c -> c.isDigit() } },
                label = { Text("Max station fee age (hours)") },
                singleLine = true,
            )
            Button(onClick = { saveSettings() }, modifier = Modifier.fillMaxWidth()) {
                Text("Save settings")
            }
            savedMessage?.let {
                Text(it, color = GreenFresh, style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionCard("Station Fees") {
            fees?.let { arr ->
                (0 until arr.length()).forEach { i ->
                    val fee = arr.optJSONObject(i) ?: return@forEach
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${fee.optString("station_name")} · ${fee.optString("city")}",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                fee.optString("observed_at").take(16).replace('T', ' '),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                        Text("${fee.optDouble("displayed_fee", 0.0)}")
                        OutlinedButton(onClick = {
                            PythonBridge.callAsync(
                                "station_fee_remove",
                                JSONObject()
                                    .put("region", fee.optString("region"))
                                    .put("city", fee.optString("city"))
                                    .put("station_type", fee.optString("station_type"))
                                    .put("observed_at", fee.optString("observed_at"))
                                    .toString(),
                            ) { loadAll() }
                        }) {
                            Text("Remove")
                        }
                    }
                }
            }
            if (showAddFee) {
                CityPicker("City", feeCity) { feeCity = it }
                CityPicker(
                    label = "Station",
                    value = STATIONS.firstOrNull { it.first == feeStation }?.second ?: "Blacksmith",
                    options = STATIONS.map { it.second },
                ) { name ->
                    feeStation = STATIONS.firstOrNull { it.second == name }?.first ?: "blacksmith"
                }
                OutlinedTextField(
                    value = feeValue,
                    onValueChange = { feeValue = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Displayed fee (silver)") },
                    singleLine = true,
                )
                Button(onClick = {
                    val value = feeValue.toDoubleOrNull() ?: return@Button
                    PythonBridge.callAsync(
                        "station_fee_set",
                        JSONObject()
                            .put("region", region)
                            .put("city", feeCity)
                            .put("station_type", feeStation)
                            .put("displayed_fee", value)
                            .put("observed_at", java.time.Instant.now().toString())
                            .toString(),
                    ) {
                        showAddFee = false
                        feeValue = ""
                        loadAll()
                    }
                }) {
                    Text("Save fee")
                }
            } else {
                OutlinedButton(onClick = { showAddFee = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Add station fee")
                }
            }
        }

        SectionCard("Refining Skills (T4–T8)") {
            listOf("ore" to "Ore→Metal Bars", "wood" to "Wood→Planks", "hide" to "Hide→Leather",
                "fiber" to "Fiber→Cloth", "rock" to "Rock→Stone Blocks").forEach { (family, label) ->
                Text(label, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(4, 5, 6, 7, 8).forEach { tier ->
                        val key = "$family:t$tier"
                        OutlinedTextField(
                            value = matrixLevels[key] ?: "",
                            onValueChange = { text ->
                                matrixLevels = matrixLevels + (key to text.filter { c -> c.isDigit() })
                            },
                            label = { Text("T$tier") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = family in matrixComplete,
                        onCheckedChange = { checked ->
                            matrixComplete =
                                if (checked) matrixComplete + family else matrixComplete - family
                        },
                    )
                    Text("Family complete (blanks are zero)", style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = {
                val levelsJson = JSONObject()
                matrixLevels.forEach { (k, v) -> levelsJson.put(k, v) }
                PythonBridge.callAsync(
                    "refining_matrix_save",
                    JSONObject()
                        .put("levels", levelsJson)
                        .put("complete_families", JSONArray(matrixComplete.toList()))
                        .toString(),
                ) { res ->
                    res.onSuccess { savedMessage = "Refining skills saved" }
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Save refining skills")
            }
        }
    }
}
