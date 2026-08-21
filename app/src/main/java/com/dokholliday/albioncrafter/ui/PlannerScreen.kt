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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

private val CITIES = listOf(
    "Bridgewatch", "Fort Sterling", "Lymhurst", "Martlock", "Thetford", "Caerleon",
)

data class ProgressState(
    val running: Boolean = false,
    val opId: String? = null,
    val stage: String? = null,
    val message: String? = null,
    val fraction: Float? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(modifier: Modifier, appStatus: AppStatus) {
    var silver by remember { mutableStateOf("1000000") }
    var homeCity by remember { mutableStateOf("Bridgewatch") }
    var preset by remember { mutableStateOf("careful") }
    var showAdvanced by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(ProgressState()) }
    var planResult by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var snapshots by remember { mutableStateOf<JSONArray?>(null) }

    // Advanced inputs
    var focusAvailable by remember { mutableStateOf("10000") }
    var useFocus by remember { mutableStateOf(false) }
    var tiers by remember { mutableStateOf(setOf(4, 5, 6, 7, 8)) }
    var actionKinds by remember { mutableStateOf(setOf("craft", "refine")) }
    var sellCities by remember { mutableStateOf(setOf("Bridgewatch")) }
    var includeArbitrage by remember { mutableStateOf(false) }
    var premium by remember { mutableStateOf(true) }

    val sink = remember {
        PythonBridge.EventSink { event ->
            val stage = event.optString("stage", event.optString("kind", ""))
            val message = event.optString("message", "")
            val fraction = event.optDouble("fraction", -1.0).takeIf { it >= 0 }?.toFloat()
            progress = progress.copy(stage = stage, message = message, fraction = fraction)
        }
    }

    fun loadSnapshots() {
        PythonBridge.callAsync("planner_recent_snapshots") { result ->
            result.onSuccess { snapshots = it.optJSONArray("snapshots") }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { loadSnapshots() }

    fun constraintsJson(): String {
        val marketAge = when (preset) {
            "fast" -> 24
            "strict" -> 2
            else -> 4
        }
        val historyEnabled = preset != "fast"
        val kinds = buildSet {
            addAll(actionKinds)
            if (includeArbitrage) add("arbitrage")
        }
        val constraints = JSONObject()
            .put("available_silver", silver.toLongOrNull() ?: 1_000_000L)
            .put("available_focus", focusAvailable.toIntOrNull() ?: 10_000)
            .put("region", appStatus.statusJson?.optString("region", "americas") ?: "americas")
            .put("premium", premium)
            .put("material_cities", JSONArray(listOf(homeCity)))
            .put("craft_cities", JSONArray(listOf(homeCity)))
            .put("sell_cities", JSONArray(sellCities))
            .put("tiers", JSONArray(tiers.sorted()))
            .put("action_kinds", JSONArray(kinds))
            .put("use_focus", useFocus)
            .put("max_market_age_hours", marketAge)
            .put("history_enabled", historyEnabled)
        return constraints.toString()
    }

    fun run() {
        val opId = PythonBridge.newOpId()
        progress = ProgressState(running = true, opId = opId)
        error = null
        PythonBridge.callAsync("planner_run", opId, constraintsJson(), sink) { result ->
            progress = ProgressState()
            result
                .onSuccess { payload ->
                    planResult = payload.optJSONObject("result")
                    loadSnapshots()
                }
                .onFailure { error = it.message ?: it.toString() }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Find Me Money", style = MaterialTheme.typography.headlineSmall)

        if (appStatus.catalogEmpty) {
            Banner(
                "No game data yet. Open Market → Update Static Game Data first (WiFi recommended, ~80MB).",
                AmberAging,
            )
        }

        SectionCard("Simple Mode") {
            OutlinedTextField(
                value = silver,
                onValueChange = { silver = it.filter { c -> c.isDigit() } },
                label = { Text("Bankroll (silver)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // Home city dropdown
            var cityMenu by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { cityMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Home city: $homeCity")
            }
            DropdownMenu(expanded = cityMenu, onDismissRequest = { cityMenu = false }) {
                CITIES.forEach { city ->
                    DropdownMenuItem(
                        text = { Text(city) },
                        onClick = {
                            homeCity = city
                            cityMenu = false
                        },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("fast" to "Fast", "careful" to "Careful", "strict" to "Strict").forEach { (value, label) ->
                    FilterChip(
                        selected = preset == value,
                        onClick = { preset = value },
                        label = { Text(label) },
                    )
                }
            }

            Button(onClick = { run() }, enabled = !progress.running, modifier = Modifier.fillMaxWidth()) {
                Text("FIND ME MONEY")
            }
        }

        if (progress.running) {
            SectionCard {
                Text(
                    (progress.stage ?: "").replace('_', ' ').uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Gold,
                )
                progress.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                progress.fraction?.let {
                    LinearProgressIndicator(
                        progress = { it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = { progress.opId?.let(PythonBridge::cancel) }) {
                    Text("Cancel")
                }
            }
        }

        error?.let { Banner("Error: $it", RedStale) }

        planResult?.let { result ->
            val snapshot = result.optJSONObject("snapshot")
            if (snapshot == null) {
                Banner("No actionable plan. " +
                    "Rejections: " + rejectionsSummary(result.optJSONArray("rejection_counts")),
                    AmberAging)
            } else {
                PlanResultCard(snapshot)
            }
        }

        OutlinedButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showAdvanced) "Hide advanced" else "Advanced options")
        }

        if (showAdvanced) {
            SectionCard("Advanced") {
                OutlinedTextField(
                    value = focusAvailable,
                    onValueChange = { focusAvailable = it.filter { c -> c.isDigit() } },
                    label = { Text("Available Focus") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useFocus, onCheckedChange = { useFocus = it })
                    Text("Use Focus in planning")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = premium, onCheckedChange = { premium = it })
                    Text("Premium")
                }
                Text("Tiers", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (4..8).forEach { tier ->
                        FilterChip(
                            selected = tier in tiers,
                            onClick = {
                                tiers = if (tier in tiers) tiers - tier else tiers + tier
                            },
                            label = { Text("T$tier") },
                        )
                    }
                }
                Text("Sell cities", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CITIES.take(5).forEach { city ->
                        FilterChip(
                            selected = city in sellCities,
                            onClick = {
                                sellCities =
                                    if (city in sellCities) sellCities - city
                                    else sellCities + city
                            },
                            label = { Text(city.take(4)) },
                        )
                    }
                }
                Text("Actions", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("craft" to "Craft", "refine" to "Refine").forEach { (kind, label) ->
                        FilterChip(
                            selected = kind in actionKinds,
                            onClick = {
                                actionKinds =
                                    if (kind in actionKinds) actionKinds - kind
                                    else actionKinds + kind
                            },
                            label = { Text(label) },
                        )
                    }
                    FilterChip(
                        selected = includeArbitrage,
                        onClick = { includeArbitrage = !includeArbitrage },
                        label = { Text("Arbitrage") },
                    )
                }
            }
        }

        snapshots?.let { arr ->
            if (arr.length() > 0) {
                SectionCard("Recent Plans") {
                    (0 until arr.length()).forEach { i ->
                        val s = arr.optJSONObject(i) ?: return@forEach
                        StatRow(
                            s.optString("created_at", "").take(16).replace('T', ' '),
                            "${s.optInt("action_count")} actions · ${s.optDouble("expected_profit", 0.0).toMoney()}",
                            valueColor = GreenFresh,
                        )
                    }
                }
            }
        }
    }
}

private fun rejectionsSummary(arr: JSONArray?): String {
    if (arr == null || arr.length() == 0) return "none"
    return (0 until arr.length())
        .mapNotNull { arr.optJSONObject(it) }
        .joinToString(", ") { "${it.optString("reason")}×${it.optInt("count")}" }
}

@Composable
private fun PlanResultCard(snapshot: JSONObject) {
    val actions = snapshot.optJSONArray("actions") ?: JSONArray()
    val planStatus = snapshot.optString("plan_status", "")
    val fresh = planStatus == "decision_grade"
    SectionCard(
        title = "PLAN · ${planStatus.replace('_', ' ')} · optimizer ${snapshot.optString("optimizer_status")}",
    ) {
        Banner(
            if (actions.length() > 0) "Expected profit ${snapshot.optDouble("total_expected_profit", 0.0).toMoney()}" +
                " · capital ${snapshot.optDouble("total_pre_revenue_cash", 0.0).toMoney()}" +
                " · ROI ${snapshot.optDouble("plan_roi", 0.0).toPercent()}" +
                " · remaining ${snapshot.optDouble("silver_remaining", 0.0).toMoney()}"
            else "No profitable action is ready yet.",
            if (fresh && actions.length() > 0) GreenFresh else AmberAging,
        )
        val search = snapshot.optJSONObject("search")
        if (search != null && search.length() > 0) {
            Text(
                "Fully priced: ${search.optInt("fully_priced", 0)} · profitable: ${search.optInt("profitable", 0)}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        (0 until actions.length()).forEach { i ->
            val action = actions.optJSONObject(i) ?: return@forEach
            ActionRow(action, i + 1)
        }
    }
}

@Composable
private fun ActionRow(action: JSONObject, number: Int) {
    val kind = action.optString("kind", "").uppercase()
    val route = if (kind == "ARBITRAGE") {
        "Buy in ${action.optString("buy_city")} → sell in ${action.optString("sell_city")}"
    } else {
        "Buy in ${action.optString("material_city")} → produce in ${action.optString("production_city")}" +
            " → sell in ${action.optString("sell_city")}"
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "$number. $kind — ${action.optString("display_name")}",
            fontWeight = FontWeight.Bold,
        )
        Text(route, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Text(
            "${action.optInt("quantity")} units · cash ${action.optDouble("pre_revenue_cash_required", 0.0).toMoney()}" +
                " · profit ${action.optDouble("expected_profit", 0.0).toMoney()}",
            style = MaterialTheme.typography.bodySmall,
            color = GreenFresh,
        )
    }
}
