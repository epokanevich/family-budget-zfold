package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.data.BudgetRepository
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.OperationType
import ru.pokanevich.familybudget.data.RecurringPaymentEntity
import ru.pokanevich.familybudget.data.RecurringPaymentScheduler
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class RecurringPaymentSchedulerTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    private fun epochMs(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        minute: Int = 0
    ): Long = LocalDateTime.of(year, month, day, hour, minute)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    @Test
    fun day31_inFebruary2026_clampsTo28th() {
        val feb2026 = YearMonth.of(2026, 2)
        assertEquals(28, RecurringPaymentScheduler.effectiveDayOfMonth(31, feb2026))

        val rentPayment = RecurringPaymentEntity(
            id = "rent-uuid-1",
            title = "Аренда",
            amountRub = 45_000.0,
            categoryId = ExpenseCategory.UTILITIES.name,
            operationType = OperationType.EXPENSE.name,
            dayOfMonth = 31,
            autoCreateInFeed = true,
            lastPaidPeriodKey = "2026-01",
            isActive = true,
            isDeleted = false,
            updatedAtEpochMs = epochMs(2026, 1, 31, 10, 0)
        )

        // 27 февраля 2026 — ещё не наступило 28-е число
        val beforeDue = RecurringPaymentScheduler.evaluateDuePayments(
            payments = listOf(rentPayment),
            nowMs = epochMs(2026, 2, 27, 23, 59),
            zoneId = zone
        )
        assertTrue(beforeDue.generatedExpenses.isEmpty())
        assertTrue(beforeDue.updatedPayments.isEmpty())

        // 28 февраля 2026 — последний день месяца, списание должно создаться
        val onDue = RecurringPaymentScheduler.evaluateDuePayments(
            payments = listOf(rentPayment),
            nowMs = epochMs(2026, 2, 28, 8, 15),
            zoneId = zone
        )
        assertEquals(1, onDue.generatedExpenses.size)
        assertEquals(1, onDue.updatedPayments.size)

        val generated = onDue.generatedExpenses.single()
        assertEquals("rec_rent-uuid-1_2026-02", generated.id)
        assertEquals(45_000.0, generated.amountRub, 0.001)
        assertEquals("Аренда", generated.merchant)
        assertEquals(ExpenseCategory.UTILITIES.name, generated.categoryName)
        assertTrue(generated.isPendingConfirmation)
        assertEquals(RecurringPaymentScheduler.SOURCE_TYPE_RECURRING, generated.sourceType)
        assertEquals(OperationType.EXPENSE, generated.effectiveOperationType)

        assertEquals("2026-02", onDue.updatedPayments.single().lastPaidPeriodKey)
    }

    @Test
    fun day31_inLeapFebruary2024_clampsTo29th_andAprilClampsTo30th() {
        val feb2024 = YearMonth.of(2024, 2)
        val apr2026 = YearMonth.of(2026, 4)
        assertEquals(29, RecurringPaymentScheduler.effectiveDayOfMonth(31, feb2024))
        assertEquals(30, RecurringPaymentScheduler.effectiveDayOfMonth(31, apr2026))

        val sub = RecurringPaymentEntity(
            id = "sub-31",
            title = "Подписка",
            amountRub = 599.0,
            categoryId = ExpenseCategory.LEISURE.name,
            dayOfMonth = 31,
            autoCreateInFeed = true,
            lastPaidPeriodKey = ""
        )

        // В високосном 2024 году 28 февраля — ещё рано
        val feb28 = RecurringPaymentScheduler.evaluateDuePayments(
            payments = listOf(sub),
            nowMs = epochMs(2024, 2, 28, 20, 0),
            zoneId = zone
        )
        assertTrue(feb28.generatedExpenses.isEmpty())

        // 29 февраля 2024 — срабатывает
        val feb29 = RecurringPaymentScheduler.evaluateDuePayments(
            payments = listOf(sub),
            nowMs = epochMs(2024, 2, 29, 9, 0),
            zoneId = zone
        )
        assertEquals(1, feb29.generatedExpenses.size)
        assertEquals("rec_sub-31_2024-02", feb29.generatedExpenses.single().id)
        assertEquals("2024-02", feb29.updatedPayments.single().lastPaidPeriodKey)

        // В апреле (30 дней) 30-го числа срабатывает с ключом 2026-04
        val apr30 = RecurringPaymentScheduler.evaluateDuePayments(
            payments = listOf(sub),
            nowMs = epochMs(2026, 4, 30, 10, 0),
            zoneId = zone
        )
        assertEquals(1, apr30.generatedExpenses.size)
        assertEquals("rec_sub-31_2026-04", apr30.generatedExpenses.single().id)
        assertEquals("2026-04", apr30.updatedPayments.single().lastPaidPeriodKey)
    }

    @Test
    fun deterministicId_preventsDuplicatesAcrossTwoPhones_andPreservesConfirmation() {
        val internet = RecurringPaymentEntity(
            id = "inet-uuid-42",
            title = "Ростелеком",
            amountRub = 950.0,
            categoryId = ExpenseCategory.UTILITIES.name,
            dayOfMonth = 15,
            autoCreateInFeed = true,
            lastPaidPeriodKey = "",
            updatedAtEpochMs = epochMs(2026, 11, 1, 12, 0)
        )

        // Телефон A запускается в 10:00, Телефон B (офлайн) в 16:40 того же дня
        val resPhoneA = RecurringPaymentScheduler.evaluateDuePayments(
            payments = listOf(internet),
            nowMs = epochMs(2026, 11, 15, 10, 0),
            defaultAuthorName = "Евгений",
            zoneId = zone
        )
        val resPhoneB = RecurringPaymentScheduler.evaluateDuePayments(
            payments = listOf(internet),
            nowMs = epochMs(2026, 11, 15, 16, 40),
            defaultAuthorName = "Анжелика",
            zoneId = zone
        )

        val expA = resPhoneA.generatedExpenses.single()
        val expB = resPhoneB.generatedExpenses.single()

        assertEquals("rec_inet-uuid-42_2026-11", expA.id)
        assertEquals(expA.id, expB.id)
        assertEquals(expA.createdAtEpochMs, expB.createdAtEpochMs)
        assertEquals(expA.updatedAtEpochMs, expB.updatedAtEpochMs)

        // Евгений на Телефоне A сразу подтвердил операцию и отправил в облако
        val confirmedOnPhoneA = expA.copy(
            isPendingConfirmation = false,
            updatedAtEpochMs = epochMs(2026, 11, 15, 10, 5),
            isSynced = true
        )

        // Когда Телефон B выходит в сеть, его локальный несynchronized черновик НЕ должен перезаписать подтверждение A
        val mergePlan = BudgetRepository.buildSyncMergePlan(
            localList = listOf(expB),
            remoteMap = mapOf(confirmedOnPhoneA.id to confirmedOnPhoneA),
            remoteExists = true,
            nowMs = epochMs(2026, 11, 15, 16, 45)
        )

        assertEquals(1, mergePlan.mergedMap.size)
        val merged = mergePlan.mergedMap.getValue("rec_inet-uuid-42_2026-11")
        assertFalse("Phone B must not revert Phone A's confirmation", merged.isPendingConfirmation)
        assertFalse("No cloud push needed when cloud already holds newer confirmed record", mergePlan.needCloudPush)
    }

    @Test
    fun skipsAlreadyPaidPeriodKey_andTriggersInNextMonth() {
        val payment = RecurringPaymentEntity(
            id = "music-1",
            title = "Яндекс Плюс",
            amountRub = 399.0,
            categoryId = ExpenseCategory.LEISURE.name,
            dayOfMonth = 10,
            autoCreateInFeed = true,
            lastPaidPeriodKey = "2026-11"
        )

        // В ноябре 2026 уже оплачено -> пропускается
        val novResult = RecurringPaymentScheduler.evaluateDuePayments(
            payments = listOf(payment),
            nowMs = epochMs(2026, 11, 20, 12, 0),
            zoneId = zone
        )
        assertTrue(novResult.generatedExpenses.isEmpty())
        assertTrue(novResult.updatedPayments.isEmpty())

        // В декабре 2026 10-го числа -> создаётся новый платёж за 2026-12
        val decResult = RecurringPaymentScheduler.evaluateDuePayments(
            payments = listOf(payment),
            nowMs = epochMs(2026, 12, 10, 9, 0),
            zoneId = zone
        )
        assertEquals(1, decResult.generatedExpenses.size)
        assertEquals("rec_music-1_2026-12", decResult.generatedExpenses.single().id)
        assertEquals("2026-12", decResult.updatedPayments.single().lastPaidPeriodKey)
    }

    @Test
    fun skipsGeneratingExpenseWhenDeterministicIdAlreadyExists_butUpdatesPeriodKey() {
        val payment = RecurringPaymentEntity(
            id = "gym-1",
            title = "Фитнес",
            amountRub = 3_500.0,
            categoryId = ExpenseCategory.LEISURE.name,
            dayOfMonth = 5,
            autoCreateInFeed = true,
            lastPaidPeriodKey = ""
        )

        val result = RecurringPaymentScheduler.evaluateDuePayments(
            payments = listOf(payment),
            existingExpenseIds = setOf("rec_gym-1_2026-11"),
            nowMs = epochMs(2026, 11, 17, 15, 0),
            zoneId = zone
        )

        assertTrue(result.generatedExpenses.isEmpty())
        assertEquals(1, result.updatedPayments.size)
        assertEquals("2026-11", result.updatedPayments.single().lastPaidPeriodKey)
    }

    @Test
    fun manualOnlyAndPausedAndDeletedPayments_doNotAutoCreate_andManualMarkWorks() {
        val forecastOnly = RecurringPaymentEntity(
            id = "forecast-1",
            title = "Садик",
            amountRub = 12_000.0,
            categoryId = ExpenseCategory.BABY.name,
            dayOfMonth = 5,
            autoCreateInFeed = false,
            lastPaidPeriodKey = "",
            updatedAtEpochMs = 1_000L
        )
        val paused = forecastOnly.copy(id = "paused-1", autoCreateInFeed = true, isActive = false)
        val deleted = forecastOnly.copy(id = "deleted-1", autoCreateInFeed = true, isDeleted = true)

        val now = epochMs(2026, 11, 17, 14, 0)
        val eval = RecurringPaymentScheduler.evaluateDuePayments(
            payments = listOf(forecastOnly, paused, deleted),
            nowMs = now,
            zoneId = zone
        )
        assertTrue(eval.generatedExpenses.isEmpty())
        assertTrue(eval.updatedPayments.isEmpty())

        // Ручная отметка «Оплачено в этом месяце»
        val markedPaid = RecurringPaymentScheduler.markPaidForMonth(forecastOnly, nowMs = now, zoneId = zone)
        assertEquals("2026-11", markedPaid.lastPaidPeriodKey)
        assertTrue(markedPaid.isPaidInPeriod("2026-11"))
        assertTrue(markedPaid.updatedAtEpochMs >= now)

        // Повторная отметка в том же месяце возвращает исходный объект без лишнего обновления
        assertEquals(markedPaid, RecurringPaymentScheduler.markPaidForMonth(markedPaid, nowMs = now + 10_000L, zoneId = zone))

        // Снятие отметки в текущем месяце
        val cleared = RecurringPaymentScheduler.clearPaidForMonth(markedPaid, nowMs = now + 20_000L, zoneId = zone)
        assertEquals("", cleared.lastPaidPeriodKey)
        assertFalse(cleared.isPaidInPeriod("2026-11"))
    }

    @Test
    fun incomeAndTransferRecurringPayments_preserveOperationType() {
        val salary = RecurringPaymentEntity(
            id = "salary-1",
            title = "Аванс",
            amountRub = 90_000.0,
            categoryId = ExpenseCategory.INCOME_SALARY.name,
            operationType = OperationType.INCOME.name,
            dayOfMonth = 15,
            autoCreateInFeed = true
        )
        val savingsTransfer = RecurringPaymentEntity(
            id = "save-1",
            title = "В копилку",
            amountRub = 15_000.0,
            categoryId = ExpenseCategory.OTHER.name,
            operationType = OperationType.TRANSFER.name,
            dayOfMonth = 15,
            autoCreateInFeed = true
        )

        val res = RecurringPaymentScheduler.evaluateDuePayments(
            payments = listOf(salary, savingsTransfer),
            nowMs = epochMs(2026, 11, 15, 11, 0),
            zoneId = zone
        )
        assertEquals(2, res.generatedExpenses.size)
        val salaryExp = res.generatedExpenses.first { it.id == "rec_salary-1_2026-11" }
        val transferExp = res.generatedExpenses.first { it.id == "rec_save-1_2026-11" }

        assertEquals(OperationType.INCOME, salaryExp.effectiveOperationType)
        assertTrue(salaryExp.isIncome)
        assertEquals(OperationType.TRANSFER, transferExp.effectiveOperationType)
        assertTrue(transferExp.isTransfer)
    }
}
