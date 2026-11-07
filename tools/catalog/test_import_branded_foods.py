"""Tests for import_branded_foods.py and build_branded.py. Run with: python -m unittest discover tools/catalog"""

import os
import sqlite3
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

    # BUG-042: per-serving labels agree with their own energy, so only the food's kind can show them
    def test_a_label_filled_in_per_serving_is_left_out_by_its_category(self):
        outside = "energy outside what its category has"
        self.assertEqual(outside, imp.product_row(product(name="Premium Arabian Dates", brands=("Nutraj",), kcal=83.5, protein=0.5,
                                                          carbs=19.6, fat=0.4, tags=("en:dates",)), HEADER)[1])
        self.assertEqual(outside, imp.product_row(product(name="Desire Strawberry Cream", brands=("Sobisco",), kcal=83.93, protein=0.85,
                                                          carbs=9.4, fat=4.77, tags=("en:cakes",)), HEADER)[1])
        self.assertEqual(outside, imp.product_row(product(name="Sour Belt", brands=("Chupa Chups",), kcal=36.5, protein=0.25, carbs=8.13,
                                                          fat=2.3, tags=("en:candies",)), HEADER)[1])
        row, reason = imp.product_row(product(name="Arabian Dates", brands=("Good Life",), kcal=312, protein=2.4, carbs=72.7, fat=0.3,
                                              tags=("en:dates",)), HEADER)
        self.assertIsNone(reason)

    def test_a_product_with_no_energy_or_nutrients_must_be_one_that_has_none(self):
        zeros = dict(kcal=0, protein=0, carbs=0, fat=0)
        self.assertEqual("no energy or nutrients, which only water, tea, coffee, diet drinks, salt and vinegar have",
                         imp.product_row(product(name="Hot & Spicy Korean Style", brands=("Bingo",), tags=(), **zeros), HEADER)[1])
        self.assertIsNotNone(imp.product_row(product(name="Baking Soda", brands=("Monaliz",), tags=("en:bicarbonates-of-soda",), **zeros), HEADER)[1])
        for name, tags in (("Mineral Water", ()), ("Black", ("en:diet-sodas",)), ("Green Tea", ("en:teas",)),
                           ("White Vinegar", ("en:vinegars",)), ("Rock Salt", ("en:salts",))):
            self.assertIsNone(imp.product_row(product(name=name, brands=("Tops",), tags=tags, **zeros), HEADER)[1], name)

    # BUG-043: one 10 g cap fits pickles; a drink at 5000 mg was salt or a slip
    def test_sodium_above_what_a_food_of_its_kind_has_is_left_out(self):
        too_salty = "more sodium than a food of its kind has: salt typed as sodium, or a slip"
        self.assertEqual(too_salty, imp.product_row(product(name="Lemon Flavoured", brands=("Campa",), kcal=46, protein=0, carbs=11.5, fat=0,
                                                            tags=("en:sodas",), **{"sodium_100g": 5.0}), HEADER)[1])
        self.assertEqual(too_salty, imp.product_row(product(name="Masala 2 Minute Noodles", brands=("Maggi",), kcal=427, protein=8,
                                                            carbs=60, fat=16, tags=(), **{"sodium_100g": 4.688}), HEADER)[1])
        row, reason = imp.product_row(product(name="Lemon Pickle", brands=("Aachi",), kcal=136.8, protein=2.7, carbs=11.7, fat=8.8,
                                              tags=("en:pickles",), **{"sodium_100g": 5.263}), HEADER)
        self.assertIsNone(reason)
        self.assertEqual("5263", row["sodiumMgPer100g"])

    # BUG-044: Open Food Facts stores a value not filled in as 0, and Indian labels need not state fibre
    def test_a_zero_the_rest_of_the_label_rules_out_is_not_stated(self):
        row, _ = imp.product_row(product(name="Wafers", brands=("Balaji",), kcal=526, protein=4.8, carbs=58.1, fat=30.5, tags=("en:crisps",),
                                         **{"saturated-fat_100g": 0, "fiber_100g": 0}), HEADER)
        self.assertEqual("", row["saturatedFatGPer100g"])
        self.assertEqual("", row["fiberGPer100g"])
        row, _ = imp.product_row(product(name="Hide & Seek", brands=("Parle",), kcal=479, protein=5.9, carbs=73.4, fat=18,
                                         tags=("en:biscuits",), **{"sodium_100g": 0}), HEADER)
        self.assertEqual("", row["sodiumMgPer100g"])
        row, _ = imp.product_row(product(name="Glucon-D", brands=("Heinz",), kcal=360, protein=0, carbs=90, fat=0, tags=("en:glucose",),
                                         **{"sugars_100g": 0, "fiber_100g": 0}), HEADER)
        self.assertEqual("", row["sugarsGPer100g"])
        self.assertEqual("0", row["fiberGPer100g"])  # glucose is not starch
        row, _ = imp.product_row(product(name="Smoked And Roasted Mix Nuts", brands=("Paperboat",), kcal=594, protein=19, carbs=26,
                                         fat=46, tags=(), **{"saturated-fat_100g": 5.8e-06}), HEADER)
        self.assertEqual("", row["saturatedFatGPer100g"])
        # Real zeros stay
        row, _ = imp.product_row(product(name="Refined Sunflower Oil", brands=("Priya",), kcal=900, protein=0, carbs=0, fat=100,
                                         tags=("en:vegetable-oils",), **{"sodium_100g": 0}), HEADER)
        self.assertEqual("0", row["sodiumMgPer100g"])
        row, _ = imp.product_row(product(name="Granulated Sugar", brands=("Parry's",), kcal=400, protein=0, carbs=99.95, fat=0,
                                         tags=("en:sugars",), **{"fiber_100g": 0, "sugars_100g": 99.95}), HEADER)
        self.assertEqual("0", row["fiberGPer100g"])

    def test_one_product_typed_differently_is_one_duplicate(self):
        self.assertEqual(imp.duplicate_key("Maggi 2-minutes Noodles"), imp.duplicate_key("Maggi 2 Minute Noodle"))


class BuildTests(unittest.TestCase):
    def rows(self):
        row, _ = imp.product_row(product(quantity="100 g"), HEADER)
        return [row]

    def curation(self, rows):
        # Synthetic rows: the shipped exclusions name products these tests do not have
        cur = build_branded.curation(rows, exclusions=[])
        cur["sources"] = [{"source": imp.SOURCE, "carbohydrate": "total"}]
        return cur

    def test_a_label_may_have_more_fat_or_sodium_than_the_main_catalog_allows(self):
        row, _ = imp.product_row(product(name="Amul Ghee", brands=("Amul",), kcal=897, protein=0, carbs=0, fat=99.7, tags=("en:fats",)), HEADER)
        with tempfile.TemporaryDirectory() as tmp:
            summary = build_branded.build([row], self.curation([row]), os.path.join(tmp, "branded.db"))
        self.assertEqual(1, summary["foods"])

    # In the main catalog "Chapati/Roti" names one food twice; a product's slash lists what it is for, so
    # "Idli/Dosa" chutney powder must not take "dosa" as its name (BUG-046)
    def test_a_slash_in_a_product_name_is_not_a_list_of_its_names(self):
        row, _ = imp.product_row(product(name="Idli/Dosa/Chilly Chutney Powder", brands=("MTR",), quantity="100 g"), HEADER)
        rows = [row]
        self.assertEqual("MTR Idli/Dosa/Chilly Chutney Powder", row["name"])
        with tempfile.TemporaryDirectory() as tmp:
            target = os.path.join(tmp, "branded.db")
            build_branded.build(rows, self.curation(rows), target)
            con = sqlite3.connect(target)
            try:
                names = con.execute("SELECT names FROM food_search").fetchone()[0]
                aliases = con.execute("SELECT alias FROM food_aliases").fetchall()
            finally:
                con.close()
        self.assertEqual("|mtr idli dosa chilly chutney powder|", names)
        self.assertEqual([], aliases)

    # A product scanned twice or more ranks as a common food; leaving one out must not stop the build
    def test_a_well_scanned_product_can_be_left_out(self):
        rows = self.rows() + [imp.product_row(product(code="8901719134852", name="Hide & Seek", quantity="100 g"), HEADER)[0]]
        exclusions = [{"sourceId": rows[1]["sourceId"], "food": rows[1]["name"], "reason": "per serving"}]
        cur = build_branded.curation(rows, exclusions)
        cur["sources"] = [{"source": imp.SOURCE, "carbohydrate": "total"}]
        self.assertEqual(1, build_branded.build(rows, cur)["foods"])

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
