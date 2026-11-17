package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.data.BudgetRepository
import ru.pokanevich.familybudget.data.ExpenseEntity

class CrossDevicePushDedupTest {

    @Test
    fun `mirpay and sber pushes within 90s keep mirpay and soft-delete sber`() {
        val baseTime = 1_700_000_000_000L
        val mirPay = ExpenseEntity(
            id = "mirpay-1",
            amountRub = 540.0,
            merchant = "Пятёрочка",
            authorName = "Анжелика",
            sourceType = "PUSH_MIRPAY",
            isPendingConfirmation = true,
            createdAtEpochMs = baseTime,
            updatedAtEpochMs = baseTime,
            dedupSignature = "AMT_540.00"
        )
        val sber = ExpenseEntity(
            id = "sber-1",
            amountRub = 540.0,
            merchant = "PYATEROCHKA",
            authorName = "Евгений",
            sourceType = "PUSH_SBER",
            isPendingConfirmation = true,
            createdAtEpochMs = baseTime + 4_000L,
            updatedAtEpochMs = baseTime + 4_000L,
            dedupSignature = "AMT_540.00"
        )
        val map = mutableMapOf(mirPay.id to mirPay, sber.id to sber)

        val losers = BudgetRepository.deduplicateCrossDevicePushExpenses(map, nowMs = baseTime + 10_000L)

        assertEquals(1, losers.size)
        assertEquals("sber-1", losers.first().id)
        assertTrue(map.getValue("sber-1").isDeleted)
        assertFalse(map.getValue("mirpay-1").isDeleted)
    }

    @Test
    fun `confirmed sber wins over pending mirpay within 90s`() {
        val baseTime = 1_700_000_000_000L
        val mirPayPending = ExpenseEntity(
            id = "mirpay-2",
            amountRub = 1200.0,
            sourceType = "PUSH_MIRPAY",
            isPendingConfirmation = true,
            createdAtEpochMs = baseTime,
            updatedAtEpochMs = baseTime,
            dedupSignature = "AMT_1200.00"
        )
        val sberConfirmed = ExpenseEntity(
            id = "sber-2",
            amountRub = 1200.0,
            sourceType = "PUSH_SBER",
            isPendingConfirmation = false,
            createdAtEpochMs = baseTime + 5_000L,
            updatedAtEpochMs = baseTime + 5_000L,
            dedupSignature = "AMT_1200.00"
        )
        val map = mutableMapOf(mirPayPending.id to mirPayPending, sberConfirmed.id to sberConfirmed)

        val losers = BudgetRepository.deduplicateCrossDevicePushExpenses(map, nowMs = baseTime + 10_000L)

        assertEquals(1, losers.size)
        assertEquals("mirpay-2", losers.first().id)
        assertTrue(map.getValue("mirpay-2").isDeleted)
        assertFalse(map.getValue("sber-2").isDeleted)
    }

    @Test
    fun `same source type within 90s is not deduplicated across devices`() {
        val baseTime = 1_700_000_000_000L
        val sber1 = ExpenseEntity(
            id = "sber-a",
            amountRub = 55.0,
            sourceType = "PUSH_SBER",
            createdAtEpochMs = baseTime,
            updatedAtEpochMs = baseTime,
            dedupSignature = "AMT_55.00"
        )
        val sber2 = ExpenseEntity(
            id = "sber-b",
            amountRub = 55.0,
            sourceType = "PUSH_SBER",
            createdAtEpochMs = baseTime + 20_000L,
            updatedAtEpochMs = baseTime + 20_000L,
            dedupSignature = "AMT_55.00"
        )
        val map = mutableMapOf(sber1.id to sber1, sber2.id to sber2)

        val losers = BudgetRepository.deduplicateCrossDevicePushExpenses(map, nowMs = baseTime + 30_000L)

        assertTrue(losers.isEmpty())
        assertFalse(map.getValue("sber-a").isDeleted)
        assertFalse(map.getValue("sber-b").isDeleted)
    }

    @Test
    fun `mirpay and sber pushes more than 90s apart are both kept`() {
        val baseTime = 1_700_000_000_000L
        val mirPay = ExpenseEntity(
            id = "mirpay-old",
            amountRub = 300.0,
            sourceType = "PUSH_MIRPAY",
            createdAtEpochMs = baseTime,
            updatedAtEpochMs = baseTime,
            dedupSignature = "AMT_300.00"
        )
        val sber = ExpenseEntity(
            id = "sber-new",
            amountRub = 300.0,
            sourceType = "PUSH_SBER",
            createdAtEpochMs = baseTime + 95_000L,
            updatedAtEpochMs = baseTime + 95_000L,
            dedupSignature = "AMT_300.00"
        )
        val map = mutableMapOf(mirPay.id to mirPay, sber.id to sber)

        val losers = BudgetRepository.deduplicateCrossDevicePushExpenses(map, nowMs = baseTime + 100_000L)

        assertTrue(losers.isEmpty())
        assertFalse(map.getValue("mirpay-old").isDeleted)
        assertFalse(map.getValue("sber-new").isDeleted)
    }
}
