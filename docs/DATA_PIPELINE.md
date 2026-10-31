# Data Pipeline & Flow Documentation

## 1. Overview

This document outlines the complete data lineage from the Indian Nutrient Databank (INDB) recipes to the runtime Android application presentation.

---

## 2. Ingestion to Runtime Flow

```
  [ macrobase_indian_foods_canonical.csv ]      [ USDA FoodData Central + UK CoFID 2021 ]
  - 1,014 INDB recipes, per 100 g                 - tools/catalog/import_basic_foods.py
  - Portion weights from INDB per-unit energy     - 190 foods -> tools/catalog/new_foods.csv
                                                  - SR Legacy, Foundation, FNDDS and CoFID
                              │
                              ▼
            [ tools/catalog/build_catalog.py ]
            - Applies exclusions, frying-oil and value corrections, aliases (tools/catalog/*.csv)
            - Works out 49 standard recipes from their ingredients (recipes.csv)
            - Checks ids, names, nutrients and portion weights; writes nothing on failure
            - Refuses new content without a new database_version
                              │
                              ▼
            [ `built_in_foods.db` (Production Asset) ]
            - 2.1 MB SQLite database, 977 foods
            - Packed at `app/src/main/assets/databases/built_in_foods.db`
                              │
                              ▼
            [ BuiltInDatabaseManager ]
            - First launch: atomic copy to internal app storage
            - Subsequent launches: reuse installed instance
            - Opens read-only on Dispatchers.IO
                              │
                              ▼
            [ branded_foods.db: Open Food Facts, ODbL ]
            - tools/catalog/import_branded_foods.py: 1,632 checked products sold in India
            - tools/catalog/build_branded.py: the same tables and checks, a separate file
                              │
                              ▼
            [ LocalFoodDatabaseProvider ]
            - Searches both catalogs and merges by how well each food matched
            - Word search on the food_search table: every query word starts a word of the
              name, an alias or a spelling of one, ranked by common foods first
              (Android's SQLite has no FTS5); a substring search catches fragments
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

Adding a new food provider adheres to the identical downstream pipeline (Open Food Facts already ships as branded_foods.db, read by the same provider):

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
|   Built-in Word Search •  On-Device Label OCR  •  Custom Foods & Recipes|
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

