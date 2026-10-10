# MacroBase P3 Bug Fix Plan

- **Source:** `bug-report/bug-report-2026-10-03-0708.pdf` (static audit, 3 Oct 2026, commit `ddfc057`)
- **Scope:** all 19 P3 findings. P1 BUG-008 and the P4 items are out of scope. P4 items that a P3 fix covers almost for free are noted inline.
- **Re-verified:** 9 Oct 2026 against the working tree (HEAD `49cd90e` plus uncommitted changes). Line numbers below are from that tree. Expect drift; re-check each location before editing.
- **Paths** are relative to `app/src/main/java/com/macrobase/app/` unless they start with `app/`, `gradle/`, `docs/` or `AGENTS.md`.

---

## 1. Status of every P3 bug today

| Bug | Problem (short) | Status on 9 Oct | Phase |
|---|---|---|---|
| BUG-026 | Goal save fails silently (percent sum truncated) | Still present | 3 |
| BUG-027 | Weight edit rejects "72,5" in comma locales | Still present | 3 |
| BUG-028 | Preference inputs not validated; cleared values come back | Still present | 3 |
| BUG-029 | Unit toggle wipes typed preference edits | Still present | 3 |
| BUG-030 | Widget tap never opens Search; back stack lost | Still present | 4 |
| BUG-031 | Pending strategy change cannot be cancelled | Still present | 3 |
| BUG-032 | Rank penalises today before the day ends | Still present | 5 |
| BUG-033 | Stats windows one day too long; score truncated | Still present | 5 |
| BUG-037 | Missing fiber/sugar/sodium stored as 0.0, not null | Fixed 10 Oct (uncommitted) | 1 |
| BUG-038 | Food Detail loses typed quantity on rotation | Still present | 4 |
| BUG-039 | Basket header shows first item only; recipe mode unused | Partly fixed (errors now shown) | 4 |
| BUG-040 | Dashboard stays on yesterday after midnight | Still present | 4 |
| BUG-041 | Scanner: "%DV" number becomes per-100 g value | Still present (worse than reported) | 6 |
| BUG-042 | Scanner: adjudicator warnings dropped, critical ones hidden | Still present | 6 |
| BUG-043 | Scanner: crop OCR coordinates not offset | Still present | 6 |
| BUG-044 | Scanner: gallery image decoded full size on main thread (OOM) | Still present | 6 |
| BUG-045 | Scanner: "Calories from Fat" row hijacks fat and calories | Still present | 6 |
| BUG-046 | Destructive migration fallback can wipe all user data | Fixed 10 Oct (uncommitted) | 1 |
| BUG-047 | Large or crafted backup can crash app; errors dump whole JSON | Still present | 2 |

---

## 2. Before starting

### 2.1 Data-loss hazard in the committed code (urgent)

Commit `372811a` raised `DatabaseConfig.USER_DATABASE_VERSION` from 2 to 3. It did not commit `MIGRATION_2_3`, which exists only as an uncommitted change in `data/database/UserDatabase.kt`. HEAD as committed has three parts:

- DB version 3
- no 2→3 migration
- `.fallbackToDestructiveMigration()`

A build made from HEAD alone will **wipe every user's diary on upgrade** from a v2 build such as v1.0.3. Never ship from HEAD as it stands. Commit the working-tree migration first, or do Phase 1 before any release.

### 2.2 Commit the current working tree

There are 64 or more modified tracked files and several untracked scanner files. Commit or stash them before Phase 0. Each phase then lands as a clean, reviewable diff. Use one branch per phase, for example `fix/p3-phase-1-data-safety`.

### 2.3 Decisions needed from the owner

**Answered by the owner on 10 Oct 2026:**
- **D1:** no real users yet, only the owner's phone, so Path A was used: `MIGRATION_2_3` was rewritten in place.
- **D2:** remove the basket recipe mode.
- **D3:** use option a, where today is provisional.
- **D4:** no blocking screen. No older version was ever released, so a downgrade cannot happen. Without the fallback, Room still throws instead of wiping.
- **D5:** use the proposed ranges.
- **BUG-008:** include it.

The original questions:

| ID | Question | Options | Recommendation |
|---|---|---|---|
| D1 | Has any build with DB schema v3 reached real users? | **A:** no, rewrite `MIGRATION_2_3` in place. **B:** yes, add a v3→v4 migration. | A if v3 is unreleased. It is simpler and gives clean semantics. |
| D2 | Basket "Single Food (Recipe)" mode | Remove it, or build it (basket items become a recipe logged as one item) | Remove now; track "build" as a feature. |
| D3 | Rank on the current day | **a:** today is provisional and never added to the rating. **b:** count today only once it is logged. | a. Option b still penalises a part-logged morning. |
| D4 | DB downgrade once the destructive fallback is gone | Let Room throw, or show a blocking "please update the app" screen | Blocking screen. It is small and avoids a crash loop. |
| D5 | Valid ranges for preferences | Weight 20–500 kg (matches `AddWeightEntryUseCase`), target weight 20–500 kg or blank, height 50–250 cm or blank, water 250–10,000 mL | Use these unless the product owner prefers others. |

---

## 3. Phase overview

| Phase | Theme | Bugs | Size | Depends on |
|---|---|---|---|---|
| 0 | Shared groundwork | none (enablers) | S | — |
| 1 | Protect user data | BUG-046, BUG-037 | L | 0, D1, D4 |
| 2 | Safe backup import | BUG-047 | M | 1 (backup DTO changes) |
| 3 | Goals and preferences | BUG-026, BUG-028, BUG-029, BUG-027, BUG-031 | L | 0, D5 |
| 4 | Logging flow and day rollover | BUG-030, BUG-038, BUG-039, BUG-040 | M | 0, 1 (both touch `FoodDetailScreen.kt`), D2 |
| 5 | Rank and statistics maths | BUG-032, BUG-033 | M | 0, D3 |
| 6 | Label scanner | BUG-044, BUG-042, BUG-041, BUG-045, BUG-043 | L | — |

**Why this order:** losing data is permanent, so Phase 1 comes first. A crash on import is next. Goals that silently fail to save affect every user who edits goals. The logging-flow bugs follow. The rank, stats and scanner bugs are visible and correctable by the user, so they come last.

**Parallel work:** Phases 3, 5 and 6 touch separate files and can run in parallel after Phase 0. Run Phase 4 after Phase 1, because both edit `feature/detail/FoodDetailScreen.kt`. Run Phase 2 after Phase 1, because both edit the backup DTO and serializer.

**Progress (10 Oct 2026):** Phase 0 and Phase 1 are done in the working tree and not committed. BUG-008 was already fixed in the working tree, and the fix was verified. 465 unit tests pass and `assembleDebug` builds. Two deviations from this plan:
- `room-testing` was not added. The migration tests run under Robolectric: they build the old database from `app/schemas/<v>.json` and let Room's own schema check validate the result. This needs no test-asset setup.
- The D4 screen was not built.

**Exit gate for every phase** (AGENTS.md section 6):

- `./gradlew testDebugUnitTest` reports 0 failures and 0 errors.
- `./gradlew assembleDebug` builds cleanly.
- Every new `String.format` passes `Locale.US`.
- No raw non-ASCII characters appear in string literals.
- Nutrient numbers use `roundToInt()`, never `.toInt()`.
- Migrations only add columns with defaults or as nullable.

---

## 4. Phase 0: Shared groundwork

**Goal:** add two small helpers that several later fixes need, so no phase builds its own copy.

### 0.1 Locale-tolerant decimal input

- **New file:** `core/util/DecimalInput.kt`.
  - `fun parseDecimalInput(text: String): Double?`: trim, replace `,` with `.`, call `toDoubleOrNull()`, and reject NaN and infinity.
  - `fun formatDecimalInput(value: Double, decimals: Int = 1): String`: always `String.format(Locale.US, ...)`.
- **Keep** `parseQuantityInput` and `parsePositiveQuantity` in `feature/detail/FoodDetailScreen.kt:115-121` as one-line delegates. `PartialFixCompletionTests` and `BasketScreen.kt:615` call them, so their public signatures must not change.
- **Tests:** "72,5" and "72.5" both give 72.5. "", "-", "abc", "1,2,3" and "NaN" give null. Formatting under `Locale.GERMANY` still produces a `.` separator.

### 0.2 Injectable clock

- In `core/di/DiModules.kt`, register `single<java.time.Clock> { Clock.systemDefaultZone() }`.
- Add a `clock: Clock` constructor parameter to these classes, defaulting to `Clock.systemDefaultZone()` so existing tests still compile:
  - `HomeViewModel` (`feature/dashboard/HomeScreen.kt:100`)
  - `RankRepositoryImpl` (`data/repository/rank/RankRepositoryImpl.kt`)
  - `StatisticsViewModel` and `StatisticsRepositoryImpl` (`feature/statistics/StatisticsScreen.kt`, `data/repository/CoreRepositoryImplementations.kt`)
- Koin `viewModelOf` and `singleOf` resolve every constructor parameter through `get()`, so registering the `Clock` single is required.
- Replace `LocalDate.now()` with `LocalDate.now(clock)` only in the code paths that Phases 4 and 5 change. Do not sweep all 68 call sites.
- **Test helper:** add `MutableTestClock` (instant plus zone, with an `advanceBy(Duration)` method) under `app/src/test/.../testutil/`.

**Exit:** helpers exist and have tests. No user-visible change yet.

---

## 5. Phase 1: Protect user data (BUG-046, BUG-037)

**Goal:** no code path can silently wipe the user database. Diary snapshots record "unknown" as `null`, not `0.0`.

Do BUG-046 first. Its schema export and migration-test setup are what BUG-037's migration is tested with.

### BUG-046: remove the destructive migration fallback

**Now:** `core/di/DiModules.kt:90-100` calls `.addMigrations(MIGRATION_1_2, MIGRATION_2_3).fallbackToDestructiveMigration()`. `data/database/UserDatabase.kt:28` sets `exportSchema = false`. There is no `app/schemas/`, no `room.schemaLocation` and no `room-testing` dependency.

**Steps:**

1. **Turn on schema export.**
   - Set `exportSchema = true` in `UserDatabase.kt`.
   - In `app/build.gradle.kts`, add `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`.
   - Commit the generated `app/schemas/com.macrobase.app.data.database.UserDatabase/3.json`.
2. **Add migration test support.**
   - Add `androidx-room-testing` (version `room` = 2.6.1) to `gradle/libs.versions.toml`.
   - Add it as both `testImplementation` and `androidTestImplementation`.
   - Expose `app/schemas` as test assets: `sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")`, plus the same for `test` if the Robolectric route works (see step 5).
3. **Rebuild the old baselines.**
   - Create a temporary git worktree at `054a547` (v1.0.3, DB v2).
   - Enable `exportSchema` there and build once.
   - Copy `2.json` (and `1.json`, if an older commit allows it) into `app/schemas/`.
   - If this proves impractical, create the v2 tables in the migration test with raw SQL copied from that commit's entities.
4. **Remove the fallback.**
   - Delete `.fallbackToDestructiveMigration()`.
   - D4: wire the blocking screen. Before Koin builds the DB, in `MacroBaseApplication` or the DB provider, read `PRAGMA user_version` from `macrobase_user.db`. Open it with `SQLiteDatabase.OPEN_READONLY` only if the file exists. If the version is greater than `USER_DATABASE_VERSION`, show a full-screen "This data was created by a newer MacroBase. Please update the app." message instead of opening Room.
5. **Write migration tests.**
   - Use `MigrationTestHelper` for v1→v3 and v2→v3. Insert a diary row, a custom food, a recipe, a weight entry and a water log at the old version, migrate, and assert every row survives with the expected values.
   - Prefer Robolectric under `app/src/test` so the tests run in `testDebugUnitTest`. If Robolectric cannot load the schema assets, use `app/src/androidTest` and add `connectedDebugAndroidTest` to the release checklist.
6. **Update docs.**
   - `docs/DATABASE_SCHEMA.md:143-144` still describes the fallback.
   - In AGENTS.md section 4.1, state that `exportSchema` is on and that every version bump needs a schema JSON and a migration test.

**Acceptance:**

- A missing migration fails the migration test in CI instead of wiping data.
- A downgrade shows the blocking message, and the database file is untouched.

### BUG-037: store unknown micronutrients as null

**Now:**

- `data/database/entity/UserEntities.kt:29-31`: `loggedFiber/loggedSugar/loggedSodium: Double = 0.0`.
- `UserDatabase.kt:48-53`: `ADD COLUMN ... REAL NOT NULL DEFAULT 0.0`.
- `data/repository/DiaryRepositoryImpl.kt:109-111, 135-137, 160-162`: each maps `?: 0.0`.
- Saturated fat, trans fat and cholesterol are not snapshotted at all, even though `BasketUseCases.kt:109-124` already passes them in.

**Migration (depends on D1):**

- **Path A (v3 never shipped).** Rewrite `MIGRATION_2_3` to add six nullable columns with no default:
  - `loggedFiber REAL`, `loggedSugar REAL`, `loggedSodium REAL`
  - `loggedSaturatedFat REAL`, `loggedTransFat REAL`, `loggedCholesterol REAL`

  Rows from v2 become `NULL`, meaning unknown. That is the truth: v2 never stored these values.
- **Path B (v3 shipped).** SQLite cannot drop `NOT NULL` with `ALTER`, and AGENTS.md allows only `ADD COLUMN` migrations. Add `MIGRATION_3_4`:
  1. Add six new nullable columns with distinct names, for example `snapFiberG`, `snapSugarG`, `snapSodiumMg`, `snapSatFatG`, `snapTransFatG`, `snapCholesterolMg`.
  2. Run `UPDATE diary_entries SET snapFiberG = NULLIF(loggedFiber, 0.0)`, and the same for sugar and sodium. This treats a legacy `0.0` as unknown, which is what the BUG-010 guard already assumes today.
  3. Leave the old columns in place, unused. Map the entity's `loggedFiber` and the other fields to the new columns with `@ColumnInfo(name = ...)`.
  4. Bump `USER_DATABASE_VERSION` to 4, register the migration in `DiModules.kt:97`, and move `PortabilityConfig.DATABASE_SCHEMA_VERSION` to match.

**Code changes (both paths):**

1. **Entity:** make the three existing fields `Double? = null` and add the three new ones.
2. **`DiaryRepositoryImpl`:** merge the three duplicated mapping blocks (`addEntry` 94-117, `addEntries` 119-143, `updateEntry` 145-167) into one private `DiaryEntry.toEntity()` that passes nulls through. Map all six fields back in `toDomain` (259-267).
3. **Daily summary:**
   - Change `DailyNutritionSummary.totalFiber`, `totalSugar` and `totalSodiumMg` (`domain/model/DailySummaries.kt:18-20`) to `Double?`.
   - Sum them with `addNullable`, which returns null only when every entry is null.
   - Make `feature/dashboard/HomeScreen.kt:550-565` show `-` for null.
4. **Backup:**
   - Add the three new fields to `DiaryEntryBackupDto` (`domain/model/BackupModels.kt:42-65`).
   - In `BackupJsonSerializer.kt`, write them at 103-128 and parse them as nullable at 130-156.
   - In `PortabilityRepositoryImpl.kt`, export them (84-86) and import them without `?: 0.0` (506-508), and fix the comment at 505.
   - Bump the backup format in `PortabilityConfig.kt` from 1.2.0 to 1.3.0, following the comment convention there.
   - A 1.2.0 backup must still restore, with the new fields set to null.
5. **BUG-010 recalculation guard:**
   - `feature/detail/FoodDetailScreen.kt:196-214` treats `null || == 0.0` as missing. Change it to `== null` only, so a real logged 0.0 is never overwritten.
   - Update AGENTS.md section 5.3 ("null/zero" becomes "null").
6. **Dead or wrong display:** `NutritionFactsPanel` (`core/designsystem/components/NutritionAndCalendarComponents.kt:202-232`) turns null into 0 and has no callers. Delete it.
7. **Docs:** update AGENTS.md sections 1.4 and 4.1 and `docs/DATABASE_SCHEMA.md:57, 123-144`.

**Tests:**

- Log a food with `fiberGrams = null`; the reloaded entry has `fiberGrams == null`.
- Log one with `0.0`; it reloads as `0.0`.
- All six fields survive a backup export and import round trip.
- A 1.2.0 backup (no new fields) restores with null values.
- Migration test, Path A: a v2 row has null in all six columns after migrating. Path B: a v3 row with `loggedFiber = 2.5` keeps 2.5, and one with 0.0 becomes null.
- Dashboard summary: all null gives null; one null plus one 3.0 gives 3.0.
- **Existing tests to update:**
  - `PhaseTwoIdentityAndBackupTests.kt`: the `diaryRow` helper (119-146) defaults to 0.0. `assertEquals(Double, Double?, delta)` will not compile. `oldFormatBackup_stillRestores_matchingCustomFoodsByName` (368) should now expect null. `editEntryMissingFiber_fillsFiberButKeepsLoggedCalories` (246) should insert null instead of 0.0.
  - `PartialFixCompletionTests.kt:121-135`.
  - `DiaryLoggingUnitTests.kt:423-457` and `CustomFoodsAndRecipesUnitTests.kt:439-453` (summary types).

**Phase 1 exit:** migration tests are green, the destructive fallback is gone, and history shows `-` for unknown micronutrients.

---

## 6. Phase 2: Safe backup import (BUG-047)

**Goal:** a hostile or very large archive fails with a short, clear message. It never crashes the app or dumps file contents into the UI or logs.

**Now:**

- **Limits:** `data/portability/BackupArchiveManager.kt:26-29` allows 100 MB total uncompressed, 50 MB per entry and 50 entries.
- **Copies in memory:**
  - Each entry is fully buffered and decoded to a `String` (126-162), and unknown entries are kept too.
  - `calculateSha256` copies the content again (200, 328-330).
  - `json.trim()` copies it once more (`BackupJsonSerializer.kt:457, 464`).
- **No OOM handling:** neither the archive manager (309) nor `ImportExportViewModel` (`feature/importexport/ImportExportScreen.kt:184`) catches `OutOfMemoryError`.
- **Raw JSON in error messages:** `BackupJsonSerializer.kt` lines 459, 466 and 514 (`"... in $text"`) embed the whole JSON. Line 531 can embed an arbitrarily long key.
- **Display:** `ImportExportScreen.kt:311-322` renders the message with no `maxLines`.

**Steps:**

1. **Bound the parser messages.**
   - Add `private fun snippet(text: String, pos: Int): String`, which returns at most 40 characters around `pos`.
   - Replace `$json` and `$text` at 459, 466 and 514 with `at pos $pos near "${snippet(...)}"`.
   - Truncate the key at 531 to 40 characters.
2. **Sanitise every message that reaches the UI.**
   - In `BackupArchiveManager` (174, 225-249, 315), build messages through `fun userMessage(e: Throwable): String`.
   - It returns a fixed sentence, plus the exception class and the first 200 characters of `e.message`.
   - Also truncate the echoed entry name at 137.
3. **Allowlist entries.**
   - Buffer only the known names: `manifest.json`, `diary.json`, `custom_foods.json`, `recipes.json`, `weight.json`, `water.json`, plus the goals and preferences entries the current format writes.
   - Skip other entries without buffering them, but still count their bytes toward the total limit.
4. **Cut the in-memory copies.**
   - Compute SHA-256 while streaming (`DigestInputStream`) instead of calling `content.toByteArray()` again.
   - Make the parser skip leading and trailing whitespace instead of calling `trim()`.
5. **Lower the limits from measured data.**
   - Measure bytes per diary entry with the dataset in `PerformanceAndStressTests.importExport_stressTest_largeDataset_zipAndChecksums`.
   - Set the per-entry and total limits to cover a heavy user (about 5 years at 30 entries per day) with 2x headroom. The expected result is a total limit of about 20–32 MB.
   - Add a test that such a backup still validates.
6. **Catch OOM at the boundary.**
   - Catch `OutOfMemoryError` in the validate and import paths of `BackupArchiveManager` and in `ImportExportViewModel.validateAndPreviewBackup`.
   - Map it to "This backup is too large to open on this device."
7. **UI:** give the error `Text` at `ImportExportScreen.kt:311-322` the settings `maxLines = 4` and `overflow = TextOverflow.Ellipsis`.
8. **Logs:** `data/repository/basket/BasketItemsJson.kt:38` logs `e.message`, which contains the basket JSON and its food names. Log only the exception class.
9. **Optional follow-up, not required for this phase:** stream-parse `diary.json` with `android.util.JsonReader`, which runs on-device and needs no network.

**Tests** (extend `SecurityAndPrivacyUnitTests.kt` and the `BackupFixtures` in `P2PhaseTwoBackupTests.kt`):

- A small zip that expands to about 90 MB of zeros is rejected gracefully, with a message under 300 characters.
- A 1 MB malformed `diary.json` produces an error under 300 characters that does not contain the payload.
- An unknown 10 MB entry is ignored and does not cause a failure, unless it breaks the total limit.
- The heavy-user backup passes.
- A view-model test confirms that a simulated OOM in validation sets a friendly `validationError`.

---

## 7. Phase 3: Goals and preferences (BUG-026, 028, 029, 027, 031)

**Goal:** what the user types is what gets saved, in every locale, or the user sees exactly why it was not saved.

Do BUG-026 first, because it is the smallest fix and the most user-visible. BUG-028 and BUG-029 share one refactor, so do them together. BUG-027 and BUG-031 follow.

### BUG-026: macro percentages that sum to 100 fail silently

**Now:**

- `domain/model/Goal.kt:57-67`: `totalPercentage` is `.toInt()`, and `isValidPercentageSum` uses it, so 99.9999 truncates to 99 and the check fails.
- The UI (`PreferencesScreen.kt:238-239`) checks the exact sum.
- `PreferencesViewModel.saveAll` (`PreferencesScreen.kt:129-133`) saves preferences first, then ignores the goal `Result`.
- The deprecated `feature/goals/DailyGoalsScreen.kt:88-91, 141-142` repeats the pattern.

**Steps:**

1. In `Goal.kt`, set `isValidPercentageSum = abs(totalPercentageExact - 100.0) < 0.01`. Change `totalPercentage` to use `roundToInt()` (AGENTS.md section 2.2).
2. Remove the BUG-026 allowlist entry in `app/src/test/.../NutrientRoundingLintTest.kt:33-34`.
3. `UpdateGoalsUseCase` (`domain/usecase/BiometricAndGoalUseCases.kt:138-141`): format the error message with `String.format(Locale.US, "%.1f%%", goal.totalPercentageExact)`.
4. `PreferencesViewModel.saveAll`:
   - Validate the goal first. If it fails, do not write preferences.
   - Expose `saveError: StateFlow<String?>` and render it inline in red above the Save button, as `BasketScreen` does.
   - Call `onSuccess()` only when both writes succeed.
5. `DailyGoalsScreen`: check whether `NavGraph.kt` still reaches it. If it does, apply the same fix. If it does not, delete the screen and its view model and its DI entry (`DiModules.kt:192`).

**Tests:**

- `Goal(30.4, 34.8, 34.8).isValidPercentageSum` is true.
- `Goal(33.5, 33.5, 33.5)` is false.
- A view-model test with an invalid goal: `saveError` is set, `onSuccess` is not called, and preferences are unchanged.
- **Existing tests to update:**
  - `GoalsAndPreferencesUnitTests.kt:266`
  - `DomainArchitectureUnitTests.kt:33-64` (the Int values 100, 80 and 110 still hold under rounding)

### BUG-028 and BUG-029: move the preferences form into the ViewModel

**Now:**

- **Form state:** all of it lives in Compose `remember` blocks.
  - `remember(prefs, selectedUnitSystem)` reseeds the weight, target, height and water fields from stored values when the unit changes (`PreferencesScreen.kt:147-185`).
  - `remember(prefs)` wipes the name and time-zone edits on any new preferences emission.
- **Parsing:** at 855-871 the inputs are parsed with `toDoubleOrNull()`.
- **Water fallback:** `?: 2500.0` is applied before the imperial conversion, so an imperial user gets 73,934 mL.
- **No validation:** there are no range or sign checks (`isFormValid` at 241 covers only calories and macros).
- **Clearing a field does nothing:** `PreferencesRepositoryImpl.kt:55-66` skips nulls, so a cleared field reappears.

**Steps:**

1. **Add form state to the ViewModel.**
   - Add `PreferencesFormState` to `PreferencesViewModel` (`PreferencesScreen.kt:81-136`).
   - Fields: `firstName`, `lastName`, `timeZone`, `unitSystem`, `weightText`, `targetWeightText`, `heightText`, `waterGoalText`, `selectedGoal`, `maintenanceText`, macro fields and `isManuallyOverridden`.
   - Seed it once from the first preferences and goals emission. Later emissions must not overwrite the user's edits.
2. **Convert typed text when the unit changes (BUG-029).**
   - `onUnitSystemChanged(newUnit)` parses each numeric field with `parseDecimalInput`.
   - If a field parses, convert it (kg↔lb, cm↔in, mL↔fl oz through `domain/model/UnitConversions.kt`) and re-format it with `formatDecimalInput`.
   - If it does not parse, keep the text as typed.
   - Re-run strategy auto-detect (`GoalStrategyHelper.autoDetectStrategy`) from the converted kg value, so the strategy matches what the user typed.
3. **Validate each field (BUG-028).**
   - Parse with `parseDecimalInput` and check the D5 ranges.
   - Expose a per-field error, show it with `isError` and supporting text, and fold the errors into `isFormValid` so Save is disabled while any field is invalid.
   - Remove the `?: 2500.0` fallback; an empty water field is now a validation error.
4. **Make clearing a field persist.**
   - In `PreferencesRepositoryImpl.updatePreferences`: `if (value == null) prefs.remove(key) else prefs[key] = value`, for height, current weight and target weight.
5. **Protect backup restore.**
   - Restore calls `updatePreferences(p.toPreferences(current))` at `PortabilityRepositoryImpl.kt:252` and `:397`.
   - Change `toPreferences` (446-455) to `backup.heightCm ?: current.heightCm`, and the same for both weights, so a backup without these values does not clear them.
6. **Write only dirty fields.** This also removes most of P4 BUG-050's unit drift in Preferences at no extra cost.

**Tests:**

- **ViewModel (with `FakePreferencesRepository`):**
  - Clearing the target weight and saving stores null.
  - "-5" for weight is rejected and Save is disabled.
  - "72,5" saves 72.5.
  - Typing 80 kg and switching to Imperial shows "176.4". Switching back shows "80.0".
  - A new preferences emission after typing does not wipe the typed text.
- **Robolectric, real `PreferencesRepositoryImpl`, using the `P2PhaseTwoGoalsRestoreTests` setup:**
  - Saving null removes the key.
  - A restore without a height keeps the stored height.

### BUG-027: weight dialogs in comma locales

**Now:** in `feature/weight/WeightScreen.kt`:

- The log dialog pre-fills with `String.format("%.1f")` (650-656) and parses with `toDoubleOrNull()` (697).
- The edit dialog does the same at 726-731 and 771.
- Display-only formats without `Locale.US` appear at 469, 882, 1184 and 1192.

**Steps:**

1. Extract `internal fun weightPrefillText(kg: Double, imperial: Boolean)`, which calls `formatDecimalInput`.
2. Extract `internal fun parseWeightInput(text: String, imperial: Boolean): Double?`, which calls `parseDecimalInput` and converts to kg.
3. Use both helpers in both dialogs.
4. Add `Locale.US` to the four display formats. This also covers the `WeightScreen` part of P4 BUG-052.

**Tests:**

- With `Locale.setDefault(Locale.GERMANY)`, a 72.5 kg entry pre-fills and parses back to 72.5.
- "72,5" typed by hand parses to 72.5.
- Restore the default locale in `@After`.

### BUG-031: a pending strategy change cannot be cancelled

**Now:**

- **Selector:** `PreferencesScreen.kt:189` always starts from the active strategy.
- **Repository:** `GoalsRepositoryImpl.updateGoals` (`data/repository/GoalsRepositoryImpl.kt:191-304`) compares against the active strategy only (214). Choosing the active strategy again counts as "no change", so these stay in place:
  - the `SCHEDULED_*` keys (43-46, written at 218-230)
  - tomorrow's `TRANSITIONS_HISTORY` record (283-298)
- **No cancel API:** the `GoalsRepository` interface (`domain/repository/CoreRepositories.kt:64-95`) has no way to cancel.

**Steps:**

1. Add `suspend fun cancelScheduledGoal()` to `GoalsRepository` and implement it in `GoalsRepositoryImpl`:
   - remove `SCHEDULED_FITNESS_GOAL`, `SCHEDULED_MAINTENANCE_CALORIES` and `SCHEDULED_EFFECTIVE_DATE`
   - drop the transition record dated on the scheduled effective date
   - do all of this in one `edit {}` call
2. In `updateGoals`, compute `pending = scheduledFitnessGoal ?: active`.
   - Requested equals active and a schedule exists: cancel, using the same code path as step 1.
   - Requested equals pending: no change.
   - Anything else: schedule or replace, as today.
   - Apply the same rule to maintenance calories.
3. Add `CancelScheduledGoalUseCase`, register it in `DiModules.kt`, and call it from `PreferencesViewModel.cancelScheduledChange()`.
4. **UI:**
   - Seed `selectedGoal` with `goals.scheduledFitnessGoal ?: goals.fitnessGoal`.
   - Add a "Cancel change" button to the pending banner (`PreferencesScreen.kt:299-305`).
   - If the deprecated `DailyGoalsScreen` survives the BUG-026 step, apply the same change there.
5. **Keep the fake in step:** mirror the new logic in `FakeGoalsRepository` (`LifestyleRankingUnitTests.kt:1212-1264`) so tests do not keep the old behaviour.
6. Confirm that `RankRepositoryImpl.kt:288-293`, which reads the pending change, behaves correctly after a cancel.

**Tests** (Robolectric, real `GoalsRepositoryImpl` with an injectable clock, template `P2PhaseFourGoalHistoryTests` in `P2PhaseFourRankTests.kt`):

- **Revert by saving:**
  1. Start on Maintaining and schedule Cutting.
  2. Re-select Maintaining and save.
  3. Advance the clock one day.
  4. Assert that the strategy is still Maintaining, the scheduled keys are gone, and there is no transition record for that day.
- **Cancel button:** schedule a change, then call `cancelScheduledGoal()`. The result is the same.
- **Change of mind:** schedule Cutting, then schedule Bulking. Only Bulking is pending.

**Phase 3 exit:** every preference and goal edit either saves exactly what was typed or shows an error, in en-US and de-DE.

---

## 8. Phase 4: Logging flow and day rollover (BUG-030, 038, 039, 040)

**Goal:** the main logging journey never loses input, never logs to the wrong day, and the widget does what it says.

### BUG-030: widget tap does not open Search

**Now:**

- **Widget intent:** `NEW_TASK | CLEAR_TOP` (`feature/widget/MacroBaseWidgetProvider.kt:122-125`).
- **Launch mode:** standard, with no `launchMode` set in `AndroidManifest.xml:26-35`. The activity is therefore destroyed and recreated, and `onNewIntent` (`MainActivity.kt:58-64`) never runs.
- **Dropped event:** `onCreate` calls `tryEmit` on a `MutableSharedFlow` with no replay before the collector exists (`MainActivity.kt:21-41`), so the event is lost.
- **Latent bug:** any fix that buffers the event would re-open Search on every rotation, because `onCreate` re-reads `intent.action`.

**Steps:**

1. Set `android:launchMode="singleTop"` on `.MainActivity`.
2. Change the widget flags to `NEW_TASK | SINGLE_TOP` and drop `CLEAR_TOP`.
3. In `MainActivity`, replace the `SharedFlow` with `Channel<String>(Channel.BUFFERED)` and collect it with `receiveAsFlow()` in the existing `LaunchedEffect`. A buffered channel holds the event until the collector starts.
4. In `onCreate`, handle the action only when `savedInstanceState == null`.
5. In `onNewIntent`, call `setIntent(intent)` and then handle the action.
6. After handling, set `intent.action = null` so a recreated activity does not handle it again.
7. Navigate with `navController.navigate(Screen.Search.createRoute(null, null)) { launchSingleTop = true }`.
8. Extract the handling decision (`action`, `isFreshStart`) into a small pure function so it can be unit-tested.

**Tests:**

- Unit-test the decision function:
  - fresh start with the widget action: navigate
  - recreation: do not navigate
  - `onNewIntent`: navigate
- **Manual check on a device:**
  1. Open the Weight screen and press Home.
  2. Tap the widget.
  3. Search opens. Back returns to Weight.
  4. Rotate the device. Search does not re-open.

### BUG-038: rotation resets Food Detail input

**Now:** `feature/detail/FoodDetailScreen.kt:508-515` runs `LaunchedEffect(foodId, entryId, basketItemId) { viewModel.loadFood(...) }`. The effect runs again after recreation. `loadFood` (165-293) has no guard, so it resets `selectedServing`, `enteredQuantity`, `quantityInputText`, `targetMealType` and `targetDate`. The ViewModel itself survives rotation.

**Steps:**

1. In `FoodDetailViewModel`, add `private var loadedKey: LoadKey? = null`, where `LoadKey(foodId, entryId, basketItemId, mealType, date)`.
2. At the start of `loadFood`, return early when the key is equal. Set the key only after a successful load.
3. Leave the constructor unchanged, so the roughly 13 test call sites keep compiling.
4. **Optional follow-up:** survive process death too, by persisting `quantityInputText` and `selectedServing` through a `SavedStateHandle`. Koin 4.0.2 supports it, but every test constructor call would need updating.

**Tests:**

- Call `loadFood(args)`, set quantity to "2.5", call `loadFood(args)` again: the quantity is still "2.5", including invalid text such as "2.".
- Calling `loadFood` with a different `foodId` reloads.
- Existing tests to keep green:
  - `foodDetailViewModel_recalculatesNutritionOnServingAndQuantityChanges`
  - `editExistingLoggedFood_paneer30g_initializesFromSnapshotAndUpdatesCorrectly`

### BUG-039: basket header and recipe mode

**Now:**

- **Fixed part:** errors now surface through `submitError` (`feature/basket/BasketViewModel.kt:81-82, 176-200`; `BasketScreen.kt:526-538`).
- **Header:** the "When:" line uses the first item's date and meal (`BasketViewModel.kt:90-96`; `BasketScreen.kt:310-316`). Rows show no date of their own, yet `CommitBasketUseCase` logs each item under its own date and meal.
- **Recipe mode:** "Single Food (Recipe)" (`LoggingMode` 31-34, `setLoggingMode` 153-155, radio buttons at `BasketScreen.kt:290-303`) is never read by anything.

**Steps:**

1. Add `isMixedDateOrMeal: StateFlow<Boolean>` to `BasketViewModel`. It is true when the items' `(date, mealType)` pairs differ.
2. When the basket is mixed and the user has not overridden it, the header reads "When: mixed (see each item)", and each row shows a small date and meal label.
   - Picking a common date or meal still applies it to every item through `UpdateAllBasketItemsUseCase`, and the mixed flag clears.
3. **D2, remove the recipe mode:** delete the `LoggingMode` enum, `loggingMode` and `setLoggingMode`, the radio group, and `test13_loggingMode_canBeToggled`.
   - Open a feature ticket for "save basket as recipe". It would build a recipe through `createRecipe` and log it as one atomic item (AGENTS.md section 2.5).
4. Remove the stale `onError = {}` parameter and its comment (`BasketScreen.kt:541-545`), or route it to `submitError`.

**Tests:**

- A mixed basket gives `isMixedDateOrMeal == true`. Committing it logs each item under its own date and meal.
- Setting a common date clears the flag.
- Update `test2_threeBasketItems_rendersAndCalculatesCorrectly` (`BasketBulkEditorUnitTests.kt:134-135`) for the new flag.

### BUG-040: dashboard stays on yesterday after midnight

**Now:**

- **Date captured once:** `feature/dashboard/HomeScreen.kt:108` sets `_selectedDate` from `LocalDate.now()` when the activity-scoped `HomeViewModel` is created.
- **Only one reset path:** `resetToToday` is called only from drawer and bottom-nav Home (`core/navigation/MacroBaseScaffold.kt:100, 256`).
- **No hooks:** there is no lifecycle hook and no clock injection.
- **Stale label:** the "Today" label (`HomeScreen.kt:218`) compares against the real date, so after midnight it shows a date while the data is still yesterday's.

**Steps:**

1. Inject the Phase 0 `Clock` into `HomeViewModel` and use `LocalDate.now(clock)` everywhere in it, including the label check at 218.
2. Add `private var followsToday = true`:
   - set it to true in `init` and in `resetToToday`
   - set it to false when the user picks a date other than today
3. Add `fun onAppResumed()`: if `followsToday` and `selectedDate != today`, set `selectedDate = today`.
4. Call `onAppResumed()` from a `LifecycleResumeEffect` in `MainActivity`'s `setContent`, where `HomeViewModel` is created (`MainActivity.kt:34`).
   - Add `androidx.lifecycle:lifecycle-runtime-compose` (2.8.7, the same version as the other lifecycle artifacts) explicitly to `app/build.gradle.kts`.
5. **Out of scope here:** the widget staleness after midnight is P4 BUG-048. The same `followsToday` idea applies to it later.

**Tests** (with `MutableTestClock`):

- Create the ViewModel at 23:59 on day D, advance to 00:01 on D+1, call `onAppResumed()`: `selectedDate` is D+1.
- If the user selected D-3, it stays on D-3.
- Existing `DashboardNavigationUnitTests` (tests A–E) still pass with the default clock.

**Phase 4 exit:** the widget opens Search. Rotation keeps input. A mixed basket is shown honestly. An overnight session logs to the new day.

---

## 9. Phase 5: Rank and statistics maths (BUG-032, BUG-033)

**Goal:** scores reflect completed days only, and every "N days" window has exactly N days.

### BUG-032: rank penalises today

**Now:**

- **Stale date:** `RankRepositoryImpl.observeRankProfile` (41-44) captures `today` once, when the flow is built, and `RankViewModel` keeps it for the ViewModel's lifetime.
- **Today counts:** the evaluation sequence (170-188) runs through today. An empty or part-logged today scores about 4, so the delta is clamped to -20 (`core/config/RankScoringConfig.kt:249-268`).
- **Same problem in stats:** `StatisticsRepositoryImpl.observeNutritionConsistency` (`CoreRepositoryImplementations.kt:243, 251`) also counts an unlogged today as eligible, and rank uses that score as a display fallback.

**Steps (D3 = a):**

1. Read `LocalDate.now(clock)` inside the flow's `combine` lambda, so every emission uses the current date. Optionally merge in a midnight ticker flow so the profile refreshes at day change with no data change.
2. **Rating:** sum deltas only for days through yesterday.
3. **Today:** still evaluate it and put it in `recentHistory`, flagged `isProvisional = true` (new field on the history item), but leave its delta out of the rating.
4. **UI:** in the rank screen, show the provisional row as "Today (so far)" in muted style, with the delta labelled "preview".
5. **Statistics consistency:** today is eligible only if it is logged. This keeps the consistency fallback in step and is shared with BUG-033.

**Tests** (fixed clock):

- No entries today and 29 perfect previous days: the rating equals the rating computed through yesterday, and today's row is provisional.
- Logging breakfast today leaves the rating unchanged. Advancing the clock one day counts the finished day.
- **Existing tests to re-check:**
  - `P2PhaseFourRankTests.oneHundredTwentyPerfectDaysGoPast600`: `recentHistory.size == 30` and `first().date == today` should still hold. Re-check the >600 threshold now that today is excluded.
  - `aBreakFromLoggingLowersTheRatingButKeepsItEstablished`.
  - The `LifestyleRankingUnitTests` rolling-window tests.

### BUG-033: windows one day too long; score truncated

**Now:**

- **Windows:** `feature/statistics/StatisticsScreen.kt:147-149` uses `today.minusDays(cInt.days)`, which spans 31 days for "30 Days". The weight windows (197-199) have the same off-by-one. The defaults at 108-109 and 133-134 also start from `minusDays(30)`.
- **Score:** `CoreRepositoryImplementations.kt:300-304` uses `.toInt()`. The comment in `domain/model/StatisticsModels.kt:36` claims it rounds.

**Steps:**

1. Add `fun windowStart(today: LocalDate, days: Long) = today.minusDays(days - 1)` in the domain layer. Use it for the consistency, macro and weight windows and for the defaults. Rank already follows this pattern (`ROLLING_WINDOW_DAYS - 1`).
2. Change the score to `roundToInt()` and fix the comment.
3. Read `today` from the Phase 0 clock.

**Tests:**

- Exactly 30 logged days (today−29 through today) give 30 eligible days and 100%.
- 29 of 30 gives 97. It was 96 before the fix.
- 28 of 30 still gives 93, so the existing `consistencyScore_calculatesCorrectlyAcrossScenarios` stays green.
- The 7-day macro average divides by 7.
- An unlogged today is not counted as a miss.

**Phase 5 exit:** a 30-day perfect logger sees 100%. Rank never drops in the morning.

---

## 10. Phase 6: Label scanner (BUG-044, 042, 041, 045, 043)

**Goal:** the scanner never crashes on a gallery image, never hides a critical warning, and reads US-format labels correctly.

**Order:** the crash fix first, then the safety net (warnings), then the three accuracy fixes.

**New test infrastructure** (the repo has none today):

- `FakeOcrEngine`, which implements `NutritionLabelOcrEngine` and returns canned `OcrResult`s.
- Orchestrator tests under Robolectric, because they need `Bitmap`.
- `android.graphics.Rect` fields read as 0 in plain JVM tests (`unitTests.isReturnDefaultValues = true`). Use `spatialBounds` in plain-JVM tests, and use Robolectric for anything that reads `Rect`.

### BUG-044: gallery image out-of-memory crash

**Now:** `feature/scanner/NutritionLabelScannerScreen.kt:108-123` has these problems:

- It decodes with `BitmapFactory.decodeStream` at full size, on the main thread.
- `catch (e: Exception)` misses `OutOfMemoryError`.
- It passes `isTestImage = true`, which skips the quality checks.
- It ignores EXIF orientation.

**Steps:**

1. **Shared decoder.**
   - Extract the bounds-probe and sampling logic from `safeDecodeImageProxy` (screen 852-912; fix its doc comment, which says 1280 but the code uses 2560) into `feature/scanner/ScannerBitmapDecoder.kt`.
   - `fun calculateInSampleSize(w, h, maxDim): Int`
   - `fun decodeSampled(resolver, uri, maxDim = 2560): Bitmap?`, which runs an `inJustDecodeBounds` pass and then a sampled decode.
2. **EXIF rotation.**
   - Add `androidx.exifinterface:exifinterface`, an on-device library with no network use, needed because `minSdk` is 26.
   - Read the orientation and rotate the decoded bitmap to match.
3. **Move decoding into the ViewModel.**
   - Add `NutritionLabelScannerViewModel.processGalleryImage(uri)`. It is an `AndroidViewModel`, so it can call `getApplication().contentResolver`.
   - Decode in `withContext(Dispatchers.IO)`, catch `Throwable`, and map `OutOfMemoryError` to "This image is too large. Try a smaller photo."
4. **Keep the quality checks.** Gallery images go through the normal path (`isTestImage = false`), or through a new `ImageSource.GALLERY` that keeps the quality warnings but skips the camera guide crop.
5. **Bonus:** move the camera path's decode (screen 367-374) off the main executor through the same helper.

**Tests:**

- `calculateInSampleSize(8000, 6000, 2560) == 4` (pure JVM).
- A Robolectric decode of a small JPEG fixture with EXIF rotation 90 comes out rotated.
- A ViewModel test where the decoder throws OOM sets the error state and does not crash.

### BUG-042: warnings dropped or hidden

**Now:**

- **Warnings captured too early:** `NutritionLabelScanOrchestrator.kt:214-252` captures `combinedWarnings` before `adjudicator.adjudicate`, so everything the adjudicator adds is lost.
- **Range check rarely runs:** `NutritionLabelGeminiAdjudicator.shouldAdjudicate` (18-25) gates the range check behind low confidence.
- **Critical warnings crowded out:** the screen shows `draft.warnings.take(3)` (screen 1196), and up to four image-quality hints come first.
- **Duplicates:** near-duplicate macro warnings come from three places: parser 569-576, orchestrator 204-212 and adjudicator 48.
- **No severity:** every warning type is a plain `List<String>`.

**Steps:**

1. **Assemble warnings after adjudication.** In the orchestrator, move the assembly after `adjudicate`.
2. **Always run the range check.** Call `validator.checkRangeSanity(draft)` unconditionally in the orchestrator, outside `shouldAdjudicate`.
3. **Add a severity split with little churn.**
   - Add `criticalWarnings: List<String> = emptyList()` to `NutritionLabelDraft` (`domain/model/scanner/ScannerModels.kt:104`). It is `@Parcelize`, and a default value keeps it compatible.
   - Range failures and macro or calorie discrepancies go to `criticalWarnings`.
   - Image-quality hints and parser notes stay in `warnings`.
   - Keep `warnings: List<String>` as it is, so the many existing tests and `CustomFoodsScreen.kt:582-590` are unaffected.
4. **Dedupe.** Keep one macro-consistency warning, the orchestrator's, and drop the parser and adjudicator duplicates.
5. **Screen.**
   - Render every critical warning first, with a red error icon, never truncated.
   - Then render up to 3 other warnings, with a "Show all (N)" toggle.
6. **Custom Foods.** Show `criticalWarnings` in `CustomFoodsScreen.kt:582-590` as well.
7. **Clean up.** Remove the unused `OrchestratorResult.warnings` (orchestrator line 22), or wire it into the ViewModel.
8. **Rename (P4 BUG-055):** drop the "Gemini" name and the unused `apiKey` parameters. Do this here only if it is cheap; it is not required.

**Tests:**

- Extract warning assembly into a pure function and test it on plain JVM: a 1,700 kcal/100 g draft has the range warning in `criticalWarnings` even when confidence is HIGH.
- Run the same scenario as an orchestrator test with `FakeOcrEngine` under Robolectric.

### BUG-041: "%DV" number becomes the per-100 g value

**Now:** in `feature/scanner/NutritionLabelParser.kt`:

- In the single-column branch (1230-1246), when the only column is PER_SERVING, a second token is taken as `per100g`. The PER_100G branch mirrors this.
- `isPercent` (1133) only checks the element's own text, so in `"160mg","7","%"` the "7" counts as a value.
- `reconcileNutrient` (1335) then treats per-100 g as authoritative, so sodium becomes **7 per 100 g**.
- The no-column branch (1248-1266) and the two-column branch (1203-1216) take extra numbers the same way.
- `SpatialTableDetector.kt:523` has the same `isPercent` weakness.

**Steps:**

1. **Detect a separate "%" element.** When building `RowToken`s, mark a numeric token `isPercent = true` if the next element is exactly `%` (or starts with `%`). `NutritionNumericParser.unitTextAfter(words, index)` (`NutritionNumericParser.kt:180`) already provides the look-ahead.
2. **Drop percent tokens.** Remove percent-tagged tokens before any column assignment, in all three branches.
3. **Single-column mode:** never fill the other basis from an extra token. Trust the detected header.
4. **No-column branch:** re-check the order in which it assigns `per100g` and `perServing` against fixtures. The report suggests it may be inverted for a PER_SERVING basis.
5. Apply the same percent look-ahead in `SpatialTableDetector`.

**Tests** (in `NutritionLabelGoldenTests` or `P2PhaseThreeScannerTests`; `parser.parseText(...)` reproduces the split elements):

- `"Amount Per Serving % Daily Value\nSodium 160mg 7 %"` gives sodium 160 per serving, no per-100 g value, basis PER_SERVING.
- `"Sodium 160mg 7%"` (attached percent sign) still works.
- An EU single per-100 g column with a %RI value is not misread.
- Golden tests test01 and test08 stay green.

### BUG-045: "Calories from Fat" row

**Now:**

- **Energy:** `extractEnergy` (`NutritionLabelParser.kt:1043-1049`) drops any row containing "calories from fat". When "Calories 230 Calories from Fat 72" is merged into one spatial row, the real calorie value is lost and the footnote "Calories: 2,000 2,500" can win.
- **Fat:** `FAT_PATTERNS` (1421-1423) includes plain `fat`, and the first matching row wins (1111-1123), which is the "from Fat" row.
- **Spatial path:** `SpatialTableDetector.kt:66-79` and 411-431 have no exclusion.
- **No tests:** nothing in `app/src/test` covers this.

**Steps:**

1. **Strip the segment.** Before matching nutrients, remove a `calories? from fat\s*\d+` segment from each row's text and elements, instead of dropping the whole row. The merged row then still yields "Calories 230".
2. **Anchor the fat keyword.** Match nutrient keywords only in the row label (text before the first numeric token), and reject labels containing `from`. "Total Fat", "Fat" and "Fat, total" must still match. "Saturated Fat" must still map to saturated fat.
3. **Guard the footnote.** Never take energy from rows that look like the %DV footnote: they contain both "2,000" and "2,500", or "daily values are based".
4. **Spatial path.** Apply the same exclusion in `SpatialTableDetector`.

**Tests:**

- A legacy US fixture with these rows: "Calories 230 Calories from Fat 72", "Total Fat 8g 12%", "Sodium 160mg 7 %", and the footnote.
- Expected: fat 8 g, calories 230, sodium 160.
- Run it twice: once with separate rows and once with the first line merged.

### BUG-043: crop-pass coordinates not offset

**Now:**

- **Crop pass:** `NutritionLabelScanOrchestrator.kt:159-180` OCRs `tableCrop`, but its boxes stay in crop coordinates.
- **Origin discarded:** `NutritionRegionDetector.cropTable` (135-151) computes a clamped origin and then throws it away.
- **Merge by Y only:** `OcrEnsemble.buildEnsemble` (149-176) merges lines by `abs(lineY - exY) < 20` only. The crop pass can also become the base pass.
- **Two fields to fix:** `OcrElement`, `OcrLine` and `OcrBlock` (`MlKitOcrEngine.kt:18-46`) each carry both `boundingBox: Rect?` and `spatialBounds: SpatialBox?`. `spatialBounds` is computed only at construction.

**Steps:**

1. **Return the crop origin.** `cropTable` returns `CroppedTable(bitmap, originX, originY)`.
2. **Offset helper.** Add `fun OcrResult.offsetBy(dx: Int, dy: Int): OcrResult` in the OCR types file. It offsets both `boundingBox` (copy with `Rect(r).apply { offset(dx, dy) }`) and `spatialBounds` (copy with `+dx/+dy`) for every block, line and element.
3. **Orchestrator.** Run `cropOcr = ocrEngine.recognizeText(crop.bitmap).offsetBy(crop.originX, crop.originY)` before scoring or merging.
4. **Optional hardening:** in `buildEnsemble`, also require horizontal overlap (or similar text) before treating two lines as the same row.

**Tests:**

- Plain JVM, using `spatialBounds`: `offsetBy(100, 300)` shifts every level.
- `buildEnsemble` with a full-image pass and a crop pass of the same rows, offset correctly, produces no duplicate or swapped rows.
- Robolectric: the `Rect` offset matches.

**Phase 6 exit:** a 50 MP gallery photo opens without a crash, the range warning always shows, and US labels with "%DV" and "Calories from Fat" parse correctly.

---

## 11. Docs to update once all phases land

- **AGENTS.md:**
  - section 1.4: the snapshot columns are nullable and include sat fat, trans fat and cholesterol
  - section 4.1: the DB version and migrations, `exportSchema`, the migration tests, and no destructive fallback
  - section 5.3: the guard checks `null` only
- **`docs/DATABASE_SCHEMA.md`:** the diary columns and the fallback text.
- **`docs/BACKUP_FORMAT.md`:** format 1.3.0, the new fields, the new size limits.
- **`docs/NUTRITION_LABEL_SCANNER.md`:** the critical-warnings split, the gallery decode path, and the crop offset.
- **`docs/RANKING_SYSTEM.md`:** today is provisional.

## 12. Out of scope (tracked elsewhere)

- **P1 BUG-008:** INTERNET permission from ML Kit's transport. Already fixed in the working tree before 10 Oct, and verified on 10 Oct:
  - `AndroidManifest.xml` removes INTERNET and the CCT upload backend.
  - `app/build.gradle.kts` fails any build whose merged manifest requests INTERNET or registers the CCT backend. Re-adding INTERNET made the build fail.
  - The merged debug and release manifests contain neither.
- **P4 items partly covered here:**
  - BUG-050 (Phase 3, dirty-field writes)
  - BUG-052 (Phase 3, `WeightScreen` formats)
  - BUG-055 (Phase 6, optional rename)
- **Remaining P4s:** BUG-048, 049, 051, 053, 054, 056 and 057 need their own pass.
