package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.data.BudgetRepository
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity

class FirestoreSyncMergeTest {

    private val baseNow = 1_763_500_000_000L

    @Test
    fun `incremental delta sync skips pushing already-synced local rows outside remote delta`() {
        val syncedOld = ExpenseEntity(
            id = "synced-1",
            amountRub = 1200.0,
            merchant = "Пятёрочка",
            categoryName = ExpenseCategory.GROCERIES.name,
            isSynced = true,
            createdAtEpochMs = baseNow - 86_400_000L,
            updatedAtEpochMs = baseNow - 86_400_000L
        )
        val localUnsynced = ExpenseEntity(
            id = "unsynced-2",
            amountRub = 500.0,
            merchant = "Кофе",
            categoryName = ExpenseCategory.CAFES.name,
            isSynced = false,
            createdAtEpochMs = baseNow - 5_000L,
            updatedAtEpochMs = baseNow - 5_000L
        )
        val remoteNew = ExpenseEntity(
            id = "remote-3",
            amountRub = 2300.0,
            merchant = "Аптека",
            categoryName = ExpenseCategory.HEALTH.name,
            isSynced = true,
            createdAtEpochMs = baseNow - 2_000L,
            updatedAtEpochMs = baseNow - 2_000L
        )

        val plan = BudgetRepository.buildIncrementalSyncMergePlan(
            localList = listOf(syncedOld, localUnsynced),
            remoteDeltaMap = mapOf(remoteNew.id to remoteNew),
            remoteExists = true,
            isFullSnapshot = false,
            nowMs = baseNow
        )

        assertTrue(plan.needCloudPush)
        assertEquals(setOf("unsynced-2"), plan.pushedVersions.keys)
        assertFalse(plan.mergedMap.containsKey("synced-1"))
        assertTrue(plan.toUpsertLocally.any { it.id == "remote-3" && it.isSynced })
    }

    @Test
    fun `incremental delta sync still deduplicates cross-device push against recent synced push`() {
        val localSyncedSber = ExpenseEntity(
            id = "sber-wife",
            amountRub = 990.0,
            merchant = "Пятёрочка",
            categoryName = ExpenseCategory.GROCERIES.name,
            authorName = "Анжелика",
            sourceType = "PUSH_SBER",
            isSynced = true,
            originDeviceId = "phone-wife",
            createdAtEpochMs = baseNow - 10_000L,
            updatedAtEpochMs = baseNow - 10_000L
        )
        val remoteMirPay = ExpenseEntity(
            id = "mirpay-eugene",
            amountRub = 990.0,
            merchant = "Оплата через Mir Pay",
            categoryName = ExpenseCategory.OTHER.name,
            authorName = "Евгений",
            sourceType = "PUSH_MIRPAY",
            isSynced = true,
            originDeviceId = "phone-eugene",
            createdAtEpochMs = baseNow - 6_000L,
            updatedAtEpochMs = baseNow - 6_000L
        )

        val plan = BudgetRepository.buildIncrementalSyncMergePlan(
            localList = listOf(localSyncedSber),
            remoteDeltaMap = mapOf(remoteMirPay.id to remoteMirPay),
            remoteExists = true,
            isFullSnapshot = false,
            nowMs = baseNow
        )

        assertTrue(plan.needCloudPush)
        assertEquals(listOf("sber-wife"), plan.dedupedLosers.map { it.id })
        assertTrue(plan.mergedMap.getValue("sber-wife").isDeleted)
        val winner = plan.mergedMap.getValue("mirpay-eugene")
        assertFalse(winner.isDeleted)
        assertEquals("Пятёрочка", winner.merchant)
        assertEquals(ExpenseCategory.GROCERIES.name, winner.categoryName)
    }
}
