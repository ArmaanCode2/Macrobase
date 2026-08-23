# MacroBase Reliability, Offline Stability & Hardening Checklist

## 1. Reliability Test Matrix

| Category / Invariant | Verification Method | Result | Notes |
| :--- | :--- | :---: | :--- |
| **Startup Resilience** | Cold start, warm start, and force stop recovery | **PASS** | `BuiltInDatabaseManager` copies/validates asset asynchronously off main thread |
| **Database Initialization** | First launch asset copy and atomic rename | **PASS** | Atomic temporary file staging with fallback copy |
| **Database Idempotency** | Version 1 $\rightarrow$ Version 1 re-launch | **PASS** | Validates existing DB file length and metadata without redundant copying |
| **Database Schema Versioning** | Schema v1 verification across tables & indexes | **PASS** | Verified 7,966 foods, 14,641 servings, FTS5 virtual table |
| **Offline Operation** | Airplane Mode execution across all 14 screens | **PASS** | 100% offline; zero network calls, zero external endpoint dependencies |
| **Network Monitoring** | Codebase audit for network clients (Retrofit/OkHttp/Firebase) | **PASS** | Zero network calls made by application core |
| **Large Diary Queries** | 5,000 diary records single-day and range aggregation | **PASS** | Single-day index lookup on `dateEpochDay`; range SQL `GROUP BY` |
| **Monthly Calendar Aggregation** | Multi-year historical timeline month changes | **PASS** | $O(1)$ database trips per month view |
| **Statistics Range Queries** | 7-day, 30-day, 90-day, 365-day, all-time summaries | **PASS** | Range queries execute under 15 ms for 5,000 records |
| **Weight Graph Scaling** | 2, 10, 100, 1,000 entries across 6 intervals | **PASS** | Dynamic Canvas rendering with bounds scaling and target line |
| **Water Tracking Daily Aggregates** | 1,000 historical logs date-filtered aggregation | **PASS** | Indexed query on `dateEpochDay` |
| **Custom Food & Recipe Scaling** | 500 custom foods, 200 recipes with 50 ingredients | **PASS** | Portion scaling sub-millisecond calculation |
| **Import Validation: Valid Backup** | 7,700 record ZIP archive with SHA-256 manifest | **PASS** | Successfully parsed and verified |
| **Import Validation: Corrupted ZIP** | Malformed byte stream extraction | **PASS** | Returns clean validation error without crashing |
| **Import Validation: Tampered SHA-256** | Mismatched file checksum inside archive | **PASS** | Rejects corrupted file before database ingestion |
| **Import Validation: Unsupported Version** | Version `2.0.0` archive ingestion | **PASS** | Cleanly rejected with user-facing update prompt |
| **Transactional Restore Rollback** | Simulated database error during restore | **PASS** | Wrapped in `runInTransaction`; 100% rolled back to previous state |
| **Merge Mode Deduplication** | Non-destructive merge with conflicting records | **PASS** | Newer `createdAt` wins; identical rows skipped |
| **Overwrite Mode Isolation** | Destructive restore confirmation | **PASS** | Clears user tables only; `built_in_foods.db` strictly untouched |
| **Historical Snapshot Immutability** | Modify catalog food after logging to diary | **PASS** | Diary entries retain immutable snapshotted nutrition values |
| **Built-in Database Upgrade** | Catalog replacement simulation | **PASS** | Diary history remains identical; user custom foods/recipes preserved |
| **Memory Stability** | 100 consecutive search queries and screen navigations | **PASS** | Bounded result limits (50), paged portions, zero heap leaks |
| **Main-Thread Purity** | IO operations dispatcher audit | **PASS** | All SQLite, Room, DataStore, JSON, and ZIP operations run on `Dispatchers.IO` |
| **UI Touch Targets** | Accessibility touch targets ($\ge 48\text{dp}$) | **PASS** | Verified across all interactive elements |
| **Font Scaling Integrity** | $1.0\times - 1.3\times$ font scale rendering | **PASS** | Layouts flex without clipping or overflow |

---

## 2. Test Execution Summary

- **Total Unit, System & Integration Tests**: 95 Tests across 14 Test Suites
- **Passed**: 95 (100%)
- **Failed**: 0
- **Build Status**:
  - `assembleDebug` $\rightarrow$ **SUCCESSFUL** (`app-debug.apk`)
  - `assembleRelease` $\rightarrow$ **SUCCESSFUL** (`app-release-unsigned.apk`)
- **Platform**: Android SDK 35 (Android 15), minSdk 26 (Android 8.0), Java 21, Kotlin 2.1
