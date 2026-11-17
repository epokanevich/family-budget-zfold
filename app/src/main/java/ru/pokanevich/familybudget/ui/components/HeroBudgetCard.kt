package ru.pokanevich.familybudget.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.pokanevich.familybudget.ai.AiBudgetReport
import ru.pokanevich.familybudget.ui.theme.LocalFamilyPalette
import ru.pokanevich.familybudget.ui.util.BudgetPeriod
import ru.pokanevich.familybudget.ui.util.Formatters
import kotlin.math.roundToInt

@Composable
fun HeroBudgetCard(
    report: AiBudgetReport,
    modifier: Modifier = Modifier
) {
    val palette = LocalFamilyPalette.current
    val window = report.window
    val isMonth = window.period == BudgetPeriod.MONTH
    val budget = report.monthlyBudgetRub.coerceAtLeast(1.0)
    val rawRatio = (report.totalSpentThisMonthRub / budget).toFloat().coerceIn(0f, 1f)
    val animatedFill by animateFloatAsState(
        targetValue = rawRatio,
        animationSpec = tween(durationMillis = 600),
        label = "budgetFill"
    )

    val isOverBudget = isMonth && report.totalSpentThisMonthRub > report.monthlyBudgetRub
    val isOverPace = isMonth && report.isCurrentMonth &&
        report.totalSpentThisMonthRub > report.expectedSpentByTodayRub * 1.12

    val barColor = when {
        isOverBudget -> Color(0xFFFB7185)
        isOverPace -> Color(0xFFFBBF24)
        else -> Color(0xFF34D399)
    }

    val allTimeBalance = report.allTimeBalanceRub
    val allTimeText = "Итого: ${Formatters.formatRub(allTimeBalance, showKopecksIfAny = true)}"
    val allTimeBg = if (allTimeBalance < -0.005) {
        Color(0xFFFB7185).copy(alpha = 0.28f)
    } else {
        Color(0xFF34D399).copy(alpha = 0.25f)
    }

    val periodNet = report.totalIncomeThisMonthRub - report.totalSpentThisMonthRub
    val periodNetStr = if (periodNet > 0.005) {
        "+${Formatters.formatRub(periodNet, showKopecksIfAny = true)}"
    } else {
        Formatters.formatRub(periodNet, showKopecksIfAny = true)
    }

    val eugene = report.eugeneSpentRub
    val anzhelika = report.anzhelikaSpentRub
    val coupleTotal = (eugene + anzhelika).coerceAtLeast(1.0)
    val eugeneRatio = if (eugene + anzhelika > 0.0) (eugene / coupleTotal).toFloat().coerceIn(0f, 1f) else 0.5f

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent,
        shadowElevation = if (palette.isDark) 0.dp else 4.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(palette.heroStart, palette.heroEnd)
                    )
                )
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.05f),
                    radius = size.minDimension * 0.62f,
                    center = Offset(size.width * 0.92f, size.height * 0.12f)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.04f),
                    radius = size.minDimension * 0.42f,
                    center = Offset(size.width * 0.78f, size.height * 0.95f)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Расходы • ${window.title}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(allTimeBg)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = allTimeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }

                // Big Amount Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = Formatters.formatRub(report.totalSpentThisMonthRub, showKopecksIfAny = true),
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White
                    )
                    if (isMonth) {
                        Text(
                            text = "из ${Formatters.formatRub(report.monthlyBudgetRub)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    } else {
                        Text(
                            text = "${report.totalExpenseTransactions} оп.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }

                // Progress bar (Budget pace in Month mode, Expense/Income ratio in other modes)
                if (isMonth) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White.copy(alpha = 0.18f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(animatedFill)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(barColor)
                            )
                        }
                        if (report.isCurrentMonth && report.monthProgressRatio in 0.03f..0.97f) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val markerX = size.width * report.monthProgressRatio
                                drawLine(
                                    color = Color.White.copy(alpha = 0.92f),
                                    start = Offset(markerX, 0f),
                                    end = Offset(markerX, size.height),
                                    strokeWidth = 2.dp.toPx()
                                )
                            }
                        }
                    }
                }

                if (report.upcomingRecurringReserveRub > 0.0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Резерв под будущие списания: ${Formatters.formatRub(report.upcomingRecurringReserveRub)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.90f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${report.safeDailySpendRemainingRub.roundToInt()} ₽/день",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFDE68A),
                            maxLines = 1
                        )
                    }
                }

                // Bottom frosted metric strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HeroMiniMetric(
                        label = "Доходы",
                        value = "+${Formatters.formatRub(report.totalIncomeThisMonthRub, showKopecksIfAny = true)}",
                        valueColor = Color(0xFF6EE7B7),
                        modifier = Modifier.weight(1.08f)
                    )

                    HeroMiniMetric(
                        label = "За период",
                        value = periodNetStr,
                        valueColor = if (periodNet < -0.005) Color(0xFFFDA4AF) else Color.White,
                        modifier = Modifier.weight(1f)
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(palette.eugeneColor)
                            )
                            Text(
                                text = "Е ${(eugeneRatio * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(palette.anzhelikaColor)
                            )
                            Text(
                                text = "А ${100 - (eugeneRatio * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White.copy(alpha = 0.16f))
                        ) {
                            if (eugene + anzhelika > 0.0) {
                                if (eugeneRatio > 0f) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .weight(eugeneRatio.coerceAtLeast(0.01f))
                                            .background(palette.eugeneColor)
                                    )
                                }
                                val aRatio = (1f - eugeneRatio).coerceAtLeast(0f)
                                if (aRatio > 0f) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .weight(aRatio.coerceAtLeast(0.01f))
                                            .background(palette.anzhelikaColor)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroMiniMetric(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.70f),
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = valueColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
