package ru.pokanevich.familybudget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.sync.GithubLegacyMigrator
import java.util.Base64

class GithubLegacyMigratorTest {

    @Test
    fun `fetchFromGithubReadOnly decodes config and budget without mutating github`() {
        val sampleConfig = JSONObject().apply {
            put("schemaVersion", 1)
            put("monthlyBudgetRub", 180000.0)
            put("budgetUpdatedAtEpochMs", 1_760_000_000_000L)
            put("categories", JSONArray())
        }.toString()

        val sampleExpenses = JSONArray().put(
            ExpenseEntity(
                id = "legacy-exp-1",
                amountRub = 750.0,
                merchant = "Магнит",
                categoryName = ExpenseCategory.GROCERIES.name,
                createdAtEpochMs = 1_760_000_000_000L,
                updatedAtEpochMs = 1_760_000_000_000L
            ).toJsonObject()
        ).toString()

        val requestedUrls = mutableListOf<String>()
        val payload = GithubLegacyMigrator.fetchFromGithubReadOnly(
            familyCode = "POKANE-FAMILY",
            token = "test-token"
        ) { url, _ ->
            requestedUrls.add(url)
            val content = when {
                url.endsWith("config_v1.json") -> sampleConfig
                url.endsWith("budget_v3.json") -> sampleExpenses
                else -> ""
            }
            val encoded = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
            JSONObject().put("content", encoded).toString()
        }

        assertNotNull(payload)
        assertEquals(2, requestedUrls.size)
        assertTrue(requestedUrls.all { it.contains("/contents/families/POKANE-FAMILY/") })
        assertEquals(180000.0, payload!!.config!!.monthlyBudgetRub, 0.0)
        assertEquals(1, payload.expenses.size)
        assertEquals("legacy-exp-1", payload.expenses.single().id)
    }

    @Test
    fun `parseFromJsonText parses both raw budget_v3 array and wrapped object`() {
        val rawArr = JSONArray().put(
            ExpenseEntity(
                id = "arr-1",
                amountRub = 420.0,
                merchant = "Ozon",
                categoryName = ExpenseCategory.SHOPPING.name,
                createdAtEpochMs = 1_760_000_000_000L,
                updatedAtEpochMs = 1_760_000_000_000L
            ).toJsonObject()
        ).toString()

        val parsedArr = GithubLegacyMigrator.parseFromJsonText(rawArr)
        assertEquals(1, parsedArr.expenses.size)
        assertEquals("arr-1", parsedArr.expenses.single().id)
    }
}
