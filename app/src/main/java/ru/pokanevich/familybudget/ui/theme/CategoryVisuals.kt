package ru.pokanevich.familybudget.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.ChildFriendly
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.pokanevich.familybudget.data.CategoryDescriptor

data class CategoryVisual(
    val icon: ImageVector?,
    val emoji: String,
    val color: Color,
    val darkModeColor: Color
)

private data class BuiltInVisualSpec(
    val icon: ImageVector,
    val lightHex: Long,
    val darkHex: Long
)

private val builtInVisualSpecs: Map<String, BuiltInVisualSpec> = mapOf(
    // 14 категорий РАСХОДОВ с цветами и иконками 1-в-1 как на скриншотах
    "GROCERIES" to BuiltInVisualSpec(Icons.Rounded.ShoppingBasket, 0xFF3A7DF0L, 0xFF60A5FAL),
    "CAFES" to BuiltInVisualSpec(Icons.Rounded.LocalCafe, 0xFF9368CEL, 0xFFB794F4L),
    "LEISURE" to BuiltInVisualSpec(Icons.Rounded.AccountBalanceWallet, 0xFF2FA393L, 0xFF4FD1C5L),
    "TRANSPORT" to BuiltInVisualSpec(Icons.Rounded.DirectionsBus, 0xFF2C62DFL, 0xFF638EFFL),
    "WORK_FOOD" to BuiltInVisualSpec(Icons.Rounded.Restaurant, 0xFF569E39L, 0xFF7AD659L),
    "HEALTH" to BuiltInVisualSpec(Icons.Rounded.MonitorHeart, 0xFF39B530L, 0xFF85F473L),
    "SHOPPING" to BuiltInVisualSpec(Icons.Rounded.ShoppingCart, 0xFFB85605L, 0xFFED8936L),
    "GIFTS" to BuiltInVisualSpec(Icons.Rounded.CardGiftcard, 0xFFE53935L, 0xFFF87171L),
    "UTILITIES" to BuiltInVisualSpec(Icons.Rounded.Home, 0xFF1E4F99L, 0xFF5A94EAL),
    "CREDIT" to BuiltInVisualSpec(Icons.Rounded.Payments, 0xFF1A9AB8L, 0xFF38BDF8L),
    "PETS" to BuiltInVisualSpec(Icons.Rounded.Pets, 0xFFD61980L, 0xFFF472B6L),
    "MOTO" to BuiltInVisualSpec(Icons.Rounded.TwoWheeler, 0xFF9EA800L, 0xFFD4E100L),
    "BABY" to BuiltInVisualSpec(Icons.Rounded.ChildFriendly, 0xFF1EB5A6L, 0xFF75E4D6L),
    "OTHER" to BuiltInVisualSpec(Icons.Rounded.HelpOutline, 0xFF64748BL, 0xFF94A3B8L),
    // 4 категории ДОХОДОВ
    "INCOME_SALARY" to BuiltInVisualSpec(Icons.Rounded.Payments, 0xFF2C62DFL, 0xFF60A5FAL),
    "INCOME_GIFT" to BuiltInVisualSpec(Icons.Rounded.CardGiftcard, 0xFFD62877L, 0xFFF472B6L),
    "INCOME_INTEREST" to BuiltInVisualSpec(Icons.Rounded.AccountBalance, 0xFF569E39L, 0xFF7AD659L),
    "INCOME_OTHER" to BuiltInVisualSpec(Icons.Rounded.Savings, 0xFF059669L, 0xFF34D399L)
)

private fun normalizeOpaqueHex(colorHex: Long): Long =
    if ((colorHex ushr 24) == 0L) colorHex or 0xFF000000L else colorHex

private fun lightenForDarkMode(base: Color): Color {
    val luma = 0.299f * base.red + 0.587f * base.green + 0.114f * base.blue
    if (luma >= 0.55f) return base
    return Color(
        red = base.red + (1f - base.red) * 0.28f,
        green = base.green + (1f - base.green) * 0.28f,
        blue = base.blue + (1f - base.blue) * 0.28f,
        alpha = base.alpha
    )
}

fun CategoryDescriptor.visual(): CategoryVisual {
    val safeHex = normalizeOpaqueHex(colorHex)
    val baseColor = Color(safeHex)
    val builtIn = if (isBuiltIn) builtInVisualSpecs[id] else null
    if (builtIn != null) {
        val darkColor = if (safeHex == builtIn.lightHex) {
            Color(builtIn.darkHex)
        } else {
            lightenForDarkMode(baseColor)
        }
        return CategoryVisual(
            icon = builtIn.icon,
            emoji = emoji,
            color = baseColor,
            darkModeColor = darkColor
        )
    }
    return CategoryVisual(
        icon = null,
        emoji = emoji.ifBlank { "❓" },
        color = baseColor,
        darkModeColor = lightenForDarkMode(baseColor)
    )
}

@Composable
fun CategorySymbol(
    visual: CategoryVisual,
    contentDescription: String?,
    tint: Color,
    iconSize: Dp
) {
    if (visual.icon != null) {
        Icon(
            imageVector = visual.icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    } else {
        Text(
            text = visual.emoji.ifBlank { "❓" },
            fontSize = (iconSize.value * 0.9f).sp
        )
    }
}

@Composable
fun CategoryIconBadge(
    category: CategoryDescriptor,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    iconSize: Dp = 21.dp,
    cornerRadius: Dp = 14.dp
) {
    val palette = LocalFamilyPalette.current
    val vis = category.visual()
    val tint = if (palette.isDark) vis.darkModeColor else vis.color
    val bgAlpha = if (palette.isDark) 0.18f else 0.13f

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(tint.copy(alpha = bgAlpha)),
        contentAlignment = Alignment.Center
    ) {
        CategorySymbol(
            visual = vis,
            contentDescription = category.displayName,
            tint = tint,
            iconSize = iconSize
        )
    }
}

@Composable
fun AuthorAvatarCircle(
    authorName: String,
    size: Dp = 28.dp
) {
    val palette = LocalFamilyPalette.current
    val isAnzhelika = authorName.equals("Анжелика", ignoreCase = true)
    val fg = if (isAnzhelika) palette.anzhelikaColor else palette.eugeneColor
    val bg = if (isAnzhelika) palette.anzhelikaSoft else palette.eugeneSoft
    val initial = if (isAnzhelika) "А" else "Е"

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = fg,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.45f).sp
        )
    }
}

@Composable
fun AuthorPill(authorName: String) {
    val palette = LocalFamilyPalette.current
    val isAnzhelika = authorName.equals("Анжелика", ignoreCase = true)
    val fg = if (isAnzhelika) palette.anzhelikaColor else palette.eugeneColor
    val bg = if (isAnzhelika) palette.anzhelikaSoft else palette.eugeneSoft

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(fg)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = authorName,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.SemiBold
        )
    }
}
