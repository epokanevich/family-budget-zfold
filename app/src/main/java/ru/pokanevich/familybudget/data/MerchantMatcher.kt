package ru.pokanevich.familybudget.data

/**
 * Нормализация и сравнение названий мерчантов из пушей Сбера / Mir Pay.
 *
 * Банки присылают одно и то же место по-разному: «Пятёрочка» (Mir Pay) и «PYATEROCHKA 2154 MOSCOW» (Сбер).
 * Поэтому сравниваем не строки, а «скелеты» токенов: транслитерация кириллицы → латиница,
 * сведение вариантов транслита (ks/x, ts/c, kh/h, shch/sch/sh, ...) и удаление гласных.
 * Чистая логика без Android-зависимостей — покрыта unit-тестами.
 */
object MerchantMatcher {

    /** Шаблонные мерчанты, которые парсер подставляет, когда не смог вытащить название магазина. */
    private val GENERIC_MERCHANTS = setOf(
        "оплата через mir pay",
        "покупка по карте сбера",
        "покупка по карте",
        "покупка",
        "оплата",
        "оплата картой",
        "mir pay",
        "мир пэй",
        "сбер",
        "сбербанк",
        "сбербанк онлайн"
    )

    /** Служебные токены (после транслитерации), не несущие информации о магазине. */
    private val STOP_TOKENS = setOf(
        "rus", "ru", "rf", "russia", "rossiya",
        "moscow", "moskva", "msk", "spb", "sankt", "peterburg", "petersburg",
        "g", "gor", "ooo", "oao", "pao", "zao", "ao", "ip",
        "oplata", "pokupka", "karta", "karte", "kartoy", "po", "cherez", "v", "na",
        "mir", "pay", "mirpay", "sber", "sberbank", "sberbanka", "sbera", "onlayn", "online",
        "card", "visa", "mastercard", "shop", "store", "magazin"
    )

    private val TRANSLIT = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    private val TOKEN_REGEX = Regex("[a-zа-яё]+")

    /** Нижний регистр + транслитерация кириллицы в латиницу (цифры и прочие символы сохраняются). */
    fun transliterate(raw: String): String {
        val lower = raw.lowercase()
        val sb = StringBuilder(lower.length + 8)
        for (ch in lower) {
            val mapped = TRANSLIT[ch]
            if (mapped != null) sb.append(mapped) else sb.append(ch)
        }
        return sb.toString()
    }

    /** Значимые латинские токены мерчанта (без цифр, служебных слов и токенов короче 2 символов). */
    fun significantTokens(raw: String): List<String> =
        TOKEN_REGEX.findAll(raw.lowercase())
            .map { transliterate(it.value) }
            .filter { it.length >= 2 && it !in STOP_TOKENS }
            .toList()

    /**
     * Фонетический «скелет» латинского токена: сводит разные схемы транслитерации к одной форме.
     * pyaterochka / PYATEROCHKA → "ptr4k", yandeks / yandex → "ndx".
     */
    fun skeleton(latinToken: String): String {
        var s = latinToken.lowercase()
        s = s.replace("shch", "6").replace("sch", "6").replace("tch", "4")
        s = s.replace("ch", "4").replace("sh", "6").replace("zh", "3").replace("kh", "h")
        s = s.replace("ck", "k").replace("ks", "x").replace("ph", "f")
            .replace("w", "v").replace("q", "k").replace("ts", "c").replace("tz", "c")
        s = s.replace("c", "k")
        s = s.filterNot { it in "aeiouyjh" }
        val collapsed = StringBuilder(s.length)
        for (ch in s) {
            if (collapsed.isEmpty() || collapsed.last() != ch) collapsed.append(ch)
        }
        return collapsed.toString()
    }

    /** true, если мерчант пустой или шаблонный («Оплата через Mir Pay», «Покупка по карте Сбера» и т.п.). */
    fun isGenericMerchant(raw: String): Boolean {
        val normalized = raw.lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return true
        if (normalized in GENERIC_MERCHANTS) return true
        return significantTokens(raw).isEmpty()
    }

    /**
     * Могут ли два названия описывать одну и ту же покупку.
     * Если один из мерчантов пустой/шаблонный — считаем совместимыми (фолбэк: сравнивать не с чем).
     */
    fun areCompatible(first: String, second: String): Boolean {
        if (isGenericMerchant(first) || isGenericMerchant(second)) return true

        val tokensA = significantTokens(first)
        val tokensB = significantTokens(second)

        // Целиком склеенные строки (на случай «X5 Retail» vs «X5RETAIL»)
        val joinedA = tokensA.joinToString("")
        val joinedB = tokensB.joinToString("")
        if (joinedA == joinedB) return true

        for (a in tokensA) {
            for (b in tokensB) {
                if (tokensMatch(a, b)) return true
            }
        }

        val joinedSkeletonA = skeleton(joinedA)
        val joinedSkeletonB = skeleton(joinedB)
        if (joinedSkeletonA.length >= 4 && joinedSkeletonB.length >= 4 &&
            (joinedSkeletonA.contains(joinedSkeletonB) || joinedSkeletonB.contains(joinedSkeletonA))
        ) {
            return true
        }
        return false
    }

    private fun tokensMatch(a: String, b: String): Boolean {
        if (a == b) return a.length >= 3
        if (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a))) return true
        val skA = skeleton(a)
        val skB = skeleton(b)
        if (skA.length < 3 || skB.length < 3) return false
        if (skA == skB) return true
        return skA.length >= 4 && skB.length >= 4 && (skA.startsWith(skB) || skB.startsWith(skA))
    }
}
