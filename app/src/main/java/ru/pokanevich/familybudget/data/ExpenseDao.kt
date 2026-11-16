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

    @Query("SELECT * FROM expenses WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE dedupSignature = :signature AND createdAtEpochMs > :minTimestampMs LIMIT 1")
    suspend fun findRecentDuplicate(signature: String, minTimestampMs: Long): ExpenseEntity?

    /**
     * Слияние по правилу Last-Write-Wins для одного UUID:
     * если запись пришла из облака со свежим updatedAtEpochMs — обновляем,
     * но никогда не затираем соседние записи.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(expense: ExpenseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(expenses: List<ExpenseEntity>)
}
