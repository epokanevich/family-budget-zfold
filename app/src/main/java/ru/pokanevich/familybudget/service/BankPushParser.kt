package ru.pokanevich.familybudget.service

import ru.pokanevich.familybudget.data.BankPushSource
import ru.pokanevich.familybudget.data.OperationType
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

data class ParsedBankExpense(
    val amountRub: Double,
    val merchant: String,
    val sourceType: String,
    val rawText: String,
    val suggestedOperationType: String = OperationType.EXPENSE.name
)

object BankPushParser {

    /**
     * Пакеты источников по умолчанию (СберБанк и Mir Pay) — сохраняется для совместимости.
     * Полный список банков и их переключение настраиваются локально в [BankPushSource].
     */
    val ALLOWED_BANK_PACKAGES: Set<String> =
        BankPushSource.DEFAULT_ENABLED.flatMap { it.packages }.toSet()

    val SMS_PACKAGES = setOf(
        "com.samsung.android.messaging",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.android.messaging"
    )

    private const val OZON_MAIN_APP_PACKAGE = "ru.ozon.app.android"
    private val OZON_BANK_TITLE_MARKERS = listOf(
        "ozon банк", "ozon bank", "озон банк", "ozon карта", "озон карта", "ozon счёт", "ozon счет"
    )

    // Ключевые слова Т-Банка / Тинькофф — блокируются, пока пользователь явно не включит BankPushSource.TBANK
    private val TBANK_KEYWORDS = listOf(
        "тинькофф", "tinkoff", "т-банк", "t-bank", "tbank"
    )

    // Слова-маркеры одноразовых кодов, отказов, входящих переводов и возвратов (НЕ расходы)
    private val IGNORE_KEYWORDS = listOf(
        "пароль", "никому не сообщайте", "никому не говорите", "одноразов",
        "подтверждения входа", "подтвердите", "для подтверждения", "код подтверждения",
        "секретный код", "вход в приложение",
        "зачисление", "зачислено", "пополнение", "пополнена", "пополнен", "пополнили",
        "зарплата", "перевод от", "входящий перевод", "поступление", "поступило",
        "вам перевели", "пришёл перевод", "пришел перевод", "начислено",
        "возврат покупок", "возврат покупки", "возврат средств", "возврат платежа",
        "возврат оплаты", "возврат на карту", "возврат денег",
        "отмена покупки", "отмена операции", "операция отменена", "покупка отменена",
        "платёж отменён", "платеж отменен",
        "отказ", "отказано", "недостаточно средств", "отклонена", "отклонено",
        "отклонён", "отклонен", "не выполнена", "не удалось", "ошибка операции",
        "кредитный лимит", "лимит по карте",
        "проценты на остаток", "начислен кэшбэк", "начислен кешбэк",
        "выплата кэшбэка", "выплата кешбэка"
    )

    private val CASHBACK_CREDIT_REGEX = Regex(
        "(?:к[эе]шб[эе]к|cashback|начислен|бонус(?:ы|ов)?|вознаграждени)",
        RegexOption.IGNORE_CASE
    )

    private val EXPLICIT_EXPENSE_VERB_REGEX = Regex(
        "(?:покупк|оплат|списан|плат[её]ж|перевод|расход|минус|−)",
        RegexOption.IGNORE_CASE
    )

    // Lookaround вместо \b, т.к. в Java Regex \b не работает с кириллицей
    private val OTP_WORD_REGEX = Regex(
        "(?<![а-яА-ЯёЁa-zA-Z0-9])(?:код|code|otp|pin|cvv|cvc)(?![а-яА-ЯёЁa-zA-Z0-9])",
        RegexOption.IGNORE_CASE
    )

    private val REFUND_WORD_REGEX = Regex(
        "(?<![а-яА-ЯёЁa-zA-Z0-9])возврат(?![а-яА-ЯёЁa-zA-Z0-9])",
        RegexOption.IGNORE_CASE
    )

    private val EXPENSE_KEYWORDS = listOf(
        "покупка", "оплата", "списание", "платеж", "платёж", "перевод",
        "сбп", "mir-", "mir ", "мир", "карта", "карты", "картой", "сберкарта",
        "оплатили", "минус", "чек", "успешно", "выполнена", "расход", "−"
    )

    private val NEGATIVE_RUB_REGEX = Regex(
        "[−-]\\s*[0-9]{1,3}(?:[\\s\\u00A0][0-9]{3})*(?:[.,][0-9]{1,2})?\\s*(?:₽|rub|руб(?:л[яей]*)?\\.?|р\\.?)(?![а-яА-ЯёЁa-zA-Z0-9])",
        RegexOption.IGNORE_CASE
    )

    // Важно: (?![а-яА-ЯёЁa-zA-Z0-9]) вместо \b, потому что в Java Regex \b НЕ срабатывает после знака ₽!
    // (?<![0-9.,:/]) слева защищает от склейки цифр времени/даты/номера карты с суммой.
    private val AMOUNT_REGEX = Regex(
        pattern = "(?:покупка|оплата|списание|плат[её]ж|перевод|сумма|на сумму|минус)?\\s*:?\\s*[−–-]?\\s*(?<![0-9.,:/])([0-9]{1,3}(?:[\\s\\u00A0][0-9]{3})*(?:[.,][0-9]{1,2})?|[0-9]+(?:[.,][0-9]{1,2})?)\\s*(?:₽|rub|руб(?:л[яей]*)?\\.?|р\\.?)(?![а-яА-ЯёЁa-zA-Z0-9])",
        option = RegexOption.IGNORE_CASE
    )

    private val BALANCE_CUTOFF_REGEX = Regex(
        "(?:баланс|доступно|остаток)\\s*:?",
        RegexOption.IGNORE_CASE
    )

    private val UNAMBIGUOUS_SELF_TRANSFER_PHRASES = listOf(
        "между своими",
        "между вашими счетами",
        "между счетами",
        "перевод на свой счет",
        "перевод на свой счёт",
        "перевод на свои счета",
        "перевод на свою карту",
        "перевод между картами",
        "перевод на собственный счет",
        "перевод на собственный счёт",
        "перевод себе"
    )

    private val CONTEXTUAL_SELF_TRANSFER_MARKERS = listOf(
        "на свой",
        "свой счет",
        "свой счёт",
        "свою карту",
        "свои счета",
        "со своего сч",
        "себе в ",
        "себе на ",
        "себе по ",
        "себе через "
    )

    private val LEADING_PUNCT_REGEX = Regex("^[.\\s,:;—–•-]+")
    private val TRAILING_PUNCT_REGEX = Regex("[.\\s,:;—–•-]+$")
    private val LEADING_PREPOSITION_REGEX = Regex("^(?:в|магазин|место|получатель:?)\\s+", RegexOption.IGNORE_CASE)
    private val LEADING_CARD_PREFIX_REGEX = Regex(
        "^(?:(?:по|с|со|на)\\s+)?(?:карте|карта|картой|сберкарте|сберкарта|сч[её]ту|сч[её]та|сч[её]т|black|all\\s+airlines|drive|platinum|mir|мир|visa|mastercard)\\s*(?:[•*-]+\\s*\\d{4}|\\d{4})[.\\s,:;—–•-]*",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_CARD_SUFFIX_REGEX = Regex(
        "(?:[.\\s,;—–•]+(?:(?:по|с|со)\\s+)?(?:карте|карта|картой|сч[её]ту|сч[её]та|сч[её]т|black|all\\s+airlines|drive|platinum|mir|мир|visa|mastercard)\\s*[•*]*\\s*\\d{4}[.\\s,;—–•]*|[.\\s,;—–•]+[•*]+\\s*\\d{4}[.\\s,;—–•]*)$",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_BONUS_SUFFIX_REGEX = Regex(
        "[.\\s,;—–•]+(?:к[эе]шб[эе]к|бонус(?:ы|ов)?|спасибо)\\b.*$",
        RegexOption.IGNORE_CASE
    )
    private val CARD_ONLY_RESIDUE_REGEX = Regex(
        "^(?:(?:black|all\\s+airlines|drive|platinum|mir|мир|visa|mastercard|карта|сч[её]т)\\s*)?[•*]*\\s*\\d{0,4}$",
        RegexOption.IGNORE_CASE
    )
    private val TITLE_STRIP_REGEX = Regex(
        "(?:900|СберБанк Онлайн|СберБанк|Сбер|Mir Pay|Т-Банк|Тинькофф|T-Bank|TBank|Tinkoff|Альфа-Банк|АльфаБанк|Alfa-Bank|AlfaBank|ВТБ Онлайн|ВТБ|VTB|Ozon Банк|Ozon Bank|Озон Банк|Ozon Карта|OzonBank|Покупка|Оплата картой|Оплата|Списание|Перевод|Уведомление)",
        RegexOption.IGNORE_CASE
    )

    private fun normalizeWhitespace(raw: String): String =
        raw.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

    private fun matchesSmsSender(title: String, source: BankPushSource): Boolean {
        val normalized = normalizeWhitespace(title).lowercase()
        if (normalized.isEmpty()) return false
        return source.smsSenders.any { sender ->
            normalized == sender ||
                normalized.startsWith("$sender ") ||
                normalized.startsWith("$sender:") ||
                normalized.startsWith("$sender.") ||
                normalized.startsWith("$sender,")
        }
    }

    private fun resolveBankSource(
        packageName: String,
        title: String,
        enabledSources: Set<BankPushSource>
    ): BankPushSource? {
        if (enabledSources.isEmpty()) return null
        val pkgLower = packageName.trim().lowercase()

        // Если Т-Банк не включён пользователем, строго блокируем все его пакеты (поведение v3.2.4)
        if (BankPushSource.TBANK !in enabledSources &&
            (pkgLower.contains("tinkoff") || pkgLower.contains("tbank"))
        ) {
            return null
        }

        enabledSources.firstOrNull { pkgLower in it.packages }?.let { return it }

        if (BankPushSource.OZON in enabledSources && pkgLower == OZON_MAIN_APP_PACKAGE) {
            val titleLower = normalizeWhitespace(title).lowercase()
            if (OZON_BANK_TITLE_MARKERS.any { titleLower.contains(it) }) {
                return BankPushSource.OZON
            }
            return null
        }

        if (pkgLower in SMS_PACKAGES) {
            return enabledSources.firstOrNull { matchesSmsSender(title, it) }
        }
        return null
    }

    private fun isExplicitSelfTransfer(lowerText: String): Boolean {
        if (UNAMBIGUOUS_SELF_TRANSFER_PHRASES.any { lowerText.contains(it) }) return true
        val hasTransferWord = lowerText.contains("перевод") || lowerText.contains("перевед")
        return hasTransferWord && CONTEXTUAL_SELF_TRANSFER_MARKERS.any { lowerText.contains(it) }
    }

    private fun cleanAfterAmount(rawAfterAmount: String): String {
        var cleaned = rawAfterAmount
            .replace(LEADING_PUNCT_REGEX, "")
            .replace(LEADING_PREPOSITION_REGEX, "")
            .replace(LEADING_CARD_PREFIX_REGEX, "")
            .replace(LEADING_PUNCT_REGEX, "")
            .replace(LEADING_PREPOSITION_REGEX, "")
            .replace(TRAILING_BONUS_SUFFIX_REGEX, "")
            .replace(TRAILING_CARD_SUFFIX_REGEX, "")
            .replace(TRAILING_PUNCT_REGEX, "")
            .trim()
        if (CARD_ONLY_RESIDUE_REGEX.matches(cleaned)) {
            cleaned = ""
        }
        return cleaned
    }

    private fun fallbackMerchant(source: BankPushSource, isSelfTransfer: Boolean): String {
        if (isSelfTransfer) return "Перевод между счетами"
        return when (source) {
            BankPushSource.MIR_PAY -> "Оплата через Mir Pay"
            BankPushSource.SBER -> "Покупка по карте Сбера"
            BankPushSource.TBANK -> "Покупка по карте Т-Банк"
            BankPushSource.ALFA -> "Покупка по карте Альфа-Банк"
            BankPushSource.VTB -> "Покупка по карте ВТБ"
            BankPushSource.OZON -> "Покупка по карте Ozon Банк"
        }
    }

    private val FULL_DMY_DATETIME_REGEX = Regex(
        "\\b(\\d{2})\\.(\\d{2})\\.(\\d{4})(?:\\s*,\\s*|\\s+)([01]?\\d|2[0-3]):([0-5]\\d)(?::([0-5]\\d))?\\b"
    )
    private val SHORT_DMY_DATETIME_REGEX = Regex(
        "\\b(\\d{2})\\.(\\d{2})\\.(\\d{2})(?:\\s*,\\s*|\\s+)([01]?\\d|2[0-3]):([0-5]\\d)(?::([0-5]\\d))?\\b"
    )
    private val ISO_DATETIME_REGEX = Regex(
        "\\b(\\d{4})-(\\d{2})-(\\d{2})[T\\s]([01]?\\d|2[0-3]):([0-5]\\d)(?::([0-5]\\d))?\\b"
    )
    private val TIME_ONLY_REGEX = Regex(
        "(?<![0-9.,:/-])([01]?\\d|2[0-3]):([0-5]\\d)(?::([0-5]\\d))?(?![0-9.,:/-])"
    )

    /**
     * Извлекает метку времени самой операции из заголовка и текста банковского пуша/SMS.
     * Возвращает `null`, если дата/время операции в тексте не указаны.
     */
    fun extractOperationTimestampMs(
        title: String,
        text: String,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long? {
        val combined = listOf(title, text)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (combined.isEmpty()) return null

        FULL_DMY_DATETIME_REGEX.find(combined)?.let { m ->
            parseDateTimeGroup(
                year = m.groupValues[3].toInt(),
                month = m.groupValues[2].toInt(),
                day = m.groupValues[1].toInt(),
                hour = m.groupValues[4].toInt(),
                minute = m.groupValues[5].toInt(),
                second = m.groupValues[6].toIntOrNull() ?: 0,
                zoneId = zoneId
            )?.let { return it }
        }

        SHORT_DMY_DATETIME_REGEX.find(combined)?.let { m ->
            parseDateTimeGroup(
                year = 2000 + m.groupValues[3].toInt(),
                month = m.groupValues[2].toInt(),
                day = m.groupValues[1].toInt(),
                hour = m.groupValues[4].toInt(),
                minute = m.groupValues[5].toInt(),
                second = m.groupValues[6].toIntOrNull() ?: 0,
                zoneId = zoneId
            )?.let { return it }
        }

        ISO_DATETIME_REGEX.find(combined)?.let { m ->
            parseDateTimeGroup(
                year = m.groupValues[1].toInt(),
                month = m.groupValues[2].toInt(),
                day = m.groupValues[3].toInt(),
                hour = m.groupValues[4].toInt(),
                minute = m.groupValues[5].toInt(),
                second = m.groupValues[6].toIntOrNull() ?: 0,
                zoneId = zoneId
            )?.let { return it }
        }

        TIME_ONLY_REGEX.find(combined)?.let { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            val second = m.groupValues[3].toIntOrNull() ?: 0
            val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
            val localTime = runCatching { LocalTime.of(hour, minute, second) }.getOrNull() ?: return null
            val candidates = listOf(today.minusDays(1), today, today.plusDays(1)).map { day ->
                LocalDateTime.of(day, localTime).atZone(zoneId).toInstant().toEpochMilli()
            }
            return candidates.minByOrNull { abs(it - nowMs) }
        }

        return null
    }

    private fun parseDateTimeGroup(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        zoneId: ZoneId
    ): Long? = runCatching {
        LocalDateTime.of(year, month, day, hour, minute, second)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    fun isPackageEligible(packageName: String, title: String): Boolean =
        isPackageEligible(packageName, title, BankPushSource.DEFAULT_ENABLED)

    fun isPackageEligible(
        packageName: String,
        title: String,
        enabledSources: Set<BankPushSource>
    ): Boolean = resolveBankSource(packageName, title, enabledSources) != null

    fun parseNotification(
        packageName: String,
        title: String,
        text: String
    ): ParsedBankExpense? = parseNotification(
        packageName = packageName,
        title = title,
        text = text,
        enabledSources = BankPushSource.DEFAULT_ENABLED
    )

    fun parseNotification(
        packageName: String,
        title: String,
        text: String,
        enabledSources: Set<BankPushSource>
    ): ParsedBankExpense? {
        val source = resolveBankSource(packageName, title, enabledSources) ?: return null

        val combined = listOf(title.trim(), text.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val lower = combined.lowercase()

        if (BankPushSource.TBANK !in enabledSources && TBANK_KEYWORDS.any { lower.contains(it) }) {
            return null
        }
        if (IGNORE_KEYWORDS.any { lower.contains(it) }) return null
        if (REFUND_WORD_REGEX.containsMatchIn(lower)) return null
        if (OTP_WORD_REGEX.containsMatchIn(lower) && !lower.contains("промокод") && !lower.contains("qr")) {
            return null
        }

        if (EXPENSE_KEYWORDS.none { lower.contains(it) } &&
            !lower.contains("₽") &&
            !NEGATIVE_RUB_REGEX.containsMatchIn(lower)
        ) {
            return null
        }

        // Отрезаем хвост "Баланс: ..." / "Доступно: ..." / "Остаток: ...", чтобы не спутать остаток с ценой
        val textBeforeBalance = combined.split(BALANCE_CUTOFF_REGEX)
            .firstOrNull()
            ?.trim()
            ?: combined

        val match = AMOUNT_REGEX.find(textBeforeBalance) ?: return null
        val capturedAmount = match.groupValues[1]
        val numberStart = textBeforeBalance.indexOf(capturedAmount, match.range.first)
        val prefixBeforeAmount = if (numberStart > 0) textBeforeBalance.substring(0, numberStart) else ""
        if (prefixBeforeAmount.trimEnd().endsWith("+")) {
            return null
        }
        // Отсекаем отдельные уведомления вида «Кэшбэк 450 ₽», но сохраняем покупки вида «Покупка 1200 ₽ Пятёрочка. Кэшбэк 12 ₽»
        if (CASHBACK_CREDIT_REGEX.containsMatchIn(prefixBeforeAmount) &&
            !EXPLICIT_EXPENSE_VERB_REGEX.containsMatchIn(prefixBeforeAmount)
        ) {
            return null
        }

        val rawNumber = capturedAmount
            .replace(" ", "")
            .replace("\u00A0", "")
            .replace(",", ".")

        val amount = rawNumber.toDoubleOrNull() ?: return null
        if (amount <= 0.0) return null

        val isSelfTransfer = isExplicitSelfTransfer(lower)

        // Извлекаем название магазина после суммы
        val afterAmount = cleanAfterAmount(textBeforeBalance.substring(match.range.last + 1))

        val cleanedTitle = title
            .replace(TITLE_STRIP_REGEX, "")
            .replace(Regex("[•*]+\\d{4}"), "")
            .replace(LEADING_PUNCT_REGEX, "")
            .replace(TRAILING_PUNCT_REGEX, "")
            .trim()

        val merchant = when {
            afterAmount.length in 2..60 -> afterAmount
            cleanedTitle.length in 2..45 -> cleanedTitle
            else -> fallbackMerchant(source, isSelfTransfer)
        }

        val suggestedOperationType = if (isSelfTransfer) {
            OperationType.TRANSFER.name
        } else {
            OperationType.EXPENSE.name
        }

        return ParsedBankExpense(
            amountRub = amount,
            merchant = merchant,
            sourceType = source.sourceTypeTag,
            rawText = combined,
            suggestedOperationType = suggestedOperationType
        )
    }
}
