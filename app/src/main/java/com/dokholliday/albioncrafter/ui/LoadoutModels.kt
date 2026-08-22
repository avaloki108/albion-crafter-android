package com.dokholliday.albioncrafter.ui

import java.util.UUID

enum class LoadoutSlot(
    val wireName: String,
    val label: String,
    val contributesToAverageIp: Boolean = false,
    val allowsQuantity: Boolean = false,
) {
    MAIN_HAND("main_hand", "Main Hand", contributesToAverageIp = true),
    OFF_HAND("off_hand", "Off Hand", contributesToAverageIp = true),
    HEAD("head", "Head", contributesToAverageIp = true),
    CHEST("chest", "Armor", contributesToAverageIp = true),
    SHOES("shoes", "Shoes", contributesToAverageIp = true),
    BAG("bag", "Bag"),
    CAPE("cape", "Cape", contributesToAverageIp = true),
    MOUNT("mount", "Mount"),
    POTION("potion", "Potion", allowsQuantity = true),
    FOOD("food", "Food", allowsQuantity = true),
}

data class LoadoutCatalogItem(
    val itemId: String,
    val name: String,
    val tier: Int,
    val enchantment: Int,
    val maxQuality: Int,
    val twoHanded: Boolean,
    val estimatedBaseIp: Int?,
    val subcategory: String,
)

data class LoadoutSelection(
    val item: LoadoutCatalogItem,
    val quality: Int = 1,
    val quantity: Int = 1,
    val observedIp: String = "",
)

data class LoadoutSlotChoice(
    val main: LoadoutSelection? = null,
    val alternatives: List<LoadoutSelection> = emptyList(),
)

data class SavedLoadout(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val author: String = "",
    val locationTags: Set<String> = emptySet(),
    val zoneTags: Set<String> = emptySet(),
    val sizeTags: Set<String> = emptySet(),
    val roleTags: Set<String> = emptySet(),
    val activityTags: Set<String> = emptySet(),
    val budgetTag: String? = null,
    val strengths: String = "",
    val weaknesses: String = "",
    val description: String = "",
    val rotationNotes: String = "",
    val slots: Map<LoadoutSlot, LoadoutSlotChoice> = emptyMap(),
    val marketCity: String = "Bridgewatch",
    val priceSide: String = "sell_order",
    val targetIp: String = "",
    val lastKnownCost: Double? = null,
    val lastMissingPrices: Int = 0,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

object LoadoutMath {
    private val qualityIpBonus = mapOf(1 to 0, 2 to 20, 3 to 40, 4 to 60, 5 to 100)

    fun itemPower(selection: LoadoutSelection?): Int? {
        if (selection == null) return null
        selection.observedIp.toIntOrNull()?.takeIf { it >= 0 }?.let { return it }
        val base = selection.item.estimatedBaseIp ?: return null
        return base + qualityIpBonus.getValue(selection.quality.coerceIn(1, 5))
    }

    fun averageItemPower(loadout: SavedLoadout): Double? {
        val main = loadout.slots[LoadoutSlot.MAIN_HAND]?.main
        val head = itemPower(loadout.slots[LoadoutSlot.HEAD]?.main) ?: 0
        val chest = itemPower(loadout.slots[LoadoutSlot.CHEST]?.main) ?: 0
        val shoes = itemPower(loadout.slots[LoadoutSlot.SHOES]?.main) ?: 0
        val cape = itemPower(loadout.slots[LoadoutSlot.CAPE]?.main) ?: 0
        val mainIp = itemPower(main) ?: 0
        val offHandIp = if (main?.item?.twoHanded == true) {
            mainIp
        } else {
            itemPower(loadout.slots[LoadoutSlot.OFF_HAND]?.main) ?: 0
        }
        val anyCombatItem = listOf(
            LoadoutSlot.HEAD,
            LoadoutSlot.CHEST,
            LoadoutSlot.SHOES,
            LoadoutSlot.CAPE,
            LoadoutSlot.MAIN_HAND,
            LoadoutSlot.OFF_HAND,
        ).any { loadout.slots[it]?.main != null }
        if (!anyCombatItem) return null
        return (head + chest + shoes + cape + mainIp + offHandIp) / 6.0
    }

    fun combatSlotsFilled(loadout: SavedLoadout): Int {
        val main = loadout.slots[LoadoutSlot.MAIN_HAND]?.main
        var filled = listOf(
            LoadoutSlot.HEAD,
            LoadoutSlot.CHEST,
            LoadoutSlot.SHOES,
            LoadoutSlot.CAPE,
        ).count { loadout.slots[it]?.main != null }
        if (main != null) filled += if (main.item.twoHanded) 2 else 1
        if (main?.item?.twoHanded != true && loadout.slots[LoadoutSlot.OFF_HAND]?.main != null) {
            filled += 1
        }
        return filled
    }
}

object LoadoutValidation {
    fun issues(loadout: SavedLoadout): List<String> = buildList {
        if (loadout.name.isBlank()) add("Build name is required")
        if (loadout.locationTags.isEmpty()) add("Choose at least one location")
        if (loadout.zoneTags.isEmpty()) add("Choose at least one zone")
        if (loadout.sizeTags.isEmpty()) add("Choose at least one group size")
        if (loadout.roleTags.isEmpty()) add("Choose at least one role")
        if (loadout.activityTags.isEmpty()) add("Choose at least one activity")
        if (loadout.budgetTag == null) add("Choose a budget")
        if (loadout.slots.values.none { it.main != null }) add("Select at least one main item")
    }
}

val LOADOUT_LOCATION_TAGS = listOf(
    "Open World", "Static Dungeon", "Avalonian Dungeon", "Solo Dungeon",
    "Roads of Avalon", "Depths", "Hellgate", "Corrupted Dungeon", "Mists",
    "Knightfall Abbey", "Arena", "Other",
)

val LOADOUT_ZONE_TAGS = listOf("Blue", "Yellow", "Orange", "Red", "Black")
val LOADOUT_SIZE_TAGS = listOf("Solo", "Duo", "Trio", "Small Group", "Large Group", "Zerg")
val LOADOUT_ROLE_TAGS = listOf("Tank", "Healer", "DPS", "Support", "Crowd Control", "Utility", "Other")
val LOADOUT_ACTIVITY_TAGS = listOf(
    "PvE Farm", "Tracking", "Ganking", "PvP", "Faction Warfare", "Territory",
    "Crystal League", "Crafting", "Gathering", "Transporting", "Exploration", "Ratting", "Other",
)
val LOADOUT_BUDGET_TAGS = listOf(
    "Newbie (<100k)", "Low (<300k)", "Medium (<2M)", "High (<5M)", "Gucci (>5M)",
)

