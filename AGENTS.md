# AGENTS.md — MacroBase AI Agent Operating Guidelines & Project Invariants

> **Audience**: Autonomous AI coding agents, pair programmers, and human maintainers.  
> **Purpose**: Establish permanent guardrails, strict architectural invariants, mathematical contracts, and implementation rules across the MacroBase Android codebase.

---

## 1. System Philosophy & Non-Negotiable Invariants

MacroBase is an offline-first, Android-only nutrition-tracking application engineered for absolute privacy, zero latency, and provider independence.

### Core Architectural Invariants
1. **Zero Cloud / Zero Network Surface**:
   - The Android Manifest does **NOT** declare `android.permission.INTERNET`.
   - Never introduce network calls, remote telemetry, analytics SDKs, cloud LLMs, or HTTP clients.
   - All calculations, database lookups, and OCR operations run strictly on-device.
2. **Strict Unidirectional Dependency Flow**:
   $$\text{UI (Jetpack Compose)} \longrightarrow \text{ViewModel (MVVM / UDF)} \longrightarrow \text{Use Case} \longrightarrow \text{Repository} \longrightarrow \text{Storage / Provider}$$
   - **Composables must NEVER query Room or SQLite directly.** All data access flows through ViewModels exposing immutable `StateFlow<UiState>`.
   - ViewModels dispatch actions to pure Domain Use Cases.
   - Repositories abstract underlying Room entities, SQLite cursors, or DataStore preferences into clean Domain models (`Food`, `Serving`, `Nutrition`, `DiaryEntry`).
3. **Hard Separation Between Built-in and User Data**:
   - **Asset Database (`built_in_foods.db`)**: Read-only SQLite database bundled at `app/src/main/assets/databases/built_in_foods.db`. Managed via `BuiltInDatabaseManager` and `LocalFoodDatabaseProvider`. Must be opened with `SQLiteDatabase.OPEN_READONLY`. **Never write to or alter the asset database at runtime.**
   - **User Database (`macrobase_user.db`)**: Read/write AndroidX Room database (`UserDatabase`) storing personal logs (`diary_entries`, `custom_foods`, `recipes`, `weight_entries`, `water_logs`).
   - **User Preferences**: Daily goals and display preferences persist via AndroidX DataStore (`user_goals`, `user_prefs`).
4. **Immutable Historical Snapshot Strategy**:
   - When a user logs a food or recipe, `DiaryEntryEntity` stores the food reference **and** snapshots the calculated nutritional values (`loggedCalories`, `loggedProtein`, `loggedCarbs`, `loggedFat`, `loggedFiber`, `loggedSugar`, `loggedSodium`, `loggedSaturatedFat`, `loggedTransFat`, `loggedCholesterol`, `servingDescription`, `gramWeight`). The six secondary nutrient columns are nullable: `null` means the food did not state the value, `0.0` means it has none (section 2.4).
   - If the built-in database is updated, or if a custom food / recipe is later edited or deleted, **historical diary entries must remain 100% immutable and accurate**. Never retroactively modify or cascade-delete historical logs.

---

## 2. Mathematical Contracts & Calculation Rules

### 2.1. Portion Scaling & Multipliers
- **Built-in Foods (`FoodSource.BUILT_IN`)**:
  - The nutrient values in `foods` are standardized per **100g** (or 100mL).
  - Scaled using `Serving.calculateGramMultiplier(userQuantity)`:
    $$\text{multiplier} = \frac{(\text{gramWeight} / \text{quantity}) \times \text{userQuantity}}{100.0}$$
  - A `"100 g"` serving represents 1 portion unit (`quantity = 1.0`, `gramWeight = 100.0`).
- **Custom Foods (`FoodSource.CUSTOM_USER` / `food.isUserOwned`)**:
  - Handled by `CalculateNutritionForServingUseCase`.
  - The entered nutrition is the baseline for **1 base portion** of that food.
  - Selecting the default serving with `userQuantity = 1.0` yields $\text{multiplier} = 1.0$ (100% of entered nutrition).
  - Selecting a sub-portion (e.g., `"1 g"` or `"1 ml"`) scales proportionally against the default portion's gram weight:
    $$\text{multiplier} = \text{userQuantity} \times \left(\frac{\text{serving.gramWeight}}{\text{defaultServing.gramWeight}}\right)$$
  - Never divide `userQuantity / serving.quantity` when serving size is 100g; that erroneously scales nutrition down to 1%.

### 2.2. Mathematical Rounding vs. Truncation
- **Strict Rule**: **NEVER** use downwards truncation `.toInt()` on floating point nutrient calculations or displays.
- Always use `roundToInt()` (from `kotlin.math.roundToInt`) or `kotlin.math.round(...).toInt()`:
  - Correct: `meal.totalCalories.roundToInt()`, `${proteinGrams.roundToInt()}g`
  - Incorrect: `meal.totalCalories.toInt()` (causes a 1999.8 kcal intake to report as 1999 kcal instead of 2000 kcal, producing discrepancies between progress strips and detail dialogs).

### 2.3. Net Carbohydrates Formula
$$\text{Net Carbs} = \max(0.0, \text{Carbohydrates} - \text{Dietary Fiber})$$
- Evaluated non-negatively via `(carbsGrams - (fiberGrams ?: 0.0)).coerceAtLeast(0.0)`.

### 2.4. Explicit Nullability for Micronutrients
- Missing or unanalyzed micronutrient data points are represented as nullable types (`Double? = null`), **never silently coerced to `0.0`**.
- Display rules:
  - If a nutrient is `null`: display `-` (or omit in compact strips).
  - If a nutrient is `0.0`: display `0.0 g` (or `0.0 mg`).
- Merging / addition rule: `addNullable(a, b)` returns `null` if and only if both `a == null` and `b == null`. If either is non-null, null is treated as `0.0`.

### 2.5. Recipe Calculations
- A recipe's total nutrition is the exact sum of its constituent ingredient nutrients scaled by their selected portion weights:
  $$\text{Total Nutrition} = \sum_{i=1}^{N} \text{CalculateNutritionForServingUseCase}(\text{food}_i, \text{serving}_i, \text{quantity}_i)$$
- Per-serving nutrition divides total nutrition by `servingsProduced`:
  $$\text{Nutrition Per Serving} = \frac{\text{Total Nutrition}}{\text{servingsProduced}}$$
- A recipe is logged into the basket/diary as a single atomic `Food` item; never explode recipe ingredients into individual diary logs.

---

## 3. Localization, Formatting & Character Encoding

### 3.1. Explicit Locale.US Requirement
- Always pass `Locale.US` explicitly to all `String.format()` invocations:
  - Correct: `String.format(Locale.US, "%.1f", value)`
  - Incorrect: `String.format("%.1f", value)` (formats decimals with commas `,` in European locales like German or French, breaking string parsers and decimal UI formatting).

### 3.2. Zero Mojibake Rule
- Never hardcode raw UTF-8 non-ASCII characters directly in Kotlin string literals if there is any chance of encoding corruption (e.g. `•` becoming `â€¢` under Windows-1252 compilation).
- Preferred options:
  1. Use Compose layout primitives with circular indicator badges (`Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))`).
  2. If a bullet symbol is required in string text, use the Unicode escape sequence: `"\u2022"`.

---

## 4. Storage, Room Migrations & ID Space Rules

### 4.1. Room Database Versions & Migrations
- Active Room database: `macrobase_user.db` (`DatabaseConfig.USER_DATABASE_NAME`).
- Current version: `DatabaseConfig.USER_DATABASE_VERSION = 3`.
- Registered migrations:
  - `MIGRATION_1_2`: Added `customUnitName TEXT` to `custom_foods`.
  - `MIGRATION_2_3`: Added nullable `loggedFiber`, `loggedSugar`, `loggedSodium`, `loggedSaturatedFat`, `loggedTransFat` and `loggedCholesterol` (`REAL`, no default) to `diary_entries`. Rows from version 2 get `null` (not recorded).
- **Migration Invariant**:
  - All Room migrations must be non-destructive (`ALTER TABLE ... ADD COLUMN ...`, either nullable or with a `DEFAULT`).
  - The database is built only by `UserDatabase.create()`. It has **no** `fallbackToDestructiveMigration()`: a missing migration or a downgrade must throw, never wipe the diary. Never add a destructive fallback.
  - `exportSchema = true`: Room writes each version to `app/schemas/com.macrobase.app.data.database.UserDatabase/<version>.json`. Commit the JSON for every version; the migration tests build old databases from it.
  - When bumping schema version, define `MIGRATION_X_Y` in `UserDatabase.Companion`, add it to `UserDatabase.ALL_MIGRATIONS`, bump `DatabaseConfig.USER_DATABASE_VERSION`, and add a migration test from the previous schema JSON (see `P3PhaseOneDataSafetyTests`).

### 4.2. ID Space Partitioning
To prevent primary key collisions across federated food sources within a unified `Food` model:
- **Built-in Foods**: IDs in the range `1` to `99,999,999`.
- **Custom User Foods**: IDs offset by `CUSTOM_FOOD_ID_OFFSET = 100_000_000L`:
  $$\text{foodId} = 100\_000\_000\text{L} + \text{customFoodEntity.id}$$
- **Recipes**: IDs offset by `RECIPE_FOOD_ID_OFFSET = 200_000_000L`:
  $$\text{foodId} = 200\_000\_000\text{L} + \text{recipeEntity.id}$$
- Use `isUserOwned` and `FoodSource` (`BUILT_IN`, `CUSTOM_USER`, `RECIPE`) to check origin.

---

## 5. Food Basket & Diary Logging Pipeline

```
+─────────────────────────────────────────────────────────────────────────+
|                      FOOD DISCOVERY & INSPECTION                        |
|   SearchScreen / LabelScannerScreen / CustomFoods / Recipes             |
+────────────────────────────────────┬────────────────────────────────────+
                                     │ User selects a food item
                                     ▼
+─────────────────────────────────────────────────────────────────────────+
|                          FoodDetailScreen                               |
|   - Select serving portion & enter quantity                             |
|   - Live calculated nutrition display (calories, macros, micronutrients)|
+───────────────────┬─────────────────────────────────┬───────────────────+
                    │ [NEW FOOD / STAGING MODE]       │ [EDIT MODE]
                    │ User taps "Add to Basket"       │ User taps "Save Changes"
                    ▼                                 ▼
+───────────────────────────────────────+ +───────────────────────────────+
|          BasketRepository             | |       DiaryRepository         |
|   - In-memory StateFlow singleton     | |   - updateEntry(diaryEntry)   |
|   - BasketItem snapshot created       | |   - Room UPDATE transaction   |
+───────────────────┬───────────────────+ +───────────────────────────────+
                    │ User reviews & taps "Log All Foods"
                    ▼
+───────────────────────────────────────+
|          CommitBasketUseCase          |
|   - Transactionally converts items    |
|   - Inserts DiaryEntryEntity records  |
|   - Clears BasketRepository           |
+───────────────────┬───────────────────+
                    │
                    ▼
+─────────────────────────────────────────────────────────────────────────+
|                           UserDatabase (Room)                           |
|   - Table: diary_entries (immutable historical snapshot columns)        |
+───────────────────┬─────────────────────────────────────────────────────+
                    │ Reactive Flow observation
                    ▼
+─────────────────────────────────────────────────────────────────────────+
|                         Home Dashboard Screen                           |
|   - DailyNutritionSummary: intake, remaining balance, macro strips      |
+─────────────────────────────────────────────────────────────────────────+
```

### Pipeline Rules:
1. **New logs MUST pass through the Basket**: Direct insertion into `diary_entries` from Food Detail, Search, or Scanner is forbidden.
2. **Edit Mode bypasses the Basket**: When a user taps an existing diary entry from the Dashboard, `FoodDetailScreen` operates in Edit Mode (`editingEntryId > 0`). Saving updates the existing record directly via `updateDiaryEntryUseCase(entry)`.
3. **Food Detail Edit Mode Recalculation Guard**: If an existing logged entry has `null` (never recorded) secondary nutrients, fill only those from `catalogFood?.nutrition` when it exists and has a positive value, safely preserving historical snapshots when catalog food is absent. A logged `0.0` is a real zero and is never refilled.

---

## 6. Verification Checklist for Changes

Before submitting or approving any modification:
- [ ] Run `./gradlew testDebugUnitTest` — must pass with 0 failures and 0 errors.
- [ ] Run `./gradlew assembleDebug` — must compile and build cleanly.
- [ ] Ensure all `String.format()` calls explicitly pass `Locale.US`.
- [ ] Verify no raw `â€¢` mojibake or unescaped non-ASCII characters exist in UI strings.
- [ ] Verify all floating-point calorie/macro numbers are rounded using `roundToInt()`.
- [ ] Verify Room schema migrations remain non-destructive (nullable or with default values), and that `app/schemas` holds the JSON for the new version.
