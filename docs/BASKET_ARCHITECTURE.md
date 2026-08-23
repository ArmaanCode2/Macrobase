# Centralized Food Basket Architecture

## Core Philosophy
The Centralized Food Basket is a crucial architectural component introduced to ensure that food items are not immediately committed to the historical diary database upon selection. 

**Core Rule**: Adding food to the diary MUST pass through the Basket. The diary database (`DiaryEntryEntity`) is modified ONLY when the user presses the final "Log Food / Submit Basket" button. Direct logging from Food Detail, Search, Recipes, or Custom Foods is forbidden.

## Basket Lifetime & Scope
- **State Medium**: In-memory `StateFlow` held within a Hilt/Koin Singleton (`InMemoryBasketRepository`).
- **Survives**: Navigation events, recomposition, configuration changes (device rotation).
- **Cleared**: On explicit submission (successful transaction commit to `DiaryRepository`), explicit cancellation, or application process death. It does not survive application restart, ensuring stale partial logs do not persist indefinitely.

## BasketItem Model
The `BasketItem` acts as a snapshot of the food precisely at the moment the user confirms portion and quantity.
It explicitly snapshots:
- `foodNameSnapshot` and `brandSnapshot`
- `calculatedNutrition` (pre-calculated scaled macros and calories)
- `quantity` and `serving`
- Target `date` and `mealType`

This guarantees that if the underlying Custom Food or Recipe is mutated while the item sits pending in the basket, the pending log remains historically accurate to what the user actually saw.

## Date & Meal Policies
- **Adding from Dashboard**: Defaults to the selected dashboard date and meal slot (e.g., if the user taps '+' on "Lunch", the basket item receives `mealType = LUNCH`).
- **Adding from generic Food Search**: Defaults to `LocalDate.now()` and a reasonable generic meal type (e.g., `LUNCH`).
- **Batch Editing**: The Basket screen allows applying a uniform date and meal type to all pending items.

## Transactional Commit Behavior
When "Log Food" is triggered:
1. All `BasketItem`s are converted to `DiaryEntry`s.
2. The list is passed to `DiaryRepository.addEntries()`, which commits the list as a single transactional batch into Room.
3. Upon success, the `BasketRepository` is cleared, and the UI routes back to the Dashboard.
4. Existing logged foods edited from the Dashboard continue to bypass the basket (they represent an update to an *already committed* historical fact, not a new pending log).

## Recipe Handling
Recipes are treated as composite entities but are logged as a single atomic `BasketItem`. The nutritional scaling occurs during the `AddFoodToBasketUseCase` phase. Recipes do not dump their individual constituent ingredients into the basket or diary; they log a single flattened `Food` item representing the recipe.
