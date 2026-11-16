package ru.pokanevich.familybudget.service

data class ParsedBankExpense(
    val amountRub: Double,
    val merchant: String,
    val sourceType: String,
    val rawText: String
)

object BankPushParser {

    /**
     * СТРОГИЙ БЕЛЫЙ СПИСОК ПАКЕТОВ:
     * Разрешены только СберБанк и Mir Pay (+ SMS с номера 900).
     * Т-Банк (ru.tinkoff.*, com.idamob.tinkoff.android, com.tbank.*) намеренно исключён!
     */
    val ALLOWED_BANK_PACKAGES = setOf(
        "ru.sberbankmobile",
        "ru.sberbank.sberbankir",
        "ru.sberbank.spasibo",
        "ru.sberbank.sberpay",
        "ru.nspk.mirpay"
    )

    val SMS_PACKAGES = setOf(
        "com.samsung.android.messaging",
        "com.google.android.apps.messaging",
        "com.android.mms"
    )

    // Блокируем любой текст или пакет Т-Банка / Тинькофф
    private val BLOCKED_BANK_KEYWORDS = listOf(
        "тинькофф", "tinkoff", "т-банк", "t-bank", "tbank"
    )

    // Слова-маркеры одноразовых кодов, отказов, входящих переводов и возвратов (НЕ расходы)
    private val IGNORE_KEYWORDS = listOf(
        "пароль", "никому не сообщайте", "никому не говорите", "одноразов",
        "подтверждения входа", "зачисление", "пополнение", "зарплата",
        "перевод от", "возврат покупок", "возврат средств", "отмена покупки",
        "отказ", "недостаточно средств", "отклонена"
    )

    private val OTP_WORD_REGEX = Regex("\\bкод\\b", RegexOption.IGNORE_CASE)

    private val EXPENSE_KEYWORDS = listOf(
        "покупка", "оплата", "списание", "платеж", "платёж", "перевод",
        "сбп", "mir-", "mir ", "мир", "карта", "сберкарта", "оплатили",
        "минус", "чек", "успешно", "выполнена"
    )

    // Важно: (?![а-яА-Яa-zA-Z0-9]) вместо \b, потому что в Java Regex \b НЕ срабатывает после знака ₽!
    private val AMOUNT_REGEX = Regex(
        pattern = "(?:покупка|оплата|списание|плат[её]ж|перевод|сумма|на сумму|минус)?\\s*([0-9]{1,3}(?:[\\s\\u00A0][0-9]{3})*(?:[.,][0-9]{1,2})?|[0-9]+(?:[.,][0-9]{1,2})?)\\s*(?:₽|руб(?:л[яей]*)?\\.?|р\\.?)(?![а-яА-Яa-zA-Z0-9])",
        option = RegexOption.IGNORE_CASE
    )

    fun isPackageEligible(packageName: String, title: String): Boolean {
        val pkgLower = packageName.lowercase()
        if (pkgLower.contains("tinkoff") || pkgLower.contains("tbank")) return false
        if (packageName in ALLOWED_BANK_PACKAGES) return true
        if (packageName in SMS_PACKAGES) {
            val t = title.trim().lowercase()
            return t == "900" || t.startsWith("900 ") || t == "sberbank" || t == "сбербанк"
        }
        return false
    }

    fun parseNotification(
        packageName: String,
        title: String,
        text: String
    ): ParsedBankExpense? {
        if (!isPackageEligible(packageName, title)) return null

        val combined = listOf(title.trim(), text.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val lower = combined.lowercase()

        if (BLOCKED_BANK_KEYWORDS.any { lower.contains(it) }) return null
        if (IGNORE_KEYWORDS.any { lower.contains(it) }) return null
        if (OTP_WORD_REGEX.containsMatchIn(lower) && !lower.contains("промокод") && !lower.contains("qr")) {
            return null
        }

        if (EXPENSE_KEYWORDS.none { lower.contains(it) } && !lower.contains("₽")) return null

        // Отрезаем хвост "Баланс: ..." / "Доступно: ..." / "Остаток: ...", чтобы не спутать остаток с ценой
        val textBeforeBalance = combined.split(
            Regex("(?:баланс|доступно|остаток)\\s*:?", RegexOption.IGNORE_CASE)
        ).firstOrNull()?.trim() ?: combined

        val match = AMOUNT_REGEX.find(textBeforeBalance) ?: return null
        val rawNumber = match.groupValues[1]
            .replace(" ", "")
            .replace("\u00A0", "")
            .replace(",", ".")

        val amount = rawNumber.toDoubleOrNull() ?: return null
        if (amount <= 0.0) return null

        // Извлекаем название магазина после суммы
        val afterAmount = textBeforeBalance.substring(match.range.last + 1)
            .replace(Regex("^[.\\s,:;—–-]+"), "")
            .replace(Regex("^(в|магазин|место)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[.\\s]+$"), "")
            .trim()

        val cleanedTitle = title
            .replace(Regex("(?:900|СберБанк Онлайн|СберБанк|Сбер|Mir Pay|Покупка|Оплата картой|Оплата|Уведомление)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[•*]\\d{4}"), "")
            .trim()

        val merchant = when {
            afterAmount.length in 2..60 -> afterAmount
            cleanedTitle.length in 2..45 -> cleanedTitle
            packageName.contains("mirpay") -> "Оплата через Mir Pay"
            else -> "Покупка по карте Сбера"
        }

        val sourceType = if (packageName.contains("mirpay")) "PUSH_MIRPAY" else "PUSH_SBER"

        return ParsedBankExpense(
            amountRub = amount,
            merchant = merchant,
            sourceType = sourceType,
            rawText = combined
        )
    }
}
