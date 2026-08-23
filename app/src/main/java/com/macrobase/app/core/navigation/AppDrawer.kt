package com.macrobase.app.core.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions

/**
 * 280dp Navigation Drawer matching UI_UX_SPECIFICATION.md
 */
@Composable
fun AppDrawer(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(Dimensions.DrawerWidth)
            .fillMaxHeight()
            .background(AppColors.Surface)
            .verticalScroll(rememberScrollState())
    ) {
        // Solid Green Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(AppColors.Primary)
                .padding(AppSpacing.lg),
            contentAlignment = Alignment.BottomStart
        ) {
            Column {
                Text(
                    text = "MacroBase",
                    style = AppTypography.Header1,
                    color = AppColors.TextPrimary
                )
                Text(
                    text = "Offline Nutrition Tracker v1.0",
                    style = AppTypography.Caption,
                    color = AppColors.TextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.sm))

        // Drawer Menu Items
        Screen.drawerScreens.forEach { screen ->
            val isSelected = currentRoute?.substringBefore("?") == screen.route.substringBefore("?")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(if (isSelected) AppColors.SurfaceAlt else AppColors.Surface)
                    .clickable { onNavigate(screen) }
                    .padding(horizontal = AppSpacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (screen.icon != null) {
                    Icon(
                        imageVector = screen.icon,
                        contentDescription = null,
                        tint = if (isSelected) AppColors.Primary else AppColors.TextSecondary,
                        modifier = Modifier.size(Dimensions.IconSizeMedium)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.lg))
                }
                Text(
                    text = screen.title,
                    style = AppTypography.Body1,
                    color = if (isSelected) AppColors.TextPrimary else AppColors.TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)

        // Footer App Branding
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.lg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "MacroBase • 100% Offline",
                style = AppTypography.Caption,
                color = AppColors.TextMuted
            )
        }
    }
}
