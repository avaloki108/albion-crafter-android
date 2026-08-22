package com.dokholliday.albioncrafter.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LoadoutStore(context: Context) {
    private val preferences = context.getSharedPreferences("loadout-builds", Context.MODE_PRIVATE)

    fun loadAll(): List<SavedLoadout> {
        val raw = preferences.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::decode)
            }.sortedByDescending { it.updatedAtMillis }
        }.getOrDefault(emptyList())
    }

    fun save(loadout: SavedLoadout): List<SavedLoadout> {
        val updated = loadAll()
            .filterNot { it.id == loadout.id }
            .plus(loadout.copy(updatedAtMillis = System.currentTimeMillis()))
            .sortedByDescending { it.updatedAtMillis }
        persist(updated)
        return updated
    }

    fun delete(id: String): List<SavedLoadout> {
        val updated = loadAll().filterNot { it.id == id }
        persist(updated)
        return updated
    }

    private fun persist(loadouts: List<SavedLoadout>) {
        val array = JSONArray()
        loadouts.forEach { array.put(encode(it)) }
        preferences.edit().putString(KEY, array.toString()).apply()
    }

    private fun encode(loadout: SavedLoadout): JSONObject = JSONObject()
        .put("id", loadout.id)
        .put("name", loadout.name)
        .put("author", loadout.author)
        .put("location_tags", JSONArray(loadout.locationTags.toList()))
        .put("zone_tags", JSONArray(loadout.zoneTags.toList()))
        .put("size_tags", JSONArray(loadout.sizeTags.toList()))
        .put("role_tags", JSONArray(loadout.roleTags.toList()))
        .put("activity_tags", JSONArray(loadout.activityTags.toList()))
        .put("budget_tag", loadout.budgetTag)
        .put("strengths", loadout.strengths)
        .put("weaknesses", loadout.weaknesses)
        .put("description", loadout.description)
        .put("rotation_notes", loadout.rotationNotes)
        .put("market_city", loadout.marketCity)
        .put("price_side", loadout.priceSide)
        .put("target_ip", loadout.targetIp)
        .put("last_known_cost", loadout.lastKnownCost)
        .put("last_missing_prices", loadout.lastMissingPrices)
        .put("updated_at", loadout.updatedAtMillis)
        .put("slots", JSONObject().apply {
            loadout.slots.forEach { (slot, choice) ->
                put(slot.wireName, JSONObject()
                    .put("main", choice.main?.let(::encodeSelection))
                    .put("alternatives", JSONArray().apply {
                        choice.alternatives.forEach { put(encodeSelection(it)) }
                    }))
            }
        })

    private fun encodeSelection(selection: LoadoutSelection): JSONObject = JSONObject()
        .put("item_id", selection.item.itemId)
        .put("name", selection.item.name)
        .put("tier", selection.item.tier)
        .put("enchantment", selection.item.enchantment)
        .put("max_quality", selection.item.maxQuality)
        .put("two_handed", selection.item.twoHanded)
        .put("estimated_base_ip", selection.item.estimatedBaseIp)
        .put("subcategory", selection.item.subcategory)
        .put("quality", selection.quality)
        .put("quantity", selection.quantity)
        .put("observed_ip", selection.observedIp)

    private fun decode(json: JSONObject): SavedLoadout {
        val slotsJson = json.optJSONObject("slots") ?: JSONObject()
        val slots = LoadoutSlot.entries.mapNotNull { slot ->
            val choice = slotsJson.optJSONObject(slot.wireName) ?: return@mapNotNull null
            val alternativesJson = choice.optJSONArray("alternatives") ?: JSONArray()
            slot to LoadoutSlotChoice(
                main = choice.optJSONObject("main")?.let(::decodeSelection),
                alternatives = (0 until alternativesJson.length()).mapNotNull { index ->
                    alternativesJson.optJSONObject(index)?.let(::decodeSelection)
                }.take(2),
            )
        }.toMap()
        return SavedLoadout(
            id = json.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            name = json.optString("name"),
            author = json.optString("author"),
            locationTags = json.stringSet("location_tags"),
            zoneTags = json.stringSet("zone_tags"),
            sizeTags = json.stringSet("size_tags"),
            roleTags = json.stringSet("role_tags"),
            activityTags = json.stringSet("activity_tags"),
            budgetTag = json.optString("budget_tag").takeIf { it.isNotBlank() && it != "null" },
            strengths = json.optString("strengths"),
            weaknesses = json.optString("weaknesses"),
            description = json.optString("description"),
            rotationNotes = json.optString("rotation_notes"),
            slots = slots,
            marketCity = json.optString("market_city", "Bridgewatch"),
            priceSide = json.optString("price_side", "sell_order"),
            targetIp = json.optString("target_ip"),
            lastKnownCost = json.optNullableDouble("last_known_cost"),
            lastMissingPrices = json.optInt("last_missing_prices", 0),
            updatedAtMillis = json.optLong("updated_at", System.currentTimeMillis()),
        )
    }

    private fun decodeSelection(json: JSONObject): LoadoutSelection = LoadoutSelection(
        item = LoadoutCatalogItem(
            itemId = json.optString("item_id"),
            name = json.optString("name"),
            tier = json.optInt("tier"),
            enchantment = json.optInt("enchantment"),
            maxQuality = json.optInt("max_quality", 1),
            twoHanded = json.optBoolean("two_handed"),
            estimatedBaseIp = if (json.isNull("estimated_base_ip")) null else json.optInt("estimated_base_ip"),
            subcategory = json.optString("subcategory"),
        ),
        quality = json.optInt("quality", 1),
        quantity = json.optInt("quantity", 1),
        observedIp = json.optString("observed_ip"),
    )

    private fun JSONObject.stringSet(key: String): Set<String> {
        val array = optJSONArray(key) ?: return emptySet()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
    }

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (isNull(key) || !has(key)) null else optDouble(key, Double.NaN).takeIf { !it.isNaN() }

    private companion object {
        const val KEY = "saved_loadouts_v1"
    }
}
