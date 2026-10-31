"""Tests for import_branded_foods.py and build_branded.py. Run with: python -m unittest discover tools/catalog"""

import os
import tempfile
import unittest

import build_branded
import build_catalog as b
import import_branded_foods as imp

HEADER = list(b.read_csv(b.FOODS_CSV)[0].keys()) + ["portions", "role", "scans"]


def product(code="8901719134845", name="Parle-G Original Gluco Biscuits", brands=("Parle",), kcal=454, protein=7, carbs=77,
            fat=13, quantity="", tags=("en:biscuits",), **nutriments):
    n = {"energy-kcal_100g": kcal, "proteins_100g": protein, "carbohydrates_100g": carbs, "fat_100g": fat}
    n.update(nutriments)
    return {"code": code, "product_name": name, "brands": list(brands), "nutriments": n, "quantity": quantity,
            "categories_tags": list(tags), "unique_scans_n": 12}


class ImportTests(unittest.TestCase):
    def test_a_labelled_indian_product_is_kept_with_its_pack(self):
        row, reason = imp.product_row(product(quantity="100 g", **{"salt_100g": 0.5}), HEADER)
        self.assertIsNone(reason)
        self.assertEqual("Parle-G Original Gluco Biscuits", row["name"])
        self.assertEqual("Parle", row["brand"])
        self.assertEqual("Packaged snacks and biscuits", row["category"])
        self.assertEqual("1 pack (100 g)", row["servingDescription"])
        self.assertAlmostEqual(0.5 / 2.54 * 1000, float(row["sodiumMgPer100g"]), places=2)
        self.assertEqual("branded", row["foodType"])

    def test_the_name_starts_with_the_brand_and_loses_pack_sizes_and_prices(self):
        row, _ = imp.product_row(product(name="classic salted chips 52g 20rs", brands=("Lay's",), kcal=550, protein=7, carbs=50, fat=35,
                                         tags=("en:crisps",)), HEADER)
        self.assertEqual("Lay's Classic Salted Chips", row["name"])

    def test_products_made_outside_india_or_with_unlikely_labels_are_left_out(self):
        self.assertEqual("barcode not registered in India (GS1 India's 890)", imp.product_row(product(code="3168930007432"), HEADER)[1])
        self.assertEqual("too little energy for what it is: the label was filled in per serving",
                         imp.product_row(product(name="Cow Ghee", brands=("Britannia",), kcal=194, protein=0, carbs=0, fat=20.9, tags=("en:fats",)), HEADER)[1])
        self.assertEqual("energy does not match protein, carbohydrate and fat", imp.product_row(product(kcal=200), HEADER)[1])
        self.assertEqual("energy outside what its category has",
                         imp.product_row(product(kcal=100, protein=2, carbs=20, fat=1, tags=("en:crisps",)), HEADER)[1])
        self.assertEqual("the name is only the brand", imp.product_row(product(name="Amul", brands=("Amul",)), HEADER)[1])
        self.assertEqual("more sugars or fibre than carbohydrate, or more saturated fat than fat",
                         imp.product_row(product(**{"fiber_100g": 90}), HEADER)[1])

    def test_a_salted_snacks_zero_sodium_is_taken_as_not_filled_in_and_brackets_stay_whole(self):
        row, _ = imp.product_row(product(name="Aloo Bhujia (200 g)", brands=("Haldiram's",), kcal=569, protein=10, carbs=40, fat=40,
                                         tags=("en:salty-snacks",), **{"sodium_100g": 0}), HEADER)
        self.assertEqual("", row["sodiumMgPer100g"])
        self.assertEqual("Haldiram's Aloo Bhujia", row["name"])
        row, _ = imp.product_row(product(name="Thums Up (Coca-Cola 750ml", brands=("Coca-Cola",), kcal=42, protein=0, carbs=10.5, fat=0,
                                         tags=("en:sodas",)), HEADER)
        self.assertEqual("Coca-Cola Thums Up", row["name"])

    def test_one_product_typed_differently_is_one_duplicate(self):
        self.assertEqual(imp.duplicate_key("Maggi 2-minutes Noodles"), imp.duplicate_key("Maggi 2 Minute Noodle"))


class BuildTests(unittest.TestCase):
    def rows(self):
        row, _ = imp.product_row(product(quantity="100 g"), HEADER)
        return [row]

    def curation(self, rows):
        cur = build_branded.curation(rows)
        cur["sources"] = [{"source": imp.SOURCE, "carbohydrate": "total"}]
        return cur

    def test_a_label_may_have_more_fat_or_sodium_than_the_main_catalog_allows(self):
        row, _ = imp.product_row(product(name="Amul Ghee", brands=("Amul",), kcal=897, protein=0, carbs=0, fat=99.7, tags=("en:fats",)), HEADER)
        with tempfile.TemporaryDirectory() as tmp:
            summary = build_branded.build([row], self.curation([row]), os.path.join(tmp, "branded.db"))
        self.assertEqual(1, summary["foods"])

    def test_an_id_the_main_catalog_uses_is_refused(self):
        rows = self.rows()
        with self.assertRaises(b.CatalogError) as raised:
            build_branded.build(rows, self.curation(rows), main_ids={int(rows[0]["id"])})
        self.assertIn("is also a main catalog food's id", "\n".join(raised.exception.problems))


class CommittedBrandedCatalogTests(unittest.TestCase):
    def test_the_committed_branded_catalog_is_what_the_build_produces_and_its_version_is_pinned(self):
        with tempfile.TemporaryDirectory() as tmp:
            sources = b.read_curation("sources.csv", build_branded.BRANDED_DIR)
            rows = b.read_imported_rows(sources)
            fingerprint = build_branded.build(rows, build_branded.curation(rows), os.path.join(tmp, "branded.db"),
                                              build_branded.main_catalog_ids())["fingerprint"]
        self.assertEqual(fingerprint, b.database_fingerprint(build_branded.TARGET_DB), "run tools/catalog/build_branded.py")
        self.assertEqual(fingerprint, b.read_fingerprint_file(build_branded.FINGERPRINT_FILE), "run tools/catalog/build_branded.py")
        self.assertEqual([], b.check_version(fingerprint, b.read_versions(build_branded.VERSIONS_CSV), build_branded.DATABASE_VERSION))


if __name__ == "__main__":
    unittest.main()
