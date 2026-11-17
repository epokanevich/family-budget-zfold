package ru.pokanevich.familybudget.ui.components

import androidx.biometric.BiometricPrompt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import ru.pokanevich.familybudget.data.PinSecurityManager
import ru.pokanevich.familybudget.ui.theme.LocalFamilyPalette
import ru.pokanevich.familybudget.ui.util.BiometricAvailability
import ru.pokanevich.familybudget.ui.util.checkBiometricAvailability
import ru.pokanevich.familybudget.ui.util.findFragmentActivity
import ru.pokanevich.familybudget.ui.util.showBiometricUnlockPrompt
import kotlin.math.roundToInt

enum class PinDialogMode {
    CREATE,
    CHANGE,
    DISABLE
}

private enum class PinDialogStep {
    VERIFY_CURRENT,
    ENTER_NEW,
    CONFIRM_NEW
}

@Composable
fun PinLockScreen(
    pinManager: PinSecurityManager,
    isBiometricEnabled: Boolean,
    lockSessionId: Long
) {
    val palette = LocalFamilyPalette.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current

    var enteredPin by remember(lockSessionId) { mutableStateOf("") }
    var errorMessage by remember(lockSessionId) { mutableStateOf<String?>(null) }
    var isErrorShaking by remember(lockSessionId) { mutableStateOf(false) }
    var failedAttempts by remember(lockSessionId) { mutableIntStateOf(0) }
    var cooldownSecondsLeft by remember(lockSessionId) { mutableIntStateOf(0) }
    var biometricAvailability by remember {
        mutableStateOf(context.checkBiometricAvailability())
    }

    val shakeOffset = remember(lockSessionId) { Animatable(0f) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                biometricAvailability = context.checkBiometricAvailability()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(cooldownSecondsLeft) {
        if (cooldownSecondsLeft > 0) {
            delay(1_000L)
            val next = cooldownSecondsLeft - 1
            cooldownSecondsLeft = next
            if (next > 0) {
                errorMessage = "Слишком много попыток. Подождите $next сек"
            } else {
                errorMessage = null
            }
        }
    }

    LaunchedEffect(isErrorShaking) {
        if (isErrorShaking) {
            val offsets = listOf(-14f, 14f, -10f, 10f, -6f, 6f, 0f)
            for (target in offsets) {
                shakeOffset.animateTo(target, animationSpec = tween(durationMillis = 36))
            }
            delay(120L)
            enteredPin = ""
            isErrorShaking = false
        }
    }

    val canUseBiometrics = isBiometricEnabled && biometricAvailability == BiometricAvailability.AVAILABLE

    val launchBiometric: () -> Unit = {
        val activity = context.findFragmentActivity()
        if (activity != null &&
            canUseBiometrics &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            showBiometricUnlockPrompt(
                activity = activity,
                onSuccess = {
                    pinManager.unlockWithBiometric()
                },
                onError = { errorCode, errText ->
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> Unit
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                            errorMessage = "Биометрия временно заблокирована — введите PIN-код"
                        }
                        else -> {
                            if (errText.isNotBlank()) {
                                errorMessage = errText
                            }
                        }
                    }
                }
            )
        }
    }

    LaunchedEffect(lockSessionId, isBiometricEnabled, biometricAvailability, lifecycleOwner) {
        if (isBiometricEnabled && biometricAvailability == BiometricAvailability.AVAILABLE) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                delay(120L)
                if (pinManager.consumeAutoBiometricPrompt(lockSessionId)) {
                    launchBiometric()
                }
            }
        }
    }

    val onDigitPress: (Char) -> Unit = { digit ->
        if (!isErrorShaking && cooldownSecondsLeft == 0 && enteredPin.length < PinSecurityManager.PIN_LENGTH) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            errorMessage = null
            val next = enteredPin + digit
            enteredPin = next
            if (next.length == PinSecurityManager.PIN_LENGTH) {
                if (pinManager.unlockWithPin(next)) {
                    failedAttempts = 0
                    errorMessage = null
                } else {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val nextAttempts = failedAttempts + 1
                    failedAttempts = nextAttempts
                    if (nextAttempts >= 5) {
                        cooldownSecondsLeft = 15
                        errorMessage = "Слишком много попыток. Подождите 15 сек"
                    } else {
                        errorMessage = "Неверный PIN-код"
                    }
                    isErrorShaking = true
                }
            }
        }
    }

    val onBackspacePress: () -> Unit = {
        if (!isErrorShaking && cooldownSecondsLeft == 0 && enteredPin.isNotEmpty()) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            enteredPin = enteredPin.dropLast(1)
            errorMessage = null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = palette.background
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            val isWideUnfolded = maxWidth >= 560.dp
            val isCompactCover = maxWidth < 350.dp
            val isShortHeight = maxHeight < 640.dp

            val keySize = when {
                isCompactCover || isShortHeight -> 60.dp
                isWideUnfolded -> 72.dp
                else -> 68.dp
            }
            val keySpacing = if (isCompactCover) 14.dp else 20.dp
            val rowSpacing = if (isShortHeight) 10.dp else 14.dp

            val contentColumn: @Composable (Boolean) -> Unit = { insideCard ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = if (insideCard) 28.dp else if (isCompactCover) 16.dp else 24.dp,
                            vertical = if (insideCard) 28.dp else 16.dp
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (isShortHeight) 48.dp else 58.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(palette.accentEmeraldSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = palette.accentEmerald,
                            modifier = Modifier.size(if (isShortHeight) 24.dp else 28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(if (isShortHeight) 12.dp else 16.dp))

                    Text(
                        text = "Семейный котёл",
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Введите PIN-код для входа",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(if (isShortHeight) 18.dp else 24.dp))

                    PinDotsRow(
                        enteredCount = enteredPin.length,
                        isError = isErrorShaking || errorMessage != null,
                        modifier = Modifier.offset { IntOffset(shakeOffset.value.roundToInt(), 0) }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (errorMessage != null) {
                            Text(
                                text = errorMessage!!,
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.accentCoral,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(if (isShortHeight) 10.dp else 16.dp))

                    PinNumericKeypad(
                        buttonSize = keySize,
                        horizontalSpacing = keySpacing,
                        verticalSpacing = rowSpacing,
                        keyContainerColor = if (insideCard) palette.surfaceElevated else palette.surfaceCard,
                        enabled = cooldownSecondsLeft == 0 && !isErrorShaking,
                        canBackspace = enteredPin.isNotEmpty() && cooldownSecondsLeft == 0 && !isErrorShaking,
                        showBiometricButton = canUseBiometrics,
                        onDigitPress = onDigitPress,
                        onBackspacePress = onBackspacePress,
                        onBiometricPress = launchBiometric
                    )

                    if (canUseBiometrics) {
                        Spacer(modifier = Modifier.height(if (isShortHeight) 8.dp else 12.dp))
                        TextButton(onClick = launchBiometric) {
                            Icon(
                                imageVector = Icons.Rounded.Fingerprint,
                                contentDescription = null,
                                tint = palette.accentEmerald,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Разблокировать по биометрии",
                                style = MaterialTheme.typography.labelLarge,
                                color = palette.accentEmerald,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center
            ) {
                if (isWideUnfolded) {
                    Surface(
                        modifier = Modifier
                            .padding(24.dp)
                            .widthIn(max = 400.dp)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        color = palette.surfaceCard,
                        border = BorderStroke(1.dp, palette.outlineSubtle)
                    ) {
                        contentColumn(true)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 380.dp)
                            .fillMaxWidth()
                    ) {
                        contentColumn(false)
                    }
                }
            }
        }
    }
}

@Composable
fun PinSetupAndVerifyDialog(
    mode: PinDialogMode,
    onVerifyCurrentPin: (String) -> Boolean,
    onPinConfigured: (String) -> Unit,
    onDisableConfirmed: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalFamilyPalette.current
    val haptic = LocalHapticFeedback.current

    val initialStep = when (mode) {
        PinDialogMode.CREATE -> PinDialogStep.ENTER_NEW
        PinDialogMode.CHANGE -> PinDialogStep.VERIFY_CURRENT
        PinDialogMode.DISABLE -> PinDialogStep.VERIFY_CURRENT
    }

    var step by remember(mode) { mutableStateOf(initialStep) }
    var firstNewPin by remember(mode) { mutableStateOf("") }
    var enteredPin by remember(mode) { mutableStateOf("") }
    var errorMessage by remember(mode) { mutableStateOf<String?>(null) }
    var isErrorShaking by remember(mode) { mutableStateOf(false) }

    val shakeOffset = remember(mode) { Animatable(0f) }

    LaunchedEffect(isErrorShaking) {
        if (isErrorShaking) {
            val offsets = listOf(-12f, 12f, -8f, 8f, -4f, 4f, 0f)
            for (target in offsets) {
                shakeOffset.animateTo(target, animationSpec = tween(durationMillis = 32))
            }
            delay(100L)
            enteredPin = ""
            isErrorShaking = false
        }
    }

    val title = when (step) {
        PinDialogStep.VERIFY_CURRENT -> if (mode == PinDialogMode.DISABLE) {
            "Отключение PIN-кода"
        } else {
            "Текущий PIN-код"
        }
        PinDialogStep.ENTER_NEW -> if (mode == PinDialogMode.CHANGE) {
            "Новый PIN-код"
        } else {
            "Придумайте PIN-код"
        }
        PinDialogStep.CONFIRM_NEW -> "Повторите PIN-код"
    }

    val subtitle = when (step) {
        PinDialogStep.VERIFY_CURRENT -> "Введите действующий 4-значный PIN"
        PinDialogStep.ENTER_NEW -> "Выберите 4 цифры для входа в приложение"
        PinDialogStep.CONFIRM_NEW -> "Введите новый PIN ещё раз для подтверждения"
    }

    val onDigitPress: (Char) -> Unit = { digit ->
        if (!isErrorShaking && enteredPin.length < PinSecurityManager.PIN_LENGTH) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            errorMessage = null
            val next = enteredPin + digit
            enteredPin = next
            if (next.length == PinSecurityManager.PIN_LENGTH) {
                when (step) {
                    PinDialogStep.VERIFY_CURRENT -> {
                        if (onVerifyCurrentPin(next)) {
                            if (mode == PinDialogMode.DISABLE) {
                                onDisableConfirmed()
                            } else {
                                enteredPin = ""
                                errorMessage = null
                                step = PinDialogStep.ENTER_NEW
                            }
                        } else {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            errorMessage = "Неверный текущий PIN-код"
                            isErrorShaking = true
                        }
                    }
                    PinDialogStep.ENTER_NEW -> {
                        firstNewPin = next
                        enteredPin = ""
                        errorMessage = null
                        step = PinDialogStep.CONFIRM_NEW
                    }
                    PinDialogStep.CONFIRM_NEW -> {
                        if (next == firstNewPin) {
                            onPinConfigured(next)
                        } else {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            errorMessage = "PIN-коды не совпадают"
                            isErrorShaking = true
                        }
                    }
                }
            }
        }
    }

    val onBackspacePress: () -> Unit = {
        if (!isErrorShaking && enteredPin.isNotEmpty()) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            enteredPin = enteredPin.dropLast(1)
            errorMessage = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            val isCompactWidth = maxWidth < 340.dp
            val keySize = if (isCompactWidth) 54.dp else 62.dp

            Surface(
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = palette.surfaceCard,
                border = BorderStroke(1.dp, palette.outlineSubtle)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = if (isCompactWidth) 14.dp else 22.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    PinDotsRow(
                        enteredCount = enteredPin.length,
                        isError = isErrorShaking || errorMessage != null,
                        modifier = Modifier.offset { IntOffset(shakeOffset.value.roundToInt(), 0) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (errorMessage != null) {
                            Text(
                                text = errorMessage!!,
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.accentCoral,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    PinNumericKeypad(
                        buttonSize = keySize,
                        horizontalSpacing = if (isCompactWidth) 12.dp else 16.dp,
                        verticalSpacing = 10.dp,
                        keyContainerColor = palette.surfaceElevated,
                        enabled = !isErrorShaking,
                        canBackspace = enteredPin.isNotEmpty() && !isErrorShaking,
                        showBiometricButton = false,
                        onDigitPress = onDigitPress,
                        onBackspacePress = onBackspacePress,
                        onBiometricPress = {}
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (step == PinDialogStep.CONFIRM_NEW) {
                            Arrangement.SpaceBetween
                        } else {
                            Arrangement.End
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (step == PinDialogStep.CONFIRM_NEW) {
                            TextButton(
                                onClick = {
                                    firstNewPin = ""
                                    enteredPin = ""
                                    errorMessage = null
                                    step = PinDialogStep.ENTER_NEW
                                }
                            ) {
                                Text(
                                    text = "Ввести заново",
                                    color = palette.accentBlue,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = "Отмена",
                                color = palette.textSecondary,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinDotsRow(
    enteredCount: Int,
    isError: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalFamilyPalette.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(PinSecurityManager.PIN_LENGTH) { index ->
            val filled = index < enteredCount
            val targetColor = when {
                isError -> palette.accentCoral
                filled -> palette.accentEmerald
                else -> Color.Transparent
            }
            val targetBorder = when {
                isError -> palette.accentCoral
                filled -> palette.accentEmerald
                else -> palette.outlineSubtle
            }
            val fillColor by animateColorAsState(
                targetValue = targetColor,
                animationSpec = tween(durationMillis = 120),
                label = "pinDotFill"
            )
            val borderColor by animateColorAsState(
                targetValue = targetBorder,
                animationSpec = tween(durationMillis = 120),
                label = "pinDotBorder"
            )
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(fillColor)
                    .border(2.dp, borderColor, CircleShape)
            )
        }
    }
}

@Composable
private fun PinNumericKeypad(
    buttonSize: Dp,
    horizontalSpacing: Dp,
    verticalSpacing: Dp,
    keyContainerColor: Color,
    enabled: Boolean,
    canBackspace: Boolean,
    showBiometricButton: Boolean,
    onDigitPress: (Char) -> Unit,
    onBackspacePress: () -> Unit,
    onBiometricPress: () -> Unit
) {
    val palette = LocalFamilyPalette.current
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9')
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(verticalSpacing)
    ) {
        rows.forEach { rowDigits ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                rowDigits.forEach { digit ->
                    DigitKeyButton(
                        digit = digit,
                        size = buttonSize,
                        containerColor = keyContainerColor,
                        enabled = enabled,
                        onClick = { onDigitPress(digit) }
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBiometricButton) {
                Surface(
                    modifier = Modifier
                        .size(buttonSize)
                        .clip(CircleShape)
                        .clickable(onClick = onBiometricPress),
                    shape = CircleShape,
                    color = palette.accentEmeraldSoft,
                    border = BorderStroke(1.dp, palette.accentEmerald.copy(alpha = 0.35f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Fingerprint,
                            contentDescription = "Разблокировать по биометрии",
                            tint = palette.accentEmerald,
                            modifier = Modifier.size((buttonSize.value * 0.42f).dp)
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.size(buttonSize))
            }

            DigitKeyButton(
                digit = '0',
                size = buttonSize,
                containerColor = keyContainerColor,
                enabled = enabled,
                onClick = { onDigitPress('0') }
            )

            Surface(
                modifier = Modifier
                    .size(buttonSize)
                    .clip(CircleShape)
                    .alpha(if (canBackspace) 1f else 0.4f)
                    .clickable(enabled = canBackspace, onClick = onBackspacePress),
                shape = CircleShape,
                color = Color.Transparent
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Backspace,
                        contentDescription = "Стереть цифру",
                        tint = palette.textSecondary,
                        modifier = Modifier.size((buttonSize.value * 0.34f).dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DigitKeyButton(
    digit: Char,
    size: Dp,
    containerColor: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalFamilyPalette.current
    Surface(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = containerColor,
        border = BorderStroke(1.dp, palette.outlineSubtle)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = digit.toString(),
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary
            )
        }
    }
}
