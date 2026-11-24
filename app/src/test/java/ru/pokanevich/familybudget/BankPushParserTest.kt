package ru.pokanevich.familybudget

import org.junit.Assert.*
import org.junit.Test
import ru.pokanevich.familybudget.data.BankPushSource
import ru.pokanevich.familybudget.data.OperationType
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

    @Test
    fun defaultEnabledSources_onlySberAndMirPay() {
        assertEquals(setOf(BankPushSource.SBER, BankPushSource.MIR_PAY), BankPushSource.DEFAULT_ENABLED)
        assertEquals(setOf("SBER", "MIR_PAY"), BankPushSource.DEFAULT_ENABLED_IDS)

        // With empty enabledSources even Sber and Mir Pay are ignored
        assertFalse(BankPushParser.isPackageEligible("ru.sberbankmobile", "СберБанк", emptySet()))
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.sberbankmobile",
                title = "СберБанк",
                text = "Покупка 500 ₽ Кофе",
                enabledSources = emptySet()
            )
        )
    }

    @Test
    fun parseNotification_tbankEnabled_parsesTemplatesAndRejectsOtpIncomes() {
        val enabled = setOf(BankPushSource.TBANK)

        assertTrue(BankPushParser.isPackageEligible("com.idamob.tinkoff.android", "Т-Банк", enabled))
        assertTrue(BankPushParser.isPackageEligible("com.samsung.android.messaging", "T-Bank", enabled))

        val classic = BankPushParser.parseNotification(
            packageName = "com.idamob.tinkoff.android",
            title = "Т-Банк",
            text = "Покупка, карта *2997. 3210.84 RUB. OOO DELOVY. Доступно 45200.50 RUB",
            enabledSources = enabled
        )
        assertNotNull(classic)
        assertEquals(3210.84, classic!!.amountRub, 0.001)
        assertEquals("OOO DELOVY", classic.merchant)
        assertEquals("PUSH_TBANK", classic.sourceType)
        assertEquals(OperationType.EXPENSE.name, classic.suggestedOperationType)

        val modern = BankPushParser.parseNotification(
            packageName = "ru.tbank.mobile",
            title = "Пятёрочка",
            text = "−540 ₽ • Black *1234",
            enabledSources = enabled
        )
        assertNotNull(modern)
        assertEquals(540.0, modern!!.amountRub, 0.001)
        assertEquals("Пятёрочка", modern.merchant)
        assertEquals("PUSH_TBANK", modern.sourceType)

        val sms = BankPushParser.parseNotification(
            packageName = "com.samsung.android.messaging",
            title = "Tinkoff",
            text = "Покупка, карта *1234. 780 ₽. ВкусВилл. Доступно 12 000 ₽",
            enabledSources = enabled
        )
        assertNotNull(sms)
        assertEquals(780.0, sms!!.amountRub, 0.001)
        assertEquals("ВкусВилл", sms.merchant)
        assertEquals("PUSH_TBANK", sms.sourceType)

        // OTP, incomes, refunds, declines, balance-only must be ignored
        assertNull(
            BankPushParser.parseNotification(
                packageName = "com.idamob.tinkoff.android",
                title = "Т-Банк",
                text = "Подтвердите покупку 2 400 RUB на Ozon. Код: 9152",
                enabledSources = enabled
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "com.idamob.tinkoff.android",
                title = "Т-Банк",
                text = "Пополнение, карта *2997. 15 000 RUB. Доступно 45 000 RUB",
                enabledSources = enabled
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "com.idamob.tinkoff.android",
                title = "Т-Банк",
                text = "Входящий перевод, карта *2997. 5 000 RUB от Иван И. Доступно 50 200.50 RUB",
                enabledSources = enabled
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "com.idamob.tinkoff.android",
                title = "Т-Банк",
                text = "Возврат покупки, карта *2997. 1 200 RUB. Доступно 15 000 RUB",
                enabledSources = enabled
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "com.idamob.tinkoff.android",
                title = "Т-Банк",
                text = "Доступно 45 200.50 RUB",
                enabledSources = enabled
            )
        )
    }

    @Test
    fun parseNotification_alfaEnabled_parsesTemplatesAndRejectsOtpIncomes() {
        // Blocked by default
        assertFalse(BankPushParser.isPackageEligible("ru.alfabank.mobile.android", "Альфа-Банк"))
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.alfabank.mobile.android",
                title = "Альфа-Банк",
                text = "Покупка. Карта *1234. Сумма: 1 500.00 RUB. Supermarket. Баланс: 45 300.00 RUB."
            )
        )

        val enabled = setOf(BankPushSource.ALFA)
        val push = BankPushParser.parseNotification(
            packageName = "ru.alfabank.mobile.android",
            title = "Альфа-Банк",
            text = "Покупка. Карта *1234. Сумма: 1 500.00 RUB. Supermarket. Баланс: 45 300.00 RUB.",
            enabledSources = enabled
        )
        assertNotNull(push)
        assertEquals(1500.0, push!!.amountRub, 0.001)
        assertEquals("Supermarket", push.merchant)
        assertEquals("PUSH_ALFA", push.sourceType)

        val accountPush = BankPushParser.parseNotification(
            packageName = "ru.alfabank.mobile.android",
            title = "Альфа-Банк",
            text = "Списание 1 200 ₽ со счёта *5678. Перекрёсток. Остаток 30 000 ₽",
            enabledSources = enabled
        )
        assertNotNull(accountPush)
        assertEquals(1200.0, accountPush!!.amountRub, 0.001)
        assertEquals("Перекрёсток", accountPush.merchant)
        assertEquals("PUSH_ALFA", accountPush.sourceType)

        val sms = BankPushParser.parseNotification(
            packageName = "com.google.android.apps.messaging",
            title = "Alfa-Bank",
            text = "Покупка *1234 Сумма: 500.00 RUB Ozon.ru Доступно: 12450.00 RUB",
            enabledSources = enabled
        )
        assertNotNull(sms)
        assertEquals(500.0, sms!!.amountRub, 0.001)
        assertEquals("Ozon.ru", sms.merchant)
        assertEquals("PUSH_ALFA", sms.sourceType)

        // OTP / replenishment / refund ignored
        assertNull(
            BankPushParser.parseNotification(
                packageName = "com.google.android.apps.messaging",
                title = "Alfa-Bank",
                text = "Подтвердите перевод 5000 RUB на карту 1234. Код 4582",
                enabledSources = enabled
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.alfabank.mobile.android",
                title = "Альфа-Банк",
                text = "Пополнение счёта +15 000 ₽. Баланс: 35 000 ₽",
                enabledSources = enabled
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.alfabank.mobile.android",
                title = "Альфа-Банк",
                text = "Возврат 1 200 ₽ Ozon.ru",
                enabledSources = enabled
            )
        )
    }

    @Test
    fun parseNotification_vtbEnabled_parsesTemplatesAndRejectsOtpIncomes() {
        assertFalse(BankPushParser.isPackageEligible("ru.vtb24.mobilebanking.android", "ВТБ Онлайн"))

        val enabled = setOf(BankPushSource.VTB)
        val push = BankPushParser.parseNotification(
            packageName = "ru.vtb24.mobilebanking.android",
            title = "ВТБ Онлайн",
            text = "Карта *1234. Покупка. Сумма: 1000.00 RUB. ВкусВилл. Доступно: 24 000.00 RUB.",
            enabledSources = enabled
        )
        assertNotNull(push)
        assertEquals(1000.0, push!!.amountRub, 0.001)
        assertEquals("ВкусВилл", push.merchant)
        assertEquals("PUSH_VTB", push.sourceType)

        val sms = BankPushParser.parseNotification(
            packageName = "com.samsung.android.messaging",
            title = "VTB",
            text = "Оплата 750 ₽ Пятёрочка. Карта *1234. Баланс: 15 000 ₽",
            enabledSources = enabled
        )
        assertNotNull(sms)
        assertEquals(750.0, sms!!.amountRub, 0.001)
        assertEquals("Пятёрочка", sms.merchant)
        assertEquals("PUSH_VTB", sms.sourceType)

        assertNull(
            BankPushParser.parseNotification(
                packageName = "com.samsung.android.messaging",
                title = "VTB",
                text = "Код подтверждения операции 839201 на сумму 2 000 ₽. Никому не сообщайте",
                enabledSources = enabled
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.vtb24.mobilebanking.android",
                title = "ВТБ Онлайн",
                text = "Зачисление 50 000 ₽. Баланс: 75 000 ₽",
                enabledSources = enabled
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.vtb24.mobilebanking.android",
                title = "ВТБ Онлайн",
                text = "Операция на 500 ₽ отклонена",
                enabledSources = enabled
            )
        )
    }

    @Test
    fun parseNotification_ozonEnabled_parsesTemplatesAndIgnoresMarketplacePromos() {
        assertFalse(BankPushParser.isPackageEligible("ru.ozon.fintech.finance", "Ozon Банк"))

        val enabled = setOf(BankPushSource.OZON)
        val push = BankPushParser.parseNotification(
            packageName = "ru.ozon.fintech.finance",
            title = "Ozon Банк",
            text = "Покупка 1 450,00 ₽ Ozon.ru. Баланс: 5 000,00 ₽",
            enabledSources = enabled
        )
        assertNotNull(push)
        assertEquals(1450.0, push!!.amountRub, 0.001)
        assertEquals("Ozon.ru", push.merchant)
        assertEquals("PUSH_OZON", push.sourceType)

        val sms = BankPushParser.parseNotification(
            packageName = "com.google.android.apps.messaging",
            title = "OzonBank",
            text = "Списание 890 RUB Магнит. Остаток: 4 110 RUB",
            enabledSources = enabled
        )
        assertNotNull(sms)
        assertEquals(890.0, sms!!.amountRub, 0.001)
        assertEquals("Магнит", sms.merchant)
        assertEquals("PUSH_OZON", sms.sourceType)

        // Ozon marketplace promo in ru.ozon.app.android must be ignored, while Ozon Bank title works
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.ozon.app.android",
                title = "Ozon",
                text = "Скидка! Товар подешевел до 990 ₽",
                enabledSources = enabled
            )
        )
        val superAppBank = BankPushParser.parseNotification(
            packageName = "ru.ozon.app.android",
            title = "Ozon Банк",
            text = "Покупка 650 ₽ Дикси. Баланс: 3 000 ₽",
            enabledSources = enabled
        )
        assertNotNull(superAppBank)
        assertEquals(650.0, superAppBank!!.amountRub, 0.001)
        assertEquals("Дикси", superAppBank.merchant)
        assertEquals("PUSH_OZON", superAppBank.sourceType)

        // OTP and replenishment ignored
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.ozon.fintech.finance",
                title = "Ozon Банк",
                text = "Одноразовый код 5566 для оплаты 1 200 ₽. Никому не сообщайте",
                enabledSources = enabled
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.ozon.fintech.finance",
                title = "Ozon Банк",
                text = "Пополнение Ozon Карты на 5 000 ₽. Баланс: 8 000 ₽",
                enabledSources = enabled
            )
        )
    }

    @Test
    fun parseNotification_explicitSelfTransfer_setsTransferOperationType() {
        val sberSelfTransfer = BankPushParser.parseNotification(
            packageName = "ru.sberbankmobile",
            title = "СберБанк",
            text = "Перевод между своими счетами 5 000 ₽. Баланс: 20 000 ₽"
        )
        assertNotNull(sberSelfTransfer)
        assertEquals(5000.0, sberSelfTransfer!!.amountRub, 0.001)
        assertEquals(OperationType.TRANSFER.name, sberSelfTransfer.suggestedOperationType)

        val vtbSelfTransfer = BankPushParser.parseNotification(
            packageName = "ru.vtb24.mobilebanking.android",
            title = "ВТБ Онлайн",
            text = "Перевод на свой счёт 3 000 ₽. Доступно: 12 000 ₽",
            enabledSources = setOf(BankPushSource.VTB)
        )
        assertNotNull(vtbSelfTransfer)
        assertEquals(OperationType.TRANSFER.name, vtbSelfTransfer!!.suggestedOperationType)

        val sberToTbankSelf = BankPushParser.parseNotification(
            packageName = "ru.sberbankmobile",
            title = "СберБанк",
            text = "Перевод себе в Т-Банк 2 000 ₽. Баланс: 10 000 ₽",
            enabledSources = setOf(BankPushSource.SBER, BankPushSource.TBANK)
        )
        assertNotNull(sberToTbankSelf)
        assertEquals(OperationType.TRANSFER.name, sberToTbankSelf!!.suggestedOperationType)

        // Transfer to another person stays EXPENSE
        val personTransfer = BankPushParser.parseNotification(
            packageName = "com.google.android.apps.messaging",
            title = "900",
            text = "Перевод 1000 ₽ Анжелика П. Баланс: 5000 ₽"
        )
        assertNotNull(personTransfer)
        assertEquals(OperationType.EXPENSE.name, personTransfer!!.suggestedOperationType)
    }

    @Test
    fun parseNotification_ignoresStandaloneCashbackAndInterest_butKeepsPurchaseWithCashback() {
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.sberbankmobile",
                title = "СберБанк Онлайн",
                text = "Начислен кэшбэк 500 ₽ за ноябрь"
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "com.idamob.tinkoff.android",
                title = "Т-Банк",
                text = "Кэшбэк 450 ₽ за покупки",
                enabledSources = setOf(BankPushSource.TBANK)
            )
        )
        assertNull(
            BankPushParser.parseNotification(
                packageName = "ru.ozon.fintech.finance",
                title = "Ozon Банк",
                text = "Проценты на остаток 185.40 ₽",
                enabledSources = setOf(BankPushSource.OZON)
            )
        )

        val purchaseWithCashback = BankPushParser.parseNotification(
            packageName = "ru.sberbankmobile",
            title = "СберБанк Онлайн",
            text = "Покупка 1 200 ₽ Пятёрочка. Кэшбэк 12 ₽"
        )
        assertNotNull(purchaseWithCashback)
        assertEquals(1200.0, purchaseWithCashback!!.amountRub, 0.001)
        assertEquals("Пятёрочка", purchaseWithCashback.merchant)
    }

    @Test
    fun parseNotification_modernSberVZapaseFormat_extractsMerchantWithoutBalanceLeak() {
        val pyaterochka = BankPushParser.parseNotification(
            packageName = "ru.sberbankmobile",
            title = "СберБанк Онлайн",
            text = "🎉 Оп! И оплачена покупка в Пятёрочка 1 034,33 ₽ — В запасе: 951 971,42 ₽ Счёт карты МИР •• 8814"
        )
        assertNotNull(pyaterochka)
        assertEquals(1034.33, pyaterochka!!.amountRub, 0.001)
        assertEquals("Пятёрочка", pyaterochka.merchant)

        val beryoza = BankPushParser.parseNotification(
            packageName = "ru.sberbankmobile",
            title = "СберБанк Онлайн",
            text = "👍 Топовая покупка в Берёза у дома 199 ₽ — В запасе: 951 772,42 ₽ Счёт карты МИР •• 8814"
        )
        assertNotNull(beryoza)
        assertEquals(199.0, beryoza!!.amountRub, 0.001)
        assertEquals("Берёза у дома", beryoza.merchant)
    }

    @Test
    fun parseNotification_modernMirPayWithDateAndSpacedBullets_fallsBackCleanly() {
        val mirPay = BankPushParser.parseNotification(
            packageName = "ru.nspk.mirpay",
            title = "Mir Pay",
            text = "Оплата покупки Сумма 767 ₽. 17.11.2026, 13:25. Карта Mir • • • • 3538"
        )
        assertNotNull(mirPay)
        assertEquals(767.0, mirPay!!.amountRub, 0.001)
        assertEquals("Оплата через Mir Pay", mirPay.merchant)
        assertEquals("PUSH_MIRPAY", mirPay.sourceType)
    }

    @Test
    fun findSameDeviceDuplicate_detectsRepeatedSberVZapaseAndRapidSameText() {
        val now = 1_794_920_000_000L
        val sberExisting = ru.pokanevich.familybudget.data.ExpenseEntity(
            id = "sber-dup-1",
            amountRub = 199.0,
            merchant = "Берёза у дома",
            sourceType = "PUSH_SBER",
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            rawPushOrQrText = "👍 Топовая покупка в Берёза у дома 199 ₽ — В запасе: 951 772,42 ₽",
            dedupSignature = "AMT_199.00"
        )
        val dup1 = ru.pokanevich.familybudget.data.BudgetRepository.findSameDeviceDuplicate(
            candidates = listOf(sberExisting),
            sourceType = "PUSH_SBER",
            merchant = "Берёза у дома",
            rawText = "👍 Топовая покупка в Берёза у дома 199 ₽ — В запасе: 951 772,42 ₽",
            nowMs = now + 20_000L
        )
        assertNotNull(dup1)

        val noBalanceExisting = sberExisting.copy(
            id = "sber-nobal",
            rawPushOrQrText = "Покупка 199 ₽ Берёза у дома"
        )
        val rapidDup = ru.pokanevich.familybudget.data.BudgetRepository.findSameDeviceDuplicate(
            candidates = listOf(noBalanceExisting),
            sourceType = "PUSH_SBER",
            merchant = "Берёза у дома",
            rawText = "Покупка 199 ₽ Берёза у дома",
            nowMs = now + 1_500L
        )
        assertNotNull(rapidDup)
    }
}
