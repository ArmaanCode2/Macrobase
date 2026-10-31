#!/usr/bin/env python3
"""
Imports the basic foods listed in basic_foods.csv into new_foods.csv, the catalog's second imported
file, from USDA FoodData Central (SR Legacy, Foundation Foods and FNDDS survey foods, public domain)
and the UK Composition of Foods Integrated Dataset (CoFID 2021, Open Government Licence v3.0).

Usage:
    python tools/catalog/import_basic_foods.py --usda-sr FoodData_Central_sr_legacy_food_csv_2018-04.zip \\
        --usda-foundation FoodData_Central_foundation_food_csv_2026-04-30.zip \\
        --usda-fndds FoodData_Central_survey_food_csv_2024-10-31.zip --cofid cofid.xlsx

Each download must match the sha256 pinned for its source in sources.csv. The script rewrites
new_foods.csv and that file's imported_sha256 in sources.csv; build_catalog.py then checks and
builds the catalog. Standard library only.
"""

import argparse
import csv
import hashlib
import io
import os
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

import build_catalog as catalog

MAPPING_CSV = os.path.join(catalog.CATALOG_DIR, "basic_foods.csv")
NEW_FOODS_CSV = os.path.join(catalog.CATALOG_DIR, "new_foods.csv")
NEW_FOODS_RELATIVE = "tools/catalog/new_foods.csv"

SOURCES = {
    "usda-sr": "USDA FoodData Central SR Legacy (April 2018)",
    "usda-foundation": "USDA FoodData Central Foundation Foods (April 2026)",
    "usda-fndds": "USDA FoodData Central FNDDS 2021-2023 (October 2024)",
    "cofid": "UK CoFID 2021 (McCance and Widdowson)",
}

# USDA nutrient ids for each catalog column, first one present wins. Fatty acids are grams in USDA
# and milligrams in the catalog. Energy prefers the food's specific Atwater factors (SR Legacy's
# 1008, Foundation's 2048) to the general ones (2047). Carbohydrate is by difference (1005), which
# includes fibre, as the catalog's carbohydrate does.
USDA_NUTRIENTS = {
    "caloriesKcalPer100g": (1008, 2048, 2047),
    "proteinGPer100g": (1003,),
    "fatGPer100g": (1004, 1085),
    "carbohydratesGPer100g": (1005,),
    "fiberGPer100g": (1079,),
    "sugarsGPer100g": (2000, 1063),
    "saturatedFatGPer100g": (1258,),
    "transFatGPer100g": (1257,),
    "cholesterolMgPer100g": (1253,),
    "sodiumMgPer100g": (1093,),
    "potassiumMgPer100g": (1092,),
    "calciumMgPer100g": (1087,),
    "ironMgPer100g": (1089,),
    "magnesiumMgPer100g": (1090,),
    "vitaminCMgPer100g": (1162,),
    "folateMcgPer100g": (1177,),
    "vitaminAMcgPer100g": (1106,),
    "vitaminDMcgPer100g": (1114,),
    "vitaminEMgPer100g": (1109,),
    "vitaminKMcgPer100g": (1185,),
    "mufaMgPer100g": (1292,),
    "pufaMgPer100g": (1293,),
}
GRAMS_TO_MG = {"mufaMgPer100g", "pufaMgPer100g"}
# FNDDS numbers its nutrients the SR way (208 for energy); these are the ids above
LEGACY_NUTRIENT_NUMBERS = {208: 1008, 203: 1003, 204: 1004, 205: 1005, 291: 1079, 269: 2000, 606: 1258, 605: 1257,
                           601: 1253, 307: 1093, 306: 1092, 301: 1087, 303: 1089, 304: 1090, 401: 1162, 417: 1177,
                           320: 1106, 328: 1114, 323: 1109, 430: 1185, 645: 1292, 646: 1293}

# CoFID sheet and column for each catalog column. cofid_values converts carbohydrate and sugars to
# weight, and takes NSP as the fibre where AOAC fibre was not measured.
COFID_NUTRIENTS = {
    "caloriesKcalPer100g": ("1.3 Proximates", "Energy (kcal) (kcal)"),
    "proteinGPer100g": ("1.3 Proximates", "Protein (g)"),
    "fatGPer100g": ("1.3 Proximates", "Fat (g)"),
    "carbohydratesGPer100g": ("1.3 Proximates", "Carbohydrate (g)"),
    "fiberGPer100g": ("1.3 Proximates", "AOAC fibre (g)"),
    "sugarsGPer100g": ("1.3 Proximates", "Total sugars (g)"),
    "saturatedFatGPer100g": ("1.3 Proximates", "Satd FA /100g fd (g)"),
    "transFatGPer100g": ("1.3 Proximates", "Trans FAs /100g food (g)"),
    "cholesterolMgPer100g": ("1.3 Proximates", "Cholesterol (mg)"),
    "mufaMgPer100g": ("1.3 Proximates", "Mono FA /100g food (g)"),
    "pufaMgPer100g": ("1.3 Proximates", "Poly FA /100g food (g)"),
    "sodiumMgPer100g": ("1.4 Inorganics", "Sodium (mg)"),
    "potassiumMgPer100g": ("1.4 Inorganics", "Potassium (mg)"),
    "calciumMgPer100g": ("1.4 Inorganics", "Calcium (mg)"),
    "ironMgPer100g": ("1.4 Inorganics", "Iron (mg)"),
    "magnesiumMgPer100g": ("1.4 Inorganics", "Magnesium (mg)"),
    "vitaminCMgPer100g": ("1.5 Vitamins", "Vitamin C (mg)"),
    "folateMcgPer100g": ("1.5 Vitamins", "Folate (\u00b5g)"),
    "vitaminDMcgPer100g": ("1.5 Vitamins", "Vitamin D (\u00b5g)"),
    "vitaminEMgPer100g": ("1.5 Vitamins", "Vitamin E (mg)"),
    "vitaminKMcgPer100g": ("1.5 Vitamins", "Vitamin K1 (\u00b5g)"),
}

_NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


class ImportError_(Exception):
    pass


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


# --- USDA FoodData Central -------------------------------------------------------------------

def _zip_csv(zf, suffix):
    name = next(n for n in zf.namelist() if n.endswith("/" + suffix) or n == suffix)
    return csv.DictReader(io.TextIOWrapper(zf.open(name), encoding="utf-8", newline=""))


def read_usda(zip_path, fdc_ids):
    """The foods [fdc_ids] of a FoodData Central CSV download: description, nutrients by id
    (per 100 g) and household portions as (description, grams)."""
    wanted = {str(i) for i in fdc_ids}
    zf = zipfile.ZipFile(zip_path)
    foods = {r["fdc_id"]: {"description": r["description"], "nutrients": {}, "portions": []}
             for r in _zip_csv(zf, "food.csv") if r["fdc_id"] in wanted}
    for r in _zip_csv(zf, "food_nutrient.csv"):
        if r["fdc_id"] in foods and r["amount"] not in ("", None):
            nutrient = int(r["nutrient_id"])
            foods[r["fdc_id"]]["nutrients"][LEGACY_NUTRIENT_NUMBERS.get(nutrient, nutrient)] = float(r["amount"])
    units = {r["id"]: r["name"] for r in _zip_csv(zf, "measure_unit.csv")}
    for r in _zip_csv(zf, "food_portion.csv"):
        if r["fdc_id"] not in foods or not r.get("gram_weight"):
            continue
        portion = r.get("portion_description", "")
        if not r.get("amount") and re.match(r"\d", portion):
            # FNDDS: "1 pakora" carries its own amount, and the modifier is a portion code
            description = re.sub(r"\s+", " ", portion).strip()
        else:
            amount = float(r["amount"]) if r.get("amount") else 1.0
            unit = units.get(r.get("measure_unit_id", ""), "")
            unit = "" if unit in ("undetermined", "") else unit
            words = " ".join(w for w in (unit, r.get("modifier", ""), portion) if w).strip()
            description = re.sub(r"\s+", " ", f"{amount:g} {words}").strip()
        foods[r["fdc_id"]]["portions"].append((description, float(r["gram_weight"])))
    missing = wanted - set(foods)
    if missing:
        raise ImportError_(f"{os.path.basename(zip_path)} has no food with fdc_id {', '.join(sorted(missing))}")
    return foods


def usda_values(food):
    values = {}
    for column, ids in USDA_NUTRIENTS.items():
        amount = next((food["nutrients"][i] for i in ids if i in food["nutrients"]), None)
        if amount is not None and column in GRAMS_TO_MG:
            amount *= 1000
        values[column] = amount
    return values


# --- CoFID -------------------------------------------------------------------------------------

def _read_sheet(zf, shared, index):
    def col(ref):
        n = 0
        for ch in re.match(r"[A-Z]+", ref).group():
            n = n * 26 + ord(ch) - 64
        return n - 1
    rows = []
    for r in ET.fromstring(zf.read(f"xl/worksheets/sheet{index}.xml")).iter("{%s}row" % _NS["m"]):
        vals = {}
        for c in r.findall("m:c", _NS):
            v = c.find("m:v", _NS)
            if v is not None:
                vals[col(c.get("r"))] = shared[int(v.text)] if c.get("t") == "s" else v.text
        if vals:
            rows.append([vals.get(i, "") for i in range(max(vals) + 1)])
    return rows


def read_cofid(xlsx_path):
    """CoFID foods by food code: name and the catalog columns, from the proximates, inorganics and
    vitamins sheets."""
    zf = zipfile.ZipFile(xlsx_path)
    shared = ["".join(t.text or "" for t in si.iter("{%s}t" % _NS["m"]))
              for si in ET.fromstring(zf.read("xl/sharedStrings.xml")).findall("m:si", _NS)]
    sheet_names = [s.get("name") for s in ET.fromstring(zf.read("xl/workbook.xml")).iter("{%s}sheet" % _NS["m"])]
    sheets = {}
    for name in {s for s, _ in COFID_NUTRIENTS.values()} | {"1.5 Vitamins"}:
        rows = _read_sheet(zf, shared, sheet_names.index(name) + 1)
        header = rows[0]
        sheets[name] = {r[0]: dict(zip(header, r + [""] * (len(header) - len(r)))) for r in rows[3:] if r and r[0]}
    return {code: {"description": prox["Food Name"], "values": cofid_values({name: rows.get(code, {}) for name, rows in sheets.items()})}
            for code, prox in sheets["1.3 Proximates"].items()}


def cofid_values(cells):
    """The catalog columns of one CoFID food from its cells, {sheet name: {column: cell}}. "Tr" (trace)
    is 0; "N" (not measured) and blanks are missing."""
    def cell(sheet, field):
        return cofid_number(cells.get(sheet, {}).get(field, ""))
    values = {}
    for column, (sheet, field) in COFID_NUTRIENTS.items():
        values[column] = cell(sheet, field)
        if values[column] is not None and column in GRAMS_TO_MG:
            values[column] *= 1000
    if values["fiberGPer100g"] is None:
        values["fiberGPer100g"] = cell("1.3 Proximates", "NSP (g)")
    # CoFID gives carbohydrate as monosaccharide equivalents. Its 2021 user guide converts them to
    # weight by dividing starch by 1.10 and sucrose, maltose and lactose by 1.05, or the whole by
    # 1.05 when those are not known.
    starch = cell("1.3 Proximates", "Starch (g)")
    disaccharides = [cell("1.3 Proximates", f"{name} (g)") for name in ("Sucrose", "Maltose", "Lactose")]
    known = starch is not None and None not in disaccharides
    for column in ("carbohydratesGPer100g", "sugarsGPer100g"):
        if values[column] is None:
            continue
        if known:
            values[column] -= sum(disaccharides) * (1 - 1 / 1.05)
            if column == "carbohydratesGPer100g":
                values[column] -= starch * (1 - 1 / 1.10)
        else:
            values[column] /= 1.05
    retinol, carotene = cell("1.5 Vitamins", "Retinol (\u00b5g)"), cell("1.5 Vitamins", "Carotene (\u00b5g)")
    # Vitamin A as retinol activity equivalents: retinol plus beta-carotene equivalents / 12
    values["vitaminAMcgPer100g"] = None if retinol is None or carotene is None else retinol + carotene / 12
    return values


def cofid_number(cell):
    s = (cell or "").strip().strip("()")
    if s in ("", "N"):
        return None
    if s == "Tr":
        return 0.0
    try:
        return float(s)
    except ValueError:
        return None


# --- new_foods.csv -----------------------------------------------------------------------------

def food_row(header, source_key, code, name, category, values, portions, description):
    source_id = f"{'USDA' if source_key.startswith('usda') else 'CoFID'}:{code}"
    digest = hashlib.md5(source_id.encode()).hexdigest()
    row = {h: "" for h in header}
    row.update({
        "id": str(int(digest[:15], 16)), "uuid": f"macrobase-food-{digest}", "source": SOURCES[source_key],
        "sourceId": source_id, "name": name, "normalizedName": catalog.normalize_name(name), "category": category,
        "foodType": "generic", "nutritionBasis": "PER_100_G",
        "portions": "|".join(f"{d}={catalog.format_number(g)}" for d, g in portions),
        "notes": f"{description} ({SOURCES[source_key]}, {code})",
    })
    for column, value in values.items():
        row[column] = "" if value is None else catalog.format_number(value)
    return row


def pick_portions(available, wanted, where):
    """The portions [wanted] names, separated by |. "cup, chopped" is the source portion ending in
    those words; "medium (3 in. dia)>1 medium" shows it under a shorter label; "1 cup=207" is a weight
    from elsewhere, which the mapping row's note must cite. Each source portion must match exactly one."""
    picked = []
    for want in [w.strip() for w in wanted.split("|") if w.strip()]:
        if "=" in want:
            label, grams = want.rsplit("=", 1)
            picked.append((label.strip(), float(grams)))
            continue
        selector, _, label = want.partition(">")
        selector = selector.strip()
        matches = [p for p in available if p[0].endswith(" " + selector) or p[0] == selector]
        if len(matches) != 1:
            raise ImportError_(f"{where}: portion '{selector}' matches {len(matches)} of {[p[0] for p in available]}")
        picked.append((label.strip() or matches[0][0], matches[0][1]))
    return picked


def main():
    parser = argparse.ArgumentParser(description="Import basic foods from USDA FoodData Central and CoFID.")
    parser.add_argument("--usda-sr", required=True)
    parser.add_argument("--usda-foundation", required=True)
    parser.add_argument("--usda-fndds", required=True)
    parser.add_argument("--cofid", required=True)
    args = parser.parse_args()
    downloads = {"usda-sr": args.usda_sr, "usda-foundation": args.usda_foundation, "usda-fndds": args.usda_fndds,
                 "cofid": args.cofid}

    sources_path = os.path.join(catalog.CATALOG_DIR, "sources.csv")
    sources = catalog.read_curation("sources.csv")
    pinned = {s["source"]: s.get("sha256", "") for s in sources}
    for key, path in downloads.items():
        if sha256(path) != pinned.get(SOURCES[key]):
            sys.exit(f"{path} is not the {SOURCES[key]} download pinned in sources.csv")

    mapping = catalog.read_curation("basic_foods.csv")
    wanted = {key: [m["code"] for m in mapping if m["source"] == key] for key in downloads}
    usda = {key: read_usda(downloads[key], wanted[key]) for key in ("usda-sr", "usda-foundation", "usda-fndds")}
    cofid = read_cofid(downloads["cofid"])

    header = list(catalog.read_csv(catalog.FOODS_CSV)[0].keys()) + ["portions", "role"]
    rows, problems, seen = [], [], set()
    for m in mapping:
        where = f"basic_foods.csv: {m['name']}"
        key, code = m.get("source"), m.get("code")
        if key not in SOURCES:
            problems.append(f"{where}: source must be one of {', '.join(SOURCES)}")
            continue
        if (key, code) in seen:
            problems.append(f"{where}: {key} {code} is listed twice")
            continue
        seen.add((key, code))
        try:
            if key == "cofid":
                if code not in cofid:
                    raise ImportError_(f"{where}: CoFID has no food {code}")
                food = cofid[code]
                values = food["values"]
            else:
                food = usda[key][code]
                values = usda_values(food)
            portions = pick_portions(food.get("portions", []), m.get("portions", ""), where)
            if any("=" in p for p in m.get("portions", "").split("|")) and not m.get("note"):
                raise ImportError_(f"{where}: a portion weight from elsewhere needs a note citing it")
            role = m.get("role", "") or "food"
            if role not in ("food", "reference"):
                raise ImportError_(f"{where}: role must be food or reference")
            row = food_row(header, key, code, m["name"], m["category"], values, portions, food["description"])
            row["role"] = "" if role == "food" else role
            rows.append(row)
        except ImportError_ as e:
            problems.append(str(e))
    if problems:
        sys.exit("Import failed:\n" + "\n".join(f"  - {p}" for p in problems))

    with open(NEW_FOODS_CSV, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=header, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)

    # Pin the new file for every source that imports into it
    digest = sha256(NEW_FOODS_CSV)
    with open(sources_path, encoding="utf-8", newline="") as f:
        lines = f.read().splitlines(keepends=True)
    comments = [l for l in lines if l.lstrip().startswith("#")]
    table = list(csv.DictReader(l for l in lines if not l.lstrip().startswith("#")))
    for s in table:
        if s.get("imported_file") == NEW_FOODS_RELATIVE:
            s["imported_sha256"] = digest
    out = io.StringIO()
    w = csv.DictWriter(out, fieldnames=list(table[0].keys()), lineterminator="\n")
    w.writeheader()
    w.writerows(table)
    with open(sources_path, "w", encoding="utf-8", newline="") as f:
        f.write("".join(comments) + out.getvalue())
    print(f"Wrote {len(rows)} foods to {NEW_FOODS_CSV} and pinned it in sources.csv")


if __name__ == "__main__":
    main()
