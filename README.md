# MacroBase

MacroBase is an offline-first nutrition and macronutrient tracking application for Android. It is designed for fast food logging, complete data privacy, and flexible portion editing without requiring an internet connection or a user account.

## Overview

Most nutrition tracking apps rely on cloud services, subscriptions, and telemetry. MacroBase stores all user data locally on the device using SQLite and Room. It includes a pre-packaged offline food database based on USDA FoodData Central, an on-device OCR scanner for physical nutrition labels, and a centralized food basket that lets you edit multiple items in bulk before committing them to your diary.

## Key Features

- Daily Dashboard: Tracks daily calorie limits and macronutrient targets (protein, carbs, fat) across six meal slots: Breakfast, Lunch, Dinner, AM Snack, PM Snack, and Late Snack. Includes single-tap date navigation and historical review.
- Fast Offline Search: Instant food search using SQLite FTS5 full-text indexing over tens of thousands of built-in USDA entries, custom foods, and recipes.
- Centralized Food Basket: A bulk editing screen for pending logs. Edit quantities and serving units inline, apply common dates or meal types across all items, and review total calories and macros before saving.
- On-Device Nutrition Label Scanner: Scan physical food labels using the device camera. The app parses serving sizes, calories, and nutrient breakdown directly on-device using PaddleOCR and ML Kit.
- Custom Foods and Recipes: Create custom foods with custom serving sizes, or combine ingredients into multi-serving recipes with automatic per-portion nutrient calculations.
- Water and Weight Tracking: Log daily water intake in customizable increments and monitor weight progress over time.
- Statistics and Adherence: Monthly calendar adherence view, macronutrient breakdown charts, and progress toward nutritional goals.
- Data Portability: Export and import your entire database and logs via JSON and raw database backups.

## Tech Stack and Architecture

The app is built following Clean Architecture principles with clear separation of concerns across presentation, domain, and data layers.

- UI: Jetpack Compose with Material 3 design system
- Language: Kotlin (Coroutines, Flow, StateFlow)
- Local Storage: Room (SQLite) with FTS5 virtual tables for full-text search
- Dependency Injection: Koin
- Navigation: Jetpack Navigation Compose
- Machine Learning and OCR: PaddleOCR (ONNX Runtime, OpenCV) and Google ML Kit Text Recognition
- Architecture: MVVM with unidirectional data flow (UDF)
- Testing: JUnit 4, Kotlinx Coroutines Test

## Project Structure

```
macrobase/
├── app/
│   └── src/
│       ├── main/
│       │   ├── assets/
│       │   │   ├── databases/built_in_foods.db
│       │   │   └── models/
│       │   ├── java/com/macrobase/app/
│       │   │   ├── core/           # Design system, navigation, DI, utilities
│       │   │   ├── data/           # Room entities, DAOs, repositories, database
│       │   │   ├── domain/         # Domain models, repository contracts, use cases
│       │   │   └── feature/        # Compose screens and ViewModels
│       │   └── res/
│       └── test/                   # Unit test suite
├── ppocr-sdk/                      # PaddleOCR Android SDK integration
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

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

## Privacy and Offline Operation

MacroBase does not collect telemetry, perform background analytics, or transmit your logs to external servers. All calculations, database queries, and OCR processing run entirely on your physical hardware.

## License

This project is open source. Check the LICENSE file for details.
