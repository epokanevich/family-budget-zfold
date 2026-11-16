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
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalPharmacy
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.SportsEsports
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
    ExpenseCategory.GROCERIES -> CategoryVisual(
        icon = Icons.Rounded.ShoppingBag,
        color = Color(0xFF059669),
        darkModeColor = Color(0xFF34D399)
    )
    ExpenseCategory.BABY_AND_PETS -> CategoryVisual(
        icon = Icons.Rounded.Pets,
        color = Color(0xFFDB2777),
        darkModeColor = Color(0xFFF472B6)
    )
    ExpenseCategory.CAFES_AND_DELIVERY -> CategoryVisual(
        icon = Icons.Rounded.LocalCafe,
        color = Color(0xFFD97706),
        darkModeColor = Color(0xFFFBBF24)
    )
    ExpenseCategory.HOME_AND_UTILITIES -> CategoryVisual(
        icon = Icons.Rounded.Home,
        color = Color(0xFF2563EB),
        darkModeColor = Color(0xFF60A5FA)
    )
    ExpenseCategory.TRANSPORT_AND_MOTO -> CategoryVisual(
        icon = Icons.Rounded.TwoWheeler,
        color = Color(0xFF4F46E5),
        darkModeColor = Color(0xFF818CF8)
    )
    ExpenseCategory.HEALTH_AND_PHARMACY -> CategoryVisual(
        icon = Icons.Rounded.LocalPharmacy,
        color = Color(0xFF0D9488),
        darkModeColor = Color(0xFF2DD4BF)
    )
    ExpenseCategory.LEISURE_AND_HOBBIES -> CategoryVisual(
        icon = Icons.Rounded.SportsEsports,
        color = Color(0xFF7C3AED),
        darkModeColor = Color(0xFFA78BFA)
    )
    ExpenseCategory.OTHER -> CategoryVisual(
        icon = Icons.Rounded.ReceiptLong,
        color = Color(0xFF475569),
        darkModeColor = Color(0xFF94A3B8)
    )
}

@Composable
fun CategoryIconBadge(
    category: ExpenseCategory,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    iconSize: Dp = 21.dp,
    cornerRadius: Dp = 13.dp
) {
    val palette = LocalFamilyPalette.current
    val vis = category.visual()
    val tint = if (palette.isDark) vis.darkModeColor else vis.color
    val bgAlpha = if (palette.isDark) 0.18f else 0.12f

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
