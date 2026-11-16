package ru.pokanevich.familybudget.ui.sheets

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ru.pokanevich.familybudget.data.SyncState
import ru.pokanevich.familybudget.ui.theme.AppThemeMode
import ru.pokanevich.familybudget.ui.theme.AuthorAvatarCircle
import ru.pokanevich.familybudget.ui.theme.LocalFamilyPalette
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilySettingsSheet(
    currentUserName: String,
    familySpaceCode: String,
    monthlyBudgetRub: Double,
    autoConfirmPush: Boolean,
    geminiApiKey: String,
    themeMode: AppThemeMode,
    syncState: SyncState,
    isPushAccessGranted: Boolean,
    isImportingBackup: Boolean,
    importStatusMessage: String?,
    onOpenPushSettings: () -> Unit,
    onSimulateTestPush: () -> Unit,
    onImportBackupUri: (Uri) -> Unit,
    onSyncNow: () -> Unit,
    onThemePreview: (AppThemeMode) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, Double, Boolean, String, AppThemeMode) -> Unit
) {
    val palette = LocalFamilyPalette.current
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
    var apiKey by remember { mutableStateOf(geminiApiKey) }
    var selectedTheme by remember { mutableStateOf(themeMode) }
    var showApiKey by remember { mutableStateOf(false) }

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
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Настройки семьи",
                style = MaterialTheme.typography.titleLarge,
                color = palette.textPrimary
            )

            // Section 1: User & Family Budget
            SettingsGroupCard(title = "Профиль и бюджет") {
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

            // Section 3: Bank Push Automation
            SettingsGroupCard(title = "Пуши СберБанка и Mir Pay") {
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

            // Section 5: Import from Money Manager (.mmbackup)
            SettingsGroupCard(title = "Перенос данных") {
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
                    val parsedBudget = budget.toDoubleOrNull()?.takeIf { it > 0 } ?: 120000.0
                    onSave(
                        name,
                        code.trim().ifBlank { "POKANE-FAMILY" },
                        parsedBudget,
                        autoConfirm,
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
