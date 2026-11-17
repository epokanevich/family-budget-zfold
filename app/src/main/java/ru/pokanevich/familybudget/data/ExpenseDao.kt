package ru.pokanevich.familybudget.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {

    @Query("SELECT * FROM expenses WHERE isDeleted = 0 AND isPendingConfirmation = 0 ORDER BY createdAtEpochMs DESC")
    fun observeConfirmedExpenses(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE isDeleted = 0 AND isPendingConfirmation = 1 ORDER BY createdAtEpochMs DESC")
    fun observePendingPushExpenses(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses ORDER BY createdAtEpochMs DESC")
    suspend fun getAllExpenses(): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE isSynced = 0")
    suspend fun getUnsyncedExpenses(): List<ExpenseEntity>

    @Query("SELECT COUNT(*) FROM expenses WHERE isSynced = 1")
    suspend fun countSyncedExpenses(): Int

    /**
     * Атомарно помечает запись синхронизированной, только если она не менялась локально
     * с момента отправки в облако (иначе свежая правка останется isSynced = 0 и уйдёт следующим циклом).
     */
    @Query("UPDATE expenses SET isSynced = 1 WHERE id = :id AND updatedAtEpochMs = :updatedAtEpochMs AND isSynced = 0")
    suspend fun markSyncedIfUnchanged(id: String, updatedAtEpochMs: Long): Int

    @Query("SELECT * FROM expenses WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ExpenseEntity?

    /**
     * Кандидаты в дубли пуша на этом устройстве: та же сумма (dedupSignature = AMT_...) в окне времени.
     * Окончательное решение (sourceType, нормализованный мерчант, текст пуша) принимает
     * [BudgetRepository.findSameDeviceDuplicate], т.к. транслитерацию мерчанта нельзя выразить в SQL.
     */
    @Query(
        "SELECT * FROM expenses WHERE dedupSignature = :signature AND createdAtEpochMs > :minTimestampMs " +
            "AND isDeleted = 0 ORDER BY createdAtEpochMs DESC"
    )
    suspend fun findRecentDuplicateCandidates(signature: String, minTimestampMs: Long): List<ExpenseEntity>

    /**
     * Слияние по правилу Last-Write-Wins для одного UUID:
     * если запись пришла из облака со свежим updatedAtEpochMs — обновляем,
     * но никогда не затираем соседние записи.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(expense: ExpenseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(expenses: List<ExpenseEntity>)

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()
}
