package ru.pokanevich.familybudget.ui.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.pokanevich.familybudget.ai.AiChatMessage
import ru.pokanevich.familybudget.ai.AiChatRole
import ru.pokanevich.familybudget.ui.theme.LocalFamilyPalette

private val QUICK_AI_QUESTIONS = listOf(
    "На чём мы сэкономили за полгода?",
    "Сколько ушло на маркетплейсы?",
    "Где перебор по категориям в этом месяце?",
    "Сколько свободных денег с учётом копилок?",
    "Топ-5 крупных трат за прошлый месяц"
)

/**
 * Модальный лист диалога с ИИ-советником (для компактного/сложенного экрана).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatSheet(
    messages: List<AiChatMessage>,
    isSending: Boolean,
    activeModelName: String?,
    totalRecordsCount: Int,
    geminiApiKey: String,
    onSendQuestion: (String) -> Unit,
    onClearChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalFamilyPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surfaceCard,
        dragHandle = null
    ) {
        AiChatContent(
            messages = messages,
            isSending = isSending,
            activeModelName = activeModelName,
            totalRecordsCount = totalRecordsCount,
            geminiApiKey = geminiApiKey,
            onSendQuestion = onSendQuestion,
            onClearChat = onClearChat,
            onOpenSettings = onOpenSettings,
            onClose = onDismiss,
            isInlineMode = false,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .imePadding()
        )
    }
}

/**
 * Встроенная панель диалога с ИИ-советником для правой колонки разложенного Z Fold:
 * слева остаётся лента операций, справа — переписка с советником.
 */
@Composable
fun AiChatInlinePanel(
    messages: List<AiChatMessage>,
    isSending: Boolean,
    activeModelName: String?,
    totalRecordsCount: Int,
    geminiApiKey: String,
    onSendQuestion: (String) -> Unit,
    onClearChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onBackToAnalytics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFamilyPalette.current
    Surface(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(20.dp),
        color = palette.surfaceCard,
        border = BorderStroke(1.dp, palette.outlineSubtle)
    ) {
        AiChatContent(
            messages = messages,
            isSending = isSending,
            activeModelName = activeModelName,
            totalRecordsCount = totalRecordsCount,
            geminiApiKey = geminiApiKey,
            onSendQuestion = onSendQuestion,
            onClearChat = onClearChat,
            onOpenSettings = onOpenSettings,
            onClose = onBackToAnalytics,
            isInlineMode = true,
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiChatContent(
    messages: List<AiChatMessage>,
    isSending: Boolean,
    activeModelName: String?,
    totalRecordsCount: Int,
    geminiApiKey: String,
    onSendQuestion: (String) -> Unit,
    onClearChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    isInlineMode: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalFamilyPalette.current
    var draftText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val hasApiKey = geminiApiKey.isNotBlank()

    LaunchedEffect(messages.size, isSending) {
        val targetIndex = if (isSending) messages.size else messages.size - 1
        if (targetIndex >= 0) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    val submitDraft = {
        val clean = draftText.trim()
        if (clean.isNotEmpty() && !isSending && hasApiKey) {
            draftText = ""
            onSendQuestion(clean)
        }
    }

    Column(modifier = modifier) {
        // Шапка диалога с индикатором модели и кнопкой очистки
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isInlineMode) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = palette.surfaceElevated,
                    border = BorderStroke(1.dp, palette.outlineSubtle),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onClose)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ChevronLeft,
                            contentDescription = "Вернуться к графикам",
                            tint = palette.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Графики",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(palette.accentBlueSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = palette.accentBlue,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "ИИ-советник по базе",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ModelStatusIndicator(
                    isSending = isSending,
                    hasApiKey = hasApiKey,
                    activeModelName = activeModelName,
                    totalRecordsCount = totalRecordsCount
                )
            }

            IconButton(
                onClick = onClearChat,
                enabled = messages.isNotEmpty() && !isSending,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Очистить чат",
                    tint = if (messages.isNotEmpty() && !isSending) {
                        palette.textSecondary
                    } else {
                        palette.textMuted.copy(alpha = 0.45f)
                    },
                    modifier = Modifier.size(20.dp)
                )
            }

            if (!isInlineMode) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Закрыть чат",
                        tint = palette.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        HorizontalDivider(thickness = 0.7.dp, color = palette.outlineSubtle)

        if (!hasApiKey) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                shape = RoundedCornerShape(14.dp),
                color = palette.accentAmberSoft,
                border = BorderStroke(1.dp, palette.accentAmber.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Укажите бесплатный ключ Gemini API в настройках, чтобы спрашивать советника по базе.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onOpenSettings,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accentBlue,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Настройки", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Область переписки или приветственное состояние с быстрыми вопросами
        if (messages.isEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = palette.surfaceElevated,
                        border = BorderStroke(1.dp, palette.outlineSubtle)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = palette.accentBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Умный поиск по вашей базе ($totalRecordsCount зап.)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.textPrimary
                                )
                            }
                            Text(
                                text = "Спросите про категории, магазины, динамику за месяцы или копилки. " +
                                    "В Gemini уходит только компактная сводка и до 50 подходящих операций без текстов пушей и номеров карт.",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary
                            )
                        }
                    }
                }

                item {
                    Text(
                        text = "Быстрые вопросы",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QUICK_AI_QUESTIONS.forEach { question ->
                            QuickQuestionChip(
                                text = question,
                                enabled = hasApiKey && !isSending,
                                onClick = { onSendQuestion(question) }
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatMessageBubble(message = msg)
                }

                if (isSending) {
                    item(key = "ai_typing_indicator") {
                        ChatTypingBubble(activeModelName = activeModelName)
                    }
                }
            }

            // Компактная лента быстрых чипсов-вопросов поверх поля ввода
            if (!isSending && hasApiKey) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(QUICK_AI_QUESTIONS, key = { it }) { question ->
                        QuickQuestionChip(
                            text = question,
                            enabled = true,
                            onClick = { onSendQuestion(question) }
                        )
                    }
                }
            }
        }

        HorizontalDivider(thickness = 0.7.dp, color = palette.outlineSubtle)

        // Панель ввода вопроса
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = draftText,
                onValueChange = { draftText = it },
                enabled = !isSending,
                placeholder = {
                    Text(
                        text = if (hasApiKey) {
                            "Спросите про траты, магазины, цели…"
                        } else {
                            "Сначала добавьте ключ Gemini в настройках"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { submitDraft() }
                ),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.accentBlue,
                    unfocusedBorderColor = palette.outlineSubtle
                ),
                modifier = Modifier.weight(1f)
            )

            val canSend = draftText.trim().isNotEmpty() && !isSending && hasApiKey
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(enabled = canSend, onClick = submitDraft),
                shape = CircleShape,
                color = if (canSend) palette.accentBlue else palette.surfaceElevated,
                border = if (canSend) null else BorderStroke(1.dp, palette.outlineSubtle)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = palette.accentBlue
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.ArrowUpward,
                            contentDescription = "Отправить вопрос",
                            tint = if (canSend) Color.White else palette.textMuted,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelStatusIndicator(
    isSending: Boolean,
    hasApiKey: Boolean,
    activeModelName: String?,
    totalRecordsCount: Int
) {
    val palette = LocalFamilyPalette.current
    val dotColor = when {
        !hasApiKey -> palette.accentAmber
        isSending -> palette.accentBlue
        activeModelName != null -> palette.accentEmerald
        else -> palette.textMuted
    }
    val statusText = when {
        !hasApiKey -> "Нет ключа API • $totalRecordsCount зап."
        isSending -> "Анализ базы… (${activeModelName ?: "подбор модели"})"
        activeModelName != null -> "$activeModelName • $totalRecordsCount зап."
        else -> "Gemini автоподбор • $totalRecordsCount зап."
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun QuickQuestionChip(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalFamilyPalette.current
    Surface(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.55f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = palette.accentBlueSoft,
        border = BorderStroke(1.dp, palette.accentBlue.copy(alpha = 0.25f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = palette.accentBlue,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun ChatMessageBubble(message: AiChatMessage) {
    val palette = LocalFamilyPalette.current
    val isUser = message.role == AiChatRole.USER

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val bubbleMaxWidth = maxWidth * if (isUser) 0.84f else 0.92f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            val bubbleShape = if (isUser) {
                RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
            } else {
                RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
            }
            val bgColor = when {
                isUser -> palette.accentBlue
                message.isError -> palette.accentCoralSoft
                else -> palette.surfaceElevated
            }
            val border = when {
                isUser -> null
                message.isError -> BorderStroke(1.dp, palette.accentCoral.copy(alpha = 0.35f))
                else -> BorderStroke(1.dp, palette.outlineSubtle)
            }

            Surface(
                modifier = Modifier.widthIn(max = bubbleMaxWidth),
                shape = bubbleShape,
                color = bgColor,
                border = border
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isUser) Color.White else palette.textPrimary
                        )
                    }

                    if (!isUser) {
                        val meta = listOfNotNull(
                            message.modelUsed,
                            message.contextSummary
                        ).joinToString(" • ")
                        if (meta.isNotBlank()) {
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (message.isError) palette.accentCoral else palette.textMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatTypingBubble(activeModelName: String?) {
    val palette = LocalFamilyPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp),
            color = palette.surfaceElevated,
            border = BorderStroke(1.dp, palette.outlineSubtle)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = palette.accentBlue
                )
                Text(
                    text = if (activeModelName != null) {
                        "Ищу операции в базе и спрашиваю $activeModelName…"
                    } else {
                        "Ищу операции в базе и спрашиваю Gemini…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary
                )
            }
        }
    }
}
