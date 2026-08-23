package com.macrobase.app.feature.dashboard

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.core.designsystem.components.MacroProgressStrip
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Meal
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetDailyDiaryUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.macrobase.app.core.util.DashboardDateFormatter
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.usecase.GetWeightForDateUseCase
import java.time.LocalDate
import java.time.ZoneId

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import com.macrobase.app.domain.model.MealConfiguration
import androidx.compose.foundation.layout.defaultMinSize

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val getDailyDiaryUseCase: GetDailyDiaryUseCase,
    private val deleteDiaryEntryUseCase: DeleteDiaryEntryUseCase,
    private val addFoodToBasketUseCase: com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase,
    private val getWeightForDateUseCase: GetWeightForDateUseCase? = null
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now(ZoneId.systemDefault()))
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val _revealedEntryId = MutableStateFlow<Long?>(null)
    val revealedEntryId: StateFlow<Long?> = _revealedEntryId.asStateFlow()

    val dailySummary: StateFlow<DailyNutritionSummary> = _selectedDate.flatMapLatest { date ->
        getDailyDiaryUseCase(date)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DailyNutritionSummary(date = LocalDate.now(ZoneId.systemDefault()))
    )

    val selectedDateWeight: StateFlow<WeightEntry?> = _selectedDate.flatMapLatest { date ->
        getWeightForDateUseCase?.invoke(date) ?: kotlinx.coroutines.flow.flowOf(null)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    fun onPreviousDay() {
        _revealedEntryId.value = null
        _selectedDate.value = _selectedDate.value.minusDays(1)
    }

    fun onNextDay() {
        _revealedEntryId.value = null
        _selectedDate.value = _selectedDate.value.plusDays(1)
    }

    fun onSelectDate(date: LocalDate) {
        _revealedEntryId.value = null
        _selectedDate.value = date
    }

    fun resetToToday(zoneId: ZoneId = ZoneId.systemDefault()) {
        _revealedEntryId.value = null
        _selectedDate.value = LocalDate.now(zoneId)
    }

    fun setRevealedEntry(entryId: Long?) {
        _revealedEntryId.value = entryId
    }

    fun deleteEntry(entryId: Long) {
        _revealedEntryId.value = null
        viewModelScope.launch {
            deleteDiaryEntryUseCase(entryId)
        }
    }

    fun copyEntry(entry: DiaryEntry) {
        _revealedEntryId.value = null
        addFoodToBasketUseCase(
            food = entry.food,
            serving = entry.serving,
            quantity = entry.quantity,
            calculatedNutrition = entry.calculatedNutrition,
            date = entry.date,
            mealType = entry.mealType
        )
    }
}

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToSearch: (MealType, Long) -> Unit,
    onNavigateToDetail: (Long, MealType, Long, Long) -> Unit,
    onNavigateToCalendar: () -> Unit,
    onNavigateToWater: (Long) -> Unit,
    onNavigateToWeight: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val selectedDate by viewModel.selectedDate.collectAsState()
    val summary by viewModel.dailySummary.collectAsState()

    var selectedMealForInfo by remember { mutableStateOf<Meal?>(null) }
    var showDailySummaryDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .verticalScroll(rememberScrollState())
    ) {
        // 1. Date Selector Ribbon - Compact and centered
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimensions.SectionHeaderHeight)
                .background(AppColors.Background),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.onPreviousDay() }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Day", tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(AppSpacing.md))
            Text(
                text = if (selectedDate == LocalDate.now()) "Today" else DashboardDateFormatter.formatDashboardDate(selectedDate),
                style = AppTypography.Body1,
                color = AppColors.TextPrimary,
                modifier = Modifier
                    .clickable {
                        val dialog = DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                viewModel.onSelectDate(LocalDate.of(year, month + 1, dayOfMonth))
                            },
                            selectedDate.year,
                            selectedDate.monthValue - 1,
                            selectedDate.dayOfMonth
                        )
                        dialog.show()
                    }
                    .padding(horizontal = AppSpacing.sm)
            )
            Spacer(modifier = Modifier.width(AppSpacing.md))
            IconButton(onClick = { viewModel.onNextDay() }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Next Day", tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
            }
        }
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)

        // 2. Calorie Target & Intake Summary (Flat, 3 Columns)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.Background)
                .padding(vertical = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "${summary.totalCaloriesIntake.toInt()}", style = AppTypography.Header3, color = AppColors.TextPrimary)
                Text(text = "Intake", style = AppTypography.Caption, color = AppColors.TextSecondary)
            }
            Box(modifier = Modifier.size(width = 1.dp, height = 24.dp).background(AppColors.Divider))
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "${summary.totalCaloriesBurned.toInt()}", style = AppTypography.Header3, color = AppColors.TextPrimary)
                Text(text = "Burned", style = AppTypography.Caption, color = AppColors.TextSecondary)
            }
            Box(modifier = Modifier.size(width = 1.dp, height = 24.dp).background(AppColors.Divider))
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val balance = summary.calorieBalance.toInt()
                Text(
                    text = "${kotlin.math.abs(balance)}",
                    style = AppTypography.Header3,
                    color = if (summary.isOverBudget) AppColors.ProgressOver else AppColors.CalorieText
                )
                Text(
                    text = if (summary.isOverBudget) "Over" else "Remaining",
                    style = AppTypography.Caption,
                    color = AppColors.TextSecondary
                )
            }
        }

        // 3. Calorie Limit Progress Bar
        val progress = if (summary.calorieGoal > 0) {
            (summary.totalCaloriesIntake / summary.calorieGoal).toFloat().coerceIn(0f, 1f)
        } else 0f
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimensions.CalorieProgressHeight),
            color = if (summary.isOverBudget) AppColors.ProgressOver else AppColors.CalorieText,
            trackColor = AppColors.SurfaceAlt
        )
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)

        // 4. Macronutrient 3-Column Strip
        MacroProgressStrip(
            proteinGrams = summary.totalProteinGrams,
            carbsGrams = summary.totalCarbsGrams,
            fatGrams = summary.totalFatGrams
        )
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)

        val revealedEntryId by viewModel.revealedEntryId.collectAsState()

        // 5. 5 Meal Diary Sections
        MealConfiguration.getVisibleMeals(summary.meals).forEach { meal ->
            DashboardMealSection(
                meal = meal,
                revealedEntryId = revealedEntryId,
                onRevealEntry = { entryId -> viewModel.setRevealedEntry(entryId) },
                onAddFoodClick = { onNavigateToSearch(meal.type, selectedDate.toEpochDay()) },
                onInfoClick = { selectedMealForInfo = meal },
                onEntryClick = { entry ->
                    viewModel.setRevealedEntry(null)
                    onNavigateToDetail(entry.food.id, meal.type, selectedDate.toEpochDay(), entry.id)
                },
                onDeleteEntry = { entry ->
                    viewModel.deleteEntry(entry.id)
                },
                onCopyEntry = { entry ->
                    viewModel.copyEntry(entry)
                }
            )
            Spacer(modifier = Modifier.height(AppSpacing.md))
        }

        // 6. View Daily Summary Action
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.sm),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimensions.ButtonHeight)
                    .clickable { showDailySummaryDialog = true }
                    .background(AppColors.Surface)
                    .padding(horizontal = AppSpacing.lg),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PieChart,
                    contentDescription = null,
                    tint = AppColors.Primary,
                    modifier = Modifier.size(Dimensions.IconSizeMedium)
                )
                Text(
                    text = "View Daily Summary",
                    style = AppTypography.Header3,
                    modifier = Modifier.padding(start = AppSpacing.sm)
                )
            }
        }
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
        Spacer(modifier = Modifier.height(AppSpacing.md))

        // 7. Supplementary Modules (Water & Weight)
        // Flat style without Card container
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.Surface)
                .clickable { onNavigateToWater(selectedDate.toEpochDay()) }
                .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Opacity, contentDescription = null, tint = AppColors.WaterCyan, modifier = Modifier.size(Dimensions.IconSizeMedium))
                Spacer(modifier = Modifier.size(AppSpacing.sm))
                Text(text = "Water", style = AppTypography.Body1)
            }
            Text(
                text = "${summary.totalWaterMl.toInt()} / ${summary.waterGoalMl.toInt()} mL",
                style = AppTypography.Body2,
                color = AppColors.WaterCyan
            )
        }
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
        Spacer(modifier = Modifier.height(AppSpacing.md))
        
        val selectedWeight by viewModel.selectedDateWeight.collectAsState()
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.Surface)
                .clickable { onNavigateToWeight(selectedDate.toEpochDay()) }
                .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Scale, contentDescription = null, tint = AppColors.MacroProtein, modifier = Modifier.size(Dimensions.IconSizeMedium))
                Spacer(modifier = Modifier.size(AppSpacing.sm))
                Text(text = "Weight", style = AppTypography.Body1)
            }
            Text(
                text = if (selectedWeight != null) "${selectedWeight?.weightKg} kg" else "Tap to log",
                style = AppTypography.Body2,
                color = if (selectedWeight != null) AppColors.TextPrimary else AppColors.TextSecondary
            )
        }
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)

        Spacer(modifier = Modifier.height(AppSpacing.xl))
    }

    // Modal Meal Info Dialog
    selectedMealForInfo?.let { meal ->
        AlertDialog(
            onDismissRequest = { selectedMealForInfo = null },
            title = { Text("${meal.type.displayName} Nutrition", style = AppTypography.Header2) },
            text = {
                Column {
                    Text("Calories: ${meal.totalCalories.toInt()} kcal", style = AppTypography.Body1)
                    Text("Protein: ${String.format("%.1f", meal.totalProtein)}g", style = AppTypography.Body1, color = AppColors.MacroProtein)
                    Text("Carbs: ${String.format("%.1f", meal.totalCarbs)}g", style = AppTypography.Body1, color = AppColors.MacroCarbs)
                    Text("Fat: ${String.format("%.1f", meal.totalFat)}g", style = AppTypography.Body1, color = AppColors.MacroFat)
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMealForInfo = null }) {
                    Text("Close", color = AppColors.Primary)
                }
            },
            containerColor = AppColors.SurfaceAlt,
            shape = AppShapes.Dialog
        )
    }

    // Modal Daily Summary Dialog
    if (showDailySummaryDialog) {
        AlertDialog(
            onDismissRequest = { showDailySummaryDialog = false },
            title = { Text("Daily Nutrition", style = AppTypography.Header2) },
            text = {
                Column {
                    Text("Intake: ${summary.totalCaloriesIntake.toInt()} / ${summary.calorieGoal.toInt()} kcal", style = AppTypography.Header3)
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Text("â€¢ Protein: ${String.format("%.1f", summary.totalProteinGrams)}g", style = AppTypography.Body1, color = AppColors.MacroProtein)
                    Text("â€¢ Carbohydrates: ${String.format("%.1f", summary.totalCarbsGrams)}g", style = AppTypography.Body1, color = AppColors.MacroCarbs)
                    Text("â€¢ Fat: ${String.format("%.1f", summary.totalFatGrams)}g", style = AppTypography.Body1, color = AppColors.MacroFat)
                    Text("â€¢ Fiber: ${String.format("%.1f", summary.totalFiberGrams)}g", style = AppTypography.Body2)
                    Text("â€¢ Sugars: ${String.format("%.1f", summary.totalSugarGrams)}g", style = AppTypography.Body2)
                    Text("â€¢ Sodium: ${String.format("%.1f", summary.totalSodiumMg)}mg", style = AppTypography.Body2)
                }
            },
            confirmButton = {
                TextButton(onClick = { showDailySummaryDialog = false }) {
                    Text("Close", color = AppColors.Primary)
                }
            },
            containerColor = AppColors.SurfaceAlt,
            shape = AppShapes.Dialog
        )
    }
}

@Composable
fun DashboardMealSection(
    meal: Meal,
    revealedEntryId: Long?,
    onRevealEntry: (Long?) -> Unit,
    onAddFoodClick: () -> Unit,
    onInfoClick: () -> Unit,
    onEntryClick: (DiaryEntry) -> Unit,
    onDeleteEntry: (DiaryEntry) -> Unit,
    onCopyEntry: (DiaryEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val sectionCalories = meal.totalCalories.toInt()

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
        // Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimensions.SectionHeaderHeight)
                .background(AppColors.Background)
                .padding(horizontal = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = meal.type.displayName.uppercase(),
                    style = AppTypography.Caption,
                    color = AppColors.TextPrimary
                )
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(AppColors.Primary)
                        .clickable(onClick = onAddFoodClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Food",
                        tint = AppColors.TextPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            IconButton(
                onClick = onInfoClick,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Meal Info",
                    tint = AppColors.TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            Text(
                text = sectionCalories.toString(),
                style = AppTypography.Body1,
                color = AppColors.TextPrimary
            )
        }

        if (meal.entries.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = Dimensions.FoodRowHeight)
                    .background(AppColors.Background)
                    .padding(horizontal = AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "No foods logged yet.",
                    style = AppTypography.Body2,
                    color = AppColors.TextSecondary
                )
            }
        } else {
            meal.entries.forEach { entry ->
                androidx.compose.runtime.key(entry.id) {
                    HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
                    DashboardFoodItemRow(
                        entry = entry,
                        isRevealed = revealedEntryId == entry.id,
                        onRevealChange = { isRevealed ->
                            onRevealEntry(if (isRevealed) entry.id else null)
                        },
                        onClick = { onEntryClick(entry) },
                        onDelete = { onDeleteEntry(entry) },
                        onCopy = { onCopyEntry(entry) }
                    )
                }
            }
        }
    }
}

@Composable
fun DashboardFoodItemRow(
    entry: DiaryEntry,
    isRevealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val actionWidth = 140.dp
    val actionWidthPx = with(density) { actionWidth.toPx() }
    val offsetX = remember(entry.id) { Animatable(if (isRevealed) -actionWidthPx else 0f) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(isRevealed) {
        val target = if (isRevealed) -actionWidthPx else 0f
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
        // Revealed Action Buttons on the Right
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(actionWidth)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Delete Button (Bright Red)
            Box(
                modifier = Modifier
                    .weight(1f)
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

            // Copy Button (Brown / Dark Amber)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0xFF8B3A00))
                    .clickable {
                        coroutineScope.launch {
                            offsetX.animateTo(0f, animationSpec = tween(150))
                            onRevealChange(false)
                            onCopy()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Copy",
                    style = AppTypography.Body1.copy(fontWeight = FontWeight.Medium),
                    color = Color.White
                )
            }
        }

        // Foreground Content (Swipable)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .defaultMinSize(minHeight = Dimensions.FoodRowHeight)
                .background(AppColors.Background)
                .pointerInput(entry.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { },
                        onDragEnd = {
                            coroutineScope.launch {
                                if (offsetX.value < -actionWidthPx * 0.4f) {
                                    offsetX.animateTo(-actionWidthPx, animationSpec = tween(200))
                                    onRevealChange(true)
                                } else {
                                    offsetX.animateTo(0f, animationSpec = tween(200))
                                    onRevealChange(false)
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                offsetX.animateTo(if (isRevealed) -actionWidthPx else 0f, animationSpec = tween(200))
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            coroutineScope.launch {
                                val newOffset = (offsetX.value + dragAmount).coerceIn(-actionWidthPx, 0f)
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
                    } else {
                        onClick()
                    }
                }
                .padding(horizontal = AppSpacing.sm, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail Box
            Box(
                modifier = Modifier
                    .size(Dimensions.FoodThumbnailSize)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AppColors.TextPrimary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Fastfood,
                    contentDescription = null,
                    tint = AppColors.Background,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Title and Subtitle
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = AppSpacing.sm)
            ) {
                Text(
                    text = entry.food.name,
                    style = AppTypography.Body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = AppColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatDiaryEntrySubtitle(entry),
                    style = AppTypography.Caption,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Calories
            Text(
                text = "${entry.calculatedNutrition.calories.toInt()}",
                style = AppTypography.Body1,
                color = AppColors.CalorieText
            )

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = AppColors.TextSecondary,
                modifier = Modifier.padding(start = AppSpacing.xs).size(16.dp)
            )
        }
    }
}

private fun formatDiaryEntrySubtitle(entry: DiaryEntry): String {
    val qty = entry.quantity
    val qtyStr = if (qty % 1.0 == 0.0) qty.toInt().toString() else String.format(java.util.Locale.US, "%.1f", qty)
    val desc = entry.serving.description.trim()

    val regex = Regex("""^(\d+(?:\.\d+)?)\s*(.*)$""")
    val match = regex.find(desc)
    return if (match != null) {
        val baseQty = match.groupValues[1].toDoubleOrNull() ?: 1.0
        val unit = match.groupValues[2].trim()
        if (baseQty == 1.0) {
            if (unit.isNotEmpty()) "$qtyStr $unit" else qtyStr
        } else {
            val total = baseQty * qty
            val totalStr = if (total % 1.0 == 0.0) total.toInt().toString() else String.format(java.util.Locale.US, "%.1f", total)
            if (unit.isNotEmpty()) "$totalStr $unit" else totalStr
        }
    } else {
        if (desc.isNotEmpty()) "$qtyStr $desc" else qtyStr
    }
}
