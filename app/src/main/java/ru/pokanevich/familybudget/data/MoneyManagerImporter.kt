package ru.pokanevich.familybudget.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.SequenceInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

data class MmImportResult(
    val parsedTotal: Int,
    val newlyAdded: Int,
    val expenseCount: Int,
    val incomeCount: Int
)

object MoneyManagerImporter {

    private const val MAX_EXTRACT_BYTES = 100L * 1024L * 1024L

    suspend fun importFromBackupUri(
        context: Context,
        uri: Uri,
        defaultAuthorName: String,
        repository: BudgetRepository
    ): Result<MmImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val tempDb = File.createTempFile("mm_import_", ".db", context.cacheDir)
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Не удалось открыть выбранный файл")

                input.use { rawStream ->
                    // Читаем первые 64 байта в цикле, чтобы пропустить заголовок (8x 0x08) до сигнатуры ZIP (PK\x03\x04)
                    val headerBuf = ByteArray(64)
                    var headerLen = 0
                    while (headerLen < headerBuf.size) {
                        val read = rawStream.read(headerBuf, headerLen, headerBuf.size - headerLen)
                        if (read <= 0) break
                        headerLen += read
                    }
                    if (headerLen < 4) throw IllegalStateException("Файл повреждён или пуст")

                    var zipOffset = -1
                    for (i in 0..(headerLen - 4)) {
                        if (headerBuf[i] == 0x50.toByte() &&
                            headerBuf[i + 1] == 0x4B.toByte() &&
                            headerBuf[i + 2] == 0x03.toByte() &&
                            headerBuf[i + 3] == 0x04.toByte()
                        ) {
                            zipOffset = i
                            break
                        }
                    }
                    if (zipOffset < 0) throw IllegalStateException("Архив .mmbackup не содержит ZIP-базы")

                    val tailHeader = ByteArrayInputStream(headerBuf, zipOffset, headerLen - zipOffset)
                    val zipStream = ZipInputStream(SequenceInputStream(tailHeader, rawStream))

                    var dbExtracted = false
                    zipStream.use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (entry.name.endsWith(".db", ignoreCase = true)) {
                                FileOutputStream(tempDb).use { out ->
                                    val buffer = ByteArray(8192)
                                    var totalBytes = 0L
                                    while (true) {
                                        val readCount = zis.read(buffer)
                                        if (readCount <= 0) break
                                        totalBytes += readCount
                                        if (totalBytes > MAX_EXTRACT_BYTES) {
                                            throw IllegalStateException("Размер базы внутри архива превышает допустимый лимит (100 МБ)")
                                        }
                                        out.write(buffer, 0, readCount)
                                    }
                                }
                                dbExtracted = true
                                break
                            }
                            entry = zis.nextEntry
                        }
                    }
                    if (!dbExtracted) throw IllegalStateException("Внутри архива не найдена база MyFinance.db")
                }

                val entities = mutableListOf<ExpenseEntity>()
                var expCount = 0
                var incCount = 0

                val db = SQLiteDatabase.openDatabase(tempDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                db.use { sqlite ->
                    val sql = """
                        SELECT t.uid, t.type, t.amountInDefaultCurrency, t.date, t.created, t.comment, sl.otherUid AS catUid
                        FROM [transaction] t
                        LEFT JOIN sync_link sl
                          ON sl.entityType='Transaction' AND sl.entityUid=t.uid AND sl.otherType='Category' AND sl.isRemoved=0
                        WHERE t.isRemoved=0
                    """.trimIndent()

                    val isoUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val isoUtcShort = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val ymdFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

                    sqlite.rawQuery(sql, null).use { cursor ->
                        val idxUid = cursor.getColumnIndexOrThrow("uid")
                        val idxType = cursor.getColumnIndexOrThrow("type")
                        val idxAmt = cursor.getColumnIndexOrThrow("amountInDefaultCurrency")
                        val idxDate = cursor.getColumnIndexOrThrow("date")
                        val idxCreated = cursor.getColumnIndexOrThrow("created")
                        val idxComment = cursor.getColumnIndexOrThrow("comment")
                        val idxCatUid = cursor.getColumnIndexOrThrow("catUid")

                        while (cursor.moveToNext()) {
                            val uid = cursor.getString(idxUid) ?: continue
                            val type = cursor.getString(idxType) ?: "Expense"
                            val amtKopecks = cursor.getLong(idxAmt)
                            if (amtKopecks <= 0L) continue
                            val amtRub = amtKopecks / 100.0

                            val dateStr = cursor.getString(idxDate).orEmpty()
                            val createdStr = cursor.getString(idxCreated).orEmpty()
                            val comment = cursor.getString(idxComment)?.trim().orEmpty()
                            val catUid = cursor.getString(idxCatUid)

                            val isIncome = type.equals("Income", ignoreCase = true)
                            val cat = ExpenseCategory.fromMoneyManagerUid(catUid, isIncome)
                            if (isIncome) incCount++ else expCount++

                            val epochMs = resolveEpochMs(dateStr, createdStr, isoUtc, isoUtcShort, ymdFormat)

                            entities.add(
                                ExpenseEntity(
                                    id = uid,
                                    amountRub = amtRub,
                                    merchant = comment.ifBlank { cat.displayName },
                                    categoryName = cat.name,
                                    authorName = defaultAuthorName,
                                    sourceType = "IMPORT_MM",
                                    isPendingConfirmation = false,
                                    isDeleted = false,
                                    isSynced = false,
                                    createdAtEpochMs = epochMs,
                                    updatedAtEpochMs = epochMs,
                                    rawPushOrQrText = "",
                                    dedupSignature = ""
                                )
                            )
                        }
                    }
                }

                val newlyAdded = repository.importExpensesBatch(entities)
                MmImportResult(
                    parsedTotal = entities.size,
                    newlyAdded = newlyAdded,
                    expenseCount = expCount,
                    incomeCount = incCount
                )
            } finally {
                tempDb.delete()
            }
        }
    }

    private fun resolveEpochMs(
        dateStr: String,
        createdStr: String,
        isoUtc: SimpleDateFormat,
        isoUtcShort: SimpleDateFormat,
        ymdFormat: SimpleDateFormat
    ): Long {
        val createdTime = runCatching { isoUtc.parse(createdStr)?.time }.getOrNull()
            ?: runCatching { isoUtcShort.parse(createdStr)?.time }.getOrNull()
        val dateOnly = runCatching { ymdFormat.parse(dateStr)?.time }.getOrNull()

        if (createdTime != null && dateOnly != null) {
            val c1 = Calendar.getInstance().apply { timeInMillis = createdTime }
            val c2 = Calendar.getInstance().apply { timeInMillis = dateOnly }
            if (c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
                c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
            ) {
                return createdTime
            }
            // Берём дату из поля date, а время суток сохраняем из created
            c2.set(Calendar.HOUR_OF_DAY, c1.get(Calendar.HOUR_OF_DAY))
            c2.set(Calendar.MINUTE, c1.get(Calendar.MINUTE))
            c2.set(Calendar.SECOND, c1.get(Calendar.SECOND))
            return c2.timeInMillis
        }

        if (dateOnly != null) {
            val c = Calendar.getInstance().apply {
                timeInMillis = dateOnly
                set(Calendar.HOUR_OF_DAY, 12)
            }
            return c.timeInMillis
        }

        return createdTime ?: System.currentTimeMillis()
    }
}
