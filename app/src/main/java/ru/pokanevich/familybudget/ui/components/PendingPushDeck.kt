package ru.pokanevich.familybudget.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.pokanevich.familybudget.data.CategoryRegistry
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.RecurringPaymentScheduler
import ru.pokanevich.familybudget.ui.theme.CategorySymbol
import ru.pokanevich.familybudget.ui.theme.LocalFamilyPalette
import ru.pokanevich.familybudget.ui.theme.visual
import ru.pokanevich.familybudget.ui.util.Formatters

@Composable
fun PushPermissionBanner(
    onOpenPushSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFamilyPalette.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = palette.surfaceCard,
        border = BorderStroke(1.dp, palette.accentBlue.copy(alpha = 0.28f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.accentBlueSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.NotificationsActive,
                    contentDescription = null,
                    tint = palette.accentBlue,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Автоучёт пушей и SMS",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary
                )
                Text(
                    text = "Включите доступ к пушам. Выбор банков — в настройках этого телефона.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary
                )
            }

            Button(
                onClick = onOpenPushSettings,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accentBlue,
                    contentColor = Color.White
                )
            ) {
                Text("Включить", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun BatteryExemptionBanner(
    onRequestBatteryExemption: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFamilyPalette.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = palette.surfaceCard,
        border = BorderStroke(1.dp, palette.accentAmber.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.accentAmberSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.BatterySaver,
                    contentDescription = null,
                    tint = palette.accentAmber,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Работа пушей в фоне (когда закрыто)",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary
                )
                Text(
                    text = "Разрешите работу без ограничений батареи, чтобы Samsung не усыплял перехват банковских пушей.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary
                )
            }

            Button(
                onClick = onRequestBatteryExemption,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accentAmber,
                    contentColor = Color.White
                )
            ) {
                Text("Разрешить", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun PendingPushCard(
    expense: ExpenseEntity,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    registry: CategoryRegistry = CategoryRegistry.DEFAULT
) {
    val palette = LocalFamilyPalette.current
    var selectedCategoryId by remember(expense.id) {
        mutableStateOf(registry.resolve(expense.categoryName).id)
    }
    val isMirPay = expense.sourceType == "PUSH_MIRPAY"
    val isRecurring = expense.sourceType == RecurringPaymentScheduler.SOURCE_TYPE_RECURRING
    val isIncome = expense.isIncome(registry)
    val isTransfer = expense.isTransfer(registry)
    val bankBg = when {
        isRecurring -> palette.accentAmberSoft
        expense.sourceType == "PUSH_TBANK" -> palette.accentAmberSoft
        expense.sourceType == "PUSH_ALFA" -> palette.accentCoralSoft
        isMirPay || expense.sourceType == "PUSH_VTB" || expense.sourceType == "PUSH_OZON" -> palette.accentBlueSoft
        else -> palette.accentEmeraldSoft
    }
    val bankFg = when {
        isRecurring -> palette.accentAmber
        expense.sourceType == "PUSH_TBANK" -> palette.accentAmber
        expense.sourceType == "PUSH_ALFA" -> palette.accentCoral
        isMirPay || expense.sourceType == "PUSH_VTB" || expense.sourceType == "PUSH_OZON" -> palette.accentBlue
        else -> palette.accentEmerald
    }
    val bankLabel = when {
        isRecurring -> "Регулярный"
        isMirPay -> "Mir Pay"
        expense.sourceType == "PUSH_TBANK" -> "Т-Банк"
        expense.sourceType == "PUSH_ALFA" -> "Альфа-Банк"
        expense.sourceType == "PUSH_VTB" -> "ВТБ"
        expense.sourceType == "PUSH_OZON" -> "Ozon Банк"
        else -> "СберБанк"
    }
    val chipCategories = if (isIncome) registry.incomeCategories else registry.expenseCategories

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = palette.surfaceCard,
        border = BorderStroke(1.2.dp, bankFg.copy(alpha = 0.45f)),
        shadowElevation = if (palette.isDark) 0.dp else 2.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(bankBg)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = bankLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = bankFg,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = Formatters.formatTimeOnly(expense.createdAtEpochMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textMuted
                    )
                }

                val formattedAmount = Formatters.formatRub(expense.amountRub, showKopecksIfAny = true)
                Text(
                    text = if (isIncome) "+$formattedAmount" else formattedAmount,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isIncome) palette.accentEmerald else palette.textPrimary
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = expense.merchant,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isRecurring && expense.note.isNotBlank()) {
                    Text(
                        text = expense.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (isTransfer) {
                Text(
                    text = "Перевод между своими счетами (не учитывается в расходах)",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.accentBlue
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(chipCategories, key = { it.id }) { cat ->
                        val vis = cat.visual()
                        val tint = if (palette.isDark) vis.darkModeColor else vis.color
                        val selected = selectedCategoryId == cat.id

                        Surface(
                            shape = RoundedCornerShape(11.dp),
                            color = if (selected) tint.copy(alpha = 0.16f) else palette.surfaceElevated,
                            border = if (selected) BorderStroke(1.2.dp, tint) else null,
                            modifier = Modifier
                                .clip(RoundedCornerShape(11.dp))
                                .clickable { selectedCategoryId = cat.id }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                CategorySymbol(
                                    visual = vis,
                                    contentDescription = null,
                                    tint = if (selected) tint else palette.textSecondary,
                                    iconSize = 15.dp
                                )
                                Text(
                                    text = cat.shortName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) tint else palette.textSecondary,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Пропустить",
                        color = palette.textSecondary,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Button(
                    onClick = { onConfirm(selectedCategoryId) },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accentEmerald,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "В бюджет",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
