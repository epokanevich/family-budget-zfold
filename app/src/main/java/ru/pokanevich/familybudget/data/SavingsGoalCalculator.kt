package ru.pokanevich.familybudget.data

data class SavingsGoalProgress(
    val goal: SavingsGoalEntity,
    val savedAmountRub: Double,
    val depositSumRub: Double,
    val withdrawSumRub: Double,
    val remainingToTargetRub: Double,
    val progressRatio: Float,
    val isCompleted: Boolean
)

/**
 * Чистая логика расчёта накоплений по семейным копилкам/целям
 * на основе операций `TRANSFER` с привязкой `goalId` и категорией `GOAL_DEPOSIT` / `GOAL_WITHDRAW`.
 */
object SavingsGoalCalculator {

    const val CATEGORY_GOAL_DEPOSIT = "GOAL_DEPOSIT"
    const val CATEGORY_GOAL_WITHDRAW = "GOAL_WITHDRAW"

    fun buildGoalProgressList(
        goals: List<SavingsGoalEntity>,
        expenses: List<ExpenseEntity>
    ): List<SavingsGoalProgress> {
        val visibleGoals = goals.filter { !it.isDeleted }
        if (visibleGoals.isEmpty()) return emptyList()

        val depositsByGoalKopecks = HashMap<String, Long>()
        val withdrawsByGoalKopecks = HashMap<String, Long>()

        for (expense in expenses) {
            if (expense.isDeleted || expense.isPendingConfirmation) continue
            val goalId = expense.goalId
            if (goalId.isBlank()) continue
            val amount = expense.amountRub
            if (!amount.isFinite() || amount <= 0.0) continue

            val kopecks = MoneyMath.toKopecks(amount)
            when (expense.categoryName) {
                CATEGORY_GOAL_DEPOSIT ->
                    depositsByGoalKopecks[goalId] = (depositsByGoalKopecks[goalId] ?: 0L) + kopecks
                CATEGORY_GOAL_WITHDRAW ->
                    withdrawsByGoalKopecks[goalId] = (withdrawsByGoalKopecks[goalId] ?: 0L) + kopecks
            }
        }

        return visibleGoals.map { goal ->
            val depositKop = depositsByGoalKopecks[goal.id] ?: 0L
            val withdrawKop = withdrawsByGoalKopecks[goal.id] ?: 0L
            val initialKop = MoneyMath.toKopecks(goal.initialAmountRub)
            val savedKop = initialKop + depositKop - withdrawKop
            val deposits = MoneyMath.fromKopecks(depositKop)
            val withdraws = MoneyMath.fromKopecks(withdrawKop)
            val saved = MoneyMath.fromKopecks(savedKop)
            val target = goal.targetAmountRub.coerceAtLeast(0.0)
            val targetKop = MoneyMath.toKopecks(target)
            val remaining = if (targetKop > 0L) MoneyMath.fromKopecks((targetKop - savedKop).coerceAtLeast(0L)) else 0.0
            val ratio = if (target > 0.0) (saved / target).toFloat().coerceIn(0f, 1f) else 0f
            val completed = targetKop > 0L && savedKop >= targetKop

            SavingsGoalProgress(
                goal = goal,
                savedAmountRub = saved,
                depositSumRub = deposits,
                withdrawSumRub = withdraws,
                remainingToTargetRub = remaining,
                progressRatio = ratio,
                isCompleted = completed
            )
        }
    }

    fun calculateGoalSavedAmount(
        goal: SavingsGoalEntity,
        expenses: List<ExpenseEntity>
    ): Double {
        if (goal.isDeleted) return 0.0
        return buildGoalProgressList(listOf(goal), expenses)
            .firstOrNull()?.savedAmountRub ?: goal.initialAmountRub
    }

    /**
     * Суммарные накопления в активных (не удалённых и не архивных) копилках.
     * Архивация цели означает её завершение/закрытие и освобождает её средства в свободный остаток.
     */
    fun calculateTotalSavedInGoals(
        goals: List<SavingsGoalEntity>,
        expenses: List<ExpenseEntity>
    ): Double {
        return buildGoalProgressList(goals, expenses)
            .asSequence()
            .filter { !it.goal.isArchived }
            .sumRubOf { it.savedAmountRub.coerceAtLeast(0.0) }
    }

    fun resolveDisplayCategory(
        expense: ExpenseEntity,
        registry: CategoryRegistry
    ): CategoryDescriptor = when (expense.categoryName) {
        CATEGORY_GOAL_DEPOSIT -> CategoryDescriptor(
            id = CATEGORY_GOAL_DEPOSIT,
            displayName = "Пополнение копилки",
            shortName = "В копилку",
            emoji = "🐷",
            colorHex = 0xFF059669L,
            isIncome = false,
            isBuiltIn = false
        )
        CATEGORY_GOAL_WITHDRAW -> CategoryDescriptor(
            id = CATEGORY_GOAL_WITHDRAW,
            displayName = "Снятие с копилки",
            shortName = "Из копилки",
            emoji = "🎯",
            colorHex = 0xFF3A7DF0L,
            isIncome = false,
            isBuiltIn = false
        )
        else -> registry.resolve(expense.categoryName)
    }

    fun transferBadgeLabel(expense: ExpenseEntity): String = when (expense.categoryName) {
        CATEGORY_GOAL_DEPOSIT -> "В копилку"
        CATEGORY_GOAL_WITHDRAW -> "Из копилки"
        else -> "Перевод"
    }
}
