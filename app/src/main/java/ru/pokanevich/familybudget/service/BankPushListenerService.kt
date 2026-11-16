package ru.pokanevich.familybudget.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.pokanevich.familybudget.data.BudgetRepository
import ru.pokanevich.familybudget.data.FamilyPreferences

class BankPushListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            FamilyPreferences(applicationContext).appendPushLog("Сервис перехвата пушей подключён системой")
            activeNotifications?.forEach { sbn ->
                processStatusBarNotification(sbn)
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        runCatching {
            requestRebind(ComponentName(this, BankPushListenerService::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn != null) {
            processStatusBarNotification(sbn)
        }
    }

    private fun processStatusBarNotification(sbn: StatusBarNotification) {
        if (sbn.isOngoing) return
        val packageName = sbn.packageName ?: return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        if (!BankPushParser.isPackageEligible(packageName, title)) return

        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }
        val normalText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" ") { it?.toString().orEmpty() }
            ?.takeIf { it.isNotBlank() }

        val combinedBody = listOfNotNull(bigText ?: normalText, subText, lines)
            .distinct()
            .joinToString(" ")
            .trim()

        val prefs = FamilyPreferences(applicationContext)
        val parsed = BankPushParser.parseNotification(
            packageName = packageName,
            title = title,
            text = combinedBody
        )

        if (parsed == null) {
            prefs.appendPushLog("Пропуск ($packageName): $title | $combinedBody".take(120))
            return
        }

        prefs.appendPushLog("Поймано ${parsed.amountRub} ₽ (${parsed.merchant}) [$packageName]")

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
