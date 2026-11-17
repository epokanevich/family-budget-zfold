package ru.pokanevich.familybudget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.data.BudgetRepository
import ru.pokanevich.familybudget.data.CategoryDescriptor
import ru.pokanevich.familybudget.data.CategoryLimitMode
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.FamilyConfig

class FamilyConfigMergeTest {

    @Test
    fun roundTrip_json_preservesBudgetCategoriesAndSchemaVersion() {
        val customExpense = CategoryDescriptor(
            id = "custom_coffee",
            displayName = "Кофе в зернах",
            shortName = "Кофе",
            emoji = "🫘",
            colorHex = 0xFF8B5CF6L,
            isIncome = false,
            isBuiltIn = false,
            limitMode = CategoryLimitMode.FIXED_RUB,
            limitValue = 4_500.0,
            isArchived = false,
            updatedAtEpochMs = 1_763_400_001_000L
        )
        val customIncome = CategoryDescriptor(
            id = "custom_freelance",
            displayName = "Подработка",
            shortName = "Подработка",
            emoji = "💻",
            colorHex = 0xFF10B981L,
            isIncome = true,
            isBuiltIn = false,
            limitMode = CategoryLimitMode.PERCENT,
            limitValue = 0.0,
            isArchived = false,
            updatedAtEpochMs = 1_763_400_002_000L
        )
        val modifiedBuiltIn = ExpenseCategory.GROCERIES.toDescriptor().copy(
            limitMode = CategoryLimitMode.FIXED_RUB,
            limitValue = 45_000.0,
            updatedAtEpochMs = 1_763_400_003_000L
        )
        val archivedCustom = CategoryDescriptor(
            id = "custom_old_hobby",
            displayName = "Старое хобби",
            shortName = "Хобби",
            emoji = "🎨",
            colorHex = 0xFFF59E0BL,
            isIncome = false,
            isBuiltIn = false,
            limitMode = CategoryLimitMode.PERCENT,
            limitValue = 2.0,
            isArchived = true,
            updatedAtEpochMs = 1_763_400_004_000L
        )

        val original = FamilyConfig(
            schemaVersion = 1,
            monthlyBudgetRub = 245_000.75,
            budgetUpdatedAtEpochMs = 1_763_400_005_123L,
            categories = listOf(customExpense, customIncome, modifiedBuiltIn, archivedCustom)
        )

        val json = original.toJsonObject()
        val restored = FamilyConfig.fromJsonObject(json)

        assertNotNull(restored)
        assertEquals(original.schemaVersion, restored!!.schemaVersion)
        assertEquals(original.monthlyBudgetRub, restored.monthlyBudgetRub, 0.0001)
        assertEquals(original.budgetUpdatedAtEpochMs, restored.budgetUpdatedAtEpochMs)
        assertEquals(4, restored.categories.size)
        assertTrue(original.isEquivalentTo(restored))

        val restoredFromString = FamilyConfig.fromJsonString(json.toString())
        assertEquals(restored, restoredFromString)
    }

    @Test
    fun fromJsonObject_invalidOrNullFields_handledSafely() {
        assertNull(FamilyConfig.fromJsonObject(JSONObject()))
        assertNull(FamilyConfig.fromJsonString(""))
        assertNull(FamilyConfig.fromJsonString("{not valid json"))

        val jsonWithNulls = JSONObject().apply {
            put("schemaVersion", 1)
            put("monthlyBudgetRub", 180_000.0)
            put("budgetUpdatedAtEpochMs", 500L)
            put(
                "categories",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("id", "GROCERIES")
                            put("limitMode", "FUTURE_UNKNOWN_MODE")
                            put("limitValue", 25.0)
                            put("displayName", JSONObject.NULL)
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("id", "   ") // blank id must be skipped
                            put("displayName", "Invalid")
                        }
                    )
                }
            )
        }

        val parsed = FamilyConfig.fromJsonObject(jsonWithNulls)
        assertNotNull(parsed)
        assertEquals(180_000.0, parsed!!.monthlyBudgetRub, 0.0001)
        assertEquals(1, parsed.categories.size)
        val groceries = parsed.categories.single()
        assertEquals("GROCERIES", groceries.id)
        assertEquals("Продукты", groceries.displayName)
        assertEquals(CategoryLimitMode.PERCENT, groceries.limitMode)
        assertEquals(25.0, groceries.limitValue, 0.0001)
        assertTrue(groceries.isBuiltIn)
    }

    @Test
    fun merge_budgetEditOnPhoneA_andCategoryAdditionOnPhoneB_preservesBothEdits() {
        val catFromB = CategoryDescriptor(
            id = "custom_yoga",
            displayName = "Йога",
            shortName = "Йога",
            emoji = "🧘",
            colorHex = 0xFFEC4899L,
            isIncome = false,
            isBuiltIn = false,
            limitMode = CategoryLimitMode.FIXED_RUB,
            limitValue = 6_000.0,
            isArchived = false,
            updatedAtEpochMs = 3_000L // newer than Phone A's budget timestamp
        )

        val phoneA = FamilyConfig(
            schemaVersion = 1,
            monthlyBudgetRub = 260_000.0,
            budgetUpdatedAtEpochMs = 2_000L,
            categories = emptyList()
        )
        val phoneB = FamilyConfig(
            schemaVersion = 1,
            monthlyBudgetRub = 200_000.0,
            budgetUpdatedAtEpochMs = 1_000L,
            categories = listOf(catFromB)
        )

        val mergedAB = FamilyConfig.merge(local = phoneA, remote = phoneB)
        val mergedBA = FamilyConfig.merge(local = phoneB, remote = phoneA)

        // Even though Phone B had a newer overall file timestamp (3_000L for category),
        // Phone A's budget change (2_000L > 1_000L) is NOT overwritten by whole-file LWW.
        for (merged in listOf(mergedAB, mergedBA)) {
            assertEquals(260_000.0, merged.monthlyBudgetRub, 0.0001)
            assertEquals(2_000L, merged.budgetUpdatedAtEpochMs)
            assertEquals(listOf(catFromB), merged.categories)
        }
    }

    @Test
    fun merge_conflictingCategoryEdit_newerUpdatedAtEpochMsWins() {
        val phoneACat = ExpenseCategory.GROCERIES.toDescriptor().copy(
            limitMode = CategoryLimitMode.FIXED_RUB,
            limitValue = 35_000.0,
            updatedAtEpochMs = 5_000L
        )
        val phoneBCat = ExpenseCategory.GROCERIES.toDescriptor().copy(
            limitMode = CategoryLimitMode.PERCENT,
            limitValue = 18.0,
            updatedAtEpochMs = 6_000L
        )

        val phoneA = FamilyConfig(categories = listOf(phoneACat))
        val phoneB = FamilyConfig(categories = listOf(phoneBCat))

        val mergedAB = FamilyConfig.merge(phoneA, phoneB)
        val mergedBA = FamilyConfig.merge(phoneB, phoneA)

        assertEquals(phoneBCat, mergedAB.categories.single())
        assertEquals(phoneBCat, mergedBA.categories.single())

        val phoneACatNewer = phoneACat.copy(updatedAtEpochMs = 7_500L)
        val mergedNewerA = FamilyConfig.merge(
            local = FamilyConfig(categories = listOf(phoneACatNewer)),
            remote = phoneB
        )
        assertEquals(phoneACatNewer, mergedNewerA.categories.single())
    }

    @Test
    fun merge_categoryArchival_softDeletesWithoutLosingHistoryAndUnarchivesWhenNewer() {
        val activeOnA = CategoryDescriptor(
            id = "custom_club",
            displayName = "Спортклуб",
            shortName = "Клуб",
            emoji = "🏋️",
            colorHex = 0xFF3B82F6L,
            isIncome = false,
            isBuiltIn = false,
            isArchived = false,
            updatedAtEpochMs = 1_000L
        )
        val archivedOnB = activeOnA.copy(
            isArchived = true,
            updatedAtEpochMs = 3_000L
        )

        val mergedArchived = FamilyConfig.merge(
            local = FamilyConfig(categories = listOf(activeOnA)),
            remote = FamilyConfig(categories = listOf(archivedOnB))
        )

        assertEquals(1, mergedArchived.categories.size)
        val storedCat = mergedArchived.categories.single()
        assertTrue(storedCat.isArchived)
        assertEquals(3_000L, storedCat.updatedAtEpochMs)

        // Category is excluded from active picker, yet resolves for historical records
        val registry = mergedArchived.toRegistry()
        assertFalse(registry.expenseCategories.any { it.id == "custom_club" })
        assertTrue(registry.isKnown("custom_club"))
        assertEquals("Спортклуб", registry.resolve("custom_club").displayName)
        assertTrue(registry.resolve("custom_club").isArchived)

        // Newer unarchive restores it back to active picker
        val restoredOnA = archivedOnB.copy(isArchived = false, updatedAtEpochMs = 4_000L)
        val mergedRestored = FamilyConfig.merge(
            local = FamilyConfig(categories = listOf(restoredOnA)),
            remote = mergedArchived
        )
        assertFalse(mergedRestored.categories.single().isArchived)
        assertTrue(mergedRestored.toRegistry().expenseCategories.any { it.id == "custom_club" })
    }

    @Test
    fun merge_isDeterministicAcrossCategoryOrdering() {
        val catA = CategoryDescriptor(
            id = "custom_alpha",
            displayName = "Альфа",
            shortName = "Альфа",
            emoji = "🅰️",
            colorHex = 0xFF111111L,
            isIncome = false,
            isBuiltIn = false,
            updatedAtEpochMs = 100L
        )
        val catZ = CategoryDescriptor(
            id = "custom_zeta",
            displayName = "Зета",
            shortName = "Зета",
            emoji = "🇿",
            colorHex = 0xFF222222L,
            isIncome = false,
            isBuiltIn = false,
            updatedAtEpochMs = 200L
        )

        val local = FamilyConfig(categories = listOf(catZ, catA))
        val remote = FamilyConfig(categories = listOf(catA, catZ))

        val merged1 = FamilyConfig.merge(local, remote)
        val merged2 = FamilyConfig.merge(remote, local)

        assertEquals(merged1, merged2)
        assertEquals(listOf("custom_alpha", "custom_zeta"), merged1.categories.map { it.id })
        assertTrue(local.isEquivalentTo(remote))
    }

    @Test
    fun buildConfigSyncPlan_handles404InitialUpload_andBidirectionalMerge() {
        val initialLocal = FamilyConfig(
            schemaVersion = 1,
            monthlyBudgetRub = 220_000.0,
            budgetUpdatedAtEpochMs = 0L,
            categories = emptyList()
        )

        // 1. Server returns 404 -> upload current local config as initial seed
        val plan404 = BudgetRepository.buildConfigSyncPlan(
            local = initialLocal,
            remote = null,
            remoteExists = false
        )
        assertTrue(plan404.needCloudPush)
        assertFalse(plan404.updateLocal)
        assertEquals(initialLocal, plan404.mergedConfig)

        // 2. Concurrent local budget update + remote new category -> both merged, update local and push
        val remoteCat = CategoryDescriptor(
            id = "custom_pets_vet",
            displayName = "Ветеринар",
            shortName = "Вет",
            emoji = "🩺",
            colorHex = 0xFFEF4444L,
            isIncome = false,
            isBuiltIn = false,
            updatedAtEpochMs = 5_000L
        )
        val localEditedBudget = initialLocal.copy(
            monthlyBudgetRub = 250_000.0,
            budgetUpdatedAtEpochMs = 4_000L
        )
        val remoteWithNewCategory = FamilyConfig(
            monthlyBudgetRub = 220_000.0,
            budgetUpdatedAtEpochMs = 1_000L,
            categories = listOf(remoteCat)
        )

        val mergePlan = BudgetRepository.buildConfigSyncPlan(
            local = localEditedBudget,
            remote = remoteWithNewCategory,
            remoteExists = true
        )
        assertTrue(mergePlan.updateLocal)
        assertTrue(mergePlan.needCloudPush)
        assertEquals(250_000.0, mergePlan.mergedConfig.monthlyBudgetRub, 0.0001)
        assertEquals(listOf(remoteCat), mergePlan.mergedConfig.categories)

        // 3. After push, local == remote -> no update, no push
        val syncedPlan = BudgetRepository.buildConfigSyncPlan(
            local = mergePlan.mergedConfig,
            remote = mergePlan.mergedConfig,
            remoteExists = true
        )
        assertFalse(syncedPlan.updateLocal)
        assertFalse(syncedPlan.needCloudPush)
    }
}
