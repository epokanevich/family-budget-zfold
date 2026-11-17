package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.ai.AiDatabaseContextBuilder
import ru.pokanevich.familybudget.data.CategoryDescriptor
import ru.pokanevich.familybudget.data.CategoryLimitMode
import ru.pokanevich.familybudget.data.CategoryRegistry
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.OperationType
import ru.pokanevich.familybudget.data.RecurringPaymentEntity
import ru.pokanevich.familybudget.data.SavingsGoalCalculator
import ru.pokanevich.familybudget.data.SavingsGoalEntity
import java.time.LocalDateTime
import java.time.ZoneId

class AiDatabaseContextBuilderTest {

    private val zoneId: ZoneId = ZoneId.of("Europe/Moscow")

    private fun epochAt(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        LocalDateTime.of(year, month, day, hour, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

    @Test
    fun neverIncludesRawPushOrQrText_inPromptSnapshotOrSearch() {
        val nowMs = epochAt(2026, 11, 15)
        val secretCard = "4276 5500 1234 9876"
        val secretMarker = "СекретныйПушМаркерБаланса"
        val expenses = listOf(
            ExpenseEntity(
                id = "e-1",
                amountRub = 1_450.0,
                merchant = "Пятёрочка",
                categoryName = ExpenseCategory.GROCERIES.name,
                authorName = "Евгений",
                note = "Продукты на ужин",
                rawPushOrQrText = "MIR-9876 $secretCard Покупка 1450р Пятерочка Баланс: 198 500р $secretMarker",
                createdAtEpochMs = epochAt(2026, 11, 10)
            ),
            ExpenseEntity(
                id = "e-2",
                amountRub = 2_500.0,
                merchant = "OZON",
                categoryName = ExpenseCategory.SHOPPING.name,
                authorName = "Анжелика",
                note = "Покупки дом",
                rawPushOrQrText = "Карта *4455 Оплата OZON Баланс 50000 $secretMarker",
                createdAtEpochMs = epochAt(2026, 11, 11)
            )
        )

        val snapshotGeneral = AiDatabaseContextBuilder.build(
            question = "Сколько потратили в Пятёрочке и на Озоне?",
            expenses = expenses,
            monthlyBudgetRub = 100_000.0,
            nowMs = nowMs,
            zoneId = zoneId
        )
        val promptGeneral = snapshotGeneral.toPromptText()

        assertFalse(promptGeneral.contains(secretCard))
        assertFalse(promptGeneral.contains(secretMarker))
        assertFalse(promptGeneral.contains("198 500"))
        assertFalse(promptGeneral.contains("9876"))
        assertFalse(promptGeneral.contains("4455"))
        assertFalse(snapshotGeneral.baseSummary.contains(secretMarker))
        snapshotGeneral.relevantRecordLines.forEach { line ->
            assertFalse(line.contains(secretMarker))
            assertFalse(line.contains(secretCard))
        }

        // Поиск по слову, которое есть ТОЛЬКО в rawPushOrQrText, не должен находить запись
        val byRawOnly = AiDatabaseContextBuilder.findRelevantRecords(
            question = "Найди $secretMarker",
            expenses = expenses,
            nowMs = nowMs,
            zoneId = zoneId
        )
        assertTrue(byRawOnly.isEmpty())
    }

    @Test
    fun capsRelevantRecordsAtFifty_evenForLargeDatabase() {
        val nowMs = epochAt(2026, 11, 17)
        val totalSize = 4_200
        val expenses = (0 until totalSize).map { i ->
            val isMarketplace = i % 10 == 0 // 420 записей маркетплейсов
            val monthOffset = i % 11
            val baseDate = LocalDateTime.of(2026, 11, 15, 12, 0).minusMonths(monthOffset.toLong())
            ExpenseEntity(
                id = "bulk-$i",
                amountRub = 500.0 + (i % 50) * 10.0,
                merchant = if (isMarketplace) "OZON" else "Магазин №$i",
                categoryName = if (isMarketplace) ExpenseCategory.SHOPPING.name else ExpenseCategory.GROCERIES.name,
                authorName = if (i % 2 == 0) "Евгений" else "Анжелика",
                note = if (isMarketplace) "Заказ маркетплейс $i" else "Чек $i",
                rawPushOrQrText = "MIR-1122 Списание ${500 + i} Баланс 100000",
                createdAtEpochMs = baseDate.atZone(zoneId).toInstant().toEpochMilli()
            )
        }

        val snapshot = AiDatabaseContextBuilder.build(
            question = "Сколько ушло на маркетплейсы?",
            expenses = expenses,
            monthlyBudgetRub = 150_000.0,
            nowMs = nowMs,
            zoneId = zoneId
        )

        assertEquals(totalSize, snapshot.totalRecordsInDatabase)
        assertEquals(420, snapshot.totalMatchedCount)
        assertEquals(AiDatabaseContextBuilder.MAX_RELEVANT_RECORDS, snapshot.attachedRecordsCount)
        assertTrue(snapshot.relevantRecordLines.size <= 50)
        // Убеждаемся, что сырые 4200 строк не утекли в текст промпта
        val prompt = snapshot.toPromptText()
        assertFalse(prompt.contains("Магазин №2001"))
        assertFalse(prompt.contains("MIR-1122"))
    }

    @Test
    fun excludesDeletedAndPendingRecords_fromAllMetricsAndSearch() {
        val nowMs = epochAt(2026, 11, 15)
        val expenses = listOf(
            ExpenseEntity(
                id = "ok",
                amountRub = 1_200.0,
                merchant = "ВкусВилл",
                categoryName = ExpenseCategory.GROCERIES.name,
                isDeleted = false,
                isPendingConfirmation = false,
                createdAtEpochMs = epochAt(2026, 11, 10)
            ),
            ExpenseEntity(
                id = "deleted",
                amountRub = 99_000.0,
                merchant = "ВкусВилл Удалённый",
                categoryName = ExpenseCategory.GROCERIES.name,
                isDeleted = true,
                isPendingConfirmation = false,
                createdAtEpochMs = epochAt(2026, 11, 11)
            ),
            ExpenseEntity(
                id = "pending",
                amountRub = 77_000.0,
                merchant = "ВкусВилл Черновик",
                categoryName = ExpenseCategory.GROCERIES.name,
                isDeleted = false,
                isPendingConfirmation = true,
                createdAtEpochMs = epochAt(2026, 11, 12)
            )
        )

        val snapshot = AiDatabaseContextBuilder.build(
            question = "Сколько потратили во ВкусВилле?",
            expenses = expenses,
            monthlyBudgetRub = 50_000.0,
            nowMs = nowMs,
            zoneId = zoneId
        )

        assertEquals(1, snapshot.totalRecordsInDatabase)
        assertEquals(1, snapshot.totalMatchedCount)
        assertEquals(1, snapshot.attachedRecordsCount)
        val line = snapshot.relevantRecordLines.single()
        assertTrue(line.contains("ВкусВилл"))
        assertFalse(line.contains("Удалённый"))
        assertFalse(line.contains("Черновик"))
        assertFalse(snapshot.toPromptText().contains("99 000"))
        assertFalse(snapshot.toPromptText().contains("77 000"))
    }

    @Test
    fun selectsRecordsByKeywordCategoryNoteAndMonthYear() {
        val nowMs = epochAt(2026, 11, 15)
        val expenses = listOf(
            ExpenseEntity(
                id = "cat-health-nov",
                amountRub = 3_400.0,
                merchant = "Поликлиника №1",
                categoryName = ExpenseCategory.HEALTH.name,
                note = "Стоматолог",
                createdAtEpochMs = epochAt(2026, 11, 5)
            ),
            ExpenseEntity(
                id = "note-gift-jan",
                amountRub = 4_500.0,
                merchant = "Цветочный салон",
                categoryName = ExpenseCategory.GIFTS.name,
                note = "Букет маме на юбилей",
                createdAtEpochMs = epochAt(2026, 1, 20)
            ),
            ExpenseEntity(
                id = "note-gift-feb",
                amountRub = 1_500.0,
                merchant = "Сувениры",
                categoryName = ExpenseCategory.GIFTS.name,
                note = "Подарок маме в феврале",
                createdAtEpochMs = epochAt(2026, 2, 14)
            ),
            ExpenseEntity(
                id = "wb-synonym",
                amountRub = 2_890.0,
                merchant = "Wildberries",
                categoryName = ExpenseCategory.SHOPPING.name,
                note = "Зимняя куртка",
                createdAtEpochMs = epochAt(2026, 10, 18)
            )
        )

        // 1. Поиск по категории («Здоровье»)
        val byCategory = AiDatabaseContextBuilder.findRelevantRecords(
            question = "Какие расходы на здоровье?",
            expenses = expenses,
            nowMs = nowMs,
            zoneId = zoneId
        )
        assertEquals(listOf("cat-health-nov"), byCategory.map { it.id })

        // 2. Поиск по заметке + конкретному месяцу и году («маме в январе 2026»)
        val byNoteAndMonth = AiDatabaseContextBuilder.findRelevantRecords(
            question = "Сколько ушло маме в январе 2026?",
            expenses = expenses,
            nowMs = nowMs,
            zoneId = zoneId
        )
        assertEquals(listOf("note-gift-jan"), byNoteAndMonth.map { it.id })

        // 3. Поиск по ключевому слову / синониму («маркетплейсы» -> Wildberries)
        val bySynonym = AiDatabaseContextBuilder.findRelevantRecords(
            question = "Сколько ушло на маркетплейсы?",
            expenses = expenses,
            nowMs = nowMs,
            zoneId = zoneId
        )
        assertEquals(listOf("wb-synonym"), bySynonym.map { it.id })

        // 4. Проверка компактного формата строки yyyy-MM-dd | Тип | Категория | Сумма ₽ | Мерчант | Заметка | Автор
        val formatted = AiDatabaseContextBuilder.formatRecordLine(expenses[0], zoneId = zoneId)
        assertEquals(
            "2026-11-05 | Расход | Здоровье | 3 400 ₽ | Поликлиника №1 | Стоматолог | Евгений",
            formatted
        )
    }

    @Test
    fun baseSnapshot_includes12MonthsWithoutTransfers_limits_recurring_goals_andTop15Merchants() {
        val nowMs = epochAt(2026, 11, 15)
        val registry = CategoryRegistry(
            listOf(
                CategoryDescriptor(
                    id = ExpenseCategory.GROCERIES.name,
                    displayName = "Продукты",
                    shortName = "Продукты",
                    emoji = "🛒",
                    colorHex = 0xFF3A7DF0L,
                    isIncome = false,
                    isBuiltIn = true,
                    limitMode = CategoryLimitMode.FIXED_RUB,
                    limitValue = 25_000.0
                )
            )
        )

        val expenses = mutableListOf(
            ExpenseEntity(
                id = "inc-1",
                amountRub = 120_000.0,
                merchant = "ООО Работа",
                categoryName = ExpenseCategory.INCOME_SALARY.name,
                operationType = OperationType.INCOME.name,
                createdAtEpochMs = epochAt(2026, 11, 5)
            ),
            ExpenseEntity(
                id = "groceries-1",
                amountRub = 12_000.0,
                merchant = "Пятёрочка",
                categoryName = ExpenseCategory.GROCERIES.name,
                createdAtEpochMs = epochAt(2026, 11, 8)
            ),
            ExpenseEntity(
                id = "transfer-goal",
                amountRub = 50_000.0,
                merchant = "Копилка Отпуск",
                categoryName = SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT,
                operationType = OperationType.TRANSFER.name,
                goalId = "goal-1",
                createdAtEpochMs = epochAt(2026, 11, 9)
            )
        )
        // 17 разных мерчантов, чтобы проверить лимит Top-15
        for (idx in 1..17) {
            expenses += ExpenseEntity(
                id = "m-$idx",
                amountRub = (20 - idx) * 100.0,
                merchant = "Мерчант_$idx",
                categoryName = ExpenseCategory.SHOPPING.name,
                createdAtEpochMs = epochAt(2026, 10, 1 + (idx % 20))
            )
        }

        val recurring = listOf(
            RecurringPaymentEntity(
                id = "rec-1",
                title = "Ипотека и ЖКХ",
                amountRub = 35_000.0,
                categoryId = ExpenseCategory.UTILITIES.name,
                operationType = OperationType.EXPENSE.name,
                dayOfMonth = 25,
                isActive = true
            )
        )
        val goals = listOf(
            SavingsGoalEntity(
                id = "goal-1",
                title = "Отпуск в Сочи",
                targetAmountRub = 200_000.0,
                initialAmountRub = 10_000.0
            )
        )

        val base = AiDatabaseContextBuilder.buildBaseSnapshot(
            expenses = expenses,
            registry = registry,
            monthlyBudgetRub = 100_000.0,
            recurringPayments = recurring,
            savingsGoals = goals,
            nowMs = nowMs,
            zoneId = zoneId
        )

        // В текущем месяце расход 12 000 ₽ (без 50 000 ₽ перевода в копилку!)
        assertTrue(base.contains("2026-11: доходы 120 000 ₽, расходы 12 000 ₽"))
        // Лимит категории в рублях (фикс) и процентном выражении
        assertTrue(base.contains("Продукты: 12 000 ₽ из лимита 25 000 ₽ [фикс.]"))
        assertTrue(base.contains("% бюджета]"))
        // Регулярный платёж и резерв
        assertTrue(base.contains("Ипотека и ЖКХ: 35 000 ₽"))
        assertTrue(base.contains("Резерв под ещё не списанные регулярные расходы этого месяца: 35 000 ₽"))
        // Копилка: 10 000 initial + 50 000 deposit = 60 000 из 200 000
        assertTrue(base.contains("Отпуск в Сочи: накоплено 60 000 ₽ из 200 000 ₽ (30%)"))
        // Топ-15 мерчантов ограничен 15 строками (самые мелкие Мерчант_16 / Мерчант_17 выпадают, если у Пятёрочки сумма выше)
        val topMerchantsBlock = base.substringAfter("[Топ-15 частых мерчантов за 12 мес.]")
            .trim()
            .lines()
            .filter { it.startsWith("- ") }
        assertEquals(15, topMerchantsBlock.size)
    }
}
