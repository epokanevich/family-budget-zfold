package ru.pokanevich.familybudget.data

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
)

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
