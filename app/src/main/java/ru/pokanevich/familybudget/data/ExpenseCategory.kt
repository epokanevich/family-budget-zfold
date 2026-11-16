package ru.pokanevich.familybudget.data

enum class ExpenseCategory(
    val displayName: String,
    val shortName: String,
    val emoji: String,
    val recommendedSharePercent: Int,
    val isIncome: Boolean = false
) {
    // 14 категорий РАСХОДОВ (перенесены 1-в-1 из Money Manager)
    GROCERIES("Продукты", "Продукты", "🛒", 20, false),
    CAFES("Кафе", "Кафе", "☕", 7, false),
    LEISURE("Досуг", "Досуг", "🎮", 2, false),
    TRANSPORT("Транспорт", "Транспорт", "🚌", 4, false),
    WORK_FOOD("Еда на работе", "На работе", "🍽️", 5, false),
    HEALTH("Здоровье", "Здоровье", "❤️", 3, false),
    SHOPPING("Покупки", "Покупки", "🛍️", 17, false),
    GIFTS("Подарки", "Подарки", "🎁", 12, false),
    UTILITIES("Коммунальные услуги", "Коммун.", "🏠", 10, false),
    CREDIT("Кредит", "Кредит", "💳", 4, false),
    PETS("Животные", "Животные", "🐾", 6, false),
    MOTO("Моторасходники", "Мото", "🏍️", 4, false),
    BABY("Малыш", "Малыш", "👶", 3, false),
    OTHER("Другое", "Другое", "❓", 3, false),

    // 4 категории ДОХОДОВ (перенесены 1-в-1 из Money Manager)
    INCOME_SALARY("Зарплата", "Зарплата", "💵", 0, true),
    INCOME_GIFT("Подарок", "Подарок", "🎁", 0, true),
    INCOME_INTEREST("Проценты по вкладу", "Проценты", "🏦", 0, true),
    INCOME_OTHER("Другое (доход)", "Другое", "💰", 0, true);

    companion object {
        val expenseCategories: List<ExpenseCategory>
            get() = entries.filter { !it.isIncome }

        val incomeCategories: List<ExpenseCategory>
            get() = entries.filter { it.isIncome }

        fun fromStoredName(raw: String): ExpenseCategory {
            return when (raw) {
                // Совместимость со старыми ключами v1/v2
                "BABY_AND_PETS" -> PETS
                "CAFES_AND_DELIVERY" -> CAFES
                "HOME_AND_UTILITIES" -> UTILITIES
                "TRANSPORT_AND_MOTO" -> TRANSPORT
                "HEALTH_AND_PHARMACY" -> HEALTH
                "LEISURE_AND_HOBBIES" -> LEISURE
                else -> runCatching { valueOf(raw) }.getOrDefault(OTHER)
            }
        }

        fun fromMoneyManagerUid(uid: String?, isIncome: Boolean): ExpenseCategory {
            if (isIncome) {
                return when (uid) {
                    "DefaultSalary" -> INCOME_SALARY
                    "DefaultPresent" -> INCOME_GIFT
                    "DefaultPercents" -> INCOME_INTEREST
                    else -> INCOME_OTHER
                }
            }
            return when (uid) {
                "DefaultHome" -> GROCERIES
                "DefaultCafe" -> CAFES
                "DefaultLeisure" -> LEISURE
                "DefaultTransport" -> TRANSPORT
                "DefaultProducts" -> WORK_FOOD
                "DefaultHealth" -> HEALTH
                "DefaultEducation" -> SHOPPING
                "DefaultPresents" -> GIFTS
                "DefaultFamily" -> UTILITIES
                "DefaultSport" -> CREDIT
                "0af1efb7-bbfa-4e81-bd70-319efb5cc56a" -> PETS
                "481d02d0-0069-488b-8e50-7211524e168a" -> MOTO
                "31cb24ce-e9e9-42f7-8bc0-372d6433e1bb" -> BABY
                else -> OTHER
            }
        }

        fun guessFromMerchant(merchantRaw: String, isIncome: Boolean = false): ExpenseCategory {
            val text = merchantRaw.lowercase()
            if (isIncome) {
                return when {
                    listOf("зарплат", "аванс", "оклад", "премия").any { text.contains(it) } -> INCOME_SALARY
                    listOf("процент", "вклад", "кэшбэк", "кешбэк", "дивиденд").any { text.contains(it) } -> INCOME_INTEREST
                    listOf("подарок", "поздравлен").any { text.contains(it) } -> INCOME_GIFT
                    else -> INCOME_OTHER
                }
            }
            return when {
                listOf("пятерочка", "пятёрочка", "pyaterochka", "перекресток", "перекрёсток", "perekrestok",
                    "спар", "spar", "eurospar", "виктория", "магнит", "magnit", "лента", "lenta",
                    "вкусвилл", "vkusvill", "самокат", "samokat", "ашан", "auchan", "метро", "красное", "к&б", "продукт").any { text.contains(it) } -> GROCERIES

                listOf("столов", "обед", "бизнес-ланч", "ланч", "на работе").any { text.contains(it) } -> WORK_FOOD

                listOf("кафе", "ресторан", "кофе", "coffee", "skuratov", "dodo", "додо", "яндекс еда",
                    "yandex.eda", "delivery", "вкусно и точка", "бургер", "пекарня", "круассан", "kfc", "rostics", "шаурм").any { text.contains(it) } -> CAFES

                listOf("подгуз", "памперс", "малыш", "коляск", "смесь", "пюре", "детский мир", "detmir").any { text.contains(it) } -> BABY

                listOf("зоо", "petshop", "четыре лапы", "4lapy", "корм", "ветклиник", "наполнител", "животн").any { text.contains(it) } -> PETS

                listOf("мото", "шлем", "экип", "цепь", "мотюль", "motul", "шиномонтаж мото").any { text.contains(it) } -> MOTO

                listOf("жкх", "энерго", "водоканал", "интернет", "ростелеком", "мтс", "мегафон", "билайн",
                    "tele2", "коммунал", "квартплат", "газ").any { text.contains(it) } -> UTILITIES

                listOf("кредит", "ипотек", "рассрочк", "займ").any { text.contains(it) } -> CREDIT

                listOf("подарок", "цветы", "букет", "сувенир", "др ").any { text.contains(it) } -> GIFTS

                listOf("ozon", "озон", "wildberries", "wb", "леруа", "лемана", "бауцентр", "baucenter",
                    "dns", "днс", "мвидео", "одежд", "обув", "маркет").any { text.contains(it) } -> SHOPPING

                listOf("азс", "лукойл", "lukoil", "газпромнефть", "роснефть", "сургутнефтегаз", "нефтегаз",
                    "такси", "yandex.go", "яндекс го", "авто", "парковка", "метро", "автобус", "билет").any { text.contains(it) } -> TRANSPORT

                listOf("аптека", "apteka", "ригла", "планета здоровья", "апрель", "клиника", "инвитро",
                    "гемотест", "стоматолог", "врач", "анализ").any { text.contains(it) } -> HEALTH

                listOf("кино", "steam", "игры", "подписка", "яндекс плюс", "книги", "спорт", "фитнес").any { text.contains(it) } -> LEISURE

                else -> OTHER
            }
        }
    }
}
