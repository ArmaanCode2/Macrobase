#!/usr/bin/env python3
"""
MacroBase — Indian Food Database Builder
========================================
Builds the canonical read-only SQLite built-in food database (built_in_foods.db)
from macrobase_indian_foods_canonical.csv.

Outputs:
  - app/src/main/assets/databases/built_in_foods.db
  - built_in_foods.db (root copy)

Schema conforms exactly to MacroBase built-in database architecture:
  - foods
  - servings
  - categories
  - nutrients
  - food_nutrients
  - food_aliases
  - database_metadata
  - foods_fts (FTS5 full-text search)
"""

import os
import re
import csv
import shutil
import sqlite3
from datetime import datetime, timezone

WORKSPACE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSV_PATH = os.path.join(WORKSPACE_DIR, "macrobase_indian_foods_canonical.csv")
TARGET_ASSET_DB = os.path.join(WORKSPACE_DIR, "app", "src", "main", "assets", "databases", "built_in_foods.db")
TARGET_ROOT_DB = os.path.join(WORKSPACE_DIR, "built_in_foods.db")
TEMP_DB = os.path.join(WORKSPACE_DIR, "built_in_foods_temp.db")

SCHEMA_SQL = """
PRAGMA foreign_keys = ON;

CREATE TABLE categories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT UNIQUE NOT NULL,
    description TEXT,
    display_order INTEGER DEFAULT 0
);

CREATE TABLE nutrients (
    id INTEGER PRIMARY KEY,
    code TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL,
    unit TEXT NOT NULL,
    tag TEXT DEFAULT 'other',
    description TEXT
);

CREATE TABLE foods (
    id INTEGER PRIMARY KEY,
    uuid TEXT UNIQUE NOT NULL,
    source_id TEXT NOT NULL,
    source_name TEXT NOT NULL,
    original_food_id TEXT,
    name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    brand TEXT,
    category_id INTEGER REFERENCES categories(id) ON DELETE SET NULL,
    food_type TEXT NOT NULL DEFAULT 'generic',
    serving_basis TEXT NOT NULL DEFAULT '100g',
    density_g_per_ml REAL,
    
    -- Core Macronutrients per 100g
    calories REAL,
    protein REAL,
    carbohydrates REAL,
    fat REAL,
    fiber REAL,
    sugar REAL,
    saturated_fat REAL,
    trans_fat REAL,
    cholesterol REAL,
    
    -- Key Minerals per 100g
    sodium REAL,
    potassium REAL,
    calcium REAL,
    iron REAL,
    magnesium REAL,
    phosphorus REAL,
    zinc REAL,
    copper REAL,
    manganese REAL,
    selenium REAL,
    
    -- Key Vitamins per 100g
    vitamin_a_rae REAL,
    vitamin_c REAL,
    vitamin_d_mcg REAL,
    vitamin_e REAL,
    vitamin_k REAL,
    thiamin_b1 REAL,
    riboflavin_b2 REAL,
    niacin_b3 REAL,
    pantothenic_acid_b5 REAL,
    vitamin_b6 REAL,
    folate_b9 REAL,
    folate_dfe REAL,
    vitamin_b12 REAL,
    
    water REAL,
    is_active INTEGER DEFAULT 1,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE servings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    food_id INTEGER NOT NULL REFERENCES foods(id) ON DELETE CASCADE,
    description TEXT NOT NULL,
    unit_type TEXT NOT NULL,
    quantity REAL NOT NULL DEFAULT 1.0,
    gram_weight REAL NOT NULL,
    is_default INTEGER DEFAULT 0,
    sequence INTEGER DEFAULT 0
);

CREATE TABLE food_aliases (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    food_id INTEGER NOT NULL REFERENCES foods(id) ON DELETE CASCADE,
    alias TEXT NOT NULL,
    alias_type TEXT DEFAULT 'synonym'
);

CREATE TABLE food_nutrients (
    food_id INTEGER NOT NULL REFERENCES foods(id) ON DELETE CASCADE,
    nutrient_id INTEGER NOT NULL REFERENCES nutrients(id) ON DELETE CASCADE,
    amount REAL NOT NULL,
    PRIMARY KEY (food_id, nutrient_id)
);

CREATE TABLE database_metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- Virtual Table for FTS5 Fast Prefix Search
CREATE VIRTUAL TABLE foods_fts USING fts5(
    food_id UNINDEXED,
    name,
    normalized_name,
    category,
    aliases,
    tokenize='unicode61 remove_diacritics 2'
);

-- Indexes
CREATE INDEX idx_foods_normalized_name ON foods(normalized_name);
CREATE INDEX idx_foods_category_id ON foods(category_id);
CREATE INDEX idx_foods_source_id ON foods(source_id);
CREATE INDEX idx_foods_uuid ON foods(uuid);
CREATE INDEX idx_servings_food_id ON servings(food_id);
CREATE INDEX idx_food_nutrients_food_id ON food_nutrients(food_id);
CREATE INDEX idx_food_nutrients_nutrient_id ON food_nutrients(nutrient_id);
CREATE INDEX idx_food_aliases_food_id ON food_aliases(food_id);
CREATE INDEX idx_food_aliases_alias ON food_aliases(alias);
"""

CORE_NUTRIENTS = [
    (208, '208', 'Energy', 'kcal', 'macro', 'Total metabolizable energy'),
    (203, '203', 'Protein', 'g', 'macro', 'Total protein'),
    (204, '204', 'Total lipid (fat)', 'g', 'macro', 'Total lipid content'),
    (205, '205', 'Carbohydrate, by difference', 'g', 'macro', 'Total carbohydrates by difference'),
    (291, '291', 'Fiber, total dietary', 'g', 'macro', 'Total dietary fiber'),
    (269, '269', 'Total Sugars', 'g', 'macro', 'Total sugars'),
    (606, '606', 'Fatty acids, total saturated', 'g', 'macro', 'Total saturated fatty acids'),
    (601, '601', 'Cholesterol', 'mg', 'lipid', 'Cholesterol'),
    (307, '307', 'Sodium, Na', 'mg', 'mineral', 'Sodium content'),
    (306, '306', 'Potassium, K', 'mg', 'mineral', 'Potassium content'),
    (301, '301', 'Calcium, Ca', 'mg', 'mineral', 'Calcium content'),
    (303, '303', 'Iron, Fe', 'mg', 'mineral', 'Iron content'),
    (304, '304', 'Magnesium, Mg', 'mg', 'mineral', 'Magnesium content'),
    (401, '401', 'Vitamin C, total ascorbic acid', 'mg', 'vitamin', 'Total ascorbic acid'),
    (417, '417', 'Folate, total', 'mcg', 'vitamin', 'Total dietary folate'),
    (321, '321', 'Carotenoids', 'mcg', 'other', 'Total carotenoids'),
    (645, '645', 'Fatty acids, total monounsaturated', 'mg', 'lipid', 'Total monounsaturated fatty acids in mg'),
    (646, '646', 'Fatty acids, total polyunsaturated', 'mg', 'lipid', 'Total polyunsaturated fatty acids in mg')
]

def parse_float_or_null(val):
    if val is None:
        return None
    s = str(val).strip()
    if not s or s.lower() == 'null' or s.lower() == 'nan':
        return None
    try:
        return float(s)
    except ValueError:
        return None

def extract_aliases_from_name(name):
    aliases = []
    # Extract parenthetical substrings e.g. "Almond biscuit (Badam ke biscuit)" -> "Badam ke biscuit"
    matches = re.findall(r'\((.*?)\)', name)
    for m in matches:
        parts = re.split(r'[/,;]', m)
        for p in parts:
            clean = p.strip()
            if clean and len(clean) > 1 and clean.lower() != name.lower():
                aliases.append(clean)
    
    # Extract slash alternatives e.g. "Chapati/Roti" -> "Chapati", "Roti"
    if '/' in name and not '(' in name:
        parts = name.split('/')
        for p in parts:
            clean = p.strip()
            if clean and len(clean) > 1:
                aliases.append(clean)
                
    return list(dict.fromkeys(aliases))  # preserve order, unique

def build_database():
    if not os.path.exists(CSV_PATH):
        raise FileNotFoundError(f"Source CSV not found at {CSV_PATH}")

    if os.path.exists(TEMP_DB):
        os.remove(TEMP_DB)

    print(f"Reading CSV from: {CSV_PATH}")
    with open(CSV_PATH, mode='r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        rows = list(reader)

    print(f"Total rows in CSV: {len(rows)}")

    conn = sqlite3.connect(TEMP_DB)
    cursor = conn.cursor()

    print("Creating SQLite schema & indexes...")
    cursor.executescript(SCHEMA_SQL)

    # Insert Category
    cursor.execute(
        "INSERT INTO categories (id, name, description, display_order) VALUES (?, ?, ?, ?)",
        (1, "Indian Foods & Recipes", "Authentic Indian dishes, curries, breads, sweets and regional recipes", 1)
    )
    category_id = 1

    # Insert Nutrients Reference
    for nut in CORE_NUTRIENTS:
        cursor.execute(
            "INSERT INTO nutrients (id, code, name, unit, tag, description) VALUES (?, ?, ?, ?, ?, ?)",
            nut
        )

    # Insert Foods, Servings, Food Nutrients, Aliases, FTS
    food_count = 0
    serving_count = 0
    alias_count = 0
    food_nutrients_count = 0

    for row in rows:
        food_id = int(row['id'])
        uuid = row['uuid']
        source_id = row['sourceId']
        source_name = row['source']
        original_food_id = source_id
        name = row['name'].strip()
        normalized_name = row['normalizedName'].strip() or name.lower()
        brand = row['brand'].strip() or None
        food_type = row['foodType'].strip() or "INDIAN_RECIPE"
        serving_basis = "100g"

        calories = parse_float_or_null(row['caloriesKcalPer100g'])
        protein = parse_float_or_null(row['proteinGPer100g'])
        carbs = parse_float_or_null(row['carbohydratesGPer100g'])
        fat = parse_float_or_null(row['fatGPer100g'])
        fiber = parse_float_or_null(row['fiberGPer100g'])
        sugar = parse_float_or_null(row['sugarsGPer100g'])
        sat_fat = parse_float_or_null(row['saturatedFatGPer100g'])
        trans_fat = parse_float_or_null(row['transFatGPer100g'])
        cholesterol = parse_float_or_null(row['cholesterolMgPer100g'])
        sodium = parse_float_or_null(row['sodiumMgPer100g'])
        potassium = parse_float_or_null(row['potassiumMgPer100g'])
        calcium = parse_float_or_null(row['calciumMgPer100g'])
        iron = parse_float_or_null(row['ironMgPer100g'])
        magnesium = parse_float_or_null(row['magnesiumMgPer100g'])
        
        # Vitamins (only populate if non-empty, preserve NULLs)
        vit_a = parse_float_or_null(row['vitaminAMcgPer100g'])
        vit_c = parse_float_or_null(row['vitaminCMgPer100g'])
        vit_d = parse_float_or_null(row['vitaminDMcgPer100g'])
        vit_e = parse_float_or_null(row['vitaminEMgPer100g'])
        vit_k = parse_float_or_null(row['vitaminKMcgPer100g'])
        folate = parse_float_or_null(row['folateMcgPer100g'])
        
        # Extended INDB lipid/carotenoid fields
        carotenoids = parse_float_or_null(row['carotenoidsMcgPer100g'])
        mufa = parse_float_or_null(row['mufaMgPer100g'])
        pufa = parse_float_or_null(row['pufaMgPer100g'])

        # 1. Insert into `foods` table
        cursor.execute("""
            INSERT INTO foods (
                id, uuid, source_id, source_name, original_food_id, name, normalized_name,
                brand, category_id, food_type, serving_basis, density_g_per_ml,
                calories, protein, carbohydrates, fat, fiber, sugar, saturated_fat, trans_fat, cholesterol,
                sodium, potassium, calcium, iron, magnesium, phosphorus, zinc, copper, manganese, selenium,
                vitamin_a_rae, vitamin_c, vitamin_d_mcg, vitamin_e, vitamin_k,
                thiamin_b1, riboflavin_b2, niacin_b3, pantothenic_acid_b5, vitamin_b6, folate_b9, folate_dfe, vitamin_b12,
                water, is_active, created_at, updated_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?,
                ?, 1, '2026-08-23 12:00:00', '2026-08-23 12:00:00'
            )
        """, (
            food_id, uuid, source_id, source_name, original_food_id, name, normalized_name,
            brand, category_id, food_type, serving_basis, None,
            calories, protein, carbs, fat, fiber, sugar, sat_fat, trans_fat, cholesterol,
            sodium, potassium, calcium, iron, magnesium, None, None, None, None, None,
            vit_a, vit_c, vit_d, vit_e, vit_k,
            None, None, None, None, None, folate, None, None,
            None
        ))
        food_count += 1

        # 2. Insert Servings
        # Baseline: 100g metric serving
        has_custom_serving = bool(row['servingUnit'].strip())
        cursor.execute("""
            INSERT INTO servings (food_id, description, unit_type, quantity, gram_weight, is_default, sequence)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """, (
            food_id,
            "100 g",
            "g",
            100.0,
            100.0,
            1 if not has_custom_serving else 0,
            0
        ))
        serving_count += 1

        # Custom/Portion Serving from CSV if present
        if has_custom_serving:
            s_unit = row['servingUnit'].strip()
            s_qty = parse_float_or_null(row['servingQuantity']) or 1.0
            s_desc = row['servingDescription'].strip() or f"{s_qty:g} {s_unit}"
            s_weight = parse_float_or_null(row['servingWeightG']) or 0.0

            cursor.execute("""
                INSERT INTO servings (food_id, description, unit_type, quantity, gram_weight, is_default, sequence)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """, (
                food_id,
                s_desc,
                s_unit,
                s_qty,
                s_weight,
                1,
                1
            ))
            serving_count += 1

        # 3. Insert Food Nutrients
        nutrient_map = [
            (208, calories),
            (203, protein),
            (204, fat),
            (205, carbs),
            (291, fiber),
            (269, sugar),
            (606, sat_fat),
            (601, cholesterol),
            (307, sodium),
            (306, potassium),
            (301, calcium),
            (303, iron),
            (304, magnesium),
            (401, vit_c),
            (417, folate),
            (321, carotenoids),
            (645, mufa),
            (646, pufa)
        ]
        for nut_id, amount in nutrient_map:
            if amount is not None:
                cursor.execute("""
                    INSERT INTO food_nutrients (food_id, nutrient_id, amount)
                    VALUES (?, ?, ?)
                """, (food_id, nut_id, amount))
                food_nutrients_count += 1

        # 4. Insert Aliases & FTS Indexing
        extracted_aliases = extract_aliases_from_name(name)
        for alias in extracted_aliases:
            cursor.execute("""
                INSERT INTO food_aliases (food_id, alias, alias_type)
                VALUES (?, ?, 'vernacular_synonym')
            """, (food_id, alias))
            alias_count += 1

        # FTS5 Record
        aliases_str = " ".join(extracted_aliases)
        cursor.execute("""
            INSERT INTO foods_fts (food_id, name, normalized_name, category, aliases)
            VALUES (?, ?, ?, ?, ?)
        """, (food_id, name, normalized_name, "Indian Foods", aliases_str))

    # 5. Insert Database Metadata
    metadata = [
        ("database_version", "2.0.0"),
        ("schema_version", "1"),
        ("pipeline_version", "2026.2"),
        ("dataset_name", "macrobase_indian_foods_canonical"),
        ("dataset_version", "2026-08"),
        ("build_timestamp", datetime.now(timezone.utc).isoformat()),
        ("total_foods", str(food_count)),
        ("total_servings", str(serving_count)),
        ("total_categories", "1"),
        ("total_aliases", str(alias_count)),
        ("total_food_nutrients", str(food_nutrients_count)),
        ("supported_providers", "INDIAN_CANONICAL,CUSTOM"),
        ("sqlite_fts_version", "FTS5 unicode61 remove_diacritics 2")
    ]
    for k, v in metadata:
        cursor.execute("INSERT INTO database_metadata (key, value) VALUES (?, ?)", (k, v))

    conn.commit()
    conn.close()

    print(f"\nSuccessfully generated database:")
    print(f"  - Foods: {food_count}")
    print(f"  - Servings: {serving_count}")
    print(f"  - Food Nutrients: {food_nutrients_count}")
    print(f"  - Aliases: {alias_count}")
    print(f"  - Categories: 1")

    # Copy to target destinations
    os.makedirs(os.path.dirname(TARGET_ASSET_DB), exist_ok=True)
    shutil.copy2(TEMP_DB, TARGET_ASSET_DB)
    shutil.copy2(TEMP_DB, TARGET_ROOT_DB)
    os.remove(TEMP_DB)

    print(f"Installed database asset to:\n  -> {TARGET_ASSET_DB}\n  -> {TARGET_ROOT_DB}")

if __name__ == "__main__":
    build_database()
