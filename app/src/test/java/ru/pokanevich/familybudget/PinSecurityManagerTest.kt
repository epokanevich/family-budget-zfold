package ru.pokanevich.familybudget

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.data.FamilyConfig
import ru.pokanevich.familybudget.data.PinLockStorage
import ru.pokanevich.familybudget.data.PinSecurityManager

class PinSecurityManagerTest {

    private class InMemoryPinLockStorage : PinLockStorage {
        override var isAppLockEnabled: Boolean = false
        override var isBiometricEnabled: Boolean = false
        override var pinSaltHex: String = ""
        override var pinHashHex: String = ""
        override var pinAutoLockTimeoutMs: Long = PinSecurityManager.DEFAULT_AUTO_LOCK_TIMEOUT_MS

        override fun clearPinCredentials() {
            isAppLockEnabled = false
            isBiometricEnabled = false
            pinSaltHex = ""
            pinHashHex = ""
        }
    }

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun setPin_generatesUniqueRandomSaltAndSha256Hash_neverStoresPlaintext() {
        val storage = InMemoryPinLockStorage()
        var nowMs = 1_000L
        val manager = PinSecurityManager(storage, clockMs = { nowMs })

        manager.setPin("2580")

        assertTrue(storage.isAppLockEnabled)
        assertTrue(manager.isAppLockEnabled.value)
        assertTrue(manager.hasPinConfigured())
        assertFalse(manager.isLocked.value)

        val firstSalt = storage.pinSaltHex
        val firstHash = storage.pinHashHex
        assertEquals(PinSecurityManager.SALT_SIZE_BYTES * 2, firstSalt.length)
        assertEquals(PinSecurityManager.HASH_SIZE_BYTES * 2, firstHash.length)
        assertFalse("Salt must not leak plaintext PIN", firstSalt.contains("2580"))
        assertFalse("Hash must not leak plaintext PIN", firstHash.contains("2580"))

        // Установка того же PIN повторно обязана сгенерировать новую случайную соль и другой хэш
        manager.setPin("2580")
        assertNotEquals(firstSalt, storage.pinSaltHex)
        assertNotEquals(firstHash, storage.pinHashHex)
        assertTrue(manager.verifyPin("2580"))
    }

    @Test
    fun verifyAndUnlock_matchesCorrectPinAndRejectsMismatchOrMalformed() {
        val storage = InMemoryPinLockStorage()
        val setupManager = PinSecurityManager(storage, clockMs = { 10_000L })
        setupManager.setPin("7391")

        // Холодный старт с теми же сохранёнными данными должен начинаться заблокированным
        val coldStartManager = PinSecurityManager(storage, clockMs = { 20_000L })
        assertTrue(coldStartManager.isLocked.value)

        assertFalse(coldStartManager.verifyPin("0000"))
        assertFalse(coldStartManager.verifyPin("739"))
        assertFalse(coldStartManager.verifyPin("73910"))
        assertFalse(coldStartManager.verifyPin("73a1"))
        assertFalse(coldStartManager.verifyPin(""))

        assertFalse(coldStartManager.unlockWithPin("0000"))
        assertTrue(coldStartManager.isLocked.value)

        assertTrue(coldStartManager.unlockWithPin("7391"))
        assertFalse(coldStartManager.isLocked.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun setPin_rejectsNonFourDigitInput() {
        val manager = PinSecurityManager(InMemoryPinLockStorage(), clockMs = { 0L })
        manager.setPin("12a4")
    }

    @Test
    fun disableAppLock_clearsSaltHashAndBiometrics_andUnlocks() {
        val storage = InMemoryPinLockStorage()
        val manager = PinSecurityManager(storage, clockMs = { 1_000L })
        manager.setPin("4826")
        manager.setBiometricEnabled(true)
        manager.lockNow()

        assertTrue(manager.isLocked.value)
        assertTrue(storage.isBiometricEnabled)
        assertTrue(storage.pinSaltHex.isNotEmpty())
        assertTrue(storage.pinHashHex.isNotEmpty())

        manager.disableAppLock()

        assertFalse(storage.isAppLockEnabled)
        assertFalse(storage.isBiometricEnabled)
        assertEquals("", storage.pinSaltHex)
        assertEquals("", storage.pinHashHex)
        assertFalse(manager.isAppLockEnabled.value)
        assertFalse(manager.isBiometricEnabled.value)
        assertFalse(manager.isLocked.value)
        assertFalse(manager.hasPinConfigured())
        assertFalse(manager.verifyPin("4826"))
    }

    @Test
    fun corruptedStorage_withoutSaltOrHash_selfHealsInsteadOfLockingForever() {
        val storage = InMemoryPinLockStorage().apply {
            isAppLockEnabled = true
            isBiometricEnabled = true
            pinSaltHex = "not-valid-hex"
            pinHashHex = ""
        }

        val manager = PinSecurityManager(storage, clockMs = { 5_000L })

        assertFalse(manager.isLocked.value)
        assertFalse(manager.isAppLockEnabled.value)
        assertFalse(manager.isBiometricEnabled.value)
        assertFalse(storage.isAppLockEnabled)
        assertFalse(storage.isBiometricEnabled)
    }

    @Test
    fun backgroundTimeout_locksOnlyWhenElapsedReachesTimeout_andIgnoresFoldConfigChanges() {
        val storage = InMemoryPinLockStorage()
        var nowMs = 1_000L
        val manager = PinSecurityManager(storage, clockMs = { nowMs })
        val lifecycleOwner = FakeLifecycleOwner()
        manager.attachToProcessLifecycle(lifecycleOwner.lifecycle)

        manager.setPin("1357")
        assertEquals(30_000L, manager.autoLockTimeoutMs.value)

        // Холодный старт при активном PIN -> экран заблокирован
        val reopened = PinSecurityManager(storage, clockMs = { nowMs })
        val processLifecycle = FakeLifecycleOwner()
        reopened.attachToProcessLifecycle(processLifecycle.lifecycle)
        processLifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        processLifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertTrue(reopened.isLocked.value)

        assertTrue(reopened.unlockWithPin("1357"))
        assertFalse(reopened.isLocked.value)
        val initialSession = reopened.lockSessionId.value

        // Раскрытие/складывание Z Fold не генерирует ON_STOP/ON_START процесса -> остаёмся разблокированы
        nowMs += 120_000L
        assertFalse(reopened.isLocked.value)

        // Кратковременный уход в фон на 29 999 мс (< 30 сек) -> не блокируем
        processLifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        nowMs += 29_999L
        processLifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertFalse(reopened.isLocked.value)

        // Реальный уход в фон ровно на 30 000 мс (>= 30 сек) -> блокируем и начинаем новую lock-сессию
        processLifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        nowMs += 30_000L
        processLifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertTrue(reopened.isLocked.value)
        assertTrue(reopened.lockSessionId.value > initialSession)

        // Проверка настраиваемого таймаута 0 мс (блокировать сразу при уходе в фон)
        assertTrue(reopened.unlockWithPin("1357"))
        reopened.setAutoLockTimeoutMs(0L)
        processLifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        nowMs += 1L
        processLifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertTrue(reopened.isLocked.value)
    }

    @Test
    fun biometricUnlock_and_singleAutoPromptPerLockSession() {
        val storage = InMemoryPinLockStorage()
        var nowMs = 1_000L
        val manager = PinSecurityManager(storage, clockMs = { nowMs })

        // Нельзя включить биометрию, пока не задан PIN
        manager.setBiometricEnabled(true)
        assertFalse(manager.isBiometricEnabled.value)

        manager.setPin("9042")
        manager.setBiometricEnabled(true)
        assertTrue(manager.isBiometricEnabled.value)

        manager.lockNow()
        val session1 = manager.lockSessionId.value
        assertTrue(manager.consumeAutoBiometricPrompt(session1))
        // При перерисовке/сгибе Z Fold в той же сессии блокировки BiometricPrompt не всплывает повторно
        assertFalse(manager.consumeAutoBiometricPrompt(session1))

        assertTrue(manager.unlockWithBiometric())
        assertFalse(manager.isLocked.value)

        // Новая блокировка по таймауту открывает новую сессию и снова разрешает авто-биометрию
        manager.onAppBackgrounded()
        nowMs += 31_000L
        manager.onAppForegrounded()
        assertTrue(manager.isLocked.value)
        val session2 = manager.lockSessionId.value
        assertNotEquals(session1, session2)
        assertTrue(manager.consumeAutoBiometricPrompt(session2))
    }

    @Test
    fun familyConfigJson_doesNotContainLocalPinOrBiometricFields() {
        val json = FamilyConfig(monthlyBudgetRub = 200_000.0).toJsonObject().toString()
        assertFalse(json.contains("pin", ignoreCase = true))
        assertFalse(json.contains("biometric", ignoreCase = true))
        assertFalse(json.contains("appLock", ignoreCase = true))
    }
}
