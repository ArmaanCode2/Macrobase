package com.macrobase.app.feature.dashboard

import kotlin.math.abs
import com.macrobase.app.core.designsystem.components.rememberReducedMotion
import com.macrobase.app.core.designsystem.components.mbDockPill
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.SideEffect
import com.macrobase.app.core.designsystem.components.rememberMbHaptics
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import kotlinx.coroutines.delay
import com.macrobase.app.core.designsystem.components.mealLabel
import com.macrobase.app.core.designsystem.components.mbClickable
import com.macrobase.app.core.designsystem.components.innerTopHighlight
import com.macrobase.app.core.designsystem.components.MbSheet
import com.macrobase.app.core.designsystem.components.MbSearchPill
import com.macrobase.app.core.designsystem.components.MbScanButton
import com.macrobase.app.core.designsystem.components.MbPanel
import com.macrobase.app.core.designsystem.components.MbMotion
import com.macrobase.app.core.designsystem.components.MbMacroRing
import com.macrobase.app.core.designsystem.components.MbIconCircle
import com.macrobase.app.core.designsystem.components.MbIconButton
import com.macrobase.app.core.designsystem.components.MbIcon
import com.macrobase.app.core.designsystem.components.MbDatePickerDialog
import com.macrobase.app.core.designsystem.components.MbBasketButton
import com.macrobase.app.core.designsystem.Ph
import com.macrobase.app.core.designsystem.AppBrushes
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.macrobase.app.core.navigation.LocalDockInset
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Meal
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetDailyDiaryUseCase
import com.macrobase.app.domain.usecase.GetFoodDetailsUseCase
import com.macrobase.app.domain.usecase.MoveDiaryEntriesUseCase
import com.macrobase.app.domain.usecase.AddWaterEntryUseCase
import com.macrobase.app.domain.usecase.GetGoalsUseCase
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.UnitConversions
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.core.config.NutritionStandardConfig
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.macrobase.app.core.util.DashboardDateFormatter
import com.macrobase.app.core.util.DeviceClock
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.usecase.GetWeightForDateUseCase
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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

/**
 * The meal to log to at [time] on the device clock: before 11:00 Breakfast, before 16:00 Lunch,
 * otherwise Dinner. The "+" sheet starts on it.
 */
internal fun mealForTime(time: LocalTime): MealType = when {
    time.isBefore(LocalTime.of(11, 0)) -> MealType.BREAKFAST
    time.isBefore(LocalTime.of(16, 0)) -> MealType.LUNCH
    else -> MealType.DINNER
}

/** What Home shows from the profile and goals: the greeting's first name, units and macro split. */
data class HomeProfile(
    val firstName: String = "",
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val proteinPercentage: Double = 25.0,
    val carbPercentage: Double = 50.0,
    val fatPercentage: Double = 25.0
)

/** Grams of protein, carbs and fat that [calorieGoal] allows at the profile's split, as Goal works them out. */
internal fun macroTargetGrams(calorieGoal: Double, profile: HomeProfile): Triple<Double, Double, Double> {
    if (calorieGoal <= 0.0) return Triple(0.0, 0.0, 0.0)
    return Triple(
        calorieGoal * (profile.proteinPercentage / 100.0) / NutritionStandardConfig.CALORIES_PER_GRAM_PROTEIN,
        calorieGoal * (profile.carbPercentage / 100.0) / NutritionStandardConfig.CALORIES_PER_GRAM_CARB,
        calorieGoal * (profile.fatPercentage / 100.0) / NutritionStandardConfig.CALORIES_PER_GRAM_FAT
    )
}

/** The water Home's quick add logs: 250 mL, or 8 fl oz with imperial units (the Water screen's first amounts). */
internal fun quickAddWaterMl(unitSystem: UnitSystem): Double =
    if (unitSystem == UnitSystem.IMPERIAL) UnitConversions.flOzToMl(8.0) else 250.0

/** A day total of a nutrient that may be unknown: "12.5g", or "-" when no entry states it. */
internal fun formatOptionalTotal(value: Double?, unit: String): String =
    value?.let { "${String.format(Locale.US, "%.1f", it)}$unit" } ?: "-"

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val getDailyDiaryUseCase: GetDailyDiaryUseCase,
    private val deleteDiaryEntryUseCase: DeleteDiaryEntryUseCase,
    private val addFoodToBasketUseCase: com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase,
    private val getFoodDetailsUseCase: GetFoodDetailsUseCase? = null,
    private val getWeightForDateUseCase: GetWeightForDateUseCase? = null,
    private val clock: Clock = DeviceClock,
    private val moveDiaryEntriesUseCase: MoveDiaryEntriesUseCase? = null,
    private val preferencesRepository: PreferencesRepository? = null,
    private val getGoalsUseCase: GetGoalsUseCase? = null,
    private val addWaterEntryUseCase: AddWaterEntryUseCase? = null
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now(clock))
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val _revealedEntryId = MutableStateFlow<Long?>(null)
    val revealedEntryId: StateFlow<Long?> = _revealedEntryId.asStateFlow()

    val dailySummary: StateFlow<DailyNutritionSummary> = _selectedDate.flatMapLatest { date ->
        getDailyDiaryUseCase(date)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DailyNutritionSummary(date = LocalDate.now(clock))
    )

    val selectedDateWeight: StateFlow<WeightEntry?> = _selectedDate.flatMapLatest { date ->
        getWeightForDateUseCase?.invoke(date) ?: kotlinx.coroutines.flow.flowOf(null)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    /** The greeting's first name, the unit system and the macro split for the rings. */
    val homeProfile: StateFlow<HomeProfile> = combine(
        preferencesRepository?.observePreferences() ?: flowOf(UserPreferences()),
        getGoalsUseCase?.invoke() ?: flowOf(Goal())
    ) { prefs, goal ->
        HomeProfile(
            firstName = prefs.firstName.trim(),
            unitSystem = prefs.unitSystem,
            proteinPercentage = goal.proteinPercentage,
            carbPercentage = goal.carbPercentage,
            fatPercentage = goal.fatPercentage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeProfile()
    )

    /**
     * Logs the quick-add amount of water to the day on screen, through the same use case as the
     * Water screen. [onAdded] runs once it is saved.
     */
    fun quickAddWater(onAdded: () -> Unit = {}) {
        val addWater = addWaterEntryUseCase ?: return
        val date = _selectedDate.value
        val amountMl = quickAddWaterMl(homeProfile.value.unitSystem)
        viewModelScope.launch {
            addWater(date, amountMl)
            onAdded()
        }
    }

    /**
     * True while the dashboard shows the current day. It then moves to the new day when the app
     * comes back after midnight; a day the user picked stays (BUG-040).
     */
    private var followsToday = true

    /** Today by this ViewModel's clock, so the "Today" label and the rollover agree. */
    fun today(): LocalDate = LocalDate.now(clock)

    private fun showDate(date: LocalDate) {
        _revealedEntryId.value = null
        _selectedDate.value = date
        followsToday = date == today()
    }

    fun onPreviousDay() {
        showDate(_selectedDate.value.minusDays(1))
    }

    fun onNextDay() {
        showDate(_selectedDate.value.plusDays(1))
    }

    fun onSelectDate(date: LocalDate) {
        showDate(date)
    }

    fun resetToToday(zoneId: ZoneId = clock.zone) {
        _revealedEntryId.value = null
        _selectedDate.value = LocalDate.now(clock.withZone(zoneId))
        followsToday = true
    }

    /**
     * The day for food logged from the dashboard, or null for "today when it is logged", so a
     * screen left open overnight logs to the new day (BUG-040).
     */
    fun dateForNewLog(): LocalDate? = if (followsToday) null else _selectedDate.value

    /** The app is in the foreground again: a dashboard left on today moves to the new day (BUG-040). */
    fun onAppResumed() {
        val today = today()
        // A day the user picked that has since become today follows the calendar from now on
        if (_selectedDate.value == today) followsToday = true
        if (followsToday && _selectedDate.value != today) {
            _revealedEntryId.value = null
            _selectedDate.value = today
        }
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
        viewModelScope.launch { addToBasket(entry) }
    }

    /**
     * Puts every food of [meal] in the basket, in the meal's order, under the day and meal it was
     * logged in; the user picks where it goes and logs it from there.
     */
    fun copyMeal(meal: Meal) {
        _revealedEntryId.value = null
        viewModelScope.launch { meal.entries.forEach { addToBasket(it) } }
    }

    /** The meal to log to now, by this ViewModel's clock. */
    fun currentMeal(): MealType = mealForTime(LocalTime.now(clock))

    /** The diary of any [date], for the "+" sheet's copy from yesterday. */
    fun summaryFor(date: LocalDate): Flow<DailyNutritionSummary> = getDailyDiaryUseCase(date)

    /**
     * Puts the foods of [meal] on [date] in the basket, in the meal's order, already set to today
     * and [toMeal], so logging them never logs [date] again. Items already in the basket keep
     * their own day and meal. [onDone] runs once every food is in.
     */
    fun copyMealFrom(date: LocalDate, meal: MealType, toMeal: MealType = meal, onDone: () -> Unit = {}) {
        _revealedEntryId.value = null
        viewModelScope.launch {
            val entries = getDailyDiaryUseCase(date).first().meals.firstOrNull { it.type == meal }?.entries.orEmpty()
            val today = today()
            entries.forEach { addToBasket(it, date = today, mealType = toMeal) }
            onDone()
        }
    }

    /** Moves every food of [meal] to [mealType] on [date]; what each food logged stays as it was. */
    fun moveMeal(meal: Meal, date: LocalDate, mealType: MealType) {
        val move = moveDiaryEntriesUseCase ?: return
        val entryIds = meal.entries.map { it.id }
        if (entryIds.isEmpty()) return
        _revealedEntryId.value = null
        viewModelScope.launch { move(entryIds, date, mealType) }
    }

    /** Deletes every food of [meal]. */
    fun deleteMeal(meal: Meal) {
        val entryIds = meal.entries.map { it.id }
        if (entryIds.isEmpty()) return
        _revealedEntryId.value = null
        viewModelScope.launch { deleteDiaryEntryUseCase(entryIds) }
    }

    private suspend fun addToBasket(entry: DiaryEntry, date: LocalDate = entry.date, mealType: MealType = entry.mealType) {
        val fullFood = getFoodDetailsUseCase?.invoke(entry.food.id)
            ?.takeIf { it.isSameFoodAs(entry.food) && it.canScale(it.resolveLoggedServing(entry.serving)) }
            ?: entry.food
        val matchedServing = fullFood.resolveLoggedServing(entry.serving)

        addFoodToBasketUseCase(
            food = fullFood,
            serving = matchedServing,
            quantity = entry.quantity,
            calculatedNutrition = entry.calculatedNutrition,
            date = date,
            mealType = mealType
        )
    }
}

/** "Today", "Yesterday" or the date, for Home's day row. */
internal fun homeDayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> DashboardDateFormatter.formatDashboardDate(date, today)
}

/** Whole calories with a thousands separator, as "2,000". */
internal fun formatKcal(value: Double): String = String.format(Locale.US, "%,d", value.roundToInt())

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToSearch: (MealType, Long?) -> Unit,
    onNavigateToDetail: (Long, MealType, Long, Long) -> Unit,
    onNavigateToCalendar: () -> Unit,
    onNavigateToWater: (Long) -> Unit,
    onNavigateToWeight: (Long) -> Unit,
    modifier: Modifier = Modifier,
    basketCount: Int = 0,
    onOpenSearch: () -> Unit = {},
    onScanLabel: () -> Unit = {},
    onOpenBasket: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onLogWeight: (Long) -> Unit = {}
) {
    val selectedDate by viewModel.selectedDate.collectAsState()
    val summary by viewModel.dailySummary.collectAsState()
    val profile by viewModel.homeProfile.collectAsState()
    val revealedEntryId by viewModel.revealedEntryId.collectAsState()

    var selectedMealForInfo by remember { mutableStateOf<Meal?>(null) }
    var showDailySummary by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    val visibleMeals = MealConfiguration.getVisibleMeals(summary.meals)
    val dockInset = LocalDockInset.current
    // The macro rings fill from empty when Home opens; a list item is rebuilt when it scrolls back
    // into view, and the rings then start where they are
    val ringsShown = remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val pageScrolled = remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }

    Box(modifier = modifier.fillMaxSize()) {
        // A lazy list: only the rows on screen are built, so a full day opens as fast as an empty one
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = AppSpacing.screenMargin,
                end = AppSpacing.screenMargin,
                top = 10.dp,
                // Clears the bottom dock
                bottom = AppSpacing.xxl + dockInset
            )
        ) {
            // The basket button is pinned over the page (below), so the header leaves its place free
            item(key = "header", contentType = "header") {
                HomeHeader(firstName = profile.firstName, onOpenProfile = onOpenProfile)
            }

            item(key = "calories", contentType = "calories") {
                CaloriesPanel(
                    summary = summary,
                    profile = profile,
                    onDailySummary = { showDailySummary = true },
                    onSearch = onOpenSearch,
                    onScan = onScanLabel,
                    ringsFillIn = !ringsShown.value,
                    modifier = Modifier
                        .padding(top = AppSpacing.sectionGap)
                        // A sideways swipe steps a day, as a swipe on the calendar steps a month
                        .pointerInput(viewModel) {
                            val threshold = DaySwipeThreshold.toPx()
                            var dragX = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { dragX = 0f },
                                onDragCancel = { dragX = 0f },
                                onDragEnd = {
                                    when (daySwipeStep(dragX, threshold)) {
                                        1 -> viewModel.onNextDay()
                                        -1 -> viewModel.onPreviousDay()
                                    }
                                    dragX = 0f
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    dragX += dragAmount
                                    // Taken from the page once the swipe is clearly sideways
                                    if (abs(dragX) > 10f) change.consume()
                                }
                            )
                        }
                )
                SideEffect { ringsShown.value = true }
            }

            // The day on screen, between its arrows: its name opens the date picker, the arrows step a day
            item(key = "day", contentType = "day") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MbIconButton(
                        icon = Ph.CaretLeft.light,
                        contentDescription = "Previous day",
                        onClick = { viewModel.onPreviousDay() },
                        size = 40.dp,
                        iconSize = 18.dp
                    )
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            text = homeDayLabel(selectedDate, viewModel.today()),
                            style = AppTypography.Header1,
                            color = AppColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .mbClickable(pressedScale = 0.97f, onClickLabel = "Choose a day") { showDatePicker = true }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                    MbIconButton(
                        icon = Ph.CaretRight.light,
                        contentDescription = "Next day",
                        onClick = { viewModel.onNextDay() },
                        size = 40.dp,
                        iconSize = 18.dp
                    )
                }
            }

            // One block per visible meal, its foods listed inline: the meal's header, then each food
            visibleMeals.forEachIndexed { index, meal ->
                item(key = "meal-${meal.type}", contentType = "mealHeader") {
                    MealHeader(
                        meal = meal,
                        onAddFoodClick = { onNavigateToSearch(meal.type, viewModel.dateForNewLog()?.toEpochDay()) },
                        onInfoClick = { selectedMealForInfo = meal },
                        modifier = Modifier.padding(top = if (index == 0) 10.dp else 18.dp)
                    )
                }
                if (meal.entries.isEmpty()) {
                    item(key = "empty-${meal.type}", contentType = "emptyMeal") { EmptyMealTile() }
                } else {
                    itemsIndexed(meal.entries, key = { _, entry -> "entry-${entry.id}" }, contentType = { _, _ -> "entry" }) { position, entry ->
                        DashboardFoodItemRow(
                            entry = entry,
                            isRevealed = revealedEntryId == entry.id,
                            onRevealChange = { isRevealed -> viewModel.setRevealedEntry(if (isRevealed) entry.id else null) },
                            onClick = {
                                viewModel.setRevealedEntry(null)
                                onNavigateToDetail(entry.food.id, meal.type, selectedDate.toEpochDay(), entry.id)
                            },
                            onDelete = { viewModel.deleteEntry(entry.id) },
                            onCopy = { viewModel.copyEntry(entry) },
                            modifier = Modifier.padding(top = if (position == 0) 0.dp else 8.dp)
                        )
                    }
                }
            }

            // Water and weight: the tracker's icon, then its quick action
            item(key = "trackers", contentType = "trackers") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AppSpacing.sectionGap),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    WaterQuickCard(
                        unitSystem = profile.unitSystem,
                        onOpenWater = { onNavigateToWater(selectedDate.toEpochDay()) },
                        onQuickAdd = { onAdded -> viewModel.quickAddWater(onAdded) },
                        modifier = Modifier.weight(1f)
                    )
                    TrackerCard(
                        icon = Ph.Scales,
                        iconDescription = "Weight tracker",
                        onIconClick = { onNavigateToWeight(selectedDate.toEpochDay()) },
                        actionLabel = "Log weight",
                        onAction = { onLogWeight(selectedDate.toEpochDay()) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Where the header row centres it, and it stays there while the page scrolls
        StickyBasketButton(
            count = basketCount,
            lifted = { pageScrolled.value },
            onClick = onOpenBasket,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = AppSpacing.screenMargin)
        )
    }

    // Meal card: totals, and Copy / Move / Delete for the whole meal
    selectedMealForInfo?.let { meal ->
        MealCardDialog(
            meal = meal,
            day = meal.entries.firstOrNull()?.date ?: selectedDate,
            today = viewModel.today(),
            onDismiss = { selectedMealForInfo = null },
            onCopy = {
                selectedMealForInfo = null
                viewModel.copyMeal(meal)
            },
            onMove = { date, mealType ->
                selectedMealForInfo = null
                viewModel.moveMeal(meal, date, mealType)
            },
            onDelete = {
                selectedMealForInfo = null
                viewModel.deleteMeal(meal)
            }
        )
    }

    if (showDailySummary) {
        DailySummarySheet(summary = summary, onDismiss = { showDailySummary = false })
    }

    if (showDatePicker) {
        MbDatePickerDialog(
            initialDate = selectedDate,
            onDateSelected = { viewModel.onSelectDate(it) },
            onDismiss = { showDatePicker = false }
        )
    }
}

/** The avatar with the first name's initial, the greeting, and the basket. */
@Composable
private fun HomeHeader(firstName: String, onOpenProfile: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AvatarRing(firstName = firstName, onClick = onOpenProfile)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (firstName.isBlank()) "Hello" else "Hello, $firstName",
                style = AppTypography.Body1.copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "What are you eating today?",
                style = AppTypography.Header2.copy(lineHeight = 21.sp),
                color = AppColors.TextPrimary
            )
        }
        // The pinned basket button sits over this space (StickyBasketButton)
        Spacer(modifier = Modifier.size(Dimensions.HeaderButton))
    }
}

/** The header row's height: the avatar's. */
private val HeaderRowHeight = 48.dp

/** How far a swipe on the calories panel must go to step a day: the calendar's month swipe distance. */
private val DaySwipeThreshold = 48.dp

/**
 * A sideways swipe on the calories panel, as the calendar's month swipe: 1 shows the next day
 * (swiped left), -1 the day before (swiped right), 0 when it is too short to count.
 */
internal fun daySwipeStep(dragX: Float, thresholdPx: Float): Int = when {
    dragX < -thresholdPx -> 1
    dragX > thresholdPx -> -1
    else -> 0
}

/**
 * The basket button, pinned at the top right while the page scrolls. At the top of the page it looks
 * as it always has; once the page scrolls under it, it takes the dock's floating fill and shadow, as
 * the "+" button has, so it stays clear over the cards.
 */
@Composable
private fun StickyBasketButton(count: Int, lifted: () -> Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reducedMotion = rememberReducedMotion()
    val backing = animateFloatAsState(
        targetValue = if (lifted()) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(200, easing = MbMotion.Ease),
        label = "basketBacking"
    )
    Box(modifier = modifier.height(HeaderRowHeight), contentAlignment = Alignment.Center) {
        // Faded by its layer, so the fade redraws only this circle
        Box(
            modifier = Modifier
                .size(Dimensions.HeaderButton)
                .graphicsLayer { alpha = backing.value }
                .mbDockPill()
        )
        MbBasketButton(count = count, onClick = onClick)
    }
}

/** A 48 dp gradient ring around the first name's initial; a person icon when there is no name. */
@Composable
private fun AvatarRing(firstName: String, onClick: () -> Unit) {
    val initial = firstName.firstOrNull()?.uppercaseChar()
    Box(
        modifier = Modifier
            .size(HeaderRowHeight)
            .mbClickable(pressedScale = 0.94f, onClick = onClick)
            .semantics { contentDescription = "Profile" }
            .clip(CircleShape)
            .drawBehind { rotate(110f) { drawCircle(AppBrushes.AvatarRing) } }
            .padding(2.dp)
            .clip(CircleShape)
            .background(AppColors.AvatarFill),
        contentAlignment = Alignment.Center
    ) {
        if (initial != null) {
            Text(
                text = initial.toString(),
                style = AppTypography.Header1.copy(fontSize = 19.sp),
                color = AppColors.AvatarInk,
                modifier = Modifier.clearAndSetSemantics { }
            )
        } else {
            MbIcon(icon = Ph.User.light, contentDescription = null, size = 20.dp, tint = AppColors.AvatarInk)
        }
    }
}

/**
 * The calories panel: "eaten / target kcal", the calorie bar, the three macro rings, and the search
 * bar with the scan button. The corner button opens the daily summary.
 */
@Composable
private fun CaloriesPanel(
    summary: DailyNutritionSummary,
    profile: HomeProfile,
    onDailySummary: () -> Unit,
    onSearch: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    ringsFillIn: Boolean = true
) {
    val (proteinTarget, carbTarget, fatTarget) = macroTargetGrams(summary.calorieGoal, profile)
    MbPanel(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(end = 48.dp)) {
                    Text(
                        text = formatKcal(summary.totalCaloriesIntake),
                        style = AppTypography.Hero,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.alignByBaseline()
                    )
                    Text(
                        text = "/ ${formatKcal(summary.calorieGoal)} kcal",
                        style = AppTypography.HeroUnit,
                        modifier = Modifier
                            .alignByBaseline()
                            .padding(start = 8.dp)
                    )
                }
                CalorieBar(
                    eaten = summary.totalCaloriesIntake,
                    goal = summary.calorieGoal,
                    over = summary.isOverBudget,
                    modifier = Modifier.padding(top = 14.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MbMacroRing(label = "Protein", eatenGrams = summary.totalProteinGrams, targetGrams = proteinTarget, color = AppColors.MacroProtein, modifier = Modifier.weight(1f), fillIn = ringsFillIn)
                    MbMacroRing(label = "Carbs", eatenGrams = summary.totalCarbsGrams, targetGrams = carbTarget, color = AppColors.MacroCarbs, modifier = Modifier.weight(1f), fillIn = ringsFillIn)
                    MbMacroRing(label = "Fat", eatenGrams = summary.totalFatGrams, targetGrams = fatTarget, color = AppColors.MacroFat, modifier = Modifier.weight(1f), fillIn = ringsFillIn)
                }
                MbSearchPill(
                    placeholder = "Search foods to log\u2026",
                    onClick = onSearch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    MbScanButton(onClick = onScan)
                }
            }
            MbIconButton(
                icon = Ph.ArrowUpRight.light,
                contentDescription = "Daily summary",
                onClick = onDailySummary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp),
                size = 40.dp,
                iconSize = 18.dp,
                outlined = true
            )
        }
    }
}

/** The 6 dp calorie bar in the brand gradient; red and full once over the target. */
@Composable
private fun CalorieBar(eaten: Double, goal: Double, over: Boolean, modifier: Modifier = Modifier) {
    val fraction = if (goal > 0.0) (eaten / goal).toFloat().coerceIn(0f, 1f) else 0f
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(AppColors.BarTrack)
    ) {
        if (over) {
            Box(modifier = Modifier.fillMaxSize().clip(shape).background(AppColors.ProgressOver))
        } else if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(AppBrushes.BrandGradient)
            )
        }
    }
}

/** How far a food row must be swiped for letting go to open it. */
private const val SWIPE_OPEN_FRACTION = 0.4f

/** A meal's name, its add and info buttons, and its total. */
@Composable
private fun MealHeader(meal: Meal, onAddFoodClick: () -> Unit, onInfoClick: () -> Unit, modifier: Modifier = Modifier) {
    val name = mealLabel(meal.type)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = name, style = AppTypography.Header2, color = AppColors.TextPrimary)
        AddToMealButton(onClick = onAddFoodClick, contentDescription = "Add food to $name")
        Spacer(modifier = Modifier.weight(1f))
        MbIconButton(
            icon = Ph.Info.light,
            contentDescription = "$name nutrition",
            onClick = onInfoClick,
            size = 40.dp,
            iconSize = 19.dp
        )
        Text(text = "${meal.totalCalories.roundToInt()}", style = AppTypography.Body1, color = AppColors.TextPrimary)
        Text(text = " kcal", style = AppTypography.Caption, color = AppColors.TextMuted)
    }
}

/** The small filled "+" after a meal's name. */
@Composable
private fun AddToMealButton(onClick: () -> Unit, contentDescription: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .mbClickable(pressedScale = 0.92f, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(AppBrushes.PrimaryButtonGradient)
                .innerTopHighlight(),
            contentAlignment = Alignment.Center
        ) {
            MbIcon(icon = Ph.Plus.regular, contentDescription = null, size = 14.dp, tint = AppColors.OnPrimary)
        }
    }
}

/** A meal with nothing logged: a dashed outline. */
@Composable
private fun EmptyMealTile() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = Color(0x1AFFFFFF),
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                )
            }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = "No foods logged yet.",
            style = AppTypography.Body2.copy(fontSize = 13.5.sp),
            color = AppColors.TextSecondary
        )
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
    val snapSpec = tween<Float>(durationMillis = MbMotion.SwipeMs, easing = MbMotion.Ease)
    val isOpen by remember { derivedStateOf { offsetX.value < 0f } }
    val rowInteraction = remember { MutableInteractionSource() }
    val rowPressed by rowInteraction.collectIsPressedAsState()
    val haptics = rememberMbHaptics()
    // Whether letting go now would open the row, so the tick plays once as a swipe reaches that point
    var swipePastOpen by remember { mutableStateOf(false) }

    LaunchedEffect(isRevealed) {
        val target = if (isRevealed) -actionWidthPx else 0f
        if (kotlin.math.abs(offsetX.value - target) > 0.5f) {
            offsetX.animateTo(target, animationSpec = snapSpec)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
    ) {
        // Delete and Copy under the row, shown only while it is open or being swiped. They take the
        // row's own size, so the row is measured once (no intrinsic pass)
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = if (offsetX.value < 0f) 1f else 0f }
        ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(actionWidth)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
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
                Text(text = "Delete", style = AppTypography.Body1.copy(fontSize = 13.5.sp), color = AppColors.OnCopy)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(AppColors.CopyFill)
                    .clickable {
                        coroutineScope.launch {
                            offsetX.animateTo(0f, animationSpec = tween(150))
                            onRevealChange(false)
                            onCopy()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(text = "Copy", style = AppTypography.Body1.copy(fontSize = 13.5.sp), color = AppColors.OnCopy)
            }
        }
        }

        // The food: an opaque row that slides left over its actions
        val rowShape = if (isOpen) RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp) else RoundedCornerShape(16.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .defaultMinSize(minHeight = 58.dp)
                .clip(rowShape)
                .background(if (rowPressed) Color(0xFF0E140F) else AppColors.RowTile)
                .border(1.dp, AppColors.Edge, rowShape)
                .pointerInput(entry.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { swipePastOpen = offsetX.value < -actionWidthPx * SWIPE_OPEN_FRACTION },
                        onDragEnd = {
                            coroutineScope.launch {
                                if (offsetX.value < -actionWidthPx * SWIPE_OPEN_FRACTION) {
                                    offsetX.animateTo(-actionWidthPx, animationSpec = snapSpec)
                                    onRevealChange(true)
                                } else {
                                    offsetX.animateTo(0f, animationSpec = snapSpec)
                                    onRevealChange(false)
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                offsetX.animateTo(if (isRevealed) -actionWidthPx else 0f, animationSpec = snapSpec)
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            coroutineScope.launch {
                                val newOffset = (offsetX.value + dragAmount).coerceIn(-actionWidthPx, 0f)
                                offsetX.snapTo(newOffset)
                                val pastOpen = newOffset < -actionWidthPx * SWIPE_OPEN_FRACTION
                                if (pastOpen && !swipePastOpen) haptics.reachedOpen()
                                swipePastOpen = pastOpen
                            }
                        }
                    )
                }
                .clickable(interactionSource = rowInteraction, indication = null) {
                    if (isRevealed || offsetX.value < -10f) {
                        coroutineScope.launch {
                            offsetX.animateTo(0f, animationSpec = snapSpec)
                            onRevealChange(false)
                        }
                    } else {
                        onClick()
                    }
                }
                .padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MbIconCircle(icon = Ph.BowlFood.light, size = 34.dp, iconSize = 17.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = entry.food.name,
                    style = AppTypography.Header3,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = remember(entry) { formatDiaryEntrySubtitle(entry) },
                    style = AppTypography.Body2.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "${entry.calculatedNutrition.calories.roundToInt()}",
                style = AppTypography.Body1,
                color = AppColors.TextPrimary
            )
            MbIcon(icon = Ph.CaretRight.light, contentDescription = null, size = 15.dp, tint = AppColors.TextMuted)
        }
    }
}

/** A compact tracker card: the tracker's icon, then a chip-style quick action filling the rest. */
@Composable
private fun TrackerCard(
    icon: Ph,
    iconDescription: String,
    onIconClick: () -> Unit,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionDescription: String? = null
) {
    MbPanel(modifier = modifier, contentPadding = PaddingValues(7.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(Dimensions.IconCircleLarge)
                    .mbClickable(pressedScale = 0.92f, onClick = onIconClick)
                    .semantics { contentDescription = iconDescription }
                    .clip(CircleShape)
                    .background(AppColors.IconCircleFill)
                    .border(1.dp, AppColors.Edge2, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                MbIcon(icon = icon.light, contentDescription = null, size = 20.dp, tint = AppColors.IconCircleInk)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .mbClickable(pressedScale = 0.95f, onClick = onAction)
                    .then(if (actionDescription != null) Modifier.semantics { contentDescription = actionDescription } else Modifier)
                    .clip(CircleShape)
                    .border(1.dp, Color(0x735BD86A), CircleShape)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = actionLabel,
                    style = AppTypography.Chip.copy(fontSize = 13.sp),
                    color = Color(0xFFB9F3C0),
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Water's card: "+250 mL" (or "+8 oz") adds that amount to the day on screen at once, and reads
 * "Added" for a moment so the tap is visible.
 */
@Composable
private fun WaterQuickCard(
    unitSystem: UnitSystem,
    onOpenWater: () -> Unit,
    onQuickAdd: (onAdded: () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    var justAdded by remember { mutableStateOf(false) }
    LaunchedEffect(justAdded) {
        if (justAdded) {
            delay(1200)
            justAdded = false
        }
    }
    val amount = if (unitSystem == UnitSystem.IMPERIAL) "8 oz" else "250 mL"
    TrackerCard(
        icon = Ph.Drop,
        iconDescription = "Water tracker",
        onIconClick = onOpenWater,
        actionLabel = if (justAdded) "Added" else "+$amount",
        onAction = { onQuickAdd { justAdded = true } },
        modifier = modifier,
        actionDescription = "Add $amount of water"
    )
}

/** The day's totals, opened from the calories panel's corner button. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DailySummarySheet(summary: DailyNutritionSummary, onDismiss: () -> Unit) {
    MbSheet(onDismiss = onDismiss, title = "Daily nutrition") {
        Text(
            text = "Intake: ${summary.totalCaloriesIntake.roundToInt()} / ${summary.calorieGoal.roundToInt()} kcal",
            style = AppTypography.Header3,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        SummaryMacroLine(label = "Protein", grams = summary.totalProteinGrams, color = AppColors.MacroProtein)
        SummaryMacroLine(label = "Carbohydrates", grams = summary.totalCarbsGrams, color = AppColors.MacroCarbs)
        SummaryMacroLine(label = "Fat", grams = summary.totalFatGrams, color = AppColors.MacroFat)
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp, modifier = Modifier.padding(vertical = 10.dp))
        Text(
            text = "Dietary Fiber: ${formatOptionalTotal(summary.totalFiberGrams, "g")}",
            style = AppTypography.Body2,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(vertical = 2.dp)
        )
        Text(
            text = "Total Sugars: ${formatOptionalTotal(summary.totalSugarGrams, "g")}",
            style = AppTypography.Body2,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(vertical = 2.dp)
        )
        Text(
            text = "Sodium: ${formatOptionalTotal(summary.totalSodiumMg, "mg")}",
            style = AppTypography.Body2,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(vertical = 2.dp)
        )
    }
}

@Composable
private fun SummaryMacroLine(label: String, grams: Double, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(AppSpacing.sm))
        Text(
            text = "$label: ${String.format(Locale.US, "%.1f", grams)}g",
            style = AppTypography.Body1,
            color = color
        )
    }
}

/** A serving description's leading amount and its unit: "2 chapati", "100 g". */
private val ServingAmount = Regex("""^(\d+(?:\.\d+)?)\s*(.*)$""")

/** "2 chapati", "1.5 bowl": the amount logged in the serving's own words. */
internal fun formatDiaryEntrySubtitle(entry: DiaryEntry): String {
    val qty = entry.quantity
    val qtyStr = if (qty % 1.0 == 0.0) qty.toInt().toString() else String.format(java.util.Locale.US, "%.1f", qty)
    val desc = entry.serving.description.trim()

    val match = ServingAmount.find(desc)
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
