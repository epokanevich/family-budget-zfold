package ru.pokanevich.familybudget.ui.sheets

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.ui.theme.AuthorAvatarCircle
import ru.pokanevich.familybudget.ui.theme.CategoryIconBadge
import ru.pokanevich.familybudget.ui.theme.LocalFamilyPalette
import ru.pokanevich.familybudget.ui.theme.visual
import kotlin.math.roundToInt

private val quickMerchants = listOf(
    "Пятёрочка", "ВкусВилл", "Кофе", "Аптека", "АЗС", "Ozon", "Детский мир"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditExpenseSheet(
    editingExpense: ExpenseEntity? = null,
    initialAmount: String = "",
    initialMerchant: String = "",
    defaultAuthorName: String,
    onOpenQrScanner: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (amountRub: Double, merchant: String, category: ExpenseCategory, authorName: String) -> Unit
) {
    val palette = LocalFamilyPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val startAmount = when {
        editingExpense != null -> {
            val v = editingExpense.amountRub
            if (v % 1.0 == 0.0) v.roundToInt().toString() else v.toString()
        }
        else -> initialAmount
    }
    val startMerchant = editingExpense?.merchant ?: initialMerchant
    val startCategory = editingExpense?.category ?: ExpenseCategory.guessFromMerchant(startMerchant)
    val startAuthor = editingExpense?.authorName ?: defaultAuthorName

    var amountText by remember(editingExpense, initialAmount) { mutableStateOf(startAmount) }
    var merchantText by remember(editingExpense, initialMerchant) { mutableStateOf(startMerchant) }
    var selectedCategory by remember(editingExpense, initialMerchant) { mutableStateOf(startCategory) }
    var selectedAuthor by remember(editingExpense, defaultAuthorName) { mutableStateOf(startAuthor) }

    val parsedAmount = amountText.replace(",", ".").replace(" ", "").toDoubleOrNull() ?: 0.0
    val isValid = parsedAmount > 0.0

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
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (editingExpense == null) "Новая трата" else "Редактировать трату",
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary
                )
                if (editingExpense == null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = palette.accentBlueSoft,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onOpenQrScanner)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.QrCodeScanner,
                                contentDescription = "QR чека",
                                tint = palette.accentBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Скан чека",
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.accentBlue,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Giant Centered Amount Input (Splitwise / Splittr / Tricount style)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = palette.surfaceElevated,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isValid) palette.accentEmerald.copy(alpha = 0.45f) else palette.outlineSubtle
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp, horizontal = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (amountText.isEmpty()) {
                                Text(
                                    text = "0",
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = palette.textMuted
                                )
                            }
                            BasicTextField(
                                value = amountText,
                                onValueChange = { raw ->
                                    val filtered = raw.filter { it.isDigit() || it == '.' || it == ',' }
                                    if (filtered.count { it == '.' || it == ',' } <= 1) {
                                        amountText = filtered
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                cursorBrush = SolidColor(palette.accentEmerald),
                                textStyle = TextStyle(
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = palette.textPrimary,
                                    textAlign = TextAlign.Center
                                ),
                                modifier = Modifier.width(190.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "₽",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.accentEmerald
                        )
                    }

                    // Quick +100 / +500 / +1000 chips
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(100, 500, 1000).forEach { addVal ->
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = palette.surfaceCard,
                                border = BorderStroke(1.dp, palette.outlineSubtle),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .clickable {
                                        val next = parsedAmount + addVal
                                        amountText = if (next % 1.0 == 0.0) {
                                            next.roundToInt().toString()
                                        } else {
                                            next.toString()
                                        }
                                    }
                            ) {
                                Text(
                                    text = "+$addVal ₽",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.textSecondary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Payer Toggle (Splitwise / Tricount style)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Кто оплатил",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listOf("Евгений", "Анжелика").forEach { person ->
                        val selected = selectedAuthor.equals(person, ignoreCase = true)
                        val isAnzhelika = person == "Анжелика"
                        val accent = if (isAnzhelika) palette.anzhelikaColor else palette.eugeneColor
                        val soft = if (isAnzhelika) palette.anzhelikaSoft else palette.eugeneSoft

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { selectedAuthor = person },
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) soft else palette.surfaceElevated,
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

            // Merchant Field + Quick Chips
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = merchantText,
                    onValueChange = {
                        merchantText = it
                        if (editingExpense == null) {
                            val guessed = ExpenseCategory.guessFromMerchant(it)
                            if (guessed != ExpenseCategory.OTHER) {
                                selectedCategory = guessed
                            }
                        }
                    },
                    label = { Text("Магазин или описание") },
                    placeholder = { Text("Например, Пятёрочка") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accentEmerald,
                        unfocusedBorderColor = palette.outlineSubtle
                    ),
                    trailingIcon = {
                        if (editingExpense == null) {
                            IconButton(onClick = onOpenQrScanner) {
                                Icon(
                                    imageVector = Icons.Rounded.QrCodeScanner,
                                    contentDescription = "Сканировать QR",
                                    tint = palette.accentBlue
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (editingExpense == null && merchantText.isBlank()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(quickMerchants) { sample ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = palette.surfaceElevated,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        merchantText = sample
                                        val guessed = ExpenseCategory.guessFromMerchant(sample)
                                        if (guessed != ExpenseCategory.OTHER) {
                                            selectedCategory = guessed
                                        }
                                    }
                            ) {
                                Text(
                                    text = sample,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 4x2 Visual Category Grid
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Категория",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary
                )
                ExpenseCategory.entries.chunked(4).forEach { rowCats ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowCats.forEach { cat ->
                            val selected = selectedCategory == cat
                            val vis = cat.visual()
                            val tint = if (palette.isDark) vis.darkModeColor else vis.color

                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { selectedCategory = cat },
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) tint.copy(alpha = 0.14f) else palette.surfaceElevated,
                                border = if (selected) BorderStroke(1.5.dp, tint) else null
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CategoryIconBadge(
                                        category = cat,
                                        size = 34.dp,
                                        iconSize = 18.dp,
                                        cornerRadius = 10.dp
                                    )
                                    Text(
                                        text = cat.shortName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) tint else palette.textSecondary,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Primary Submit Button
            Button(
                enabled = isValid,
                onClick = {
                    onSave(
                        parsedAmount,
                        merchantText.trim().ifBlank { selectedCategory.shortName },
                        selectedCategory,
                        selectedAuthor
                    )
                },
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 15.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accentEmerald,
                    contentColor = Color.White,
                    disabledContainerColor = palette.surfaceElevated,
                    disabledContentColor = palette.textMuted
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = if (editingExpense == null) "Добавить в бюджет" else "Сохранить изменения",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
