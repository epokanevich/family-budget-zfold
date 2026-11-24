package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.service.ParsedBankExpense
import ru.pokanevich.familybudget.service.PushNotificationDeduplicator

class PushNotificationDeduplicatorTest {

    private val baseTime = 1_794_920_000_000L

    private fun sampleParsed(amount: Double = 1034.33, merchant: String = "Пятёрочка") = ParsedBankExpense(
        amountRub = amount,
        merchant = merchant,
        sourceType = "PUSH_SBER",
        rawText = "СберБанк Онлайн Оплачена покупка в $merchant $amount ₽ — В запасе: 951 971,42 ₽"
    )

    @Test
    fun `updated sber notification in same slot with new postTime is deduplicated`() {
        val dedup = PushNotificationDeduplicator()
        val fp = PushNotificationDeduplicator.buildContentFingerprint(sampleParsed())

        assertTrue(dedup.markIfNew("0|ru.sberbankmobile|101|null|0", baseTime, fp, nowMs = baseTime))
        // Сбер обновил шторку спустя 1.5 сек (иконка/категория) — postTime сменился
        assertFalse(
            dedup.markIfNew("0|ru.sberbankmobile|101|null|0", baseTime + 1_500L, fp, nowMs = baseTime + 1_500L)
        )
    }

    @Test
    fun `new purchase reusing same notification slot with different content is allowed`() {
        val dedup = PushNotificationDeduplicator()
        val fp1 = PushNotificationDeduplicator.buildContentFingerprint(sampleParsed(199.0, "Берёза у дома"))
        val fp2 = PushNotificationDeduplicator.buildContentFingerprint(sampleParsed(540.0, "Пятёрочка"))

        assertTrue(dedup.markIfNew("0|ru.sberbankmobile|101|null|0", baseTime, fp1, nowMs = baseTime))
        assertTrue(
            dedup.markIfNew("0|ru.sberbankmobile|101|null|0", baseTime + 30_000L, fp2, nowMs = baseTime + 30_000L)
        )
    }

    @Test
    fun `deduplication survives service restart via persisted lines`() {
        val first = PushNotificationDeduplicator()
        val fp = PushNotificationDeduplicator.buildContentFingerprint(sampleParsed())
        assertTrue(first.markIfNew("0|ru.sberbankmobile|77|null|0", baseTime, fp, nowMs = baseTime))
        val saved = first.exportPersistedLines()

        val afterRestart = PushNotificationDeduplicator()
        afterRestart.ensureLoaded(saved)
        assertFalse(
            afterRestart.markIfNew(
                "0|ru.sberbankmobile|77|null|0",
                baseTime + 2_000L,
                fp,
                nowMs = baseTime + 2_000L
            )
        )
    }

    @Test
    fun `buffer respects maxEntries and keeps legacy key compatibility`() {
        val dedup = PushNotificationDeduplicator(maxEntries = 3)
        dedup.ensureLoaded(listOf("legacy_slot|1000"))
        assertFalse(dedup.markIfNew("legacy_slot", 1000L, "fp", nowMs = baseTime))
        assertTrue(dedup.markIfNew("slot1", 1001L, "fp1", nowMs = baseTime + 1))
        assertTrue(dedup.markIfNew("slot2", 1002L, "fp2", nowMs = baseTime + 2))
        assertTrue(dedup.markIfNew("slot3", 1003L, "fp3", nowMs = baseTime + 3))
        assertEquals(3, dedup.exportPersistedLines().size)
    }
}
