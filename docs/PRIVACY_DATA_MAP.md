# MacroBase Privacy & Data Map

## 1. Overview & Core Privacy Guarantee

MacroBase is built on a **100% Offline, Privacy-First Architecture**.

- **Zero Cloud Transmission**: MacroBase makes zero network calls and transmits zero bytes off the device.
- **Zero Third-Party SDKs**: No analytics SDKs, no advertising trackers, no crash-reporting telemetries, no cloud sync SDKs.
- **Complete User Ownership**: 100% of personal nutrition logs, biometric data, custom foods, and goals remain on the physical device.

---

## 2. Comprehensive Data Map

| Data Field / Item | Category | Collection Purpose | Local Storage | Leaves Device | User Deletion / Control |
| :--- | :--- | :--- | :--- | :---: | :--- |
| **Logged Foods & Meals** | Food Diary | Nutrition tracking & calorie calculation | `macrobase_user.db` | **NO** | User can edit/delete individual entries or clear all data |
| **Historical Portion & Nutrient Snapshots** | Food Diary | Historical accuracy preservation | `macrobase_user.db` | **NO** | Automatic immutable snapshotting; deleted with entry |
| **Custom Foods** | Custom Catalog | User-created nutrition items | `macrobase_user.db` | **NO** | User can edit or delete any custom food at any time |
| **Recipes & Ingredients** | Recipe Manager | Composite meal tracking | `macrobase_user.db` | **NO** | User can edit or delete recipes |
| **Body Weight Records** | Biometric | Weight trend tracking & graphing | `macrobase_user.db` | **NO** | User can edit or delete any weight entry |
| **Hydration Logs** | Hydration | Daily water goal tracking | `macrobase_user.db` | **NO** | User can edit or delete water records |
| **Daily Calorie & Macro Goals** | Targets | Adherence scoring & dashboard bars | DataStore (`goals`) | **NO** | User can update at any time |
| **Profile (First Name, Last Name, Height, Time Zone)** | Profile | Serving & unit display | DataStore (`prefs`) | **NO** | User can clear or update profile fields |
| **Unit System Preference (Metric/Imperial)** | Preferences | Unit formatting | DataStore (`prefs`) | **NO** | User can toggle units freely |

---

## 3. Data Export & Portability

- **Format**: Decoupled `.zip` archive containing standard JSON files (`manifest.json`, `diary.json`, `custom_foods.json`, `recipes.json`, `weight.json`, `water.json`, `goals.json`, `preferences.json`).
- **Mechanism**: Initiated strictly by explicit user action via Android Storage Access Framework (SAF).
- **Scope**: Contains only user-created data. Built-in 7,966-food USDA database is **never** exported into the backup archive.

---

## 4. Android System Backup Exclusions

In accordance with Android Auto Backup best practices, `data_extraction_rules.xml` and `backup_rules.xml` explicitly exclude the local immutable database (`built_in_foods.db`) from cloud backups to conserve user Google Drive quota.
