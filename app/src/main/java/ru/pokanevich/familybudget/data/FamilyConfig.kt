package ru.pokanevich.familybudget.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Общие семейные настройки, синхронизируемые в `families/<CODE>/config_v1.json`.
 * Слияние выполняется по-элементно (отдельный timestamp у бюджета, каждой категории и каждого
 * регулярного платежа), чтобы одновременные правки на двух телефонах не затирали друг друга.
 */
data class FamilyConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val monthlyBudgetRub: Double = DEFAULT_MONTHLY_BUDGET_RUB,
    val budgetUpdatedAtEpochMs: Long = 0L,
    val categories: List<CategoryDescriptor> = emptyList(),
    val recurringPayments: List<RecurringPaymentEntity> = emptyList()
) {
    fun toRegistry(): CategoryRegistry = CategoryRegistry(categories)

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("monthlyBudgetRub", monthlyBudgetRub)
        put("budgetUpdatedAtEpochMs", budgetUpdatedAtEpochMs)
        val categoriesArr = JSONArray()
        for (category in categories.filter { it.id.isNotBlank() }.sortedBy { it.id }) {
            categoriesArr.put(category.toJsonObject())
        }
        put("categories", categoriesArr)
        val recurringArr = JSONArray()
        for (payment in recurringPayments.filter { it.id.isNotBlank() }.sortedBy { it.id }) {
            recurringArr.put(payment.toJsonObject())
        }
        put("recurringPayments", recurringArr)
    }

    fun isEquivalentTo(other: FamilyConfig): Boolean {
        return schemaVersion == other.schemaVersion &&
            monthlyBudgetRub == other.monthlyBudgetRub &&
            budgetUpdatedAtEpochMs == other.budgetUpdatedAtEpochMs &&
            normalizeById(categories, { it.id }, { it.updatedAtEpochMs }) ==
            normalizeById(other.categories, { it.id }, { it.updatedAtEpochMs }) &&
            normalizeById(recurringPayments, { it.id }, { it.updatedAtEpochMs }) ==
            normalizeById(other.recurringPayments, { it.id }, { it.updatedAtEpochMs })
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val DEFAULT_MONTHLY_BUDGET_RUB = 200_000.0

        private fun <T> normalizeById(
            source: List<T>,
            idOf: (T) -> String,
            updatedAtOf: (T) -> Long
        ): Map<String, T> {
            val map = LinkedHashMap<String, T>()
            for (item in source) {
                val id = idOf(item)
                if (id.isBlank()) continue
                val existing = map[id]
                if (existing == null || updatedAtOf(item) >= updatedAtOf(existing)) {
                    map[id] = item
                }
            }
            return map
        }

        private fun <T> mergeById(
            local: List<T>,
            remote: List<T>,
            idOf: (T) -> String,
            updatedAtOf: (T) -> Long
        ): List<T> {
            val merged = LinkedHashMap<String, T>()
            fun collect(source: List<T>) {
                for (item in source) {
                    val id = idOf(item)
                    if (id.isBlank()) continue
                    val current = merged[id]
                    if (current == null || updatedAtOf(item) >= updatedAtOf(current)) {
                        merged[id] = item
                    }
                }
            }
            collect(local)
            collect(remote)
            return merged.entries.sortedBy { it.key }.map { it.value }
        }

        private fun <T> parseJsonArrayDeduped(
            arr: JSONArray?,
            parseItem: (JSONObject) -> T?,
            idOf: (T) -> String,
            updatedAtOf: (T) -> Long
        ): List<T> {
            if (arr == null) return emptyList()
            val parsedItems = ArrayList<T>(arr.length())
            for (i in 0 until arr.length()) {
                val itemObj = arr.optJSONObject(i) ?: continue
                parseItem(itemObj)?.let { parsedItems.add(it) }
            }
            return normalizeById(parsedItems, idOf, updatedAtOf)
                .entries
                .sortedBy { it.key }
                .map { it.value }
        }

        fun mergeRecurringPayments(
            local: List<RecurringPaymentEntity>,
            remote: List<RecurringPaymentEntity>
        ): List<RecurringPaymentEntity> =
            mergeById(local, remote, { it.id }, { it.updatedAtEpochMs })

        fun fromJsonString(raw: String): FamilyConfig? {
            if (raw.isBlank()) return null
            return runCatching { fromJsonObject(JSONObject(raw)) }.getOrNull()
        }

        fun fromJsonObject(obj: JSONObject): FamilyConfig? {
            val hasRecognizedField = obj.has("schemaVersion") ||
                obj.has("monthlyBudgetRub") ||
                obj.has("categories") ||
                obj.has("recurringPayments")
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

            val categories = parseJsonArrayDeduped(
                arr = obj.optJSONArray("categories"),
                parseItem = CategoryDescriptor::fromJsonObject,
                idOf = { it.id },
                updatedAtOf = { it.updatedAtEpochMs }
            )

            val recurringPayments = parseJsonArrayDeduped(
                arr = obj.optJSONArray("recurringPayments"),
                parseItem = RecurringPaymentEntity::fromJsonObject,
                idOf = { it.id },
                updatedAtOf = { it.updatedAtEpochMs }
            )

            return FamilyConfig(
                schemaVersion = schemaVersion,
                monthlyBudgetRub = monthlyBudgetRub,
                budgetUpdatedAtEpochMs = budgetUpdatedAtEpochMs,
                categories = categories,
                recurringPayments = recurringPayments
            )
        }

        /**
         * Детерминированное слияние локального и удалённого конфига:
         * - `monthlyBudgetRub` выбирается по большему `budgetUpdatedAtEpochMs` (при равенстве — `remote`);
         * - `categories` сливаются по `id` и большему `updatedAtEpochMs` (при равенстве — `remote`);
         * - `recurringPayments` сливаются по `id` и большему `updatedAtEpochMs` (при равенстве — `remote`);
         * - удаление категорий/платежей только мягкое через `isArchived = true` / `isDeleted = true` + `updatedAtEpochMs`.
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

            val mergedCategories = mergeById(
                local = local.categories,
                remote = remote.categories,
                idOf = { it.id },
                updatedAtOf = { it.updatedAtEpochMs }
            )
            val mergedRecurring = mergeRecurringPayments(
                local = local.recurringPayments,
                remote = remote.recurringPayments
            )

            return FamilyConfig(
                schemaVersion = mergedSchemaVersion,
                monthlyBudgetRub = mergedBudget,
                budgetUpdatedAtEpochMs = mergedBudgetUpdatedAt,
                categories = mergedCategories,
                recurringPayments = mergedRecurring
            )
        }
    }
}
