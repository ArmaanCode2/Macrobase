package com.macrobase.app.feature.basket

import com.macrobase.app.feature.detail.steppedQuantity
import com.macrobase.app.feature.detail.QuantityStepper
import com.macrobase.app.feature.detail.MacroTiles
import com.macrobase.app.core.navigation.ReportContentScrolled
import com.macrobase.app.core.designsystem.components.rememberIsScrolled
import com.macrobase.app.core.designsystem.components.MbMotion
import com.macrobase.app.core.designsystem.components.MbButtonKind
import com.macrobase.app.core.designsystem.components.MbButton
import com.macrobase.app.core.designsystem.components.MbIconCircle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.ColumnScope
import com.macrobase.app.core.designsystem.components.mbClickable
import com.macrobase.app.core.designsystem.components.mealLabel
import com.macrobase.app.core.designsystem.components.MbIconButton
import com.macrobase.app.core.designsystem.components.MbIcon
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.Ph
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.macrobase.app.feature.detail.formatQuantityForInput
import com.macrobase.app.feature.detail.parseQuantityInput
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.core.designsystem.components.MealAndDayPicker
import com.macrobase.app.core.designsystem.components.PrimaryButton
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.basket.BasketItem
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** "Log 1 food" or "Log 3 foods". */
internal fun basketLogLabel(count: Int): String = if (count == 1) "Log 1 food" else "Log $count foods"

/**
 * The When table's header for the basket, in sentence case: "Log to Breakfast, Today", or which
 * part is mixed when the items differ.
 */
internal fun basketWhenHeader(date: LocalDate?, mealType: MealType?, today: LocalDate): String {
    val day = date?.let { basketDateLabel(it, today) }
    val meal = mealType?.let { mealLabel(it) }
    return when {
        day != null && meal != null -> "Log to $meal, $day"
        day == null && meal == null -> "Mixed (see each item)"
        else -> "${day ?: "Mixed days"}, ${meal ?: "mixed meals"}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasketScreen(
    viewModel: BasketViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToDashboard: () -> Unit,
    onEditItem: (String, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val items by viewModel.items.collectAsState()
    val commonDate by viewModel.commonDate.collectAsState()
    val commonMealType by viewModel.commonMealType.collectAsState()
    val isMixedDateOrMeal by viewModel.isMixedDateOrMeal.collectAsState()
    val revealedItemId by viewModel.revealedItemId.collectAsState()
    val invalidQuantityIds by viewModel.invalidQuantityIds.collectAsState()
    val isSubmitting by viewModel.isSubmitting.collectAsState()
    val submitError by viewModel.submitError.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AppSpacing.xl),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MbIconCircle(icon = Ph.Basket.light, size = 56.dp, iconSize = 26.dp)
                    Spacer(modifier = Modifier.height(AppSpacing.lg))
                    Text("Your basket is empty.", style = AppTypography.Header2, color = AppColors.TextSecondary)
                    Spacer(modifier = Modifier.height(AppSpacing.xl))
                    MbButton(text = "Add food", onClick = onNavigateToSearch, icon = Ph.Plus.regular)
                }
            }
        } else {
            val scrollState = rememberScrollState()
            ReportContentScrolled(scrollState.rememberIsScrolled())
            val today = LocalDate.now()

            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = AppSpacing.screenMargin)
                ) {
                    // 1. Swipe left to delete hint
                    Text(
                        text = "Swipe left to delete",
                        style = AppTypography.Caption,
                        color = AppColors.TextMuted,
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                    )

                    // 2. Pending Food Items (Bulk Inline Editors)
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.rowGap)) {
                        items.forEach { item ->
                            androidx.compose.runtime.key(item.id) {
                                BasketItemRow(
                                    item = item,
                                    isRevealed = revealedItemId == item.id,
                                    onRevealChange = { isRevealed ->
                                        viewModel.setRevealedItem(if (isRevealed) item.id else null)
                                    },
                                    onQuantityChange = { newQtyText ->
                                        viewModel.onQuantityChange(item.id, newQtyText)
                                    },
                                    onServingChange = { newServing ->
                                        viewModel.onServingChange(item.id, newServing)
                                    },
                                    onInfoClick = {
                                        onEditItem(item.id, item.food.id)
                                    },
                                    onDelete = {
                                        viewModel.removeItem(item.id)
                                    },
                                    isQuantityInvalid = item.id in invalidQuantityIds,
                                    onQuantityTextReset = { viewModel.onQuantityTextReset(item.id) },
                                    whenLabel = if (isMixedDateOrMeal) basketWhenHeader(item.date, item.mealType, today) else null
                                )
                            }
                        }
                    }

                    // 3. Total calories and macros
                    val totalCalories = items.sumOf { it.calculatedNutrition.calories }
                    val totalProtein = items.sumOf { it.calculatedNutrition.proteinGrams }
                    val totalCarbs = items.sumOf { it.calculatedNutrition.carbsGrams }
                    val totalFat = items.sumOf { it.calculatedNutrition.fatGrams }

                    BasketSection("Total") {
                        Row {
                            Text(
                                text = "${totalCalories.roundToInt()}",
                                style = AppTypography.BigStat,
                                color = AppColors.TextPrimary,
                                modifier = Modifier.alignByBaseline()
                            )
                            Text(
                                text = "kcal",
                                style = AppTypography.HeroUnit,
                                modifier = Modifier
                                    .alignByBaseline()
                                    .padding(start = 8.dp)
                            )
                        }
                        MacroTiles(
                            protein = totalProtein,
                            carbs = totalCarbs,
                            fat = totalFat,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }

                    // 4. When: one meal and day for every item; "mixed" while they differ (BUG-039)
                    BasketSection("When") {
                        MealAndDayPicker(
                            selectedMeal = commonMealType,
                            selectedDate = commonDate,
                            today = today,
                            onMealSelected = { viewModel.setCommonMealType(it) },
                            onDateSelected = { viewModel.setCommonDate(it) },
                            header = basketWhenHeader(commonDate, commonMealType, today)
                        )
                    }

                    // Room for the pinned action bar
                    Spacer(modifier = Modifier.height(112.dp))
                }

                // 5. Clear and "Log N foods", pinned at the bottom over a short fade
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.18f to AppColors.Background, 1f to AppColors.Background))
                        .padding(start = AppSpacing.screenMargin, end = AppSpacing.screenMargin, top = 16.dp, bottom = 12.dp)
                ) {
                    val logBlockedMessage = if (invalidQuantityIds.isNotEmpty()) {
                        "Enter an amount above 0 for the highlighted items."
                    } else {
                        submitError
                    }
                    logBlockedMessage?.let { error ->
                        Text(
                            error,
                            style = AppTypography.Body2,
                            color = AppColors.Error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MbButton(text = "Clear", kind = MbButtonKind.Secondary, onClick = { viewModel.clearBasket() })
                        MbButton(
                            text = basketLogLabel(items.size),
                            onClick = {
                                // A failure shows above the button through submitError
                                viewModel.submitBasket(onSuccess = onNavigateToDashboard)
                            },
                            enabled = !isSubmitting && invalidQuantityIds.isEmpty(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** A titled block of the basket ("Total", "When"). */
@Composable
private fun BasketSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.sectionGap)) {
        Text(
            text = title,
            style = AppTypography.Header2,
            color = AppColors.TextPrimary,
            modifier = Modifier
                .padding(bottom = 10.dp)
                .semantics { heading() }
        )
        content()
    }
}

@Composable
fun BasketItemRow(
    item: BasketItem,
    isRevealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    onQuantityChange: (String) -> Unit,
    onServingChange: (Serving) -> Unit,
    onInfoClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    isQuantityInvalid: Boolean = false,
    onQuantityTextReset: () -> Unit = {},
    /** The item's own day and meal, shown when the basket is mixed (BUG-039). */
    whenLabel: String? = null
) {
    val density = LocalDensity.current
    val deleteActionWidth = 80.dp
    val deleteActionWidthPx = with(density) { deleteActionWidth.toPx() }
    val offsetX = remember(item.id) { Animatable(if (isRevealed) -deleteActionWidthPx else 0f) }
    val coroutineScope = rememberCoroutineScope()
    val snapSpec = tween<Float>(durationMillis = MbMotion.SwipeMs, easing = MbMotion.Ease)
    val isOpen by remember { derivedStateOf { offsetX.value < 0f } }

    var showServingMenu by remember { mutableStateOf(false) }
    // Exact quantity (0.25 stays "0.25"); the typed text is only replaced when the quantity
    // changes from outside this field, so partial input like "0.0" is never reset mid-typing
    var quantityInputText by remember(item.id) { mutableStateOf(formatQuantityForInput(item.quantity)) }
    // A recreated field shows the stored amount, so an error for text typed earlier no longer applies
    LaunchedEffect(item.id) { onQuantityTextReset() }
    LaunchedEffect(item.quantity) {
        if (parseQuantityInput(quantityInputText) != item.quantity) {
            quantityInputText = formatQuantityForInput(item.quantity)
            onQuantityTextReset()
        }
    }

    LaunchedEffect(isRevealed) {
        val target = if (isRevealed) -deleteActionWidthPx else 0f
        if (kotlin.math.abs(offsetX.value - target) > 0.5f) {
            offsetX.animateTo(target, animationSpec = snapSpec)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(16.dp))
    ) {
        // Delete under the row, shown only while it is open or being swiped
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(deleteActionWidth)
                .fillMaxHeight()
                .graphicsLayer { alpha = if (offsetX.value < 0f) 1f else 0f }
                .background(AppColors.DangerFill)
                .clickable {
                    coroutineScope.launch {
                        offsetX.snapTo(0f)
                        onRevealChange(false)
                        onDelete()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Delete",
                style = AppTypography.Body1.copy(fontSize = 13.5.sp),
                color = AppColors.OnCopy
            )
        }

        // Swipable Foreground Row
        val rowShape = if (isOpen) RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp) else RoundedCornerShape(16.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .clip(rowShape)
                .background(AppColors.RowTile)
                .border(1.dp, AppColors.Edge, rowShape)
                .pointerInput(item.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { },
                        onDragEnd = {
                            coroutineScope.launch {
                                if (offsetX.value < -deleteActionWidthPx * 0.4f) {
                                    offsetX.animateTo(-deleteActionWidthPx, animationSpec = snapSpec)
                                    onRevealChange(true)
                                } else {
                                    offsetX.animateTo(0f, animationSpec = snapSpec)
                                    onRevealChange(false)
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                offsetX.animateTo(if (isRevealed) -deleteActionWidthPx else 0f, animationSpec = snapSpec)
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            coroutineScope.launch {
                                val newOffset = (offsetX.value + dragAmount).coerceIn(-deleteActionWidthPx, 0f)
                                offsetX.snapTo(newOffset)
                            }
                        }
                    )
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (isRevealed || offsetX.value < -10f) {
                        coroutineScope.launch {
                            offsetX.animateTo(0f, animationSpec = snapSpec)
                            onRevealChange(false)
                        }
                    }
                }
                .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
        ) {
            // Line 1: icon, name with "qty x serving" (and its own day and meal when mixed), calories
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MbIconCircle(icon = Ph.BowlFood.light, size = 34.dp, iconSize = 17.dp)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = item.foodNameSnapshot,
                        style = AppTypography.Header3,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${formatQuantityForInput(item.quantity)} \u00D7 ${item.food.servingLabel(item.serving)}",
                        style = AppTypography.Body2.copy(fontSize = 12.5.sp),
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Line 3, mixed basket only: where this item will be logged
                    if (whenLabel != null) {
                        Text(text = whenLabel, style = AppTypography.Caption, color = AppColors.TextMuted)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${item.calculatedNutrition.calories.roundToInt()}",
                        style = AppTypography.Body1,
                        color = AppColors.TextPrimary
                    )
                    Text(text = "kcal", style = AppTypography.Caption, color = AppColors.TextMuted)
                }
            }

            // Line 2: the amount stepper, the serving, and the item's full detail
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuantityStepper(
                    text = quantityInputText,
                    isError = isQuantityInvalid,
                    onTextChange = { newText ->
                        quantityInputText = newText
                        onQuantityChange(newText)
                    },
                    onStep = { delta ->
                        val current = parseQuantityInput(quantityInputText) ?: item.quantity
                        val newText = formatQuantityForInput(steppedQuantity(current, delta))
                        quantityInputText = newText
                        onQuantityChange(newText)
                    }
                )

                val servingDisplay = extractUnitDescription(item.serving.description)
                Box(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .border(1.dp, AppColors.ChipBorder, CircleShape)
                            .mbClickable(pressedScale = 0.97f, onClickLabel = "Select serving") { showServingMenu = true }
                            .heightIn(min = 40.dp)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = servingDisplay,
                            style = AppTypography.Chip,
                            color = AppColors.ChipInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        MbIcon(icon = Ph.CaretDown.light, contentDescription = null, size = 14.dp, tint = AppColors.TextSecondary)
                    }

                    val availableServings = if (item.food.servings.isNotEmpty()) {
                        item.food.servings
                    } else {
                        listOf(item.serving)
                    }

                    DropdownMenu(
                        expanded = showServingMenu,
                        onDismissRequest = { showServingMenu = false },
                        modifier = Modifier.background(AppColors.SheetFill)
                    ) {
                        availableServings.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(item.food.servingLabel(s), color = AppColors.TextPrimary) },
                                onClick = {
                                    onServingChange(s)
                                    showServingMenu = false
                                }
                            )
                        }
                    }
                }

                // Opens the detailed item editor
                MbIconButton(
                    icon = Ph.Info.light,
                    contentDescription = "Item info",
                    onClick = onInfoClick,
                    size = 40.dp,
                    iconSize = 19.dp
                )
            }
        }
    }
}
/** "Today" or "Yesterday", otherwise the weekday and date. */
internal fun basketDateLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(DateTimeFormatter.ofPattern("EEEE, MM/dd", java.util.Locale.US))
}

/**
 * The day and meal for the basket header, or for one item. A null day or meal means the items
 * differ, so that part reads "mixed" (BUG-039).
 */
internal fun basketWhenLabel(date: LocalDate?, mealType: MealType?, today: LocalDate): String {
    val day = date?.let { basketDateLabel(it, today) }
    val meal = mealType?.displayName
    return if (day == null && meal == null) "Mixed (see each item)" else "${day ?: "Mixed days"}, ${meal ?: "mixed meals"}"
}

/**
 * Unit text shown after the quantity field. "1 plate" reads as "plate"; any other leading
 * amount stays visible ("\u00D7 100 g"), so a quantity of 1 never looks like "1 g".
 */
internal fun extractUnitDescription(description: String): String {
    val trimmed = description.trim()
    val regex = Regex("""^(\d+(?:\.\d+)?)\s*(.*)$""")
    val match = regex.find(trimmed)
    return if (match != null) {
        val unit = match.groupValues[2].trim()
        when {
            unit.isBlank() -> trimmed
            match.groupValues[1].toDouble() == 1.0 -> unit
            else -> "\u00D7 $trimmed"
        }
    } else {
        trimmed
    }
}
