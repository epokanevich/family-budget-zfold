package ru.pokanevich.familybudget

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.OperationType

class ExpenseEntityJsonTest {

    @Test
    fun roundTrip_defaultValues_restoresAccurately() {
        val original = ExpenseEntity(
            id = "expense-uuid-12345",
            amountRub = 850.50,
            merchant = "Пятёрочка",
            categoryName = ExpenseCategory.GROCERIES.name,
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
            categoryName = ExpenseCategory.CAFES.name,
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
            categoryName = ExpenseCategory.INCOME_SALARY.name
        )

        val restored = ExpenseEntity.fromJsonObject(income.toJsonObject())
        assertNotNull(restored)
        assertEquals(ExpenseCategory.INCOME_SALARY, restored!!.category)
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
        assertFalse(json.has("note"))
        assertFalse(json.has("splitGroupId"))
        assertFalse(json.has("operationType"))

        // Required fields must be present
        assertTrue(json.has("id"))
        assertTrue(json.has("amountRub"))
        assertTrue(json.has("merchant"))
        assertTrue(json.has("categoryName"))
        assertTrue(json.has("createdAtEpochMs"))
    }

    @Test
    fun toJsonObject_omitsOperationTypeWhenMatchesCategoryDefault() {
        val explicitExpense = ExpenseEntity(
            id = "exp-explicit",
            amountRub = 250.0,
            merchant = "Аптека",
            categoryName = ExpenseCategory.HEALTH.name,
            operationType = OperationType.EXPENSE.name
        )
        val expJson = explicitExpense.toJsonObject()
        assertFalse(expJson.has("operationType"))
        assertEquals(OperationType.EXPENSE, explicitExpense.effectiveOperationType)
        assertFalse(explicitExpense.isIncome)
        assertFalse(explicitExpense.isTransfer)

        val explicitIncome = ExpenseEntity(
            id = "inc-explicit",
            amountRub = 50_000.0,
            merchant = "Зарплата",
            categoryName = ExpenseCategory.INCOME_SALARY.name,
            operationType = OperationType.INCOME.name
        )
        val incJson = explicitIncome.toJsonObject()
        assertFalse(incJson.has("operationType"))
        assertEquals(OperationType.INCOME, explicitIncome.effectiveOperationType)
        assertTrue(explicitIncome.isIncome)
        assertFalse(explicitIncome.isTransfer)
    }

    @Test
    fun roundTrip_noteSplitGroupAndTransfer_restoresAllFields() {
        val original = ExpenseEntity(
            id = "transfer-uuid-1",
            amountRub = 15_000.0,
            merchant = "Перевод на накопительный счёт",
            categoryName = ExpenseCategory.OTHER.name,
            authorName = "Евгений",
            createdAtEpochMs = 1700000000000L,
            updatedAtEpochMs = 1700000000000L,
            note = "Отпускная подушка",
            splitGroupId = "split-group-abc",
            operationType = OperationType.TRANSFER.name
        )

        val json = original.toJsonObject()
        assertEquals("Отпускная подушка", json.getString("note"))
        assertEquals("split-group-abc", json.getString("splitGroupId"))
        assertEquals("TRANSFER", json.getString("operationType"))

        val restored = ExpenseEntity.fromJsonObject(json)
        assertNotNull(restored)
        assertEquals(original.note, restored!!.note)
        assertEquals(original.splitGroupId, restored.splitGroupId)
        assertEquals("TRANSFER", restored.operationType)
        assertEquals(OperationType.TRANSFER, restored.effectiveOperationType)
        assertTrue(restored.isTransfer)
        assertFalse(restored.isIncome)
    }

    @Test
    fun roundTrip_transferWithIncomeCategory_remainsTransfer() {
        val transfer = ExpenseEntity(
            id = "transfer-inc-cat",
            amountRub = 10_000.0,
            merchant = "Между счетами",
            categoryName = ExpenseCategory.INCOME_OTHER.name,
            operationType = OperationType.TRANSFER.name
        )

        val json = transfer.toJsonObject()
        assertEquals("TRANSFER", json.getString("operationType"))

        val restored = ExpenseEntity.fromJsonObject(json)
        assertNotNull(restored)
        assertEquals(OperationType.TRANSFER, restored!!.effectiveOperationType)
        assertTrue(restored.isTransfer)
        assertFalse(restored.isIncome)
    }

    @Test
    fun fromJsonObject_legacyJsonWithoutNewFields_usesSafeDefaults() {
        val legacyExpenseJson = JSONObject().apply {
            put("id", "legacy-exp-1")
            put("amountRub", 630.0)
            put("merchant", "Обед")
            put("categoryName", ExpenseCategory.WORK_FOOD.name)
            put("createdAtEpochMs", 1763297160000L)
        }
        val expense = ExpenseEntity.fromJsonObject(legacyExpenseJson)
        assertNotNull(expense)
        assertEquals("", expense!!.note)
        assertEquals("", expense.splitGroupId)
        assertEquals("", expense.operationType)
        assertEquals(OperationType.EXPENSE, expense.effectiveOperationType)
        assertFalse(expense.isIncome)
        assertFalse(expense.isTransfer)
        assertFalse(expense.toJsonObject().has("note"))
        assertFalse(expense.toJsonObject().has("splitGroupId"))
        assertFalse(expense.toJsonObject().has("operationType"))

        val legacyIncomeJson = JSONObject().apply {
            put("id", "legacy-inc-1")
            put("amountRub", 95_000.0)
            put("merchant", "Зарплата")
            put("categoryName", ExpenseCategory.INCOME_SALARY.name)
            put("createdAtEpochMs", 1763297160000L)
        }
        val income = ExpenseEntity.fromJsonObject(legacyIncomeJson)
        assertNotNull(income)
        assertEquals("", income!!.note)
        assertEquals("", income.splitGroupId)
        assertEquals("", income.operationType)
        assertEquals(OperationType.INCOME, income.effectiveOperationType)
        assertTrue(income.isIncome)
        assertFalse(income.isTransfer)
        assertFalse(income.toJsonObject().has("operationType"))
    }

    @Test
    fun fromJsonObject_nullOrUnknownOperationType_fallsBackSafely() {
        val jsonWithNulls = JSONObject().apply {
            put("id", "null-fields-1")
            put("amountRub", 400.0)
            put("merchant", "Кофе")
            put("categoryName", ExpenseCategory.CAFES.name)
            put("note", JSONObject.NULL)
            put("splitGroupId", JSONObject.NULL)
            put("operationType", JSONObject.NULL)
        }
        val restoredNulls = ExpenseEntity.fromJsonObject(jsonWithNulls)
        assertNotNull(restoredNulls)
        assertEquals("", restoredNulls!!.note)
        assertEquals("", restoredNulls.splitGroupId)
        assertEquals("", restoredNulls.operationType)
        assertEquals(OperationType.EXPENSE, restoredNulls.effectiveOperationType)

        val unknownOp = ExpenseEntity(
            id = "unknown-op-1",
            amountRub = 100.0,
            categoryName = ExpenseCategory.INCOME_GIFT.name,
            operationType = "FUTURE_OP_TYPE"
        )
        assertEquals(OperationType.INCOME, unknownOp.effectiveOperationType)
        assertTrue(unknownOp.isIncome)
        assertFalse(unknownOp.isTransfer)
    }

    @Test
    fun roundTrip_unknownCustomCategoryId_preservesCategoryNameWithoutOverwriting() {
        val customId = "custom_1763300000_ab12"
        val original = ExpenseEntity(
            id = "custom-cat-op-1",
            amountRub = 1250.0,
            merchant = "Кофе с собой",
            categoryName = customId,
            createdAtEpochMs = 1763300000000L,
            updatedAtEpochMs = 1763300000000L
        )

        val json = original.toJsonObject()
        assertEquals(customId, json.getString("categoryName"))

        val restored = ExpenseEntity.fromJsonObject(json)
        assertNotNull(restored)
        assertEquals(customId, restored!!.categoryName)
        assertEquals(OperationType.EXPENSE, restored.effectiveOperationType)
    }

    @Test
    fun roundTrip_originDeviceId_preservedAndOmittedWhenEmpty() {
        val withDevice = ExpenseEntity(
            id = "dev-1",
            amountRub = 500.0,
            originDeviceId = "install-uuid-1234"
        )
        val json = withDevice.toJsonObject()
        assertEquals("install-uuid-1234", json.getString("originDeviceId"))
        assertEquals("install-uuid-1234", ExpenseEntity.fromJsonObject(json)?.originDeviceId)

        val withoutDevice = ExpenseEntity(id = "dev-2", amountRub = 500.0)
        assertFalse(withoutDevice.toJsonObject().has("originDeviceId"))
    }
}
