# MacroBase — Technical Architecture Specification

## 1. System Philosophy & Architecture Principles

**MacroBase** is an offline-first, Android-only nutrition-tracking application engineered for absolute privacy, zero latency, and provider independence.

### Architectural Invariants:
1. **Strict Unidirectional Dependency Flow**:
   $$\text{UI (Jetpack Compose)} \longrightarrow \text{ViewModel} \longrightarrow \text{Use Case} \longrightarrow \text{Repository} \longrightarrow \text{Data Source / Provider}$$
2. **Provider Agnosticism**: The Domain and UI layers are completely decoupled from any single food catalog or database format. The built-in SQLite catalog (`built_in_foods.db`) is treated as only **one** possible data provider.
3. **Hard Separation Between Built-in and User Data**:
   - **Built-in Food Data (`built_in_foods.db`)**: Read-only static catalog loaded from bundled asset storage (`app/src/main/assets/databases/built_in_foods.db`). Never mutated by user actions.
   - **User Data (`macrobase_user.db`) & Preferences (DataStore)**: Read/write user personal data (meal diary, custom foods, recipes, body weight logs, water intake) stored in AndroidX Room; user preferences and daily macro/calorie goals persisted via AndroidX DataStore.
4. **Historical Correctness for Diary Entries (Hybrid Snapshot Strategy)**:
   - When a user logs a food or recipe to their diary, `DiaryEntryEntity` stores the stable food reference (`foodId`, `uuid`, `sourceName`) **and** snapshots the calculated nutritional values (`loggedCalories`, `loggedProtein`, `loggedCarbs`, `loggedFat`) and portion description (`servingDescription`, `gramWeight`, `userQuantity`). If `built_in_foods.db` is updated, or if a custom food / recipe / daily goal is later edited or deleted, historical diary logs remain 100% immutable, readable, and accurate.
5. **No Direct Storage Access in UI**: Composables observe immutable `StateFlow<UiState>` emitted by ViewModels. UI never queries SQLite, Room, or DataStore directly.
6. **Explicit Nullability for Missing Nutrients**: Unanalyzed or missing micronutrient data points are represented as nullable (`null`), never silently coerced to `0.0`.

---

## 2. Layer Responsibilities & Component Architecture

```
+───────────────────────────────────────────────────────────────────────────────+
|                             UI LAYER (Jetpack Compose)                        |
|   HomeScreen • SearchScreen • FoodDetailScreen • CustomFoods • Recipes        |
|   CalendarScreen • DailyGoalsScreen • PreferencesScreen • ...                 |
+───────────────────────────────────────┬───────────────────────────────────────+
                                        │ Observes StateFlow<UiState>
                                        ▼
+───────────────────────────────────────────────────────────────────────────────+
|                               VIEWMODEL LAYER                                 |
|   HomeViewModel • CalendarViewModel • DailyGoalsViewModel • ...               |
+───────────────────────────────────────┬───────────────────────────────────────+
                                        │ Dispatches Use Cases
                                        ▼
+───────────────────────────────────────────────────────────────────────────────+
|                               USE CASE LAYER                                  |
|   GetDailyDiaryUseCase • GetCalendarAdherenceUseCase • GetGoalsUseCase ...    |
+───────────────────────────────────────┬───────────────────────────────────────+
                                        │ Interacts with pure Domain Models
                                        ▼
+───────────────────────────────────────────────────────────────────────────────+
|                              REPOSITORY LAYER                                 |
|   DiaryRepository • GoalsRepository • PreferencesRepository • FoodRepository  |
+───────────────────────────────────────┬───────────────────────────────────────+
                                        │
        ┌───────────────────────────────┼───────────────────────────────┐
        ▼                               ▼                               ▼
+───────────────────────+ +───────────────────────────+ +───────────────────────+
|   FOOD PROVIDER       | |     USER DATA STORAGE     | |     PREFERENCES       |
|  FoodDataProvider     | |  Room Database (AndroidX) | |  DataStore (AndroidX) |
| LocalFoodDatabaseProv | | `macrobase_user.db` (Room)| | user_goals, user_prefs|
| (`built_in_foods.db`) | | Diary, CustomFood, Recipe | | Daily goals, metrics  |
+───────────────────────+ +───────────────────────────+ +───────────────────────+
```

---

## 3. Food Logging Calendar Architecture (Phase 9)

### 3.1. Calendar ViewModel & State Flow
- `CalendarViewModel` manages `_displayedYearMonth: MutableStateFlow<YearMonth>` (defaulting to current month).
- UI State is exposed via `StateFlow<CalendarUiState>` constructed using reactive Kotlin Coroutine operators:
  ```kotlin
  val uiState: StateFlow<CalendarUiState> = _displayedYearMonth.flatMapLatest { ym ->
      combine(
          getCalendarAdherenceUseCase.observe(ym.year, ym.monthValue),
          goalsRepository.observeGoals()
      ) { summaries, goals ->
          calculateMonthState(ym, summaries, goals)
      }
  }.stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5000), initialValue = CalendarUiState())
  ```

### 3.2. Month Query & Aggregation Strategy
- `DiaryDao.observeMonthlyCalorieSummaries(startEpochDay, endEpochDay)` executes a single aggregated SQLite query:
  ```sql
  SELECT dateEpochDay, SUM(loggedCalories) as totalCal
  FROM diary_entries
  WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay
  GROUP BY dateEpochDay
  ```
- Executes off the main thread on `Dispatchers.IO`, loading all days of the month in $O(1)$ database trips without loading full diary entries into memory.

### 3.3. Performance Classification Integration
- Evaluates $C_{logged}$ against $G_{daily}$ using `CalendarPerformanceConfig.evaluatePerformance`:
  - $C = 0 \text{ or } G \le 0 \implies \text{EMPTY\_MISSED}$ (`AppColors.CalendarEmpty`)
  - $0 < C \le 0.85 \times G \implies \text{UNDER\_BUDGET}$ (`AppColors.CalendarGreenSubtle`)
  - $0.85 \times G < C \le 1.05 \times G \implies \text{OPTIMAL\_TARGET}$ (`AppColors.CalendarGreenOptimal`)
  - $1.05 \times G < C \le 1.20 \times G \implies \text{MODERATE\_OVER}$ (`AppColors.CalendarRedWarning`)
  - $C > 1.20 \times G \implies \text{HIGH\_OVER\_TARGET}$ (`AppColors.CalendarRedAlert`)

### 3.4. Calendar Summary Metrics
- **Days Missed**: Number of eligible past or current dates ($date \le \text{today}$) within the displayed month where $C = 0$. Future dates are excluded.
- **% Days of Green**: Percentage of eligible logged days ($C > 0$ and $date \le \text{today}$) achieving `OPTIMAL_TARGET`:
  $$\text{greenPercentage} = \text{round}\left(\frac{\text{optimalDaysCount}}{\text{loggedDaysCount}} \times 100\right)$$
  (Denominator: eligible logged days in the month).

### 3.5. Calendar $\longleftrightarrow$ Dashboard Navigation Contract
- Tapping any calendar day cell invokes `onDateClick(date)`, triggering `navController.navigate(Screen.Home.createRoute(tappedDate.toEpochDay()))`.
- The Dashboard displays the exact historical date tapped (with logged foods or empty 0-cal state).
- Pressing Android Back returns directly to `CalendarScreen` with the selected month preserved.

### 3.6. Goal Dependency & Real-Time Reactivity
- The calendar dynamically combines the active user calorie goal from `GoalsRepository.observeGoals()`. If the user changes their daily calorie goal (e.g. from 2000 to 1800 kcal), all cell classifications, colors, and Green % metrics immediately recalculate without app restart.

### 3.7. Historical Snapshot Dependency
- Calendar adherence derives directly from immutable snapshot values persisted in `diary_entries`. If underlying foods or recipes are modified/deleted in later app versions, historical calendar totals remain permanently preserved.

---

## 4. Water Hydration Tracking Architecture (Phase 10)

### 4.1. Canonical Milliliter Storage & Unit Conversions
- All water logs in `macrobase_user.db` (`water_logs` table) store water quantities in canonical milliliters (`amountMl: Double`).
- When the user selects Metric (`mL` / `L`) or Imperial (`fl oz`) in `UserPreferences`, `UnitConversions` dynamically handles display formatting:
  - Metric: `amountMl` displayed directly as milliliters (e.g. `1,750 mL`).
  - Imperial: `UnitConversions.mlToFlOz(amountMl)` dynamically converts to fluid ounces (e.g. `59 fl oz`).
- Input from Custom dialogs is converted to canonical milliliters prior to database insertion.

### 4.2. Authoritative Water Goal Source of Truth
- The authoritative daily hydration target is loaded from `PreferencesRepository.observePreferences()` (`dailyWaterGoalMl`).
- Modifying the daily water goal in Preferences immediately recalculates progress ratios, percentages, remaining quantities, and visual progress indicators across both the Water Tracker screen and the Home Dashboard without requiring an application restart.

### 4.3. Historical Selected-Date Behavior
- `WaterViewModel` and `HomeScreen` respect the date context:
  $$\text{Calendar / Dashboard (SelectedDate)} \longrightarrow \text{Water Tracker (SelectedDate)} \longrightarrow \text{Quick Add / Custom Amount} \longrightarrow \text{Logs Persisted to SelectedDate}$$
- Logging water for an older date (e.g. August 18) persists strictly to August 18 (`dateEpochDay = 20676`), leaving today's (August 19) totals untouched.

### 4.4. Reactive Flow Pipeline & UI Updates
- `WaterViewModel` combines:
  1. `GetWaterForDateUseCase` $\rightarrow$ aggregate day total from Room
  2. `GetWaterEntriesUseCase` $\rightarrow$ individual day entries list from Room
  3. `PreferencesRepository.observePreferences()` $\rightarrow$ active water goal and unit system from DataStore
- Emits unified `StateFlow<WaterUiState>`, driving smooth progress animations via `animateFloatAsState` without blocking background database transactions.

---

## 5. Body Weight Tracking & Graphs Architecture (Phase 11)

### 5.1. Canonical Kilogram Storage & Unit Conversion
- All body weight records in `macrobase_user.db` (`weight_entries` table) store weights canonically in kilograms (`weightKg: Double`).
- When the user toggles Metric (`kg`) vs Imperial (`lb`) in `UserPreferences`, `UnitConversions.kgToLbs` and `UnitConversions.lbsToKg` convert dynamically at the UI presentation boundary:
  - Metric: Displays directly in kg (e.g. `82.5 kg`).
  - Imperial: $1\text{ kg} \approx 2.20462\text{ lb}$ (e.g. `181.9 lb`).
- Internal database storage remains strictly unpolluted by mixed units.

### 5.2. Single-Entry-Per-Day Replacement Policy
- The `weight_entries` table enforces a unique index on `dateEpochDay`:
  ```sql
  CREATE TABLE IF NOT EXISTS weight_entries (
      id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
      dateEpochDay INTEGER NOT NULL,
      weightKg REAL NOT NULL,
      note TEXT,
      createdAt INTEGER NOT NULL
  );
  CREATE UNIQUE INDEX IF NOT EXISTS index_weight_entries_dateEpochDay ON weight_entries(dateEpochDay);
  ```
- Re-logging or editing a weigh-in for a given calendar date replaces that day's entry via `@Insert(onConflict = OnConflictStrategy.REPLACE)`, ensuring deterministic historical records and preventing ambiguous duplicate entries.

### 5.3. Date Range Queries & Interval Filtering
- `WeightDao.getWeightEntriesBetween(startEpochDay, endEpochDay)` executes a bounded range query on the indexed `dateEpochDay` column:
  ```sql
  SELECT * FROM weight_entries
  WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay
  ORDER BY dateEpochDay ASC
  ```
- Intervals supported: `"Last 30 days"`, `"Last 3 months"`, `"Last 6 months"`, `"1 Year"`, `"All Time"`, and `"Custom"`.

### 5.4. Metric Strip Calculations (Start / Current / Change)
- For the selected date range containing $N \ge 1$ entries sorted by date ascending:
  - **Start Weight**: $W_{start} = \text{entries.first().weightKg}$
  - **Current Weight**: $W_{current} = \text{entries.last().weightKg}$
  - **Delta Change**: $\Delta W = W_{current} - W_{start}$
  - **Percentage Change**: $\Delta \% = \left(\frac{\Delta W}{W_{start}}\right) \times 100\%$
  - **Target Delta**: $\Delta_{target} = W_{current} - W_{target}$ (using authoritative `targetWeightKg` from `PreferencesRepository`).

### 5.5. Dedicated Canvas Cubic Bezier Line Graph
- Rendered using Jetpack Compose `Canvas` with zero third-party chart dependencies:
  - Dynamic Y-axis bounded by $\lfloor\min(W) - 1\rfloor$ to $\lceil\max(W) + 1\rceil$.
  - Cubic Bezier spline constructed across data points with `#81D4FA` stroke (2.5dp).
  - Vertical gradient fill under curve from `rgba(129, 212, 250, 0.25)` to `rgba(18, 18, 18, 0.0)`.
  - Circular data points with white inner fill (6dp) and cyan border (2dp).
  - Empty state threshold: $< 2$ entries displays guidance prompt and `+ Log Weight` button.

---

## 6. Statistics & Food Logging Heatmap Architecture (Phase 12)

### 6.1. Multi-Tab Architecture & ViewModel Pipeline
- `StatisticsViewModel` exposes a unified `StateFlow<StatisticsUiState>` managing three sub-modules:
  1. **Weight Stats**: Reuses the Phase 11 `WeightLineGraph` and 3-column metric strip (START, CURRENT, CHANGE) over configurable ranges.
  2. **Nutrition Consistency**: GitHub-style Weekly Matrix Heatmap (Sun–Sat rows $\times$ week columns) with interactive day inspection and Consistency Score.
  3. **Macro Averages**: Daily intake averages ($C, P, C, F$) and Target vs Actual split ratios ($P\% \times 4, C\% \times 4, F\% \times 9$).

### 6.2. High-Performance Range Queries
- Rather than querying every meal item, `DiaryDao.observeDateNutritionSummaries(startEpochDay, endEpochDay)` executes a bounded aggregation query grouped by `dateEpochDay`:
  ```sql
  SELECT dateEpochDay, 
         SUM(loggedCalories) as totalCal,
         SUM(loggedProtein) as totalProtein,
         SUM(loggedCarbs) as totalCarbs,
         SUM(loggedFat) as totalFat
  FROM diary_entries
  WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay
  GROUP BY dateEpochDay
  ```
- Executes off the main thread on `Dispatchers.IO`, loading months of data in $O(1)$ database trips.

### 6.3. Consistency Score & Heatmap Date Invariants
- For any date range $[D_{start}, D_{end}]$:
  - **Eligible Days**: Calendar days in range where $D \le \text{today}$.
  - **Logged Days**: Eligible days with $C_{logged} > 0$.
  - **Consistency Score**:
    $$\text{Score} = \text{round}\left(\frac{\text{Logged Days}}{\text{Eligible Days}} \times 100\right)$$
  - **Future Days**: $D > \text{today}$ are excluded from denominator and displayed as unlogged placeholders.

### 6.4. Calorie Performance Category Reuse
- Cell color intensity maps directly to `CalendarPerformanceConfig.evaluatePerformance(calories, goal)`:
  - `EMPTY_MISSED` (`AppColors.CalendarEmpty` / `#262626`)
  - `UNDER_BUDGET` (`AppColors.CalendarGreenSubtle` / `#1B5E20`)
  - `OPTIMAL_TARGET` (`AppColors.CalendarGreenOptimal` / `#00C853`)
  - `MODERATE_OVER` (`AppColors.CalendarRedWarning` / `#FFA000` or `#E53935`)
  - `HIGH_OVER_TARGET` (`AppColors.CalendarRedAlert` / `#B71C1C`)

### 6.5. Macro Averages & Target vs Actual Split Formulas
- Denominator is strictly **logged days** ($N_{logged}$ with $C > 0$).
- Average daily macros:
  $$\overline{C} = \frac{\sum C}{N_{logged}}, \quad \overline{P} = \frac{\sum P}{N_{logged}}, \quad \overline{\text{Carb}} = \frac{\sum \text{Carb}}{N_{logged}}, \quad \overline{F} = \frac{\sum F}{N_{logged}}$$
- Calorie energy contribution ratios:
  $$\text{Cals}_P = \overline{P} \times 4, \quad \text{Cals}_{\text{Carb}} = \overline{\text{Carb}} \times 4, \quad \text{Cals}_F = \overline{F} \times 9$$
  $$\text{Actual } P\% = \frac{\text{Cals}_P}{\text{Total Macro Cals}} \times 100\%$$
- Compared directly with active user goal percentages ($P_{target}\%, \text{Carb}_{target}\%, F_{target}\%$) from `GoalsRepository.observeGoals()`.

---

## 7. Import & Export / Data Portability Architecture (Phase 13)

### 7.1. Decoupled Backup DTO Layer
- Portability is completely decoupled from Room internal entity versions and SQLite storage schemas via dedicated DTO models in `BackupModels.kt`:
  - `BackupManifestDto`, `DiaryEntryBackupDto`, `CustomFoodBackupDto`, `RecipeBackupDto`, `WeightEntryBackupDto`, `WaterLogBackupDto`, `GoalBackupDto`, `UserPreferencesBackupDto`.
- Conversion flow:
  - **Export**: Room Entities / DataStore $\rightarrow$ Domain Models $\rightarrow$ Backup DTOs $\rightarrow$ JSON $\rightarrow$ ZIP Archive.
  - **Import**: ZIP Archive $\rightarrow$ Validation $\rightarrow$ JSON $\rightarrow$ Backup DTOs $\rightarrow$ Transactional SQLite / Room Inserts.

### 7.2. Archive Structure & SHA-256 Checksums
- Standard ZIP file format (`MacroBase_Backup_YYYY-MM-DD.zip`):
  - `manifest.json`, `diary.json`, `custom_foods.json`, `recipes.json`, `weight.json`, `water.json`, `goals.json`, `preferences.json`.
- `manifest.json` records SHA-256 digests of each constituent JSON document. During extraction, every file's checksum is verified before database interaction to prevent corrupt or tampered data ingestion.

### 7.3. Merge vs Overwrite Restoration Semantics
- **Merge Mode (Non-Destructive)**:
  - Custom foods and recipes are matched by `uuid`. Identical records are skipped; newer records (`createdAt`) update existing rows.
  - Diary entries are deduplicated by `uuid`.
  - Body weight entries are merged by `dateEpochDay` (newer `createdAt` replaces).
  - Water intake logs are deduplicated by `(dateEpochDay, timestamp, amountMl)`.
- **Overwrite Mode (Destructive)**:
  - Explicit confirmation requested via prominent warning dialog.
  - Clears `diary_entries`, `custom_foods`, `recipes`, `weight_entries`, `water_logs` in `macrobase_user.db`.
  - Replaces all user records transactionally.
  - **Built-in Database Boundary**: `built_in_foods.db` is strictly untouched.

### 7.4. Transactional Safety & Historical Snapshot Integrity
- Database operations are wrapped inside `UserDatabase.runInTransaction { ... }`.
- Any parse exception, corrupt record, or constraint failure triggers an immediate rollback, leaving the existing database in its original clean state.
- Snapshotted nutrition in diary entries is restored byte-for-byte, maintaining historical accuracy without retroactively modifying previous logs.

### 7.5. Android Storage Access Framework (SAF)
- Zero filesystem permissions requested.
- Export invokes `ActivityResultContracts.CreateDocument("application/zip")`.
- Import invokes `ActivityResultContracts.OpenDocument()`.
- Reads and writes stream directly via `ContentResolver.openInputStream` and `ContentResolver.openOutputStream` on background threads (`Dispatchers.IO`).

---

## 8. Security, Privacy & Data Safety Architecture (Phase 16)

### 8.1. Threat Model & Defensive Input Boundaries
- **ZIP Path Traversal Prevention**: Archive entries are scanned for relative or absolute paths (`..`, `/`, `\`, `:`). Any entry violating sandbox boundaries is rejected before decompression.
- **Decompression Bomb Protection**: Hard limits enforce a maximum archive size of 50 MB, maximum entry size of 50 MB, maximum total extraction of 100 MB, and maximum 50 files.
- **Defensive JSON Sanitization**: `SimpleJsonParser` and `JsonObject` defensively sanitize `NaN`, `Infinity`, negative anomalies, and enforce string length caps (50,000 chars) to prevent OutOfMemory and injection attacks.

### 8.2. Storage & Component Isolation
- **Built-in Database Immutability**: `built_in_foods.db` is opened strictly with `SQLiteDatabase.OPEN_READONLY`. User operations cannot mutate or overwrite catalog tables.
- **Zero Network Surface**: Manifest declares zero network permissions (`android.permission.INTERNET` is absent). No HTTP clients, no WebView, no analytics, no cloud backends.
- **Android Auto Backup Isolation**: `data_extraction_rules.xml` and `backup_rules.xml` exclude large immutable catalog databases from system cloud backups.
- **Scoped SAF Permissions**: All backup file operations occur via user-mediated Storage Access Framework with zero broad external storage permissions.

