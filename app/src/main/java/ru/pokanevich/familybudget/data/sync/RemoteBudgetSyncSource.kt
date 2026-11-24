package ru.pokanevich.familybudget.data.sync

import ru.pokanevich.familybudget.data.CategoryRegistry
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.FamilyConfig
import ru.pokanevich.familybudget.data.RecurringPaymentEntity
import ru.pokanevich.familybudget.data.SavingsGoalEntity

data class ConfigSyncOutcome(
    val remoteConfig: FamilyConfig?,
    val remoteExists: Boolean,
    val mergedConfig: FamilyConfig,
    val needsLocalUpdate: Boolean,
    val pushedToCloud: Boolean
)

data class RemoteDeltaPayload(
    val remoteExists: Boolean,
    val isFullSnapshot: Boolean,
    val expensesDelta: Map<String, ExpenseEntity>,
    val recurringDelta: List<RecurringPaymentEntity>,
    val goalsDelta: List<SavingsGoalEntity>,
    val maxObservedWatermarkMs: Long
)

interface RemoteBudgetSyncSource {
    val isConfigured: Boolean

    suspend fun syncConfig(
        contour: SyncContour,
        spaceKey: String,
        localConfig: FamilyConfig,
        hasUnsyncedLocal: Boolean
    ): ConfigSyncOutcome

    suspend fun pullChanges(
        contour: SyncContour,
        spaceKey: String,
        sinceWatermarkMs: Long
    ): RemoteDeltaPayload

    suspend fun pushChanges(
        contour: SyncContour,
        spaceKey: String,
        expenses: List<ExpenseEntity>,
        recurring: List<RecurringPaymentEntity>,
        goals: List<SavingsGoalEntity>,
        registry: CategoryRegistry
    )

    fun observeRealtimeChanges(
        contour: SyncContour,
        spaceKey: String,
        onRemoteChanged: () -> Unit
    ): AutoCloseable
}
