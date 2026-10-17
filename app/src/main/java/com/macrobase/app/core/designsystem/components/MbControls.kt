package com.macrobase.app.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macrobase.app.core.designsystem.AppBrushes
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.core.designsystem.Ph

/** C3 button kinds. */
enum class MbButtonKind { Primary, Secondary, Danger }

/**
 * C3 Button: a pill at least 48 dp tall. Primary is the green gradient with dark ink, Secondary is
 * outlined, Danger is the red fill. Disabled buttons fade to 42 %.
 */
@Composable
fun MbButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: MbButtonKind = MbButtonKind.Primary,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    minHeight: Dp = Dimensions.ButtonHeightMentor
) {
    val shape = AppShapes.Button
    val ink = when (kind) {
        MbButtonKind.Primary -> AppColors.OnPrimary
        MbButtonKind.Secondary -> AppColors.TextPrimary
        MbButtonKind.Danger -> AppColors.OnDanger
    }
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .alpha(if (enabled) 1f else 0.42f)
            .mbClickable(enabled = enabled, onClick = onClick)
            .clip(shape)
            .then(
                when (kind) {
                    MbButtonKind.Primary -> Modifier.background(AppBrushes.PrimaryButtonGradient).innerTopHighlight()
                    MbButtonKind.Secondary -> Modifier.border(1.dp, AppColors.DividerStrong, shape)
                    MbButtonKind.Danger -> Modifier.background(AppColors.DangerFill)
                }
            )
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            MbIcon(icon = icon, contentDescription = null, size = 18.dp, tint = ink)
        }
        Text(text = text, style = AppTypography.Button, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** C3 Danger-ghost: a 48 dp outlined circle with a red trash icon. */
@Composable
fun MbDangerGhostButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .mbClickable(enabled = enabled, onClick = onClick)
            .clip(CircleShape)
            .background(AppColors.Background)
            .border(1.dp, AppColors.DividerStrong, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        MbIcon(icon = Ph.Trash.light, contentDescription = contentDescription, size = 20.dp, tint = AppColors.Error)
    }
}

/**
 * A round icon button. Plain ones are tinted secondary; [outlined] ones (header buttons) get the
 * faint fill and the Edge-2 ring.
 */
@Composable
fun MbIconButton(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Dimensions.MinTouchTarget,
    iconSize: Dp = 21.dp,
    tint: Color = AppColors.TextSecondary,
    outlined: Boolean = false,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.35f)
            .mbClickable(enabled = enabled, pressedScale = 0.94f, onClickLabel = null, onClick = onClick)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .clip(CircleShape)
            .then(
                if (outlined) Modifier.background(Color(0x05FFFFFF)).border(1.dp, AppColors.Edge2, CircleShape)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        MbIcon(icon = icon, contentDescription = null, size = iconSize, tint = if (outlined) AppColors.TextPrimary else tint)
    }
}

/** C4 Chip: a 40 dp pill, outlined, or green-tinted when selected. */
@Composable
fun MbChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    role: Role = Role.RadioButton
) {
    val shape = AppShapes.PillButton
    val ink = if (selected) AppColors.ChipOnInk else AppColors.ChipInk
    Row(
        modifier = modifier
            .height(40.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .mbSelectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
            .clip(shape)
            .background(if (selected) AppColors.ChipOnFill else Color.Transparent)
            .border(1.dp, if (selected) AppColors.ChipOnBorder else AppColors.ChipBorder, shape)
            .padding(horizontal = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            MbIcon(icon = icon, contentDescription = null, size = 16.dp, tint = ink)
        }
        Text(text = text, style = AppTypography.Chip, color = ink, maxLines = 1)
    }
}

/** A row of chips that scrolls sideways. */
@Composable
fun MbChipRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * C5 Text field: label above, a rounded field with a green focus border and ring, an optional unit
 * [suffix] inside at the right, and help or error text below.
 */
@Composable
fun MbTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    suffix: String? = null,
    helpText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    @DrawableRes leadingIcon: Int? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val isError = errorText != null
    val shape = AppShapes.InputBox
    val borderColor = when {
        isError -> AppColors.Error
        focused -> AppColors.Primary
        else -> AppColors.FieldBorder
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) {
            Text(text = label, style = AppTypography.Body2.copy(fontSize = 13.sp), color = AppColors.TextSecondary)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = AppTypography.Body1.copy(color = AppColors.TextPrimary),
            cursorBrush = SolidColor(AppColors.Primary),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    if (label != null) contentDescription = label
                    if (errorText != null) error(errorText)
                },
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .drawBehind {
                            if (focused && !isError) {
                                val ring = 3.dp.toPx()
                                drawRoundRect(
                                    color = AppColors.FieldRing,
                                    topLeft = Offset(-ring / 2f, -ring / 2f),
                                    size = Size(size.width + ring, size.height + ring),
                                    cornerRadius = CornerRadius(14.dp.toPx() + ring / 2f),
                                    style = Stroke(width = ring)
                                )
                            }
                        }
                        .clip(shape)
                        .background(AppColors.FieldFill)
                        .border(1.dp, borderColor, shape)
                        .alpha(if (enabled) 1f else 0.5f)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (leadingIcon != null) {
                        MbIcon(icon = leadingIcon, contentDescription = null, size = 18.dp, tint = AppColors.TextMuted)
                    }
                    Box(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(text = placeholder, style = AppTypography.Body1, color = AppColors.TextMuted, maxLines = 1)
                        }
                        innerTextField()
                    }
                    if (suffix != null) {
                        Text(text = suffix, style = AppTypography.Body2, color = AppColors.TextSecondary)
                    }
                    trailing?.invoke()
                }
            }
        )
        when {
            errorText != null -> Text(text = errorText, style = RowSubtitleStyle, color = AppColors.Error)
            helpText != null -> Text(text = helpText, style = RowSubtitleStyle, color = AppColors.TextMuted)
        }
    }
}

/** C7 The round gradient "+" button above the bar's right end. */
@Composable
fun MbFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Add"
) {
    Box(
        modifier = modifier
            .size(Dimensions.Fab)
            .mbClickable(pressedScale = 0.92f, onClick = onClick)
            .shadow(
                elevation = 12.dp,
                shape = CircleShape,
                ambientColor = Color(0x8C50D26E),
                spotColor = Color(0x8C50D26E)
            )
            .clip(CircleShape)
            .background(AppBrushes.FabGradient)
            .innerTopHighlight(Color(0x66FFFFFF)),
        contentAlignment = Alignment.Center
    ) {
        MbIcon(icon = Ph.Plus.regular, contentDescription = contentDescription, size = 26.dp, tint = AppColors.FabInk)
    }
}
