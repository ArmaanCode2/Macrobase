# Data Pipeline & Flow Documentation

## 1. Overview

This document outlines the complete data lineage from raw USDA datasets to the runtime Android application presentation.

---

## 2. Ingestion to Runtime Flow

```
[ Raw USDA Foundation JSON ]        [ Raw USDA SR Legacy JSON ]
            │                                     │
            └─────────────────┬───────────────────┘
                              │
                              ▼
            [ Ingestion & Normalization Engine ]
            - Standardizes 247 nutrients to 100g/100mL base
            - Generates 14,641 structured portion servings
            - Normalizes text & compiles 9,343 search aliases
            - Builds FTS5 search index with diacritic stripping
                              │
                              ▼
            [ `built_in_foods.db` (Production Asset) ]
            - 45.8 MB SQLite database
            - Packed at `app/src/main/assets/databases/built_in_foods.db`
                              │
                              ▼
            [ BuiltInDatabaseManager ]
            - First launch: atomic copy to internal app storage
            - Subsequent launches: reuse installed instance
            - Opens read-only on Dispatchers.IO
                              │
                              ▼
            [ LocalFoodDatabaseProvider ]
            - FTS5 tokenized & prefix search (e.g. `apple*`, `greek* yogurt*`)
            - Single food lookup & portion extraction
            - Strict preservation of nullable unanalyzed nutrients
                              │
                              ▼
            [ FoodRepository & Use Cases ]
            - SearchFoodsUseCase
            - GetFoodDetailsUseCase
            - CalculateNutritionForServingUseCase
                              │
                              ▼
            [ ViewModels & Compose UI ]
            - SearchViewModel  ──>  SearchScreen
            - FoodDetailViewModel  ──>  FoodDetailScreen
            - HomeViewModel  ──>  HomeScreen
```

---

## 3. Provider Extension Pipeline

Adding a new food provider (e.g. Open Food Facts or Indian Food Database) adheres to the identical downstream pipeline:

```
[ External Data Source ]
          │
          ▼
[ New FoodDataProvider implementation ]
(e.g., OpenFoodFactsProvider)
          │
          ▼
[ Domain Model Mapping (Food, Serving, Nutrition) ]
          │
          ▼
[ FoodRepositoryImpl (Federated Search & ID Resolution) ]
          │
          ▼
[ Existing Use Cases & UI — ZERO CODE CHANGES ]
```

---

## 4. Unified Food Logging Pipeline & Journey

```
+─────────────────────────────────────────────────────────────────────────+
|                           FOOD DISCOVERY                                |
|   Built-in FTS Search  •  On-Device Label OCR  •  Custom Foods & Recipes|
+────────────────────────────────────┬────────────────────────────────────+
                                     │ Food Selected (foodId / draft)
                                     ▼
+─────────────────────────────────────────────────────────────────────────+
|                          FoodDetailScreen                               |
|   Select portion unit & enter quantity -> Live nutrition calculation    |
+───────────────────┬─────────────────────────────────┬───────────────────+
                    │ [NEW FOOD / STAGING MODE]       │ [EDIT MODE]
                    │ (foodId > 0, entryId == null)   │ (entryId > 0)
                    ▼                                 ▼
+───────────────────────────────────────+ +───────────────────────────────+
|           BasketScreen                | |       DiaryRepository         |
|   - Staging in InMemoryBasketRepo     | |   - updateDiaryEntryUseCase() |
|   - Bulk edit: meal type, date, qty   | |   - Direct Room UPDATE        |
|   - Review total macros & calories    | +───────────────────────────────+
+───────────────────┬───────────────────+
                    │ User taps "Log All Foods"
                    ▼
+───────────────────────────────────────+
|          CommitBasketUseCase          |
|   - Converts BasketItems -> Entries   |
|   - Transactional Room batch INSERT   |
|   - Clears InMemoryBasketRepository   |
+───────────────────┬───────────────────+
                    │
                    ▼
+─────────────────────────────────────────────────────────────────────────+
|                   macrobase_user.db (Room: diary_entries)               |
|   - Snapshots: calories, macros, secondary nutrients, portion & weight  |
+───────────────────┬─────────────────────────────────────────────────────+
                    │ Reactive Flow observation
                    ▼
+─────────────────────────────────────────────────────────────────────────+
|                      Home Dashboard Screen                              |
|   - DailyNutritionSummary: intake, remaining balance, macro strips      |
+─────────────────────────────────────────────────────────────────────────+
```

### 4.1. New Food Logging Mode vs. Diary Entry Edit Mode
- **New Food Logging Mode**:
  - Activated when opening a food from Search, Recents, Favorites, Scanner, or Custom Foods (`entryId == null`).
  - The item CANNOT be committed directly to Room from `FoodDetailScreen`. Instead, tapping "Add to Basket" routes into `InMemoryBasketRepository`.
  - The user can queue multiple foods, modify their portion quantities, align their meal slots or dates, and review aggregated nutrition before atomic commit via `CommitBasketUseCase`.
- **Diary Entry Edit Mode**:
  - Activated when tapping an existing logged meal row on the Home Dashboard (`entryId > 0`).
  - Bypasses the Basket. Saving updates the existing `diary_entries` row in Room via `UpdateDiaryEntryUseCase`.
  - Dynamically recalculates missing secondary nutrients if older logs lack fiber/sugar/sodium while catalog data is present.

