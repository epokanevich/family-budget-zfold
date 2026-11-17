package ru.pokanevich.familybudget.data

import org.json.JSONObject

enum class CategoryLimitMode {
    PERCENT,
    FIXED_RUB
}

/**
 * Универсальное описание категории (встроенной или пользовательской).
 */
data class CategoryDescriptor(
    val id: String,
    val displayName: String,
    val shortName: String,
    val emoji: String,
    val colorHex: Long,
    val isIncome: Boolean,
    val isBuiltIn: Boolean,
    val limitMode: CategoryLimitMode = CategoryLimitMode.PERCENT,
    val limitValue: Double = 0.0,
    val isArchived: Boolean = false,
    val updatedAtEpochMs: Long = 0L
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("displayName", displayName)
        put("shortName", shortName)
        put("emoji", emoji)
        put("colorHex", colorHex)
        put("isIncome", isIncome)
        put("isBuiltIn", isBuiltIn)
        put("limitMode", limitMode.name)
        put("limitValue", limitValue)
        put("isArchived", isArchived)
        put("updatedAtEpochMs", updatedAtEpochMs)
    }

    companion object {
        private fun JSONObject.optNonNullString(key: String): String {
            if (!has(key) || isNull(key)) return ""
            return optString(key, "")
        }

        fun fromJsonObject(obj: JSONObject): CategoryDescriptor? {
            val id = obj.optNonNullString("id").trim().ifBlank { return null }
            val builtInFallback = ExpenseCategory.entries.firstOrNull { it.name == id }?.toDescriptor()

            val displayName = obj.optNonNullString("displayName").trim()
                .ifBlank { builtInFallback?.displayName ?: id }
            val shortName = obj.optNonNullString("shortName").trim()
                .ifBlank { builtInFallback?.shortName ?: displayName }
            val emoji = obj.optNonNullString("emoji").trim()
                .ifBlank { builtInFallback?.emoji ?: ExpenseCategory.OTHER.emoji }
            val colorHex = if (obj.has("colorHex") && !obj.isNull("colorHex")) {
                obj.optLong("colorHex", builtInFallback?.colorHex ?: ExpenseCategory.OTHER.defaultColorHex)
            } else {
                builtInFallback?.colorHex ?: ExpenseCategory.OTHER.defaultColorHex
            }
            val isIncome = if (obj.has("isIncome") && !obj.isNull("isIncome")) {
                obj.optBoolean("isIncome", builtInFallback?.isIncome ?: false)
            } else {
                builtInFallback?.isIncome ?: false
            }
            val isBuiltIn = if (obj.has("isBuiltIn") && !obj.isNull("isBuiltIn")) {
                obj.optBoolean("isBuiltIn", builtInFallback != null)
            } else {
                builtInFallback != null
            }
            val rawMode = obj.optNonNullString("limitMode").trim().uppercase()
            val limitMode = runCatching { CategoryLimitMode.valueOf(rawMode) }
                .getOrDefault(builtInFallback?.limitMode ?: CategoryLimitMode.PERCENT)

            val rawLimit = if (obj.has("limitValue") && !obj.isNull("limitValue")) {
                obj.optDouble("limitValue", builtInFallback?.limitValue ?: 0.0)
            } else {
                builtInFallback?.limitValue ?: 0.0
            }
            val limitValue = if (rawLimit.isFinite() && rawLimit >= 0.0) rawLimit else 0.0

            val isArchived = if (obj.has("isArchived") && !obj.isNull("isArchived")) {
                obj.optBoolean("isArchived", false)
            } else {
                false
            }
            val updatedAtEpochMs = if (obj.has("updatedAtEpochMs") && !obj.isNull("updatedAtEpochMs")) {
                obj.optLong("updatedAtEpochMs", 0L).coerceAtLeast(0L)
            } else {
                0L
            }

            return CategoryDescriptor(
                id = id,
                displayName = displayName,
                shortName = shortName,
                emoji = emoji,
                colorHex = colorHex,
                isIncome = isIncome,
                isBuiltIn = isBuiltIn,
                limitMode = limitMode,
                limitValue = limitValue,
                isArchived = isArchived,
                updatedAtEpochMs = updatedAtEpochMs
            )
        }
    }
}

/**
 * Реестр категорий: объединяет 14+4 встроенные категории и пользовательские/переопределённые,
 * поддерживает миграцию старых ключей v1/v2 и безопасный фолбэк отображения для неизвестных ID
 * без перезаписи исходного [ExpenseEntity.categoryName].
 */
class CategoryRegistry(
    customCategories: List<CategoryDescriptor> = emptyList()
) {
    val allCategories: List<CategoryDescriptor>
    private val byId: Map<String, CategoryDescriptor>

    init {
        val builtIns = ExpenseCategory.entries.map { it.toDescriptor() }
        val builtInMap = builtIns.associateBy { it.id }.toMutableMap()
        val extraCustom = LinkedHashMap<String, CategoryDescriptor>()

        for (item in customCategories) {
            if (item.id.isBlank()) continue
            if (item.id in builtInMap) {
                builtInMap[item.id] = item.copy(isBuiltIn = true)
            } else {
                extraCustom[item.id] = item
            }
        }

        val ordered = builtIns.map { builtInMap.getValue(it.id) } + extraCustom.values
        allCategories = ordered
        byId = ordered.associateBy { it.id }
    }

    val expenseCategories: List<CategoryDescriptor>
        get() = allCategories.filter { !it.isIncome && !it.isArchived }

    val incomeCategories: List<CategoryDescriptor>
        get() = allCategories.filter { it.isIncome && !it.isArchived }

    val allExpenseCategories: List<CategoryDescriptor>
        get() = allCategories.filter { !it.isIncome }

    val allIncomeCategories: List<CategoryDescriptor>
        get() = allCategories.filter { it.isIncome }

    val defaultExpenseCategory: CategoryDescriptor
        get() = resolve(ExpenseCategory.GROCERIES.name)

    val defaultIncomeCategory: CategoryDescriptor
        get() = resolve(ExpenseCategory.INCOME_SALARY.name)

    val otherExpenseCategory: CategoryDescriptor
        get() = resolve(ExpenseCategory.OTHER.name)

    val otherIncomeCategory: CategoryDescriptor
        get() = resolve(ExpenseCategory.INCOME_OTHER.name)

    fun find(rawCategoryName: String): CategoryDescriptor? {
        byId[rawCategoryName]?.let { return it }
        ExpenseCategory.LEGACY_STORED_ALIASES[rawCategoryName]?.let { legacyEnum ->
            return byId[legacyEnum.name]
        }
        return null
    }

    fun isKnown(rawCategoryName: String): Boolean = find(rawCategoryName) != null

    fun resolve(rawCategoryName: String): CategoryDescriptor {
        find(rawCategoryName)?.let { return it }
        if (rawCategoryName.isBlank()) {
            return byId[ExpenseCategory.OTHER.name] ?: fallbackDescriptor(ExpenseCategory.OTHER.name)
        }
        return fallbackDescriptor(rawCategoryName)
    }

    fun guessFromMerchant(merchantRaw: String, isIncome: Boolean = false): CategoryDescriptor {
        val guessed = ExpenseCategory.guessFromMerchant(merchantRaw, isIncome)
        return resolve(guessed.name)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CategoryRegistry) return false
        return allCategories == other.allCategories
    }

    override fun hashCode(): Int = allCategories.hashCode()

    companion object {
        val DEFAULT = CategoryRegistry()

        fun fallbackDescriptor(rawId: String): CategoryDescriptor = CategoryDescriptor(
            id = rawId,
            displayName = ExpenseCategory.OTHER.displayName,
            shortName = ExpenseCategory.OTHER.shortName,
            emoji = ExpenseCategory.OTHER.emoji,
            colorHex = ExpenseCategory.OTHER.defaultColorHex,
            isIncome = false,
            isBuiltIn = false
        )
    }
}
