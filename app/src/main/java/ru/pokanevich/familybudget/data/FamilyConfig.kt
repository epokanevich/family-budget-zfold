package ru.pokanevich.familybudget.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Общие семейные настройки, синхронизируемые в `families/<CODE>/config_v1.json`.
 * Слияние выполняется по-элементно (отдельный timestamp у бюджета и у каждой категории),
 * чтобы одновременные правки бюджета на одном телефоне и категорий на другом не затирали друг друга.
 */
data class FamilyConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val monthlyBudgetRub: Double = DEFAULT_MONTHLY_BUDGET_RUB,
    val budgetUpdatedAtEpochMs: Long = 0L,
    val categories: List<CategoryDescriptor> = emptyList()
) {
    fun toRegistry(): CategoryRegistry = CategoryRegistry(categories)

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("monthlyBudgetRub", monthlyBudgetRub)
        put("budgetUpdatedAtEpochMs", budgetUpdatedAtEpochMs)
        val arr = JSONArray()
        for (category in categories.filter { it.id.isNotBlank() }.sortedBy { it.id }) {
            arr.put(category.toJsonObject())
        }
        put("categories", arr)
    }

    fun isEquivalentTo(other: FamilyConfig): Boolean {
        return schemaVersion == other.schemaVersion &&
            monthlyBudgetRub == other.monthlyBudgetRub &&
            budgetUpdatedAtEpochMs == other.budgetUpdatedAtEpochMs &&
            normalizedCategoriesMap() == other.normalizedCategoriesMap()
    }

    private fun normalizedCategoriesMap(): Map<String, CategoryDescriptor> {
        val map = LinkedHashMap<String, CategoryDescriptor>()
        for (item in categories) {
            if (item.id.isBlank()) continue
            val existing = map[item.id]
            if (existing == null || item.updatedAtEpochMs >= existing.updatedAtEpochMs) {
                map[item.id] = item
            }
        }
        return map
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val DEFAULT_MONTHLY_BUDGET_RUB = 200_000.0

        fun fromJsonString(raw: String): FamilyConfig? {
            if (raw.isBlank()) return null
            return runCatching { fromJsonObject(JSONObject(raw)) }.getOrNull()
        }

        fun fromJsonObject(obj: JSONObject): FamilyConfig? {
            val hasRecognizedField = obj.has("schemaVersion") ||
                obj.has("monthlyBudgetRub") ||
                obj.has("categories")
            if (!hasRecognizedField) return null

            val schemaVersion = if (obj.has("schemaVersion") && !obj.isNull("schemaVersion")) {
                obj.optInt("schemaVersion", CURRENT_SCHEMA_VERSION).coerceAtLeast(1)
            } else {
                CURRENT_SCHEMA_VERSION
            }

            val rawBudget = if (obj.has("monthlyBudgetRub") && !obj.isNull("monthlyBudgetRub")) {
                obj.optDouble("monthlyBudgetRub", DEFAULT_MONTHLY_BUDGET_RUB)
            } else {
                DEFAULT_MONTHLY_BUDGET_RUB
            }
            val monthlyBudgetRub = if (rawBudget.isFinite() && rawBudget >= 0.0) {
                rawBudget
            } else {
                DEFAULT_MONTHLY_BUDGET_RUB
            }

            val budgetUpdatedAtEpochMs = if (obj.has("budgetUpdatedAtEpochMs") && !obj.isNull("budgetUpdatedAtEpochMs")) {
                obj.optLong("budgetUpdatedAtEpochMs", 0L).coerceAtLeast(0L)
            } else {
                0L
            }

            val dedupedCategories = LinkedHashMap<String, CategoryDescriptor>()
            val arr = obj.optJSONArray("categories")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val itemObj = arr.optJSONObject(i) ?: continue
                    val parsed = CategoryDescriptor.fromJsonObject(itemObj) ?: continue
                    val existing = dedupedCategories[parsed.id]
                    if (existing == null || parsed.updatedAtEpochMs >= existing.updatedAtEpochMs) {
                        dedupedCategories[parsed.id] = parsed
                    }
                }
            }

            return FamilyConfig(
                schemaVersion = schemaVersion,
                monthlyBudgetRub = monthlyBudgetRub,
                budgetUpdatedAtEpochMs = budgetUpdatedAtEpochMs,
                categories = dedupedCategories.values.sortedBy { it.id }
            )
        }

        /**
         * Детерминированное слияние локального и удалённого конфига:
         * - `monthlyBudgetRub` выбирается по большему `budgetUpdatedAtEpochMs` (при равенстве — `remote`);
         * - `categories` сливаются по `id` и большему `updatedAtEpochMs` (при равенстве — `remote`);
         * - удаление/скрытие категорий только мягкое через `isArchived = true` + `updatedAtEpochMs`.
         */
        fun merge(local: FamilyConfig, remote: FamilyConfig): FamilyConfig {
            val mergedSchemaVersion = maxOf(local.schemaVersion, remote.schemaVersion)
                .coerceAtLeast(CURRENT_SCHEMA_VERSION)

            val (mergedBudget, mergedBudgetUpdatedAt) = when {
                local.budgetUpdatedAtEpochMs > remote.budgetUpdatedAtEpochMs ->
                    local.monthlyBudgetRub to local.budgetUpdatedAtEpochMs
                remote.budgetUpdatedAtEpochMs > local.budgetUpdatedAtEpochMs ->
                    remote.monthlyBudgetRub to remote.budgetUpdatedAtEpochMs
                else ->
                    remote.monthlyBudgetRub to remote.budgetUpdatedAtEpochMs
            }

            val mergedCategories = LinkedHashMap<String, CategoryDescriptor>()
            fun collect(source: List<CategoryDescriptor>) {
                for (item in source) {
                    if (item.id.isBlank()) continue
                    val current = mergedCategories[item.id]
                    val shouldReplace = when {
                        current == null -> true
                        item.updatedAtEpochMs > current.updatedAtEpochMs -> true
                        item.updatedAtEpochMs < current.updatedAtEpochMs -> false
                        else -> true
                    }
                    if (shouldReplace) {
                        mergedCategories[item.id] = item
                    }
                }
            }

            collect(local.categories)
            collect(remote.categories)

            return FamilyConfig(
                schemaVersion = mergedSchemaVersion,
                monthlyBudgetRub = mergedBudget,
                budgetUpdatedAtEpochMs = mergedBudgetUpdatedAt,
                categories = mergedCategories.values.sortedBy { it.id }
            )
        }
    }
}
