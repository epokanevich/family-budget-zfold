package ru.pokanevich.familybudget.ui.sheets

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ru.pokanevich.familybudget.data.BankPushSource
import ru.pokanevich.familybudget.data.FamilyPreferences
import ru.pokanevich.familybudget.data.PinSecurityManager
import ru.pokanevich.familybudget.data.SyncState
import ru.pokanevich.familybudget.ui.components.PinDialogMode
import ru.pokanevich.familybudget.ui.components.PinSetupAndVerifyDialog
import ru.pokanevich.familybudget.ui.theme.AppThemeMode
import ru.pokanevich.familybudget.ui.theme.AuthorAvatarCircle
import ru.pokanevich.familybudget.ui.theme.LocalFamilyPalette
import ru.pokanevich.familybudget.ui.util.BiometricAvailability
import ru.pokanevich.familybudget.ui.util.checkBiometricAvailability
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FamilySettingsSheet(
    currentUserName: String,
    familySpaceCode: String,
    monthlyBudgetRub: Double,
    autoConfirmPush: Boolean,
    enabledBankSources: Set<BankPushSource>,
    geminiApiKey: String,
    themeMode: AppThemeMode,
    syncState: SyncState,
    isPushAccessGranted: Boolean,
    isBatteryUnrestricted: Boolean,
    pushDebugLog: String,
    isImportingBackup: Boolean,
    importStatusMessage: String?,
    isAppLockEnabled: Boolean,
    isBiometricEnabled: Boolean,
    hideAmountsOnWidget: Boolean,
    autoLockTimeoutMs: Long,
    onVerifyCurrentPin: (String) -> Boolean,
    onSetOrChangePin: (String) -> Unit,
    onDisableAppLock: () -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onToggleHideAmountsOnWidget: (Boolean) -> Unit,
    onChangeAutoLockTimeout: (Long) -> Unit,
    onExportCsvCurrentPeriod: () -> Unit,
    onExportCsvAllTime: () -> Unit,
    onOpenPushSettings: () -> Unit,
    onOpenAppInfoSettings: () -> Unit = {},
    onRequestBatteryExemption: () -> Unit,
    onSimulateTestPush: () -> Unit,
    onImportBackupUri: (Uri) -> Unit,
    onSyncNow: () -> Unit,
    onOpenCategoriesAndLimits: () -> Unit,
    onOpenRecurringPayments: () -> Unit,
    onThemePreview: (AppThemeMode) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, Double, Boolean, Set<BankPushSource>, String, AppThemeMode) -> Unit
) {
    val palette = LocalFamilyPalette.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val backupPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportBackupUri(uri)
        }
    }

    var name by remember { mutableStateOf(currentUserName) }
    var code by remember { mutableStateOf(familySpaceCode) }
    var budget by remember { mutableStateOf(monthlyBudgetRub.roundToInt().toString()) }
    var autoConfirm by remember { mutableStateOf(autoConfirmPush) }
    var selectedBankSources by remember { mutableStateOf(enabledBankSources) }
    var apiKey by remember { mutableStateOf(geminiApiKey) }
    var selectedTheme by remember { mutableStateOf(themeMode) }
    var showApiKey by remember { mutableStateOf(false) }
    var activePinDialogMode by remember { mutableStateOf<PinDialogMode?>(null) }
    var biometricAvailability by remember {
        mutableStateOf(context.checkBiometricAvailability())
    }

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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surfaceCard,
        contentColor = palette.textPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = if (FamilyPreferences.isSoloProfile) "Настройки бюджета" else "Настройки семьи",
                style = MaterialTheme.typography.titleLarge,
                color = palette.textPrimary
            )

            // Section 1: User & Family Budget
            SettingsGroupCard(title = "Профиль и бюджет") {
                if (FamilyPreferences.isSoloProfile) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Имя владельца бюджета") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.accentEmerald,
                            unfocusedBorderColor = palette.outlineSubtle
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = "Кто пользуется этим телефоном",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        listOf("Евгений", "Анжелика").forEach { person ->
                            val selected = name == person
                            val isAnzhelika = person == "Анжелика"
                            val accent = if (isAnzhelika) palette.anzhelikaColor else palette.eugeneColor
                            val soft = if (isAnzhelika) palette.anzhelikaSoft else palette.eugeneSoft

                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { name = person },
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) soft else palette.surfaceCard,
                                border = BorderStroke(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) accent else palette.outlineSubtle
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    AuthorAvatarCircle(authorName = person, size = 24.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = person,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (selected) accent else palette.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                if (FamilyPreferences.isSpaceCodeLocked) {
                    OutlinedTextField(
                        value = FamilyPreferences.defaultFamilySpaceCode,
                        onValueChange = {},
                        enabled = false,
                        readOnly = true,
                        label = { Text("Изолированный контур") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = palette.textSecondary,
                            disabledBorderColor = palette.outlineSubtle,
                            disabledLabelColor = palette.textMuted
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Общий код семьи") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.accentEmerald,
                            unfocusedBorderColor = palette.outlineSubtle
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (FamilyPreferences.normalizeFamilyCode(code) != FamilyPreferences.normalizeFamilyCode(familySpaceCode)) {
                        Text(
                            text = "При сохранении нового кода локальный кэш будет очищен, чтобы не смешать базы разных семей.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.accentAmber
                        )
                    }
                }

                OutlinedTextField(
                    value = budget,
                    onValueChange = { raw -> budget = raw.filter { it.isDigit() } },
                    label = { Text("Месячный бюджет (₽)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accentEmerald,
                        unfocusedBorderColor = palette.outlineSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(
                    onClick = onOpenCategoriesAndLimits,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, palette.accentEmerald),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = palette.accentEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Категории и ориентиры расходов",
                        color = palette.accentEmerald,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = onOpenRecurringPayments,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, palette.accentBlue),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Repeat,
                        contentDescription = null,
                        tint = palette.accentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Регулярные платежи и подписки",
                        color = palette.accentBlue,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Section 2: Theme
            SettingsGroupCard(title = "Оформление") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppThemeMode.entries.forEach { mode ->
                        val selected = selectedTheme == mode
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedTheme = mode
                                    onThemePreview(mode)
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) palette.accentEmeraldSoft else palette.surfaceCard,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (selected) palette.accentEmerald else palette.outlineSubtle
                            )
                        ) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) palette.accentEmerald else palette.textSecondary,
                                modifier = Modifier
                                    .padding(vertical = 10.dp)
                                    .fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Section 3: Local Security (PIN & Biometrics)
            SettingsGroupCard(title = "Безопасность и вход") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (isAppLockEnabled) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        contentDescription = null,
                        tint = if (isAppLockEnabled) palette.accentEmerald else palette.textMuted,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isAppLockEnabled) {
                                "Вход по PIN-коду включён"
                            } else {
                                "Вход по PIN-коду выключен"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isAppLockEnabled) palette.accentEmerald else palette.textPrimary
                        )
                        Text(
                            text = "Настройка локальная — только для этого телефона",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary
                        )
                    }
                }

                if (!isAppLockEnabled) {
                    OutlinedButton(
                        onClick = { activePinDialogMode = PinDialogMode.CREATE },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, palette.accentEmerald),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = palette.accentEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Установить PIN-код",
                            color = palette.accentEmerald,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    HorizontalDivider(thickness = 0.6.dp, color = palette.outlineSubtle)

                    val canEnableBiometric = biometricAvailability == BiometricAvailability.AVAILABLE
                    val biometricSubtitle = when (biometricAvailability) {
                        BiometricAvailability.AVAILABLE -> "Отпечаток пальца или лицо при входе"
                        BiometricAvailability.NONE_ENROLLED -> "Добавьте отпечаток или лицо в настройках телефона"
                        BiometricAvailability.NO_HARDWARE -> "Биометрический датчик не обнаружен"
                        BiometricAvailability.UNAVAILABLE -> "Биометрия временно недоступна"
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Fingerprint,
                                contentDescription = null,
                                tint = if (isBiometricEnabled && canEnableBiometric) {
                                    palette.accentEmerald
                                } else {
                                    palette.textSecondary
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "Разблокировка по биометрии",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.textPrimary
                                )
                                Text(
                                    text = biometricSubtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = isBiometricEnabled,
                            enabled = canEnableBiometric || isBiometricEnabled,
                            onCheckedChange = { enabled ->
                                if (!enabled || canEnableBiometric) {
                                    onToggleBiometric(enabled)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = palette.accentEmerald
                            )
                        )
                    }

                    HorizontalDivider(thickness = 0.6.dp, color = palette.outlineSubtle)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Скрывать суммы на виджете",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "Показывать «•••• ₽» вместо чисел на рабочем столе",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textMuted
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = hideAmountsOnWidget,
                            onCheckedChange = onToggleHideAmountsOnWidget,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = palette.accentEmerald
                            )
                        )
                    }

                    HorizontalDivider(thickness = 0.6.dp, color = palette.outlineSubtle)

                    Text(
                        text = "Автоблокировка после ухода в фон",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PinSecurityManager.AUTO_LOCK_TIMEOUT_OPTIONS_MS.forEach { optionMs ->
                            val selected = autoLockTimeoutMs == optionMs
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onChangeAutoLockTimeout(optionMs) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) palette.accentEmeraldSoft else palette.surfaceCard,
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (selected) palette.accentEmerald else palette.outlineSubtle
                                )
                            ) {
                                Text(
                                    text = formatAutoLockTimeoutLabel(optionMs),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) palette.accentEmerald else palette.textSecondary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(thickness = 0.6.dp, color = palette.outlineSubtle)

                    OutlinedButton(
                        onClick = { activePinDialogMode = PinDialogMode.CHANGE },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, palette.accentBlue),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Сменить PIN-код",
                            color = palette.accentBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    OutlinedButton(
                        onClick = { activePinDialogMode = PinDialogMode.DISABLE },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, palette.accentCoral),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Отключить PIN-код",
                            color = palette.accentCoral,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Section 3: Bank Push Automation
            SettingsGroupCard(title = "Автоучёт пушей и SMS") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Сразу заносить покупки в бюджет без подтверждения",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = autoConfirm,
                        onCheckedChange = { autoConfirm = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = palette.accentEmerald
                        )
                    )
                }

                HorizontalDivider(thickness = 0.6.dp, color = palette.outlineSubtle)

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Банки для перехвата",
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "Настройка только для этого телефона",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BankPushSource.entries.forEach { source ->
                        val checked = source in selectedBankSources
                        val toggleSource = { enabled: Boolean ->
                            selectedBankSources = if (enabled) {
                                selectedBankSources + source
                            } else {
                                selectedBankSources - source
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { toggleSource(!checked) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = source.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = checked,
                                onCheckedChange = toggleSource,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = palette.accentEmerald
                                )
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 0.6.dp, color = palette.outlineSubtle)

                OutlinedButton(
                    onClick = onOpenPushSettings,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isPushAccessGranted) palette.accentEmerald else palette.accentBlue
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.NotificationsActive,
                        contentDescription = null,
                        tint = if (isPushAccessGranted) palette.accentEmerald else palette.accentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isPushAccessGranted) "Доступ к уведомлениям включён ✓" else "Включить доступ к уведомлениям",
                        color = if (isPushAccessGranted) palette.accentEmerald else palette.accentBlue,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (!isPushAccessGranted) {
                    Text(
                        text = "На Android 13+ при установке APK из Telegram системный тумблер может быть заблокирован: откройте «О приложении» → ⋮ → «Разрешить ограниченные настройки».",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                    TextButton(
                        onClick = onOpenAppInfoSettings,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Открыть «О приложении» (разблокировать ⋮)",
                            color = palette.accentBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                OutlinedButton(
                    onClick = onRequestBatteryExemption,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isBatteryUnrestricted) palette.accentEmerald else palette.accentAmber
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BatterySaver,
                        contentDescription = null,
                        tint = if (isBatteryUnrestricted) palette.accentEmerald else palette.accentAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBatteryUnrestricted) {
                            "Фоновая работа без ограничений ✓"
                        } else {
                            "Разрешить работу в фоне (батарея)"
                        },
                        color = if (isBatteryUnrestricted) palette.accentEmerald else palette.accentAmber,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (pushDebugLog.isNotBlank()) {
                    Text(
                        text = "Последние события пушей:\n$pushDebugLog",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textMuted
                    )
                }

                TextButton(
                    onClick = {
                        onSimulateTestPush()
                        onDismiss()
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = "Отправить тестовый пуш Сбера",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textSecondary
                    )
                }
            }

            // Section 4: Cloud & AI
            SettingsGroupCard(title = "Облако и ИИ-аналитик") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = syncState.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onSyncNow) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = null,
                            tint = palette.accentEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Обновить",
                            color = palette.accentEmerald,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Ключ Gemini API (необязательно)") },
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = "Показать ключ",
                                tint = palette.textMuted
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accentEmerald,
                        unfocusedBorderColor = palette.outlineSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Section 5: Import / Export
            SettingsGroupCard(title = "Перенос данных") {
                OutlinedButton(
                    onClick = onExportCsvCurrentPeriod,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, palette.accentEmerald),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileDownload,
                        contentDescription = null,
                        tint = palette.accentEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Экспорт в CSV (текущий период)",
                        color = palette.accentEmerald,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = onExportCsvAllTime,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, palette.accentEmerald),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileDownload,
                        contentDescription = null,
                        tint = palette.accentEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Экспорт в CSV (вся база)",
                        color = palette.accentEmerald,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    enabled = !isImportingBackup,
                    onClick = { backupPickerLauncher.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, palette.accentBlue),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isImportingBackup) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = palette.accentBlue
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Импорт архива...",
                            color = palette.accentBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.FileUpload,
                            contentDescription = null,
                            tint = palette.accentBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Импорт архива Money Manager (.mmbackup)",
                            color = palette.accentBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (importStatusMessage != null) {
                    Text(
                        text = importStatusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.accentEmerald
                    )
                }
            }

            Button(
                onClick = {
                    val parsedBudget = budget.toDoubleOrNull()?.takeIf { it > 0 }
                        ?: FamilyPreferences.defaultMonthlyBudgetRub
                    val resolvedCode = if (FamilyPreferences.isSpaceCodeLocked) {
                        FamilyPreferences.defaultFamilySpaceCode
                    } else {
                        code.trim().ifBlank { FamilyPreferences.defaultFamilySpaceCode }
                    }
                    onSave(
                        name,
                        resolvedCode,
                        parsedBudget,
                        autoConfirm,
                        selectedBankSources,
                        apiKey.trim(),
                        selectedTheme
                    )
                },
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accentEmerald,
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Сохранить настройки",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    val pinMode = activePinDialogMode
    if (pinMode != null) {
        PinSetupAndVerifyDialog(
            mode = pinMode,
            onVerifyCurrentPin = onVerifyCurrentPin,
            onPinConfigured = { newPin ->
                onSetOrChangePin(newPin)
                activePinDialogMode = null
            },
            onDisableConfirmed = {
                onDisableAppLock()
                activePinDialogMode = null
            },
            onDismiss = { activePinDialogMode = null }
        )
    }
}

private fun formatAutoLockTimeoutLabel(timeoutMs: Long): String = when {
    timeoutMs <= 0L -> "Сразу"
    timeoutMs < 60_000L -> "${timeoutMs / 1000L} сек"
    else -> "${timeoutMs / 60_000L} мин"
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val palette = LocalFamilyPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary,
            fontWeight = FontWeight.SemiBold
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = palette.surfaceElevated
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }
}
