package ru.pokanevich.familybudget

import org.junit.Assert.*
import org.junit.Test
import ru.pokanevich.familybudget.service.BankPushParser

class BankPushParserTest {

    @Test
    fun parseNotification_sberbankPush_success() {
        val parsed = BankPushParser.parseNotification(
            packageName = "ru.sberbankmobile",
            title = "СберБанк",
            text = "Покупка 1 540,50 ₽ ВкусВилл. Баланс: 12 345,67 ₽"
        )
        assertNotNull(parsed)
        assertEquals(1540.50, parsed!!.amountRub, 0.001)
        assertEquals("ВкусВилл", parsed.merchant)
        assertEquals("PUSH_SBER", parsed.sourceType)
    }

    @Test
    fun parseNotification_sberOtherPackages_success() {
        val parsed = BankPushParser.parseNotification(
            packageName = "ru.sberbank.sberpay",
            title = "Сбер",
            text = "Оплата 890 ₽ Магнит. Доступно: 50 000 ₽"
        )
        assertNotNull(parsed)
        assertEquals(890.0, parsed!!.amountRub, 0.001)
        assertEquals("Магнит", parsed.merchant)
        assertEquals("PUSH_SBER", parsed.sourceType)
    }

    @Test
    fun parseNotification_mirPayPush_success() {
        val parsed = BankPushParser.parseNotification(
            packageName = "ru.nspk.mirpay",
            title = "Mir Pay",
            text = "Оплата 350 ₽ Аптека Вита. Остаток: 5 000 ₽"
        )
        assertNotNull(parsed)
        assertEquals(350.0, parsed!!.amountRub, 0.001)
        assertEquals("Аптека Вита", parsed.merchant)
        assertEquals("PUSH_MIRPAY", parsed.sourceType)
    }

    @Test
    fun parseNotification_mirPayPush_fallbackMerchant() {
        val parsed = BankPushParser.parseNotification(
            packageName = "ru.nspk.mirpay",
            title = "Mir Pay",
            text = "Оплата 500 ₽"
        )
        assertNotNull(parsed)
        assertEquals(500.0, parsed!!.amountRub, 0.001)
        assertEquals("Оплата через Mir Pay", parsed.merchant)
        assertEquals("PUSH_MIRPAY", parsed.sourceType)
    }

    @Test
    fun parseNotification_smsFrom900_success() {
        val parsed = BankPushParser.parseNotification(
            packageName = "com.samsung.android.messaging",
            title = "900",
            text = "СберКарта ••1234 10:15 Покупка 2 345.80р Пятерочка Баланс: 45 120.50р"
        )
        assertNotNull(parsed)
        assertEquals(2345.80, parsed!!.amountRub, 0.001)
        assertEquals("PUSH_SBER", parsed.sourceType)
        assertEquals("Пятерочка", parsed.merchant)
    }

    @Test
    fun parseNotification_smsFrom900_googleMessages() {
        val parsed = BankPushParser.parseNotification(
            packageName = "com.google.android.apps.messaging",
            title = "900",
            text = "Перевод 1000 ₽ Анжелика П. Баланс: 5000 ₽"
        )
        assertNotNull(parsed)
        assertEquals(1000.0, parsed!!.amountRub, 0.001)
        assertEquals("PUSH_SBER", parsed.sourceType)
    }

    @Test
    fun parseNotification_smsFromUnknownSender_ignored() {
        assertFalse(BankPushParser.isPackageEligible("com.samsung.android.messaging", "Друг"))
        assertFalse(BankPushParser.isPackageEligible("com.samsung.android.messaging", "79991234567"))

        val parsed = BankPushParser.parseNotification(
            packageName = "com.samsung.android.messaging",
            title = "Друг",
            text = "Переведи мне 500 ₽"
        )
        assertNull(parsed)
    }

    @Test
    fun isPackageEligible_strictTBankBlocking() {
        assertFalse(BankPushParser.isPackageEligible("com.idamob.tinkoff.android", "Т-Банк"))
        assertFalse(BankPushParser.isPackageEligible("ru.tinkoff.invest", "Тинькофф"))
        assertFalse(BankPushParser.isPackageEligible("com.tbank.app", "T-Bank"))
        assertFalse(BankPushParser.isPackageEligible("ru.tbank.mobile", "Т-Банк"))

        // Even with SMS package or Sber title
        assertFalse(BankPushParser.isPackageEligible("com.tbank.app", "900"))
    }

    @Test
    fun parseNotification_blockedTBankKeywords_returnsNull() {
        // T-Bank package
        assertNull(
            BankPushParser.parseNotification(
                packageName = "com.idamob.tinkoff.android",
                title = "Тинькофф",
                text = "Покупка 500 ₽ ВкусВилл"
            )
        )

        // Sber package, but text/title contains T-Bank keywords
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.sberbankmobile",
                title = "СберБанк",
                text = "Перевод в Тинькофф 1 500 ₽"
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.sberbankmobile",
                title = "СберБанк",
                text = "Перевод на Т-Банк 2 000 ₽"
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.sberbankmobile",
                title = "СберБанк",
                text = "Пополнение счета Tinkoff 500 ₽"
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.sberbankmobile",
                title = "СберБанк",
                text = "Перевод tbank 300 ₽"
            )
        )
    }

    @Test
    fun parseNotification_otpAndSecurityCodes_ignored() {
        assertNull(
            BankPushParser.parseNotification(
                packageName = "com.samsung.android.messaging",
                title = "900",
                text = "Пароль для входа в СберБанк Онлайн: 456789. Никому не сообщайте"
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "com.samsung.android.messaging",
                title = "900",
                text = "Никому не говорите код подтверждения: 1234"
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "com.samsung.android.messaging",
                title = "900",
                text = "Одноразовый пароль для оплаты 500 ₽: 7788"
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.sberbankmobile",
                title = "СберБанк",
                text = "Код подтверждения входа 987654"
            )
        )
    }

    @Test
    fun parseNotification_returnsAndIncomes_ignored() {
        // Returns
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.sberbankmobile",
                title = "СберБанк",
                text = "Возврат покупок 1 500 ₽ Ozon. Баланс: 10 000 ₽"
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.sberbankmobile",
                title = "СберБанк",
                text = "Возврат средств 300 ₽. Баланс: 5 000 ₽"
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.sberbankmobile",
                title = "СберБанк",
                text = "Отмена покупки 750 ₽. Баланс: 8 000 ₽"
            )
        )

        // Incomes
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.sberbankmobile",
                title = "СберБанк",
                text = "Зачисление 45 000 ₽ зарплата. Баланс: 50 000 ₽"
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.sberbankmobile",
                title = "СберБанк",
                text = "Пополнение карты на 5 000 ₽. Баланс: 15 000 ₽"
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.sberbankmobile",
                title = "СберБанк",
                text = "Перевод от Иван И. 2 000 ₽"
            )
        )

        // Rejections
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.sberbankmobile",
                title = "СберБанк",
                text = "Отказ. Недостаточно средств для покупки 500 ₽"
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.sberbankmobile",
                title = "СберБанк",
                text = "Операция отклонена банком"
            )
        )
    }

    @Test
    fun parseNotification_amountFormattingVariations() {
        // Space in thousands
        val p1 = BankPushParser.parseNotification(
            packageName = "ru.sberbankmobile",
            title = "СберБанк",
            text = "Покупка 12 345 ₽ Мебель"
        )
        assertNotNull(p1)
        assertEquals(12345.0, p1!!.amountRub, 0.001)

        // Non-breaking space \u00A0 and comma kopecks
        val p2 = BankPushParser.parseNotification(
            packageName = "ru.sberbankmobile",
            title = "СберБанк",
            text = "Покупка 5\u00A0432,10 ₽ DNS"
        )
        assertNotNull(p2)
        assertEquals(5432.10, p2!!.amountRub, 0.001)

        // Ruble abbreviations: руб., рублей, р., р
        val p3 = BankPushParser.parseNotification(
            packageName = "ru.sberbankmobile",
            title = "СберБанк",
            text = "Покупка 320 руб. Кафе"
        )
        assertNotNull(p3)
        assertEquals(320.0, p3!!.amountRub, 0.001)

        val p4 = BankPushParser.parseNotification(
            packageName = "ru.sberbankmobile",
            title = "СберБанк",
            text = "Покупка 100 рублей Хлеб"
        )
        assertNotNull(p4)
        assertEquals(100.0, p4!!.amountRub, 0.001)

        val p5 = BankPushParser.parseNotification(
            packageName = "ru.sberbankmobile",
            title = "СберБанк",
            text = "Покупка 75 р. Булочка"
        )
        assertNotNull(p5)
        assertEquals(75.0, p5!!.amountRub, 0.001)

        val p6 = BankPushParser.parseNotification(
            packageName = "ru.sberbankmobile",
            title = "СберБанк",
            text = "Покупка 50 р Вода"
        )
        assertNotNull(p6)
        assertEquals(50.0, p6!!.amountRub, 0.001)
    }

    @Test
    fun parseNotification_balanceCutoff() {
        // Verify balance / доступно / остаток numbers are not mistaken for expense amount
        val p1 = BankPushParser.parseNotification(
            packageName = "ru.sberbankmobile",
            title = "СберБанк",
            text = "Покупка 150 ₽ Кофе. Баланс: 98 450 ₽"
        )
        assertNotNull(p1)
        assertEquals(150.0, p1!!.amountRub, 0.001)
        assertEquals("Кофе", p1.merchant)

        val p2 = BankPushParser.parseNotification(
            packageName = "ru.sberbankmobile",
            title = "СберБанк",
            text = "Покупка 300 ₽ Кино. Доступно: 50 000 ₽"
        )
        assertNotNull(p2)
        assertEquals(300.0, p2!!.amountRub, 0.001)
        assertEquals("Кино", p2.merchant)

        val p3 = BankPushParser.parseNotification(
            packageName = "ru.sberbankmobile",
            title = "СберБанк",
            text = "Покупка 600 ₽ Бензин. Остаток: 12 000 ₽"
        )
        assertNotNull(p3)
        assertEquals(600.0, p3!!.amountRub, 0.001)
        assertEquals("Бензин", p3.merchant)
    }
}
