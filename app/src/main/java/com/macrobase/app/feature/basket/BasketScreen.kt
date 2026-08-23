package com.macrobase.app.feature.basket

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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.macrobase.app.core.designsystem.components.PrimaryButton
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.basket.BasketItem
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

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
    val loggingMode by viewModel.loggingMode.collectAsState()
    val revealedItemId by viewModel.revealedItemId.collectAsState()

    var showDatePicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
    ) {
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AppSpacing.xl),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Your basket is empty.", style = AppTypography.Header3, color = AppColors.TextSecondary)
                    Spacer(modifier = Modifier.height(AppSpacing.xl))
                    PrimaryButton(text = "Add Food", onClick = onNavigateToSearch)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // 1. Swipe left to delete Hint
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.Surface)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "swipe left to delete",
                        style = AppTypography.Caption,
                        color = AppColors.TextSecondary
                    )
                }
                HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)

                // 2. Pending Food Items (Bulk Inline Editors)
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
                            }
                        )
                        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
                    }
                }

                // 3. Total Calories & 3-Column Macro Strip
                val totalCalories = items.sumOf { it.calculatedNutrition.calories }
                val totalProtein = items.sumOf { it.calculatedNutrition.proteinGrams }
                val totalCarbs = items.sumOf { it.calculatedNutrition.carbsGrams }
                val totalFat = items.sumOf { it.calculatedNutrition.fatGrams }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.Surface)
                        .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Total Calories",
                            style = AppTypography.Header3.copy(fontWeight = FontWeight.Bold),
                            color = AppColors.TextPrimary
                        )
                        Text(
                            text = "${totalCalories.toInt()}",
                            style = AppTypography.Header2.copy(fontWeight = FontWeight.Bold),
                            color = AppColors.CalorieText
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(AppSpacing.xs))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .background(AppColors.SurfaceAlt)
                            .border(1.dp, AppColors.Divider),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${totalProtein.toInt()}g Protein",
                                style = AppTypography.Body2.copy(fontWeight = FontWeight.Medium),
                                color = Color(0xFF5C93FF)
                            )
                        }
                        VerticalDivider(color = AppColors.Divider, thickness = 1.dp)
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${totalCarbs.toInt()}g Carb",
                                style = AppTypography.Body2.copy(fontWeight = FontWeight.Medium),
                                color = Color(0xFF4DD0E1)
                            )
                        }
                        VerticalDivider(color = AppColors.Divider, thickness = 1.dp)
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${totalFat.toInt()}g Fat",
                                style = AppTypography.Body2.copy(fontWeight = FontWeight.Medium),
                                color = Color(0xFFFF7043)
                            )
                        }
                    }
                }

                HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)

                // 4. "Appear on food log as:" Mode Selector
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.Background)
                        .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm)
                ) {
                    Text(
                        text = "Appear on food log as:",
                        style = AppTypography.Body1.copy(fontWeight = FontWeight.Medium),
                        color = AppColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { viewModel.setLoggingMode(LoggingMode.MULTIPLE_FOODS) }
                        ) {
                            RadioButton(
                                selected = loggingMode == LoggingMode.MULTIPLE_FOODS,
                                onClick = { viewModel.setLoggingMode(LoggingMode.MULTIPLE_FOODS) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color(0xFF00B0FF),
                                    unselectedColor = AppColors.TextSecondary
                                )
                            )
                            Text("Multiple Foods", style = AppTypography.Body2, color = AppColors.TextPrimary)
                        }
                        Spacer(modifier = Modifier.width(AppSpacing.md))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { viewModel.setLoggingMode(LoggingMode.SINGLE_FOOD_RECIPE) }
                        ) {
                            RadioButton(
                                selected = loggingMode == LoggingMode.SINGLE_FOOD_RECIPE,
                                onClick = { viewModel.setLoggingMode(LoggingMode.SINGLE_FOOD_RECIPE) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color(0xFF00B0FF),
                                    unselectedColor = AppColors.TextSecondary
                                )
                            )
                            Text("Single Food (Recipe)", style = AppTypography.Body2, color = AppColors.TextPrimary)
                        }
                    }
                }

                HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)

                // 5. "When:" Header & Common Meal / Date Controls
                val today = LocalDate.now()
                val dateLabel = when (commonDate) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> commonDate.format(DateTimeFormatter.ofPattern("EEEE, MM/dd"))
                }
                val whenLabel = "$dateLabel, ${commonMealType.displayName}"

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.Background)
                        .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "When:  $whenLabel",
                            style = AppTypography.Header3.copy(fontWeight = FontWeight.Bold),
                            color = AppColors.TextPrimary
                        )
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit When",
                            tint = AppColors.TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(AppSpacing.sm))

                    // 6-Meal Grid (2 rows x 3 columns)
                    val mealGrid = listOf(
                        listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER),
                        listOf(MealType.AM_SNACK, MealType.PM_SNACK, MealType.SNACK)
                    )

                    mealGrid.forEach { rowMeals ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            rowMeals.forEach { meal ->
                                val isSelected = meal == commonMealType
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (isSelected) AppColors.SurfaceAlt else AppColors.Surface)
                                        .border(1.dp, if (isSelected) AppColors.TextSecondary else AppColors.Divider, RoundedCornerShape(2.dp))
                                        .clickable { viewModel.setCommonMealType(meal) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = meal.displayName,
                                        style = AppTypography.Body2.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) AppColors.TextPrimary else AppColors.TextSecondary
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(AppSpacing.xs))

                    // Date Selection Row (3 buttons: Yesterday, Today, Choose a day)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val isYesterday = commonDate == today.minusDays(1)
                        val isToday = commonDate == today
                        val isOther = !isYesterday && !isToday

                        // Yesterday
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (isYesterday) AppColors.SurfaceAlt else AppColors.Surface)
                                .border(1.dp, if (isYesterday) AppColors.TextSecondary else AppColors.Divider, RoundedCornerShape(2.dp))
                                .clickable { viewModel.setCommonDate(today.minusDays(1)) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Yesterday",
                                style = AppTypography.Body2.copy(
                                    fontWeight = if (isYesterday) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isYesterday) AppColors.TextPrimary else AppColors.TextSecondary
                                )
                            )
                        }

                        // Today
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (isToday) AppColors.SurfaceAlt else AppColors.Surface)
                                .border(1.dp, if (isToday) AppColors.TextSecondary else AppColors.Divider, RoundedCornerShape(2.dp))
                                .clickable { viewModel.setCommonDate(today) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Today",
                                style = AppTypography.Body2.copy(
                                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isToday) AppColors.TextPrimary else AppColors.TextSecondary
                                )
                            )
                        }

                        // Choose a day
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (isOther) AppColors.SurfaceAlt else AppColors.Surface)
                                .border(1.dp, if (isOther) AppColors.TextSecondary else AppColors.Divider, RoundedCornerShape(2.dp))
                                .clickable { showDatePicker = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isOther) commonDate.format(DateTimeFormatter.ofPattern("MM/dd")) else "Choose a day",
                                style = AppTypography.Body2.copy(
                                    fontWeight = if (isOther) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isOther) AppColors.TextPrimary else AppColors.TextSecondary
                                )
                            )
                        }
                    }
                }

                if (showDatePicker) {
                    val initialMillis = commonDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
                    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
                    DatePickerDialog(
                        onDismissRequest = { showDatePicker = false },
                        confirmButton = {
                            TextButton(onClick = {
                                datePickerState.selectedDateMillis?.let { millis ->
                                    val selectedLocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                                    viewModel.setCommonDate(selectedLocalDate)
                                }
                                showDatePicker = false
                            }) { Text("OK", color = AppColors.Primary) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDatePicker = false }) { Text("Cancel", color = AppColors.Primary) }
                        },
                        colors = DatePickerDefaults.colors(containerColor = AppColors.Surface)
                    ) {
                        DatePicker(
                            state = datePickerState,
                            colors = DatePickerDefaults.colors(
                                containerColor = AppColors.Surface,
                                titleContentColor = AppColors.TextPrimary,
                                headlineContentColor = AppColors.TextPrimary,
                                weekdayContentColor = AppColors.TextSecondary,
                                subheadContentColor = AppColors.TextPrimary,
                                yearContentColor = AppColors.TextPrimary,
                                currentYearContentColor = AppColors.Primary,
                                selectedYearContentColor = AppColors.TextPrimary,
                                selectedYearContainerColor = AppColors.Primary,
                                dayContentColor = AppColors.TextPrimary,
                                selectedDayContentColor = AppColors.TextPrimary,
                                selectedDayContainerColor = AppColors.Primary,
                                todayDateBorderColor = AppColors.Primary,
                                todayContentColor = AppColors.Primary
                            )
                        )
                    }
                }

                HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)

                // 6. Photo Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Photo", style = AppTypography.Body1, color = AppColors.TextPrimary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Add Photo:", style = AppTypography.Body2, color = AppColors.TextSecondary)
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.Image, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
                    }
                }

                HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
                Spacer(modifier = Modifier.height(AppSpacing.md))

                // 7. Primary Action Button: "Log X Foods"
                val logButtonLabel = "Log ${items.size} Food${if (items.size > 1) "s" else ""}"
                Button(
                    onClick = {
                        viewModel.submitBasket(
                            onSuccess = onNavigateToDashboard,
                            onError = { /* show error */ }
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = AppSpacing.xs),
                    shape = RoundedCornerShape(2.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary)
                ) {
                    Text(logButtonLabel, style = AppTypography.Header3, color = AppColors.TextPrimary)
                }

                Spacer(modifier = Modifier.height(AppSpacing.md))

                // 8. Lower Links & Clear Basket
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Report a problem", style = AppTypography.Caption, color = AppColors.TextSecondary)
                    Text("Report nutrition discrepancy", style = AppTypography.Caption, color = AppColors.TextSecondary)
                }

                Spacer(modifier = Modifier.height(AppSpacing.md))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.clearBasket() }
                        .padding(vertical = AppSpacing.xs),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Clear Basket", style = AppTypography.Body1.copy(fontWeight = FontWeight.Medium), color = Color(0xFFE53935))
                }

                Spacer(modifier = Modifier.height(AppSpacing.xxl))
            }
        }
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
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val deleteActionWidth = 80.dp
    val deleteActionWidthPx = with(density) { deleteActionWidth.toPx() }
    val offsetX = remember(item.id) { Animatable(if (isRevealed) -deleteActionWidthPx else 0f) }
    val coroutineScope = rememberCoroutineScope()

    var showServingMenu by remember { mutableStateOf(false) }
    var quantityInputText by remember(item.quantity) {
        mutableStateOf(
            if (item.quantity % 1.0 == 0.0) item.quantity.toInt().toString()
            else String.format(java.util.Locale.US, "%.1f", item.quantity)
        )
    }

    LaunchedEffect(isRevealed) {
        val target = if (isRevealed) -deleteActionWidthPx else 0f
        if (kotlin.math.abs(offsetX.value - target) > 0.5f) {
            offsetX.animateTo(target, animationSpec = tween(durationMillis = 200))
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(AppColors.Background)
    ) {
        // Revealed Red Delete Button on the Right
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(deleteActionWidth)
                .fillMaxHeight()
                .background(Color(0xFFFF0000))
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
                style = AppTypography.Body1.copy(fontWeight = FontWeight.Medium),
                color = Color.White
            )
        }

        // Swipable Foreground Row
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .background(AppColors.Background)
                .pointerInput(item.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { },
                        onDragEnd = {
                            coroutineScope.launch {
                                if (offsetX.value < -deleteActionWidthPx * 0.4f) {
                                    offsetX.animateTo(-deleteActionWidthPx, animationSpec = tween(200))
                                    onRevealChange(true)
                                } else {
                                    offsetX.animateTo(0f, animationSpec = tween(200))
                                    onRevealChange(false)
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                offsetX.animateTo(if (isRevealed) -deleteActionWidthPx else 0f, animationSpec = tween(200))
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
                .clickable {
                    if (isRevealed || offsetX.value < -10f) {
                        coroutineScope.launch {
                            offsetX.animateTo(0f, animationSpec = tween(200))
                            onRevealChange(false)
                        }
                    }
                }
                .padding(horizontal = AppSpacing.sm, vertical = 8.dp)
        ) {
            // Line 1: [Icon]  [Quantity Box]  [Serving Unit]   (i)   [Calories cal]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Food Icon
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AppColors.SurfaceAlt),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Fastfood,
                        contentDescription = null,
                        tint = AppColors.TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(AppSpacing.sm))

                // Quantity Text Field Box
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(34.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AppColors.SurfaceAlt)
                        .border(1.dp, AppColors.Divider, RoundedCornerShape(2.dp))
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicTextField(
                        value = quantityInputText,
                        onValueChange = { newText ->
                            quantityInputText = newText
                            onQuantityChange(newText)
                        },
                        textStyle = AppTypography.Body1.copy(
                            fontWeight = FontWeight.Bold,
                            color = AppColors.TextPrimary,
                            textAlign = TextAlign.Center
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        cursorBrush = SolidColor(AppColors.Primary)
                    )
                }

                Spacer(modifier = Modifier.width(AppSpacing.sm))

                // Serving / Unit Dropdown Trigger
                val servingDisplay = extractUnitDescription(item.serving.description)
                Box {
                    Row(
                        modifier = Modifier
                            .clickable { showServingMenu = true }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = servingDisplay,
                            style = AppTypography.Body1,
                            color = AppColors.TextSecondary
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select Serving",
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    val availableServings = if (item.food.servings.isNotEmpty()) {
                        item.food.servings
                    } else {
                        listOf(item.serving)
                    }

                    DropdownMenu(
                        expanded = showServingMenu,
                        onDismissRequest = { showServingMenu = false }
                    ) {
                        availableServings.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.description) },
                                onClick = {
                                    onServingChange(s)
                                    showServingMenu = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(AppSpacing.xs))

                // Info Icon (Opens Detailed Item Editor)
                IconButton(
                    onClick = onInfoClick,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Item Info",
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Calories Column
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${item.calculatedNutrition.calories.toInt()}",
                        style = AppTypography.Body1.copy(fontWeight = FontWeight.Bold),
                        color = AppColors.CalorieText
                    )
                    Text(
                        text = "cal",
                        style = AppTypography.Caption,
                        color = AppColors.TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Line 2: Food Name
            Text(
                text = item.foodNameSnapshot,
                style = AppTypography.Body1.copy(fontWeight = FontWeight.Medium),
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun extractUnitDescription(description: String): String {
    val trimmed = description.trim()
    val regex = Regex("""^(\d+(?:\.\d+)?)\s*(.*)$""")
    val match = regex.find(trimmed)
    return if (match != null) {
        val unit = match.groupValues[2].trim()
        if (unit.isNotBlank()) unit else trimmed
    } else {
        trimmed
    }
}
