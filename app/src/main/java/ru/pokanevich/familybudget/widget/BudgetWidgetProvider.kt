package ru.pokanevich.familybudget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.pokanevich.familybudget.MainActivity
import ru.pokanevich.familybudget.R
import ru.pokanevich.familybudget.data.AppDatabase
import ru.pokanevich.familybudget.data.FamilyPreferences

class BudgetWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (appWidgetIds.isEmpty()) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        widgetScope.launch {
            try {
                updateWidgets(appContext, appWidgetManager, appWidgetIds)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to update widget", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        widgetScope.launch {
            try {
                updateWidgets(appContext, appWidgetManager, intArrayOf(appWidgetId))
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to resize widget", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BudgetWidgetProvider"
        private const val ACTION_OPEN_APP = "ru.pokanevich.familybudget.widget.OPEN_APP"
        private const val ACTION_OPEN_ADD_EXPENSE = "ru.pokanevich.familybudget.widget.OPEN_ADD_EXPENSE"
        private const val REQUEST_CODE_OPEN_APP = 2001
        private const val REQUEST_CODE_ADD_EXPENSE = 2002

        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun requestUpdate(context: Context) {
            val appContext = context.applicationContext
            val appWidgetManager = AppWidgetManager.getInstance(appContext)
            val componentName = ComponentName(appContext, BudgetWidgetProvider::class.java)
            val appWidgetIds = runCatching {
                appWidgetManager.getAppWidgetIds(componentName)
            }.getOrNull() ?: return
            if (appWidgetIds.isEmpty()) return

            widgetScope.launch {
                try {
                    updateWidgets(appContext, appWidgetManager, appWidgetIds)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to refresh widget", t)
                }
            }
        }

        private suspend fun updateWidgets(
            appContext: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val db = AppDatabase.getInstance(appContext)
            val prefs = FamilyPreferences(appContext)
            val config = prefs.loadFamilyConfig()
            val expenses = db.expenseDao().getAllExpenses()
            val recurring = db.recurringPaymentDao().getAll()
            val goals = db.savingsGoalDao().getAll()

            val snapshot = WidgetSnapshotBuilder.build(
                expenses = expenses,
                monthlyBudgetRub = config.monthlyBudgetRub,
                registry = config.toRegistry(),
                recurringPayments = recurring,
                savingsGoals = goals,
                // Флаг приватности действует только при включённой защите PIN-кодом
                hideAmountsOnWidget = prefs.isAppLockEnabled && prefs.hideAmountsOnWidget
            )

            for (appWidgetId in appWidgetIds) {
                val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
                val views = buildRemoteViews(appContext, snapshot, options)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        private fun buildRemoteViews(
            context: Context,
            snapshot: WidgetSnapshot,
            options: Bundle
        ): RemoteViews {
            val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 60)
            val isWide = minWidthDp >= 220
            val isTall = minHeightDp >= 110
            val isShort = minHeightDp < 72

            val views = RemoteViews(context.packageName, R.layout.widget_budget)

            // Низкая ячейка 2x1 / 4x1 (Cover Screen Z Fold): уменьшаем вертикальные отступы, чтобы не обрезать текст
            val density = context.resources.displayMetrics.density
            val horizontalPaddingPx = (12 * density).toInt()
            val verticalPaddingPx = ((if (isShort) 4 else 12) * density).toInt()
            views.setViewPadding(
                R.id.widget_root,
                horizontalPaddingPx,
                verticalPaddingPx,
                horizontalPaddingPx,
                verticalPaddingPx
            )

            views.setViewVisibility(R.id.widget_header_row, if (isTall) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_daily_label, if (isWide || isTall) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_spent_compact, if (isTall) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_expanded_section, if (isTall) View.VISIBLE else View.GONE)

            views.setTextViewText(R.id.widget_safe_daily, snapshot.safeDailyText)
            views.setTextViewText(
                R.id.widget_spent_compact,
                if (isWide) "Потрачено за месяц: ${snapshot.spentText}" else "Потрачено: ${snapshot.spentText}"
            )
            views.setTextViewText(
                R.id.widget_spent_expanded,
                "Потрачено за месяц: ${snapshot.budgetProgressText}"
            )
            views.setProgressBar(R.id.widget_progress, 100, snapshot.spentProgressPercent, false)
            views.setTextViewText(R.id.widget_days_left, "Осталось ${snapshot.daysLeftInMonth} дн.")

            if (snapshot.upcomingRecurringReserveRub > 0.0) {
                views.setViewVisibility(R.id.widget_reserve_text, View.VISIBLE)
                views.setTextViewText(R.id.widget_reserve_text, "Резерв платежей: ${snapshot.reserveText}")
            } else {
                views.setViewVisibility(R.id.widget_reserve_text, View.GONE)
            }

            val badgeCount = snapshot.pendingBadgeText
            if (badgeCount != null) {
                views.setViewVisibility(R.id.widget_pending_badge, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_pending_badge,
                    if (isWide || isTall) "$badgeCount пуш." else badgeCount
                )
            } else {
                views.setViewVisibility(R.id.widget_pending_badge, View.GONE)
            }

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_APP
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_CODE_OPEN_APP,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val addExpenseIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_ADD_EXPENSE
                putExtra(MainActivity.EXTRA_OPEN_ADD_SHEET, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val addExpensePendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_CODE_ADD_EXPENSE,
                addExpenseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_add_button, addExpensePendingIntent)

            return views
        }
    }
}
