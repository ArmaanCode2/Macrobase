# Database Schema & Storage Architecture

MacroBase employs a clean two-database storage model:

1. **Static Built-in Food Database (`built_in_foods.db`)**: Read-only SQLite database containing USDA foods, normalized nutrients, structured portions, search aliases, and FTS5 full-text search index.
2. **Dynamic User Database (`macrobase_user.db`)**: Read/write AndroidX Room database for meal diary logs, custom foods, recipes, body weight, and water intake.

---

## 1. Built-in Food Database (`built_in_foods.db`)

Packaged in Android assets at `app/src/main/assets/databases/built_in_foods.db` (45.8 MB) and managed by `BuiltInDatabaseManager` and `LocalFoodDatabaseProvider`.

```
categories (1) ────< foods (N) ────< servings (N)
                      │      │
                      │      └──< food_aliases (N)
                      │
                      └──< food_nutrients (N) >──── (1) nutrients
```

### 1.1. Core Tables

- **`categories`** (25 rows): `id (PK)`, `name (UNIQUE)`, `description`, `display_order`
- **`foods`** (7,966 rows): `id (PK)`, `uuid (UNIQUE)`, `source_id`, `source_name`, `original_food_id`, `name`, `normalized_name`, `brand`, `category_id (FK)`, `food_type`, `serving_basis`, `calories`, `protein`, `carbohydrates`, `fat`, `fiber`, `sugar`, `saturated_fat`, `trans_fat`, `cholesterol`, `sodium`, `potassium`, `calcium`, `iron`, `magnesium`, `phosphorus`, `zinc`, `vitamin_a_rae`, `vitamin_c`, `vitamin_d_mcg`, `vitamin_e`, `vitamin_k`, `thiamin_b1`, `riboflavin_b2`, `niacin_b3`, `vitamin_b6`, `folate_b9`, `vitamin_b12`, `water`, `is_active`
- **`nutrients`** (247 rows): `id (PK)`, `code (UNIQUE)`, `name`, `unit`, `tag`, `description`
- **`food_nutrients`** (640,123 rows): `food_id (PK, FK)`, `nutrient_id (PK, FK)`, `amount`
- **`servings`** (14,641 rows): `id (PK)`, `food_id (FK)`, `description`, `unit_type`, `quantity`, `gram_weight`, `is_default`, `sequence`
- **`food_aliases`** (9,343 rows): `id (PK)`, `food_id (FK)`, `alias`, `alias_type`
- **`database_metadata`** (15 rows): `key (PK)`, `value` (`database_version: 1.0.0`, `schema_version: 1`)
- **`foods_fts`**: FTS5 virtual table for full-text search indexing with `unicode61 remove_diacritics 2`.

---

## 2. Dynamic User Room Database (`macrobase_user.db`)

Managed via **AndroidX Room** in `com.macrobase.app.data.database.UserDatabase`.

### 2.1. Table Schemas

#### 2.1.1. `diary_entries`
| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `PRIMARY KEY AUTOINCREMENT` | Entry identifier |
| `uuid` | `TEXT` | `NOT NULL UNIQUE` | Deterministic UUID |
| `dateEpochDay` | `INTEGER` | `NOT NULL, INDEXED` | `LocalDate.toEpochDay()` for high-speed range queries |
| `mealType` | `TEXT` | `NOT NULL, INDEXED` | `BREAKFAST`, `LUNCH`, `PM_SNACK`, `DINNER`, `SNACK` |
| `foodId` | `INTEGER` | `NOT NULL` | Reference to Food ID |
| `foodName` | `TEXT` | `NOT NULL` | Materialized food title snapshot |
| `userQuantity` | `REAL` | `NOT NULL` | Multiplier logged by user (e.g. `1.5`) |
| `servingDescription`| `TEXT`| `NOT NULL` | Serving label snapshot (e.g. `"1 cup"`) |
| `gramWeight` | `REAL` | `NOT NULL` | Gram weight per portion unit |
| `loggedCalories` | `REAL` | `NOT NULL` | Scaled calories snapshot |
| `loggedProtein` | `REAL` | `NOT NULL` | Scaled protein snapshot (g) |
| `loggedCarbs` | `REAL` | `NOT NULL` | Scaled carbs snapshot (g) |
| `loggedFat` | `REAL` | `NOT NULL` | Scaled fat snapshot (g) |
| `createdAt` | `INTEGER` | `NOT NULL` | Timestamp |

#### 2.1.2. `custom_foods`
| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `PRIMARY KEY AUTOINCREMENT` | Custom food ID |
| `uuid` | `TEXT` | `NOT NULL UNIQUE` | Unique UUID |
| `name` | `TEXT` | `NOT NULL, INDEXED` | Food title |
| `brand` | `TEXT` | `NULL` | Optional brand |
| `servingSize` | `REAL` | `NOT NULL` | Portion quantity |
| `servingUnit` | `TEXT` | `NOT NULL` | Serving unit label |
| `calories` | `REAL` | `NOT NULL` | Calories |
| `proteinGrams` | `REAL` | `NOT NULL` | Protein in grams |
| `carbsGrams` | `REAL` | `NOT NULL` | Carbs in grams |
| `fatGrams` | `REAL` | `NOT NULL` | Fat in grams |
| `fiberGrams` | `REAL` | `NULL` | Fiber (nullable) |
| `sugarGrams` | `REAL` | `NULL` | Sugar (nullable) |
| `sodiumMg` | `REAL` | `NULL` | Sodium (nullable) |
| `potassiumMg` | `REAL` | `NULL` | Potassium (nullable) |
| `calciumMg` | `REAL` | `NULL` | Calcium (nullable) |
| `ironMg` | `REAL` | `NULL` | Iron (nullable) |
| `createdAt` | `INTEGER` | `NOT NULL` | Timestamp |

#### 2.1.3. `recipes`
| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `PRIMARY KEY AUTOINCREMENT` | Recipe ID |
| `uuid` | `TEXT` | `NOT NULL UNIQUE` | Unique UUID |
| `name` | `TEXT` | `NOT NULL` | Recipe title |
| `servingsProduced`| `INTEGER`| `NOT NULL` | Total servings produced |
| `ingredientsJson` | `TEXT` | `NOT NULL` | Serialized ingredient items |
| `caloriesPerServing`| `REAL` | `NOT NULL` | Calories per serving |
| `proteinPerServing` | `REAL` | `NOT NULL` | Protein per serving (g) |
| `carbsPerServing` | `REAL` | `NOT NULL` | Carbs per serving (g) |
| `fatPerServing` | `REAL` | `NOT NULL` | Fat per serving (g) |
| `createdAt` | `INTEGER` | `NOT NULL` | Timestamp |

#### 2.1.4. `weight_entries`
| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `PRIMARY KEY AUTOINCREMENT` | Entry ID |
| `dateEpochDay` | `INTEGER` | `NOT NULL UNIQUE, INDEXED` | One weight entry per date |
| `weightKg` | `REAL` | `NOT NULL` | Body weight in kilograms |
| `note` | `TEXT` | `NULL` | Optional log note |
| `createdAt` | `INTEGER` | `NOT NULL` | Timestamp |

#### 2.1.5. `water_logs`
| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `PRIMARY KEY AUTOINCREMENT` | Log ID |
| `dateEpochDay` | `INTEGER` | `NOT NULL, INDEXED` | Date |
| `amountMl` | `REAL` | `NOT NULL` | Intake in mL |
| `timestamp` | `INTEGER` | `NOT NULL` | Timestamp |

---

## 3. Versioning & Migration Strategy

- **Built-in Database**: Versioned via `database_metadata` (`database_version: 1.0.0`, `schema_version: 1`). Upgrades are atomic file replacements managed by `BuiltInDatabaseManager`.
- **User Database**: Versioned via Room schema migrations (`MIGRATION_1_2` infrastructure). Non-destructive migration guarantees zero user data loss.
- **Stable Food Identity Strategy**: Diary entries record the food reference (`foodId`, `uuid`) and snapshot immutable nutrition and portion descriptions, ensuring that older diary entries remain readable and accurate across future database updates.
