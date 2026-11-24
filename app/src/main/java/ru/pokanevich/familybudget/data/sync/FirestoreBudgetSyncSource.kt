package ru.pokanevich.familybudget.data.sync

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import ru.pokanevich.familybudget.BuildConfig
import ru.pokanevich.familybudget.data.CategoryDescriptor
import ru.pokanevich.familybudget.data.CategoryLimitMode
import ru.pokanevich.familybudget.data.CategoryRegistry
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.FamilyConfig
import ru.pokanevich.familybudget.data.RecurringPaymentEntity
import ru.pokanevich.familybudget.data.SavingsGoalEntity

/**
 * Облачный транспорт на базе Firebase Cloud Firestore + Anonymous Auth.
 * Использует MemoryCacheSettings, так как локальным источником правды (Offline-First) остаётся Room.
 */
class FirestoreBudgetSyncSource(
    private val appContext: Context,
    private val deviceInstallationIdProvider: () -> String
) : RemoteBudgetSyncSource {

    private val initLock = Any()

    @Volatile
    private var cachedContour: SyncContour? = null

    @Volatile
    private var cachedFirestore: FirebaseFirestore? = null

    @Volatile
    private var cachedAuth: FirebaseAuth? = null

    @Volatile
    private var registeredSpaceMemberKey: String? = null

    override val isConfigured: Boolean
        get() = BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() &&
            BuildConfig.FIREBASE_APP_ID.isNotBlank() &&
            BuildConfig.FIREBASE_API_KEY.isNotBlank()

    private fun getOrInitClients(contour: SyncContour): Pair<FirebaseFirestore, FirebaseAuth> {
        synchronized(initLock) {
            val existingDb = cachedFirestore
            val existingAuth = cachedAuth
            if (cachedContour == contour && existingDb != null && existingAuth != null) {
                return existingDb to existingAuth
            }

            val appName = "fbz_${contour.id.lowercase()}"
            val firebaseApp = FirebaseApp.getApps(appContext).firstOrNull { it.name == appName }
                ?: FirebaseApp.initializeApp(
                    appContext,
                    FirebaseOptions.Builder()
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .apply {
                            if (BuildConfig.FIREBASE_STORAGE_BUCKET.isNotBlank()) {
                                setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET)
                            }
                        }
                        .build(),
                    appName
                )

            val auth = FirebaseAuth.getInstance(firebaseApp)
            val firestore = FirebaseFirestore.getInstance(firebaseApp)
            runCatching {
                val settings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                    .build()
                firestore.firestoreSettings = settings
            }

            cachedContour = contour
            cachedFirestore = firestore
            cachedAuth = auth
            registeredSpaceMemberKey = null
            return firestore to auth
        }
    }

    private suspend fun ensureAuthenticatedMember(
        contour: SyncContour,
        spaceKey: String
    ): FirebaseFirestore {
        val (firestore, auth) = getOrInitClients(contour)
        val user = auth.currentUser ?: auth.signInAnonymously().await().user
            ?: throw IllegalStateException("Firebase Anonymous Auth failed to return user")

        val memberCacheKey = "${contour.id}/$spaceKey/${user.uid}"
        if (registeredSpaceMemberKey != memberCacheKey) {
            val memberRef = firestore
                .collection(contour.rootCollection)
                .document(spaceKey)
                .collection("members")
                .document(user.uid)

            val memberPayload = mapOf(
                "uid" to user.uid,
                "contour" to contour.id,
                "deviceId" to deviceInstallationIdProvider().take(128),
                "updatedAtEpochMs" to System.currentTimeMillis()
            )
            memberRef.set(memberPayload).await()
            registeredSpaceMemberKey = memberCacheKey
        }
        return firestore
    }

    override suspend fun syncConfig(
        contour: SyncContour,
        spaceKey: String,
        localConfig: FamilyConfig,
        hasUnsyncedLocal: Boolean
    ): ConfigSyncOutcome {
        val firestore = ensureAuthenticatedMember(contour, spaceKey)
        val configRef = firestore
            .collection(contour.rootCollection)
            .document(spaceKey)
            .collection("meta")
            .document("config")

        return firestore.runTransaction { tx ->
            val snap = tx.get(configRef)
            val remoteExists = snap.exists()
            val remoteConfig = if (remoteExists) parseMetaConfig(snap) else null
            val merged = if (remoteConfig != null) {
                FamilyConfig.merge(
                    local = localConfig.copy(recurringPayments = emptyList(), savingsGoals = emptyList()),
                    remote = remoteConfig
                )
            } else {
                localConfig.copy(recurringPayments = emptyList(), savingsGoals = emptyList())
            }

            val shouldPush = !remoteExists ||
                remoteConfig == null ||
                hasUnsyncedLocal ||
                !merged.isEquivalentTo(remoteConfig)

            if (shouldPush) {
                val prevUpdatedAt = snap.getLong("updatedAtEpochMs") ?: 0L
                val maxItemTs = maxOf(
                    merged.budgetUpdatedAtEpochMs,
                    merged.categories.maxOfOrNull { it.updatedAtEpochMs } ?: 0L
                )
                val monotonicTs = maxOf(System.currentTimeMillis(), prevUpdatedAt + 1L, maxItemTs)
                tx.set(configRef, metaConfigToMap(merged, monotonicTs))
            }

            ConfigSyncOutcome(
                remoteConfig = remoteConfig,
                remoteExists = remoteExists,
                mergedConfig = merged,
                needsLocalUpdate = remoteConfig != null && !merged.isEquivalentTo(
                    localConfig.copy(recurringPayments = emptyList(), savingsGoals = emptyList())
                ),
                pushedToCloud = shouldPush
            )
        }.await()
    }

    override suspend fun pullChanges(
        contour: SyncContour,
        spaceKey: String,
        sinceWatermarkMs: Long
    ): RemoteDeltaPayload {
        val firestore = ensureAuthenticatedMember(contour, spaceKey)
        val spaceRef = firestore.collection(contour.rootCollection).document(spaceKey)
        val isFullSnapshot = sinceWatermarkMs <= 0L
        val lowerBoundMs = (sinceWatermarkMs - WATERMARK_OVERLAP_MS).coerceAtLeast(0L)

        val expensesSnap = if (isFullSnapshot) {
            spaceRef.collection("expenses").get().await()
        } else {
            spaceRef.collection("expenses")
                .whereGreaterThan("updatedAtEpochMs", lowerBoundMs)
                .get()
                .await()
        }

        val recurringSnap = if (isFullSnapshot) {
            spaceRef.collection("recurring_payments").get().await()
        } else {
            spaceRef.collection("recurring_payments")
                .whereGreaterThan("updatedAtEpochMs", lowerBoundMs)
                .get()
                .await()
        }

        val goalsSnap = if (isFullSnapshot) {
            spaceRef.collection("savings_goals").get().await()
        } else {
            spaceRef.collection("savings_goals")
                .whereGreaterThan("updatedAtEpochMs", lowerBoundMs)
                .get()
                .await()
        }

        val expensesMap = LinkedHashMap<String, ExpenseEntity>(expensesSnap.size())
        var maxWatermark = sinceWatermarkMs.coerceAtLeast(0L)

        for (doc in expensesSnap.documents) {
            val parsed = parseExpenseDoc(doc) ?: continue
            expensesMap[parsed.id] = parsed
            if (parsed.updatedAtEpochMs > maxWatermark) {
                maxWatermark = parsed.updatedAtEpochMs
            }
        }

        val recurringList = ArrayList<RecurringPaymentEntity>(recurringSnap.size())
        for (doc in recurringSnap.documents) {
            val parsed = parseRecurringDoc(doc) ?: continue
            recurringList.add(parsed)
            if (parsed.updatedAtEpochMs > maxWatermark) {
                maxWatermark = parsed.updatedAtEpochMs
            }
        }

        val goalsList = ArrayList<SavingsGoalEntity>(goalsSnap.size())
        for (doc in goalsSnap.documents) {
            val parsed = parseGoalDoc(doc) ?: continue
            goalsList.add(parsed)
            if (parsed.updatedAtEpochMs > maxWatermark) {
                maxWatermark = parsed.updatedAtEpochMs
            }
        }

        val metaExists = if (isFullSnapshot) {
            runCatching { spaceRef.collection("meta").document("config").get().await().exists() }.getOrDefault(false)
        } else {
            true
        }
        val remoteExists = metaExists || expensesMap.isNotEmpty() || recurringList.isNotEmpty() || goalsList.isNotEmpty()

        return RemoteDeltaPayload(
            remoteExists = remoteExists,
            isFullSnapshot = isFullSnapshot,
            expensesDelta = expensesMap,
            recurringDelta = recurringList,
            goalsDelta = goalsList,
            maxObservedWatermarkMs = maxWatermark
        )
    }

    override suspend fun pushChanges(
        contour: SyncContour,
        spaceKey: String,
        expenses: List<ExpenseEntity>,
        recurring: List<RecurringPaymentEntity>,
        goals: List<SavingsGoalEntity>,
        registry: CategoryRegistry
    ) {
        if (expenses.isEmpty() && recurring.isEmpty() && goals.isEmpty()) return

        val firestore = ensureAuthenticatedMember(contour, spaceKey)
        val spaceRef = firestore.collection(contour.rootCollection).document(spaceKey)

        val operations = ArrayList<Pair<com.google.firebase.firestore.DocumentReference, Map<String, Any>>>(
            expenses.size + recurring.size + goals.size
        )
        for (expense in expenses) {
            if (expense.id.isBlank()) continue
            operations.add(
                spaceRef.collection("expenses").document(expense.id) to expenseToMap(expense, registry)
            )
        }
        for (payment in recurring) {
            if (payment.id.isBlank()) continue
            operations.add(
                spaceRef.collection("recurring_payments").document(payment.id) to recurringToMap(payment)
            )
        }
        for (goal in goals) {
            if (goal.id.isBlank()) continue
            operations.add(
                spaceRef.collection("savings_goals").document(goal.id) to goalToMap(goal)
            )
        }

        for (chunk in operations.chunked(WRITE_BATCH_MAX_OPS)) {
            val batch = firestore.batch()
            for ((ref, payload) in chunk) {
                batch.set(ref, payload, SetOptions.merge())
            }
            batch.commit().await()
        }
    }

    override fun observeRealtimeChanges(
        contour: SyncContour,
        spaceKey: String,
        onRemoteChanged: () -> Unit
    ): AutoCloseable {
        if (!isConfigured) return AutoCloseable {}

        val (firestore, auth) = getOrInitClients(contour)
        if (auth.currentUser == null) {
            // До первой анонимной авторизации и создания /members/{uid} слушатель не подписываем:
            // это сделает первый успешный performSyncCycle.
            return AutoCloseable {}
        }

        val spaceRef = firestore.collection(contour.rootCollection).document(spaceKey)
        val sinceMs = (System.currentTimeMillis() - 60_000L).coerceAtLeast(0L)

        var firstExpensesSnapshot = true
        var firstConfigSnapshot = true

        val expensesReg: ListenerRegistration = spaceRef.collection("expenses")
            .whereGreaterThan("updatedAtEpochMs", sinceMs)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Realtime expenses listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (firstExpensesSnapshot) {
                    firstExpensesSnapshot = false
                    return@addSnapshotListener
                }
                if (snapshot != null && !snapshot.metadata.hasPendingWrites() && !snapshot.isEmpty) {
                    onRemoteChanged()
                }
            }

        val configReg: ListenerRegistration = spaceRef.collection("meta")
            .document("config")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Realtime config listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (firstConfigSnapshot) {
                    firstConfigSnapshot = false
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists() && !snapshot.metadata.hasPendingWrites()) {
                    onRemoteChanged()
                }
            }

        return AutoCloseable {
            runCatching { expensesReg.remove() }
            runCatching { configReg.remove() }
        }
    }

    companion object {
        private const val TAG = "FirestoreSyncSource"
        private const val WRITE_BATCH_MAX_OPS = 400
        private const val WATERMARK_OVERLAP_MS = 5_000L

        internal fun metaConfigToMap(config: FamilyConfig, monotonicUpdatedAtMs: Long): Map<String, Any> {
            val categoriesList = config.categories
                .filter { it.id.isNotBlank() }
                .sortedBy { it.id }
                .map { cat ->
                    mapOf(
                        "id" to cat.id.take(128),
                        "displayName" to cat.displayName.take(128),
                        "shortName" to cat.shortName.take(64),
                        "emoji" to cat.emoji.take(32),
                        "colorHex" to cat.colorHex,
                        "isIncome" to cat.isIncome,
                        "isBuiltIn" to cat.isBuiltIn,
                        "limitMode" to cat.limitMode.name,
                        "limitValue" to cat.limitValue.coerceAtLeast(0.0),
                        "isArchived" to cat.isArchived,
                        "updatedAtEpochMs" to cat.updatedAtEpochMs.coerceAtLeast(0L)
                    )
                }

            return mapOf(
                "schemaVersion" to config.schemaVersion.coerceAtLeast(1),
                "monthlyBudgetRub" to config.monthlyBudgetRub.coerceIn(0.0, 1_000_000_000.0),
                "budgetUpdatedAtEpochMs" to config.budgetUpdatedAtEpochMs.coerceAtLeast(0L),
                "categories" to categoriesList,
                "updatedAtEpochMs" to monotonicUpdatedAtMs.coerceAtLeast(0L)
            )
        }

        @Suppress("UNCHECKED_CAST")
        internal fun parseMetaConfig(doc: DocumentSnapshot): FamilyConfig? {
            if (!doc.exists()) return null
            val schemaVersion = (doc.getLong("schemaVersion") ?: 1L).toInt().coerceAtLeast(1)
            val monthlyBudget = doc.getDouble("monthlyBudgetRub")
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?: FamilyConfig.DEFAULT_MONTHLY_BUDGET_RUB
            val budgetUpdatedAt = (doc.getLong("budgetUpdatedAtEpochMs") ?: 0L).coerceAtLeast(0L)

            val rawCategories = doc.get("categories") as? List<*> ?: emptyList<Any?>()
            val categories = rawCategories.mapNotNull { item ->
                val map = item as? Map<String, Any?> ?: return@mapNotNull null
                val id = (map["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val modeStr = (map["limitMode"] as? String).orEmpty()
                val limitMode = runCatching { CategoryLimitMode.valueOf(modeStr) }.getOrDefault(CategoryLimitMode.PERCENT)
                CategoryDescriptor(
                    id = id,
                    displayName = (map["displayName"] as? String).orEmpty().ifBlank { id },
                    shortName = (map["shortName"] as? String).orEmpty().ifBlank { id },
                    emoji = (map["emoji"] as? String).orEmpty().ifBlank { "📌" },
                    colorHex = (map["colorHex"] as? Number)?.toLong() ?: 0xFF64748BL,
                    isIncome = (map["isIncome"] as? Boolean) ?: false,
                    isBuiltIn = (map["isBuiltIn"] as? Boolean) ?: false,
                    limitMode = limitMode,
                    limitValue = (map["limitValue"] as? Number)?.toDouble()?.coerceAtLeast(0.0) ?: 0.0,
                    isArchived = (map["isArchived"] as? Boolean) ?: false,
                    updatedAtEpochMs = (map["updatedAtEpochMs"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
                )
            }

            return FamilyConfig(
                schemaVersion = schemaVersion,
                monthlyBudgetRub = monthlyBudget,
                budgetUpdatedAtEpochMs = budgetUpdatedAt,
                categories = categories
            )
        }

        internal fun expenseToMap(expense: ExpenseEntity, registry: CategoryRegistry): Map<String, Any> {
            val normalizedCategory = expense.categoryName
                .ifBlank { registry.resolve(expense.categoryName).id }
                .take(128)
            val safeAmount = if (expense.amountRub.isFinite() && expense.amountRub > 0.0) {
                expense.amountRub.coerceAtMost(1_000_000_000.0)
            } else {
                0.01
            }
            return mapOf(
                "id" to expense.id.take(128),
                "amountRub" to safeAmount,
                "merchant" to expense.merchant.take(256),
                "categoryName" to normalizedCategory,
                "authorName" to expense.authorName.take(128),
                "sourceType" to expense.sourceType.take(64),
                "isPendingConfirmation" to expense.isPendingConfirmation,
                "isDeleted" to expense.isDeleted,
                "createdAtEpochMs" to expense.createdAtEpochMs.coerceAtLeast(0L),
                "updatedAtEpochMs" to expense.updatedAtEpochMs.coerceAtLeast(0L),
                "rawPushOrQrText" to expense.rawPushOrQrText.take(2000),
                "dedupSignature" to expense.dedupSignature.take(128),
                "note" to expense.note.take(1000),
                "operationType" to expense.operationType.take(32),
                "splitGroupId" to expense.splitGroupId.take(128),
                "goalId" to expense.goalId.take(128),
                "originDeviceId" to expense.originDeviceId.take(128)
            )
        }

        internal fun parseExpenseDoc(doc: DocumentSnapshot): ExpenseEntity? {
            if (!doc.exists()) return null
            val id = doc.getString("id")?.ifBlank { null } ?: doc.id.takeIf { it.isNotBlank() } ?: return null
            val amount = doc.getDouble("amountRub") ?: return null
            if (!amount.isFinite() || amount <= 0.0) return null

            val created = (doc.getLong("createdAtEpochMs") ?: 0L).coerceAtLeast(0L)
            val updated = (doc.getLong("updatedAtEpochMs") ?: created).coerceAtLeast(0L)

            return ExpenseEntity(
                id = id,
                amountRub = amount,
                merchant = doc.getString("merchant").orEmpty(),
                categoryName = doc.getString("categoryName").orEmpty().ifBlank { ExpenseCategory.OTHER.name },
                authorName = doc.getString("authorName").orEmpty().ifBlank { "Евгений" },
                sourceType = doc.getString("sourceType").orEmpty().ifBlank { "MANUAL" },
                isPendingConfirmation = doc.getBoolean("isPendingConfirmation") ?: false,
                isDeleted = doc.getBoolean("isDeleted") ?: false,
                isSynced = true,
                createdAtEpochMs = created,
                updatedAtEpochMs = updated,
                rawPushOrQrText = doc.getString("rawPushOrQrText").orEmpty(),
                dedupSignature = doc.getString("dedupSignature").orEmpty(),
                note = doc.getString("note").orEmpty(),
                splitGroupId = doc.getString("splitGroupId").orEmpty(),
                operationType = doc.getString("operationType").orEmpty(),
                goalId = doc.getString("goalId").orEmpty(),
                originDeviceId = doc.getString("originDeviceId").orEmpty()
            )
        }

        internal fun recurringToMap(payment: RecurringPaymentEntity): Map<String, Any> {
            val safeAmount = if (payment.amountRub.isFinite() && payment.amountRub > 0.0) {
                payment.amountRub.coerceAtMost(1_000_000_000.0)
            } else {
                0.01
            }
            return mapOf(
                "id" to payment.id.take(128),
                "title" to payment.title.take(200),
                "amountRub" to safeAmount,
                "categoryId" to payment.categoryId.ifBlank { ExpenseCategory.OTHER.name }.take(128),
                "operationType" to payment.effectiveOperationType.name.take(32),
                "dayOfMonth" to payment.dayOfMonth.coerceIn(1, 31),
                "autoCreateInFeed" to payment.autoCreateInFeed,
                "isActive" to payment.isActive,
                "lastPaidPeriodKey" to payment.lastPaidPeriodKey.take(16),
                "isDeleted" to payment.isDeleted,
                "updatedAtEpochMs" to payment.updatedAtEpochMs.coerceAtLeast(0L)
            )
        }

        internal fun parseRecurringDoc(doc: DocumentSnapshot): RecurringPaymentEntity? {
            if (!doc.exists()) return null
            val id = doc.getString("id")?.ifBlank { null } ?: doc.id.takeIf { it.isNotBlank() } ?: return null
            val amount = doc.getDouble("amountRub") ?: return null
            if (!amount.isFinite() || amount <= 0.0) return null

            return RecurringPaymentEntity(
                id = id,
                title = doc.getString("title").orEmpty(),
                amountRub = amount,
                categoryId = doc.getString("categoryId").orEmpty().ifBlank { ExpenseCategory.OTHER.name },
                operationType = doc.getString("operationType").orEmpty().ifBlank { "EXPENSE" },
                dayOfMonth = (doc.getLong("dayOfMonth") ?: 1L).toInt().coerceIn(1, 31),
                autoCreateInFeed = doc.getBoolean("autoCreateInFeed") ?: true,
                lastPaidPeriodKey = doc.getString("lastPaidPeriodKey").orEmpty(),
                isActive = doc.getBoolean("isActive") ?: true,
                isDeleted = doc.getBoolean("isDeleted") ?: false,
                updatedAtEpochMs = (doc.getLong("updatedAtEpochMs") ?: 0L).coerceAtLeast(0L)
            )
        }

        internal fun goalToMap(goal: SavingsGoalEntity): Map<String, Any> = mapOf(
            "id" to goal.id.take(128),
            "title" to goal.title.take(200),
            "emoji" to goal.emoji.ifBlank { "🎯" }.take(32),
            "targetAmountRub" to goal.targetAmountRub.coerceIn(0.0, 1_000_000_000.0),
            "initialAmountRub" to goal.initialAmountRub.coerceIn(0.0, 1_000_000_000.0),
            "isArchived" to goal.isArchived,
            "isDeleted" to goal.isDeleted,
            "updatedAtEpochMs" to goal.updatedAtEpochMs.coerceAtLeast(0L)
        )

        internal fun parseGoalDoc(doc: DocumentSnapshot): SavingsGoalEntity? {
            if (!doc.exists()) return null
            val id = doc.getString("id")?.ifBlank { null } ?: doc.id.takeIf { it.isNotBlank() } ?: return null
            return SavingsGoalEntity(
                id = id,
                title = doc.getString("title").orEmpty(),
                emoji = doc.getString("emoji").orEmpty().ifBlank { "🎯" },
                targetAmountRub = doc.getDouble("targetAmountRub")?.coerceAtLeast(0.0) ?: 0.0,
                initialAmountRub = doc.getDouble("initialAmountRub")?.coerceAtLeast(0.0) ?: 0.0,
                isArchived = doc.getBoolean("isArchived") ?: false,
                isDeleted = doc.getBoolean("isDeleted") ?: false,
                updatedAtEpochMs = (doc.getLong("updatedAtEpochMs") ?: 0L).coerceAtLeast(0L)
            )
        }
    }
}
