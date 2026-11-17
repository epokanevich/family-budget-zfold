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
         * Уведомления из шторки старше этого порога при переподключении сервиса не обрабатываются.
         * Должно быть <= BudgetRepository.SAME_DEVICE_DEDUP_WINDOW_MS (120 сек), чтобы повторно
         * обработанный пуш гарантированно попал в окно дедупликации БД.
         */
        internal const val STALE_NOTIFICATION_MS = 90_000L
        private const val MAX_PROCESSED_KEYS = 500
        private val processedNotificationKeys = LinkedHashSet<String>()
        private var persistedKeysLoaded = false

        /**
         * Возвращает true, если уведомление с таким dedupKey ещё не обрабатывалось.
         * Последние 50 ключей сохраняются в SharedPreferences, поэтому перезапуск процесса
         * (и повторная выдача activeNotifications в onListenerConnected) не сбрасывает защиту.
         */
        private fun markNotificationProcessedIfNew(prefs: FamilyPreferences, dedupKey: String): Boolean {
            synchronized(processedNotificationKeys) {
                if (!persistedKeysLoaded) {
                    persistedKeysLoaded = true
                    runCatching { processedNotificationKeys.addAll(prefs.recentPushDedupKeys) }
                }
                if (!processedNotificationKeys.add(dedupKey)) {
                    return false
                }
                while (processedNotificationKeys.size > MAX_PROCESSED_KEYS) {
                    val iterator = processedNotificationKeys.iterator()
                    if (!iterator.hasNext()) break
                    iterator.next()
                    iterator.remove()
                }
                runCatching {
                    prefs.recentPushDedupKeys =
                        processedNotificationKeys.toList().takeLast(FamilyPreferences.MAX_PERSISTED_PUSH_DEDUP_KEYS)
                }
                return true
            }
        }
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
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        if (!BankPushParser.isPackageEligible(packageName, title)) return

        val prefs = FamilyPreferences(applicationContext)
        val dedupKey = "${sbn.key ?: "$packageName:${sbn.id}"}|${sbn.postTime}"
        if (!markNotificationProcessedIfNew(prefs, dedupKey)) return

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
