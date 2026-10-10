# MacroBase

MacroBase is an offline-first nutrition and macronutrient tracking application for Android. It is designed for fast food logging, complete data privacy, and flexible portion editing without requiring an internet connection or a user account.

---

## Documentation

For developers, architects, and AI coding agents, comprehensive technical documentation is available:

- **[AGENTS.md](AGENTS.md)**: Strict architectural invariants, mathematical calculation contracts, locale/encoding rules, and regression checklists.
- **[Documentation Index (docs/INDEX.md)](docs/INDEX.md)**: The master directory for all architectural, mathematical, database, and quality documentation.
  - **[Architecture (docs/ARCHITECTURE.md)](docs/ARCHITECTURE.md)**: Clean Architecture layers, Koin DI modules, and immutable historical snapshotting.
  - **[Calculation Engine (docs/CALCULATION_ENGINE.md)](docs/CALCULATION_ENGINE.md)**: Exact mathematical formulas for serving multipliers, Atwater energy, recipes, net carbs, and aggregations.
  - **[Known Pitfalls & Guardrails (docs/KNOWN_PITFALLS_AND_GUARDRAILS.md)](docs/KNOWN_PITFALLS_AND_GUARDRAILS.md)**: Critical engineering pitfalls and regression prevention catalog.
  - **[Database Schema (docs/DATABASE_SCHEMA.md)](docs/DATABASE_SCHEMA.md)**: Schemas for read-only `built_in_foods.db` (FTS5) and Room `macrobase_user.db` (v3).
  - **[Data Pipeline (docs/DATA_PIPELINE.md)](docs/DATA_PIPELINE.md)**: Data lifecycle from search and basket to diary persistence and edit mode.
  - **[Basket Architecture (docs/BASKET_ARCHITECTURE.md)](docs/BASKET_ARCHITECTURE.md)**: In-memory staging repository and bulk editing flow.

---

## Overview

Most nutrition tracking apps rely on cloud services, subscriptions, and telemetry. MacroBase stores all user data locally on the device using SQLite and Room. It includes a pre-packaged offline food database with FTS5 search, an on-device OCR scanner for physical nutrition labels, and a centralized food basket that lets you edit multiple items in bulk before committing them to your diary.

---

## Key Features

- **Daily Dashboard & Nutrition Summary**: Tracks daily calorie limits and macronutrient targets (protein, carbs, fat) across six meal slots: Breakfast, Lunch, Dinner, AM Snack, PM Snack, and Late Snack. Includes an in-depth daily nutrition summary modal showing secondary nutrients (dietary fiber, sugar, sodium) with mathematical rounding.
- **Fast Offline Search**: Instant food search using SQLite FTS5 full-text indexing over tens of thousands of built-in USDA entries, custom foods, and recipes with categorized search results.
- **Centralized Food Basket**: A bulk editing staging repository (`InMemoryBasketRepository`) for pending logs. Edit quantities and serving units inline, apply common dates or meal types across all items, and review total calories and macros before saving.
- **Diary Entry Editing**: Direct inline editing of previously logged diary entries with pre-populated quantities and instant historical recalculation.
- **On-Device Nutrition Label Scanner**: Scan physical food labels using the device camera. The app parses serving sizes, calories, and nutrient breakdown directly on-device using ML Kit Text Recognition and PaddleOCR with 100% offline privacy.
- **Custom Foods and Recipes**: Create custom foods with custom portion names (`customUnitName`), or combine ingredients into multi-serving recipes with automatic per-portion nutrient calculations.
- **Lifestyle Food Rankings**: Built-in food scoring algorithm that ranks foods across 7 lifestyle profiles: High Protein, Low Carb, Keto, Mediterranean, Low Sodium, Vegan, and Vegetarian.
- **Water and Weight Tracking**: Log daily water intake in customizable increments and monitor weight progress over time.
- **Statistics and Adherence**: Monthly calendar adherence view, macronutrient breakdown charts, and progress toward nutritional goals.
- **Data Portability**: Export and import your entire database and logs via JSON and raw database backups, protected by stream-bounded decompression against Zip-bomb attacks.

---

## Tech Stack and Architecture

The app is built following Clean Architecture principles with clear separation of concerns across presentation, domain, and data layers.

- **UI**: Jetpack Compose with Material 3 design system and single top app bar navigation architecture.
- **Language**: Kotlin (Coroutines, Flow, StateFlow).
- **Local Storage**:
  - `built_in_foods.db`: Pre-packaged read-only SQLite database with FTS5 virtual tables.
  - `macrobase_user.db`: Room database (v3) storing user diary entries (with historical snapshot macros and secondary nutrients: fiber, sugar, sodium), custom foods, recipes, weight, and water logs.
- **Dependency Injection**: Koin (modularized into `databaseModule`, `repositoryModule`, `useCaseModule`, `viewModelModule`).
- **Navigation**: Jetpack Navigation Compose with type-safe routing arguments.
- **Machine Learning and OCR**: On-device Google ML Kit Text Recognition and PaddleOCR (ONNX Runtime, OpenCV). Zero network calls.
- **Architecture**: MVVM with unidirectional data flow (UDF) and Immutable Historical Snapshot pattern.
- **Testing**: JUnit 4, Kotlinx Coroutines Test, Room in-memory testing.

---

## Project Structure

```
macrobase/
├── AGENTS.md                       # AI agent invariants, contracts, and guardrails
├── docs/                           # Comprehensive technical documentation & audits
│   ├── INDEX.md                    # Master documentation directory
│   ├── ARCHITECTURE.md             # System architecture & Clean Architecture layers
│   ├── CALCULATION_ENGINE.md       # Exact mathematical formulas and serving logic
│   ├── KNOWN_PITFALLS_AND_GUARDRAILS.md # Architectural hazards and regression catalog
│   ├── DATABASE_SCHEMA.md          # SQLite and Room schema definitions (v3)
│   ├── DATA_PIPELINE.md            # End-to-end data lifecycle & edit mode
│   ├── BASKET_ARCHITECTURE.md      # In-memory staging basket workflow
│   └── ...                         # Security, privacy, performance, and UI specs
├── app/
│   └── src/
│       ├── main/
│       │   ├── assets/
│       │   │   ├── databases/built_in_foods.db  # Read-only USDA catalog (FTS5)
│       │   │   └── models/                      # OCR models
│       │   ├── java/com/macrobase/app/
│       │   │   ├── core/           # Design system, navigation, DI, utilities
│       │   │   ├── data/           # Room entities, DAOs, repositories, database
│       │   │   ├── domain/         # Domain models, repository contracts, use cases
│       │   │   └── feature/        # Compose screens and ViewModels
│       │   └── res/                # Vector drawables, strings, theme XMLs
│       └── test/                   # Comprehensive unit test suite (260+ tests)
├── ppocr-sdk/                      # PaddleOCR Android SDK integration
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## Getting Started

### Prerequisites

- Android Studio Iguana (2023.2.1) or newer
- JDK 17
- Android SDK 26 (Android 8.0) or higher
- Android NDK (for native OpenCV / ONNX Runtime components in PaddleOCR)

### Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/macrobase.git
   cd macrobase
   ```

2. Open the project in Android Studio or build via the command line:
   ```bash
   ./gradlew assembleDebug
   ```

3. Run the unit test suite:
   ```bash
   ./gradlew testDebugUnitTest
   ```

4. Install the debug build on a connected device or emulator:
   ```bash
   ./gradlew installDebug
   ```

---

## Privacy and Offline Operation

MacroBase is strictly zero-cloud and requires no internet permission for core operations. It does not collect telemetry, perform background analytics, or transmit your logs to external servers. All calculations, database queries, and OCR processing run entirely on physical device hardware.

---

## License

This project is open source. Check the LICENSE file for details.
