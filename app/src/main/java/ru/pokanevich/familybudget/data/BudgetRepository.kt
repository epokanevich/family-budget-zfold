package ru.pokanevich.familybudget.data

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

enum class SyncState(val label: String) {
    OFFLINE_READY("Локальная БД активна (офлайн)"),
    SYNCING("Синхронизация с облаком..."),
    CLOUD_CONNECTED("Облако синхронизировано (2 устройства)")
}

class BudgetRepository private constructor(context: Context) {

    private val dao = AppDatabase.getInstance(context).expenseDao()
    val prefs = FamilyPreferences(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private var pollingJob: Job? = null

    private var cachedFamilyCode: String? = null
    private var cachedEtag: String? = null
    private var cachedRemoteSha: String? = null
    private var cachedRemoteMap: Map<String, ExpenseEntity> = emptyMap()

    private val _syncState = MutableStateFlow(SyncState.OFFLINE_READY)
    val syncState: StateFlow<SyncState> = _syncState

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    val confirmedExpenses: Flow<List<ExpenseEntity>> = dao.observeConfirmedExpenses()
    val pendingPushExpenses: Flow<List<ExpenseEntity>> = dao.observePendingPushExpenses()

    init {
        startRealtimeSync()
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
                delay(15_000L)
            }
        }
    }

    suspend fun syncWithCloudNow() = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            _isSyncing.value = true
            try {
                runCatching {
                    val token = prefs.githubSyncToken
                    if (token.isBlank()) {
                        _syncState.value = SyncState.OFFLINE_READY
                        return@withLock
                    }

                    val familyCode = prefs.familySpaceCode.ifBlank { "POKANE-FAMILY" }
                        .replace(Regex("[^A-Za-z0-9_-]"), "_")

                    if (cachedFamilyCode != familyCode) {
                        cachedFamilyCode = familyCode
                        cachedEtag = null
                        cachedRemoteSha = null
                        cachedRemoteMap = emptyMap()
                    }

                    val filePath = "families/$familyCode/budget_v3.json"
                    val apiUrl = "https://api.github.com/repos/epokanevich/family-budget-sync-db/contents/$filePath"

                    for (attempt in 0..1) {
                        var remoteSha: String? = null
                        val remoteMap = mutableMapOf<String, ExpenseEntity>()

                        val getConn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Authorization", "token $token")
                            setRequestProperty("Accept", "application/vnd.github+json")
                            setRequestProperty("User-Agent", "FamilyBudgetZFold")
                            if (!cachedEtag.isNullOrBlank()) {
                                setRequestProperty("If-None-Match", cachedEtag)
                            }
                            connectTimeout = 15_000
                            readTimeout = 25_000
                        }

                        val getCode = getConn.responseCode
                        if (getCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                            remoteSha = cachedRemoteSha
                            remoteMap.putAll(cachedRemoteMap)

                            // Если локально ничего не изменилось и облако не менялось — завершаем мгновенно
                            val unsynced = dao.getUnsyncedExpenses()
                            if (unsynced.isEmpty()) {
                                _syncState.value = SyncState.CLOUD_CONNECTED
                                return@withLock
                            }
                        } else if (getCode in 200..299) {
                            val body = getConn.inputStream.bufferedReader().readText()
                            val fileObj = JSONObject(body)
                            remoteSha = fileObj.optString("sha", "").ifBlank { null }
                            val remoteSize = fileObj.optInt("size", 0)
                            var contentB64 = fileObj.optString("content", "")
                                .replace("\n", "")
                                .replace("\r", "")

                            // Для файлов > 1 МБ GitHub Contents API возвращает пустой content — читаем Git Blob до 100 МБ
                            if (contentB64.isBlank() && remoteSha != null) {
                                val blobUrl = "https://api.github.com/repos/epokanevich/family-budget-sync-db/git/blobs/$remoteSha"
                                val blobConn = (URL(blobUrl).openConnection() as HttpURLConnection).apply {
                                    requestMethod = "GET"
                                    setRequestProperty("Authorization", "token $token")
                                    setRequestProperty("Accept", "application/vnd.github+json")
                                    setRequestProperty("User-Agent", "FamilyBudgetZFold")
                                    connectTimeout = 15_000
                                    readTimeout = 30_000
                                }
                                if (blobConn.responseCode in 200..299) {
                                    val blobBody = blobConn.inputStream.bufferedReader().readText()
                                    contentB64 = JSONObject(blobBody)
                                        .optString("content", "")
                                        .replace("\n", "")
                                        .replace("\r", "")
                                } else {
                                    throw IllegalStateException("GitHub Blob HTTP ${blobConn.responseCode}")
                                }
                            }

                            if (contentB64.isNotBlank()) {
                                val decodedJson = String(Base64.decode(contentB64, Base64.DEFAULT), Charsets.UTF_8)
                                val arr = JSONArray(decodedJson)
                                for (i in 0 until arr.length()) {
                                    val itemObj = arr.optJSONObject(i) ?: continue
                                    val parsed = ExpenseEntity.fromJsonObject(itemObj) ?: continue
                                    remoteMap[parsed.id] = parsed
                                }
                            }

                            // Защита от случайной перезаписи непустой облачной базы при сбое чтения
                            if (remoteSize > 5 && remoteMap.isEmpty()) {
                                throw IllegalStateException("Sync guard: remote size $remoteSize decoded 0 items")
                            }

                            cachedEtag = getConn.getHeaderField("ETag")
                            cachedRemoteSha = remoteSha
                            cachedRemoteMap = remoteMap.toMap()
                        } else if (getCode != 404) {
                            throw IllegalStateException("GitHub Sync HTTP $getCode")
                        }

                        // 2. Сливаем локальные записи Room и облачные записи по UUIDv4 (Last-Write-Wins)
                        val localList = dao.getAllExpenses()
                        val localMap = localList.associateBy { it.id }

                        var needCloudPush = remoteSha == null
                        val mergedMap = mutableMapOf<String, ExpenseEntity>()
                        val toUpsertLocally = mutableListOf<ExpenseEntity>()

                        val allIds = (remoteMap.keys + localMap.keys).toSet()
                        for (id in allIds) {
                            val loc = localMap[id]
                            val rem = remoteMap[id]
                            when {
                                loc != null && rem == null -> {
                                    mergedMap[id] = loc.copy(isSynced = true)
                                    needCloudPush = true
                                }
                                loc == null && rem != null -> {
                                    val syncedRem = rem.copy(isSynced = true)
                                    mergedMap[id] = syncedRem
                                    toUpsertLocally.add(syncedRem)
                                }
                                loc != null && rem != null -> {
                                    if (loc.updatedAtEpochMs > rem.updatedAtEpochMs) {
                                        mergedMap[id] = loc.copy(isSynced = true)
                                        needCloudPush = true
                                    } else if (rem.updatedAtEpochMs > loc.updatedAtEpochMs) {
                                        val syncedRem = rem.copy(isSynced = true)
                                        mergedMap[id] = syncedRem
                                        toUpsertLocally.add(syncedRem)
                                    } else {
                                        val synced = loc.copy(isSynced = true)
                                        mergedMap[id] = synced
                                        if (!loc.isSynced) {
                                            toUpsertLocally.add(synced)
                                        }
                                    }
                                }
                            }
                        }

                        if (toUpsertLocally.isNotEmpty()) {
                            dao.upsertAll(toUpsertLocally)
                        }

                        // 3. Если локально появились новые или обновлённые траты — сохраняем объединённый список в облако
                        if (needCloudPush) {
                            val sortedMerged = mergedMap.values.sortedByDescending { it.createdAtEpochMs }
                            val jsonArray = JSONArray()
                            for (item in sortedMerged) {
                                jsonArray.put(item.toJsonObject())
                            }
                            val rawBytes = jsonArray.toString().toByteArray(Charsets.UTF_8)
                            val encodedB64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP)

                            val putBody = JSONObject().apply {
                                put("message", "Sync family budget ($familyCode)")
                                put("content", encodedB64)
                                if (remoteSha != null) {
                                    put("sha", remoteSha)
                                }
                            }

                            val putConn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                                requestMethod = "PUT"
                                setRequestProperty("Authorization", "token $token")
                                setRequestProperty("Accept", "application/vnd.github+json")
                                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                                setRequestProperty("User-Agent", "FamilyBudgetZFold")
                                connectTimeout = 15_000
                                readTimeout = 30_000
                                doOutput = true
                            }

                            OutputStreamWriter(putConn.outputStream, Charsets.UTF_8).use { writer ->
                                writer.write(putBody.toString())
                            }

                            val putCode = putConn.responseCode
                            if (putCode in 200..299) {
                                val putRespText = putConn.inputStream.bufferedReader().readText()
                                val newSha = JSONObject(putRespText).optJSONObject("content")?.optString("sha", "")
                                cachedRemoteSha = newSha?.ifBlank { remoteSha } ?: remoteSha
                                cachedEtag = null
                                cachedRemoteMap = mergedMap.toMap()

                                val newlySynced = localList.filter { !it.isSynced }.map { it.copy(isSynced = true) }
                                if (newlySynced.isNotEmpty()) {
                                    dao.upsertAll(newlySynced)
                                }
                                break
                            } else if (putCode == HttpURLConnection.HTTP_CONFLICT) {
                                cachedEtag = null
                                cachedRemoteSha = null
                                if (attempt == 0) {
                                    Log.w("BudgetRepository", "GitHub PUT HTTP 409 conflict, retrying sync cycle immediately")
                                    continue
                                }
                                throw IllegalStateException("GitHub PUT HTTP $putCode")
                            } else {
                                cachedEtag = null
                                throw IllegalStateException("GitHub PUT HTTP $putCode")
                            }
                        } else {
                            break
                        }
                    }

                    _syncState.value = SyncState.CLOUD_CONNECTED
                }.onFailure { err ->
                    Log.w("BudgetRepository", "Cloud sync offline: ${err.message}")
                    _syncState.value = SyncState.OFFLINE_READY
                }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    suspend fun importExpensesBatch(items: List<ExpenseEntity>): Int = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext 0
        val existingIds = dao.getAllExpenses().map { it.id }.toSet()
        val newItems = items.filter { it.id !in existingIds }.map { it.copy(isSynced = false) }
        if (newItems.isNotEmpty()) {
            dao.upsertAll(newItems)
            scope.launch { syncWithCloudNow() }
        }
        newItems.size
    }

    suspend fun addOrUpdateExpense(expense: ExpenseEntity) {
        val now = System.currentTimeMillis()
        val toSave = expense.copy(
            updatedAtEpochMs = now,
            isSynced = false
        )
        dao.upsert(toSave)
        scope.launch { syncWithCloudNow() }
    }

    suspend fun confirmPendingExpense(expense: ExpenseEntity, chosenCategory: ExpenseCategory) {
        val updated = expense.copy(
            categoryName = chosenCategory.name,
            isPendingConfirmation = false,
            updatedAtEpochMs = System.currentTimeMillis(),
            isSynced = false
        )
        dao.upsert(updated)
        scope.launch { syncWithCloudNow() }
    }

    suspend fun softDeleteExpense(expense: ExpenseEntity) {
        val deleted = expense.copy(
            isDeleted = true,
            updatedAtEpochMs = System.currentTimeMillis(),
            isSynced = false
        )
        dao.upsert(deleted)
        scope.launch { syncWithCloudNow() }
    }

    suspend fun restoreExpense(expense: ExpenseEntity) {
        val restored = expense.copy(
            isDeleted = false,
            updatedAtEpochMs = System.currentTimeMillis(),
            isSynced = false
        )
        dao.upsert(restored)
        scope.launch { syncWithCloudNow() }
    }

    /**
     * Вызывается из BankPushListenerService при получении пуша от СберБанка или Mir Pay.
     * Включает защиту от дубликатов (если Сбер и Mir Pay прислали пуш об одной и той же покупке в течение 90 секунд).
     */
    suspend fun ingestBankNotification(
        amountRub: Double,
        merchant: String,
        sourceType: String,
        rawText: String
    ) {
        val now = System.currentTimeMillis()
        val normalizedAmount = String.format(java.util.Locale.US, "%.2f", amountRub)
        val signature = "AMT_$normalizedAmount"
        val duplicate = dao.findRecentDuplicate(signature, now - 90_000L)
        if (duplicate != null && !duplicate.isDeleted) {
            Log.i("BudgetRepository", "Duplicate push ignored within 90s: $signature ($merchant)")
            return
        }

        val guessedCategory = ExpenseCategory.guessFromMerchant(merchant, isIncome = false)
        val expense = ExpenseEntity(
            amountRub = amountRub,
            merchant = merchant.ifBlank { "Покупка по карте" },
            categoryName = guessedCategory.name,
            authorName = prefs.currentUserName,
            sourceType = sourceType,
            isPendingConfirmation = !prefs.autoConfirmBankPush,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            rawPushOrQrText = rawText,
            dedupSignature = signature
        )
        dao.upsert(expense)
        scope.launch { syncWithCloudNow() }
    }

    companion object {
        @Volatile
        private var INSTANCE: BudgetRepository? = null

        fun getInstance(context: Context): BudgetRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BudgetRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
