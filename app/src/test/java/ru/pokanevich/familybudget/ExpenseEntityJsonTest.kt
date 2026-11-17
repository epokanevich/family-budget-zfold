package ru.pokanevich.familybudget

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity

class ExpenseEntityJsonTest {

    @Test
    fun roundTrip_defaultValues_restoresAccurately() {
        val original = ExpenseEntity(
            id = "expense-uuid-12345",
            amountRub = 850.50,
            merchant = "Пятёрочка",
            categoryName = ExpenseCategory.SUPERMARKET.name,
            authorName = "Евгений",
            sourceType = "MANUAL",
            isPendingConfirmation = false,
            isDeleted = false,
            isSynced = false,
            createdAtEpochMs = 1700000000000L,
            updatedAtEpochMs = 1700000000000L,
            rawPushOrQrText = "",
            dedupSignature = ""
        )

        val json = original.toJsonObject()
        val restored = ExpenseEntity.fromJsonObject(json)

        assertNotNull(restored)
        assertEquals(original.id, restored!!.id)
        assertEquals(original.amountRub, restored.amountRub, 0.001)
        assertEquals(original.merchant, restored.merchant)
        assertEquals(original.categoryName, restored.categoryName)
        assertEquals(original.authorName, restored.authorName)
        assertEquals(original.sourceType, restored.sourceType)
        assertEquals(original.isPendingConfirmation, restored.isPendingConfirmation)
        assertEquals(original.isDeleted, restored.isDeleted)
        assertTrue(restored.isSynced) // Restored from JSON marks as synced
        assertEquals(original.createdAtEpochMs, restored.createdAtEpochMs)
        assertEquals(original.updatedAtEpochMs, restored.updatedAtEpochMs)
        assertEquals(original.rawPushOrQrText, restored.rawPushOrQrText)
        assertEquals(original.dedupSignature, restored.dedupSignature)
    }

    @Test
    fun roundTrip_customValues_restoresAllFields() {
        val original = ExpenseEntity(
            id = "custom-uuid-67890",
            amountRub = 4999.99,
            merchant = "ВкусВилл Экспресс",
            categoryName = ExpenseCategory.CAFE.name,
            authorName = "Анжелика",
            sourceType = "PUSH_SBER",
            isPendingConfirmation = true,
            isDeleted = true,
            isSynced = false,
            createdAtEpochMs = 1700000000000L,
            updatedAtEpochMs = 1700000050000L,
            rawPushOrQrText = "Покупка 4999.99 ₽ ВкусВилл Экспресс",
            dedupSignature = "sber_4999.99_1700000000"
        )

        val json = original.toJsonObject()
        val restored = ExpenseEntity.fromJsonObject(json)

        assertNotNull(restored)
        assertEquals(original.id, restored!!.id)
        assertEquals(original.amountRub, restored.amountRub, 0.001)
        assertEquals(original.merchant, restored.merchant)
        assertEquals(original.categoryName, restored.categoryName)
        assertEquals(original.authorName, restored.authorName)
        assertEquals(original.sourceType, restored.sourceType)
        assertEquals(original.isPendingConfirmation, restored.isPendingConfirmation)
        assertEquals(original.isDeleted, restored.isDeleted)
        assertTrue(restored.isSynced)
        assertEquals(original.createdAtEpochMs, restored.createdAtEpochMs)
        assertEquals(original.updatedAtEpochMs, restored.updatedAtEpochMs)
        assertEquals(original.rawPushOrQrText, restored.rawPushOrQrText)
        assertEquals(original.dedupSignature, restored.dedupSignature)
    }

    @Test
    fun roundTrip_incomeCategory_preserved() {
        val income = ExpenseEntity(
            id = "salary-1",
            amountRub = 120000.0,
            merchant = "Аванс",
            categoryName = ExpenseCategory.SALARY.name
        )

        val restored = ExpenseEntity.fromJsonObject(income.toJsonObject())
        assertNotNull(restored)
        assertEquals(ExpenseCategory.SALARY, restored!!.category)
        assertTrue(restored.isIncome)
    }

    @Test
    fun fromJsonObject_invalidOrMissingId_returnsNull() {
        // Missing id
        val noId = JSONObject().apply {
            put("amountRub", 500.0)
            put("merchant", "Аптека")
        }
        assertNull(ExpenseEntity.fromJsonObject(noId))

        // Blank id
        val blankId = JSONObject().apply {
            put("id", "   ")
            put("amountRub", 500.0)
        }
        assertNull(ExpenseEntity.fromJsonObject(blankId))

        // Empty json
        assertNull(ExpenseEntity.fromJsonObject(JSONObject()))
    }

    @Test
    fun toJsonObject_compactOmissions() {
        val entity = ExpenseEntity(
            id = "compact-1",
            amountRub = 100.0,
            merchant = "Магнит",
            authorName = "Евгений", // default
            sourceType = "MANUAL",  // default
            isPendingConfirmation = false,
            isDeleted = false,
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 1000L, // same as createdAt
            rawPushOrQrText = "",
            dedupSignature = ""
        )

        val json = entity.toJsonObject()

        // Defaults should be omitted in JSON to save payload size
        assertFalse(json.has("authorName"))
        assertFalse(json.has("sourceType"))
        assertFalse(json.has("isPendingConfirmation"))
        assertFalse(json.has("isDeleted"))
        assertFalse(json.has("updatedAtEpochMs"))
        assertFalse(json.has("rawPushOrQrText"))
        assertFalse(json.has("dedupSignature"))

        // Required fields must be present
        assertTrue(json.has("id"))
        assertTrue(json.has("amountRub"))
        assertTrue(json.has("merchant"))
        assertTrue(json.has("categoryName"))
        assertTrue(json.has("createdAtEpochMs"))
    }
}
