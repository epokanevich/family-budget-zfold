package ru.pokanevich.familybudget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.ai.AiBudgetAdvisor
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.FamilyConfig
import ru.pokanevich.familybudget.data.OperationType
import ru.pokanevich.familybudget.data.SavingsGoalCalculator
import ru.pokanevich.familybudget.data.SavingsGoalEntity
import ru.pokanevich.familybudget.ui.util.BudgetPeriod
import ru.pokanevich.familybudget.ui.util.PeriodCalculator
import java.util.Calendar

class SavingsGoalCalculatorTest {

    private fun epochInNovember2026(day: Int): Long = Calendar.getInstance().apply {
        set(Calendar.YEAR, 2026)
        set(Calendar.MONTH, Calendar.NOVEMBER)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun goalAccumulation_initialPlusDepositMinusWithdraw_computesProgressAccurately() {
        val vacation = SavingsGoalEntity(
            id = "goal-vacation",
            title = "Отпуск",
            emoji = "✈️",
            targetAmountRub = 100_000.0,
            initialAmountRub = 15_000.0
        )
        val laptop = SavingsGoalEntity(
            id = "goal-laptop",
            title = "Ноутбук",
            emoji = "💻",
            targetAmountRub = 50_000.0,
            initialAmountRub = 0.0
        )

        val now = epochInNovember2026(10)
        val expenses = listOf(
            ExpenseEntity(
                id = "dep-1",
                amountRub = 25_000.0,
                merchant = "Пополнение копилки: Отпуск",
                categoryName = SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT,
                operationType = OperationType.TRANSFER.name,
                goalId = vacation.id,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            ),
            ExpenseEntity(
                id = "dep-2",
                amountRub = 10_000.0,
                merchant = "Пополнение копилки: Отпуск",
                categoryName = SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT,
                operationType = OperationType.TRANSFER.name,
                goalId = vacation.id,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            ),
            ExpenseEntity(
                id = "wd-1",
                amountRub = 5_000.0,
                merchant = "Снятие с копилки: Отпуск",
                categoryName = SavingsGoalCalculator.CATEGORY_GOAL_WITHDRAW,
                operationType = OperationType.TRANSFER.name,
                goalId = vacation.id,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            ),
            ExpenseEntity(
                id = "dep-laptop",
                amountRub = 50_000.0,
                merchant = "Пополнение копилки: Ноутбук",
                categoryName = SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT,
                operationType = OperationType.TRANSFER.name,
                goalId = laptop.id,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        )

        val list = SavingsGoalCalculator.buildGoalProgressList(listOf(vacation, laptop), expenses)
        assertEquals(2, list.size)

        val vacProg = list.first { it.goal.id == vacation.id }
        // 15_000 + 25_000 + 10_000 - 5_000 = 45_000
        assertEquals(45_000.0, vacProg.savedAmountRub, 0.01)
        assertEquals(35_000.0, vacProg.depositSumRub, 0.01)
        assertEquals(5_000.0, vacProg.withdrawSumRub, 0.01)
        assertEquals(55_000.0, vacProg.remainingToTargetRub, 0.01)
        assertEquals(0.45f, vacProg.progressRatio, 0.001f)
        assertFalse(vacProg.isCompleted)

        val laptopProg = list.first { it.goal.id == laptop.id }
        assertEquals(50_000.0, laptopProg.savedAmountRub, 0.01)
        assertEquals(0.0, laptopProg.remainingToTargetRub, 0.01)
        assertEquals(1.0f, laptopProg.progressRatio, 0.001f)
        assertTrue(laptopProg.isCompleted)

        assertEquals(95_000.0, SavingsGoalCalculator.calculateTotalSavedInGoals(listOf(vacation, laptop), expenses), 0.01)
    }

    @Test
    fun deletedAndPendingOperations_andDeletedGoals_areIgnored() {
        val activeGoal = SavingsGoalEntity(
            id = "goal-1",
            title = "Подушка",
            targetAmountRub = 200_000.0,
            initialAmountRub = 20_000.0
        )
        val deletedGoal = SavingsGoalEntity(
            id = "goal-deleted",
            title = "Старая цель",
            targetAmountRub = 50_000.0,
            initialAmountRub = 10_000.0,
            isDeleted = true
        )
        val archivedGoal = SavingsGoalEntity(
            id = "goal-archived",
            title = "Архивная цель",
            targetAmountRub = 30_000.0,
            initialAmountRub = 30_000.0,
            isArchived = true
        )

        val now = epochInNovember2026(12)
        val expenses = listOf(
            // Confirmed active deposit -> counted
            ExpenseEntity(
                id = "ok-dep",
                amountRub = 15_000.0,
                categoryName = SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT,
                operationType = OperationType.TRANSFER.name,
                goalId = activeGoal.id,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            ),
            // Soft-deleted deposit -> ignored
            ExpenseEntity(
                id = "del-dep",
                amountRub = 40_000.0,
                categoryName = SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT,
                operationType = OperationType.TRANSFER.name,
                goalId = activeGoal.id,
                isDeleted = true,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            ),
            // Soft-deleted withdraw -> ignored
            ExpenseEntity(
                id = "del-wd",
                amountRub = 5_000.0,
                categoryName = SavingsGoalCalculator.CATEGORY_GOAL_WITHDRAW,
                operationType = OperationType.TRANSFER.name,
                goalId = activeGoal.id,
                isDeleted = true,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            ),
            // Pending confirmation deposit -> ignored
            ExpenseEntity(
                id = "pend-dep",
                amountRub = 10_000.0,
                categoryName = SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT,
                operationType = OperationType.TRANSFER.name,
                goalId = activeGoal.id,
                isPendingConfirmation = true,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            ),
            // Deposit for deleted goal
            ExpenseEntity(
                id = "del-goal-dep",
                amountRub = 5_000.0,
                categoryName = SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT,
                operationType = OperationType.TRANSFER.name,
                goalId = deletedGoal.id,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        )

        assertEquals(35_000.0, SavingsGoalCalculator.calculateGoalSavedAmount(activeGoal, expenses), 0.01)
        assertEquals(0.0, SavingsGoalCalculator.calculateGoalSavedAmount(deletedGoal, expenses), 0.01)

        val visibleList = SavingsGoalCalculator.buildGoalProgressList(
            listOf(activeGoal, deletedGoal, archivedGoal),
            expenses
        )
        assertEquals(listOf("goal-1", "goal-archived"), visibleList.map { it.goal.id })

        // Archived goal does not lock free unallocated balance
        assertEquals(
            35_000.0,
            SavingsGoalCalculator.calculateTotalSavedInGoals(listOf(activeGoal, deletedGoal, archivedGoal), expenses),
            0.01
        )
    }

    @Test
    fun goalTransfers_doNotDistortMonthlyExpensesOrIncome_andUpdateReportBalances() {
        val now = epochInNovember2026(15)
        val window = PeriodCalculator.buildWindow(BudgetPeriod.MONTH, now)

        val goal = SavingsGoalEntity(
            id = "goal-auto",
            title = "Авто",
            emoji = "🚗",
            targetAmountRub = 500_000.0,
            initialAmountRub = 10_000.0
        )

        val expenses = listOf(
            ExpenseEntity(
                id = "salary",
                amountRub = 120_000.0,
                merchant = "Зарплата",
                categoryName = ExpenseCategory.INCOME_SALARY.name,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            ),
            ExpenseEntity(
                id = "groceries",
                amountRub = 20_000.0,
                merchant = "Пятёрочка",
                categoryName = ExpenseCategory.GROCERIES.name,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            ),
            ExpenseEntity(
                id = "goal-dep",
                amountRub = 30_000.0,
                merchant = "Пополнение копилки: Авто",
                categoryName = SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT,
                operationType = OperationType.TRANSFER.name,
                goalId = goal.id,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            ),
            ExpenseEntity(
                id = "goal-wd",
                amountRub = 5_000.0,
                merchant = "Снятие с копилки: Авто",
                categoryName = SavingsGoalCalculator.CATEGORY_GOAL_WITHDRAW,
                operationType = OperationType.TRANSFER.name,
                goalId = goal.id,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        )

        val report = AiBudgetAdvisor.buildInstantLocalReport(
            expenses = expenses,
            monthlyBudgetRub = 100_000.0,
            window = window,
            nowMs = now,
            savingsGoals = listOf(goal)
        )

        assertEquals(20_000.0, report.totalSpentThisMonthRub, 0.01)
        assertEquals(120_000.0, report.totalIncomeThisMonthRub, 0.01)
        assertEquals(1, report.totalExpenseTransactions)
        assertEquals(1, report.totalIncomeTransactions)

        // allTimeBalance = 120_000 - 20_000 = 100_000
        assertEquals(100_000.0, report.allTimeBalanceRub, 0.01)
        // totalSavedInGoals = 10_000 + 30_000 - 5_000 = 35_000
        assertEquals(35_000.0, report.totalSavedInGoalsRub, 0.01)
        // freeUnallocatedBalance = 100_000 - 35_000 = 65_000
        assertEquals(65_000.0, report.freeUnallocatedBalanceRub, 0.01)
    }

    @Test
    fun expenseEntityJson_goalIdOmittedWhenEmptyAndRestoredWhenSet() {
        // Legacy JSON without goalId
        val legacyJson = JSONObject().apply {
            put("id", "legacy-1")
            put("amountRub", 450.0)
            put("merchant", "Кофе")
            put("categoryName", ExpenseCategory.CAFES.name)
            put("createdAtEpochMs", 1_700_000_000_000L)
        }
        val legacyEntity = ExpenseEntity.fromJsonObject(legacyJson)
        assertNotNull(legacyEntity)
        assertEquals("", legacyEntity!!.goalId)
        assertFalse(legacyEntity.toJsonObject().has("goalId"))

        // Goal transfer JSON round-trip
        val goalDeposit = ExpenseEntity(
            id = "goal-tx-1",
            amountRub = 12_000.0,
            merchant = "Пополнение копилки: Отпуск",
            categoryName = SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT,
            operationType = OperationType.TRANSFER.name,
            goalId = "goal-vac",
            createdAtEpochMs = 1_700_000_000_000L,
            updatedAtEpochMs = 1_700_000_000_000L
        )
        val json = goalDeposit.toJsonObject()
        assertEquals("goal-vac", json.getString("goalId"))
        assertEquals("TRANSFER", json.getString("operationType"))

        val restored = ExpenseEntity.fromJsonObject(json)
        assertNotNull(restored)
        assertEquals("goal-vac", restored!!.goalId)
        assertEquals(OperationType.TRANSFER, restored.effectiveOperationType)
        assertEquals(SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT, restored.categoryName)
    }

    @Test
    fun familyConfigMerge_savingsGoalsBetweenTwoPhones_preservesEditsAndPropagatesSoftDelete() {
        val goalA = SavingsGoalEntity(
            id = "goal-vac",
            title = "Отпуск в Сочи",
            emoji = "✈️",
            targetAmountRub = 180_000.0,
            initialAmountRub = 20_000.0,
            isArchived = false,
            isDeleted = false,
            updatedAtEpochMs = 5_000L
        )
        val goalAStaleB = goalA.copy(
            title = "Отпуск",
            targetAmountRub = 150_000.0,
            updatedAtEpochMs = 2_000L
        )
        val goalFromB = SavingsGoalEntity(
            id = "goal-cushion",
            title = "Подушка",
            emoji = "🛡️",
            targetAmountRub = 300_000.0,
            initialAmountRub = 50_000.0,
            updatedAtEpochMs = 4_000L
        )

        val phoneA = FamilyConfig(savingsGoals = listOf(goalA))
        val phoneB = FamilyConfig(savingsGoals = listOf(goalAStaleB, goalFromB))

        val mergedAB = FamilyConfig.merge(phoneA, phoneB)
        val mergedBA = FamilyConfig.merge(phoneB, phoneA)

        for (merged in listOf(mergedAB, mergedBA)) {
            assertEquals(2, merged.savingsGoals.size)
            val mergedVac = merged.savingsGoals.first { it.id == "goal-vac" }
            val mergedCushion = merged.savingsGoals.first { it.id == "goal-cushion" }
            assertEquals("Отпуск в Сочи", mergedVac.title)
            assertEquals(180_000.0, mergedVac.targetAmountRub, 0.01)
            assertEquals(goalFromB, mergedCushion)
        }

        // Round-trip JSON serialization
        val roundTripped = FamilyConfig.fromJsonString(mergedAB.toJsonObject().toString())
        assertNotNull(roundTripped)
        assertTrue(mergedAB.isEquivalentTo(roundTripped!!))
        assertEquals(2, roundTripped.savingsGoals.size)

        // Legacy config_v1.json without savingsGoals parses cleanly
        val legacyConfig = FamilyConfig.fromJsonObject(
            JSONObject().apply {
                put("schemaVersion", 1)
                put("monthlyBudgetRub", 200_000.0)
                put("categories", JSONArray())
            }
        )
        assertNotNull(legacyConfig)
        assertTrue(legacyConfig!!.savingsGoals.isEmpty())

        // Invalid goal without id returns null
        assertNull(SavingsGoalEntity.fromJsonObject(JSONObject().put("id", "  ")))

        // Soft-delete from Phone B with newer timestamp propagates
        val deletedFromB = goalFromB.copy(isDeleted = true, updatedAtEpochMs = 9_000L)
        val afterDelete = FamilyConfig.merge(
            local = mergedAB,
            remote = FamilyConfig(savingsGoals = listOf(deletedFromB))
        )
        assertTrue(afterDelete.savingsGoals.first { it.id == "goal-cushion" }.isDeleted)
    }
}
