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

    companion object {
        /**
         * Уведомления старше 5 минут игнорируются как при переподключении сервиса, так и при доставке.
         */
        internal const val STALE_NOTIFICATION_MS = BankPushTimeValidator.MAX_ALLOWED_DRIFT_MS
        private val deduplicator = PushNotificationDeduplicator()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            FamilyPreferences(applicationContext).appendPushLog("Сервис перехвата пушей подключён системой")
            val now = System.currentTimeMillis()
            activeNotifications?.forEach { sbn ->
                if (sbn.postTime > 0L && now - sbn.postTime > STALE_NOTIFICATION_MS) {
                    return@forEach
                }
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
        // Групповые summary-уведомления Android дублируют текст последней операции с новым sbn.id
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val extras = notification.extras ?: return

        val prefs = FamilyPreferences(applicationContext)
        val enabledSources = prefs.enabledBankPushSources
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        if (!BankPushParser.isPackageEligible(packageName, title, enabledSources)) return

        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }
        val normalText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .orEmpty()

        val primaryText = (bigText ?: normalText).orEmpty()
        val combinedBody = when {
            primaryText.isNotBlank() -> {
                val withSub = listOfNotNull(primaryText, subText).distinct().joinToString(" ").trim()
                if (BankPushParser.parseNotification(packageName, title, primaryText, enabledSources) != null) {
                    primaryText
                } else {
                    withSub
                }
            }
            lines.isNotEmpty() -> lines.last()
            else -> subText.orEmpty()
        }

        val nowMs = System.currentTimeMillis()
        val operationTimeMs = BankPushParser.extractOperationTimestampMs(
            title = title,
            text = combinedBody,
            nowMs = nowMs
        )
        val timeCheck = BankPushTimeValidator.validate(
            nowMs = nowMs,
            postTimeMs = sbn.postTime,
            notificationWhenMs = notification.`when`,
            operationTimeMs = operationTimeMs
        )
        if (!timeCheck.isValid) {
            prefs.appendPushLog(
                "Пропуск (>5 мин, ${timeCheck.rejectReason}): $title ($packageName)".take(120)
            )
            return
        }

        val parsed = BankPushParser.parseNotification(
            packageName = packageName,
            title = title,
            text = combinedBody,
            enabledSources = enabledSources
        )

        if (parsed == null) {
            prefs.appendPushLog("Пропуск ($packageName): $title | $combinedBody".take(120))
            return
        }

        val slotKey = sbn.key ?: "$packageName:${sbn.tag.orEmpty()}:${sbn.id}"
        val contentFingerprint = PushNotificationDeduplicator.buildContentFingerprint(parsed)
        val isNewNotification = synchronized(deduplicator) {
            deduplicator.ensureLoaded(prefs.recentPushDedupKeys)
            val added = deduplicator.markIfNew(
                slotKey = slotKey,
                postTimeMs = sbn.postTime,
                contentFingerprint = contentFingerprint,
                nowMs = nowMs
            )
            if (added) {
                runCatching {
                    prefs.recentPushDedupKeys = deduplicator.exportPersistedLines()
                }
            }
            added
        }
        if (!isNewNotification) return

        prefs.appendPushLog("Поймано ${parsed.amountRub} ₽ (${parsed.merchant}) [$packageName]")

        val repository = BudgetRepository.getInstance(applicationContext)
        serviceScope.launch {
            repository.ingestBankNotification(
                amountRub = parsed.amountRub,
                merchant = parsed.merchant,
                sourceType = parsed.sourceType,
                rawText = parsed.rawText,
                suggestedOperationType = parsed.suggestedOperationType
            )
        }
    }
}
