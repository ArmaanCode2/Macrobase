# MacroBase Master Documentation Index

This directory contains the technical architecture, data pipeline specifications, domain calculation contracts, security standards, and operational audits for the MacroBase Android application.

All documents are synchronized with the actual Kotlin codebase (`app/src/main/java/com/macrobase/app/`) and the dual SQLite/Room storage layer (`built_in_foods.db` and `macrobase_user.db`).

---

## Quick Reference Links

- **[AI Agent Invariants & Guardrails](../AGENTS.md)**: Strict rules, mathematical contracts, and implementation checklists for human developers and AI assistants.
- **[Project README](../README.md)**: Project overview, feature summary, build instructions, and tech stack.

---

## 1. System & Core Architecture

| Document | Description |
| :--- | :--- |
| **[ARCHITECTURE.md](ARCHITECTURE.md)** | **High-Level System Architecture**: Clean Architecture layers (Presentation, Domain, Data), unidirectional data flow (UDF), Koin dependency injection modules, and the Immutable Historical Snapshot pattern. |
| **[DATA_PIPELINE.md](DATA_PIPELINE.md)** | **End-to-End Data Pipeline**: Data flow from built-in SQLite catalog and custom food creation through the `InMemoryBasketRepository` to Room `diary_entries`, including Diary Edit mode. |
| **[BASKET_ARCHITECTURE.md](BASKET_ARCHITECTURE.md)** | **Staging Basket Subsystem**: In-memory staging repository (`InMemoryBasketRepository`), bulk portion editing, meal type allocation, and atomic batch logging to the diary. |

---

## 2. Domain Logic & Calculation Engine

| Document | Description |
| :--- | :--- |
| **[CALCULATION_ENGINE.md](CALCULATION_ENGINE.md)** | **Mathematical Contracts & Formulas**: Exact serving multiplier logic (100g base vs 1-serving base vs 1g sub-portions), Atwater energy factor ratios, recipe portioning, net carbohydrates, daily aggregations, and calendar adherence thresholds. |
| **[KNOWN_PITFALLS_AND_GUARDRAILS.md](KNOWN_PITFALLS_AND_GUARDRAILS.md)** | **Architectural Pitfalls & Regression Catalog**: Analysis and prevention of 7 recurring failure modes (Mojibake bullet corruption, secondary nutrient loss, 100g division collapse, integer truncation, ID collisions, Compose scroll crashes, Zip bombs). |

---

## 3. Data Storage & Privacy

| Document | Description |
| :--- | :--- |
| **[DATABASE_SCHEMA.md](DATABASE_SCHEMA.md)** | **Dual-Database Architecture**: Full DDL schema for read-only asset `built_in_foods.db` (FTS5 search) and user database `macrobase_user.db` (Room v3 migrations `1->2` and `2->3` with `loggedFiber`, `loggedSugar`, `loggedSodium`). |
| **[BACKUP_FORMAT.md](BACKUP_FORMAT.md)** | **Backup & Portability Specification**: JSON serialization schema, bounded streaming ZIP archive manager, format versioning, and preservation of custom portion names (`customUnitName`). |
| **[PRIVACY_DATA_MAP.md](PRIVACY_DATA_MAP.md)** | **Privacy Contract & Data Storage Map**: Complete audit of local file paths, zero-telemetry policy, network isolation, and on-device hardware data residency. |

---

## 4. Subsystems & Specialized Features

| Document | Description |
| :--- | :--- |
| **[NUTRITION_LABEL_SCANNER.md](NUTRITION_LABEL_SCANNER.md)** | **Nutrition Label OCR Architecture**: CameraX lifecycle integration, bounded bitmap memory management, ML Kit OCR recognition, multi-column regex parsing, and confidence scoring. |
| **[PADDLE_OCR_INTEGRATION.md](PADDLE_OCR_INTEGRATION.md)** | **PaddleOCR Native Integration**: C++/JNI ONNX Runtime inference, OpenCV preprocessing, and text recognition pipeline for offline nutrition label extraction. |
| **[RANKING_SYSTEM.md](RANKING_SYSTEM.md)** | **Food Ranking & Scoring**: Multi-dimensional nutrient density scoring, lifestyle diet profiles (High Protein, Low Carb, Keto, Mediterranean, Low Sodium, Vegan, Vegetarian), and sorting metrics. |

---

## 5. Audits, Quality & UI Specifications

| Document | Description |
| :--- | :--- |
| **[SECURITY_PRIVACY_AUDIT.md](SECURITY_PRIVACY_AUDIT.md)** | **Security Hardening Audit**: Zero-cloud privacy enforcement, Zip-slip/Zip-bomb DOS mitigations, SQL wildcard injection escaping, and recursion-safe JSON parsers. |
| **[RELIABILITY_CHECKLIST.md](RELIABILITY_CHECKLIST.md)** | **Reliability & Resilience Guidelines**: Activity lifecycle preservation, process-death recovery, state synchronization, and Room database migration testing. |
| **[PERFORMANCE_REPORT.md](PERFORMANCE_REPORT.md)** | **Performance Benchmarks & Optimizations**: SQLite FTS5 query latency, 60fps Jetpack Compose scroll metrics, coroutine dispatching profiles, and cold-start benchmarks. |
| **[RELEASE_CANDIDATE_REPORT.md](RELEASE_CANDIDATE_REPORT.md)** | **Release Verification Matrix**: Test coverage, regression verification, Android SDK version support matrix, and release readiness audit. |
| **[UI_UX_SPECIFICATION.md](UI_UX_SPECIFICATION.md)** | **Design System & Token Catalog**: Material 3 typography scales, color token schemes (dynamic / dark / light), component states, and layout specifications. |
| **[UI_AUDIT.md](UI_AUDIT.md)** | **UI & Accessibility Audit**: Touch target sizing, contrast ratios, single-app-bar navigation rules, and Compose constraint hierarchy safeguards. |
| **[BUG_AUDIT_REPORT.md](BUG_AUDIT_REPORT.md)** | **Historical Bug Audit**: Retrospective catalog of fixed bugs, regression test mapping, and lessons learned. |

---

## Maintenance Guidelines

When modifying code in `app/src/main/java/com/macrobase/app/` or altering database schemas:
1. Cross-reference changes against **[AGENTS.md](../AGENTS.md)** to prevent invariant violations.
2. Update the corresponding technical document in `docs/` (e.g., update [DATABASE_SCHEMA.md](DATABASE_SCHEMA.md) if adding Room columns).
3. If new calculation logic or portion scaling is touched, review and update [CALCULATION_ENGINE.md](CALCULATION_ENGINE.md).
4. Run `./gradlew testDebugUnitTest` to ensure all regression tests pass.
