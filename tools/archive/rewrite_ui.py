import re

with open('app/src/main/java/com/macrobase/app/feature/detail/FoodDetailScreen.kt', 'r', encoding='utf-8') as f:
    text = f.read()

new_ui = \"\"\"@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodDetailScreen(
    foodId: Long,
    viewModel: FoodDetailViewModel,
    mealType: MealType? = null,
    date: LocalDate? = null,
    entryId: Long? = null,
    basketItemId: String? = null,
    onLogFoodComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(foodId, mealType, date, entryId, basketItemId) {
        viewModel.loadFood(foodId, mealType, date, entryId, basketItemId)
    }

    if (uiState.isLoading) {
        Box(modifier = modifier.fillMaxSize().background(AppColors.Background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AppColors.Primary)
        }
        return
    }

    val food = uiState.food ?: return

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
    ) {
        // HEADER
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Placeholder for optional food image if it existed in original
            Column(modifier = Modifier.weight(1f)) {
                Text(food.name, style = AppTypography.Header3, color = AppColors.TextPrimary)
                val subtitle = food.brand ?: food.category ?: "USDA Standard Reference"
                Text(subtitle, style = AppTypography.Caption, color = AppColors.TextSecondary)
            }
        }
        
        Spacer(modifier = Modifier.height(AppSpacing.xs))

        // QUANTITY & SERVING
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = uiState.quantityInputText,
                onValueChange = { viewModel.onQuantityChange(it) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(2.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppColors.Primary,
                    unfocusedBorderColor = AppColors.Divider,
                    focusedTextColor = AppColors.TextPrimary,
                    unfocusedTextColor = AppColors.TextPrimary,
                    focusedContainerColor = AppColors.Surface,
                    unfocusedContainerColor = AppColors.Surface
                )
            )

            var dropdownExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.weight(3f).height(50.dp)) {
                OutlinedTextField(
                    value = uiState.selectedServing?.description ?: "100 g",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = AppColors.TextSecondary) },
                    modifier = Modifier.fillMaxWidth().clickable { dropdownExpanded = true },
                    shape = RoundedCornerShape(2.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.Primary,
                        unfocusedBorderColor = AppColors.Divider,
                        focusedTextColor = AppColors.TextPrimary,
                        unfocusedTextColor = AppColors.TextPrimary,
                        focusedContainerColor = AppColors.Surface,
                        unfocusedContainerColor = AppColors.Surface
                    )
                )
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                    modifier = Modifier.background(AppColors.SurfaceAlt)
                ) {
                    uiState.availableServings.forEach { serving ->
                        DropdownMenuItem(
                            text = { Text(serving.description, color = AppColors.TextPrimary) },
                            onClick = {
                                viewModel.onServingSelected(serving)
                                dropdownExpanded = false
                            }
                        )
                    }
                }
            }
            
            // Calorie preview in line
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = AppSpacing.sm)) {
                Text(String.format("%.0f", uiState.calculatedNutrition.calories), style = AppTypography.Header2, color = AppColors.CalorieText)
                Text("cal", style = AppTypography.Caption, color = AppColors.TextSecondary)
            }
        }

        // CALORIES & MACROS STRIP
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = AppSpacing.sm)
                .background(AppColors.Surface, RoundedCornerShape(2.dp))
                .border(1.dp, AppColors.Divider, RoundedCornerShape(2.dp))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Total Calories", style = AppTypography.Body1, color = AppColors.TextPrimary)
                Text(String.format("%.0f", uiState.calculatedNutrition.calories), style = AppTypography.Body1, color = AppColors.CalorieText)
            }
            HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.xs),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MacroValue(label = "Protein", value = uiState.calculatedNutrition.proteinGrams, color = AppColors.MacroProtein)
                HorizontalDivider(modifier = Modifier.height(16.dp).size(1.dp), color = AppColors.Divider)
                MacroValue(label = "Carb", value = uiState.calculatedNutrition.carbsGrams, color = AppColors.MacroCarbs)
                HorizontalDivider(modifier = Modifier.height(16.dp).size(1.dp), color = AppColors.Divider)
                MacroValue(label = "Fat", value = uiState.calculatedNutrition.fatGrams, color = AppColors.MacroFat)
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.xs))

        // WHEN (MEAL & DATE)
        var isEditingWhen by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth().clickable { isEditingWhen = !isEditingWhen }.padding(vertical = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val formatter = java.time.format.DateTimeFormatter.ofPattern("EEE, MM/dd/yyyy")
            val today = LocalDate.now()
            val yesterday = today.minusDays(1)
            val dateLabel = when (uiState.targetDate) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> uiState.targetDate.format(formatter)
            }
            Text("When: , ", style = AppTypography.Body1, color = AppColors.TextPrimary)
            Icon(Icons.Default.Edit, contentDescription = "Edit When", tint = AppColors.TextSecondary, modifier = Modifier.size(16.dp))
        }

        if (isEditingWhen) {
            // MEAL BUTTONS
            Column(
                modifier = Modifier.fillMaxWidth().border(1.dp, AppColors.Divider, RoundedCornerShape(2.dp))
            ) {
                val meals = MealType.entries
                Row(modifier = Modifier.fillMaxWidth()) {
                    MealShortcutButton(meals[0], uiState.targetMealType, Modifier.weight(1f)) { viewModel.onMealTypeSelected(it) }
                    MealShortcutButton(meals[1], uiState.targetMealType, Modifier.weight(1f)) { viewModel.onMealTypeSelected(it) }
                    MealShortcutButton(meals[3], uiState.targetMealType, Modifier.weight(1f)) { viewModel.onMealTypeSelected(it) }
                }
                HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
                Row(modifier = Modifier.fillMaxWidth()) {
                    MealShortcutButton(meals[2], uiState.targetMealType, Modifier.weight(1f)) { viewModel.onMealTypeSelected(it) }
                    MealShortcutButton(meals[4], uiState.targetMealType, Modifier.weight(1f)) { viewModel.onMealTypeSelected(it) }
                    MealShortcutButton(meals[4], uiState.targetMealType, Modifier.weight(1f), isPlaceholder = true) {} // Layout balance
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.xs))

            // DATE SHORTCUTS
            Row(modifier = Modifier.fillMaxWidth().border(1.dp, AppColors.Divider, RoundedCornerShape(2.dp))) {
                val today = LocalDate.now()
                val yesterday = today.minusDays(1)
                
                DateShortcutButton("Yesterday", yesterday, uiState.targetDate, Modifier.weight(1f)) { viewModel.onDateSelected(yesterday) }
                DateShortcutButton("Today", today, uiState.targetDate, Modifier.weight(1f)) { viewModel.onDateSelected(today) }
                
                var showDatePicker by remember { mutableStateOf(false) }
                val isCustomDate = uiState.targetDate != today && uiState.targetDate != yesterday
                
                TextButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(0.dp),
                    colors = ButtonDefaults.textButtonColors(containerColor = if (isCustomDate) AppColors.SurfaceAlt else Color.Transparent)
                ) {
                    Text("Choose a day", style = AppTypography.Caption, color = if (isCustomDate) AppColors.Primary else AppColors.TextPrimary)
                }
                
                if (showDatePicker) {
                    val initialMillis = uiState.targetDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                    val datePickerState = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = initialMillis)
                    
                    androidx.compose.material3.DatePickerDialog(
                        onDismissRequest = { showDatePicker = false },
                        confirmButton = {
                            TextButton(onClick = {
                                datePickerState.selectedDateMillis?.let { millis ->
                                    val selectedDate = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                                    viewModel.onDateSelected(selectedDate)
                                }
                                showDatePicker = false
                            }) { Text("Confirm", color = AppColors.Primary) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDatePicker = false }) { Text("Cancel", color = AppColors.Primary) }
                        },
                        colors = androidx.compose.material3.DatePickerDefaults.colors(containerColor = AppColors.Surface)
                    ) {
                        androidx.compose.material3.DatePicker(
                            state = datePickerState,
                            colors = androidx.compose.material3.DatePickerDefaults.colors(
                                titleContentColor = AppColors.TextPrimary,
                                headlineContentColor = AppColors.TextPrimary,
                                weekdayContentColor = AppColors.TextSecondary,
                                subheadContentColor = AppColors.TextSecondary,
                                yearContentColor = AppColors.TextPrimary,
                                currentYearContentColor = AppColors.Primary,
                                selectedYearContentColor = AppColors.TextPrimary,
                                selectedYearContainerColor = AppColors.Primary,
                                dayContentColor = AppColors.TextPrimary,
                                disabledDayContentColor = AppColors.TextDisabled,
                                selectedDayContentColor = AppColors.TextPrimary,
                                disabledSelectedDayContentColor = AppColors.TextDisabled,
                                selectedDayContainerColor = AppColors.Primary,
                                disabledSelectedDayContainerColor = AppColors.TextDisabled,
                                todayContentColor = AppColors.Primary,
                                todayDateBorderColor = AppColors.Primary,
                                containerColor = AppColors.Surface,
                                dividerColor = AppColors.Divider
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // PRIMARY ACTION
        val primaryActionText = if (viewModel.isEditingDiary()) "Update Logged Food" else "Log 1 Food"
        Button(
            onClick = { viewModel.logFood(onSuccess = onLogFoodComplete) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(2.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary, contentColor = AppColors.TextPrimary)
        ) {
            Text(primaryActionText, style = AppTypography.Button)
        }

        if (viewModel.isEditingDiary()) {
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            Button(
                onClick = { viewModel.deleteEntry(onSuccess = onLogFoodComplete) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(2.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.ProgressOver, contentColor = AppColors.TextPrimary)
            ) {
                Text("Delete Entry", style = AppTypography.Button)
            }
        } else if (!viewModel.isEditingDiary()) {
            TextButton(
                onClick = { viewModel.keepInBasket(onSuccess = onLogFoodComplete) },
                modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.xs)
            ) {
                Text("Keep in Basket", color = AppColors.TextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        DetailedNutritionFactsCard(
            foodName = food.name,
            servingDescription = uiState.selectedServing?.description ?: "",
            nutrition = uiState.calculatedNutrition
        )
        
        Spacer(modifier = Modifier.height(AppSpacing.xl))
    }
}

@Composable
fun MealShortcutButton(meal: MealType, selectedMeal: MealType, modifier: Modifier = Modifier, isPlaceholder: Boolean = false, onClick: (MealType) -> Unit) {
    if (isPlaceholder) {
        Box(modifier = modifier)
        return
    }
    val isSelected = meal == selectedMeal
    TextButton(
        onClick = { onClick(meal) },
        modifier = modifier,
        shape = RoundedCornerShape(0.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = if (isSelected) AppColors.SurfaceAlt else Color.Transparent)
    ) {
        Text(meal.displayName, style = AppTypography.Caption, color = if (isSelected) AppColors.TextPrimary else AppColors.TextSecondary)
    }
}

@Composable
fun DateShortcutButton(label: String, date: LocalDate, selectedDate: LocalDate, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val isSelected = date == selectedDate
    TextButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(0.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = if (isSelected) AppColors.SurfaceAlt else Color.Transparent)
    ) {
        Text(label, style = AppTypography.Caption, color = if (isSelected) AppColors.TextPrimary else AppColors.TextSecondary)
    }
}

@Composable
fun MacroValue(label: String, value: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(String.format("%.0fg ", value), style = AppTypography.Caption, color = color)
        Text(label, style = AppTypography.Caption, color = AppColors.TextSecondary)
    }
}
\"\"\"

pattern = re.compile(r'@OptIn\(ExperimentalMaterial3Api::class\)\s*@Composable\s*fun FoodDetailScreen.*?fun MacroValue.*?\}', re.DOTALL)
text = pattern.sub(new_ui, text)

# Just to be safe, if MacroValue isn't in original, replace to the end
pattern2 = re.compile(r'@OptIn\(ExperimentalMaterial3Api::class\)\s*@Composable\s*fun FoodDetailScreen.*', re.DOTALL)
text = pattern2.sub(new_ui, text)

with open('app/src/main/java/com/macrobase/app/feature/detail/FoodDetailScreen.kt', 'w', encoding='utf-8') as f:
    f.write(text)
