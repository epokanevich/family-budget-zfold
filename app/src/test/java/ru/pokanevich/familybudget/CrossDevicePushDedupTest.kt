package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.data.BudgetRepository
import ru.pokanevich.familybudget.data.ExpenseEntity

class CrossDevicePushDedupTest {

    private val baseTime = 1_700_000_000_000L

    private fun push(
        id: String,
        amount: Double,
        source: String,
        merchant: String = "",
        offsetMs: Long = 0L,
        pending: Boolean = true,
        synced: Boolean = true,
        author: String = "Евгений",
        originDeviceId: String = "",
        categoryName: String = "OTHER",
        rawText: String = ""
    ) = ExpenseEntity(
        id = id,
        amountRub = amount,
        merchant = merchant,
        categoryName = categoryName,
        authorName = author,
        sourceType = source,
        isPendingConfirmation = pending,
        isSynced = synced,
        createdAtEpochMs = baseTime + offsetMs,
        updatedAtEpochMs = baseTime + offsetMs,
        rawPushOrQrText = rawText,
        dedupSignature = BudgetRepository.buildAmountSignature(amount),
        originDeviceId = originDeviceId
    )

    @Test
    fun `mirpay and sber pushes within 90s keep mirpay and soft-delete sber`() {
        val mirPay = push("mirpay-1", 540.0, "PUSH_MIRPAY", merchant = "Пятёрочка", author = "Анжелика")
        val sber = push("sber-1", 540.0, "PUSH_SBER", merchant = "PYATEROCHKA", offsetMs = 4_000L)
        val map = mutableMapOf(mirPay.id to mirPay, sber.id to sber)

        val losers = BudgetRepository.deduplicateCrossDevicePushExpenses(map, nowMs = baseTime + 10_000L)

        assertEquals(1, losers.size)
        assertEquals("sber-1", losers.first().id)
        assertTrue(map.getValue("sber-1").isDeleted)
        assertFalse(map.getValue("mirpay-1").isDeleted)
    }

    @Test
    fun `dedup losers are not marked synced before successful PUT`() {
        val mirPay = push("mirpay-s", 540.0, "PUSH_MIRPAY", merchant = "Пятёрочка")
        val sber = push("sber-s", 540.0, "PUSH_SBER", merchant = "PYATEROCHKA", offsetMs = 3_000L)
        val map = mutableMapOf(mirPay.id to mirPay, sber.id to sber)

        val losers = BudgetRepository.deduplicateCrossDevicePushExpenses(map, nowMs = baseTime + 10_000L)

        assertEquals(1, losers.size)
        assertTrue(losers.first().isDeleted)
        assertFalse("loser must stay isSynced=false until PUT 2xx", losers.first().isSynced)
    }

    @Test
    fun `transliterated merchants with bank suffixes are deduplicated`() {
        val mirPay = push("mirpay-t", 1650.0, "PUSH_MIRPAY", merchant = "Пятёрочка")
        val sber = push("sber-t", 1650.0, "PUSH_SBER", merchant = "PYATEROCHKA 2154 MOSCOW RUS", offsetMs = 6_000L)
        val map = mutableMapOf(mirPay.id to mirPay, sber.id to sber)

        val losers = BudgetRepository.deduplicateCrossDevicePushExpenses(map, nowMs = baseTime + 10_000L)

        assertEquals(listOf("sber-t"), losers.map { it.id })
        assertFalse(map.getValue("mirpay-t").isDeleted)
    }

    @Test
    fun `same amount in different stores on both phones is not deduplicated`() {
        // 50 ₽ проезд у Евгения (Сбер) и 50 ₽ вода у Анжелики (Mir Pay) почти одновременно
        val transport = push("sber-transport", 50.0, "PUSH_SBER", merchant = "MOSGORTRANS")
        val water = push(
            "mirpay-water", 50.0, "PUSH_MIRPAY", merchant = "Вода Родник",
            offsetMs = 15_000L, author = "Анжелика"
        )
        val map = mutableMapOf(transport.id to transport, water.id to water)

        val losers = BudgetRepository.deduplicateCrossDevicePushExpenses(map, nowMs = baseTime + 20_000L)

        assertTrue(losers.isEmpty())
        assertFalse(map.getValue("sber-transport").isDeleted)
        assertFalse(map.getValue("mirpay-water").isDeleted)
    }

    @Test
    fun `generic mirpay merchant falls back to amount and time match`() {
        val mirPay = push("mirpay-g", 777.0, "PUSH_MIRPAY", merchant = "Оплата через Mir Pay")
        val sber = push("sber-g", 777.0, "PUSH_SBER", merchant = "MAGNIT MM", offsetMs = 2_000L)
        val map = mutableMapOf(mirPay.id to mirPay, sber.id to sber)

        val losers = BudgetRepository.deduplicateCrossDevicePushExpenses(map, nowMs = baseTime + 10_000L)

        assertEquals(listOf("sber-g"), losers.map { it.id })
    }

    @Test
    fun `confirmed sber wins over pending mirpay within 90s`() {
        val mirPayPending = push("mirpay-2", 1200.0, "PUSH_MIRPAY", pending = true)
        val sberConfirmed = push("sber-2", 1200.0, "PUSH_SBER", offsetMs = 5_000L, pending = false)
        val map = mutableMapOf(mirPayPending.id to mirPayPending, sberConfirmed.id to sberConfirmed)

        val losers = BudgetRepository.deduplicateCrossDevicePushExpenses(map, nowMs = baseTime + 10_000L)

        assertEquals(1, losers.size)
        assertEquals("mirpay-2", losers.first().id)
        assertTrue(map.getValue("mirpay-2").isDeleted)
        assertFalse(map.getValue("sber-2").isDeleted)
    }

    @Test
    fun `same source type within 90s is not deduplicated across devices`() {
        val sber1 = push("sber-a", 55.0, "PUSH_SBER")
        val sber2 = push("sber-b", 55.0, "PUSH_SBER", offsetMs = 20_000L)
        val map = mutableMapOf(sber1.id to sber1, sber2.id to sber2)

        val losers = BudgetRepository.deduplicateCrossDevicePushExpenses(map, nowMs = baseTime + 30_000L)

        assertTrue(losers.isEmpty())
        assertFalse(map.getValue("sber-a").isDeleted)
        assertFalse(map.getValue("sber-b").isDeleted)
    }

    @Test
    fun `mirpay and sber pushes more than 180s apart are both kept`() {
        val mirPay = push("mirpay-old", 300.0, "PUSH_MIRPAY")
        val sber = push("sber-new", 300.0, "PUSH_SBER", offsetMs = 185_000L)
        val map = mutableMapOf(mirPay.id to mirPay, sber.id to sber)

        val losers = BudgetRepository.deduplicateCrossDevicePushExpenses(map, nowMs = baseTime + 200_000L)

        assertTrue(losers.isEmpty())
        assertFalse(map.getValue("mirpay-old").isDeleted)
        assertFalse(map.getValue("sber-new").isDeleted)
    }

    @Test
    fun `shared card sber pushes from two phones within 180s are deduplicated`() {
        val eugeneSber = push(
            id = "sber-eugene",
            amount = 1034.33,
            source = "PUSH_SBER",
            merchant = "Пятёрочка",
            offsetMs = 0L,
            author = "Евгений",
            originDeviceId = "device-fold-eugene",
            categoryName = "GROCERIES"
        )
        val wifeSber = push(
            id = "sber-wife",
            amount = 1034.33,
            source = "PUSH_SBER",
            merchant = "PYATEROCHKA 2154",
            offsetMs = 120_000L,
            author = "Анжелика",
            originDeviceId = "device-phone-wife",
            categoryName = "OTHER"
        )
        val map = mutableMapOf(eugeneSber.id to eugeneSber, wifeSber.id to wifeSber)

        val losers = BudgetRepository.deduplicateCrossDevicePushExpenses(map, nowMs = baseTime + 150_000L)

        assertEquals(listOf("sber-wife"), losers.map { it.id })
        assertTrue(map.getValue("sber-wife").isDeleted)
        assertFalse(map.getValue("sber-eugene").isDeleted)
    }

    @Test
    fun `shared card dedup enriches generic mirpay winner with sber merchant and category`() {
        val mirPayGeneric = push(
            id = "mirpay-gen",
            amount = 767.0,
            source = "PUSH_MIRPAY",
            merchant = "Оплата через Mir Pay",
            author = "Анжелика",
            originDeviceId = "device-wife",
            categoryName = "OTHER"
        )
        val sberRich = push(
            id = "sber-rich",
            amount = 767.0,
            source = "PUSH_SBER",
            merchant = "ВкусВилл",
            offsetMs = 8_000L,
            author = "Евгений",
            originDeviceId = "device-eugene",
            categoryName = "GROCERIES"
        )

        val plan = BudgetRepository.buildSyncMergePlan(
            localList = listOf(mirPayGeneric),
            remoteMap = mapOf(sberRich.id to sberRich),
            remoteExists = true,
            nowMs = baseTime + 20_000L
        )

        assertEquals(listOf("sber-rich"), plan.dedupedLosers.map { it.id })
        val winner = plan.mergedMap.getValue("mirpay-gen")
        assertFalse(winner.isDeleted)
        assertEquals("ВкусВилл", winner.merchant)
        assertEquals("GROCERIES", winner.categoryName)
        assertTrue(plan.toUpsertLocally.any { it.id == "mirpay-gen" && it.merchant == "ВкусВилл" })
        assertNotNull(plan.pushedVersions["mirpay-gen"])
    }

    @Test
    fun `shared card dedup handles double epsilon drift and is order independent`() {
        val a = push("id-a", 199.0000001, "PUSH_SBER", merchant = "Берёза у дома", author = "Евгений", originDeviceId = "dev-1")
        val b = push("id-b", 198.9999999, "PUSH_SBER", merchant = "Берёза у дома", offsetMs = 15_000L, author = "Анжелика", originDeviceId = "dev-2")

        val mapAB = linkedMapOf(a.id to a, b.id to b)
        val mapBA = linkedMapOf(b.id to b, a.id to a)

        val losersAB = BudgetRepository.deduplicateCrossDevicePushExpenses(mapAB, nowMs = baseTime + 30_000L)
        val losersBA = BudgetRepository.deduplicateCrossDevicePushExpenses(mapBA, nowMs = baseTime + 30_000L)

        assertEquals(losersAB.map { it.id }, losersBA.map { it.id })
        assertEquals(1, losersAB.size)
    }

    // ---------- Полный цикл синка: неудачный PUT не приводит к повторному удалению ----------

    /** Минимальная in-memory модель таблицы expenses (upsert + markSyncedIfUnchanged). */
    private class FakeLocalDb(initial: List<ExpenseEntity>) {
        val rows = initial.associateBy { it.id }.toMutableMap()
        fun all() = rows.values.toList()
        fun unsynced() = rows.values.filter { !it.isSynced }
        fun upsertAll(items: List<ExpenseEntity>) = items.forEach { rows[it.id] = it }
        fun markSyncedIfUnchanged(versions: Map<String, Long>) {
            for ((id, updatedAt) in versions) {
                val row = rows[id] ?: continue
                if (!row.isSynced && row.updatedAtEpochMs == updatedAt) rows[id] = row.copy(isSynced = true)
            }
        }
    }

    @Test
    fun `failed PUT keeps loser unsynced and does not delete again on retry`() {
        // Локально: свой Mir Pay (уже в облаке). В облаке: Сбер с другого телефона на ту же покупку.
        val mirPay = push("mirpay-p", 540.0, "PUSH_MIRPAY", merchant = "Пятёрочка", synced = true)
        val sberRemote = push("sber-p", 540.0, "PUSH_SBER", merchant = "PYATEROCHKA", offsetMs = 5_000L)
        val remote = mapOf(mirPay.id to mirPay, sberRemote.id to sberRemote)
        val local = FakeLocalDb(listOf(mirPay))

        // Цикл 1: дедуп помечает Сбер удалённым, но PUT падает (сеть / 5xx)
        val plan1 = BudgetRepository.buildSyncMergePlan(local.all(), remote, remoteExists = true, nowMs = baseTime + 60_000L)
        assertEquals(listOf("sber-p"), plan1.dedupedLosers.map { it.id })
        assertTrue(plan1.needCloudPush)
        assertTrue(plan1.mergedMap.getValue("sber-p").isDeleted)
        local.upsertAll(plan1.toUpsertLocally)
        // PUT failed -> markSyncedIfUnchanged НЕ вызывается

        val savedLoser = local.rows.getValue("sber-p")
        assertTrue(savedLoser.isDeleted)
        assertFalse("loser must remain unsynced after failed PUT", savedLoser.isSynced)
        assertTrue(
            "304 fast-path must not skip the pending deletion",
            local.unsynced().any { it.id == "sber-p" }
        )

        // Цикл 2 (облако не менялось): удаление повторно отправляется, но не создаётся заново
        val plan2 = BudgetRepository.buildSyncMergePlan(local.all(), remote, remoteExists = true, nowMs = baseTime + 120_000L)
        assertTrue("no repeated deletion", plan2.dedupedLosers.isEmpty())
        assertTrue(plan2.needCloudPush)
        assertTrue(plan2.mergedMap.getValue("sber-p").isDeleted)
        assertFalse(plan2.mergedMap.getValue("mirpay-p").isDeleted)
        assertEquals(savedLoser.updatedAtEpochMs, plan2.mergedMap.getValue("sber-p").updatedAtEpochMs)
        assertEquals(savedLoser.updatedAtEpochMs, plan2.pushedVersions["sber-p"])

        // PUT 2xx -> только теперь переводим в isSynced = true
        local.upsertAll(plan2.toUpsertLocally)
        local.markSyncedIfUnchanged(plan2.pushedVersions)
        assertTrue(local.unsynced().isEmpty())

        // Цикл 3: облако уже содержит удаление — пушить больше нечего
        val remoteAfterPut = plan2.mergedMap
        val plan3 = BudgetRepository.buildSyncMergePlan(local.all(), remoteAfterPut, remoteExists = true, nowMs = baseTime + 180_000L)
        assertFalse(plan3.needCloudPush)
        assertTrue(plan3.dedupedLosers.isEmpty())
    }

    @Test
    fun `local edit made during PUT is not marked synced`() {
        val manual = ExpenseEntity(
            id = "manual-1",
            amountRub = 100.0,
            isSynced = false,
            createdAtEpochMs = baseTime,
            updatedAtEpochMs = baseTime
        )
        val local = FakeLocalDb(listOf(manual))
        val plan = BudgetRepository.buildSyncMergePlan(local.all(), emptyMap(), remoteExists = true, nowMs = baseTime + 1_000L)
        assertTrue(plan.needCloudPush)
        assertEquals(baseTime, plan.pushedVersions["manual-1"])
        assertFalse(plan.mergedMap.isEmpty())

        // Пользователь отредактировал запись, пока шёл PUT
        local.upsertAll(listOf(manual.copy(amountRub = 150.0, updatedAtEpochMs = baseTime + 2_000L)))
        local.markSyncedIfUnchanged(plan.pushedVersions)

        val row = local.rows.getValue("manual-1")
        assertFalse(row.isSynced)
        assertEquals(150.0, row.amountRub, 0.0)
    }

    @Test
    fun `remote newer record overwrites local and is stored as synced`() {
        val localOld = ExpenseEntity(id = "x", amountRub = 10.0, isSynced = true, createdAtEpochMs = baseTime, updatedAtEpochMs = baseTime)
        val remoteNew = localOld.copy(amountRub = 20.0, updatedAtEpochMs = baseTime + 5_000L)
        val plan = BudgetRepository.buildSyncMergePlan(listOf(localOld), mapOf("x" to remoteNew), remoteExists = true)
        assertFalse(plan.needCloudPush)
        val upsert = plan.toUpsertLocally.single()
        assertTrue(upsert.isSynced)
        assertEquals(20.0, upsert.amountRub, 0.0)
        assertNull(plan.pushedVersions["x"])
        assertNotNull(plan.mergedMap["x"])
    }

    @Test
    fun `mirpay and tbank pushes within 90s keep mirpay and soft-delete tbank`() {
        val mirPay = push("mirpay-tb", 1250.0, "PUSH_MIRPAY", merchant = "Пятёрочка", author = "Анжелика")
        val tbank = push("tbank-1", 1250.0, "PUSH_TBANK", merchant = "PYATEROCHKA", offsetMs = 5_000L)
        val map = mutableMapOf(mirPay.id to mirPay, tbank.id to tbank)

        val losers = BudgetRepository.deduplicateCrossDevicePushExpenses(map, nowMs = baseTime + 15_000L)

        assertEquals(listOf("tbank-1"), losers.map { it.id })
        assertTrue(map.getValue("tbank-1").isDeleted)
        assertFalse(map.getValue("mirpay-tb").isDeleted)
    }

    @Test
    fun `confirmed tbank wins over pending mirpay within 90s`() {
        val mirPayPending = push("mirpay-tb-p", 890.0, "PUSH_MIRPAY", merchant = "Магнит", pending = true)
        val tbankConfirmed = push("tbank-2", 890.0, "PUSH_TBANK", merchant = "MAGNIT MM", offsetMs = 4_000L, pending = false)
        val map = mutableMapOf(mirPayPending.id to mirPayPending, tbankConfirmed.id to tbankConfirmed)

        val losers = BudgetRepository.deduplicateCrossDevicePushExpenses(map, nowMs = baseTime + 15_000L)

        assertEquals(listOf("mirpay-tb-p"), losers.map { it.id })
        assertTrue(map.getValue("mirpay-tb-p").isDeleted)
        assertFalse(map.getValue("tbank-2").isDeleted)
    }

    @Test
    fun `mirpay pairs with alfa vtb and ozon pushes within 90s`() {
        val mirPayAlfa = push("mp-a", 450.0, "PUSH_MIRPAY", merchant = "ВкусВилл")
        val alfa = push("alfa-1", 450.0, "PUSH_ALFA", merchant = "VKUSVILL", offsetMs = 3_000L)

        val mirPayVtb = push("mp-v", 650.0, "PUSH_MIRPAY", merchant = "Аптека Вита", offsetMs = 200_000L)
        val vtb = push("vtb-1", 650.0, "PUSH_VTB", merchant = "APTEKA VITA", offsetMs = 204_000L)

        val mirPayOzon = push("mp-o", 1450.0, "PUSH_MIRPAY", merchant = "Ozon.ru", offsetMs = 400_000L)
        val ozon = push("ozon-1", 1450.0, "PUSH_OZON", merchant = "OZON", offsetMs = 406_000L)

        val map = mutableMapOf(
            mirPayAlfa.id to mirPayAlfa, alfa.id to alfa,
            mirPayVtb.id to mirPayVtb, vtb.id to vtb,
            mirPayOzon.id to mirPayOzon, ozon.id to ozon
        )

        val losers = BudgetRepository.deduplicateCrossDevicePushExpenses(map, nowMs = baseTime + 450_000L)

        assertEquals(setOf("alfa-1", "vtb-1", "ozon-1"), losers.map { it.id }.toSet())
        assertFalse(map.getValue("mp-a").isDeleted)
        assertFalse(map.getValue("mp-v").isDeleted)
        assertFalse(map.getValue("mp-o").isDeleted)
    }

    @Test
    fun `different bank pushes without mirpay are not deduplicated`() {
        val sber = push("sber-only", 540.0, "PUSH_SBER", merchant = "Пятёрочка")
        val tbank = push("tbank-only", 540.0, "PUSH_TBANK", merchant = "Пятёрочка", offsetMs = 10_000L)
        val map = mutableMapOf(sber.id to sber, tbank.id to tbank)

        val losers = BudgetRepository.deduplicateCrossDevicePushExpenses(map, nowMs = baseTime + 20_000L)

        assertTrue(losers.isEmpty())
        assertFalse(map.getValue("sber-only").isDeleted)
        assertFalse(map.getValue("tbank-only").isDeleted)
    }
}
