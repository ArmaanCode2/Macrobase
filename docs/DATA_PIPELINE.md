# Data Pipeline & Flow Documentation

## 1. Overview

This document outlines the complete data lineage from raw USDA datasets to the runtime Android application presentation.

---

## 2. Ingestion to Runtime Flow

```
[ Raw USDA Foundation JSON ]        [ Raw USDA SR Legacy JSON ]
            │                                     │
            └─────────────────┬───────────────────┘
                              │
                              ▼
            [ Ingestion & Normalization Engine ]
            - Standardizes 247 nutrients to 100g/100mL base
            - Generates 14,641 structured portion servings
            - Normalizes text & compiles 9,343 search aliases
            - Builds FTS5 search index with diacritic stripping
                              │
                              ▼
            [ `built_in_foods.db` (Production Asset) ]
            - 45.8 MB SQLite database
            - Packed at `app/src/main/assets/databases/built_in_foods.db`
                              │
                              ▼
            [ BuiltInDatabaseManager ]
            - First launch: atomic copy to internal app storage
            - Subsequent launches: reuse installed instance
            - Opens read-only on Dispatchers.IO
                              │
                              ▼
            [ LocalFoodDatabaseProvider ]
            - FTS5 tokenized & prefix search (e.g. `apple*`, `greek* yogurt*`)
            - Single food lookup & portion extraction
            - Strict preservation of nullable unanalyzed nutrients
                              │
                              ▼
            [ FoodRepository & Use Cases ]
            - SearchFoodsUseCase
            - GetFoodDetailsUseCase
            - CalculateNutritionForServingUseCase
                              │
                              ▼
            [ ViewModels & Compose UI ]
            - SearchViewModel  ──>  SearchScreen
            - FoodDetailViewModel  ──>  FoodDetailScreen
            - HomeViewModel  ──>  HomeScreen
```

---

## 3. Provider Extension Pipeline

Adding a new food provider (e.g. Open Food Facts or Indian Food Database) adheres to the identical downstream pipeline:

```
[ External Data Source ]
          │
          ▼
[ New FoodDataProvider implementation ]
(e.g., OpenFoodFactsProvider)
          │
          ▼
[ Domain Model Mapping (Food, Serving, Nutrition) ]
          │
          ▼
[ FoodRepositoryImpl (Federated Search & ID Resolution) ]
          │
          ▼
[ Existing Use Cases & UI — ZERO CODE CHANGES ]
```
