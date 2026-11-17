package ru.pokanevich.familybudget.data

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Локальное хранилище настроек PIN-кода и биометрии (только для данного устройства).
 * Вынесено в интерфейс для изоляции в unit-тестах на JVM без Android-контекста.
 */
interface PinLockStorage {
    var isAppLockEnabled: Boolean
    var isBiometricEnabled: Boolean
    var pinSaltHex: String
    var pinHashHex: String
    var pinAutoLockTimeoutMs: Long
    fun clearPinCredentials()
}

/**
 * Менеджер локальной защиты приложения по 4-значному PIN-коду и биометрии.
 *
 * Ключевые инварианты:
 * - PIN-код никогда не хранится в открытом виде: сохраняется только 128-битная случайная соль
 *   и SHA-256 хэш (сравнение выполняется через constant-time [MessageDigest.isEqual]).
 * - Настройки PIN и биометрии строго локальны и не попадают в облачную синхронизацию.
 * - Отслеживает жизненный цикл процесса (`ON_STOP` / `ON_START` от `ProcessLifecycleOwner`),
 *   поэтому срабатывает при холодном старте и реальном уходе в фон дольше таймаута, но НЕ
 *   сбрасывает разблокировку при складывании/раскрытии экрана Samsung Galaxy Z Fold.
 * - Фоновые сервисы (`BankPushListenerService`) и `BudgetRepository` не блокируются PIN-экраном.
 */
class PinSecurityManager internal constructor(
    private val storage: PinLockStorage,
    private val clockMs: () -> Long,
    private val random: SecureRandom = SecureRandom()
) {
    private val stateLock = Any()
    private val processObserverAttached = AtomicBoolean(false)
    private var backgroundedAtMs: Long? = null
    private var lastAutoPromptedSessionId: Long = -1L

    private val _isAppLockEnabled = MutableStateFlow(false)
    val isAppLockEnabled: StateFlow<Boolean> = _isAppLockEnabled

    private val _isBiometricEnabled = MutableStateFlow(false)
    val isBiometricEnabled: StateFlow<Boolean> = _isBiometricEnabled

    private val _autoLockTimeoutMs = MutableStateFlow(storage.pinAutoLockTimeoutMs.coerceAtLeast(0L))
    val autoLockTimeoutMs: StateFlow<Long> = _autoLockTimeoutMs

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked

    private val _lockSessionId = MutableStateFlow(0L)
    val lockSessionId: StateFlow<Long> = _lockSessionId

    init {
        synchronized(stateLock) {
            val validPin = hasValidStoredPinLocked()
            if (storage.isAppLockEnabled && !validPin) {
                // Самовосстановление: если флаг включён без валидной соли/хэша, не блокируем вход навсегда
                storage.clearPinCredentials()
                _isAppLockEnabled.value = false
                _isBiometricEnabled.value = false
                _isLocked.value = false
                _lockSessionId.value = 0L
            } else {
                val enabled = storage.isAppLockEnabled && validPin
                _isAppLockEnabled.value = enabled
                _isBiometricEnabled.value = enabled && storage.isBiometricEnabled
                _isLocked.value = enabled
                _lockSessionId.value = if (enabled) 1L else 0L
            }
        }
    }

    fun hasPinConfigured(): Boolean = synchronized(stateLock) {
        hasValidStoredPinLocked()
    }

    /**
     * Устанавливает новый 4-значный PIN-код: генерирует новую случайную соль,
     * вычисляет SHA-256 хэш и включает защиту (текущая сессия остаётся разблокированной).
     */
    fun setPin(pin: String) {
        require(isValidPinFormat(pin)) { "PIN must consist of $PIN_LENGTH digits" }
        synchronized(stateLock) {
            val saltBytes = generateSaltBytes(random)
            val hashBytes = hashPin(pin, saltBytes)
            storage.pinSaltHex = bytesToHex(saltBytes)
            storage.pinHashHex = bytesToHex(hashBytes)
            storage.isAppLockEnabled = true
            backgroundedAtMs = null
            _isAppLockEnabled.value = true
            _isLocked.value = false
        }
    }

    /** Проверяет введённый PIN по сохранённой соли и SHA-256 хэшу без изменения состояния блокировки. */
    fun verifyPin(pin: String): Boolean {
        if (!isValidPinFormat(pin)) return false
        return synchronized(stateLock) {
            verifyPinLocked(pin)
        }
    }

    /** Проверяет PIN и при совпадении разблокирует текущую сессию. */
    fun unlockWithPin(pin: String): Boolean {
        if (!isValidPinFormat(pin)) return false
        return synchronized(stateLock) {
            if (!verifyPinLocked(pin)) {
                return@synchronized false
            }
            backgroundedAtMs = null
            _isLocked.value = false
            true
        }
    }

    /** Разблокирует приложение после успешного прохождения системного BiometricPrompt. */
    fun unlockWithBiometric(): Boolean = synchronized(stateLock) {
        if (!_isAppLockEnabled.value || !_isBiometricEnabled.value || !hasValidStoredPinLocked()) {
            return@synchronized false
        }
        backgroundedAtMs = null
        _isLocked.value = false
        true
    }

    fun setBiometricEnabled(enabled: Boolean) {
        synchronized(stateLock) {
            val effective = enabled && _isAppLockEnabled.value && hasValidStoredPinLocked()
            storage.isBiometricEnabled = effective
            _isBiometricEnabled.value = effective
        }
    }

    fun setAutoLockTimeoutMs(timeoutMs: Long) {
        synchronized(stateLock) {
            val sanitized = timeoutMs.coerceAtLeast(0L)
            storage.pinAutoLockTimeoutMs = sanitized
            _autoLockTimeoutMs.value = sanitized
        }
    }

    /** Полностью отключает PIN и биометрию, стирая соль, хэш и флаги из локального хранилища. */
    fun disableAppLock() {
        synchronized(stateLock) {
            storage.clearPinCredentials()
            backgroundedAtMs = null
            _isAppLockEnabled.value = false
            _isBiometricEnabled.value = false
            _isLocked.value = false
        }
    }

    fun lockNow() {
        synchronized(stateLock) {
            if (_isAppLockEnabled.value && hasValidStoredPinLocked()) {
                if (!_isLocked.value) {
                    _lockSessionId.value = _lockSessionId.value + 1L
                }
                _isLocked.value = true
                backgroundedAtMs = null
            }
        }
    }

    /** Вызывается при реальном уходе приложения в фон (ON_STOP процесса). */
    fun onAppBackgrounded() {
        synchronized(stateLock) {
            if (!_isAppLockEnabled.value || !hasValidStoredPinLocked()) return
            if (_isLocked.value) return
            if (backgroundedAtMs == null) {
                backgroundedAtMs = clockMs()
            }
        }
    }

    /**
     * Вызывается при возврате приложения из фона (ON_START процесса).
     * Блокирует вход, только если время между ON_STOP и ON_START достигло порога [autoLockTimeoutMs].
     */
    fun onAppForegrounded() {
        synchronized(stateLock) {
            val bgTime = backgroundedAtMs
            backgroundedAtMs = null
            if (!_isAppLockEnabled.value || !hasValidStoredPinLocked()) {
                _isLocked.value = false
                return
            }
            if (_isLocked.value) return
            if (bgTime != null) {
                val elapsedMs = (clockMs() - bgTime).coerceAtLeast(0L)
                if (elapsedMs >= _autoLockTimeoutMs.value) {
                    _lockSessionId.value = _lockSessionId.value + 1L
                    _isLocked.value = true
                }
            }
        }
    }

    /**
     * Гарантирует однократный автозапуск BiometricPrompt в рамках одной сессии блокировки,
     * чтобы при раскрытии/складывании Z Fold диалог не всплывал повторно после отмены.
     */
    fun consumeAutoBiometricPrompt(sessionId: Long): Boolean = synchronized(stateLock) {
        if (!_isLocked.value || !_isAppLockEnabled.value || !_isBiometricEnabled.value) {
            return@synchronized false
        }
        if (lastAutoPromptedSessionId == sessionId) {
            return@synchronized false
        }
        lastAutoPromptedSessionId = sessionId
        true
    }

    /** Подписывается на жизненный цикл процесса один раз (вызывать на главном потоке). */
    fun attachToProcessLifecycle(lifecycle: Lifecycle) {
        if (processObserverAttached.compareAndSet(false, true)) {
            lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> onAppBackgrounded()
                        Lifecycle.Event.ON_START -> onAppForegrounded()
                        else -> Unit
                    }
                }
            )
        }
    }

    private fun hasValidStoredPinLocked(): Boolean {
        val salt = hexToBytes(storage.pinSaltHex) ?: return false
        val hash = hexToBytes(storage.pinHashHex) ?: return false
        return salt.size == SALT_SIZE_BYTES && hash.size == HASH_SIZE_BYTES
    }

    private fun verifyPinLocked(pin: String): Boolean {
        val salt = hexToBytes(storage.pinSaltHex) ?: return false
        val expectedHash = hexToBytes(storage.pinHashHex) ?: return false
        if (salt.size != SALT_SIZE_BYTES || expectedHash.size != HASH_SIZE_BYTES) {
            return false
        }
        val actualHash = hashPin(pin, salt)
        return MessageDigest.isEqual(expectedHash, actualHash)
    }

    companion object {
        const val PIN_LENGTH = 4
        const val SALT_SIZE_BYTES = 16
        const val HASH_SIZE_BYTES = 32
        const val DEFAULT_AUTO_LOCK_TIMEOUT_MS = 30_000L

        val AUTO_LOCK_TIMEOUT_OPTIONS_MS: List<Long> = listOf(
            0L,
            30_000L,
            60_000L,
            300_000L
        )

        private val HEX_DIGITS = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
        )

        @Volatile
        private var INSTANCE: PinSecurityManager? = null

        fun getInstance(context: Context): PinSecurityManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PinSecurityManager(
                    storage = FamilyPreferences(context.applicationContext),
                    clockMs = { SystemClock.elapsedRealtime() }
                ).also { INSTANCE = it }
            }
        }

        fun isValidPinFormat(pin: String): Boolean =
            pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

        internal fun generateSaltBytes(random: SecureRandom = SecureRandom()): ByteArray =
            ByteArray(SALT_SIZE_BYTES).also { random.nextBytes(it) }

        internal fun hashPin(pin: String, saltBytes: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(saltBytes)
            digest.update(':'.code.toByte())
            digest.update(pin.toByteArray(Charsets.UTF_8))
            return digest.digest()
        }

        internal fun bytesToHex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val value = bytes[i].toInt() and 0xFF
                out[i * 2] = HEX_DIGITS[value ushr 4]
                out[i * 2 + 1] = HEX_DIGITS[value and 0x0F]
            }
            return String(out)
        }

        internal fun hexToBytes(hex: String): ByteArray? {
            if (hex.isEmpty() || hex.length % 2 != 0) return null
            val result = ByteArray(hex.length / 2)
            for (i in result.indices) {
                val hi = Character.digit(hex[i * 2], 16)
                val lo = Character.digit(hex[i * 2 + 1], 16)
                if (hi == -1 || lo == -1) return null
                result[i] = ((hi shl 4) or lo).toByte()
            }
            return result
        }
    }
}
