#!/usr/bin/env python3
"""
Derive gram weights for the household portions in built_in_foods.db (BUG-001).

The canonical CSV kept INDB's portion names ("1 plate", "1 bowl") but not their weights,
so the app had no way to scale per-100 g nutrition by a portion. Anuvaad INDB publishes
both per-100 g and per-unit-serving values for every recipe, so the unit weight is

    weight_g = unit_serving_energy_kcal / energy_kcal * 100

and it is cross-checked against the carbohydrate, protein and fat ratios.

INDB sometimes records a whole recipe yield as "1 bowl" or "1 cup". Portions whose derived
weight falls outside PORTION_BOUNDS_G are dropped, so those foods offer only "100 g".

Source: https://github.com/lindsayjaacks/Indian-Nutrient-Databank-INDB-
        INDB.xlsx at commit 3e77f56d843b3ad1e165a40fbf65bc546f9de989
        (sha256 65f91911de09ebdb4b80c4a3dc3ff372a35420e05444239d8df8a42e8b5b9ecf)

Usage:
    python tools/derive_indb_portion_weights.py path/to/INDB.xlsx

Updates app/src/main/assets/databases/built_in_foods.db and
macrobase_indian_foods_canonical.csv in place. Safe to re-run. Standard library only.
"""

import csv
import hashlib
import io
import os
import re
import sqlite3
import sys
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone

WORKSPACE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DB_PATH = os.path.join(WORKSPACE_DIR, "app", "src", "main", "assets", "databases", "built_in_foods.db")
CSV_PATH = os.path.join(WORKSPACE_DIR, "macrobase_indian_foods_canonical.csv")

INDB_COMMIT = "3e77f56d843b3ad1e165a40fbf65bc546f9de989"
INDB_SHA256 = "65f91911de09ebdb4b80c4a3dc3ff372a35420e05444239d8df8a42e8b5b9ecf"
DATABASE_VERSION = "2.1.0"
DATASET_VERSION = "2026-10"

# Plausible gram range for one unit of a household measure. Units not listed are
# allowed 1-1000 g. Mirrored by DatabaseIntegrationTests.builtInDatabase_portionWeightsArePlausible.
PORTION_BOUNDS_G = {
    "ml": (0.8, 1.5), "gm": (0.8, 1.5),
    "teaspoon": (2, 10), "tablespoon": (7, 30),
    "cup": (100, 400), "tea cup": (80, 300),
    "ice cream cup": (40, 300), "ice-cream cup": (40, 300), "souffle cup": (40, 300),
    "glass": (120, 500), "tall glass": (150, 600), "juice glass": (100, 400),
    "sundae glass": (100, 500), "tall stemmed glass": (100, 500),
    "bowl": (80, 600), "small bowl": (50, 400), "soup bowl": (120, 600), "curry bowl": (80, 600),
}
DEFAULT_BOUNDS_G = (1, 1000)

OLD_NOTE = "The supplied CSVs do not contain serving weights/volumes, so none were invented."
WEIGHED_NOTE = f"Serving weight derived from INDB per-unit-serving energy (INDB.xlsx @ {INDB_COMMIT[:7]})."
DROPPED_NOTE = "INDB serving weight {w:g} g is implausible for one {unit}; portion omitted, log by grams."

_NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


def read_xlsx(path):
    """Minimal .xlsx reader for the first worksheet; returns a list of dicts keyed by header."""
    def col_index(ref):
        n = 0
        for ch in re.match(r"[A-Z]+", ref).group():
            n = n * 26 + ord(ch) - 64
        return n - 1

    z = zipfile.ZipFile(path)
    shared = []
    if "xl/sharedStrings.xml" in z.namelist():
        for si in ET.fromstring(z.read("xl/sharedStrings.xml")).findall("m:si", _NS):
            shared.append("".join(t.text or "" for t in si.iter("{%s}t" % _NS["m"])))
    rows = []
    for r in ET.fromstring(z.read("xl/worksheets/sheet1.xml")).iter("{%s}row" % _NS["m"]):
        values = {}
        for c in r.findall("m:c", _NS):
            v = c.find("m:v", _NS)
            if v is None:
                continue
            values[col_index(c.get("r"))] = shared[int(v.text)] if c.get("t") == "s" else v.text
        if values:
            rows.append([values.get(i, "") for i in range(max(values) + 1)])
    header = rows[0]
    return [dict(zip(header, row + [""] * (len(header) - len(row)))) for row in rows[1:]]


def derive_weight(indb_row):
    per_100g = float(indb_row["energy_kcal"])
    per_unit = float(indb_row["unit_serving_energy_kcal"])
    weight = per_unit / per_100g * 100.0
    for macro in ("carb_g", "protein_g", "fat_g"):
        base, unit_value = float(indb_row[macro] or 0), indb_row["unit_serving_" + macro]
        if base > 0.5 and unit_value:
            ratio_weight = float(unit_value) / base * 100.0
            if abs(ratio_weight - weight) / weight > 0.02:
                raise ValueError(f"{indb_row['food_code']}: {macro} ratio disagrees with energy ratio")
    return round(weight, 1)


def is_plausible(unit, weight):
    low, high = PORTION_BOUNDS_G.get(unit.strip().lower(), DEFAULT_BOUNDS_G)
    return low <= weight <= high


def main(indb_path):
    with open(indb_path, "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    if digest != INDB_SHA256:
        sys.exit(f"INDB.xlsx sha256 {digest} does not match the pinned commit {INDB_COMMIT}")
    indb = read_xlsx(indb_path)

    con = sqlite3.connect(DB_PATH)
    cur = con.cursor()
    portions = cur.execute("""
        SELECT s.id, s.food_id, s.unit_type, f.source_id, f.name
        FROM servings s JOIN foods f ON f.id = s.food_id
        WHERE s.description != '100 g'
    """).fetchall()

    csv_updates = {}
    weighed = dropped = 0
    for serving_id, food_id, unit, source_id, name in portions:
        indb_row = indb[int(source_id.split(":")[1]) - 1]  # INDB:NNNN is the 1-based INDB row
        if indb_row["food_name"].strip().lower() != name.strip().lower():
            sys.exit(f"{source_id}: catalog name '{name}' does not match INDB '{indb_row['food_name']}'")
        weight = derive_weight(indb_row)
        if is_plausible(unit, weight):
            cur.execute("UPDATE servings SET gram_weight = ?, is_default = 0 WHERE id = ?", (weight, serving_id))
            csv_updates[source_id] = (f"{weight:g}", WEIGHED_NOTE)
            weighed += 1
        else:
            cur.execute("DELETE FROM servings WHERE id = ?", (serving_id,))
            csv_updates[source_id] = ("", DROPPED_NOTE.format(w=weight, unit=unit))
            dropped += 1

    cur.execute("UPDATE servings SET is_default = 1 WHERE description = '100 g'")
    total_servings = cur.execute("SELECT count(*) FROM servings").fetchone()[0]
    cur.executemany("UPDATE database_metadata SET value = ? WHERE key = ?", [
        (DATABASE_VERSION, "database_version"),
        (DATASET_VERSION, "dataset_version"),
        (str(total_servings), "total_servings"),
        (datetime.now(timezone.utc).isoformat(), "build_timestamp"),
    ])
    con.commit()

    checks = {
        "servings without a gram weight": "SELECT count(*) FROM servings WHERE gram_weight IS NULL OR gram_weight <= 0",
        "foods without exactly one default": """SELECT count(*) FROM foods f WHERE
            (SELECT count(*) FROM servings s WHERE s.food_id = f.id AND s.is_default = 1) != 1""",
        "defaults other than 100 g": "SELECT count(*) FROM servings WHERE is_default = 1 AND NOT (description = '100 g' AND gram_weight = 100.0)",
    }
    for label, sql in checks.items():
        count = cur.execute(sql).fetchone()[0]
        if count:
            sys.exit(f"Post-check failed, {label}: {count}")
    if cur.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
        sys.exit("Post-check failed: integrity_check")
    con.execute("VACUUM")
    con.close()

    with open(CSV_PATH, encoding="utf-8", newline="") as f:
        rows = list(csv.reader(io.StringIO(f.read(), newline="")))
    header = rows[0]
    source_col, weight_col, notes_col = header.index("sourceId"), header.index("servingWeightG"), header.index("notes")
    for row in rows[1:]:
        update = csv_updates.get(row[source_col])
        if update:
            row[weight_col] = update[0]
            row[notes_col] = row[notes_col].replace(OLD_NOTE, update[1])
    out = io.StringIO(newline="")
    csv.writer(out, lineterminator="\n").writerows(rows)
    with open(CSV_PATH, "w", encoding="utf-8", newline="") as f:
        f.write(out.getvalue())

    print(f"Portions weighed: {weighed}, dropped as implausible: {dropped}, total servings: {total_servings}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
