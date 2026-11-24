package ru.pokanevich.familybudget.data

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlin.math.min

data class RecurringScheduleResult(
    val generatedExpenses: List<ExpenseEntity>,
    val updatedPayments: List<RecurringPaymentEntity>
)

/**
 * Чистый планировщик регулярных платежей и подписок без дублей на двух телефонах.
 *
 * Для автосоздаваемых операций формирует детерминированный ID `"rec_${payment.id}_${currentMonthKey}"`
 * и детерминированный timestamp даты списания, поэтому два телефона при независимом запуске
 * сгенерируют одну и ту же запись (а уже подтверждённая/удалённая на одном телефоне запись
 * с более свежим `updatedAtEpochMs` не будет перезаписана вторым телефоном).
 */
object RecurringPaymentScheduler {

    const val SOURCE_TYPE_RECURRING = "RECURRING"

    fun periodKeyFor(yearMonth: YearMonth): String =
        String.format(Locale.US, "%04d-%02d", yearMonth.year, yearMonth.monthValue)

    fun periodKeyFor(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val localDate = Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalDate()
        return periodKeyFor(YearMonth.from(localDate))
    }

    fun effectiveDayOfMonth(dayOfMonth: Int, yearMonth: YearMonth): Int =
        min(dayOfMonth.coerceIn(1, 31), yearMonth.lengthOfMonth())

    fun dueDateInMonth(dayOfMonth: Int, yearMonth: YearMonth): LocalDate =
        yearMonth.atDay(effectiveDayOfMonth(dayOfMonth, yearMonth))

    fun deterministicExpenseId(paymentId: String, periodKey: String): String =
        "rec_${paymentId}_${periodKey}"

    fun scheduledEpochMs(
        dayOfMonth: Int,
        yearMonth: YearMonth,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long = dueDateInMonth(dayOfMonth, yearMonth)
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()

    fun evaluateDuePayments(
        payments: List<RecurringPaymentEntity>,
        existingExpenseIds: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
        defaultAuthorName: String = "Евгений",
        zoneId: ZoneId = ZoneId.systemDefault()
    ): RecurringScheduleResult {
        if (payments.isEmpty()) {
            return RecurringScheduleResult(emptyList(), emptyList())
        }

        val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        val currentMonth = YearMonth.from(today)
        val currentMonthKey = periodKeyFor(currentMonth)

        val generatedExpenses = mutableListOf<ExpenseEntity>()
        val updatedPayments = mutableListOf<RecurringPaymentEntity>()

        for (payment in payments) {
            if (payment.id.isBlank()) continue
            if (payment.isDeleted || !payment.isActive) continue
            if (!payment.autoCreateInFeed) continue
            if (payment.amountRub <= 0.0 || !payment.amountRub.isFinite()) continue
            if (payment.isPaidInPeriod(currentMonthKey)) continue

            val dueDay = effectiveDayOfMonth(payment.dayOfMonth, currentMonth)
            if (today.dayOfMonth < dueDay) continue

            val deterministicId = deterministicExpenseId(payment.id, currentMonthKey)
            val scheduledMs = scheduledEpochMs(payment.dayOfMonth, currentMonth, zoneId)

            if (deterministicId !in existingExpenseIds) {
                val effectiveOp = payment.effectiveOperationType
                val resolvedCategoryId = if (effectiveOp == OperationType.TRANSFER) {
                    ExpenseCategory.OTHER.name
                } else {
                    payment.categoryId.ifBlank { ExpenseCategory.OTHER.name }
                }

                generatedExpenses.add(
                    ExpenseEntity(
                        id = deterministicId,
                        amountRub = payment.amountRub,
                        merchant = payment.title.trim().ifBlank { "Регулярный платёж" },
                        categoryName = resolvedCategoryId,
                        authorName = defaultAuthorName,
                        sourceType = SOURCE_TYPE_RECURRING,
                        isPendingConfirmation = true,
                        isDeleted = false,
                        isSynced = false,
                        createdAtEpochMs = scheduledMs,
                        updatedAtEpochMs = scheduledMs,
                        note = "Регулярный платёж ($dueDay-е число)",
                        operationType = effectiveOp.name
                    )
                )
            }

            updatedPayments.add(
                payment.copy(
                    lastPaidPeriodKey = currentMonthKey,
                    updatedAtEpochMs = maxOf(nowMs, scheduledMs, payment.updatedAtEpochMs + 1L)
                )
            )
        }

        return RecurringScheduleResult(
            generatedExpenses = generatedExpenses,
            updatedPayments = updatedPayments
        )
    }

    fun markPaidForMonth(
        payment: RecurringPaymentEntity,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): RecurringPaymentEntity {
        val currentMonthKey = periodKeyFor(nowMs, zoneId)
        if (payment.lastPaidPeriodKey == currentMonthKey) return payment
        return payment.copy(
            lastPaidPeriodKey = currentMonthKey,
            updatedAtEpochMs = maxOf(nowMs, payment.updatedAtEpochMs + 1L)
        )
    }

    fun clearPaidForMonth(
        payment: RecurringPaymentEntity,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): RecurringPaymentEntity {
        val currentMonthKey = periodKeyFor(nowMs, zoneId)
        if (payment.lastPaidPeriodKey != currentMonthKey) return payment
        return payment.copy(
            lastPaidPeriodKey = "",
            updatedAtEpochMs = maxOf(nowMs, payment.updatedAtEpochMs + 1L)
        )
    }
}
