package ru.pokanevich.familybudget.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.pokanevich.familybudget.BuildConfig
import ru.pokanevich.familybudget.data.sync.FirestoreBudgetSyncSource
import ru.pokanevich.familybudget.data.sync.GithubLegacyMigrator
import ru.pokanevich.familybudget.data.sync.RemoteBudgetSyncSource
import ru.pokanevich.familybudget.data.sync.SyncContour
import ru.pokanevich.familybudget.widget.BudgetWidgetProvider
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToLong

enum class SyncState(val label: String) {
    OFFLINE_READY("Локальная БД активна (офлайн)"),
    SYNCING("Синхронизация с облаком..."),
    CLOUD_CONNECTED("Облако синхронизировано (2 устройства)")
}

/** Контролируемая ошибка синка: облачную базу перезаписывать нельзя (Sync Guard). */
class SyncGuardException(message: String) : IllegalStateException(message)

/**
 * План одного цикла слияния локальной Room-базы и облачного budget_v3.json (чистая логика, покрыта тестами).
 *
 * @property mergedMap итоговое содержимое облачного файла (losers дедупликации помечены isDeleted = true)
 * @property toUpsertLocally записи, которые нужно сохранить в Room до PUT
 * @property needCloudPush нужен ли PUT в облако
 * @property pushedVersions id → updatedAtEpochMs локальных несинхронизированных записей, которые
 *   переводятся в isSynced = true ТОЛЬКО после успешного PUT (200..299)
 * @property dedupedLosers записи, мягко удалённые кросс-девайс дедупликацией (локально isSynced = false)
 */
internal data class SyncMergePlan(
    val mergedMap: Map<String, ExpenseEntity>,
    val toUpsertLocally: List<ExpenseEntity>,
    val needCloudPush: Boolean,
    val pushedVersions: Map<String, Long>,
    val dedupedLosers: List<ExpenseEntity>
)

internal data class ConfigSyncMergePlan(
    val mergedConfig: FamilyConfig,
    val updateLocal: Boolean,
    val needCloudPush: Boolean
)

class BudgetRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(context)
    private val dao = db.expenseDao()
    private val recurringDao = db.recurringPaymentDao()
    private val savingsGoalDao = db.savingsGoalDao()
    val prefs = FamilyPreferences(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val ingestMutex = Mutex()
    private val recurringMutex = Mutex()
    private val goalsMutex = Mutex()
    private val configLock = Any()
    private var pollingJob: Job? = null

    private val foregroundWakeupSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var isForegroundActive: Boolean = false

    /**
     * Coalescing: true, если уже есть поставленный в очередь на [syncMutex] цикл синка
     * от локального изменения. Повторные изменения в это время не плодят новые полные циклы.
     */
    private val pendingSyncRequested = AtomicBoolean(false)

    private val syncSource: RemoteBudgetSyncSource =
        FirestoreBudgetSyncSource(appContext) { prefs.deviceInstallationId }

    private var cachedSpaceKey: String? = null
    private val listenerLock = Any()
    private var realtimeListener: AutoCloseable? = null

    private val _syncState = MutableStateFlow(SyncState.OFFLINE_READY)
    val syncState: StateFlow<SyncState> = _syncState

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private val initialConfig = prefs.loadFamilyConfig()
    private val _familyConfig = MutableStateFlow(initialConfig)
    val familyConfig: StateFlow<FamilyConfig> = _familyConfig

    private val _categoryRegistry = MutableStateFlow(initialConfig.toRegistry())
    val categoryRegistry: StateFlow<CategoryRegistry> = _categoryRegistry

    val confirmedExpenses: Flow<List<ExpenseEntity>> = dao.observeConfirmedExpenses()
    val pendingPushExpenses: Flow<List<ExpenseEntity>> = dao.observePendingPushExpenses()
    val recurringPayments: Flow<List<RecurringPaymentEntity>> = recurringDao.observeRecurringPayments()
    val savingsGoals: Flow<List<SavingsGoalEntity>> = savingsGoalDao.observeSavingsGoals()

    init {
        scope.launch {
            reconcileAndProcessRecurringOnStartup()
            reconcileSavingsGoalsOnStartup()
            // Авто-созданные регулярные платежи и зеркалирование конфига могли изменить базу
            BudgetWidgetProvider.requestUpdate(appContext)
        }
        startRealtimeSync()
    }

    /**
     * Переключает режим синхронизации Cloud Firestore в зависимости от жизненного цикла UI:
     * на активном экране держит SnapshotListener в реальном времени и подстраховку раз в минуту;
     * при уходе в фон слушатель немедленно закрывается для экономии заряда и трафика.
     */
    fun setForegroundActive(active: Boolean) {
        val previous = isForegroundActive
        isForegroundActive = active
        if (active && !previous) {
            attachRealtimeListener()
            scope.launch {
                val changed = recurringMutex.withLock {
                    evaluateAndPersistRecurringLocked()
                }
                if (changed) {
                    requestCoalescedSync()
                }
            }
            foregroundWakeupSignal.trySend(Unit)
        } else if (!active && previous) {
            closeRealtimeListener()
        }
    }

    private fun attachRealtimeListener() {
        val contour = SyncContour.current()
        val spaceKey = SyncContour.validateAndNormalizeSpaceKey(prefs.familySpaceCode, contour)
        synchronized(listenerLock) {
            realtimeListener?.let { runCatching { it.close() } }
            realtimeListener = syncSource.observeRealtimeChanges(contour, spaceKey) {
                requestCoalescedSync()
            }
        }
    }

    private fun closeRealtimeListener() {
        synchronized(listenerLock) {
            realtimeListener?.let { runCatching { it.close() } }
            realtimeListener = null
        }
    }

    /**
     * Запускает двустороннюю облачную синхронизацию на базе Cloud Firestore
     * с инкрементальным pull по watermark и пакетной записью через WriteBatch.
     */
    fun startRealtimeSync() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                syncWithCloudNow()
                while (foregroundWakeupSignal.tryReceive().isSuccess) {
                    // Сбрасываем сигналы, пришедшие во время уже выполнявшегося цикла синка
                }
                val intervalMs = if (isForegroundActive) {
                    FOREGROUND_POLL_INTERVAL_MS
                } else {
                    BACKGROUND_POLL_INTERVAL_MS
                }
                withTimeoutOrNull(intervalMs) {
                    foregroundWakeupSignal.receive()
                }
            }
        }
    }

    suspend fun syncWithCloudNow() {
        runSync(onlyIfPending = false)
    }

    /**
     * Запрос синка после локального изменения. Если цикл уже стоит в очереди на [syncMutex],
     * новый не создаётся: ожидающий цикл всё равно прочитает актуальное состояние Room/конфига.
     */
    private fun requestCoalescedSync() {
        if (pendingSyncRequested.compareAndSet(false, true)) {
            scope.launch { runSync(onlyIfPending = true) }
        }
    }

    private suspend fun runSync(onlyIfPending: Boolean) = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            // Любой цикл, захвативший мьютекс, читает свежее состояние Room и FamilyConfig.
            val wasPending = pendingSyncRequested.getAndSet(false)
            if (onlyIfPending && !wasPending) return@withLock

            _isSyncing.value = true
            try {
                runCatching { performSyncCycle() }
                    .onSuccess { state ->
                        _syncState.value = state
                        if (state == SyncState.CLOUD_CONNECTED) {
                            BudgetWidgetProvider.requestUpdate(appContext)
                        }
                    }
                    .onFailure { err ->
                        if (err is CancellationException) throw err
                        if (err is SyncGuardException) {
                            if (BuildConfig.DEBUG) Log.e("BudgetRepository", "Cloud sync blocked: ${err.message}")
                        } else {
                            if (BuildConfig.DEBUG) Log.w("BudgetRepository", "Cloud sync offline: ${err.message}")
                        }
                        _syncState.value = SyncState.OFFLINE_READY
                    }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private fun applyLocalConfigLocked(config: FamilyConfig, hasUnsynced: Boolean) {
        prefs.saveFamilyConfig(config, hasUnsynced = hasUnsynced)
        _familyConfig.value = config
        _categoryRegistry.value = config.toRegistry()
    }

    /**
     * Безопасно переключает приложение на другой код семьи: предварительно полностью очищает
     * локальный кэш Room, ETag/SHA и дедуп-ключи предыдущего пространства, чтобы данные разных
     * семей (или тестовой базы) никогда не смешивались и не загружались в чужое облако.
     */
    suspend fun switchFamilySpace(newFamilyCode: String, initialMonthlyBudgetRub: Double? = null) {
        val contour = SyncContour.current()
        val targetCode = SyncContour.validateAndNormalizeSpaceKey(newFamilyCode, contour)
        val switched = syncMutex.withLock {
            val currentCode = SyncContour.validateAndNormalizeSpaceKey(prefs.familySpaceCode, contour)
            if (currentCode == targetCode) return@withLock false
            closeRealtimeListener()
            recurringMutex.withLock {
                goalsMutex.withLock {
                    dao.deleteAll()
                    recurringDao.deleteAll()
                    savingsGoalDao.deleteAll()
                    synchronized(configLock) {
                        val fallbackBudget = initialMonthlyBudgetRub
                            ?.takeIf { it.isFinite() && it > 0.0 }
                            ?: _familyConfig.value.monthlyBudgetRub
                        val cleanConfig = FamilyConfig(
                            schemaVersion = FamilyConfig.CURRENT_SCHEMA_VERSION,
                            monthlyBudgetRub = fallbackBudget,
                            budgetUpdatedAtEpochMs = 0L,
                            categories = emptyList(),
                            recurringPayments = emptyList(),
                            savingsGoals = emptyList()
                        )
                        applyLocalConfigLocked(cleanConfig, hasUnsynced = false)
                    }
                    prefs.clearFirestoreSyncState(currentCode)
                    prefs.clearFirestoreSyncState(targetCode)
                    cachedSpaceKey = targetCode
                    prefs.recentPushDedupKeys = emptyList()
                    prefs.familySpaceCode = targetCode
                }
            }
            true
        }
        if (switched) {
            BudgetWidgetProvider.requestUpdate(appContext)
            if (isForegroundActive) attachRealtimeListener()
            syncWithCloudNow()
        }
    }

    /**
     * Обновляет общий месячный бюджет семьи с новым `budgetUpdatedAtEpochMs`
     * и ставит синхронизацию в очередь для отправки на второй телефон.
     */
    fun updateMonthlyBudget(newBudgetRub: Double) {
        if (!newBudgetRub.isFinite() || newBudgetRub <= 0.0) return
        val changed = synchronized(configLock) {
            val current = _familyConfig.value
            if (abs(current.monthlyBudgetRub - newBudgetRub) <= 0.001) {
                return@synchronized false
            }
            val now = maxOf(System.currentTimeMillis(), current.budgetUpdatedAtEpochMs + 1L)
            val updated = current.copy(
                monthlyBudgetRub = newBudgetRub,
                budgetUpdatedAtEpochMs = now
            )
            applyLocalConfigLocked(updated, hasUnsynced = true)
            true
        }
        if (changed) {
            BudgetWidgetProvider.requestUpdate(appContext)
            requestCoalescedSync()
        }
    }

    /**
     * Добавляет или обновляет категорию в семейном конфиге с монотонным `updatedAtEpochMs`
     * и ставит синхронизацию в очередь.
     */
    fun upsertCategory(descriptor: CategoryDescriptor) {
        updateCategories(listOf(descriptor))
    }

    /**
     * Пакетно обновляет или добавляет категории в семейном конфиге,
     * повышая `updatedAtEpochMs` только у реально изменившихся категорий,
     * и ставит синхронизацию в очередь.
     */
    fun updateCategories(descriptors: List<CategoryDescriptor>) {
        if (descriptors.isEmpty()) return
        val changed = synchronized(configLock) {
            val current = _familyConfig.value
            val currentRegistry = current.toRegistry()
            val byId = current.categories.associateBy { it.id }.toMutableMap()
            val maxExistingTs = current.categories.maxOfOrNull { it.updatedAtEpochMs } ?: 0L
            val now = maxOf(System.currentTimeMillis(), maxExistingTs + 1L)
            var anyChanged = false

            for (descriptor in descriptors) {
                if (descriptor.id.isBlank()) continue
                val baseline = currentRegistry.find(descriptor.id)
                val isDifferent = baseline == null ||
                    baseline.displayName != descriptor.displayName ||
                    baseline.shortName != descriptor.shortName ||
                    baseline.emoji != descriptor.emoji ||
                    baseline.colorHex != descriptor.colorHex ||
                    baseline.isIncome != descriptor.isIncome ||
                    baseline.limitMode != descriptor.limitMode ||
                    abs(baseline.limitValue - descriptor.limitValue) > 0.0001 ||
                    baseline.isArchived != descriptor.isArchived

                if (isDifferent) {
                    val nextUpdatedAt = maxOf(
                        now,
                        (baseline?.updatedAtEpochMs ?: 0L) + 1L,
                        descriptor.updatedAtEpochMs
                    )
                    byId[descriptor.id] = descriptor.copy(
                        isBuiltIn = baseline?.isBuiltIn ?: descriptor.isBuiltIn,
                        updatedAtEpochMs = nextUpdatedAt
                    )
                    anyChanged = true
                }
            }

            if (anyChanged) {
                val updatedList = byId.values.sortedBy { it.id }
                applyLocalConfigLocked(current.copy(categories = updatedList), hasUnsynced = true)
            }
            anyChanged
        }
        if (changed) {
            BudgetWidgetProvider.requestUpdate(appContext)
            requestCoalescedSync()
        }
    }

    private suspend fun performSyncCycle(): SyncState {
        if (!syncSource.isConfigured) return SyncState.OFFLINE_READY

        val contour = SyncContour.current()
        val spaceKey = SyncContour.validateAndNormalizeSpaceKey(prefs.familySpaceCode, contour)

        if (cachedSpaceKey != spaceKey) {
            closeRealtimeListener()
            cachedSpaceKey = spaceKey
        }

        // Одноразовый read-only перенос из старого GitHub-бэкапа (если Firestore пуст на новой установке FAMILY).
        maybeAutoSeedFromReadOnlyGithubBackup(contour, spaceKey)

        // 1. Транзакционно синхронизируем meta/config (лимиты, категории, общий месячный бюджет).
        val hasUnsyncedBeforeMerge = synchronized(configLock) { prefs.hasUnsyncedConfig }
        val localConfigNow = synchronized(configLock) { _familyConfig.value }
        val configOutcome = syncSource.syncConfig(
            contour = contour,
            spaceKey = spaceKey,
            localConfig = localConfigNow,
            hasUnsyncedLocal = hasUnsyncedBeforeMerge
        )
        if (configOutcome.remoteExists) {
            prefs.markConfigSyncedForFamily(spaceKey)
        }
        synchronized(configLock) {
            val latest = _familyConfig.value
            val mergedFull = configOutcome.mergedConfig.copy(
                recurringPayments = latest.recurringPayments,
                savingsGoals = latest.savingsGoals
            )
            if (latest.monthlyBudgetRub == localConfigNow.monthlyBudgetRub &&
                latest.budgetUpdatedAtEpochMs == localConfigNow.budgetUpdatedAtEpochMs &&
                latest.categories == localConfigNow.categories
            ) {
                if (configOutcome.needsLocalUpdate || prefs.hasUnsyncedConfig) {
                    applyLocalConfigLocked(mergedFull, hasUnsynced = false)
                }
            } else {
                val reMerged = FamilyConfig.merge(latest, configOutcome.mergedConfig)
                applyLocalConfigLocked(reMerged, hasUnsynced = true)
            }
        }

        // 2. Инкрементальный pull операций, регулярных платежей и копилок из Cloud Firestore.
        val currentWatermark = prefs.getFirestoreWatermark(spaceKey)
        val delta = syncSource.pullChanges(
            contour = contour,
            spaceKey = spaceKey,
            sinceWatermarkMs = currentWatermark
        )

        if (currentWatermark <= 0L && !delta.remoteExists &&
            (prefs.hasSyncedFirestoreSpace(spaceKey) || dao.countSyncedExpenses() > 0)
        ) {
            throw SyncGuardException(
                "Sync guard: Firestore space $spaceKey returned empty full snapshot while local DB has synced records"
            )
        }

        // 3. Слияние регулярных платежей и копилок между Room и Firestore.
        val recurringToPush = mergeAndDetermineRecurringToPush(delta.recurringDelta, delta.isFullSnapshot)
        val goalsToPush = mergeAndDetermineGoalsToPush(delta.goalsDelta, delta.isFullSnapshot)

        // 4. Слияние операций (Last-Write-Wins + кросс-девайс дедупликация).
        val mergePlan = buildIncrementalSyncMergePlan(
            localList = dao.getAllExpenses(),
            remoteDeltaMap = delta.expensesDelta,
            remoteExists = delta.remoteExists,
            isFullSnapshot = delta.isFullSnapshot,
            nowMs = System.currentTimeMillis()
        )
        if (mergePlan.toUpsertLocally.isNotEmpty()) {
            db.withTransaction {
                mergePlan.toUpsertLocally.chunked(250).forEach { chunk ->
                    dao.upsertAll(chunk)
                }
            }
        }

        // 5. Пакетный push изменений в Cloud Firestore (WriteBatch <= 400).
        val expensesToPush = mergePlan.pushedVersions.keys.mapNotNull { mergePlan.mergedMap[it] }
        var maxWatermark = maxOf(currentWatermark, delta.maxObservedWatermarkMs)
        if (expensesToPush.isNotEmpty() || recurringToPush.isNotEmpty() || goalsToPush.isNotEmpty()) {
            val registrySnapshot = synchronized(configLock) { _categoryRegistry.value }
            syncSource.pushChanges(
                contour = contour,
                spaceKey = spaceKey,
                expenses = expensesToPush,
                recurring = recurringToPush,
                goals = goalsToPush,
                registry = registrySnapshot
            )
            markPushedAsSynced(mergePlan.pushedVersions)
            prefs.clearUnsyncedRecurringIds(recurringToPush.map { it.id }.toSet())
            prefs.clearUnsyncedGoalIds(goalsToPush.map { it.id }.toSet())
            val pushedMax = maxOf(
                expensesToPush.maxOfOrNull { it.updatedAtEpochMs } ?: 0L,
                recurringToPush.maxOfOrNull { it.updatedAtEpochMs } ?: 0L,
                goalsToPush.maxOfOrNull { it.updatedAtEpochMs } ?: 0L
            )
            if (pushedMax > maxWatermark) maxWatermark = pushedMax
        }

        prefs.markFirestoreSpaceSynced(spaceKey)
        if (maxWatermark > 0L) {
            prefs.setFirestoreWatermark(spaceKey, maxWatermark)
        }

        if (isForegroundActive && synchronized(listenerLock) { realtimeListener == null }) {
            attachRealtimeListener()
        }

        // 6. Проверяем наступившие регулярные платежи уже на свежей базе (без дублей с другим телефоном).
        val recurringChanged = recurringMutex.withLock {
            evaluateAndPersistRecurringLocked()
        }
        if (recurringChanged) {
            requestCoalescedSync()
        }

        return SyncState.CLOUD_CONNECTED
    }

    private suspend fun maybeAutoSeedFromReadOnlyGithubBackup(contour: SyncContour, spaceKey: String) {
        if (contour != SyncContour.FAMILY) return
        if (prefs.isGithubLegacyMigrated(spaceKey)) return
        val token = prefs.githubSyncToken.trim()
        if (token.isBlank()) {
            return
        }
        if (dao.countExpenses() > 0) {
            prefs.markGithubLegacyMigrated(spaceKey)
            return
        }
        val probe = runCatching {
            syncSource.pullChanges(contour, spaceKey, sinceWatermarkMs = 0L)
        }.getOrNull() ?: return
        if (probe.remoteExists) {
            prefs.markGithubLegacyMigrated(spaceKey)
            return
        }

        val payload = runCatching {
            GithubLegacyMigrator.fetchFromGithubReadOnly(spaceKey, token)
        }.getOrNull() ?: return

        applyLegacyMigrationPayload(payload)
        prefs.markGithubLegacyMigrated(spaceKey)
    }

    suspend fun importLegacyJsonUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val stream = appContext.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Не удалось открыть JSON-файл")
            val payload = stream.use { GithubLegacyMigrator.parseFromJsonStream(it) }
            applyLegacyMigrationPayload(payload)
        }
    }

    suspend fun importNativeCsvUri(
        uri: Uri,
        defaultAuthorName: String = prefs.currentUserName
    ): Result<CsvImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val maxCsvBytes = 50 * 1024 * 1024
            val stream = appContext.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Не удалось открыть CSV-файл")
            val bytes = stream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > maxCsvBytes) {
                        throw IllegalStateException("CSV-файл превышает допустимый размер (50 МБ)")
                    }
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            }

            val registrySnapshot = synchronized(configLock) { _categoryRegistry.value }
            val nowMs = System.currentTimeMillis()
            val parsedBatch = CsvImporter.parseCsvBytes(
                bytes = bytes,
                registry = registrySnapshot,
                defaultAuthorName = defaultAuthorName,
                nowEpochMs = nowMs
            )
            if (parsedBatch.items.isEmpty()) {
                return@runCatching CsvImportResult(parsedTotal = 0, newlyAdded = 0, skippedDuplicates = 0)
            }

            val dedupResult = ingestMutex.withLock {
                val existing = dao.getAllExpenses()
                val res = CsvImporter.deduplicateAgainstExisting(
                    candidates = parsedBatch.items,
                    existing = existing,
                    registry = registrySnapshot
                )
                if (res.toInsert.isNotEmpty()) {
                    val deviceId = prefs.deviceInstallationId
                    val prepared = res.toInsert.map {
                        it.copy(
                            isSynced = false,
                            updatedAtEpochMs = nowMs,
                            originDeviceId = it.originDeviceId.ifBlank { deviceId }
                        )
                    }
                    db.withTransaction {
                        prepared.chunked(250).forEach { chunk ->
                            dao.upsertAll(chunk)
                        }
                    }
                }
                res
            }

            if (dedupResult.toInsert.isNotEmpty()) {
                BudgetWidgetProvider.requestUpdate(appContext)
                requestCoalescedSync()
            }

            CsvImportResult(
                parsedTotal = parsedBatch.items.size,
                newlyAdded = dedupResult.toInsert.size,
                skippedDuplicates = dedupResult.skippedDuplicates
            )
        }
    }

    private suspend fun applyLegacyMigrationPayload(payload: ru.pokanevich.familybudget.data.sync.LegacyMigrationPayload): Int {
        payload.config?.let { importedCfg ->
            synchronized(configLock) {
                val merged = FamilyConfig.merge(_familyConfig.value, importedCfg)
                applyLocalConfigLocked(merged, hasUnsynced = true)
            }
            mirrorConfigRecurringToRoom()
            mirrorConfigGoalsToRoom()
            payload.config.recurringPayments.forEach { prefs.markRecurringUnsynced(it.id) }
            payload.config.savingsGoals.forEach { prefs.markGoalUnsynced(it.id) }
        }
        return if (payload.expenses.isNotEmpty()) {
            importExpensesBatch(payload.expenses)
        } else {
            0
        }
    }

    private suspend fun mergeAndDetermineRecurringToPush(
        remoteDelta: List<RecurringPaymentEntity>,
        isFullSnapshot: Boolean
    ): List<RecurringPaymentEntity> = recurringMutex.withLock {
        val localList = recurringDao.getAll()
        val remoteById = remoteDelta.associateBy { it.id }
        val merged = FamilyConfig.mergeRecurringPayments(localList, remoteDelta)
        val mergedById = merged.associateBy { it.id }

        val toSaveLocally = merged.filter { item ->
            val loc = localList.firstOrNull { it.id == item.id }
            loc == null || loc != item
        }
        if (toSaveLocally.isNotEmpty()) {
            recurringDao.upsertAll(toSaveLocally)
        }
        synchronized(configLock) {
            val current = _familyConfig.value
            val configMerged = FamilyConfig.mergeRecurringPayments(current.recurringPayments, merged)
            if (configMerged != current.recurringPayments) {
                applyLocalConfigLocked(current.copy(recurringPayments = configMerged), hasUnsynced = prefs.hasUnsyncedConfig)
            }
        }

        val unsyncedIds = prefs.unsyncedRecurringIds
        return@withLock mergedById.values.filter { item ->
            val rem = remoteById[item.id]
            when {
                item.id in unsyncedIds -> rem == null || item.updatedAtEpochMs >= rem.updatedAtEpochMs
                rem != null -> item.updatedAtEpochMs > rem.updatedAtEpochMs
                isFullSnapshot -> true
                else -> false
            }
        }
    }

    private suspend fun mergeAndDetermineGoalsToPush(
        remoteDelta: List<SavingsGoalEntity>,
        isFullSnapshot: Boolean
    ): List<SavingsGoalEntity> = goalsMutex.withLock {
        val localList = savingsGoalDao.getAll()
        val remoteById = remoteDelta.associateBy { it.id }
        val merged = FamilyConfig.mergeSavingsGoals(localList, remoteDelta)
        val mergedById = merged.associateBy { it.id }

        val toSaveLocally = merged.filter { item ->
            val loc = localList.firstOrNull { it.id == item.id }
            loc == null || loc != item
        }
        if (toSaveLocally.isNotEmpty()) {
            savingsGoalDao.upsertAll(toSaveLocally)
        }
        synchronized(configLock) {
            val current = _familyConfig.value
            val configMerged = FamilyConfig.mergeSavingsGoals(current.savingsGoals, merged)
            if (configMerged != current.savingsGoals) {
                applyLocalConfigLocked(current.copy(savingsGoals = configMerged), hasUnsynced = prefs.hasUnsyncedConfig)
            }
        }

        val unsyncedIds = prefs.unsyncedGoalIds
        return@withLock mergedById.values.filter { item ->
            val rem = remoteById[item.id]
            when {
                item.id in unsyncedIds -> rem == null || item.updatedAtEpochMs >= rem.updatedAtEpochMs
                rem != null -> item.updatedAtEpochMs > rem.updatedAtEpochMs
                isFullSnapshot -> true
                else -> false
            }
        }
    }

    private suspend fun markPushedAsSynced(pushedVersions: Map<String, Long>) {
        if (pushedVersions.isEmpty()) return
        db.withTransaction {
            for ((id, updatedAt) in pushedVersions) {
                dao.markSyncedIfUnchanged(id, updatedAt)
            }
        }
    }

    suspend fun importExpensesBatch(items: List<ExpenseEntity>): Int = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext 0
        val existingIds = dao.getAllExpenses().map { it.id }.toSet()
        val newItems = items.filter { it.id !in existingIds }.map { it.copy(isSynced = false) }
        if (newItems.isNotEmpty()) {
            db.withTransaction {
                newItems.chunked(250).forEach { chunk ->
                    dao.upsertAll(chunk)
                }
            }
            BudgetWidgetProvider.requestUpdate(appContext)
            requestCoalescedSync()
        }
        newItems.size
    }

    suspend fun addOrUpdateExpense(expense: ExpenseEntity) {
        val now = maxOf(System.currentTimeMillis(), expense.updatedAtEpochMs + 1L)
        val effectiveDeviceId = expense.originDeviceId.ifBlank { prefs.deviceInstallationId }
        val toSave = expense.copy(
            updatedAtEpochMs = now,
            isSynced = false,
            originDeviceId = effectiveDeviceId
        )
        dao.upsert(toSave)
        BudgetWidgetProvider.requestUpdate(appContext)
        requestCoalescedSync()
    }

    suspend fun saveSplitExpenses(
        original: ExpenseEntity?,
        parts: List<SplitPartInput>,
        totalAmountRub: Double,
        merchant: String,
        authorName: String,
        note: String,
        sourceType: String = "MANUAL"
    ): List<ExpenseEntity> {
        val entities = SplitExpenseBuilder.build(
            original = original,
            parts = parts,
            totalAmountRub = totalAmountRub,
            merchant = merchant,
            authorName = authorName,
            note = note,
            sourceType = sourceType,
            originDeviceId = original?.originDeviceId?.ifBlank { prefs.deviceInstallationId }
                ?: prefs.deviceInstallationId
        )
        db.withTransaction {
            dao.upsertAll(entities)
        }
        BudgetWidgetProvider.requestUpdate(appContext)
        requestCoalescedSync()
        return entities
    }

    suspend fun confirmPendingExpense(expense: ExpenseEntity, categoryId: String) {
        val updated = expense.copy(
            categoryName = categoryId,
            isPendingConfirmation = false,
            updatedAtEpochMs = maxOf(System.currentTimeMillis(), expense.updatedAtEpochMs + 1L),
            isSynced = false
        )
        dao.upsert(updated)
        BudgetWidgetProvider.requestUpdate(appContext)
        requestCoalescedSync()
    }

    suspend fun softDeleteExpense(expense: ExpenseEntity) {
        val deleted = expense.copy(
            isDeleted = true,
            updatedAtEpochMs = maxOf(System.currentTimeMillis(), expense.updatedAtEpochMs + 1L),
            isSynced = false
        )
        dao.upsert(deleted)
        BudgetWidgetProvider.requestUpdate(appContext)
        requestCoalescedSync()
    }

    suspend fun restoreExpense(expense: ExpenseEntity) {
        val restored = expense.copy(
            isDeleted = false,
            updatedAtEpochMs = maxOf(System.currentTimeMillis(), expense.updatedAtEpochMs + 1L),
            isSynced = false
        )
        dao.upsert(restored)
        BudgetWidgetProvider.requestUpdate(appContext)
        requestCoalescedSync()
    }

    suspend fun upsertRecurringPayment(payment: RecurringPaymentEntity) {
        if (payment.id.isBlank()) return
        recurringMutex.withLock {
            val saved = synchronized(configLock) {
                val current = _familyConfig.value
                val existing = current.recurringPayments.firstOrNull { it.id == payment.id }
                val maxExistingTs = current.recurringPayments.maxOfOrNull { it.updatedAtEpochMs } ?: 0L
                val now = maxOf(
                    System.currentTimeMillis(),
                    maxExistingTs + 1L,
                    (existing?.updatedAtEpochMs ?: 0L) + 1L,
                    payment.updatedAtEpochMs
                )
                val normalized = payment.copy(
                    title = payment.title.trim(),
                    categoryId = payment.categoryId.ifBlank { ExpenseCategory.OTHER.name },
                    operationType = payment.effectiveOperationType.name,
                    dayOfMonth = payment.dayOfMonth.coerceIn(1, 31),
                    updatedAtEpochMs = now
                )
                val updatedList = FamilyConfig.mergeRecurringPayments(
                    local = current.recurringPayments,
                    remote = listOf(normalized)
                )
                applyLocalConfigLocked(
                    current.copy(recurringPayments = updatedList),
                    hasUnsynced = true
                )
                normalized
            }
            recurringDao.upsert(saved)
            prefs.markRecurringUnsynced(saved.id)
            evaluateAndPersistRecurringLocked()
        }
        BudgetWidgetProvider.requestUpdate(appContext)
        requestCoalescedSync()
    }

    suspend fun toggleRecurringPaymentActive(payment: RecurringPaymentEntity, active: Boolean) {
        upsertRecurringPayment(payment.copy(isActive = active))
    }

    suspend fun setRecurringPaymentPaidForCurrentMonth(
        payment: RecurringPaymentEntity,
        paid: Boolean
    ) {
        val changed = recurringMutex.withLock {
            val updated = synchronized(configLock) {
                val current = _familyConfig.value
                val latest = current.recurringPayments.firstOrNull { it.id == payment.id } ?: payment
                val now = maxOf(System.currentTimeMillis(), latest.updatedAtEpochMs + 1L)
                val nextPayment = if (paid) {
                    RecurringPaymentScheduler.markPaidForMonth(latest, nowMs = now)
                } else {
                    RecurringPaymentScheduler.clearPaidForMonth(latest, nowMs = now)
                }
                if (nextPayment == latest) return@synchronized null
                val updatedList = FamilyConfig.mergeRecurringPayments(
                    local = current.recurringPayments,
                    remote = listOf(nextPayment)
                )
                applyLocalConfigLocked(
                    current.copy(recurringPayments = updatedList),
                    hasUnsynced = true
                )
                nextPayment
            } ?: return@withLock false
            recurringDao.upsert(updated)
            prefs.markRecurringUnsynced(updated.id)
            true
        }
        if (changed) {
            BudgetWidgetProvider.requestUpdate(appContext)
            requestCoalescedSync()
        }
    }

    suspend fun softDeleteRecurringPayment(payment: RecurringPaymentEntity) {
        recurringMutex.withLock {
            val deleted = synchronized(configLock) {
                val current = _familyConfig.value
                val latest = current.recurringPayments.firstOrNull { it.id == payment.id } ?: payment
                val now = maxOf(System.currentTimeMillis(), latest.updatedAtEpochMs + 1L)
                val nextPayment = latest.copy(
                    isActive = false,
                    isDeleted = true,
                    updatedAtEpochMs = now
                )
                val updatedList = FamilyConfig.mergeRecurringPayments(
                    local = current.recurringPayments,
                    remote = listOf(nextPayment)
                )
                applyLocalConfigLocked(
                    current.copy(recurringPayments = updatedList),
                    hasUnsynced = true
                )
                nextPayment
            }
            recurringDao.upsert(deleted)
            prefs.markRecurringUnsynced(deleted.id)
        }
        BudgetWidgetProvider.requestUpdate(appContext)
        requestCoalescedSync()
    }

    suspend fun upsertSavingsGoal(goal: SavingsGoalEntity) {
        if (goal.id.isBlank()) return
        goalsMutex.withLock {
            val saved = synchronized(configLock) {
                val current = _familyConfig.value
                val existing = current.savingsGoals.firstOrNull { it.id == goal.id }
                val maxExistingTs = current.savingsGoals.maxOfOrNull { it.updatedAtEpochMs } ?: 0L
                val now = maxOf(
                    System.currentTimeMillis(),
                    maxExistingTs + 1L,
                    (existing?.updatedAtEpochMs ?: 0L) + 1L,
                    goal.updatedAtEpochMs
                )
                val normalized = goal.copy(
                    title = goal.title.trim(),
                    emoji = goal.emoji.trim().ifBlank { "🎯" },
                    targetAmountRub = if (goal.targetAmountRub.isFinite() && goal.targetAmountRub >= 0.0) {
                        goal.targetAmountRub
                    } else {
                        0.0
                    },
                    initialAmountRub = if (goal.initialAmountRub.isFinite() && goal.initialAmountRub >= 0.0) {
                        goal.initialAmountRub
                    } else {
                        0.0
                    },
                    updatedAtEpochMs = now
                )
                val updatedList = FamilyConfig.mergeSavingsGoals(
                    local = current.savingsGoals,
                    remote = listOf(normalized)
                )
                applyLocalConfigLocked(
                    current.copy(savingsGoals = updatedList),
                    hasUnsynced = true
                )
                normalized
            }
            savingsGoalDao.upsert(saved)
            prefs.markGoalUnsynced(saved.id)
        }
        BudgetWidgetProvider.requestUpdate(appContext)
        requestCoalescedSync()
    }

    suspend fun toggleSavingsGoalArchived(goal: SavingsGoalEntity, archived: Boolean) {
        upsertSavingsGoal(goal.copy(isArchived = archived))
    }

    suspend fun softDeleteSavingsGoal(goal: SavingsGoalEntity) {
        goalsMutex.withLock {
            val deleted = synchronized(configLock) {
                val current = _familyConfig.value
                val latest = current.savingsGoals.firstOrNull { it.id == goal.id } ?: goal
                val now = maxOf(System.currentTimeMillis(), latest.updatedAtEpochMs + 1L)
                val nextGoal = latest.copy(
                    isDeleted = true,
                    updatedAtEpochMs = now
                )
                val updatedList = FamilyConfig.mergeSavingsGoals(
                    local = current.savingsGoals,
                    remote = listOf(nextGoal)
                )
                applyLocalConfigLocked(
                    current.copy(savingsGoals = updatedList),
                    hasUnsynced = true
                )
                nextGoal
            }
            savingsGoalDao.upsert(deleted)
            prefs.markGoalUnsynced(deleted.id)
        }
        BudgetWidgetProvider.requestUpdate(appContext)
        requestCoalescedSync()
    }

    suspend fun recordGoalTransfer(
        goal: SavingsGoalEntity,
        amountRub: Double,
        isDeposit: Boolean,
        authorName: String,
        note: String = ""
    ) {
        if (goal.id.isBlank() || !amountRub.isFinite() || amountRub <= 0.0) return
        val now = System.currentTimeMillis()
        val categoryName = if (isDeposit) {
            SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT
        } else {
            SavingsGoalCalculator.CATEGORY_GOAL_WITHDRAW
        }
        val title = goal.title.ifBlank { "Копилка" }
        val merchant = if (isDeposit) {
            "Пополнение копилки: $title"
        } else {
            "Снятие с копилки: $title"
        }
        val transfer = ExpenseEntity(
            amountRub = amountRub,
            merchant = merchant,
            categoryName = categoryName,
            authorName = authorName,
            sourceType = "MANUAL",
            isPendingConfirmation = false,
            isDeleted = false,
            isSynced = false,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            note = note.trim(),
            operationType = OperationType.TRANSFER.name,
            goalId = goal.id,
            originDeviceId = prefs.deviceInstallationId
        )
        addOrUpdateExpense(transfer)
    }

    private suspend fun reconcileSavingsGoalsOnStartup() {
        val needsSync = goalsMutex.withLock {
            val roomList = savingsGoalDao.getAll()
            var configChanged = false
            val targetList = synchronized(configLock) {
                val current = _familyConfig.value
                val merged = FamilyConfig.mergeSavingsGoals(current.savingsGoals, roomList)
                if (merged != current.savingsGoals) {
                    applyLocalConfigLocked(current.copy(savingsGoals = merged), hasUnsynced = true)
                    configChanged = true
                }
                merged
            }
            if (targetList.isNotEmpty()) {
                savingsGoalDao.upsertAll(targetList)
            }
            configChanged
        }
        if (needsSync) {
            requestCoalescedSync()
        }
    }

    private suspend fun mirrorConfigGoalsToRoom() {
        goalsMutex.withLock {
            val configGoals = synchronized(configLock) { _familyConfig.value.savingsGoals }
            val roomById = savingsGoalDao.getAll().associateBy { it.id }
            val toUpsert = configGoals.filter { cfg ->
                val existing = roomById[cfg.id]
                existing == null || existing != cfg
            }
            if (toUpsert.isNotEmpty()) {
                savingsGoalDao.upsertAll(toUpsert)
            }
        }
    }

    private suspend fun reconcileAndProcessRecurringOnStartup() {
        val needsSync = recurringMutex.withLock {
            val roomList = recurringDao.getAll()
            var configChanged = false
            val targetList = synchronized(configLock) {
                val current = _familyConfig.value
                val merged = FamilyConfig.mergeRecurringPayments(current.recurringPayments, roomList)
                if (merged != current.recurringPayments) {
                    applyLocalConfigLocked(current.copy(recurringPayments = merged), hasUnsynced = true)
                    configChanged = true
                }
                merged
            }
            if (targetList.isNotEmpty()) {
                recurringDao.upsertAll(targetList)
            }
            val processed = evaluateAndPersistRecurringLocked()
            configChanged || processed
        }
        if (needsSync) {
            requestCoalescedSync()
        }
    }

    private suspend fun mirrorConfigRecurringToRoom() {
        recurringMutex.withLock {
            val configPayments = synchronized(configLock) { _familyConfig.value.recurringPayments }
            val roomById = recurringDao.getAll().associateBy { it.id }
            val toUpsert = configPayments.filter { cfg ->
                val existing = roomById[cfg.id]
                existing == null || existing != cfg
            }
            if (toUpsert.isNotEmpty()) {
                recurringDao.upsertAll(toUpsert)
            }
        }
    }

    private suspend fun evaluateAndPersistRecurringLocked(): Boolean {
        val payments = synchronized(configLock) { _familyConfig.value.recurringPayments }
        if (payments.isEmpty()) return false
        val existingIds = dao.getAllExpenses().map { it.id }.toSet()
        val result = RecurringPaymentScheduler.evaluateDuePayments(
            payments = payments,
            existingExpenseIds = existingIds,
            nowMs = System.currentTimeMillis(),
            defaultAuthorName = prefs.currentUserName
        )
        if (result.generatedExpenses.isEmpty() && result.updatedPayments.isEmpty()) {
            return false
        }

        val updatedById = result.updatedPayments.associateBy { it.id }
        val mergedPayments = synchronized(configLock) {
            val current = _familyConfig.value
            val nextList = FamilyConfig.mergeRecurringPayments(
                local = current.recurringPayments,
                remote = result.updatedPayments
            )
            if (nextList != current.recurringPayments) {
                applyLocalConfigLocked(
                    current.copy(recurringPayments = nextList),
                    hasUnsynced = true
                )
            }
            nextList
        }

        db.withTransaction {
            if (result.generatedExpenses.isNotEmpty()) {
                val toInsert = result.generatedExpenses.filter { it.id !in existingIds }
                if (toInsert.isNotEmpty()) {
                    dao.upsertAll(toInsert)
                }
            }
            if (updatedById.isNotEmpty()) {
                recurringDao.upsertAll(mergedPayments.filter { it.id in updatedById })
                updatedById.keys.forEach { prefs.markRecurringUnsynced(it) }
            }
        }
        return true
    }

    /**
     * Вызывается из BankPushListenerService при получении пуша/SMS от включённых банков или Mir Pay.
     * Защита от дублей на одном устройстве (окно [SAME_DEVICE_DEDUP_WINDOW_MS]) учитывает sourceType,
     * нормализованный мерчант и текст пуша — см. [findSameDeviceDuplicate].
     */
    fun enqueueBankNotification(
        amountRub: Double,
        merchant: String,
        sourceType: String,
        rawText: String,
        suggestedOperationType: String = OperationType.EXPENSE.name
    ) {
        scope.launch {
            ingestBankNotification(
                amountRub = amountRub,
                merchant = merchant,
                sourceType = sourceType,
                rawText = rawText,
                suggestedOperationType = suggestedOperationType
            )
        }
    }

    suspend fun ingestBankNotification(
        amountRub: Double,
        merchant: String,
        sourceType: String,
        rawText: String,
        suggestedOperationType: String = OperationType.EXPENSE.name
    ) {
        val inserted = withContext(NonCancellable) {
            ingestMutex.withLock {
                val now = System.currentTimeMillis()
                val signature = buildAmountSignature(amountRub)
                val candidates = dao.findRecentDuplicateCandidates(signature, now - SAME_DEVICE_DEDUP_WINDOW_MS)
                val duplicate = findSameDeviceDuplicate(candidates, sourceType, merchant, rawText, nowMs = now)
                if (duplicate != null) {
                    if (BuildConfig.DEBUG) {
                        Log.i(
                            "BudgetRepository",
                            "Duplicate push ignored: source=$sourceType existing=${duplicate.sourceType}"
                        )
                    }
                    return@withLock false
                }

                val normalizedOpType = runCatching {
                    OperationType.valueOf(suggestedOperationType.trim().uppercase())
                }.getOrDefault(OperationType.EXPENSE)

                val registrySnapshot = synchronized(configLock) { _categoryRegistry.value }
                val guessedCategory = if (normalizedOpType == OperationType.TRANSFER) {
                    registrySnapshot.otherExpenseCategory
                } else {
                    registrySnapshot.guessFromMerchant(
                        merchant,
                        isIncome = normalizedOpType == OperationType.INCOME
                    )
                }
                val storedOperationType = if (normalizedOpType == OperationType.EXPENSE) {
                    ""
                } else {
                    normalizedOpType.name
                }
                val expense = ExpenseEntity(
                    amountRub = amountRub,
                    merchant = merchant.ifBlank { "Покупка по карте" },
                    categoryName = guessedCategory.id,
                    authorName = prefs.currentUserName,
                    sourceType = sourceType,
                    isPendingConfirmation = !prefs.autoConfirmBankPush,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    rawPushOrQrText = rawText,
                    dedupSignature = signature,
                    operationType = storedOperationType,
                    originDeviceId = prefs.deviceInstallationId
                )
                dao.upsert(expense)
                true
            }
        }
        if (inserted) {
            BudgetWidgetProvider.requestUpdate(appContext)
            requestCoalescedSync()
        }
    }

    companion object {
        internal const val FOREGROUND_POLL_INTERVAL_MS = 60_000L
        internal const val BACKGROUND_POLL_INTERVAL_MS = 300_000L

        /** Окно кросс-девайс дедупа общей карты (Mir Pay ↔ Банк или Банк ↔ Банк с двух телефонов): ±3 минуты. */
        internal const val CROSS_DEVICE_DEDUP_WINDOW_MS = 180_000L

        /** Окно отбора недавних синхронизированных пушей в Room для кросс-девайсной склейки при delta-синке. */
        private const val INCREMENTAL_DEDUP_CANDIDATE_WINDOW_MS = 15 * 60_000L

        /**
         * Окно дедупа пушей на одном устройстве (>= 5 минут, покрывает допустимый дрифт
         * [ru.pokanevich.familybudget.service.BankPushTimeValidator.MAX_ALLOWED_DRIFT_MS]).
         */
        internal const val SAME_DEVICE_DEDUP_WINDOW_MS = 300_000L

        private val BALANCE_MARKER_REGEX = Regex(
            "(?:баланс|остаток|доступно|в\\s+запасе|запас\\s+средств)",
            RegexOption.IGNORE_CASE
        )
        private const val RAPID_SAME_TEXT_DUPLICATE_MS = 3_000L

        internal fun buildConfigSyncPlan(
            local: FamilyConfig,
            remote: FamilyConfig?,
            remoteExists: Boolean
        ): ConfigSyncMergePlan {
            if (!remoteExists || remote == null) {
                return ConfigSyncMergePlan(
                    mergedConfig = local,
                    updateLocal = false,
                    needCloudPush = true
                )
            }
            val merged = FamilyConfig.merge(local, remote)
            return ConfigSyncMergePlan(
                mergedConfig = merged,
                updateLocal = !merged.isEquivalentTo(local),
                needCloudPush = !merged.isEquivalentTo(remote)
            )
        }

        internal fun amountToKopecks(amountRub: Double): Long =
            if (amountRub.isFinite()) (amountRub * 100.0).roundToLong() else 0L

        internal fun buildAmountSignature(amountRub: Double): String {
            val kopecks = amountToKopecks(amountRub)
            val absKopecks = abs(kopecks)
            val sign = if (kopecks < 0L) "-" else ""
            return "AMT_${sign}${absKopecks / 100L}.${String.format(java.util.Locale.US, "%02d", absKopecks % 100L)}"
        }

        private fun isBankPushSource(sourceType: String): Boolean =
            sourceType.startsWith("PUSH_")

        private fun normalizePushText(text: String): String =
            text.lowercase().replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

        /** «Шаблон» текста пуша без цифр: одинаков у двух покупок из одного канала (приложение/SMS). */
        private fun pushTextTemplate(text: String): String =
            normalizePushText(text)
                .replace(Regex("[0-9]"), "")
                .replace(Regex("[\\s.,:;₽*•-]+"), " ")
                .trim()

        /**
         * Ищет среди кандидатов (та же сумма, недавнее время) дубль нового пуша на этом устройстве.
         *
         * - Разные sourceType (Mir Pay + банковский PUSH_* об одной покупке): дубль, если мерчанты совместимы
         *   (нормализация с транслитом или шаблонный мерчант).
         * - Одинаковый sourceType:
         *   - идентичный текст с балансом/остатком — это повторная доставка того же пуша → дубль;
         *   - идентичный текст без баланса (например, два билета по 50 ₽ в Mir Pay) по содержимому не отличить —
         *     НЕ дубль, повторную доставку отсекает сохранённый dedupKey в BankPushListenerService;
         *   - тот же «шаблон» текста (отличаются только цифры: баланс, время) — две реальные покупки → НЕ дубль;
         *   - разный шаблон (например, SMS 900 и пуш приложения Сбера) и совместимый мерчант → дубль.
         */
        internal fun findSameDeviceDuplicate(
            candidates: List<ExpenseEntity>,
            sourceType: String,
            merchant: String,
            rawText: String,
            nowMs: Long = System.currentTimeMillis()
        ): ExpenseEntity? = candidates.firstOrNull { existing ->
            if (existing.isDeleted) return@firstOrNull false
            if (existing.sourceType != sourceType) {
                val hasMirPayPair = existing.sourceType == "PUSH_MIRPAY" || sourceType == "PUSH_MIRPAY"
                return@firstOrNull hasMirPayPair &&
                    isBankPushSource(existing.sourceType) &&
                    isBankPushSource(sourceType) &&
                    MerchantMatcher.areCompatible(existing.merchant, merchant)
            }
            val existingText = normalizePushText(existing.rawPushOrQrText)
            val newText = normalizePushText(rawText)
            when {
                existingText.isEmpty() || newText.isEmpty() -> false
                existingText == newText ->
                    BALANCE_MARKER_REGEX.containsMatchIn(newText) ||
                        abs(nowMs - existing.createdAtEpochMs) <= RAPID_SAME_TEXT_DUPLICATE_MS
                pushTextTemplate(existingText) == pushTextTemplate(newText) -> false
                else -> MerchantMatcher.areCompatible(existing.merchant, merchant)
            }
        }

        /**
         * Слияние локальной базы и облака по UUID (Last-Write-Wins) + кросс-девайс дедупликация.
         * Локальные несинхронизированные изменения и losers дедупа НЕ помечаются isSynced = true:
         * это делается только после успешного PUT через [SyncMergePlan.pushedVersions].
         */
        internal fun buildSyncMergePlan(
            localList: List<ExpenseEntity>,
            remoteMap: Map<String, ExpenseEntity>,
            remoteExists: Boolean,
            nowMs: Long = System.currentTimeMillis()
        ): SyncMergePlan = buildIncrementalSyncMergePlan(
            localList = localList,
            remoteDeltaMap = remoteMap,
            remoteExists = remoteExists,
            isFullSnapshot = true,
            nowMs = nowMs
        )

        /**
         * Слияние локальной Room-базы с полным снимком или инкрементальной дельтой Firestore:
         * - при [isFullSnapshot] == false уже синхронизированные локальные записи, не вошедшие в дельту,
         *   НЕ помечаются к повторной отправке;
         * - при этом недавние банковские пуши включаются в кандидатный пул кросс-девайсной дедупликации.
         */
        internal fun buildIncrementalSyncMergePlan(
            localList: List<ExpenseEntity>,
            remoteDeltaMap: Map<String, ExpenseEntity>,
            remoteExists: Boolean,
            isFullSnapshot: Boolean,
            nowMs: Long = System.currentTimeMillis()
        ): SyncMergePlan {
            val localMap = localList.associateBy { it.id }
            var needCloudPush = isFullSnapshot && !remoteExists
            val mergedMap = mutableMapOf<String, ExpenseEntity>()
            val dedupCandidatePool = mutableMapOf<String, ExpenseEntity>()
            val toUpsertLocally = mutableListOf<ExpenseEntity>()
            val pushedVersions = mutableMapOf<String, Long>()

            val allIds = (remoteDeltaMap.keys + localMap.keys).toSet()
            for (id in allIds) {
                val loc = localMap[id]
                val rem = remoteDeltaMap[id]
                when {
                    loc != null && rem == null -> {
                        if (isFullSnapshot || !loc.isSynced) {
                            mergedMap[id] = loc
                            dedupCandidatePool[id] = loc
                            needCloudPush = true
                            if (!loc.isSynced) pushedVersions[id] = loc.updatedAtEpochMs
                        } else if (!loc.isDeleted &&
                            isBankPushSource(loc.sourceType) &&
                            abs(nowMs - loc.createdAtEpochMs) <= INCREMENTAL_DEDUP_CANDIDATE_WINDOW_MS
                        ) {
                            dedupCandidatePool[id] = loc
                        }
                    }
                    loc == null && rem != null -> {
                        val syncedRem = rem.copy(isSynced = true)
                        mergedMap[id] = syncedRem
                        dedupCandidatePool[id] = syncedRem
                        toUpsertLocally.add(syncedRem)
                    }
                    loc != null && rem != null -> {
                        val resolvedRecurring = resolveRecurringConflict(loc, rem)
                        if (resolvedRecurring != null) {
                            mergedMap[id] = resolvedRecurring
                            dedupCandidatePool[id] = resolvedRecurring
                            if (resolvedRecurring.updatedAtEpochMs > rem.updatedAtEpochMs || !resolvedRecurring.isSynced) {
                                needCloudPush = true
                                pushedVersions[id] = resolvedRecurring.updatedAtEpochMs
                            }
                            if (resolvedRecurring != loc) {
                                toUpsertLocally.add(resolvedRecurring)
                            }
                        } else if (loc.updatedAtEpochMs > rem.updatedAtEpochMs) {
                            mergedMap[id] = loc
                            dedupCandidatePool[id] = loc
                            needCloudPush = true
                            if (!loc.isSynced) pushedVersions[id] = loc.updatedAtEpochMs
                        } else if (rem.updatedAtEpochMs > loc.updatedAtEpochMs) {
                            val syncedRem = rem.copy(isSynced = true)
                            mergedMap[id] = syncedRem
                            dedupCandidatePool[id] = syncedRem
                            toUpsertLocally.add(syncedRem)
                        } else {
                            val synced = loc.copy(isSynced = true)
                            mergedMap[id] = synced
                            dedupCandidatePool[id] = synced
                            if (!loc.isSynced) {
                                toUpsertLocally.add(synced)
                            }
                        }
                    }
                }
            }

            val dedupResult = deduplicateCrossDevicePushExpensesWithEnrichment(dedupCandidatePool, nowMs)
            val dedupedLosers = dedupResult.losers
            val changedByDedup = dedupResult.losers + dedupResult.enrichedWinners
            if (changedByDedup.isNotEmpty()) {
                val changedIds = changedByDedup.map { it.id }.toSet()
                toUpsertLocally.removeAll { it.id in changedIds }
                toUpsertLocally.addAll(changedByDedup)
                changedByDedup.forEach {
                    mergedMap[it.id] = it
                    pushedVersions[it.id] = it.updatedAtEpochMs
                }
                needCloudPush = true
            }

            return SyncMergePlan(
                mergedMap = mergedMap,
                toUpsertLocally = toUpsertLocally,
                needCloudPush = needCloudPush,
                pushedVersions = pushedVersions,
                dedupedLosers = dedupedLosers
            )
        }

        internal data class CrossDeviceDedupResult(
            val losers: List<ExpenseEntity>,
            val enrichedWinners: List<ExpenseEntity>
        )

        private fun isRecurringAutoDraft(item: ExpenseEntity): Boolean =
            item.sourceType == RecurringPaymentScheduler.SOURCE_TYPE_RECURRING &&
                item.isPendingConfirmation &&
                !item.isDeleted

        /**
         * Anti-resurrection guard для детерминированных регулярных операций (`rec_<id>_<YYYY-MM>`):
         * авто-сгенерированный черновик из другого часового пояса не должен воскрешать tombstone (`isDeleted`)
         * или откатывать подтверждённую запись обратно в `isPendingConfirmation = true`.
         */
        private fun resolveRecurringConflict(loc: ExpenseEntity, rem: ExpenseEntity): ExpenseEntity? {
            val involvesRecurring = loc.sourceType == RecurringPaymentScheduler.SOURCE_TYPE_RECURRING ||
                rem.sourceType == RecurringPaymentScheduler.SOURCE_TYPE_RECURRING
            if (!involvesRecurring) return null

            if (loc.isDeleted != rem.isDeleted) {
                val deletedSide = if (loc.isDeleted) loc else rem
                val liveSide = if (loc.isDeleted) rem else loc
                if (isRecurringAutoDraft(liveSide)) {
                    val bumpedTs = maxOf(deletedSide.updatedAtEpochMs, liveSide.updatedAtEpochMs)
                    val fromLocal = loc.isDeleted
                    val matchesRemoteVersion = !fromLocal && bumpedTs == rem.updatedAtEpochMs && rem.isDeleted
                    return deletedSide.copy(
                        updatedAtEpochMs = bumpedTs,
                        isSynced = matchesRemoteVersion
                    )
                }
            }

            if (!loc.isDeleted && !rem.isDeleted && loc.isPendingConfirmation != rem.isPendingConfirmation) {
                val confirmedSide = if (!loc.isPendingConfirmation) loc else rem
                val draftSide = if (loc.isPendingConfirmation) loc else rem
                if (isRecurringAutoDraft(draftSide)) {
                    val bumpedTs = maxOf(confirmedSide.updatedAtEpochMs, draftSide.updatedAtEpochMs)
                    val fromLocal = !loc.isPendingConfirmation
                    val matchesRemoteVersion = !fromLocal && bumpedTs == rem.updatedAtEpochMs && !rem.isPendingConfirmation
                    return confirmedSide.copy(
                        updatedAtEpochMs = bumpedTs,
                        isSynced = matchesRemoteVersion
                    )
                }
            }

            return null
        }

        private val CARD_LAST4_IN_RAW_REGEX = Regex(
            "(?:[•*·]+\\s*)+(\\d{4})\\b|\\b(?:карт[аеыу]?|сч[её]т[ау]?|mir|мир|visa|mastercard)\\s*[•*·-]*\\s*(\\d{4})\\b",
            RegexOption.IGNORE_CASE
        )

        private fun extractCardLast4(rawText: String): String? {
            if (rawText.isBlank()) return null
            val m = CARD_LAST4_IN_RAW_REGEX.find(rawText) ?: return null
            return m.groupValues[1].ifBlank { m.groupValues[2] }.takeIf { it.length == 4 }
        }

        private fun isCrossDeviceOrigin(first: ExpenseEntity, second: ExpenseEntity): Boolean {
            val idA = first.originDeviceId.trim()
            val idB = second.originDeviceId.trim()
            if (idA.isNotEmpty() && idB.isNotEmpty()) {
                return idA != idB
            }
            val authorA = first.authorName.trim()
            val authorB = second.authorName.trim()
            return authorA.isNotEmpty() && authorB.isNotEmpty() && !authorA.equals(authorB, ignoreCase = true)
        }

        private fun canPairAsCrossDeviceDuplicate(first: ExpenseEntity, second: ExpenseEntity): Boolean {
            val deltaMs = abs(second.createdAtEpochMs - first.createdAtEpochMs)
            if (deltaMs > CROSS_DEVICE_DEDUP_WINDOW_MS) return false

            if (first.sourceType != second.sourceType) {
                val hasMirPayPair = first.sourceType == "PUSH_MIRPAY" || second.sourceType == "PUSH_MIRPAY"
                if (!hasMirPayPair) return false
                return MerchantMatcher.areCompatible(first.merchant, second.merchant)
            }

            // Одинаковый банковский канал (например, PUSH_SBER + PUSH_SBER по общей карте):
            // склеиваем только если записи точно пришли с разных устройств/авторов.
            if (!isCrossDeviceOrigin(first, second)) return false
            if (!MerchantMatcher.areCompatible(first.merchant, second.merchant)) return false

            if (MerchantMatcher.isGenericMerchant(first.merchant) &&
                MerchantMatcher.isGenericMerchant(second.merchant)
            ) {
                val cardA = extractCardLast4(first.rawPushOrQrText)
                val cardB = extractCardLast4(second.rawPushOrQrText)
                return when {
                    cardA != null && cardB != null -> cardA == cardB
                    else -> deltaMs <= 60_000L
                }
            }
            return true
        }

        private fun shouldFirstWinCrossDevice(first: ExpenseEntity, second: ExpenseEntity): Boolean {
            if (first.isPendingConfirmation != second.isPendingConfirmation) {
                return !first.isPendingConfirmation
            }
            if (first.sourceType != second.sourceType) {
                val firstMirPay = first.sourceType == "PUSH_MIRPAY"
                val secondMirPay = second.sourceType == "PUSH_MIRPAY"
                if (firstMirPay != secondMirPay) return firstMirPay
            }
            val firstSpecificMerchant = !MerchantMatcher.isGenericMerchant(first.merchant)
            val secondSpecificMerchant = !MerchantMatcher.isGenericMerchant(second.merchant)
            if (firstSpecificMerchant != secondSpecificMerchant) {
                return firstSpecificMerchant
            }
            val firstSpecificCat = first.categoryName != ExpenseCategory.OTHER.name
            val secondSpecificCat = second.categoryName != ExpenseCategory.OTHER.name
            if (firstSpecificCat != secondSpecificCat) {
                return firstSpecificCat
            }
            if (first.createdAtEpochMs != second.createdAtEpochMs) {
                return first.createdAtEpochMs < second.createdAtEpochMs
            }
            return first.id < second.id
        }

        /**
         * Кросс-девайсная дедупликация общей карты («Я + Жена»):
         * - сравнивает операции в копейках (`amountToKopecks`) и по `effectiveOperationType`;
         * - объединяет пары `PUSH_MIRPAY` ↔ `PUSH_<BANK>`, а также одинаковый канал `PUSH_<BANK>` ↔ `PUSH_<BANK>`
         *   с двух разных устройств/авторов в пределах [CROSS_DEVICE_DEDUP_WINDOW_MS] (3 мин);
         * - детерминированно выбирает победителя и обогащает шаблонный мерчант/категорию победителя
         *   метаданными проигравшей записи; проигравшая запись мягко удаляется (`isDeleted = true`, `isSynced = false`).
         */
        internal fun deduplicateCrossDevicePushExpenses(
            mergedMap: MutableMap<String, ExpenseEntity>,
            nowMs: Long = System.currentTimeMillis()
        ): List<ExpenseEntity> = deduplicateCrossDevicePushExpensesWithEnrichment(mergedMap, nowMs).losers

        internal fun deduplicateCrossDevicePushExpensesWithEnrichment(
            mergedMap: MutableMap<String, ExpenseEntity>,
            nowMs: Long = System.currentTimeMillis()
        ): CrossDeviceDedupResult {
            val candidatesByKey = mergedMap.values
                .filter {
                    !it.isDeleted &&
                        isBankPushSource(it.sourceType) &&
                        amountToKopecks(it.amountRub) > 0L
                }
                .groupBy { amountToKopecks(it.amountRub) to it.effectiveOperationType }

            val deletedLosers = mutableListOf<ExpenseEntity>()
            val enrichedWinnersById = linkedMapOf<String, ExpenseEntity>()

            for ((_, group) in candidatesByKey) {
                if (group.size < 2) continue
                val sorted = group.sortedWith(
                    compareBy<ExpenseEntity> { it.createdAtEpochMs }.thenBy { it.id }
                )
                for (i in sorted.indices) {
                    var first = mergedMap[sorted[i].id] ?: continue
                    if (first.isDeleted) continue
                    for (j in (i + 1) until sorted.size) {
                        val second = mergedMap[sorted[j].id] ?: continue
                        if (second.isDeleted) continue
                        if (second.createdAtEpochMs - first.createdAtEpochMs > CROSS_DEVICE_DEDUP_WINDOW_MS) {
                            break
                        }
                        if (!canPairAsCrossDeviceDuplicate(first, second)) continue

                        val firstWins = shouldFirstWinCrossDevice(first, second)
                        var winner = if (firstWins) first else second
                        val loser = if (firstWins) second else first

                        val needsMerchantEnrichment = MerchantMatcher.isGenericMerchant(winner.merchant) &&
                            !MerchantMatcher.isGenericMerchant(loser.merchant)
                        val needsCategoryEnrichment = winner.categoryName == ExpenseCategory.OTHER.name &&
                            loser.categoryName != ExpenseCategory.OTHER.name &&
                            !winner.isIncome &&
                            !winner.isTransfer
                        val needsNoteEnrichment = winner.note.isBlank() && loser.note.isNotBlank()

                        if (needsMerchantEnrichment || needsCategoryEnrichment || needsNoteEnrichment) {
                            winner = winner.copy(
                                merchant = if (needsMerchantEnrichment) loser.merchant else winner.merchant,
                                categoryName = if (needsCategoryEnrichment) loser.categoryName else winner.categoryName,
                                note = if (needsNoteEnrichment) loser.note else winner.note,
                                updatedAtEpochMs = maxOf(nowMs, winner.updatedAtEpochMs + 1L),
                                isSynced = false
                            )
                            mergedMap[winner.id] = winner
                            enrichedWinnersById[winner.id] = winner
                            if (firstWins) {
                                first = winner
                            }
                        }

                        val updatedLoser = loser.copy(
                            isDeleted = true,
                            updatedAtEpochMs = maxOf(nowMs, loser.updatedAtEpochMs + 1L),
                            isSynced = false
                        )
                        mergedMap[loser.id] = updatedLoser
                        deletedLosers.add(updatedLoser)
                        if (!firstWins) break
                    }
                }
            }
            return CrossDeviceDedupResult(
                losers = deletedLosers,
                enrichedWinners = enrichedWinnersById.values.toList()
            )
        }

        @Volatile
        private var INSTANCE: BudgetRepository? = null

        fun getInstance(context: Context): BudgetRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BudgetRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
