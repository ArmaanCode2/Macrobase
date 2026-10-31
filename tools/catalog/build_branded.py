#!/usr/bin/env python3
"""
Builds app/src/main/assets/databases/branded_foods.db, the app's second, read-only catalog: branded
products sold in India from Open Food Facts (branded/branded_foods.csv, written by
import_branded_foods.py). It is a separate file because Open Food Facts is under the Open Database
Licence: this database is too, and the main catalog stays out of it.

Usage:
    python tools/catalog/build_branded.py            check, and write the database if it changed
    python tools/catalog/build_branded.py --check    check, and that the committed database is current

The tables are the main catalog's (build_catalog.SCHEMA_SQL) and every food passes the same checks,
except that a label's fat or sodium may be above the main catalog's limits. Standard library only.
"""

import argparse
import csv
import os
import shutil
import sqlite3
import sys
import tempfile
from datetime import date

import build_catalog as catalog

BRANDED_DIR = os.path.join(catalog.CATALOG_DIR, "branded")
TARGET_DB = os.path.join(os.path.dirname(catalog.TARGET_DB), "branded_foods.db")
FINGERPRINT_FILE = os.path.join(os.path.dirname(catalog.TARGET_DB), "branded_foods.fingerprint")
VERSIONS_CSV = os.path.join(BRANDED_DIR, "catalog_versions.csv")
# Must equal BuiltInDatabaseManager's branded catalog version and dataset name, or the app refuses the file
DATABASE_VERSION = "1.0.0"
IDENTITY = {"database_version": DATABASE_VERSION, "dataset_name": "macrobase_branded_foods_off",
            "dataset_version": "2026-10", "supported_providers": "OPEN_FOOD_FACTS"}
# Checks a label must pass; fat_level and sodium_level are left to the label (ghee, pickles)
REQUIRED_CHECKS = {"name_text", "serving_text", "energy_vs_macros", "macro_total", "portion_weight"}


def popularity(scans):
    """How commonly a product is eaten, from how often it was scanned (the snapshot's most scanned has 14 scans):
    search ranks it by this."""
    return 2 if scans >= 5 else 1 if scans >= 2 else 0


def curation(rows):
    return {
        "sources": catalog.read_curation("sources.csv", BRANDED_DIR),
        "exclusions": catalog.read_curation("exclusions.csv", BRANDED_DIR),
        "overrides": [], "aliases": [], "exceptions": [], "corrections": [], "recipes": [], "recipe_ingredients": [],
        # The main catalog's spellings, so "biskut" finds what "biscuit" does; not every group has a product
        "synonyms": catalog.read_curation("synonyms.csv"), "every_synonym_used": False,
        "common_foods": [{"sourceId": r["sourceId"], "food": r["name"], "priority": str(popularity(int(r["scans"] or 0)))}
                         for r in rows if popularity(int(r["scans"] or 0))],
    }


def main_catalog_ids(path=catalog.TARGET_DB):
    if not os.path.isfile(path):
        return set()
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        return {row[0] for row in con.execute("SELECT id FROM foods")}
    finally:
        con.close()


def build(rows, cur, target=None, main_ids=frozenset(), workspace=catalog.WORKSPACE_DIR):
    problems = catalog.check_sources(rows, cur["sources"], workspace)
    if problems:
        raise catalog.CatalogError(problems)
    foods = catalog.apply_curation(rows, cur)
    hard, findings = catalog.check_foods(foods)
    problems = hard + [f"{sid} fails {check}: {detail}" for (sid, check), (detail, _) in sorted(findings.items())
                       if check in REQUIRED_CHECKS]
    problems += [f"{f['sourceId']}: id {f['id']} is also a main catalog food's id" for f in foods if int(f["id"]) in main_ids]
    if problems:
        raise catalog.CatalogError(problems)
    summary = {"foods": len(foods), "excluded": len(cur["exclusions"])}
    if target:
        summary.update(catalog.write_database(foods, target, IDENTITY))
    return summary


def main():
    parser = argparse.ArgumentParser(description="Check and build the branded food catalog.")
    parser.add_argument("--check", action="store_true", help="check, and that the committed database is current; write nothing")
    args = parser.parse_args()
    workdir = tempfile.mkdtemp(prefix="macrobase_branded_")
    staged = TARGET_DB + ".tmp"
    try:
        sources = catalog.read_curation("sources.csv", BRANDED_DIR)
        rows = catalog.read_imported_rows(sources)
        summary = build(rows, curation(rows), os.path.join(workdir, "branded_foods.db"), main_catalog_ids())
        fingerprint = summary["fingerprint"]
        version_problems = catalog.check_version(fingerprint, catalog.read_versions(VERSIONS_CSV), DATABASE_VERSION)
        if version_problems:
            raise catalog.CatalogError([f"The branded catalog's content changed but its version is still {DATABASE_VERSION}, so installed "
                                        f"phones would keep the old one. Bump DATABASE_VERSION in build_branded.py and "
                                        f"BuiltInDatabaseManager.BRANDED_DATABASE_VERSION together."])
        current = catalog.database_fingerprint(TARGET_DB) == fingerprint
        bundled = catalog.read_fingerprint_file(FINGERPRINT_FILE) == fingerprint
        if args.check:
            problems = []
            if version_problems is None:
                problems.append(f"DATABASE_VERSION {DATABASE_VERSION} is not in branded/catalog_versions.csv; run build_branded.py")
            if not current or not bundled:
                problems.append("branded_foods.db or its fingerprint is not what the build produces; run build_branded.py")
            if problems:
                raise catalog.CatalogError(problems)
            status = "The committed branded catalog is current."
        elif current and bundled:
            status = "The committed branded catalog is already current; nothing written."
        else:
            if not current:
                shutil.copyfile(os.path.join(workdir, "branded_foods.db"), staged)
                os.replace(staged, TARGET_DB)
            with open(FINGERPRINT_FILE, "w", encoding="ascii", newline="\n") as f:
                f.write(fingerprint + "\n")
            status = f"Wrote {TARGET_DB} and its fingerprint"
            if version_problems is None:
                with open(VERSIONS_CSV, "a", encoding="utf-8", newline="") as f:
                    csv.writer(f, lineterminator="\n").writerow((DATABASE_VERSION, fingerprint, date.today().isoformat()))
                status += f"\nPinned branded catalog version {DATABASE_VERSION} in branded/catalog_versions.csv"
    except catalog.CatalogError as e:
        print(f"Branded catalog check failed, {len(e.problems)} problem(s):\n{e}", file=sys.stderr)
        sys.exit(1)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
        if os.path.exists(staged):
            os.remove(staged)
    print(f"Branded foods: {summary['foods']} ({summary['excluded']} excluded)")
    print(f"Servings: {summary['servings']}, food nutrients: {summary['food_nutrients']}, "
          f"{os.path.getsize(TARGET_DB) / 1e6:.1f} MB")
    print(status)


if __name__ == "__main__":
    main()
