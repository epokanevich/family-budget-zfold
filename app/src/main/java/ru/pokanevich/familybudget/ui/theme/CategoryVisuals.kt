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
import ru.pokanevich.familybudget.data.ExpenseCategory

data class CategoryVisual(
    val icon: ImageVector,
    val color: Color,
    val darkModeColor: Color
)

fun ExpenseCategory.visual(): CategoryVisual = when (this) {
    // 14 категорий РАСХОДОВ с цветами и иконками 1-в-1 как на скриншотах
    ExpenseCategory.GROCERIES -> CategoryVisual(
        icon = Icons.Rounded.ShoppingBasket,
        color = Color(0xFF3A7DF0),
        darkModeColor = Color(0xFF60A5FA)
    )
    ExpenseCategory.CAFES -> CategoryVisual(
        icon = Icons.Rounded.LocalCafe,
        color = Color(0xFF9368CE),
        darkModeColor = Color(0xFFB794F4)
    )
    ExpenseCategory.LEISURE -> CategoryVisual(
        icon = Icons.Rounded.AccountBalanceWallet,
        color = Color(0xFF2FA393),
        darkModeColor = Color(0xFF4FD1C5)
    )
    ExpenseCategory.TRANSPORT -> CategoryVisual(
        icon = Icons.Rounded.DirectionsBus,
        color = Color(0xFF2C62DF),
        darkModeColor = Color(0xFF638EFF)
    )
    ExpenseCategory.WORK_FOOD -> CategoryVisual(
        icon = Icons.Rounded.Restaurant,
        color = Color(0xFF569E39),
        darkModeColor = Color(0xFF7AD659)
    )
    ExpenseCategory.HEALTH -> CategoryVisual(
        icon = Icons.Rounded.MonitorHeart,
        color = Color(0xFF39B530),
        darkModeColor = Color(0xFF85F473)
    )
    ExpenseCategory.SHOPPING -> CategoryVisual(
        icon = Icons.Rounded.ShoppingCart,
        color = Color(0xFFB85605),
        darkModeColor = Color(0xFFED8936)
    )
    ExpenseCategory.GIFTS -> CategoryVisual(
        icon = Icons.Rounded.CardGiftcard,
        color = Color(0xFFE53935),
        darkModeColor = Color(0xFFF87171)
    )
    ExpenseCategory.UTILITIES -> CategoryVisual(
        icon = Icons.Rounded.Home,
        color = Color(0xFF1E4F99),
        darkModeColor = Color(0xFF5A94EA)
    )
    ExpenseCategory.CREDIT -> CategoryVisual(
        icon = Icons.Rounded.Payments,
        color = Color(0xFF1A9AB8),
        darkModeColor = Color(0xFF38BDF8)
    )
    ExpenseCategory.PETS -> CategoryVisual(
        icon = Icons.Rounded.Pets,
        color = Color(0xFFD61980),
        darkModeColor = Color(0xFFF472B6)
    )
    ExpenseCategory.MOTO -> CategoryVisual(
        icon = Icons.Rounded.TwoWheeler,
        color = Color(0xFF9EA800),
        darkModeColor = Color(0xFFD4E100)
    )
    ExpenseCategory.BABY -> CategoryVisual(
        icon = Icons.Rounded.ChildFriendly,
        color = Color(0xFF1EB5A6),
        darkModeColor = Color(0xFF75E4D6)
    )
    ExpenseCategory.OTHER -> CategoryVisual(
        icon = Icons.Rounded.HelpOutline,
        color = Color(0xFF64748B),
        darkModeColor = Color(0xFF94A3B8)
    )

    // 4 категории ДОХОДОВ
    ExpenseCategory.INCOME_SALARY -> CategoryVisual(
        icon = Icons.Rounded.Payments,
        color = Color(0xFF2C62DF),
        darkModeColor = Color(0xFF60A5FA)
    )
    ExpenseCategory.INCOME_GIFT -> CategoryVisual(
        icon = Icons.Rounded.CardGiftcard,
        color = Color(0xFFD62877),
        darkModeColor = Color(0xFFF472B6)
    )
    ExpenseCategory.INCOME_INTEREST -> CategoryVisual(
        icon = Icons.Rounded.AccountBalance,
        color = Color(0xFF569E39),
        darkModeColor = Color(0xFF7AD659)
    )
    ExpenseCategory.INCOME_OTHER -> CategoryVisual(
        icon = Icons.Rounded.Savings,
        color = Color(0xFF059669),
        darkModeColor = Color(0xFF34D399)
    )
}

@Composable
fun CategoryIconBadge(
    category: ExpenseCategory,
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
        Icon(
            imageVector = vis.icon,
            contentDescription = category.displayName,
            tint = tint,
            modifier = Modifier.size(iconSize)
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
