package com.dokholliday.albioncrafter.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import com.dokholliday.albioncrafter.PythonBridge
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private val CITIES = listOf(
    "Bridgewatch", "Fort Sterling", "Lymhurst", "Martlock", "Thetford", "Caerleon",
)

data class RecipeMatch(
    val itemId: String,
    val name: String,
    val tier: Int,
    val enchantment: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalculatorScreen(modifier: Modifier) {
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<RecipeMatch>>(emptyList()) }
    var selected by remember { mutableStateOf<RecipeMatch?>(null) }
    var materialCity by remember { mutableStateOf("Bridgewatch") }
    var craftCity by remember { mutableStateOf("Bridgewatch") }
    var sellCity by remember { mutableStateOf("Bridgewatch") }
    var quantity by remember { mutableStateOf("10") }
    var premium by remember { mutableStateOf(true) }
    var useFocus by remember { mutableStateOf(false) }
    var saleMethod by remember { mutableStateOf("sell_order") }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            matches = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        runCatching {
            PythonBridge.call("catalog_search", query, 30)
        }.onSuccess { payload ->
            val arr = payload.optJSONArray("results") ?: JSONArray()
            matches = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    RecipeMatch(
                        it.optString("item_id"),
                        it.optString("name"),
                        it.optInt("tier", 0),
                        it.optInt("enchantment", 0),
                    )
                }
            }
        }
    }

    fun evaluate() {
        val recipe = selected ?: return
        val request = JSONObject()
            .put("item_id", recipe.itemId)
            .put("material_city", materialCity)
            .put("craft_city", craftCity)
            .put("sell_city", sellCity)
            .put("crafts", quantity.toIntOrNull() ?: 1)
            .put("quality", 1)
            .put("premium", premium)
            .put("use_focus", useFocus)
            .put("sale_method", saleMethod)
        runCatching { PythonBridge.call("calculator_evaluate", request.toString()) }
            .onSuccess { payload ->
                result = if (payload.optBoolean("ok")) payload else null
                error = if (!payload.optBoolean("ok")) payload.optString("error") else null
            }
            .onFailure { error = it.message ?: it.toString() }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Production Calculator", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search item (e.g. bag, sword, plank)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        if (query.isNotBlank() && selected == null) {
            matches.take(10).forEach { match ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = match
                            result = null
                        }
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val ench = if (match.enchantment > 0) " .${match.enchantment}" else ""
                    Text("${match.name}$ench", Modifier.weight(1f))
                    Text("T${match.tier}", color = Gold)
                }
            }
            if (matches.isEmpty()) {
                Text("No matches", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }

        selected?.let { recipe ->
            SectionCard("Recipe: ${recipe.name} (T${recipe.tier})") {
                CityPicker("Material city", materialCity) { materialCity = it }
                CityPicker("Craft city", craftCity) { craftCity = it }
                CityPicker("Sell city", sellCity) { sellCity = it }
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it.filter { c -> c.isDigit() } },
                    label = { Text("Crafts") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = premium, onCheckedChange = { premium = it })
                    Text("Premium")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useFocus, onCheckedChange = { useFocus = it })
                    Text("Use Focus")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = saleMethod == "sell_order",
                        onClick = { saleMethod = "sell_order" },
                        label = { Text("Sell order") },
                    )
                    FilterChip(
                        selected = saleMethod == "instant_sell",
                        onClick = { saleMethod = "instant_sell" },
                        label = { Text("Instant sell") },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { evaluate() }, modifier = Modifier.weight(1f)) {
                        Text("Calculate")
                    }
                    OutlinedButton(
                        onClick = {
                            val request = JSONObject()
                                .put("item_id", recipe.itemId)
                                .put("material_city", materialCity)
                                .put("sell_city", sellCity)
                                .put("quality", 1)
                                .put("sale_method", saleMethod)
                            refreshing = true
                            PythonBridge.callAsync(
                                "calculator_refresh_prices",
                                PythonBridge.newOpId(),
                                request.toString(),
                                PythonBridge.EventSink { },
                            ) { res ->
                                refreshing = false
                                res.onSuccess { evaluate() }
                            }
                        },
                        enabled = !refreshing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Refresh prices")
                    }
                }
                if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            OutlinedButton(onClick = { selected = null; result = null }) {
                Text("Change item")
            }
        }

        error?.let { Banner("Error: $it", RedStale) }

        result?.let { payload ->
            val r = payload.optJSONObject("result")
            if (r != null) {
                SectionCard("Economics") {
                    val profit = r.optJSONObject("profit")
                    StatRow("Profit", r.optDoubleOrNull("profit").toMoney(),
                        valueColor = if ((r.optDoubleOrNull("profit") ?: 0.0) >= 0) GreenFresh else RedStale)
                    StatRow("ROI", r.optDoubleOrNull("roi").toPercent())
                    StatRow("Margin", r.optDoubleOrNull("margin").toPercent())
                    StatRow("Output quantity", r.optIntOrNull("output_quantity")?.toString() ?: "—")
                    StatRow("Raw material cost", r.optDoubleOrNull("raw_material_cost").toMoney())
                    StatRow("Effective material cost", r.optDoubleOrNull("effective_material_cost").toMoney())
                    StatRow("Station fee", r.optDoubleOrNull("station_fee").toMoney())
                    StatRow("Gross sale value", r.optDoubleOrNull("gross_sale_value").toMoney())
                    StatRow("Market fees", r.optDoubleOrNull("market_fees").toMoney())
                    StatRow("Net sale value", r.optDoubleOrNull("net_sale_value").toMoney())
                    StatRow("Upfront capital", r.optDoubleOrNull("upfront_capital_required").toMoney())
                    StatRow("Break-even price", r.optDoubleOrNull("break_even_price").toMoney())
                    StatRow("Focus used", r.optDoubleOrNull("focus_used")?.toString() ?: "—")
                    StatRow("Silver / Focus", r.optDoubleOrNull("silver_per_focus").toMoney())
                }
                val warnings = r.optJSONArray("warnings")
                if (warnings != null && warnings.length() > 0) {
                    SectionCard("Warnings") {
                        (0 until warnings.length()).forEach { i ->
                            Text("• ${warnings.optString(i)}", style = MaterialTheme.typography.bodySmall, color = AmberAging)
                        }
                    }
                }
                val station = payload.optJSONObject("station_fee_evidence")
                if (station != null && !station.optBoolean("present")) {
                    Banner(
                        "No station fee for ${station.optString("station")} in $craftCity. " +
                            "Enter it in Settings → Station fees.",
                        AmberAging,
                    )
                }
                if (
                    station != null &&
                    station.optBoolean("present") &&
                    station.optString("freshness").equals("stale", ignoreCase = true) &&
                    station.optBoolean("allow_stale")
                ) {
                    Banner(
                        "Using the saved ${station.optString("station")} fee from " +
                            station.optString("observed_at").take(10) +
                            ". It remains usable as advisory evidence; update it in Settings " +
                            "when the in-game fee changes.",
                        AmberAging,
                    )
                }
                val fce = payload.optJSONObject("fce_evidence")
                if (fce != null) {
                    Text(
                        "Focus efficiency: ${fce.optDoubleOrNull("fce")?.toString() ?: "unknown"} " +
                            "(${fce.optString("source")})",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
            }
        }
    }
}

fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key, Double.NaN).takeIf { !it.isNaN() }

fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key)) null else optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CityPicker(
    label: String,
    value: String,
    options: List<String> = CITIES,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
