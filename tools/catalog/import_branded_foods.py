#!/usr/bin/env python3
"""
Imports branded products sold in India from an Open Food Facts snapshot into branded/branded_foods.csv,
which build_branded.py turns into the app's branded_foods.db. Open Food Facts is under the Open Database
Licence (ODbL 1.0), its contents under the Database Contents Licence (DbCL 1.0), so branded_foods.csv and
branded_foods.db are too, and they stay separate from the main catalog.

Usage:
    python tools/catalog/import_branded_foods.py --snapshot off_india_top10000.jsonl

The snapshot is one JSON product per line, as search.openfoodfacts.org returns them for
countries_tags:"en:india" sorted by -unique_scans_n, and must match the sha256 pinned in
branded/sources.csv. Only products that state energy, protein, carbohydrate and fat per 100 g, name a
brand, and pass the checks below are kept; for two with the same name (duplicate_key), the more scanned one.
Standard library only.
"""

import argparse
import csv
import hashlib
import io
import json
import os
import re
import sys
import unicodedata

import build_catalog as catalog

BRANDED_DIR = os.path.join(catalog.CATALOG_DIR, "branded")
SOURCES_CSV = os.path.join(BRANDED_DIR, "sources.csv")
BRANDED_CSV = os.path.join(BRANDED_DIR, "branded_foods.csv")
SOURCE = "Open Food Facts (ODbL 1.0)"

# Open Food Facts nutrients per 100 g, in grams, and the catalog column each fills (times the factor)
NUTRIMENTS = {
    "proteins_100g": ("proteinGPer100g", 1), "carbohydrates_100g": ("carbohydratesGPer100g", 1),
    "fat_100g": ("fatGPer100g", 1), "fiber_100g": ("fiberGPer100g", 1), "sugars_100g": ("sugarsGPer100g", 1),
    "saturated-fat_100g": ("saturatedFatGPer100g", 1), "trans-fat_100g": ("transFatGPer100g", 1),
    "cholesterol_100g": ("cholesterolMgPer100g", 1000), "sodium_100g": ("sodiumMgPer100g", 1000),
    "potassium_100g": ("potassiumMgPer100g", 1000), "calcium_100g": ("calciumMgPer100g", 1000),
    "iron_100g": ("ironMgPer100g", 1000), "vitamin-c_100g": ("vitaminCMgPer100g", 1000),
}
# The first group whose Open Food Facts category a product has, in this order
GROUPS = [
    ({"en:dietary-supplements", "en:bodybuilding-supplements"}, "Packaged foods"),
    ({"en:biscuits", "en:biscuits-and-crackers", "en:crackers", "en:cookies"}, "Packaged snacks and biscuits"),
    ({"en:instant-noodles", "en:noodles"}, "Packaged snacks and biscuits"),
    ({"en:breakfast-cereals", "en:breads", "en:pastas"}, "Breads and breakfast cereals"),
    ({"en:salty-snacks", "en:chips-and-fries", "en:crisps", "en:appetizers"}, "Packaged snacks and biscuits"),
    ({"en:chocolates", "en:confectioneries", "en:candies", "en:sweeteners", "en:sugars", "en:honeys"}, "Sugars and sweets"),
    ({"en:dairies", "en:milks", "en:cheeses", "en:yogurts", "en:fermented-milk-products", "en:butters"}, "Dairy"),
    ({"en:beverages", "en:beverages-and-beverages-preparations"}, "Drinks"),
    ({"en:fats", "en:vegetable-oils"}, "Oils and fats"),
    ({"en:nuts-and-their-products", "en:seeds", "en:peanut-butters"}, "Nuts and seeds"),
    ({"en:legumes-and-their-products"}, "Pulses and legumes"),
    ({"en:cereal-grains", "en:flours", "en:cereals-and-their-products"}, "Cereals and flours"),
    ({"en:snacks"}, "Packaged snacks and biscuits"),
]
DEFAULT_GROUP = "Packaged foods"
# Typed in names in place of plain ASCII
REPLACEMENTS = {"\u2019": "'", "\u2018": "'", "\u201c": '"', "\u201d": '"', "\u2013": "-", "\u2014": "-", "\u00a0": " ",
                "\u2122": "", "\u00ae": "", "\u00a9": "", "&amp;": "&"}
PACK = re.compile(r"^\s*(\d+(?:\.\d+)?)\s*(?:g|gm|gms|gram|grams)\s*$", re.I)
ENERGY_TOLERANCE = 0.20  # tighter than the main catalog's 25%: these are label values
# kcal per 100 g a product of these categories can have; outside it, the label was copied wrongly
ENERGY_RANGES = {"en:crisps": (400, 620), "en:potato-crisps": (400, 620), "en:biscuits": (350, 580),
                 "en:chocolates": (380, 620), "en:instant-noodles": (330, 520), "en:sodas": (0, 70),
                 "en:carbonated-drinks": (0, 70), "en:milks": (25, 120), "en:vegetable-oils": (800, 900),
                 "en:peanut-butters": (480, 700), "en:breakfast-cereals": (300, 480),
                 # Dates at 83 kcal and a cream cake at 84 agree with their own energy, so only these show them per serving
                 "en:dates": (200, 400), "en:cakes": (250, 550), "en:candies": (150, 600), "en:gummies": (150, 450),
                 "en:spices": (150, 650)}
# Pack sizes and prices typed into names: "750ml", "100 g", "20rs", "Rs. 10"
PACK_IN_NAME = re.compile(r"\b\d+(?:\.\d+)?\s*(?:ml|l|ltr|litre|liter|g|gm|gms|gram|grams|kg|rs|/-)(?![a-z])\.?|\brs\.?\s*\d+\b", re.I)
TRAILING_PRICE = re.compile(r"\s+\d{1,3}$")  # "Lays Salted Chips 30": the price
# The least energy per 100 g foods with these words in their name have; a label below it was filled in per
# serving, not per 100 g (a "ghee" at 194 kcal, a muesli at 59)
MINIMUM_KCAL = [
    (re.compile(r"\b(ghee|oil|vanaspati)\b"), 650),
    (re.compile(r"\b(chips|wafers|crisps|namkeen|bhujia|mixture|sev|chivda|chakli|kurkure|puffs|nachos|khakhra)\b"), 350),
    (re.compile(r"\b(biscuits?|cookies?|crackers?|rusks?)\b"), 350),
    (re.compile(r"\b(muesli|granola|oats|cornflakes|flakes)\b"), 300),
    (re.compile(r"\b(bars?|whey|protein powder|makhana|nuts?|almonds?|cashews?|peanuts?|seeds?|dry ?fruits?)\b"), 300),
    (re.compile(r"\b(noodles?|pasta|vermicelli|macaroni|flour|atta|maida|besan|suji|rava|jaggery|milk powder|dairy whitener)\b"), 300),
]
# A milk category's range does not hold for these, whatever their tags say
NOT_A_MILK = re.compile(r"\b(ghee|butter|powder|whitener|khoa|khoya|paneer|cheese)\b")
# Foods that cannot have no salt or no protein; Open Food Facts often stores a value not filled in as 0
SALTY = re.compile(r"\b(chips|wafers|crisps|namkeen|bhujia|mixture|sev|chivda|chakli|kurkure|puffs|nachos|khakhra|biscuits?|"
                   r"cookies?|crackers?|noodles?|pickle|achar|sauce|ketchup|bread|rusks?|papad|masala|salted|soup|pav|bun)\b")
HAS_PROTEIN = re.compile(r"\b(noodles?|biscuits?|cookies?|chips|bhujia|namkeen|bread|atta|flour|dal|oats|muesli|pasta|paneer|"
                         r"milk|cheese|nuts?|peanuts?|makhana)\b")
# Categories whose foods all have some sodium, for products whose names SALTY misses ("Hide & Seek", "Nissin Geki")
SALTY_TAGS = {"en:biscuits", "en:biscuits-and-crackers", "en:cakes", "en:instant-noodles", "en:salty-snacks", "en:crisps",
              "en:chips-and-fries", "en:breads", "en:cheeses", "en:milks", "en:yogurts", "en:eggs", "en:soups", "en:pickles"}
# A glucose powder at 0 g sugars left its sugars blank
GLUCOSE = re.compile(r"\b(glucose|glucon|dextrose)\b")
# The only foods with no energy, protein, carbohydrate or fat; any other product at all zeros was not filled in
ZERO_ENERGY = re.compile(r"\b(water|tea|coffee|diet|zero|salt|vinegar)\b")
ZERO_ENERGY_TAGS = {"en:waters", "en:teas", "en:coffees", "en:instant-coffees", "en:diet-beverages", "en:diet-sodas",
                    "en:salts", "en:vinegars", "en:creatine"}
# The most sodium per 100 g these foods have, in mg; above it the label holds salt typed as sodium (2.54 times as
# much) or a slip, such as a lemon soda at 5000 mg. Each is far above its kind's highest real label in the snapshot.
# Pickles, sauces, spice mixes and soup powders keep the 10 g cap.
SODIUM_CEILINGS = [
    (None, {"en:sodas", "en:carbonated-drinks"}, 500),
    (None, {"en:vegetable-oils"}, 100),
    (None, {"en:chocolates", "en:candies", "en:confectioneries"}, 1500),
    (re.compile(r"\b(flour|syrup)\b"), set(), 1000),
    (re.compile(r"\b(noodles?|namkeen|bhujia|mixture|sev|chivda)\b"), set(), 3000),
]
MANUFACTURER = re.compile(r"\b(?:pvt|ltd|limited|private|company|corporation|industries)\b", re.I)


def clean(text):
    """[text] as plain ASCII words, or None when it has anything else (another script, an emoji)."""
    text = text or ""
    for a, b in REPLACEMENTS.items():
        text = text.replace(a, b)
    text = unicodedata.normalize("NFKD", text)
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    text = " ".join(text.split()).strip(" -,.")
    if not text or any(not (32 <= ord(ch) < 127) for ch in text):
        return None
    return text


def balanced(name):
    """[name] without empty or numbered brackets, a bracket left open by the cleanup ("Thums Up (Coca-Cola"), or stray ends."""
    name = re.sub(r"\(\s*\d*\s*\)", "", name)  # "()" and "(1)"
    while name.count("(") > name.count(")"):
        name = name[:name.rfind("(")]
    while name.count(")") > name.count("("):
        name = name.replace(")", "", 1)
    return " ".join(name.split()).strip(" -,.")


def capitalised(text):
    """Words typed all in lower case start with a capital; others stay as written ("McVitie's")."""
    return " ".join(w[:1].upper() + w[1:] if w.islower() else w for w in text.split())


def duplicate_key(name):
    """Names that are one product typed differently share it: "Maggi 2-minutes Noodles", "Maggi 2 Minute Noodle"."""
    return " ".join(word.rstrip("s") for word in catalog.search_words(name))


def number(nutriments, key):
    value = nutriments.get(key)
    return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) else None


def product_row(p, header):
    """The branded_foods.csv row for an Open Food Facts product, or (None, reason)."""
    n = p.get("nutriments") or {}
    kcal = number(n, "energy-kcal_100g")
    if kcal is None and number(n, "energy-kj_100g") is not None:
        kcal = number(n, "energy-kj_100g") / 4.184
    values = {"caloriesKcalPer100g": kcal}
    for key, (column, factor) in NUTRIMENTS.items():
        v = number(n, key)
        values[column] = None if v is None else v * factor
    if values["sodiumMgPer100g"] is None and number(n, "salt_100g") is not None:
        values["sodiumMgPer100g"] = number(n, "salt_100g") / 2.54 * 1000
    required = ("caloriesKcalPer100g", "proteinGPer100g", "carbohydratesGPer100g", "fatGPer100g")
    if any(values[c] is None for c in required):
        return None, "no energy, protein, carbohydrate and fat per 100 g"
    if any(v is not None and v < 0 for v in values.values()):
        return None, "a negative value"
    protein, carbs, fat, kcal = (values[c] for c in ("proteinGPer100g", "carbohydratesGPer100g", "fatGPer100g", "caloriesKcalPer100g"))
    fibre, sugars, sat = values["fiberGPer100g"] or 0, values["sugarsGPer100g"], values["saturatedFatGPer100g"]
    if protein + carbs + fat > 101 or kcal > 900:
        return None, "more than 100 g of nutrients, or 900 kcal, per 100 g"
    if (sugars is not None and sugars > carbs + 0.5) or (sat is not None and sat > fat + 0.5) or fibre > carbs + 0.5:
        return None, "more sugars or fibre than carbohydrate, or more saturated fat than fat"
    if (values["sodiumMgPer100g"] or 0) > 10_000:
        return None, "more than 10 g sodium per 100 g"
    estimate = 4 * protein + 4 * max(carbs - fibre, 0) + 2 * fibre + 9 * fat
    if abs(kcal - estimate) > max(ENERGY_TOLERANCE * max(kcal, estimate), 20):
        return None, "energy does not match protein, carbohydrate and fat"
    tags = set(p.get("categories_tags") or [])
    named = (clean(p.get("product_name_en")) or clean(p.get("product_name")) or "").lower()
    if NOT_A_MILK.search(named):
        tags.discard("en:milks")
    if any(not lo <= kcal <= hi for tag, (lo, hi) in ENERGY_RANGES.items() if tag in tags):
        return None, "energy outside what its category has"
    if any(pattern.search(named) and kcal < least for pattern, least in MINIMUM_KCAL):
        return None, "too little energy for what it is: the label was filled in per serving"
    if protein == 0 and HAS_PROTEIN.search(named):
        return None, "no protein, which a food made of flour, pulses, milk or nuts has"
    if kcal == 0 and protein + carbs + fat == 0 and not (ZERO_ENERGY.search(named) or tags & ZERO_ENERGY_TAGS):
        return None, "no energy or nutrients, which only water, tea, coffee, diet drinks, salt and vinegar have"
    sodium = values["sodiumMgPer100g"] or 0
    if any(sodium > most and ((pattern and pattern.search(named)) or tags & kinds) for pattern, kinds, most in SODIUM_CEILINGS):
        return None, "more sodium than a food of its kind has: salt typed as sodium, or a slip"
    # A 0 the rest of the label rules out was not filled in, so it is not stated (AGENTS.md 2.4). A value is 0 as the
    # CSV stores it: a mixed nuts' saturated fat of 0.0000058 g is one
    zero = {c for c, v in values.items() if v is not None and catalog.format_number(v) == "0"}
    glucose = GLUCOSE.search(named) or "en:glucose" in tags
    if "sodiumMgPer100g" in zero and (SALTY.search(named) or tags & SALTY_TAGS):
        values["sodiumMgPer100g"] = None  # a salted snack, a biscuit, milk or an egg has some
    if "saturatedFatGPer100g" in zero and fat >= 5:
        values["saturatedFatGPer100g"] = None  # every fat is at least 6% saturated
    # 40 g of starch (flour, rice, potato) comes with fibre; sugars and glucose have none
    if "fiberGPer100g" in zero and carbs - (sugars or 0) >= 40 and not glucose:
        values["fiberGPer100g"] = None
    if "sugarsGPer100g" in zero and glucose:
        values["sugarsGPer100g"] = None
    code = str(p.get("code", "")).strip()
    if not code.startswith("890") or not code.isdigit():
        return None, "barcode not registered in India (GS1 India's 890)"
    brands = p.get("brands") or []
    if isinstance(brands, str):
        brands = brands.split(",")
    brands = [b for b in (clean(x) for x in brands) if b]
    makers_only = bool(brands) and all(MANUFACTURER.search(b) for b in brands)
    brands = [b for b in brands if not MANUFACTURER.search(b)] or brands
    name = clean(p.get("product_name_en")) or clean(p.get("product_name"))
    name = name and balanced(TRAILING_PRICE.sub("", " ".join(PACK_IN_NAME.sub(" ", name).split())))
    if not brands or not name or len(name) < 3:
        return None, "no plain brand or product name"
    # Search results show only the name, so it starts with the brand unless it names one already
    squash = lambda text: re.sub(r"[^a-z0-9]", "", text.lower())
    if any(squash(name) == squash(b) for b in brands):
        return None, "the name is only the brand"
    # "Haldiram's" is in "Haldiram Aloo Bhujia"
    brand = next((b for b in brands if squash(b) and (squash(b) in squash(name) or squash(b).rstrip("s") in squash(name))), None)
    if brand is None:
        brand = brands[0]
        if not makers_only:  # a company name ("... Beverages Pvt Ltd") is not what people call the product
            name = f"{brand} {name}"
    brand, name = capitalised(brand), capitalised(name)
    group = next((g for t, g in GROUPS if t & tags), DEFAULT_GROUP)
    sid = f"OFF:{code}"
    digest = hashlib.md5(sid.encode()).hexdigest()
    row = {h: "" for h in header}
    row.update({
        "id": str(int(digest[:15], 16)), "uuid": f"openfoodfacts-{digest}", "source": SOURCE, "sourceId": sid,
        "name": name, "normalizedName": catalog.normalize_name(name), "brand": brand, "category": group,
        "foodType": "branded", "barcode": code, "nutritionBasis": "PER_100_G",
        "notes": f"https://world.openfoodfacts.org/product/{code}",
        "scans": str(int(p.get("unique_scans_n") or 0)),
    })
    m = PACK.match(p.get("quantity") or "")
    if m and 5 <= float(m.group(1)) <= 250:
        grams = float(m.group(1))
        row.update({"servingUnit": "pack", "servingQuantity": "1", "servingDescription": f"1 pack ({grams:g} g)",
                    "servingWeightG": catalog.format_number(grams)})
    for column, value in values.items():
        row[column] = "" if value is None else catalog.format_number(value)
    return row, None


def main():
    parser = argparse.ArgumentParser(description="Import Open Food Facts branded products sold in India.")
    parser.add_argument("--snapshot", required=True)
    args = parser.parse_args()
    source = catalog.read_curation("sources.csv", BRANDED_DIR)[0]
    if catalog.file_sha256(args.snapshot) != source["sha256"]:
        sys.exit(f"{args.snapshot} is not the snapshot pinned in branded/sources.csv")

    header = list(catalog.read_csv(catalog.FOODS_CSV)[0].keys()) + ["portions", "role", "scans"]
    kept, dropped = {}, {}
    with open(args.snapshot, encoding="utf-8") as f:
        for line in f:
            p = json.loads(line)
            row, reason = product_row(p, header)
            if row is None:
                dropped[reason] = dropped.get(reason, 0) + 1
                continue
            key = duplicate_key(row["name"])
            if key in kept and int(kept[key]["scans"]) >= int(row["scans"]):
                dropped["same name as a more scanned product"] = dropped.get("same name as a more scanned product", 0) + 1
                continue
            kept[key] = row
    # One product listed under two names ("Lay's ... Cream & Onion", "... Cream And Onion Chips") has one label
    by_label = {}
    for row in sorted(kept.values(), key=lambda r: (-int(r["scans"]), r["name"])):
        label = (row["brand"].lower(),) + tuple(row[c] for c in ("caloriesKcalPer100g", "proteinGPer100g", "carbohydratesGPer100g", "fatGPer100g"))
        if label in by_label:
            dropped["the same brand and label as a more scanned product"] = dropped.get("the same brand and label as a more scanned product", 0) + 1
            continue
        by_label[label] = row
    rows = sorted(by_label.values(), key=lambda r: (-int(r["scans"]), r["name"]))

    with open(BRANDED_CSV, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=header, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    lines = open(SOURCES_CSV, encoding="utf-8", newline="").read().splitlines(keepends=True)
    comments = [l for l in lines if l.lstrip().startswith("#")]
    table = list(csv.DictReader(l for l in lines if not l.lstrip().startswith("#")))
    table[0]["imported_sha256"] = catalog.file_sha256(BRANDED_CSV)
    out = io.StringIO()
    w = csv.DictWriter(out, fieldnames=list(table[0].keys()), lineterminator="\n")
    w.writeheader()
    w.writerows(table)
    open(SOURCES_CSV, "w", encoding="utf-8", newline="").write("".join(comments) + out.getvalue())
    print(f"Kept {len(rows)} products in {BRANDED_CSV}; left out:")
    for reason, count in sorted(dropped.items(), key=lambda kv: -kv[1]):
        print(f"  {count:6}  {reason}")


if __name__ == "__main__":
    main()
