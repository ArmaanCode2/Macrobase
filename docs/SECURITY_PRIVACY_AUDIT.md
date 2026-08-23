# MacroBase Security, Privacy & Data Safety Audit

## 1. Executive Summary & Security Posture

MacroBase was designed and verified under an **offline-first, zero-cloud, privacy-by-design** architecture. 

- **Platform**: Android (minSdk 26, targetSdk 35)
- **Networking**: **Zero network requests**, **Zero network permissions** (`INTERNET` permission absent).
- **Storage Isolation**:
  - `built_in_foods.db`: 45.8 MB immutable, read-only USDA SQLite database.
  - `macrobase_user.db`: Application-private Room SQLite database (`/data/data/com.macrobase.app/databases/macrobase_user.db`).
  - `DataStore Preferences`: Application-private key-value storage for goals and profile units.
- **Portability Format**: Standardized offline ZIP archive containing versioned JSON files and SHA-256 integrity checksums.
- **Verification Status**: **89 Automated Tests Passed (100%)**, `assembleDebug` & `assembleRelease` verified.

---

## 2. Threat Model & Mitigations

| Threat Description | Attack Surface | Implemented Mitigation | Risk Level |
| :--- | :--- | :--- | :---: |
| **ZIP Path Traversal Attack** | Malicious backup ZIP containing `../` or absolute path entry names | `BackupArchiveManager` validates all entry names against `..`, `/`, `\`, and `:`, rejecting malicious archives immediately | **MITIGATED (Low)** |
| **Decompression Bomb (Zip Bomb)** | Malicious tiny ZIP extracting gigabytes of data | Hard limits: Max archive size 50 MB, Max entry size 50 MB, Max total extracted bytes 100 MB, Max 50 entries | **MITIGATED (Low)** |
| **Malformed JSON & Corrupted DTOs** | Injected NaN/Infinity, negative values, or oversized strings in backup | `BackupJsonSerializer` and `JsonObject` validate finite numbers, sanitize NaN/Infinity to null, and cap string lengths | **MITIGATED (Low)** |
| **Partial Restore / Database Inconsistency** | App crash or constraint violation mid-import | Entire import is wrapped in Room's `runInTransaction`; automatically rolls back 100% on any failure | **MITIGATED (Low)** |
| **Built-in Database Mutation / Injection** | Malicious food injection into packaged catalog | `BuiltInDatabaseManager` opens catalog strictly with `SQLiteDatabase.OPEN_READONLY`; physical file in app storage is read-only | **MITIGATED (Low)** |
| **Accidental Cloud / System Backup Leakage** | Android Auto Backup uploading private nutrition logs | `data_extraction_rules.xml` and `backup_rules.xml` explicitly exclude local databases from automatic cloud backup | **MITIGATED (Low)** |
| **Network Attack Surface** | External API vulnerability or man-in-the-middle | App requests zero network permissions; no HTTP client, no WebView, no analytics SDK | **NONE (Zero Surface)** |
| **Sensitive Log Leakage** | Production Logcat leaking user weight or food logs | Production builds emit zero user data in logs; technical logs restricted to catalog asset initialization | **MITIGATED (Low)** |
| **Local Storage Exposure (Rooted Devices)** | Unencrypted SQLite on rooted device | Room SQLite is stored in app-private sandbox. (Note: standard unencrypted SQLite; documented in Known Limitations) | **ACCEPTED (Medium)** |

---

## 3. User Data Inventory & Sensitivity Classification

| Data Category | Storage Location | Sensitivity | Required | Exported in Backup | Leaves Device |
| :--- | :--- | :---: | :---: | :---: | :---: |
| **Diary Entries & Historical Snapshots** | `macrobase_user.db` (`diary_entries`) | Sensitive | Yes | Yes (`diary.json`) | **No (Local Only)** |
| **Custom Foods** | `macrobase_user.db` (`custom_foods`) | Low | Optional | Yes (`custom_foods.json`) | **No (Local Only)** |
| **Recipes & Ingredients** | `macrobase_user.db` (`recipes`) | Low | Optional | Yes (`recipes.json`) | **No (Local Only)** |
| **Body Weight History** | `macrobase_user.db` (`weight_entries`) | Highly Sensitive | Optional | Yes (`weight.json`) | **No (Local Only)** |
| **Hydration Logs** | `macrobase_user.db` (`water_logs`) | Sensitive | Optional | Yes (`water.json`) | **No (Local Only)** |
| **Daily Nutrition Goals** | Jetpack DataStore (`goals_preferences`) | Low | Yes | Yes (`goals.json`) | **No (Local Only)** |
| **Profile Preferences (Name, Height, Units)**| Jetpack DataStore (`user_preferences`) | Sensitive | Optional | Yes (`preferences.json`) | **No (Local Only)** |
| **Built-in Food Catalog** | `built_in_foods.db` (Asset) | Public / Open Data | Yes | **No (Excluded)** | **No (Local Only)** |

---

## 4. Built-in Database Security & Immutability

- `BuiltInDatabaseManager` opens `built_in_foods.db` strictly with flags:
  ```kotlin
  SQLiteDatabase.openDatabase(
      dbFile.absolutePath,
      null,
      SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
  )
  ```
- User custom food creation, recipe creation, and diary logging write strictly to `macrobase_user.db`.
- Database replacement/upgrade tests verified that upgrading the catalog preserves all existing historical diary snapshots and custom foods without data loss or corruption.

---

## 5. Android Permissions & Component Exposure

- **Declared Permissions**: **NONE**.
  - No `android.permission.INTERNET`
  - No `android.permission.READ_EXTERNAL_STORAGE`
  - No `android.permission.WRITE_EXTERNAL_STORAGE`
  - No `android.permission.ACCESS_NETWORK_STATE`
- **Storage Access Framework (SAF)**: Export and import use native `ActivityResultContracts.CreateDocument` and `ActivityResultContracts.OpenDocument`, providing strict user-mediated file access without requiring broad storage permissions.
- **Component Exposure**:
  - `MainActivity`: Only exported component (`android:exported="true"`) solely because it possesses the `MAIN` + `LAUNCHER` intent filter.
  - Services: None.
  - Broadcast Receivers: None.
  - Content Providers: None.

---

## 6. Known Limitations & Future Recommendations

### Known Limitations (Honest Disclosure)
1. **Unencrypted Local Database**: `macrobase_user.db` uses standard Room SQLite. On rooted devices or forensic physical extraction, SQLite data in the app's private sandbox could be read. (No false "encrypted" claims).
2. **Unencrypted Backup Archives**: Exported ZIP archives are unencrypted ZIP/JSON files. Users are responsible for safeguarding exported backup files stored on external storage or cloud drives.
3. **SHA-256 Checksums**: Checksums in the backup manifest protect against accidental file corruption during transfer; they are not digital cryptographic signatures.

### Recommended Future Enhancements
1. **SQLCipher Integration**: Provide optional database encryption using Android Keystore and SQLCipher for high-security environments.
2. **Encrypted Backups**: Allow users to optionally set a passphrase to encrypt backup ZIP archives with AES-256 (GCM).
3. **Biometric App Lock**: Optional biometric prompt (fingerprint/face) upon app resume for users with sensitive weight tracking data.
