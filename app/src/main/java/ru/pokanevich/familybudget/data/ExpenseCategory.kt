package ru.pokanevich.familybudget.data

enum class ExpenseCategory(
    val displayName: String,
    val shortName: String,
    val emoji: String,
    val recommendedSharePercent: Int
) {
    GROCERIES("Продукты и супермаркеты", "Продукты", "🛒", 35),
    BABY_AND_PETS("Ребёнок и кошки", "Малыш и кот", "🍼", 15),
    CAFES_AND_DELIVERY("Кафе, кофе и доставка", "Кафе", "☕", 10),
    HOME_AND_UTILITIES("Дом, ЖКХ и быт", "Дом и ЖКХ", "🏠", 15),
    TRANSPORT_AND_MOTO("Транспорт, авто и мото", "Транспорт", "🏍️", 10),
    HEALTH_AND_PHARMACY("Аптеки и здоровье", "Здоровье", "💊", 5),
    LEISURE_AND_HOBBIES("Досуг, игры и подписки", "Досуг", "🎮", 5),
    OTHER("Прочие покупки", "Прочее", "📦", 5);

    companion object {
        fun guessFromMerchant(merchantRaw: String): ExpenseCategory {
            val text = merchantRaw.lowercase()
            return when {
                listOf("пятерочка", "пятёрочка", "pyaterochka", "перекресток", "перекрёсток", "perekrestok",
                    "спар", "spar", "eurospar", "виктория", "магнит", "magnit", "лента", "lenta",
                    "вкусвилл", "vkusvill", "самокат", "samokat", "ашан", "auchan", "метро", "красное", "к&б").any { text.contains(it) } -> GROCERIES

                listOf("детский мир", "detmir", "зоо", "petshop", "четыре лапы", "4lapy", "подгуз", "корма").any { text.contains(it) } -> BABY_AND_PETS

                listOf("кафе", "ресторан", "кофе", "coffee", "skuratov", "dodo", "додо", "яндекс еда",
                    "yandex.eda", "delivery", "вкусно и точка", "бургер", "пекарня", "круассан", "kfc", "rostics").any { text.contains(it) } -> CAFES_AND_DELIVERY

                listOf("жкх", "энерго", "водоканал", "интернет", "ростелеком", "мтс", "мегафон", "билайн",
                    "tele2", "леруа", "лемана", "бауцентр", "baucenter", "ozon", "озон", "wildberries", "wb").any { text.contains(it) } -> HOME_AND_UTILITIES

                listOf("азс", "лукойл", "lukoil", "газпромнефть", "роснефть", "сургутнефтегаз", "нефтегаз",
                    "такси", "yandex.go", "яндекс го", "мото", "авто", "парковка", "шиномонтаж").any { text.contains(it) } -> TRANSPORT_AND_MOTO

                listOf("аптека", "apteka", "ригла", "планета здоровья", "апрель", "клиника", "инвитро", "гемотест").any { text.contains(it) } -> HEALTH_AND_PHARMACY

                listOf("кино", "steam", "игры", "подписка", "яндекс плюс", "книги", "спорт").any { text.contains(it) } -> LEISURE_AND_HOBBIES

                else -> OTHER
            }
        }
    }
}
