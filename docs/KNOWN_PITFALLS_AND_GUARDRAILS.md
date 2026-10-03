# Known Pitfalls & Architectural Guardrails

This document serves as a living catalog of historical bugs, dangerous patterns, and architectural traps in the MacroBase codebase. AI agents and developers must consult this document before modifying core calculations, storage schemas, or UI layouts.

---

## 1. Catalog of Pitfalls & Solutions

### Pitfall 1: Non-ASCII Mojibake Glitch (`â€¢`)
- **Symptoms**: UI text displays corrupted characters like `â€¢ Protein`, `â€¢ Carbohydrates`.
- **Root Cause**: Kotlin source code files saved with UTF-8 non-ASCII characters (e.g., `•` bullet point) being read or compiled on Windows environments under default platform encodings (Windows-1252).
- **Guardrail**:
  - Never hardcode raw non-ASCII bullets (`•`) directly into string literals.
  - In Compose, use custom UI indicator badges (`Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))`).
  - If a string bullet is strictly required, use the Unicode escape sequence `"\u2022"`.

---

### Pitfall 2: Room Entity Secondary Nutrient Omission (Data Loss)
- **Symptoms**: Dietary fiber, sugar, and sodium values display as `0.0g` / `0.0mg` in the Daily Summary dialog and Nutrition Facts, even though the logged food has positive values.
- **Root Cause**: `DiaryEntryEntity` in Room omitted columns for `loggedFiber`, `loggedSugar`, and `loggedSodium`. While `Food` and `Nutrition` supported secondary nutrients, writing an entry to Room dropped them, and reading back mapped them to `null` $\rightarrow$ `0.0`.
- **Guardrail**:
  - Database schema is at `USER_DATABASE_VERSION = 3`.
  - Columns `loggedFiber`, `loggedSugar`, and `loggedSodium` (`REAL NOT NULL DEFAULT 0.0`) are persisted in `diary_entries`.
  - `MIGRATION_2_3` safely migrates older databases without data loss.
  - When adding any new nutrient, you must update:
    1. `DiaryEntryEntity` (Room entity)
    2. `UserDatabase` migration script (`MIGRATION_X_Y`)
    3. `DatabaseConfig.USER_DATABASE_VERSION`
    4. `DiModules.kt` Room builder
    5. `DiaryRepositoryImpl` (`addEntry`, `addEntries`, `updateEntry`, `toDomain`)

---

### Pitfall 3: Custom Food Portion Multiplier Collapse (1-2 Calorie Bug)
- **Symptoms**: Creating a custom food with `servingSize = 100.0, unit = g` and logging 1 portion collapses the food down to 1% or 2% of its actual calories (e.g., a 250 kcal granola logs as 2.5 kcal $\approx$ 2 kcal).
- **Root Cause**: `CustomFood.toFood()` set `Serving.quantity = 100.0`. In `CalculateNutritionForServingUseCase`, the multiplier was computed as:
  $$\text{multiplier} = \frac{\text{userQuantity}}{\text{serving.quantity}} = \frac{1.0}{100.0} = 0.01$$
- **Guardrail**:
  - For custom foods, the entered nutrition is for **1 base portion**.
  - `CustomFood.toFood()` and `CustomFoodEntity.toDomainFood()` set the default serving to `quantity = 1.0` (with description e.g. `"100 g"`).
  - For gram/milliliter foods where `servingSize > 1.0`, an explicit secondary `"1 g"` or `"1 ml"` sub-portion is generated.
  - `CalculateNutritionForServingUseCase` explicitly checks for custom foods: if selecting the default serving, $\text{multiplier} = \text{userQuantity}$. Sub-portions scale proportionally against the default portion's gram weight.

---

### Pitfall 4: Integer Truncation Discrepancies (`.toInt()`)
- **Symptoms**: The macro progress strip displays `32g Protein`, but tapping Daily Summary shows `33.0g Protein`. Or a 1999.8 kcal day displays as `1999 kcal` instead of `2000 kcal`.
- **Root Cause**: Using `.toInt()` on floating-point values performs downwards truncation (e.g., `32.8.toInt() == 32`, `1999.8.toInt() == 1999`).
- **Guardrail**:
  - **Always use `roundToInt()`** from `kotlin.math.roundToInt` or `kotlin.math.round(...).toInt()` for integer displays and conversions.
  - Never use `.toInt()` for display values of macros, calories, or weight.

---

### Pitfall 5: ID Space Collision & Hijacking
- **Symptoms**: Selecting built-in food ID `1` opens custom food ID `1`, or built-in foods fail to load in `FoodDetailScreen`.
- **Root Cause**: Auto-incrementing primary keys in SQLite (`built_in_foods.db`) and Room (`custom_foods`, `recipes`) overlap in the range $1, 2, 3 \dots$.
- **Guardrail**:
  - Always enforce ID partitioning constants:
    - `CUSTOM_FOOD_ID_OFFSET = 100_000_000L`
    - `RECIPE_FOOD_ID_OFFSET = 200_000_000L`
  - In `FoodRepositoryImpl`:
    - If $\text{id} \ge \text{RECIPE\_FOOD\_ID\_OFFSET} \implies \text{Recipe food}$.
    - If $\text{id} \ge \text{CUSTOM\_FOOD\_ID\_OFFSET} \implies \text{Custom user food}$.
    - Otherwise $\implies \text{Built-in catalog food}$.

---

### Pitfall 6: Compose Layout Crash in Scrolling Containers (`Modifier.weight`)
- **Symptoms**: Fatal crash `IllegalStateException: Vertically scrollable component was measured with an infinity maximum height constraints` when opening `AppDrawer` or dialogs.
- **Root Cause**: Using `Modifier.weight(1f)` inside a `Column` that has `Modifier.verticalScroll()`. A scrolling column gives children infinite vertical measurement constraints, making `weight` mathematically undefined.
- **Guardrail**:
  - Never apply `Modifier.weight()` directly inside a scrollable container.
  - To push content to the bottom of a scrollable drawer/screen, either place the bottom content outside the scrollable Column in a parent Box/Column, or use explicit spacing.

---

### Pitfall 7: Locale-Sensitive Decimal Parsing & Formatting
- **Symptoms**: Numbers format with commas (e.g. `12,5 g`) or crash with `NumberFormatException` when parsing text in regions with non-US decimal separators.
- **Root Cause**: Omitting `Locale.US` in `String.format("%.1f", value)`.
- **Guardrail**:
  - **Always explicitly pass `Locale.US`**: `String.format(Locale.US, "%.1f", value)`.

---

### Pitfall 8: Remote API Violations & Privacy Leakage
- **Symptoms**: App attempts to reach Google Gemini, cloud vision APIs, or remote servers.
- **Root Cause**: Introducing cloud OCR adjudicators or remote telemetry.
- **Guardrail**:
  - MacroBase has a strict zero-cloud privacy guarantee.
  - The Android Manifest does not declare `android.permission.INTERNET`.
  - All OCR adjudications run on-device via ML Kit and PaddleOCR.

---

### Pitfall 9: Unbounded File I/O & Zip Bomb Denial-of-Service
- **Symptoms**: Out-of-memory crash or freeze when restoring a user backup.
- **Root Cause**: Calling `readBytes()` on unbounded archive streams or failing to validate file extraction paths.
- **Guardrail**:
  - In `BackupArchiveManager.kt`:
    - Maximum archive file size: 50 MB.
    - Maximum single entry size: 50 MB.
    - Maximum total extracted bytes: 100 MB.
    - Maximum files allowed: 50.
    - Strict zip path traversal checks (rejecting `..`, `/`, `\`, `:`).

---

## 2. Summary Table of Developer Guardrails

| Subsystem | Dangerous Pattern | Safe / Mandatory Pattern |
| :--- | :--- | :--- |
| **UI Formatting** | `•` in string literal | Circular badge / `\u2022` |
| **Number Display** | `value.toInt()` | `value.roundToInt()` |
| **Decimal String** | `String.format("%.1f", v)` | `String.format(Locale.US, "%.1f", v)` |
| **Custom Food Multiplier** | `userQty / serving.quantity` | `CalculateNutritionForServingUseCase` default serving |
| **Room Migrations** | Destructive recreation | Non-destructive `MIGRATION_X_Y` with `DEFAULT` |
| **ID Resolution** | Direct entity ID | Offset IDs (`CUSTOM_FOOD_ID_OFFSET`) |
| **Compose Layout** | `Modifier.weight()` in `verticalScroll` | Non-scrolling parent or fixed spacing |
| **Network** | Any network call | 100% on-device processing |
| **Micronutrients** | `fiber ?: 0.0` | Preserve `null` if unanalyzed |
