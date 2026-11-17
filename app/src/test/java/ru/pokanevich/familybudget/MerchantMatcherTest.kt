package ru.pokanevich.familybudget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.data.BudgetRepository
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.MerchantMatcher

class MerchantMatcherTest {

    private val baseTime = 1_700_000_000_000L

    private fun pushCandidate(
        id: String,
        amount: Double,
        sourceType: String,
        merchant: String,
        rawText: String,
        offsetMs: Long = 0L
    ) = ExpenseEntity(
        id = id,
        amountRub = amount,
        merchant = merchant,
        sourceType = sourceType,
        createdAtEpochMs = baseTime + offsetMs,
        updatedAtEpochMs = baseTime + offsetMs,
        rawPushOrQrText = rawText,
        dedupSignature = BudgetRepository.buildAmountSignature(amount)
    )

    @Test
    fun `areCompatible matches cyrillic and transliterated merchant with bank suffixes`() {
        assertTrue(MerchantMatcher.areCompatible("Пятёрочка", "PYATEROCHKA 2154 MOSCOW RUS"))
        assertTrue(MerchantMatcher.areCompatible("Яндекс.Лавка", "YANDEX LAVKA"))
        assertTrue(MerchantMatcher.areCompatible("Магнит", "MAGNIT MM"))
    }

    @Test
    fun `areCompatible falls back to true when one merchant is generic`() {
        assertTrue(MerchantMatcher.areCompatible("Оплата через Mir Pay", "MAGNIT MM"))
        assertTrue(MerchantMatcher.areCompatible("Покупка по карте Сбера", "Пятёрочка"))
        assertTrue(MerchantMatcher.areCompatible("", "ВкусВилл"))
    }

    @Test
    fun `areCompatible rejects different stores`() {
        assertFalse(MerchantMatcher.areCompatible("MOSGORTRANS", "Вода Родник"))
        assertFalse(MerchantMatcher.areCompatible("Пятёрочка", "Аптека Ригла"))
        assertFalse(MerchantMatcher.areCompatible("КОФЕ ХАУЗ", "OZON.RU"))
    }

    @Test
    fun `two consecutive purchases for same amount in same channel with different balance are not duplicates`() {
        val firstTicket = pushCandidate(
            id = "sber-1",
            amount = 50.0,
            sourceType = "PUSH_SBER",
            merchant = "MOSGORTRANS",
            rawText = "MIR-1234 12:01 Покупка 50р MOSGORTRANS Баланс: 1000р"
        )

        val dupSber = BudgetRepository.findSameDeviceDuplicate(
            candidates = listOf(firstTicket),
            sourceType = "PUSH_SBER",
            merchant = "MOSGORTRANS",
            rawText = "MIR-1234 12:02 Покупка 50р MOSGORTRANS Баланс: 950р"
        )
        assertNull(dupSber)

        val firstMirPay = pushCandidate(
            id = "mirpay-1",
            amount = 50.0,
            sourceType = "PUSH_MIRPAY",
            merchant = "Мосгортранс",
            rawText = "Оплата 50 ₽ Мосгортранс"
        )
        val dupMirPay = BudgetRepository.findSameDeviceDuplicate(
            candidates = listOf(firstMirPay),
            sourceType = "PUSH_MIRPAY",
            merchant = "Мосгортранс",
            rawText = "Оплата 50 ₽ Мосгортранс"
        )
        assertNull(dupMirPay)
    }

    @Test
    fun `redelivery of identical push with balance is detected as duplicate`() {
        val raw = "СберБанк Онлайн Покупка 540 ₽ Пятёрочка Баланс: 12 340 ₽"
        val existing = pushCandidate(
            id = "sber-orig",
            amount = 540.0,
            sourceType = "PUSH_SBER",
            merchant = "Пятёрочка",
            rawText = raw
        )

        val duplicate = BudgetRepository.findSameDeviceDuplicate(
            candidates = listOf(existing),
            sourceType = "PUSH_SBER",
            merchant = "Пятёрочка",
            rawText = raw
        )
        assertNotNull(duplicate)
    }

    @Test
    fun `sms 900 and sber app push for same purchase with compatible merchant are detected as duplicate`() {
        val sms900 = pushCandidate(
            id = "sms-900",
            amount = 540.0,
            sourceType = "PUSH_SBER",
            merchant = "PYATEROCHKA 2154",
            rawText = "900 MIR-1234 14:15 Покупка 540р PYATEROCHKA 2154 Баланс: 15000р"
        )

        val duplicate = BudgetRepository.findSameDeviceDuplicate(
            candidates = listOf(sms900),
            sourceType = "PUSH_SBER",
            merchant = "Пятёрочка",
            rawText = "СберБанк Онлайн Оплата 540 ₽ Пятёрочка Доступно: 15 000 ₽"
        )
        assertNotNull(duplicate)
    }

    @Test
    fun `same device mirpay and sber pushes for different stores are not duplicates`() {
        val mirPay = pushCandidate(
            id = "mirpay-water",
            amount = 50.0,
            sourceType = "PUSH_MIRPAY",
            merchant = "Вода Родник",
            rawText = "Оплата 50 ₽ Вода Родник"
        )

        val duplicate = BudgetRepository.findSameDeviceDuplicate(
            candidates = listOf(mirPay),
            sourceType = "PUSH_SBER",
            merchant = "MOSGORTRANS",
            rawText = "Покупка 50р MOSGORTRANS Баланс: 900р"
        )
        assertNull(duplicate)
    }
}
