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
     * Разрешены только СберБанк (включая актуальные APK СберБанк Онлайн) и Mir Pay.
     * Т-Банк (ru.tinkoff.mbenterprise / com.idamob.tinkoff.android) намеренно исключён!
     */
    val ALLOWED_PACKAGES = setOf(
        "ru.sberbankmobile",
        "ru.sberbank.sberbankir",
        "ru.sberbank.spasibo",
        "ru.nspk.mirpay"
    )

    // Слова-маркеры входящих переводов, балансов или кодов, которые НЕ являются расходами
    private val IGNORE_KEYWORDS = listOf(
        "код", "пароль", "никому не сообщайте", "зачисление", "пополнение",
        "перевод от", "возврат покупок", "отказ", "недостаточно средств"
    )

    // Примеры пушей Сбера и Mir Pay:
    // "Покупка 1 450.50 ₽ Пятёрочка Баланс: 45 200 ₽"
    // "MIR-1234 19:40 Покупка 890р PYATEROCHKA Баланс: 12000р"
    // "Оплата 350,00 ₽ в Кафе Круассан"
    // "Списание 2 100 ₽ Ozon"
    private val EXPENSE_KEYWORDS = listOf(
        "покупка", "оплата", "списание", "платеж", "платёж", "перевод", "mir-", "мир", "карта"
    )

    // Ищем сумму перед знаком рубля (₽, р., руб) с учётом пробелов в тысячах
    private val AMOUNT_REGEX = Regex(
        pattern = "(?:покупка|оплата|списание|сумма|на сумму)?\\s*([0-9]{1,3}(?:[\\s\\u00A0][0-9]{3})*(?:[.,][0-9]{1,2})?|[0-9]+(?:[.,][0-9]{1,2})?)\\s*(?:₽|руб\\.?|р\\.?)\\b",
        option = RegexOption.IGNORE_CASE
    )

    fun parseNotification(
        packageName: String,
        title: String,
        text: String
    ): ParsedBankExpense? {
        if (packageName !in ALLOWED_PACKAGES) return null

        val combined = "$title $text".trim()
        val lower = combined.lowercase()

        if (IGNORE_KEYWORDS.any { lower.contains(it) }) return null
        if (EXPENSE_KEYWORDS.none { lower.contains(it) } && !lower.contains("₽")) return null

        // Отрезаем хвост "Баланс: ..." или "Доступно: ...", чтобы не перепутать остаток на карте с суммой покупки
        val textBeforeBalance = combined.split(
            Regex("(?:баланс|доступно|остаток)\\s*:?", RegexOption.IGNORE_CASE)
        ).firstOrNull() ?: combined

        val match = AMOUNT_REGEX.find(textBeforeBalance) ?: return null
        val rawNumber = match.groupValues[1]
            .replace(" ", "")
            .replace(" ", "")
            .replace(",", ".")

        val amount = rawNumber.toDoubleOrNull() ?: return null
        if (amount <= 0.0) return null

        // Извлекаем название магазина после суммы или из заголовка
        val afterAmount = textBeforeBalance.substring(match.range.last + 1)
            .replace(Regex("^[.\\s,:;—–-]+"), "")
            .replace(Regex("^(в|магазин|место)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()

        val cleanedTitle = title
            .replace(Regex("(?:СберБанк|Сбер|Mir Pay|Покупка|Оплата|Уведомление)", RegexOption.IGNORE_CASE), "")
            .trim()

        val merchant = when {
            afterAmount.length in 2..45 -> afterAmount
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
