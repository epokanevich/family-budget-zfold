package ru.pokanevich.familybudget

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.pokanevich.familybudget.ai.AiBudgetAdvisor
import ru.pokanevich.familybudget.ai.AiChatContextSnapshot
import ru.pokanevich.familybudget.ai.AiChatMessage
import ru.pokanevich.familybudget.ai.AiChatRole
import ru.pokanevich.familybudget.ai.GeminiHttpException

class AiBudgetAdvisorChatTest {

    private val sampleSnapshot = AiChatContextSnapshot(
        baseSummary = "Текущий месяц: потрачено 45 000 ₽",
        searchCriteria = "Критерии: маркетплейс.",
        matchedAggregates = "Найдено записей: 2. Сумма: 5 000 ₽.",
        relevantRecordLines = listOf("2026-11-10 | Расход | Покупки | 5 000 ₽ | OZON | Заказ | Евгений"),
        totalMatchedCount = 2,
        totalRecordsInDatabase = 4115
    )

    @Before
    fun setUp() {
        AiBudgetAdvisor.resetForTest()
    }

    @After
    fun tearDown() {
        AiBudgetAdvisor.resetForTest()
    }

    @Test
    fun buildChatRequestJson_includesSystemInstructionContextAndSlidingWindow() {
        val history = mutableListOf<AiChatMessage>()
        for (i in 1..6) {
            history += AiChatMessage(role = AiChatRole.USER, text = "Вопрос $i")
            history += AiChatMessage(role = AiChatRole.MODEL, text = "Ответ $i")
        }
        // Ошибочное сообщение не должно попадать в историю промпта
        history += AiChatMessage(role = AiChatRole.MODEL, text = "Ошибка HTTP 429", isError = true)

        val jsonStr = AiBudgetAdvisor.buildChatRequestJson(
            history = history,
            userQuestion = "Сколько ушло на маркетплейсы?",
            contextSnapshot = sampleSnapshot,
            historyLimit = 8
        )
        val root = JSONObject(jsonStr)

        // Системная инструкция присутствует
        val sysText = root.getJSONObject("system_instruction")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        assertTrue(sysText.contains("семейный финансовый советник"))

        // Скользящее окно: 8 последних валидных (Вопрос 3..Ответ 6) + 1 текущий вопрос = 9 элементов
        val contents = root.getJSONArray("contents")
        assertEquals(9, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals(
            "Вопрос 3",
            contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        )
        assertFalse(jsonStr.contains("Вопрос 1"))
        assertFalse(jsonStr.contains("Ошибка HTTP 429"))

        // Последний ход — текущий вопрос с подготовленным срезом
        val lastTurn = contents.getJSONObject(8)
        assertEquals("user", lastTurn.getString("role"))
        val lastText = lastTurn.getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(lastText.contains("Текущий месяц: потрачено 45 000 ₽"))
        assertTrue(lastText.contains("OZON"))
        assertTrue(lastText.contains("Сколько ушло на маркетплейсы?"))
    }

    @Test
    fun askFinancialAdvisorChat_remembersLastSuccessfulModelFirst() = runBlocking {
        val calledModels = mutableListOf<String>()
        AiBudgetAdvisor.modelCaller = { _, model, _ ->
            calledModels += model
            if (model == "gemini-flash-latest") {
                Result.failure(GeminiHttpException(404, "Not found"))
            } else {
                Result.success("Ответ от $model")
            }
        }
        AiBudgetAdvisor.availableModelsFetcher = { emptyList() }

        val firstReply = AiBudgetAdvisor.askFinancialAdvisorChat(
            apiKey = "test-key",
            history = emptyList(),
            userQuestion = "Первый вопрос",
            contextSnapshot = sampleSnapshot
        ).getOrThrow()

        assertEquals("gemini-flash-lite-latest", firstReply.modelUsed)
        assertEquals(2, firstReply.attemptsCount)
        assertEquals(listOf("gemini-flash-latest", "gemini-flash-lite-latest"), calledModels)

        calledModels.clear()

        val secondReply = AiBudgetAdvisor.askFinancialAdvisorChat(
            apiKey = "test-key",
            history = listOf(
                AiChatMessage(role = AiChatRole.USER, text = "Первый вопрос"),
                AiChatMessage(role = AiChatRole.MODEL, text = firstReply.text, modelUsed = firstReply.modelUsed)
            ),
            userQuestion = "Второй вопрос",
            contextSnapshot = sampleSnapshot
        ).getOrThrow()

        assertEquals("gemini-flash-lite-latest", secondReply.modelUsed)
        assertEquals(1, secondReply.attemptsCount)
        assertEquals(listOf("gemini-flash-lite-latest"), calledModels)
    }

    @Test
    fun askFinancialAdvisorChat_stopsImmediatelyOnHttp429WithFriendlyMessage() = runBlocking {
        var callCount = 0
        var discoveryCalls = 0
        AiBudgetAdvisor.modelCaller = { _, _, _ ->
            callCount++
            Result.failure(GeminiHttpException(429, "Quota exceeded for quota metric"))
        }
        AiBudgetAdvisor.availableModelsFetcher = {
            discoveryCalls++
            listOf("gemini-other")
        }

        val result = AiBudgetAdvisor.askFinancialAdvisorChat(
            apiKey = "test-key",
            history = emptyList(),
            userQuestion = "На чём мы сэкономили за полгода?",
            contextSnapshot = sampleSnapshot
        )

        assertTrue(result.isFailure)
        assertEquals(1, callCount)
        assertEquals(0, discoveryCalls)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("429"))
        assertTrue(message.contains("лимит", ignoreCase = true))
    }
}
