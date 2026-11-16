package ru.pokanevich.familybudget.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.pokanevich.familybudget.data.BudgetRepository

class BankPushListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName ?: return
        // Жёсткий фильтр: обрабатываем ТОЛЬКО Сбер и Mir Pay (Т-Банк полностью игнорируется)
        if (packageName !in BankPushParser.ALLOWED_PACKAGES) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        val parsed = BankPushParser.parseNotification(
            packageName = packageName,
            title = title,
            text = text
        ) ?: return

        val repository = BudgetRepository.getInstance(applicationContext)
        serviceScope.launch {
            repository.ingestBankNotification(
                amountRub = parsed.amountRub,
                merchant = parsed.merchant,
                sourceType = parsed.sourceType,
                rawText = parsed.rawText
            )
        }
    }
}
