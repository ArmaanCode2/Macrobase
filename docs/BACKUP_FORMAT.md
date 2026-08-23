# MacroBase Backup Format Specification

## 1. Overview & Portability Principles

MacroBase provides 100% offline data portability. Users own and control their personal nutrition and biometric data without accounts, servers, or cloud dependencies.

- **Format Name**: `MacroBaseBackup`
- **Format Version**: `1.0.0`
- **MIME Type**: `application/zip`
- **Default Filename**: `MacroBase_Backup_YYYY-MM-DD.zip`
- **Storage Strategy**: Decoupled from AndroidX Room internal SQLite schemas. Serialized as structured JSON documents within a standard ZIP archive.

---

## 2. Archive File Structure

A valid MacroBase backup file is a ZIP archive containing the following structured files:

```
MacroBase_Backup_2026-08-19.zip
├── manifest.json
├── diary.json
├── custom_foods.json
├── recipes.json
├── weight.json
├── water.json
├── goals.json
└── preferences.json
```

> [!IMPORTANT]
> The built-in food catalog (`built_in_foods.db`) and static assets are deliberately **excluded** from the backup. The built-in database is bundled with the application and remains immutable.

---

## 3. Manifest Specification (`manifest.json`)

The manifest contains metadata, schema compatibility, exact record counts, and SHA-256 integrity checksums for every data file in the archive.

```json
{
  "format": "MacroBaseBackup",
  "backupVersion": "1.0.0",
  "appVersion": "1.0.0",
  "schemaVersion": 1,
  "exportedAt": "2026-08-19T07:15:30Z",
  "timeZone": "America/New_York",
  "deviceInfo": "Android",
  "counts": {
    "diaryEntries": 1245,
    "customFoods": 18,
    "recipes": 6,
    "weightEntries": 75,
    "waterEntries": 412,
    "goals": 1,
    "preferences": 1
  },
  "checksums": {
    "diary.json": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "custom_foods.json": "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb",
    "recipes.json": "4e07408562bedb8b60ce05c1decfe3ad16b72230967de01f640b7e4729b49fce",
    "weight.json": "4b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8a",
    "water.json": "ef2d127de37b942baad06145e54b0c619a1f22327b2ebbcfbec78f5564afe39d",
    "goals.json": "8a835561a0d8157790b4bf8934fb5b45287f3b8b668d2a6a1ee59996d9fa24d8",
    "preferences.json": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
  },
  "compatibility": {
    "minSupportedAppVersion": "1.0.0",
    "supportedSchemaVersion": 1
  }
}
```

---

## 4. Entity DTO Specifications

### 4.1. Diary Entries (`diary.json`)
Stores historical meal logs with immutable nutrition snapshots.
```json
[
  {
    "uuid": "4c94f58b-bf11-4f32-9447-9759d57a94d8",
    "dateEpochDay": 20684,
    "dateString": "2026-08-19",
    "mealType": "BREAKFAST",
    "foodId": 101,
    "foodName": "Oatmeal with Honey",
    "userQuantity": 1.5,
    "servingDescription": "1 cup cooked",
    "gramWeight": 234.0,
    "loggedCalories": 300.0,
    "loggedProtein": 10.5,
    "loggedCarbs": 54.0,
    "loggedFat": 4.5,
    "createdAt": 1787121996830
  }
]
```

### 4.2. Custom Foods (`custom_foods.json`)
```json
[
  {
    "uuid": "8b3f2c59-1e34-4b57-a36c-9b88950d8841",
    "name": "Whey Isolate Vanilla",
    "brand": "Optimum Nutrition",
    "servingSize": 1.0,
    "servingUnit": "scoop (31g)",
    "calories": 120.0,
    "proteinGrams": 24.0,
    "carbsGrams": 3.0,
    "fatGrams": 1.0,
    "fiberGrams": 0.0,
    "sugarGrams": 1.0,
    "sodiumMg": 130.0,
    "potassiumMg": 160.0,
    "calciumMg": 100.0,
    "ironMg": 0.0,
    "createdAt": 1787121946482
  }
]
```

### 4.3. Recipes (`recipes.json`)
```json
[
  {
    "uuid": "99df2103-6258-450a-b2bb-76f83ecff388",
    "name": "High-Protein Smoothie",
    "servingsProduced": 2,
    "ingredientsJson": "[{\"foodId\":101,\"quantity\":1.0,\"unitDescription\":\"1 cup\"},{\"foodId\":205,\"quantity\":1.0,\"unitDescription\":\"1 scoop\"}]",
    "caloriesPerServing": 210.0,
    "proteinPerServing": 27.5,
    "carbsPerServing": 18.0,
    "fatPerServing": 2.5,
    "createdAt": 1787121933971
  }
]
```

### 4.4. Weight Entries (`weight.json`)
```json
[
  {
    "dateEpochDay": 20684,
    "dateString": "2026-08-19",
    "weightKg": 82.5,
    "note": "Morning weigh-in",
    "createdAt": 1787121921954
  }
]
```

### 4.5. Water Logs (`water.json`)
```json
[
  {
    "dateEpochDay": 20684,
    "dateString": "2026-08-19",
    "amountMl": 500.0,
    "timestamp": 1787121874722
  }
]
```

### 4.6. Goals (`goals.json`)
```json
{
  "dailyCalorieGoal": 2200.0,
  "carbPercentage": 45.0,
  "proteinPercentage": 30.0,
  "fatPercentage": 25.0
}
```

### 4.7. Preferences (`preferences.json`)
```json
{
  "firstName": "Alex",
  "lastName": "Mercer",
  "timeZone": "America/New_York",
  "unitSystem": "METRIC",
  "heightCm": 180.0,
  "currentWeightKg": 82.5,
  "targetWeightKg": 78.0,
  "dailyWaterGoalMl": 3000.0
}
```

---

## 5. Restoration Modes & Deterministic Conflict Handling

### 5.1. MERGE Mode (Non-Destructive)
- **Custom Foods**: Matched by `uuid`. If identical $\rightarrow$ skipped. If different $\rightarrow$ newer record (`createdAt`) wins. If absent $\rightarrow$ inserted.
- **Recipes**: Matched by `uuid`. If identical $\rightarrow$ skipped. If different $\rightarrow$ newer record (`createdAt`) wins. If absent $\rightarrow$ inserted.
- **Diary Entries**: Matched by `uuid`. If exists $\rightarrow$ skipped. If absent $\rightarrow$ inserted.
- **Weight Entries**: Matched by `dateEpochDay`. If exists $\rightarrow$ newer record (`createdAt`) wins. If absent $\rightarrow$ inserted.
- **Water Logs**: Matched by `(dateEpochDay, timestamp, amountMl)`. Duplicate timestamps skipped; new logs inserted.
- **Goals & Preferences**: Updated to backup configuration.

### 5.2. OVERWRITE Mode (Destructive)
- Requires explicit user confirmation via a prominent red warning dialog.
- Clears all user tables (`diary_entries`, `custom_foods`, `recipes`, `weight_entries`, `water_logs`) in `macrobase_user.db`.
- Inserts all backup records transactionally.
- **Built-in Catalog Guarantee**: `built_in_foods.db` is strictly untouched and unaffected.

---

## 6. Transactional Safety & Integrity Verification
- Restoration executes inside `UserDatabase.runInTransaction { ... }`.
- If any validation failure, corrupted JSON, or SQLite error occurs during restoration, the transaction is immediately aborted and rolled back.
- Post-import integrity checks verify record counts, non-null mandatory fields, and foreign reference validity before committing.
