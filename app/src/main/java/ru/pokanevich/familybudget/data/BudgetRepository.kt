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

    private val _syncState = MutableStateFlow(SyncState.OFFLINE_READY)
    val syncState: StateFlow<SyncState> = _syncState

    val confirmedExpenses: Flow<List<ExpenseEntity>> = dao.observeConfirmedExpenses()
    val pendingPushExpenses: Flow<List<ExpenseEntity>> = dao.observePendingPushExpenses()

    init {
        startRealtimeSync()
    }

    /**
     * Запускает двустороннюю облачную синхронизацию между двумя смартфонами
     * через приватный GitHub-репозиторий epokanevich/family-budget-sync-db.
     * Слияние выполняется по правилу Last-Write-Wins для каждого UUIDv4 без потери записей.
     */
    fun startRealtimeSync() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                syncWithCloudNow()
                delay(12_000L) // Автообновление каждые 12 секунд
            }
        }
    }

    suspend fun syncWithCloudNow() = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            runCatching {
                val token = prefs.githubSyncToken
                if (token.isBlank()) {
                    _syncState.value = SyncState.OFFLINE_READY
                    return@withLock
                }

                val familyCode = prefs.familySpaceCode.ifBlank { "POKANE-FAMILY" }
                    .replace(Regex("[^A-Za-z0-9_-]"), "_")
                val filePath = "families/$familyCode/expenses.json"
                val apiUrl = "https://api.github.com/repos/epokanevich/family-budget-sync-db/contents/$filePath"

                // 1. Загружаем текущее состояние из облака
                var remoteSha: String? = null
                val remoteMap = mutableMapOf<String, ExpenseEntity>()

                val getConn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "token $token")
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "FamilyBudgetZFold")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }

                val getCode = getConn.responseCode
                if (getCode in 200..299) {
                    val body = getConn.inputStream.bufferedReader().readText()
                    val fileObj = JSONObject(body)
                    remoteSha = fileObj.optString("sha", "").ifBlank { null }
                    val contentB64 = fileObj.optString("content", "").replace("\n", "").replace("\r", "")
                    if (contentB64.isNotBlank()) {
                        val decodedJson = String(Base64.decode(contentB64, Base64.DEFAULT), Charsets.UTF_8)
                        val arr = JSONArray(decodedJson)
                        for (i in 0 until arr.length()) {
                            val itemObj = arr.optJSONObject(i) ?: continue
                            val parsed = ExpenseEntity.fromJsonObject(itemObj) ?: continue
                            remoteMap[parsed.id] = parsed
                        }
                    }
                } else if (getCode != 404) {
                    throw IllegalStateException("GitHub Sync HTTP $getCode")
                }

                // 2. Сливаем локальные записи Room и облачные записи по UUIDv4
                val localList = dao.getAllExpenses()
                val localMap = localList.associateBy { it.id }

                var needCloudPush = remoteSha == null
                val mergedMap = mutableMapOf<String, ExpenseEntity>()

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
                            dao.upsert(syncedRem)
                        }
                        loc != null && rem != null -> {
                            if (loc.updatedAtEpochMs > rem.updatedAtEpochMs) {
                                mergedMap[id] = loc.copy(isSynced = true)
                                needCloudPush = true
                            } else if (rem.updatedAtEpochMs > loc.updatedAtEpochMs) {
                                val syncedRem = rem.copy(isSynced = true)
                                mergedMap[id] = syncedRem
                                dao.upsert(syncedRem)
                            } else {
                                mergedMap[id] = loc.copy(isSynced = true)
                                if (!loc.isSynced) {
                                    dao.upsert(loc.copy(isSynced = true))
                                }
                            }
                        }
                    }
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
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        doOutput = true
                    }

                    OutputStreamWriter(putConn.outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(putBody.toString())
                    }

                    val putCode = putConn.responseCode
                    if (putCode in 200..299) {
                        for (item in sortedMerged) {
                            dao.upsert(item.copy(isSynced = true))
                        }
                    } else {
                        throw IllegalStateException("GitHub PUT HTTP $putCode")
                    }
                }

                _syncState.value = SyncState.CLOUD_CONNECTED
            }.onFailure { err ->
                Log.w("BudgetRepository", "Cloud sync offline: ${err.message}")
                _syncState.value = SyncState.OFFLINE_READY
            }
        }
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
        val normalizedAmount = String.format("%.2f", amountRub)
        // Сигнатура по сумме для защиты от двойного списания (Mir Pay + Сбер за одну покупку)
        val signature = "AMT_$normalizedAmount"
        val duplicate = dao.findRecentDuplicate(signature, now - 90_000L)
        if (duplicate != null && !duplicate.isDeleted) {
            Log.i("BudgetRepository", "Duplicate push ignored within 90s: $signature ($merchant)")
            return
        }

        val guessedCategory = ExpenseCategory.guessFromMerchant(merchant)
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
