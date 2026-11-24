package ru.pokanevich.familybudget.data

import android.content.Context
import android.util.Base64
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
import org.json.JSONArray
import org.json.JSONObject
import ru.pokanevich.familybudget.widget.BudgetWidgetProvider
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
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

    private var cachedFamilyCode: String? = null
    private var cachedEtag: String? = null
    private var cachedRemoteSha: String? = null
    private var cachedRemoteMap: Map<String, ExpenseEntity> = emptyMap()

    private var cachedConfigEtag: String? = null
    private var cachedConfigSha: String? = null
    private var cachedRemoteConfig: FamilyConfig? = null

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
     * Переключает частоту фонового поллинга GitHub API в зависимости от жизненного цикла UI:
     * на активном экране — каждые 15 секунд, в фоне — раз в 3 минуты (при переходе в foreground
     * цикл пробуждается немедленно и проверяет наступившие регулярные платежи).
     */
    fun setForegroundActive(active: Boolean) {
        val previous = isForegroundActive
        isForegroundActive = active
        if (active && !previous) {
            scope.launch {
                val changed = recurringMutex.withLock {
                    evaluateAndPersistRecurringLocked()
                }
                if (changed) {
                    requestCoalescedSync()
                }
            }
            foregroundWakeupSignal.trySend(Unit)
        }
    }

    /**
     * Запускает двустороннюю облачную синхронизацию между двумя смартфонами
     * через приватный GitHub-репозиторий epokanevich/family-budget-sync-db.
     * Использует ETag (304 Not Modified) для экономии трафика и пакетный Room upsertAll.
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
                            Log.e("BudgetRepository", "Cloud sync blocked: ${err.message}")
                        } else {
                            Log.w("BudgetRepository", "Cloud sync offline: ${err.message}")
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
        val targetCode = FamilyPreferences.normalizeFamilyCode(newFamilyCode)
        val switched = syncMutex.withLock {
            val currentCode = FamilyPreferences.normalizeFamilyCode(prefs.familySpaceCode)
            if (currentCode == targetCode) return@withLock false
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
                    cachedFamilyCode = targetCode
                    cachedEtag = null
                    cachedRemoteSha = null
                    cachedRemoteMap = emptyMap()
                    cachedConfigEtag = null
                    cachedConfigSha = null
                    cachedRemoteConfig = null
                    prefs.recentPushDedupKeys = emptyList()
                    prefs.familySpaceCode = targetCode
                }
            }
            true
        }
        if (switched) {
            BudgetWidgetProvider.requestUpdate(appContext)
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

    private sealed interface GithubFileFetchResult {
        data object NotModified : GithubFileFetchResult
        data object NotFound : GithubFileFetchResult
        data class Loaded(
            val sha: String?,
            val size: Int,
            val etag: String?,
            val decodedText: String
        ) : GithubFileFetchResult
    }

    private class RemoteConfigSnapshot(
        val sha: String?,
        val config: FamilyConfig?,
        val notModified: Boolean
    )

    private class RemoteSnapshot(
        val sha: String?,
        val items: Map<String, ExpenseEntity>,
        val notModified: Boolean
    )

    private suspend fun performSyncCycle(): SyncState {
        val token = prefs.githubSyncToken
        if (token.isBlank()) return SyncState.OFFLINE_READY

        val familyCode = FamilyPreferences.normalizeFamilyCode(prefs.familySpaceCode)

        if (cachedFamilyCode != familyCode) {
            cachedFamilyCode = familyCode
            cachedEtag = null
            cachedRemoteSha = null
            cachedRemoteMap = emptyMap()
            cachedConfigEtag = null
            cachedConfigSha = null
            cachedRemoteConfig = null
        }

        // 1. Строго сначала синхронизируем config_v1.json, чтобы новые кастомные категории
        // и шаблоны регулярных платежей всегда приезжали раньше операций из budget_v3.json.
        syncConfigCycle(familyCode, token)
        mirrorConfigRecurringToRoom()
        mirrorConfigGoalsToRoom()

        // 2. Затем синхронизируем операции в budget_v3.json.
        syncExpensesCycle(familyCode, token)

        // 3. Проверяем наступившие регулярные платежи уже на свежей базе (без дублей с другим телефоном).
        val recurringChanged = recurringMutex.withLock {
            evaluateAndPersistRecurringLocked()
        }
        if (recurringChanged) {
            requestCoalescedSync()
        }

        return SyncState.CLOUD_CONNECTED
    }

    private fun syncConfigCycle(familyCode: String, token: String) {
        val filePath = "families/$familyCode/config_v1.json"
        val apiUrl = "https://api.github.com/repos/epokanevich/family-budget-sync-db/contents/$filePath"

        for (attempt in 0..1) {
            val remote = fetchRemoteConfigSnapshot(apiUrl, filePath, familyCode, token)
            val hasUnsyncedBeforeMerge = synchronized(configLock) { prefs.hasUnsyncedConfig }

            if (remote.notModified && !hasUnsyncedBeforeMerge) {
                return
            }

            val localNow = synchronized(configLock) { _familyConfig.value }
            val plan = buildConfigSyncPlan(
                local = localNow,
                remote = remote.config,
                remoteExists = remote.sha != null
            )

            if (!plan.needCloudPush) {
                if (remote.sha != null) {
                    prefs.markConfigSyncedForFamily(familyCode)
                }
                val needFollowUp = synchronized(configLock) {
                    val latest = _familyConfig.value
                    if (latest.isEquivalentTo(localNow)) {
                        if (plan.updateLocal || prefs.hasUnsyncedConfig) {
                            applyLocalConfigLocked(plan.mergedConfig, hasUnsynced = false)
                        }
                        false
                    } else {
                        val reMerged = FamilyConfig.merge(latest, plan.mergedConfig)
                        val stillNeedsPush = remote.config == null || !reMerged.isEquivalentTo(remote.config)
                        applyLocalConfigLocked(reMerged, hasUnsynced = stillNeedsPush)
                        stillNeedsPush
                    }
                }
                if (needFollowUp) {
                    requestCoalescedSync()
                }
                return
            }

            val payloadToPush = synchronized(configLock) {
                val latest = _familyConfig.value
                val toSave = if (latest.isEquivalentTo(localNow)) {
                    plan.mergedConfig
                } else {
                    FamilyConfig.merge(latest, plan.mergedConfig)
                }
                if (!toSave.isEquivalentTo(latest) || !prefs.hasUnsyncedConfig) {
                    applyLocalConfigLocked(toSave, hasUnsynced = true)
                }
                toSave
            }

            val rawBytes = payloadToPush.toJsonObject().toString().toByteArray(Charsets.UTF_8)
            val putCode = putRemoteFileBytes(
                apiUrl = apiUrl,
                token = token,
                commitMessage = "Sync family config ($familyCode)",
                rawBytes = rawBytes,
                remoteSha = remote.sha
            ) { newSha ->
                cachedConfigSha = newSha?.ifBlank { null } ?: remote.sha
                cachedConfigEtag = null
                cachedRemoteConfig = payloadToPush
            }

            when {
                putCode in 200..299 -> {
                    prefs.markConfigSyncedForFamily(familyCode)
                    val needFollowUp = synchronized(configLock) {
                        val latest = _familyConfig.value
                        if (latest.isEquivalentTo(payloadToPush)) {
                            applyLocalConfigLocked(payloadToPush, hasUnsynced = false)
                            false
                        } else {
                            val reMerged = FamilyConfig.merge(latest, payloadToPush)
                            applyLocalConfigLocked(reMerged, hasUnsynced = true)
                            true
                        }
                    }
                    if (needFollowUp) {
                        requestCoalescedSync()
                    }
                    return
                }
                putCode == HttpURLConnection.HTTP_CONFLICT -> {
                    cachedConfigEtag = null
                    cachedConfigSha = null
                    cachedRemoteConfig = null
                    if (attempt == 0) {
                        Log.w("BudgetRepository", "GitHub Config PUT HTTP 409 conflict, retrying sync cycle immediately")
                        continue
                    }
                    throw IllegalStateException("GitHub Config PUT HTTP $putCode")
                }
                else -> {
                    cachedConfigEtag = null
                    throw IllegalStateException("GitHub Config PUT HTTP $putCode")
                }
            }
        }
    }

    private suspend fun syncExpensesCycle(familyCode: String, token: String) {
        val filePath = "families/$familyCode/budget_v3.json"
        val apiUrl = "https://api.github.com/repos/epokanevich/family-budget-sync-db/contents/$filePath"

        for (attempt in 0..1) {
            // 1. Читаем облачный файл (ETag / Blob API для файлов > 1 МБ)
            val remote = fetchRemoteSnapshot(apiUrl, filePath, token)

            // Если облако не менялось и локально нечего отправлять — завершаем мгновенно
            if (remote.notModified && dao.getUnsyncedExpenses().isEmpty()) {
                return
            }

            // 2. Сливаем локальные записи Room и облачные записи по UUIDv4 (Last-Write-Wins) + кросс-девайс дедуп
            val plan = buildSyncMergePlan(
                localList = dao.getAllExpenses(),
                remoteMap = remote.items,
                remoteExists = remote.sha != null
            )

            if (plan.toUpsertLocally.isNotEmpty()) {
                dao.upsertAll(plan.toUpsertLocally)
            }

            if (!plan.needCloudPush) return

            // 3. Сохраняем объединённый список в облако (с актуальным CategoryRegistry из config_v1.json)
            val registrySnapshot = synchronized(configLock) { _categoryRegistry.value }
            val putCode = putRemoteExpensesFile(
                apiUrl = apiUrl,
                token = token,
                familyCode = familyCode,
                mergedMap = plan.mergedMap,
                registry = registrySnapshot,
                remoteSha = remote.sha
            ) { newSha ->
                cachedRemoteSha = newSha?.ifBlank { null } ?: remote.sha
                cachedEtag = null
                cachedRemoteMap = plan.mergedMap.mapValues { it.value.copy(isSynced = true) }
            }

            when {
                putCode in 200..299 -> {
                    // Только теперь отправленные локальные изменения (включая losers дедупа) считаются синхронизированными
                    markPushedAsSynced(plan.pushedVersions)
                    return
                }
                putCode == HttpURLConnection.HTTP_CONFLICT -> {
                    cachedEtag = null
                    cachedRemoteSha = null
                    if (attempt == 0) {
                        Log.w("BudgetRepository", "GitHub PUT HTTP 409 conflict, retrying sync cycle immediately")
                        continue
                    }
                    throw IllegalStateException("GitHub PUT HTTP $putCode")
                }
                else -> {
                    cachedEtag = null
                    throw IllegalStateException("GitHub PUT HTTP $putCode")
                }
            }
        }
    }

    private fun fetchGithubFileContent(
        apiUrl: String,
        token: String,
        etag: String?,
        errorLabel: String
    ): GithubFileFetchResult {
        val getConn = openGithubConnection(apiUrl, token, "GET", readTimeoutMs = 25_000)
        try {
            if (!etag.isNullOrBlank()) {
                getConn.setRequestProperty("If-None-Match", etag)
            }
            val getCode = getConn.responseCode
            return when {
                getCode == HttpURLConnection.HTTP_NOT_MODIFIED -> GithubFileFetchResult.NotModified
                getCode == HttpURLConnection.HTTP_NOT_FOUND -> {
                    getConn.drainErrorStream()
                    GithubFileFetchResult.NotFound
                }
                getCode in 200..299 -> {
                    val body = getConn.readBodyText()
                    val fileObj = JSONObject(body)
                    val remoteSha = fileObj.optString("sha", "").ifBlank { null }
                    val remoteSize = fileObj.optInt("size", 0)
                    var contentB64 = fileObj.optString("content", "")
                        .replace("\n", "")
                        .replace("\r", "")

                    // Для файлов > 1 МБ GitHub Contents API возвращает пустой content — читаем Git Blob до 100 МБ
                    if (contentB64.isBlank() && remoteSha != null) {
                        contentB64 = fetchBlobContent(remoteSha, token)
                    }

                    val decodedText = if (contentB64.isNotBlank()) {
                        String(Base64.decode(contentB64, Base64.DEFAULT), Charsets.UTF_8)
                    } else {
                        ""
                    }
                    GithubFileFetchResult.Loaded(
                        sha = remoteSha,
                        size = remoteSize,
                        etag = getConn.getHeaderField("ETag"),
                        decodedText = decodedText
                    )
                }
                else -> {
                    getConn.drainErrorStream()
                    throw IllegalStateException("$errorLabel HTTP $getCode")
                }
            }
        } finally {
            getConn.disconnect()
        }
    }

    private fun fetchRemoteConfigSnapshot(
        apiUrl: String,
        filePath: String,
        familyCode: String,
        token: String
    ): RemoteConfigSnapshot {
        val res = runCatching {
            fetchGithubFileContent(apiUrl, token, cachedConfigEtag, "GitHub Config Sync")
        }.onFailure {
            cachedConfigEtag = null
        }.getOrThrow()

        return when (res) {
            is GithubFileFetchResult.NotModified -> {
                RemoteConfigSnapshot(cachedConfigSha, cachedRemoteConfig, notModified = true)
            }
            is GithubFileFetchResult.NotFound -> {
                cachedConfigEtag = null
                val hasSyncedConfig = cachedConfigSha != null || prefs.hasSyncedConfigForFamily(familyCode)
                if (hasSyncedConfig) {
                    throw SyncGuardException(
                        "Sync guard: GET 404 for $filePath, but config was already synced — refusing to recreate cloud config"
                    )
                }
                RemoteConfigSnapshot(null, null, notModified = false)
            }
            is GithubFileFetchResult.Loaded -> {
                cachedConfigEtag = null
                val parsed = if (res.decodedText.isNotBlank()) {
                    FamilyConfig.fromJsonString(res.decodedText)
                } else {
                    null
                }
                if (res.size > 5 && parsed == null) {
                    throw SyncGuardException(
                        "Sync guard: remote config size ${res.size} could not be parsed"
                    )
                }
                cachedConfigEtag = res.etag
                cachedConfigSha = res.sha
                cachedRemoteConfig = parsed
                RemoteConfigSnapshot(res.sha, parsed, notModified = false)
            }
        }
    }

    private suspend fun fetchRemoteSnapshot(apiUrl: String, filePath: String, token: String): RemoteSnapshot {
        val res = runCatching {
            fetchGithubFileContent(apiUrl, token, cachedEtag, "GitHub Sync")
        }.onFailure {
            cachedEtag = null
        }.getOrThrow()

        return when (res) {
            is GithubFileFetchResult.NotModified -> {
                RemoteSnapshot(cachedRemoteSha, cachedRemoteMap, notModified = true)
            }
            is GithubFileFetchResult.NotFound -> {
                cachedEtag = null
                // 404 — это «новая пустая база» только для устройства, которое ещё ни разу не синхронизировалось.
                // Иначе (протухший токен, потеря доступа, переименование) нельзя перезаливать файл с нуля.
                val hasSyncedHistory = cachedRemoteSha != null || dao.countSyncedExpenses() > 0
                if (hasSyncedHistory) {
                    throw SyncGuardException(
                        "Sync guard: GET 404 for $filePath, but local DB already has synced records — refusing to recreate cloud file"
                    )
                }
                RemoteSnapshot(null, emptyMap(), notModified = false)
            }
            is GithubFileFetchResult.Loaded -> {
                cachedEtag = null
                val remoteMap = mutableMapOf<String, ExpenseEntity>()
                if (res.decodedText.isNotBlank()) {
                    val arr = JSONArray(res.decodedText)
                    for (i in 0 until arr.length()) {
                        val itemObj = arr.optJSONObject(i) ?: continue
                        val parsed = ExpenseEntity.fromJsonObject(itemObj) ?: continue
                        remoteMap[parsed.id] = parsed
                    }
                }

                // Защита от случайной перезаписи непустой облачной базы при сбое чтения
                if (res.size > 5 && remoteMap.isEmpty()) {
                    throw SyncGuardException("Sync guard: remote size ${res.size} decoded 0 items")
                }

                cachedEtag = res.etag
                cachedRemoteSha = res.sha
                cachedRemoteMap = remoteMap.toMap()
                RemoteSnapshot(res.sha, remoteMap, notModified = false)
            }
        }
    }

    private fun fetchBlobContent(remoteSha: String, token: String): String {
        val blobUrl = "https://api.github.com/repos/epokanevich/family-budget-sync-db/git/blobs/$remoteSha"
        val blobConn = openGithubConnection(blobUrl, token, "GET", readTimeoutMs = 30_000)
        try {
            val blobCode = blobConn.responseCode
            if (blobCode !in 200..299) {
                blobConn.drainErrorStream()
                throw IllegalStateException("GitHub Blob HTTP $blobCode")
            }
            val blobBody = blobConn.readBodyText()
            return JSONObject(blobBody)
                .optString("content", "")
                .replace("\n", "")
                .replace("\r", "")
        } finally {
            blobConn.disconnect()
        }
    }

    private fun putRemoteExpensesFile(
        apiUrl: String,
        token: String,
        familyCode: String,
        mergedMap: Map<String, ExpenseEntity>,
        registry: CategoryRegistry,
        remoteSha: String?,
        onSuccess: (newSha: String?) -> Unit
    ): Int {
        val sortedMerged = mergedMap.values.sortedByDescending { it.createdAtEpochMs }
        val jsonArray = JSONArray()
        for (item in sortedMerged) {
            jsonArray.put(item.toJsonObject(registry))
        }
        val rawBytes = jsonArray.toString().toByteArray(Charsets.UTF_8)
        return putRemoteFileBytes(
            apiUrl = apiUrl,
            token = token,
            commitMessage = "Sync family budget ($familyCode)",
            rawBytes = rawBytes,
            remoteSha = remoteSha,
            onSuccess = onSuccess
        )
    }

    /** Выполняет PUT произвольного файла в GitHub Contents API и возвращает HTTP-код; [onSuccess] вызывается при 200..299. */
    private fun putRemoteFileBytes(
        apiUrl: String,
        token: String,
        commitMessage: String,
        rawBytes: ByteArray,
        remoteSha: String?,
        onSuccess: (newSha: String?) -> Unit
    ): Int {
        val encodedB64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP)

        val putBody = JSONObject().apply {
            put("message", commitMessage)
            put("content", encodedB64)
            if (remoteSha != null) {
                put("sha", remoteSha)
            }
        }

        val putConn = openGithubConnection(apiUrl, token, "PUT", readTimeoutMs = 30_000).apply {
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            doOutput = true
        }
        try {
            OutputStreamWriter(putConn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(putBody.toString())
            }
            val putCode = putConn.responseCode
            if (putCode in 200..299) {
                val putRespText = putConn.readBodyText()
                val newSha = runCatching {
                    JSONObject(putRespText).optJSONObject("content")?.optString("sha", "")
                }.getOrNull()
                onSuccess(newSha)
            } else {
                putConn.drainErrorStream()
            }
            return putCode
        } finally {
            putConn.disconnect()
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

    private fun openGithubConnection(
        url: String,
        token: String,
        method: String,
        readTimeoutMs: Int
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        setRequestProperty("Authorization", "token $token")
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "FamilyBudgetZFold")
        connectTimeout = 15_000
        readTimeout = readTimeoutMs
    }

    private fun HttpURLConnection.readBodyText(): String =
        inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** Дочитывает и закрывает errorStream, чтобы не держать сокет при ответах 4xx/5xx. */
    private fun HttpURLConnection.drainErrorStream() {
        runCatching { errorStream?.use { it.readBytes() } }
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
                    Log.i(
                        "BudgetRepository",
                        "Duplicate push ignored: $signature $sourceType ($merchant) ~ ${duplicate.sourceType} (${duplicate.merchant})"
                    )
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
        internal const val FOREGROUND_POLL_INTERVAL_MS = 15_000L
        internal const val BACKGROUND_POLL_INTERVAL_MS = 180_000L

        /** Окно кросс-девайс дедупа общей карты (Mir Pay ↔ Банк или Банк ↔ Банк с двух телефонов): ±3 минуты. */
        internal const val CROSS_DEVICE_DEDUP_WINDOW_MS = 180_000L

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
        ): SyncMergePlan {
            val localMap = localList.associateBy { it.id }
            var needCloudPush = !remoteExists
            val mergedMap = mutableMapOf<String, ExpenseEntity>()
            val toUpsertLocally = mutableListOf<ExpenseEntity>()
            val pushedVersions = mutableMapOf<String, Long>()

            val allIds = (remoteMap.keys + localMap.keys).toSet()
            for (id in allIds) {
                val loc = localMap[id]
                val rem = remoteMap[id]
                when {
                    loc != null && rem == null -> {
                        mergedMap[id] = loc
                        needCloudPush = true
                        if (!loc.isSynced) pushedVersions[id] = loc.updatedAtEpochMs
                    }
                    loc == null && rem != null -> {
                        val syncedRem = rem.copy(isSynced = true)
                        mergedMap[id] = syncedRem
                        toUpsertLocally.add(syncedRem)
                    }
                    loc != null && rem != null -> {
                        val resolvedRecurring = resolveRecurringConflict(loc, rem)
                        if (resolvedRecurring != null) {
                            mergedMap[id] = resolvedRecurring
                            if (resolvedRecurring.updatedAtEpochMs > rem.updatedAtEpochMs || !resolvedRecurring.isSynced) {
                                needCloudPush = true
                                pushedVersions[id] = resolvedRecurring.updatedAtEpochMs
                            }
                            if (resolvedRecurring != loc) {
                                toUpsertLocally.add(resolvedRecurring)
                            }
                        } else if (loc.updatedAtEpochMs > rem.updatedAtEpochMs) {
                            mergedMap[id] = loc
                            needCloudPush = true
                            if (!loc.isSynced) pushedVersions[id] = loc.updatedAtEpochMs
                        } else if (rem.updatedAtEpochMs > loc.updatedAtEpochMs) {
                            val syncedRem = rem.copy(isSynced = true)
                            mergedMap[id] = syncedRem
                            toUpsertLocally.add(syncedRem)
                        } else {
                            // Та же версия уже лежит в облаке — локально можно считать синхронизированной
                            val synced = loc.copy(isSynced = true)
                            mergedMap[id] = synced
                            if (!loc.isSynced) {
                                toUpsertLocally.add(synced)
                            }
                        }
                    }
                }
            }

            val dedupResult = deduplicateCrossDevicePushExpensesWithEnrichment(mergedMap, nowMs)
            val dedupedLosers = dedupResult.losers
            val changedByDedup = dedupResult.losers + dedupResult.enrichedWinners
            if (changedByDedup.isNotEmpty()) {
                val changedIds = changedByDedup.map { it.id }.toSet()
                toUpsertLocally.removeAll { it.id in changedIds }
                toUpsertLocally.addAll(changedByDedup)
                changedByDedup.forEach { pushedVersions[it.id] = it.updatedAtEpochMs }
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
