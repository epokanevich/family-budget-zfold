package ru.pokanevich.familybudget.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringPaymentDao {

    @Query("SELECT * FROM recurring_payments WHERE isDeleted = 0 ORDER BY dayOfMonth ASC, title ASC")
    fun observeRecurringPayments(): Flow<List<RecurringPaymentEntity>>

    @Query("SELECT * FROM recurring_payments ORDER BY dayOfMonth ASC, title ASC")
    suspend fun getAll(): List<RecurringPaymentEntity>

    @Query("SELECT * FROM recurring_payments WHERE isDeleted = 0 AND isActive = 1")
    suspend fun getActive(): List<RecurringPaymentEntity>

    @Query("SELECT * FROM recurring_payments WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RecurringPaymentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(payment: RecurringPaymentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(payments: List<RecurringPaymentEntity>)
}
