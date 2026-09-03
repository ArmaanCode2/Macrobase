import re

with open('app/src/main/java/com/macrobase/app/feature/detail/FoodDetailScreen.kt', 'r', encoding='utf-8') as f:
    text = f.read()

new_view_model = \"\"\"class FoodDetailViewModel(
    private val getFoodDetailsUseCase: GetFoodDetailsUseCase,
    private val calculateNutritionForServingUseCase: CalculateNutritionForServingUseCase,
    private val addFoodToBasketUseCase: AddFoodToBasketUseCase,
    private val getBasketItemsUseCase: GetBasketItemsUseCase,
    private val updateBasketItemUseCase: UpdateBasketItemUseCase,
    private val commitSingleBasketItemUseCase: CommitSingleBasketItemUseCase,
    private val updateDiaryEntryUseCase: UpdateDiaryEntryUseCase,
    private val deleteDiaryEntryUseCase: DeleteDiaryEntryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(FoodDetailUiState())
    val uiState: StateFlow<FoodDetailUiState> = _uiState.asStateFlow()

    private var editingEntryId: Long? = null
    private var editingBasketItemId: String? = null

    fun loadFood(foodId: Long, mealType: MealType? = null, date: LocalDate? = null, entryId: Long? = null, basketItemId: String? = null) {
        editingEntryId = if (entryId != null && entryId > 0L) entryId else null
        editingBasketItemId = basketItemId
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val item = getFoodDetailsUseCase(foodId)

            if (item == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Food record not found") }
                return@launch
            }

            val servings = if (item.servings.isNotEmpty()) {
                item.servings
            } else {
                listOf(Serving(description = "100 g", gramWeight = 100.0, isDefault = true))
            }

            // If editing basket item, load its state
            val basketItem = editingBasketItemId?.let { id -> getBasketItemsUseCase().value.firstOrNull { it.id == id } }
            
            val defaultServing = basketItem?.serving ?: (servings.firstOrNull { it.isDefault } ?: servings.first())
            val initialQty = basketItem?.quantity ?: 1.0
            
            val calculated = calculateNutritionForServingUseCase(item, defaultServing, initialQty)
            val split = MacroCalorieSplit.fromNutrition(calculated)
            
            val targetDate = basketItem?.date ?: (date ?: LocalDate.now())
            val targetMealType = basketItem?.mealType ?: (mealType ?: MealType.BREAKFAST)

            if (editingBasketItemId == null && editingEntryId == null) {
                val newId = addFoodToBasketUseCase(
                    food = item,
                    serving = defaultServing,
                    quantity = initialQty,
                    calculatedNutrition = calculated,
                    date = targetDate,
                    mealType = targetMealType
                )
                editingBasketItemId = newId
            }

            _uiState.update {
                it.copy(
                    food = item,
                    availableServings = servings,
                    selectedServing = defaultServing,
                    enteredQuantity = initialQty,
                    quantityInputText = initialQty.toString().removeSuffix(".0"),
                    calculatedNutrition = calculated,
                    macroCalorieSplit = split,
                    targetMealType = targetMealType,
                    targetDate = targetDate,
                    isLoading = false,
                    errorMessage = null
                )
            }
        }
    }

    private fun saveCurrentStateToBasket() {
        if (editingBasketItemId != null) {
            val state = _uiState.value
            val food = state.food ?: return
            val serving = state.selectedServing ?: return
            val existingItem = getBasketItemsUseCase().value.firstOrNull { it.id == editingBasketItemId }
            if (existingItem != null) {
                val updatedItem = existingItem.copy(
                    quantity = state.enteredQuantity,
                    serving = serving,
                    calculatedNutrition = state.calculatedNutrition,
                    date = state.targetDate,
                    mealType = state.targetMealType
                )
                updateBasketItemUseCase(updatedItem)
            }
        }
    }

    fun onQuantityChange(newQuantityText: String) {
        val qty = newQuantityText.toDoubleOrNull() ?: 0.0
        val food = _uiState.value.food ?: return
        val serving = _uiState.value.selectedServing ?: return

        val calculated = calculateNutritionForServingUseCase(food, serving, qty)
        val split = MacroCalorieSplit.fromNutrition(calculated)

        _uiState.update {
            it.copy(
                enteredQuantity = qty,
                quantityInputText = newQuantityText,
                calculatedNutrition = calculated,
                macroCalorieSplit = split
            )
        }
        saveCurrentStateToBasket()
    }

    fun onServingSelected(serving: Serving) {
        val food = _uiState.value.food ?: return
        val qty = _uiState.value.enteredQuantity

        val calculated = calculateNutritionForServingUseCase(food, serving, qty)
        val split = MacroCalorieSplit.fromNutrition(calculated)

        _uiState.update {
            it.copy(
                selectedServing = serving,
                calculatedNutrition = calculated,
                macroCalorieSplit = split
            )
        }
        saveCurrentStateToBasket()
    }

    fun onMealTypeSelected(mealType: MealType) {
        _uiState.update { it.copy(targetMealType = mealType) }
        saveCurrentStateToBasket()
    }
    
    fun onDateSelected(date: LocalDate) {
        _uiState.update { it.copy(targetDate = date) }
        saveCurrentStateToBasket()
    }

    fun logFood(onSuccess: () -> Unit) {
        val state = _uiState.value
        val food = state.food ?: return
        val serving = state.selectedServing ?: return

        viewModelScope.launch {
            if (editingEntryId != null && editingEntryId!! > 0L) {
                val entry = DiaryEntry(
                    id = editingEntryId ?: 0L,
                    uuid = UUID.randomUUID().toString(),
                    date = state.targetDate,
                    mealType = state.targetMealType,
                    food = food,
                    serving = serving,
                    quantity = state.enteredQuantity,
                    calculatedNutrition = state.calculatedNutrition,
                    loggedAt = Instant.now()
                )
                updateDiaryEntryUseCase(entry)
            } else if (editingBasketItemId != null) {
                saveCurrentStateToBasket()
                commitSingleBasketItemUseCase(editingBasketItemId!!)
            }

            _uiState.update { it.copy(isSavedSuccess = true) }
            onSuccess()
        }
    }
    
    fun keepInBasket(onSuccess: () -> Unit) {
        saveCurrentStateToBasket()
        _uiState.update { it.copy(isSavedSuccess = true) }
        onSuccess()
    }

    fun deleteEntry(onSuccess: () -> Unit) {
        val id = editingEntryId ?: return
        viewModelScope.launch {
            deleteDiaryEntryUseCase(id)
            onSuccess()
        }
    }

    fun isEditingDiary(): Boolean = editingEntryId != null && editingEntryId!! > 0L
    fun isEditingBasket(): Boolean = editingBasketItemId != null
}\"\"\"

pattern = re.compile(r'class FoodDetailViewModel\(.*?fun isEditingBasket\(\): Boolean = editingBasketItemId != null\s*\}', re.DOTALL)
text = pattern.sub(new_view_model, text)

with open('app/src/main/java/com/macrobase/app/feature/detail/FoodDetailScreen.kt', 'w', encoding='utf-8') as f:
    f.write(text)
