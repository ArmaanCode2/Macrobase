#!/usr/bin/env python3
"""
MacroBase built-in food catalog builder.

The only writer of app/src/main/assets/databases/built_in_foods.db. It reads the INDB recipe
rows in macrobase_indian_foods_canonical.csv, applies the curation files in this folder, checks
every food and builds the read-only SQLite catalog the app ships. Nothing is written when a check
fails. See README.md in this folder for the file formats.

Usage:
    python tools/catalog/build_catalog.py            check, and write the catalog if it changed
    python tools/catalog/build_catalog.py --check    check, and that the committed catalog is current

Standard library only.
"""

import argparse
import csv
import hashlib
import os
import re
import shutil
import sqlite3
import sys
import tempfile
import unicodedata
from datetime import date, datetime, timezone

CATALOG_DIR = os.path.dirname(os.path.abspath(__file__))
WORKSPACE_DIR = os.path.dirname(os.path.dirname(CATALOG_DIR))
FOODS_CSV = os.path.join(WORKSPACE_DIR, "macrobase_indian_foods_canonical.csv")
TARGET_DB = os.path.join(WORKSPACE_DIR, "app", "src", "main", "assets", "databases", "built_in_foods.db")
VERSIONS_CSV = os.path.join(CATALOG_DIR, "catalog_versions.csv")
# The catalog's content fingerprint, bundled beside it. BuiltInDatabaseManager replaces an installed
# catalog whose fingerprint differs, so a phone never keeps a stale copy under an unchanged version.
FINGERPRINT_FILE = os.path.join(os.path.dirname(TARGET_DB), "built_in_foods.fingerprint")

# Must equal BuiltInDatabaseManager.EXPECTED_DATABASE_VERSION (DatabaseIntegrationTests checks).
# Installed phones replace their catalog only when it changes, so the build refuses new content
# under a version already pinned in catalog_versions.csv.
DATABASE_VERSION = "2.5.0"
# The table layout; BuiltInDatabaseManager.EXPECTED_SCHEMA_VERSION mirrors it.
SCHEMA_VERSION = "2"
# Must equal BuiltInDatabaseManager.EXPECTED_DATASET_NAME, or the app refuses the catalog.
DATASET_NAME = "macrobase_indian_foods_canonical"
DATASET_VERSION = "2026-10"
PIPELINE_VERSION = "2026.3"

# Food ids of custom foods and recipes (FoodRepositoryImpl.CUSTOM_FOOD_ID_OFFSET up to
# Recipe.FOOD_ID_RANGE_END). A catalog food id must never fall in this range.
USER_ID_RANGE = (100_000_000, 300_000_000)

# Plausible gram range for one unit of a household measure. Units not listed are allowed
# DEFAULT_BOUNDS_G. DatabaseIntegrationTests.builtInDatabase_portionWeightsArePlausible mirrors the
# container units (ml down to curry bowl).
PORTION_BOUNDS_G = {
    "ml": (0.8, 1.5), "gm": (0.8, 1.5),
    "teaspoon": (2, 10), "tablespoon": (7, 30),
    "cup": (100, 400), "tea cup": (80, 300),
    "ice cream cup": (40, 300), "ice-cream cup": (40, 300), "souffle cup": (40, 300),
    "glass": (120, 500), "tall glass": (150, 600), "juice glass": (100, 400),
    "sundae glass": (100, 500), "tall stemmed glass": (100, 500),
    "bowl": (80, 600), "small bowl": (50, 400), "soup bowl": (120, 600), "curry bowl": (80, 600),
    "plate": (60, 500),
    # One piece of a food. INDB sometimes records the whole recipe yield as one piece.
    "piece": (3, 200), "large piece": (20, 350), "slice": (10, 250), "large slice": (20, 350),
    "egg": (30, 80), "omelette": (40, 300),
    "biscuit": (3, 40), "cookie": (3, 40), "cookies": (3, 40), "candy": (2, 40), "straw": (2, 30),
    "meringue": (2, 40), "aigrette": (5, 60), "ribbon": (5, 60), "shell": (5, 60), "finger": (10, 80),
    "gingerman": (10, 80), "horn": (15, 100), "swan": (15, 100), "square": (10, 120),
    "gulab jamun": (20, 90), "ladoo": (15, 80), "burfi": (10, 60), "kulfi": (30, 150), "gunjia": (15, 80),
    "samosa": (30, 150), "kachori": (20, 100), "pakora": (5, 60), "mulik/fritter": (5, 80),
    "vada": (20, 100), "bonda": (20, 100), "cutlet": (20, 120), "tikki": (20, 120), "patty": (30, 200),
    "kofta": (15, 100), "kebab": (15, 150), "kabab": (15, 150), "chop": (30, 250), "chops": (30, 250),
    "gushtaba": (30, 250), "spring roll": (20, 150), "puff": (30, 150), "tart": (20, 150),
    "pastry": (30, 200), "\u00e9clair": (20, 100), "triangle": (10, 100), "toasted triangle": (10, 100),
    "tukra": (20, 150), "muthia": (10, 80), "dhokla": (10, 80), "nest": (20, 200),
    "idli": (20, 80), "dosa": (30, 250), "uttapam": (50, 250), "appam": (30, 150), "pesarattu": (40, 200),
    "cheela": (30, 200), "pancake": (20, 200), "puttu": (40, 300),
    "chapati": (20, 80), "roti": (20, 80), "poori": (10, 60), "bhatura": (30, 150), "naan": (40, 200),
    "parantha": (40, 200), "thepla": (20, 100), "khakhra": (10, 60), "toast": (15, 120),
    "bun": (20, 150), "hot dog bun": (30, 150), "bread roll": (20, 150),
    "sandwich": (40, 350), "burger": (80, 400), "kaathi roll": (80, 400), "roll": (20, 400),
    "fillet": (40, 300), "chicken breast": (80, 350), "joint": (50, 400),
    "potato": (50, 400), "tomato": (40, 300), "stuffed tomato": (50, 300), "capsicum": (50, 300),
    "brinjal": (50, 400), "cauliflower steak": (50, 300), "orange case": (50, 300),
}
DEFAULT_BOUNDS_G = (1, 1000)

# Plausibility limits per 100 g. A food outside them needs a row in data_exceptions.csv.
FAT_LIMIT_G = 45.0  # deep-fried dishes counted with the whole pot of frying oil land at 50-90 g
SODIUM_LIMIT_MG = 2500.0
MACRO_TOTAL_LIMIT_G = 102.0
ENERGY_TOLERANCE = 0.25  # share of the larger of stated and macro-derived energy
ENERGY_TOLERANCE_KCAL = 20.0
# Impossible per 100 g: these stop the build outright. Pure salt is 39.3 g sodium per 100 g.
GRAM_CEILING = 100.0
SODIUM_CEILING_MG = 39_300.0

# Food groups in display order; a food names one in its category column. Ids follow this order, so
# append new groups at the end. INDB recipes have no category and take the first group (id 1 since 2.1.0).
CATEGORIES = [
    ("Indian Foods & Recipes", "Authentic Indian dishes, curries, breads, sweets and regional recipes"),
    ("Cereals and flours", "Rice, wheat, millets and their flours, raw or cooked plain"),
    ("Breads and breakfast cereals", "Bread, buns, pasta, oats and ready breakfast cereals"),
    ("Pulses and legumes", "Dry dals, beans, chickpeas, soya and sprouts"),
    ("Dairy", "Milk, curd, paneer, butter, ghee, cheese and cream"),
    ("Eggs", "Eggs, whole or in parts"),
    ("Meat, poultry and fish", "Raw chicken, mutton, fish and seafood"),
    ("Vegetables", "Raw vegetables and herbs"),
    ("Fruits", "Fresh and dried fruit"),
    ("Nuts and seeds", "Nuts, seeds, makhana and nut butters"),
    ("Oils and fats", "Cooking oils, vanaspati and other fats"),
    ("Sugars and sweets", "Sugar, honey and chocolate"),
    ("Drinks", "Tea, coffee, juices, soft drinks and alcohol"),
    ("Packaged snacks and biscuits", "Biscuits, rusks, chips, namkeen and instant noodles"),
    ("Packaged foods", "Branded and other packaged foods"),
]
DEFAULT_CATEGORY = CATEGORIES[0][0]
CARBOHYDRATE_RULES = ("total", "available")

# corrections.csv. excess_frying_oil: INDB counts the whole pot of frying oil as eaten and none of
# the water frying dries off; the oil comes out but what the food keeps, and the water is set.
# values_from: the listed columns (or all nutrient columns) come from a reference row of an
# imported file, one marked role=reference. Corrections apply in this order.
CORRECTION_KINDS = ("excess_frying_oil", "cooked_water", "values_from")
OIL_KCAL_PER_G = 9.0  # INDB's energy counts fat at 9 kcal/g, frying oil included
# Columns that come with the frying oil and shrink with the fat; every other nutrient comes with
# the food and is concentrated as the excess oil leaves
OIL_COLUMNS = ("saturatedFatGPer100g", "transFatGPer100g", "mufaMgPer100g", "pufaMgPer100g", "vitaminEMgPer100g")

# recipes.csv: a standard recipe is worked out from its ingredients in recipe_ingredients.csv
RECIPE_SOURCE = "MacroBase standard recipes"
RECIPE_ID = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
UNSTATED_SHARE = 0.10  # of a recipe's solids that may come from ingredients not stating a nutrient

# Extra household portions of an imported food: "1 cup, chopped=91|1 medium=118" in the portions column
PORTION_ENTRY = re.compile(r"^(?P<description>[^=|]+)=(?P<grams>[0-9.]+)$")
MAX_PORTION_G = 2000.0

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

-- Search words for LocalFoodDatabaseProvider. Android's SQLite has no FTS5, so search is plain SQL
-- on these columns. terms and name_terms hold folded words with a space before and after each one.
CREATE TABLE food_search (
    food_id INTEGER PRIMARY KEY REFERENCES foods(id) ON DELETE CASCADE,
    terms TEXT NOT NULL,       -- words of the name, the aliases and their spelling variants
    name_terms TEXT NOT NULL,  -- words of the name only
    names TEXT NOT NULL,       -- the name and each alias as a whole phrase, between | marks
    priority INTEGER NOT NULL DEFAULT 0  -- how commonly the food is eaten, 0 to 3; higher ranks first
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

# (nutrient id, code, name, unit, tag, description, CSV column)
NUTRIENTS = [
    (208, '208', 'Energy', 'kcal', 'macro', 'Total metabolizable energy', 'caloriesKcalPer100g'),
    (203, '203', 'Protein', 'g', 'macro', 'Total protein', 'proteinGPer100g'),
    (204, '204', 'Total lipid (fat)', 'g', 'macro', 'Total lipid content', 'fatGPer100g'),
    (205, '205', 'Carbohydrate, by difference', 'g', 'macro', 'Total carbohydrates by difference', 'carbohydratesGPer100g'),
    (291, '291', 'Fiber, total dietary', 'g', 'macro', 'Total dietary fiber', 'fiberGPer100g'),
    (269, '269', 'Total Sugars', 'g', 'macro', 'Total sugars', 'sugarsGPer100g'),
    (606, '606', 'Fatty acids, total saturated', 'g', 'macro', 'Total saturated fatty acids', 'saturatedFatGPer100g'),
    (601, '601', 'Cholesterol', 'mg', 'lipid', 'Cholesterol', 'cholesterolMgPer100g'),
    (307, '307', 'Sodium, Na', 'mg', 'mineral', 'Sodium content', 'sodiumMgPer100g'),
    (306, '306', 'Potassium, K', 'mg', 'mineral', 'Potassium content', 'potassiumMgPer100g'),
    (301, '301', 'Calcium, Ca', 'mg', 'mineral', 'Calcium content', 'calciumMgPer100g'),
    (303, '303', 'Iron, Fe', 'mg', 'mineral', 'Iron content', 'ironMgPer100g'),
    (304, '304', 'Magnesium, Mg', 'mg', 'mineral', 'Magnesium content', 'magnesiumMgPer100g'),
    (401, '401', 'Vitamin C, total ascorbic acid', 'mg', 'vitamin', 'Total ascorbic acid', 'vitaminCMgPer100g'),
    (417, '417', 'Folate, total', 'mcg', 'vitamin', 'Total dietary folate', 'folateMcgPer100g'),
    (321, '321', 'Carotenoids', 'mcg', 'other', 'Total carotenoids', 'carotenoidsMcgPer100g'),
    (645, '645', 'Fatty acids, total monounsaturated', 'mg', 'lipid', 'Total monounsaturated fatty acids in mg', 'mufaMgPer100g'),
    (646, '646', 'Fatty acids, total polyunsaturated', 'mg', 'lipid', 'Total polyunsaturated fatty acids in mg', 'pufaMgPer100g'),
]

# Columns a curation override may change. Ids, uuids and the source stay as imported, and the
# normalized name follows the name.
OVERRIDABLE_COLUMNS = {
    "name", "servingUnit", "servingQuantity", "servingDescription", "servingWeightG",
    "caloriesKcalPer100g", "proteinGPer100g", "carbohydratesGPer100g", "fatGPer100g", "fiberGPer100g",
    "sugarsGPer100g", "saturatedFatGPer100g", "transFatGPer100g", "cholesterolMgPer100g", "sodiumMgPer100g",
    "potassiumMgPer100g", "calciumMgPer100g", "ironMgPer100g", "magnesiumMgPer100g", "vitaminCMgPer100g",
    "folateMcgPer100g", "vitaminAMcgPer100g", "vitaminDMcgPer100g", "vitaminEMgPer100g", "vitaminKMcgPer100g",
    "carotenoidsMcgPer100g", "mufaMgPer100g", "pufaMgPer100g",
}
NUTRIENT_COLUMNS = sorted(c for c in OVERRIDABLE_COLUMNS if c.endswith("Per100g"))
NUMBER_COLUMNS = set(NUTRIENT_COLUMNS) | {"servingQuantity", "servingWeightG"}
REQUIRED_COLUMNS = ("caloriesKcalPer100g", "proteinGPer100g", "carbohydratesGPer100g", "fatGPer100g")
GRAM_COLUMNS = ("proteinGPer100g", "carbohydratesGPer100g", "fatGPer100g", "fiberGPer100g", "sugarsGPer100g",
                "saturatedFatGPer100g", "transFatGPer100g")

# Checks a data_exceptions.csv row may cover, one per food. Every other problem stops the build.
EXCEPTION_CHECKS = ("name_text", "serving_text", "energy_vs_macros", "macro_total", "fat_level", "sodium_level", "portion_weight", "cooked_dish_water")
EXCEPTION_STATUSES = ("fix", "accepted")

# INDB dishes that are griddled or boiled in water, and the least water one holds as eaten
COOKED_DISH = re.compile(r"\b(dosa|cheela|chilla|uttapam|pesarattu|upma|daliya|poha|dal|rice)\b", re.IGNORECASE)
COOKED_DISH_MIN_WATER_G = 45.0
ALIAS_KINDS = ("spelling", "hindi", "regional", "english", "other")
PRIORITIES = ("1", "2", "3")

NAME_TEXT = re.compile(r"^[\x21-\x7e]+( [\x21-\x7e]+)*$")  # printable ASCII words, single spaces
# Dropped from a word rather than splitting it: apostrophes, and the zero-width joiners Indic keyboards type
JOINED_INSIDE_WORDS = {ord(c): None for c in "'\u2019\u200c\u200d"}

# Left out of the content fingerprint: they change on every build or say nothing about the foods.
UNFINGERPRINTED_METADATA = ("build_timestamp", "content_fingerprint", "pipeline_version")


class CatalogError(Exception):
    """A problem that stops the build; the message lists every one found."""

    def __init__(self, problems):
        self.problems = problems
        super().__init__("\n".join(f"  - {p}" for p in problems))


def number(value):
    """A CSV cell as a float, or None when it is blank. Raises ValueError for anything else."""
    s = (value or "").strip()
    if not s or s.lower() in ("null", "nan"):
        return None
    return float(s)


def format_number(value):
    """A computed value as a CSV cell: at most 4 decimals, no trailing zeros."""
    return ("%.4f" % value).rstrip("0").rstrip(".")


def normalize_name(name):
    """How normalized_name is stored, as LocalFoodDatabaseProvider.foldForNormalizedName folds a
    query: lower case without accents on Latin letters, runs of any whitespace as one space."""
    kept, after_latin = [], False
    for ch in unicodedata.normalize("NFD", name.lower()):
        if unicodedata.category(ch) == "Mn":
            if not after_latin:
                kept.append(ch)
            continue
        kept.append(ch)
        after_latin = "LATIN" in unicodedata.name(ch, "")
    return " ".join(unicodedata.normalize("NFC", "".join(kept)).split())


def search_words(text):
    """The words search matches, as LocalFoodDatabaseProvider.searchWords splits a query: folded like
    normalize_name, apostrophes and zero-width joiners dropped ("lady's" is "ladys"), then split at
    anything that is not a letter, a combining mark or a digit."""
    words, word = [], []
    for ch in normalize_name(text).translate(JOINED_INSIDE_WORDS):
        if unicodedata.category(ch)[0] in "LMN":
            word.append(ch)
        elif word:
            words.append("".join(word))
            word = []
    if word:
        words.append("".join(word))
    return words


def has_hidden_characters(text):
    """Control or formatting characters, or any space other than an ordinary one."""
    return any(unicodedata.category(ch) in ("Cc", "Cf", "Zl", "Zp") or (unicodedata.category(ch) == "Zs" and ch != " ")
               for ch in text)


def printable(text):
    """[text] with anything outside printable ASCII spelled out, for messages and exception values."""
    return ascii(text)[1:-1]


def read_csv(path):
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def file_sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def read_curation(name, directory=CATALOG_DIR):
    """A curation file. Lines starting with # are comments; an Excel byte-order mark is ignored."""
    path = os.path.join(directory, name)
    with open(path, encoding="utf-8-sig", newline="") as f:
        lines = [line for line in f if not line.lstrip().startswith("#")]
    rows, problems = [], []
    for line_no, row in enumerate(csv.DictReader(lines), start=2):
        if None in row:
            problems.append(f"{name}: row {line_no} has more cells than the header (quote a cell that has a comma)")
            continue
        rows.append({k: (v or "").strip() for k, v in row.items()})
    if problems:
        raise CatalogError(problems)
    return rows


def load_curation(directory=CATALOG_DIR):
    return {
        "sources": read_curation("sources.csv", directory),
        "exclusions": read_curation("exclusions.csv", directory),
        "overrides": read_curation("overrides.csv", directory),
        "aliases": read_curation("aliases.csv", directory),
        "exceptions": read_curation("data_exceptions.csv", directory),
        "synonyms": read_curation("synonyms.csv", directory),
        "common_foods": read_curation("common_foods.csv", directory),
        "corrections": read_curation("corrections.csv", directory),
        "recipes": read_curation("recipes.csv", directory),
        "recipe_ingredients": read_curation("recipe_ingredients.csv", directory),
    }


def extract_aliases_from_name(name):
    """Search names inside a food name: "Almond biscuit (Badam ke biscuit)" gives "Badam ke biscuit"."""
    aliases = []
    for m in re.findall(r'\((.*?)\)', name):
        for p in re.split(r'[/,;]', m):
            clean = p.strip()
            if clean and len(clean) > 1 and clean.lower() != name.lower():
                aliases.append(clean)
    # "Chapati/Roti" gives "Chapati" and "Roti"
    if '/' in name and '(' not in name:
        for p in name.split('/'):
            clean = p.strip()
            if clean and len(clean) > 1:
                aliases.append(clean)
    return list(dict.fromkeys(aliases))


def read_imported_rows(sources, workspace=WORKSPACE_DIR):
    """The rows of every imported file in sources.csv, each file once, in the order first listed."""
    rows, seen = [], set()
    for s in sources:
        imported = s.get("imported_file", "")
        if imported and imported not in seen:
            seen.add(imported)
            rows += read_csv(os.path.join(workspace, imported))
    return rows


def check_sources(rows, sources, workspace=WORKSPACE_DIR):
    """Every food's source is registered, and every imported file is the one that was pinned."""
    problems = []
    registered = {s["source"] for s in sources}
    for r in rows:
        if r["source"] not in registered:
            problems.append(f"{r['sourceId']}: source '{r['source']}' is not in sources.csv")
    for s in sources:
        if s.get("carbohydrate", "total") not in CARBOHYDRATE_RULES:
            problems.append(f"sources.csv: {s['source']} carbohydrate must be total or available")
    for s in sources:
        imported = s.get("imported_file", "")
        if not imported:
            continue
        path = os.path.join(workspace, imported)
        if not os.path.isfile(path):
            problems.append(f"sources.csv: {imported} does not exist")
        elif file_sha256(path) != s.get("imported_sha256", ""):
            problems.append(f"sources.csv: {imported} changed since it was imported. Put corrections in overrides.csv; "
                            f"after a real re-import, set imported_sha256 to {file_sha256(path)}")
    return problems


def apply_curation(rows, curation):
    """The foods to ship: imported rows minus exclusions, with overrides and aliases applied.
    Each food also gets the serving ids it ships with (see serving_slots)."""
    problems = []
    by_source = {}
    for r in rows:
        if r["sourceId"] in by_source:
            problems.append(f"{r['sourceId']}: sourceId is used by more than one imported row")
        by_source[r["sourceId"]] = r
    # Reference rows supply values to corrections and never ship as foods
    references = {sid: r for sid, r in by_source.items() if (r.get("role") or "").strip() == "reference"}
    rows = [r for r in rows if r["sourceId"] not in references]
    by_source = {sid: r for sid, r in by_source.items() if sid not in references}

    excluded = set()
    for e in curation["exclusions"]:
        sid = e.get("sourceId", "")
        if sid not in by_source:
            problems.append(f"exclusions.csv: {sid or '(blank)'} is not a catalog food")
        elif sid in excluded:
            problems.append(f"exclusions.csv: {sid} is listed twice")
        elif not e.get("reason"):
            problems.append(f"exclusions.csv: {sid} has no reason")
        excluded.add(sid)

    slots = serving_slots(rows)
    kept = [dict(r, _serving_ids=slots[r["sourceId"]]) for r in rows if r["sourceId"] not in excluded]
    kept_by_source = {r["sourceId"]: r for r in kept}

    rules = {s["source"]: s.get("carbohydrate", "total") for s in curation["sources"]}
    recipe_lines = curation.get("recipe_ingredients", [])
    problems += apply_corrections(kept_by_source, excluded, references, rules, curation["corrections"],
                                  also_used={line.get("ingredient", "") for line in recipe_lines})

    seen_overrides = set()
    for o in curation["overrides"]:
        sid, column = o.get("sourceId", ""), o.get("column", "")
        where = f"overrides.csv: {sid} {column}"
        food = kept_by_source.get(sid)
        if food is None:
            problems.append(f"{where}: {'excluded' if sid in excluded else 'not a catalog food'}")
            continue
        if column not in OVERRIDABLE_COLUMNS:
            problems.append(f"{where}: column cannot be overridden")
            continue
        if (sid, column) in seen_overrides:
            problems.append(f"{where}: listed twice")
            continue
        seen_overrides.add((sid, column))
        if not o.get("reason") or not o.get("reference"):
            problems.append(f"{where}: needs a reason and a reference")
        value = o.get("value", "")
        if column in NUMBER_COLUMNS:
            try:
                same = number(value) == number(food[column])
            except ValueError:
                problems.append(f"{where}: '{value}' is not a number")
                continue
        else:
            same = value == food[column]
        if same:
            problems.append(f"{where}: already {value or 'blank'}, remove the override")
        food[column] = value
        if column == "name":
            food["normalizedName"] = normalize_name(value)

    # The app reads carbohydrate as total carbohydrate, fibre included (net carbs = carbs - fibre).
    # INDB and CoFID give available carbohydrate, without fibre, so fibre is added back. Overrides
    # are written in the source's own terms and converted with it.
    available = {s["source"] for s in curation["sources"] if s.get("carbohydrate") == "available"}
    for food in kept:
        if food["source"] in available:
            try:
                carbs, fibre = number(food["carbohydratesGPer100g"]), number(food["fiberGPer100g"])
            except ValueError:
                continue  # check_foods reports the cell
            if carbs is not None and fibre is not None:
                food["carbohydratesGPer100g"] = format_number(carbs + fibre)

    # Standard recipes, worked out from their ingredients' curated values, which are final by now
    header = list(dict.fromkeys(list(rows[0].keys()) + ["portions", "role"])) if rows else []
    recipes, recipe_problems = build_recipes(curation.get("recipes", []), recipe_lines, kept_by_source, references,
                                             rules, excluded, header, slots)
    problems += recipe_problems
    for recipe in recipes:
        kept.append(recipe)
        kept_by_source[recipe["sourceId"]] = recipe

    category_names = [c[0] for c in CATEGORIES]
    for food in kept:
        food["_category"] = (food.get("category") or "").strip() or DEFAULT_CATEGORY
        if food["_category"] not in category_names:
            problems.append(f"{food['sourceId']}: category '{food['_category']}' is not one of {', '.join(category_names)}")

    # Extra portions take serving ids after every imported row's, in file order
    next_serving_id = max(i for ids in slots.values() for i in ids if i) + 1
    for food in kept:
        food["_portions"] = []
        entries = [e.strip() for e in (food.get("portions") or "").split("|") if e.strip()]
        for entry in entries:
            m = PORTION_ENTRY.match(entry)
            grams = float(m["grams"]) if m else 0.0
            if not m or not 0 < grams <= MAX_PORTION_G:
                problems.append(f"{food['sourceId']}: portion '{entry}' must read 'description=grams', 0 < grams <= {MAX_PORTION_G:g}")
            elif m["description"].strip() in [p[0] for p in food["_portions"]] + ["100 g", food["servingDescription"].strip()]:
                problems.append(f"{food['sourceId']}: portion '{m['description'].strip()}' is listed twice")
            else:
                food["_portions"].append((m["description"].strip(), grams, next_serving_id))
                next_serving_id += 1

    for food in kept:
        food["_aliases"] = extract_aliases_from_name(food["name"])
        food["_curated_aliases"] = []
        food["_alias_kinds"] = {}
    for a in curation["aliases"]:
        sid, alias = a.get("sourceId", ""), a.get("alias", "")
        food = kept_by_source.get(sid)
        if food is None:
            problems.append(f"aliases.csv: {sid} {'is excluded' if sid in excluded else 'is not a catalog food'}")
            continue
        if a.get("food") != food["name"]:
            problems.append(f"aliases.csv: {sid} is {food['name']}, not '{a.get('food')}'")
            continue
        if not alias or alias != " ".join(alias.split()) or has_hidden_characters(alias):
            problems.append(f"aliases.csv: {sid} alias '{printable(alias)}' is blank or has stray or hidden characters")
            continue
        if a.get("kind") not in ALIAS_KINDS:
            problems.append(f"aliases.csv: {sid} alias '{alias}' needs a kind: {', '.join(ALIAS_KINDS)}")
            continue
        if a.get("kind") == "other" and not a.get("note"):
            problems.append(f"aliases.csv: {sid} alias '{alias}' has kind other, so it needs a note saying why it names the food")
            continue
        # Bracket text is searchable but not a full name, so an alias repeating it is not redundant
        known = exact_names(food)
        if alias.lower() in (k.lower() for k in known):
            problems.append(f"aliases.csv: {sid} alias '{alias}' is already a name or alias of {food['name']}")
            continue
        food["_curated_aliases"].append(alias)
        food["_alias_kinds"][alias] = a["kind"]

    for food in kept:
        food["_priority"] = 0
    seen_common = set()
    for c in curation["common_foods"]:
        sid = c.get("sourceId", "")
        food = kept_by_source.get(sid)
        where = f"common_foods.csv: {sid}"
        if food is None:
            problems.append(f"{where} {'is excluded' if sid in excluded else 'is not a catalog food'}")
        elif c.get("food") != food["name"]:
            problems.append(f"{where} is {food['name']}, not '{c.get('food')}'")
        elif sid in seen_common:
            problems.append(f"{where} is listed twice")
        elif c.get("priority") not in PRIORITIES:
            problems.append(f"{where} priority must be 1, 2 or 3")
        else:
            food["_priority"] = int(c["priority"])
        seen_common.add(sid)

    problems += add_search_words(kept, curation["synonyms"], every_group_used=curation.get("every_synonym_used", True))
    if problems:
        raise CatalogError(problems)
    return kept


def apply_corrections(foods, excluded, references, carbohydrate_rules, corrections, also_used=()):
    """Applies corrections.csv to [foods] (by sourceId) in place. Returns the problems found. A
    reference row that neither a correction nor [also_used] (the recipes' ingredients) uses is a problem."""
    problems, seen, used = [], set(), set()
    # A food's frying correction runs before its values_from, which then replaces its nutrients but
    # keeps the household portion the frying correction weighed
    order = {kind: i for i, kind in enumerate(CORRECTION_KINDS)}
    for c in sorted(corrections, key=lambda c: order.get(c.get("correction", ""), -1)):
        sid, kind = c.get("sourceId", ""), c.get("correction", "")
        where = f"corrections.csv: {sid} {kind}"
        food = foods.get(sid)
        if food is None:
            problems.append(f"{where}: {'excluded' if sid in excluded else 'not a catalog food'}")
            continue
        if c.get("food") != food["name"]:
            problems.append(f"{where}: {sid} is {food['name']}, not '{c.get('food')}'")
            continue
        if kind not in CORRECTION_KINDS:
            problems.append(f"{where}: correction must be one of {', '.join(CORRECTION_KINDS)}")
            continue
        if (sid, kind) in seen:
            problems.append(f"{where}: listed twice")
            continue
        seen.add((sid, kind))
        if not c.get("reason") or not c.get("reference"):
            problems.append(f"{where}: needs a reason and a reference")
        if kind == "excess_frying_oil":
            try:
                frying_oil, kept = float(c.get("frying_oil_g", "")), float(c.get("oil_kept_g", ""))
                water = float(c["water_g"]) if c.get("water_g") else None
            except ValueError:
                problems.append(f"{where}: frying_oil_g and oil_kept_g must be numbers, and water_g a number or blank")
                continue
            problem = remove_frying_oil(food, frying_oil, kept, water)
            if problem:
                problems.append(f"{where}: {problem}")
        elif kind == "cooked_water":
            try:
                water = float(c.get("water_g", ""))
            except ValueError:
                problems.append(f"{where}: water_g must be a number")
                continue
            problem = set_cooked_water(food, water)
            if problem:
                problems.append(f"{where}: {problem}")
        else:
            ref = references.get(c.get("from", ""))
            if ref is None:
                problems.append(f"{where}: from '{c.get('from', '')}' is not a reference row of an imported file")
                continue
            used.add(ref["sourceId"])
            columns = [x.strip() for x in c.get("columns", "").split("|") if x.strip()] or NUTRIENT_COLUMNS
            unknown = [x for x in columns if x not in NUTRIENT_COLUMNS]
            if unknown:
                problems.append(f"{where}: {', '.join(unknown)} is not a nutrient column")
                continue
            for column in columns:
                food[column] = ref[column]
            # Carbohydrate in the food's own terms; apply_curation converts it with the food's source
            src, dst = carbohydrate_rules.get(ref["source"]), carbohydrate_rules.get(food["source"])
            if "carbohydratesGPer100g" in columns and src != dst:
                carbs, fibre = number(ref["carbohydratesGPer100g"]), number(ref["fiberGPer100g"])
                if carbs is not None and fibre is not None:
                    food["carbohydratesGPer100g"] = format_number(carbs - fibre if dst == "available" else carbs + fibre)
    for sid in references:
        if sid not in used and sid not in also_used:
            problems.append(f"{sid}: a reference row that no correction or recipe uses; remove it from the import")
    return problems


def estimated_water(food, carbohydrate_has_fibre=False):
    """Water in g per 100 g of [food] by difference: what protein, fat, carbohydrate, fibre and ash
    leave, with ash as salt (2.54 x sodium) plus twice the potassium, calcium and magnesium (within
    0.2 g of the measured ash for half of USDA SR Legacy's foods). With [carbohydrate_has_fibre], as
    in the catalog's own terms, the fibre is not counted twice."""
    def grams(column, per_mg=None):
        value = number(food[column]) or 0.0
        return value / 1000 * per_mg if per_mg else value
    ash = grams("sodiumMgPer100g", 2.54) + sum(grams(c, 2.0) for c in ("potassiumMgPer100g", "calciumMgPer100g", "magnesiumMgPer100g"))
    parts = ("proteinGPer100g", "fatGPer100g", "carbohydratesGPer100g") + (() if carbohydrate_has_fibre else ("fiberGPer100g",))
    dry = sum(grams(c) for c in parts)
    return max(0.0, 100 - dry - ash)


def set_cooked_water(food, water):
    """Gives [food] [water] g of water per 100 g as eaten. Returns a problem, or None.

    INDB adds up its ingredients' raw weights, so a griddled or boiled dish with no pot of frying oil
    to take out can sit at nearly the dry weight of its ingredients, without the water it was cooked
    in. 100 g of INDB's food becomes m g: every nutrient is concentrated by 100 / m, and the household
    portion becomes m% of its weight, so one dosa keeps its energy and gets its real weight.
    """
    current = estimated_water(food)
    if not current < water < 100:
        return f"the food already holds {current:.1f} g water per 100 g by difference, so it cannot take water_g {water:g}"
    mass = (100 - current) / (1 - water / 100)
    for column in NUTRIENT_COLUMNS:
        value = number(food[column])
        if value is not None:
            food[column] = format_number(value * 100 / mass)
    weight = number(food["servingWeightG"])
    if weight:
        food["servingWeightG"] = format_number(weight * mass / 100)
    return None


def remove_frying_oil(food, frying_oil, kept, water=None):
    """Takes the pot of frying oil INDB counts as eaten out of [food], but [kept] g per 100 g of the
    food as eaten. Returns a problem, or None.

    INDB adds up its ingredients' raw weights, so [frying_oil] g of every 100 g is frying oil and no
    water is lost in the pan. 100 g of INDB's food becomes m g once the oil it does not keep comes
    out and, with [water], its water is set to that many g per 100 g as eaten (taking out what
    frying dries off, or putting back recipe water INDB left out). Every nutrient that came with
    the food is concentrated by 100 / m. The fat loses the oil taken out; the oil's own fatty acids
    and vitamin E fall with the fat, and its energy (9 kcal/g, as INDB counts fat) comes out. The
    household portion, weighed the same way, becomes m% of its weight.
    """
    fat, kcal = number(food["fatGPer100g"]), number(food["caloriesKcalPer100g"])
    if fat is None or kcal is None:
        return "the food has no fat or energy to correct"
    if not 0 < frying_oil <= fat:
        return f"frying_oil_g {frying_oil:g} must be above 0 and at most the food's {fat:g} g fat"
    if not 0 <= kept < 100 or (water is not None and not 0 < water < 100 - kept):
        return "oil_kept_g must be 0 to 100 and water_g above 0 and below 100 - oil_kept_g"
    if water is None:
        mass = (100 - frying_oil) / (1 - kept / 100)
    else:
        mass = (100 - frying_oil - estimated_water(food)) / (1 - (kept + water) / 100)
    removed = frying_oil - kept * mass / 100
    if mass <= 0 or removed < 0:
        return f"oil_kept_g {kept:g} keeps more oil than the recipe fried in"
    if kcal - OIL_KCAL_PER_G * removed <= 0:
        return "taking the oil out leaves no energy; the food's values do not fit the correction"
    scale = 100 / mass
    new_fat = (fat - removed) * scale
    for column in NUTRIENT_COLUMNS:
        value = number(food[column])
        if value is None:
            continue
        if column == "fatGPer100g":
            value = new_fat
        elif column == "caloriesKcalPer100g":
            value = (value - OIL_KCAL_PER_G * removed) * scale
        elif column in OIL_COLUMNS:
            value = value * new_fat / fat
        else:
            value = value * scale
        food[column] = format_number(value)
    weight = number(food["servingWeightG"])
    if weight:
        food["servingWeightG"] = format_number(weight * mass / 100)
    return None


def build_recipes(recipes, lines, foods, references, carbohydrate_rules, excluded, header, slots):
    """Catalog rows for recipes.csv, as (rows, problems). A recipe's nutrients are its ingredients'
    (catalog foods, reference rows or recipes above it, all in the catalog's terms) added up, a
    nutrient staying blank when ingredients that do not state it are more than UNSTATED_SHARE of
    the solids, over what the batch weighs as eaten: cooked_g, or
    the weight at which it has water_g of water per 100 g, or else its ingredients' weight. pieces
    of unit give the household portion. A recipe with role ingredient only goes into other recipes.
    Recipes take serving ids after every imported row's."""
    problems, rows, made = [], [], {}
    known = {r.get("id", "") for r in recipes}
    by_recipe = {}
    for line_no, line in enumerate(lines, start=2):
        if line.get("recipe", "") not in known:
            problems.append(f"recipe_ingredients.csv: row {line_no}: '{line.get('recipe', '')}' is not in recipes.csv")
        else:
            by_recipe.setdefault(line["recipe"], []).append(line)
    next_id = max((i for ids in slots.values() for i in ids if i), default=0) + 1
    for r in recipes:
        rid = r.get("id", "")
        sid, where = f"MacroBase:{rid}", f"recipes.csv: {rid}"
        if not RECIPE_ID.match(rid) or sid in made:
            problems.append(f"{where}: id must be unique lower-case words joined by hyphens")
            continue
        if not r.get("name") or not r.get("reference"):
            problems.append(f"{where}: needs a name and a reference")
            continue
        totals, batch, dry, bad = {c: None for c in NUTRIENT_COLUMNS}, 0.0, 0.0, False
        unstated = {c: 0.0 for c in NUTRIENT_COLUMNS}  # solids of the ingredients that do not state it
        for line in by_recipe.get(rid, []):
            ingredient = line.get("ingredient", "")
            values = made.get(ingredient) or foods.get(ingredient)
            if values is None and ingredient in references:
                values = dict(references[ingredient])
                carbs, fibre = number(values["carbohydratesGPer100g"]), number(values["fiberGPer100g"])
                if carbohydrate_rules.get(values["source"]) == "available" and carbs is not None and fibre is not None:
                    values["carbohydratesGPer100g"] = format_number(carbs + fibre)
            if values is None:
                later = ingredient.startswith("MacroBase:") and ingredient[len("MacroBase:"):] in known
                state = "excluded" if ingredient in excluded else "a recipe below this one" if later else "not a catalog food"
                problems.append(f"{where}: ingredient {ingredient} is {state}")
                bad = True
                continue
            if line.get("food") != values["name"]:
                problems.append(f"{where}: ingredient {ingredient} is {values['name']}, not '{line.get('food')}'")
                bad = True
                continue
            try:
                grams = float(line.get("grams", ""))
            except ValueError:
                grams = 0.0
            if grams <= 0:
                problems.append(f"{where}: {values['name']} needs grams above 0")
                bad = True
                continue
            solids = (100 - estimated_water(values, carbohydrate_has_fibre=True)) * grams / 100
            for column in NUTRIENT_COLUMNS:
                value = number(values[column])
                if value is not None:
                    totals[column] = (totals[column] or 0.0) + value * grams / 100
                else:
                    unstated[column] += solids
            batch += grams
            dry += solids
        if not by_recipe.get(rid):
            problems.append(f"{where}: has no ingredients in recipe_ingredients.csv")
            continue
        if bad:
            continue
        try:
            cooked, water, pieces = number(r.get("cooked_g")), number(r.get("water_g")), number(r.get("pieces"))
        except ValueError:
            problems.append(f"{where}: cooked_g, water_g and pieces must be numbers or blank")
            continue
        if cooked is not None and water is not None:
            problems.append(f"{where}: give cooked_g or water_g, not both")
            continue
        if water is not None and not 0 <= water < 100:
            problems.append(f"{where}: water_g must be 0 to 100")
            continue
        mass = cooked if cooked is not None else dry / (1 - water / 100) if water is not None else batch
        if mass < dry - 0.05:
            problems.append(f"{where}: {mass:g} g as eaten is less than the {dry:.1f} g of its ingredients that is not water")
            continue
        if (pieces is None) != (not r.get("unit")) or (pieces is not None and pieces <= 0):
            problems.append(f"{where}: pieces and unit go together, pieces above 0")
            continue
        if r.get("role", "") not in ("", "ingredient"):
            problems.append(f"{where}: role must be blank or ingredient")
            continue
        digest = hashlib.md5(sid.encode()).hexdigest()
        row = {h: "" for h in header}
        row.update({
            "id": str(int(digest[:15], 16)), "uuid": f"macrobase-food-{digest}", "source": RECIPE_SOURCE, "sourceId": sid,
            "name": r["name"], "normalizedName": normalize_name(r["name"]), "category": r.get("category", ""),
            "foodType": "INDIAN_RECIPE", "nutritionBasis": "PER_100_G", "portions": r.get("portions", ""),
            "notes": r["reference"],
        })
        for column in NUTRIENT_COLUMNS:
            # Ingredients that do not state a nutrient count as having none, as the app adds up recipes, unless
            # they are more than a tenth of the solids: then the sum would mislead and the nutrient is not stated
            known = totals[column] is not None and unstated[column] <= UNSTATED_SHARE * dry
            row[column] = format_number(totals[column] * 100 / mass) if known else ""
        sugars, carbs = number(row["sugarsGPer100g"]), number(row["carbohydratesGPer100g"])
        if sugars is not None and carbs is not None and sugars > carbs:
            row["sugarsGPer100g"] = row["carbohydratesGPer100g"]  # USDA's milk states 5.05 g sugars in 4.8 g carbohydrate
        if pieces is not None:
            row.update({"servingUnit": r["unit"], "servingQuantity": "1", "servingDescription": f"1 {r['unit']}",
                        "servingWeightG": format_number(mass / pieces)})
        made[sid] = row
        if r.get("role") == "ingredient":
            continue  # only other recipes use it
        slots[sid] = (next_id, next_id + 1)
        next_id += 2
        row["_serving_ids"] = slots[sid]
        rows.append(row)
    return rows, problems


def add_search_words(foods, synonym_rows, every_group_used=True):
    """Sets each food's search columns. A food whose words include one member of a synonyms.csv group
    is also found by the other members, so "barfi" finds "Plain burfi". Returns the problems found."""
    problems, groups, owner = [], [], {}
    for row_no, row in enumerate(synonym_rows, start=1):
        where = f"synonyms.csv: row {row_no} ({row.get('words', '')})"
        members = [tuple(search_words(m)) for m in row.get("words", "").split("|")]
        repeated = next((m for m in members if m in owner), None)
        if row.get("kind") not in ALIAS_KINDS:
            problems.append(f"{where} needs a kind: {', '.join(ALIAS_KINDS)}")
        elif row.get("kind") == "other" and not row.get("note"):
            problems.append(f"{where} has kind other, so it needs a note saying why the words mean the same")
        elif len(members) < 2 or not all(members):
            problems.append(f"{where} needs two or more members separated by |")
        elif len(set(members)) != len(members):
            problems.append(f"{where} lists a member twice")
        elif repeated:
            problems.append(f"{where} repeats '{' '.join(repeated)}' from row {owner[repeated]}")
        else:
            groups.append((row_no, members))
            owner.update({m: row_no for m in members})
    # Search matches words one by one, so a phrase member adds its words separately: "patta gobhi" for
    # cabbage would make "gobhi" (cauliflower) find cabbage. A phrase may not use another group's word.
    for row_no, members in groups:
        for m in members:
            clash = next((w for w in m if len(m) > 1 and owner.get((w,), row_no) != row_no), None)
            if clash:
                problems.append(f"synonyms.csv: row {row_no} member '{' '.join(m)}' uses '{clash}', a member of row {owner[(clash,)]}")

    def contains(words, member):
        n = len(member)
        return any(tuple(words[i:i + n]) == member for i in range(len(words) - n + 1))

    used = set()
    for f in foods:
        name_words = search_words(f["name"])
        # A phrase member must sit inside the name or inside one alias, not across the two
        phrases = [name_words] + [search_words(a) for a in f["_curated_aliases"]]
        variants = []
        for row_no, members in groups:
            if any(contains(p, m) for p in phrases for m in members):
                used.add(row_no)
                for m in members:
                    variants += m
        words = [w for p in phrases for w in p]
        f["_terms"] = " " + " ".join(dict.fromkeys(words + variants)) + " "
        f["_name_terms"] = " " + " ".join(name_words) + " "
        f["_names"] = "|" + "|".join(dict.fromkeys(" ".join(search_words(n)) for n in exact_names(f) if search_words(n))) + "|"
    for row_no, members in groups:
        if every_group_used and row_no not in used:
            problems.append(f"synonyms.csv: row {row_no} ({' | '.join(' '.join(m) for m in members)}) matches no food; "
                            f"add it with the first food that needs it")
    return problems


def exact_names(food):
    """What counts as typing the food's name in full: the name, the name without its brackets, the
    parts of a slash name ("Chapati/Roti") and the curated aliases. Text in brackets is often a
    description ("Cabbage rolls (curry)"), so "curry" alone must not rank it first."""
    base = re.sub(r"\(.*", "", food["name"]).strip()
    slash_parts = food["_aliases"] if "(" not in food["name"] else []
    return [food["name"], base] + slash_parts + food["_curated_aliases"]


def serving_slots(rows):
    """Serving ids by sourceId, as (100 g id, household portion id or None).

    Every imported row takes one id for its 100 g serving and one more when it names a household
    unit, in file order, whether or not the food or its portion ships. Excluding a food or dropping
    a portion therefore never renumbers another food's servings; these are the ids the catalog has
    shipped with since 2.1.0. A portion that only an override adds gets an id after all of them.
    """
    slots, next_id = {}, 1
    for r in rows:
        hundred = next_id
        next_id += 1
        portion = None
        if r["servingUnit"].strip():
            portion = next_id
            next_id += 1
        slots[r["sourceId"]] = (hundred, portion)
    for sid, (hundred, portion) in list(slots.items()):
        if portion is None:
            slots[sid] = (hundred, next_id)
            next_id += 1
    return slots


def portion_bounds(unit):
    return PORTION_BOUNDS_G.get(unit.strip().lower(), DEFAULT_BOUNDS_G)


def check_foods(foods):
    """Hard problems (always stop the build) and exception-able findings as
    {(sourceId, check): (detail, value)}, where value is what an exception row must record."""
    hard, findings = [], {}
    seen = {"id": {}, "uuid": {}, "normalizedName": {}}

    for f in foods:
        sid = f["sourceId"]
        for key in seen:
            if f[key] in seen[key]:
                hard.append(f"{sid}: {key} '{f[key]}' repeats {seen[key][f[key]]}")
            seen[key][f[key]] = sid

        try:
            food_id = int(f["id"])
        except ValueError:
            hard.append(f"{sid}: id '{f['id']}' is not a whole number")
            food_id = 0
        if food_id <= 0 or USER_ID_RANGE[0] <= food_id < USER_ID_RANGE[1]:
            hard.append(f"{sid}: id {f['id']} is not a catalog id (custom foods and recipes use {USER_ID_RANGE[0]}..{USER_ID_RANGE[1] - 1})")
        digest = f["uuid"].rsplit("-", 1)[-1]
        if not re.fullmatch(r"[0-9a-f]{32}", digest) or int(digest[:15], 16) != food_id:
            hard.append(f"{sid}: id {f['id']} is not the first 15 hex digits of uuid {f['uuid']}")

        if not f["name"].strip():
            hard.append(f"{sid}: name is blank")
        if f["normalizedName"] != normalize_name(f["name"]):
            hard.append(f"{sid}: normalizedName '{f['normalizedName']}' does not match the name")

        values = {}
        for column in NUMBER_COLUMNS:
            try:
                values[column] = number(f[column])
            except ValueError:
                hard.append(f"{sid}: {column} '{f[column]}' is not a number")
                values[column] = None
            if values[column] is not None and values[column] < 0:
                hard.append(f"{sid}: {column} is negative")
        for column in REQUIRED_COLUMNS:
            if values[column] is None:
                hard.append(f"{sid}: {column} is blank")
        for column in GRAM_COLUMNS:
            if (values[column] or 0) > GRAM_CEILING:
                hard.append(f"{sid}: {column} is {values[column]:g} g, more than 100 g per 100 g")
        if (values["sodiumMgPer100g"] or 0) > SODIUM_CEILING_MG:
            hard.append(f"{sid}: sodium {values['sodiumMgPer100g']:g} mg per 100 g is more than pure salt has")
        quantity = values["servingQuantity"]
        if quantity is not None and quantity <= 0:
            hard.append(f"{sid}: servingQuantity must be positive")

        if not NAME_TEXT.match(f["name"]):
            findings[(sid, "name_text")] = ("name has characters other than printable ASCII and single spaces", printable(f["name"]))
        serving_text = f"{f['servingUnit']} | {f['servingDescription']}"
        if has_hidden_characters(serving_text) or "\ufffd" in serving_text:
            findings[(sid, "serving_text")] = ("serving unit or description has hidden or garbled characters", printable(serving_text))

        kcal, protein, carbs, fat = (values[c] or 0.0 for c in REQUIRED_COLUMNS)
        # carbs include fibre, which gives about 2 kcal/g rather than 4
        fibre = min(values["fiberGPer100g"] or 0.0, carbs)
        estimate = 4 * protein + 4 * (carbs - fibre) + 2 * fibre + 9 * fat
        if abs(kcal - estimate) > max(ENERGY_TOLERANCE_KCAL, ENERGY_TOLERANCE * max(kcal, estimate)):
            findings[(sid, "energy_vs_macros")] = (f"{kcal:g} kcal stated, {estimate:.0f} kcal from protein, carbs and fat",
                                                   f"{kcal:g} kcal vs {estimate:.0f}")
        if protein + carbs + fat > MACRO_TOTAL_LIMIT_G:
            findings[(sid, "macro_total")] = (f"protein, carbs and fat add up to {protein + carbs + fat:.1f} g per 100 g",
                                              f"{protein + carbs + fat:.1f} g")
        if fat > FAT_LIMIT_G:
            findings[(sid, "fat_level")] = (f"{fat:g} g fat per 100 g", f"{fat:g} g")
        sodium = values["sodiumMgPer100g"]
        if sodium is not None and sodium > SODIUM_LIMIT_MG:
            findings[(sid, "sodium_level")] = (f"{sodium:g} mg sodium per 100 g", f"{sodium:g} mg")
        # Energy fits the macros at any water content, so that check cannot see a dish left at dry weight
        if sid.startswith("INDB:") and COOKED_DISH.search(f["name"]):
            water = estimated_water(f, carbohydrate_has_fibre=True)
            if water < COOKED_DISH_MIN_WATER_G:
                findings[(sid, "cooked_dish_water")] = (f"{water:.1f} g water per 100 g by difference", f"{water:.1f} g")
        weight = values["servingWeightG"]
        if f["servingUnit"].strip() and weight:
            per_unit = weight / (quantity or 1.0)
            low, high = portion_bounds(f["servingUnit"])
            if not low <= per_unit <= high:
                unit = printable(f["servingUnit"].strip())
                findings[(sid, "portion_weight")] = (f"1 {unit} = {per_unit:g} g, outside {low:g}-{high:g} g", f"{per_unit:g} g")

    return hard, findings


def reconcile_exceptions(findings, exceptions, food_ids):
    """Every finding needs an exception row recording the same value, and every row its finding."""
    problems, listed = [], set()
    for e in exceptions:
        key = (e.get("sourceId", ""), e.get("check", ""))
        where = f"data_exceptions.csv: {key[0]} {key[1]}"
        if key[0] not in food_ids:
            problems.append(f"{where}: not a catalog food (excluded or unknown)")
        elif key[1] not in EXCEPTION_CHECKS:
            problems.append(f"{where}: unknown check, use one of {', '.join(EXCEPTION_CHECKS)}")
        elif e.get("status") not in EXCEPTION_STATUSES:
            problems.append(f"{where}: status must be fix or accepted")
        elif not e.get("note"):
            problems.append(f"{where}: needs a note (what is wrong, or why the value is right)")
        elif key in listed:
            problems.append(f"{where}: listed twice")
        elif key not in findings:
            problems.append(f"{where}: the check passes now, remove the row")
        elif e.get("value") != findings[key][1]:
            problems.append(f"{where}: the value is now {findings[key][1]}, not {e.get('value')}; check it and update the row")
        listed.add(key)
    for key, (detail, value) in sorted(findings.items()):
        if key not in listed:
            problems.append(f"{key[0]} fails {key[1]}: {detail} (value {value})")
    return problems


def write_database(foods, path, identity=None):
    """Writes the catalog to [path] and checks the result. Returns the row counts. [identity] replaces the
    database_version, dataset_name, dataset_version and supported_providers metadata (build_branded.py)."""
    con = sqlite3.connect(path)
    try:
        cur = con.cursor()
        cur.executescript(SCHEMA_SQL)
        cur.executemany("INSERT INTO categories (id, name, description, display_order) VALUES (?, ?, ?, ?)",
                        [(i, name, description, i) for i, (name, description) in enumerate(CATEGORIES, start=1)])
        category_ids = {name: i for i, (name, _) in enumerate(CATEGORIES, start=1)}
        cur.executemany("INSERT INTO nutrients (id, code, name, unit, tag, description) VALUES (?, ?, ?, ?, ?, ?)",
                        [n[:6] for n in NUTRIENTS])

        counts = {"servings": 0, "aliases": 0, "food_nutrients": 0}
        for f in foods:
            food_id = int(f["id"])
            hundred_id, portion_id = f["_serving_ids"]
            v = {c: number(f[c]) for c in NUTRIENT_COLUMNS}
            cur.execute("""
                INSERT INTO foods (
                    id, uuid, source_id, source_name, original_food_id, name, normalized_name,
                    brand, category_id, food_type, serving_basis,
                    calories, protein, carbohydrates, fat, fiber, sugar, saturated_fat, trans_fat, cholesterol,
                    sodium, potassium, calcium, iron, magnesium,
                    vitamin_a_rae, vitamin_c, vitamin_d_mcg, vitamin_e, vitamin_k, folate_b9,
                    is_active, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '100g', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          1, '2026-08-23 12:00:00', '2026-08-23 12:00:00')
            """, (
                food_id, f["uuid"], f["sourceId"], f["source"], f["sourceId"], f["name"], f["normalizedName"],
                f["brand"].strip() or None, category_ids[f["_category"]], f["foodType"].strip() or "INDIAN_RECIPE",
                v["caloriesKcalPer100g"], v["proteinGPer100g"], v["carbohydratesGPer100g"], v["fatGPer100g"],
                v["fiberGPer100g"], v["sugarsGPer100g"], v["saturatedFatGPer100g"], v["transFatGPer100g"],
                v["cholesterolMgPer100g"], v["sodiumMgPer100g"], v["potassiumMgPer100g"], v["calciumMgPer100g"],
                v["ironMgPer100g"], v["magnesiumMgPer100g"], v["vitaminAMcgPer100g"], v["vitaminCMgPer100g"],
                v["vitaminDMcgPer100g"], v["vitaminEMgPer100g"], v["vitaminKMcgPer100g"], v["folateMcgPer100g"],
            ))

            # Nutrition is per 100 g, so "100 g" is always the default and a household portion
            # ships only when its gram weight is known. LocalFoodDatabaseProvider reads 100 x 1 g
            # as one 100 g portion.
            cur.execute("INSERT INTO servings (id, food_id, description, unit_type, quantity, gram_weight, is_default, sequence) "
                        "VALUES (?, ?, '100 g', 'g', 100.0, 100.0, 1, 0)", (hundred_id, food_id))
            counts["servings"] += 1
            unit, weight = f["servingUnit"].strip(), number(f["servingWeightG"]) or 0.0
            if unit and weight > 0:
                quantity = number(f["servingQuantity"]) or 1.0
                description = f["servingDescription"].strip() or f"{quantity:g} {unit}"
                cur.execute("INSERT INTO servings (id, food_id, description, unit_type, quantity, gram_weight, is_default, sequence) "
                            "VALUES (?, ?, ?, ?, ?, ?, 0, 1)", (portion_id, food_id, description, unit, quantity, weight))
                counts["servings"] += 1
            for sequence, (description, grams, serving_id) in enumerate(f["_portions"], start=2):
                # "1 cup, chopped" and "4 oz (113 g)" are a cup and ounces: the unit is the word after the count
                words = description.replace(",", " ").split()
                unit_word = words[1] if len(words) > 1 else "portion"
                cur.execute("INSERT INTO servings (id, food_id, description, unit_type, quantity, gram_weight, is_default, sequence) "
                            "VALUES (?, ?, ?, ?, 1.0, ?, 0, ?)", (serving_id, food_id, description, unit_word, grams, sequence))
                counts["servings"] += 1

            for nutrient_id, *_, column in NUTRIENTS:
                if v[column] is not None:
                    cur.execute("INSERT INTO food_nutrients (food_id, nutrient_id, amount) VALUES (?, ?, ?)",
                                (food_id, nutrient_id, v[column]))
                    counts["food_nutrients"] += 1

            aliases = [(a, "vernacular_synonym") for a in f["_aliases"]] + [(a, f["_alias_kinds"][a]) for a in f["_curated_aliases"]]
            cur.executemany("INSERT INTO food_aliases (food_id, alias, alias_type) VALUES (?, ?, ?)",
                            [(food_id, a, kind) for a, kind in aliases])
            counts["aliases"] += len(aliases)
            cur.execute("INSERT INTO food_search (food_id, terms, name_terms, names, priority) VALUES (?, ?, ?, ?, ?)",
                        (food_id, f["_terms"], f["_name_terms"], f["_names"], f["_priority"]))

        ident = {"database_version": DATABASE_VERSION, "dataset_name": DATASET_NAME, "dataset_version": DATASET_VERSION,
                 "supported_providers": "INDIAN_CANONICAL,CUSTOM", **(identity or {})}
        metadata = [
            ("database_version", ident["database_version"]),
            ("schema_version", SCHEMA_VERSION),
            ("pipeline_version", PIPELINE_VERSION),
            ("dataset_name", ident["dataset_name"]),
            ("dataset_version", ident["dataset_version"]),
            ("build_timestamp", datetime.now(timezone.utc).isoformat()),
            ("total_foods", str(len(foods))),
            ("total_servings", str(counts["servings"])),
            ("total_categories", str(len(CATEGORIES))),
            ("total_aliases", str(counts["aliases"])),
            ("total_food_nutrients", str(counts["food_nutrients"])),
            ("supported_providers", ident["supported_providers"]),
            ("search_index", "food_search: word prefixes of names, aliases and spelling variants"),
        ]
        cur.executemany("INSERT INTO database_metadata (key, value) VALUES (?, ?)", metadata)
        con.commit()

        problems = []
        if cur.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            problems.append("integrity_check failed")
        if cur.execute("PRAGMA foreign_key_check").fetchall():
            problems.append("foreign_key_check failed")
        if cur.execute("SELECT count(*) FROM food_search").fetchone()[0] != len(foods):
            problems.append("food_search does not have one row per food")
        if cur.execute("""SELECT count(*) FROM foods f WHERE (SELECT count(*) FROM servings s
                          WHERE s.food_id = f.id AND s.is_default = 1 AND s.description = '100 g' AND s.gram_weight = 100.0) != 1""").fetchone()[0]:
            problems.append("a food does not have exactly one default 100 g serving")
        if problems:
            raise CatalogError(problems)
        fingerprint = content_fingerprint(con)
        cur.execute("INSERT INTO database_metadata (key, value) VALUES ('content_fingerprint', ?)", (fingerprint,))
        con.commit()
        con.execute("VACUUM")
        counts["fingerprint"] = fingerprint
        return counts
    finally:
        con.close()


def content_fingerprint(con):
    """sha256 of everything a phone reads from the catalog: the layout and rows of every table, except
    the build time and the fingerprint itself. Works on any catalog version, whatever its tables."""
    digest = hashlib.sha256()
    entries = con.execute("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND sql IS NOT NULL "
                          "ORDER BY type, name").fetchall()
    virtual = [name for kind, name, sql in entries if kind == "table" and sql.upper().startswith("CREATE VIRTUAL TABLE")]
    for kind, name, sql in entries:
        if any(name.startswith(v + "_") for v in virtual):
            continue  # a full-text table's own storage, already covered by the table's rows
        # Indexes, views and triggers count too: a new index is a change phones must get
        digest.update(f"{kind} {' '.join(sql.split())}".encode())
        if kind != "table":
            continue
        columns = ", ".join(f'"{row[1]}"' for row in con.execute(f'PRAGMA table_info("{name}")'))
        where, args = "", ()
        if name == "database_metadata":
            where, args = " WHERE key NOT IN (%s)" % ",".join("?" * len(UNFINGERPRINTED_METADATA)), UNFINGERPRINTED_METADATA
        for row in con.execute(f'SELECT * FROM "{name}"{where} ORDER BY {columns}', args):
            digest.update(repr(row).encode())
    return digest.hexdigest()


def database_fingerprint(path):
    if not os.path.isfile(path):
        return None
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        return content_fingerprint(con)
    finally:
        con.close()


def read_fingerprint_file(path=FINGERPRINT_FILE):
    if not os.path.isfile(path):
        return None
    with open(path, encoding="ascii") as f:
        return f.read().strip()


def read_versions(path=VERSIONS_CSV):
    with open(path, encoding="utf-8-sig", newline="") as f:
        return {r["database_version"]: r["content_fingerprint"] for r in csv.DictReader(line for line in f if not line.startswith("#"))}


def check_version(fingerprint, versions, version=DATABASE_VERSION):
    """Problems when the content changed but the version did not. None means the version is new."""
    pinned = versions.get(version)
    if pinned is None:
        return None
    if pinned != fingerprint:
        return [f"The catalog content changed but DATABASE_VERSION is still {version}, so installed phones would keep "
                f"the old catalog. Bump DATABASE_VERSION here and EXPECTED_DATABASE_VERSION in BuiltInDatabaseManager."]
    return []


def build(rows, curation, target=None, workspace=WORKSPACE_DIR):
    """Curates and checks [rows]. With [target], also writes and checks the database there and
    returns its fingerprint. Raises CatalogError listing every problem."""
    problems = check_sources(rows, curation["sources"], workspace)
    if problems:
        raise CatalogError(problems)
    foods = apply_curation(rows, curation)
    hard, findings = check_foods(foods)
    problems = hard + reconcile_exceptions(findings, curation["exceptions"], {f["sourceId"] for f in foods})
    if problems:
        raise CatalogError(problems)
    summary = {
        "foods": len(foods),
        "excluded": len({e.get("sourceId") for e in curation["exclusions"]}),
        "corrected": len({c.get("sourceId") for c in curation["corrections"]}),
        "recipes": sum(1 for f in foods if f["source"] == RECIPE_SOURCE),
        "to_fix": sum(1 for e in curation["exceptions"] if e["status"] == "fix"),
        "accepted": sum(1 for e in curation["exceptions"] if e["status"] == "accepted"),
    }
    if target:
        summary.update(write_database(foods, target))
    return summary


def main():
    parser = argparse.ArgumentParser(description="Check and build the built-in food catalog.")
    parser.add_argument("--check", action="store_true", help="check, and that the committed catalog is current; write nothing")
    args = parser.parse_args()

    workdir = tempfile.mkdtemp(prefix="macrobase_catalog_")
    staged = TARGET_DB + ".tmp"
    try:
        curation = load_curation()
        summary = build(read_imported_rows(curation["sources"]), curation, os.path.join(workdir, "built_in_foods.db"))
        fingerprint = summary["fingerprint"]
        version_problems = check_version(fingerprint, read_versions())
        if version_problems:
            raise CatalogError(version_problems)
        current = database_fingerprint(TARGET_DB) == fingerprint
        bundled = read_fingerprint_file() == fingerprint
        if args.check:
            problems = []
            if version_problems is None:
                problems.append(f"DATABASE_VERSION {DATABASE_VERSION} is not in catalog_versions.csv; run build_catalog.py to pin it")
            if not current:
                problems.append(f"{os.path.relpath(TARGET_DB, WORKSPACE_DIR)} is not what the build produces; run build_catalog.py")
            if not bundled:
                problems.append(f"{os.path.relpath(FINGERPRINT_FILE, WORKSPACE_DIR)} does not hold the catalog's fingerprint; run build_catalog.py")
            if problems:
                raise CatalogError(problems)
            status = "The committed catalog is current."
        elif current and bundled:
            status = "The committed catalog is already current; nothing written."
        else:
            if not current:
                # Copied next to the target first, so the replace itself cannot leave a half-written catalog
                shutil.copyfile(os.path.join(workdir, "built_in_foods.db"), staged)
                os.replace(staged, TARGET_DB)
            with open(FINGERPRINT_FILE, "w", encoding="ascii", newline="\n") as f:
                f.write(fingerprint + "\n")
            status = f"Wrote {TARGET_DB} and its fingerprint"
            if version_problems is None:
                with open(VERSIONS_CSV, "a", encoding="utf-8", newline="") as f:
                    csv.writer(f, lineterminator="\n").writerow((DATABASE_VERSION, fingerprint, date.today().isoformat()))
                status += f"\nPinned catalog version {DATABASE_VERSION} in catalog_versions.csv"
    except CatalogError as e:
        print(f"Catalog check failed, {len(e.problems)} problem(s):\n{e}", file=sys.stderr)
        sys.exit(1)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
        if os.path.exists(staged):
            os.remove(staged)

    print(f"Foods: {summary['foods']} ({summary['excluded']} excluded, {summary['corrected']} corrected, "
          f"{summary['recipes']} standard recipes)")
    print(f"Known data problems still to fix: {summary['to_fix']}, accepted exceptions: {summary['accepted']}")
    print(f"Servings: {summary['servings']}, aliases: {summary['aliases']}, food nutrients: {summary['food_nutrients']}")
    print(status)


if __name__ == "__main__":
    main()
