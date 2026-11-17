package ru.pokanevich.familybudget.ui.util

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

enum class BiometricAvailability {
    AVAILABLE,
    NONE_ENROLLED,
    NO_HARDWARE,
    UNAVAILABLE
}

fun Context.checkBiometricAvailability(): BiometricAvailability {
    val status = runCatching {
        BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
    }.getOrDefault(BiometricManager.BIOMETRIC_STATUS_UNKNOWN)

    return when (status) {
        BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> BiometricAvailability.NO_HARDWARE
        else -> BiometricAvailability.UNAVAILABLE
    }
}

tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

fun showBiometricUnlockPrompt(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: (errorCode: Int, message: String) -> Unit,
    onFailedAttempt: () -> Unit = {}
) {
    if (activity.isFinishing || activity.isDestroyed || activity.supportFragmentManager.isStateSaved) {
        onError(BiometricPrompt.ERROR_CANCELED, "")
        return
    }

    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onError(errorCode, errString.toString())
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onFailedAttempt()
            }
        }
    )

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Вход в Семейный котёл")
        .setSubtitle("Подтвердите личность по биометрии")
        .setNegativeButtonText("Ввести PIN-код")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .build()

    runCatching {
        prompt.authenticate(promptInfo)
    }.onFailure { err ->
        onError(BiometricPrompt.ERROR_UNABLE_TO_PROCESS, err.message.orEmpty())
    }
}
