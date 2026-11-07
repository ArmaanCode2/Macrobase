"""Tests for build_catalog.py. Run with: python -m unittest discover tools/catalog"""

import hashlib
import os
import sqlite3
import tempfile
import unittest

import build_catalog as b


def food(source_id, name, kcal=100.0, protein=5.0, carbs=15.0, fat=2.0, unit="", weight="", **extra):
    digest = hashlib.md5(source_id.encode()).hexdigest()
    row = {c: "" for c in b.NUMBER_COLUMNS}
    row.update({
        "id": str(int(digest[:15], 16)), "uuid": f"indian-food-{digest}", "source": "Test source",
        "sourceId": source_id, "name": name, "normalizedName": b.normalize_name(name), "brand": "",
        "foodType": "INDIAN_RECIPE", "servingUnit": unit, "servingQuantity": "1" if unit else "",
        "servingDescription": f"1 {unit}" if unit else "", "servingWeightG": str(weight),
        "caloriesKcalPer100g": str(kcal), "proteinGPer100g": str(protein),
        "carbohydratesGPer100g": str(carbs), "fatGPer100g": str(fat),
    })
    row.update(extra)
    return row


def curation(**files):
    base = {"sources": [{"source": "Test source"}], "exclusions": [], "overrides": [], "aliases": [], "exceptions": [],
            "synonyms": [], "common_foods": [], "corrections": []}
    base.update(files)
    return base


def problems_of(rows, cur, target=None):
    with unittest.TestCase().assertRaises(b.CatalogError) as raised:
        b.build(rows, cur, target)
    return "\n".join(raised.exception.problems)


def built(rows, cur, sql):
    """Builds into a temporary database and returns the rows of [sql]."""
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "catalog.db")
        b.build(rows, cur, path)
        con = sqlite3.connect(path)
        try:
            return con.execute(sql).fetchall()
        finally:
            con.close()


class BuildTests(unittest.TestCase):
    def setUp(self):
        self.rows = [food("T:1", "Plain dosa", unit="dosa", weight=36), food("T:2", "Chapati/Roti", kcal=200, carbs=40)]

    def test_writes_one_default_100g_serving_plus_the_household_portion(self):
        servings = built(self.rows, curation(), "SELECT id, description, gram_weight, is_default FROM servings ORDER BY id")
        self.assertEqual([(1, "100 g", 100.0, 1), (2, "1 dosa", 36.0, 0), (3, "100 g", 100.0, 1)], servings)

    def test_excluding_a_food_does_not_renumber_the_other_foods_servings(self):
        exclusions = [{"sourceId": "T:1", "reason": "too specific"}]
        self.assertEqual([(3, "100 g")], built(self.rows, curation(exclusions=exclusions), "SELECT id, description FROM servings"))

    def test_the_fingerprint_follows_the_content(self):
        with tempfile.TemporaryDirectory() as tmp:
            first = b.build(self.rows, curation(), os.path.join(tmp, "a.db"))["fingerprint"]
            again = b.build(self.rows, curation(), os.path.join(tmp, "b.db"))["fingerprint"]
            changed = b.build(self.rows[:1], curation(), os.path.join(tmp, "c.db"))["fingerprint"]
            self.assertEqual(first, b.database_fingerprint(os.path.join(tmp, "a.db")))
        self.assertEqual(first, again)
        self.assertNotEqual(first, changed)

    def test_changed_content_under_a_pinned_version_is_refused(self):
        self.assertEqual([], b.check_version("abc", {"2.1.0": "abc"}, "2.1.0"))
        self.assertIn("Bump DATABASE_VERSION", b.check_version("def", {"2.1.0": "abc"}, "2.1.0")[0])
        self.assertIsNone(b.check_version("def", {"2.1.0": "abc"}, "2.2.0"))

    def test_nothing_is_written_when_a_check_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "catalog.db")
            problems_of(self.rows + [food("T:3", "Idli", fat=120)], curation(), path)
            self.assertFalse(os.path.exists(path))

    def test_an_unregistered_source_or_a_changed_imported_file_stops_the_build(self):
        self.assertIn("is not in sources.csv", problems_of(self.rows, curation(sources=[])))
        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, "foods.csv"), "w") as f:
                f.write("edited by hand")
            sources = [{"source": "Test source", "imported_file": "foods.csv", "imported_sha256": "0" * 64}]
            problems = b.check_sources(self.rows, sources, workspace=tmp)
        self.assertIn("foods.csv changed since it was imported", problems[0])

    def test_a_repeated_source_id_stops_the_build(self):
        self.assertIn("used by more than one imported row", problems_of(self.rows + [food("T:1", "Masala dosa")], curation()))

    def test_an_excluded_food_is_left_out(self):
        summary = b.build(self.rows, curation(exclusions=[{"sourceId": "T:1", "reason": "too specific"}]))
        self.assertEqual((1, 1), (summary["foods"], summary["excluded"]))

    def test_an_exclusion_needs_a_known_food_and_a_reason(self):
        problems = problems_of(self.rows, curation(exclusions=[{"sourceId": "T:9", "reason": "x"}, {"sourceId": "T:1", "reason": ""}]))
        self.assertIn("T:9 is not a catalog food", problems)
        self.assertIn("T:1 has no reason", problems)

    def test_an_override_replaces_the_value_and_a_renamed_food_is_searched_by_its_new_name(self):
        overrides = [
            {"sourceId": "T:1", "column": "fatGPer100g", "value": "3.5", "reason": "r", "reference": "ref"},
            {"sourceId": "T:1", "column": "name", "value": "Dosa, plain", "reason": "r", "reference": "ref"},
        ]
        dosa = next(f for f in b.apply_curation(self.rows, curation(overrides=overrides)) if f["sourceId"] == "T:1")
        self.assertEqual("3.5", dosa["fatGPer100g"])
        self.assertEqual("dosa, plain", dosa["normalizedName"])

    def test_an_override_that_changes_nothing_or_has_no_reference_stops_the_build(self):
        overrides = [
            {"sourceId": "T:1", "column": "fatGPer100g", "value": "2.0", "reason": "r", "reference": "ref"},
            {"sourceId": "T:2", "column": "proteinGPer100g", "value": "6", "reason": "r", "reference": ""},
            {"sourceId": "T:2", "column": "id", "value": "1", "reason": "r", "reference": "ref"},
        ]
        problems = problems_of(self.rows, curation(overrides=overrides))
        self.assertIn("already 2.0, remove the override", problems)
        self.assertIn("needs a reason and a reference", problems)
        self.assertIn("column cannot be overridden", problems)

    def test_a_curated_alias_is_searchable_and_a_duplicate_is_refused(self):
        aliases = [{"sourceId": "T:2", "food": "Chapati/Roti", "alias": "Phulka", "kind": "regional"}]
        self.assertEqual([(" chapati roti phulka ", " chapati roti ", "|chapati roti|chapati|roti|phulka|")],
                         built(self.rows, curation(aliases=aliases), "SELECT terms, name_terms, names FROM food_search WHERE food_id = "
                                                                     f"{self.rows[1]['id']}"))
        duplicate = [{"sourceId": "T:2", "food": "Chapati/Roti", "alias": "roti", "kind": "spelling"}]
        self.assertIn("already a name or alias", problems_of(self.rows, curation(aliases=duplicate)))

    def test_an_alias_names_its_food_has_a_kind_and_no_hidden_characters(self):
        problems = problems_of(self.rows, curation(aliases=[
            {"sourceId": "T:2", "food": "Chapati/Roti", "alias": "Phulka", "kind": ""},
            {"sourceId": "T:1", "food": "Plain dosa", "alias": "Do\u200bsa", "kind": "spelling"},
            {"sourceId": "T:1", "food": "Masala dosa", "alias": "Dosai", "kind": "spelling"}]))
        self.assertIn("needs a kind", problems)
        self.assertIn("stray or hidden characters", problems)
        self.assertIn("T:1 is Plain dosa, not 'Masala dosa'", problems)

    def test_a_synonym_finds_every_food_with_another_member_and_must_match_a_food(self):
        rows = self.rows + [food("T:3", "Plain burfi (Burfi)"), food("T:4", "Bottle gourd kheer (Ghiya/Lauki kheer)")]
        synonyms = [{"words": "burfi|barfi", "kind": "spelling"}, {"words": "lauki|bottle gourd", "kind": "hindi"}]
        terms = dict(built(rows, curation(synonyms=synonyms), "SELECT f.name, s.terms FROM food_search s JOIN foods f ON f.id = s.food_id"))
        self.assertEqual(" plain burfi barfi ", terms["Plain burfi (Burfi)"])
        self.assertIn(" lauki ", terms["Bottle gourd kheer (Ghiya/Lauki kheer)"])
        self.assertEqual(" plain dosa ", terms["Plain dosa"])
        unused = [{"words": "jalebi|jilebi", "kind": "spelling"}]
        self.assertIn("matches no food", problems_of(rows, curation(synonyms=unused)))

    def test_a_synonym_group_needs_two_members_a_kind_and_no_member_from_another_group(self):
        synonyms = [{"words": "dosa|dosai", "kind": "spelling"}, {"words": "dosa|dose", "kind": "spelling"},
                    {"words": "roti", "kind": "hindi"}, {"words": "chapati|phulka", "kind": ""}]
        problems = problems_of(self.rows, curation(synonyms=synonyms))
        self.assertIn("repeats 'dosa' from row 1", problems)
        self.assertIn("needs two or more members", problems)
        self.assertIn("row 4 (chapati|phulka) needs a kind", problems)

    def test_a_phrase_member_may_not_use_a_word_of_another_group(self):
        # "patta gobhi" for cabbage would make "gobhi", which means cauliflower, find cabbage
        rows = self.rows + [food("T:3", "Cabbage and peas"), food("T:4", "Aloo gobhi")]
        synonyms = [{"words": "gobhi|cauliflower", "kind": "hindi"}, {"words": "patta gobhi|cabbage", "kind": "hindi"}]
        self.assertIn("row 2 member 'patta gobhi' uses 'gobhi', a member of row 1", problems_of(rows, curation(synonyms=synonyms)))

    def test_a_phrase_member_matches_inside_the_name_or_one_alias_only(self):
        rows = [food("T:1", "Stuffed bottle")]
        aliases = [{"sourceId": "T:1", "food": "Stuffed bottle", "alias": "Gourd curry", "kind": "english"}]
        synonyms = [{"words": "lauki|bottle gourd", "kind": "hindi"}]
        # "bottle" ends the name and "gourd" starts the alias: that is not the phrase
        self.assertIn("matches no food", problems_of(rows, curation(aliases=aliases, synonyms=synonyms)))

    def test_kind_other_needs_a_note(self):
        aliases = [{"sourceId": "T:1", "food": "Plain dosa", "alias": "Dosa", "kind": "other", "note": ""}]
        self.assertIn("has kind other, so it needs a note", problems_of(self.rows, curation(aliases=aliases)))
        synonyms = [{"words": "dosa|dosai", "kind": "other", "note": ""}]
        self.assertIn("has kind other, so it needs a note", problems_of(self.rows, curation(synonyms=synonyms)))

    def test_text_in_brackets_is_not_an_exact_name_but_the_name_without_it_is(self):
        rows = [food("T:1", "Cabbage rolls (curry)"), food("T:2", "Potato curry (Aloo ki sabzi)")]
        names = dict(built(rows, curation(), "SELECT f.name, s.names FROM food_search s JOIN foods f ON f.id = s.food_id"))
        self.assertEqual("|cabbage rolls curry|cabbage rolls|", names["Cabbage rolls (curry)"])
        self.assertEqual("|potato curry aloo ki sabzi|potato curry|", names["Potato curry (Aloo ki sabzi)"])

    def test_a_curated_alias_keeps_its_kind(self):
        aliases = [{"sourceId": "T:2", "food": "Chapati/Roti", "alias": "Phulka", "kind": "regional"}]
        self.assertIn(("Phulka", "regional"), built(self.rows, curation(aliases=aliases), "SELECT alias, alias_type FROM food_aliases"))

    def test_a_common_food_ranks_by_its_priority(self):
        common = [{"sourceId": "T:2", "food": "Chapati/Roti", "priority": "3"}]
        self.assertEqual([(0,), (3,)], built(self.rows, curation(common_foods=common), "SELECT priority FROM food_search ORDER BY priority"))
        wrong = [{"sourceId": "T:2", "food": "Roti", "priority": "3"}, {"sourceId": "T:1", "food": "Plain dosa", "priority": "5"}]
        problems = problems_of(self.rows, curation(common_foods=wrong))
        self.assertIn("T:2 is Chapati/Roti, not 'Roti'", problems)
        self.assertIn("priority must be 1, 2 or 3", problems)

    def test_search_words_split_like_the_app_and_keep_indic_vowel_signs(self):
        self.assertEqual(["paneer", "makhani", "dal"], b.search_words("Paneer-Makhani (dal)"))
        self.assertEqual(["puree"], b.search_words("Pur\u00e9e"))
        # An apostrophe or a zero-width joiner joins its word instead of splitting it
        self.assertEqual(["ladys", "fingers"], b.search_words("Lady's fingers"))
        self.assertEqual(["shepherds", "pie"], b.search_words("Shepherd\u2019s pie"))
        self.assertEqual(["\u0915\u094d\u0937"], b.search_words("\u0915\u094d\u200d\u0937"))
        self.assertEqual(["\u0926\u093e\u0932", "\u091a\u093e\u0935\u0932"], b.search_words("\u0926\u093e\u0932 \u091a\u093e\u0935\u0932"))

    def test_implausible_values_need_an_exception_row_with_the_value_seen(self):
        rows = [food("T:1", "Poori", kcal=738, protein=1.4, carbs=8.2, fat=77.6, unit="poori", weight=125)]
        problems = problems_of(rows, curation())
        self.assertIn("T:1 fails fat_level: 77.6 g fat per 100 g (value 77.6 g)", problems)
        self.assertIn("T:1 fails portion_weight", problems)
        exceptions = [{"sourceId": "T:1", "check": "fat_level", "value": "77.6 g", "status": "fix", "note": "frying oil"},
                      {"sourceId": "T:1", "check": "portion_weight", "value": "125 g", "status": "fix", "note": "batch"}]
        self.assertEqual(2, b.build(rows, curation(exceptions=exceptions))["to_fix"])

    def test_an_exception_no_longer_covers_a_food_whose_value_changed(self):
        rows = [food("T:1", "Chaat masala", sodiumMgPer100g="90000")]
        exceptions = [{"sourceId": "T:1", "check": "sodium_level", "value": "11873.6 mg", "status": "accepted", "note": "salt"}]
        problems = problems_of(rows, curation(exceptions=exceptions))
        self.assertIn("more than pure salt has", problems)
        rows = [food("T:1", "Chaat masala", sodiumMgPer100g="12500")]
        self.assertIn("the value is now 12500 mg, not 11873.6 mg", problems_of(rows, curation(exceptions=exceptions)))

    def test_an_exception_whose_check_passes_or_that_has_no_note_stops_the_build(self):
        exceptions = [{"sourceId": "T:1", "check": "fat_level", "value": "2 g", "status": "fix", "note": "x"},
                      {"sourceId": "T:2", "check": "sodium_level", "value": "1 mg", "status": "accepted", "note": ""}]
        problems = problems_of(self.rows, curation(exceptions=exceptions))
        self.assertIn("T:1 fat_level: the check passes now, remove the row", problems)
        self.assertIn("T:2 sodium_level: needs a note", problems)

    def test_energy_salt_and_portion_checks_use_their_limits(self):
        rows = [food("T:1", "Lentil soup", kcal=31, protein=9.7, carbs=3.9, fat=11.7, sodiumMgPer100g="10418.8"),
                food("T:2", "Sweet lassi", unit="tall glass", weight="900", servingQuantity="2")]
        problems = problems_of(rows, curation())
        self.assertIn("T:1 fails energy_vs_macros: 31 kcal stated, 160 kcal from protein, carbs and fat", problems)
        self.assertIn("T:1 fails sodium_level", problems)
        self.assertNotIn("T:2 fails portion_weight", problems)  # 2 tall glasses of 450 g each

    def test_impossible_values_and_unparsable_cells_stop_the_build_outright(self):
        rows = [food("T:1", "Ghee", fat=101), food("T:2", "Roti", proteinGPer100g="lots"), food("T:3", " ", id="x")]
        problems = problems_of(rows, curation())
        self.assertIn("fatGPer100g is 101 g, more than 100 g per 100 g", problems)
        self.assertIn("proteinGPer100g 'lots' is not a number", problems)
        self.assertIn("T:3: id 'x' is not a whole number", problems)
        self.assertIn("T:3: name is blank", problems)

    def test_a_food_id_must_stay_outside_the_custom_food_and_recipe_range_and_match_its_uuid(self):
        problems = problems_of(self.rows + [food("T:3", "Idli", id="150000000")], curation())
        self.assertIn("is not a catalog id", problems)
        self.assertIn("is not the first 15 hex digits of uuid", problems)

    def test_a_non_breaking_space_in_a_name_is_a_finding(self):
        rows = [food("T:1", "Mango milkshake ice cream\xa0ke\xa0saath")]
        self.assertIn("T:1 fails name_text", problems_of(rows, curation()))

    def test_normalized_names_fold_latin_accents_like_the_app_but_keep_indic_vowel_signs(self):
        self.assertEqual("puree", b.normalize_name("Pur\u00e9e"))
        self.assertEqual("\u0926\u093e\u0932", b.normalize_name("\u0926\u093e\u0932"))
        self.assertEqual("ice cream ke saath", b.normalize_name("Ice cream\xa0ke  saath"))


def cooked_water(water, sid="T:1", name="Plain dosa"):
    return {"sourceId": sid, "food": name, "correction": "cooked_water", "water_g": str(water), "reason": "r", "reference": "ref"}


class CookedWaterTests(unittest.TestCase):
    """BUG-009: griddled and boiled dishes INDB carries at nearly the dry weight of their ingredients."""

    def dosa(self):
        return food("T:1", "Plain dosa", kcal=380.9, protein=10.3, carbs=64.1, fat=8.4, unit="dosa", weight=36.1,
                    fiberGPer100g="5.2", sodiumMgPer100g="10")

    def test_the_water_is_set_and_every_nutrient_and_the_portion_follow_it(self):
        before = b.estimated_water(self.dosa())
        dosa = b.apply_curation([self.dosa()], curation(corrections=[cooked_water(57)]))[0]
        value = lambda column: float(dosa[column])
        scale = 43 / (100 - before)

        self.assertAlmostEqual(57.0, b.estimated_water(dosa), delta=0.01)
        self.assertAlmostEqual(380.9 * scale, value("caloriesKcalPer100g"), delta=0.05)
        self.assertAlmostEqual(10.3 * scale, value("proteinGPer100g"), delta=0.01)
        self.assertAlmostEqual(8.4 * scale, value("fatGPer100g"), delta=0.01)
        self.assertAlmostEqual(5.2 * scale, value("fiberGPer100g"), delta=0.01)

    def test_one_dosa_keeps_its_energy_because_only_water_was_added(self):
        dosa = b.apply_curation([self.dosa()], curation(corrections=[cooked_water(57)]))[0]

        self.assertAlmostEqual(380.9 * 36.1 / 100, float(dosa["caloriesKcalPer100g"]) * float(dosa["servingWeightG"]) / 100, delta=0.05)

    def test_a_blank_nutrient_stays_blank(self):
        dosa = b.apply_curation([self.dosa()], curation(corrections=[cooked_water(57)]))[0]

        self.assertEqual("", dosa.get("cholesterolMgPer100g", ""))

    def test_a_target_drier_than_the_food_is_refused(self):
        self.assertIn("already holds", problems_of([self.dosa()], curation(corrections=[cooked_water(5)])))

    def test_a_target_is_required(self):
        self.assertIn("water_g must be a number", problems_of([self.dosa()], curation(corrections=[cooked_water("")])))

    def test_a_cooked_dish_with_too_little_water_is_a_data_problem(self):
        _, findings = b.check_foods([food("INDB:1", "Plain dosa", kcal=380, protein=10, carbs=69, fat=8, fiberGPer100g="5")])

        self.assertIn(("INDB:1", "cooked_dish_water"), findings)

    def test_only_recipes_are_held_to_it(self):
        raw = food("USDA:1", "Rice, white, raw", kcal=360, protein=7, carbs=80, fat=1)

        self.assertNotIn(("USDA:1", "cooked_dish_water"), b.check_foods([raw])[1])


class BundledCatalogTests(unittest.TestCase):
    """The dishes as they ship: a griddled or boiled dish holds its cooking water."""

    @classmethod
    def setUpClass(cls):
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets", "databases", "built_in_foods.db")
        cls.con = sqlite3.connect(f"file:{path}?mode=ro&immutable=1", uri=True)
        cls.addClassCleanup(cls.con.close)

    def kcal(self, source_id):
        return self.con.execute("SELECT calories FROM foods WHERE source_id = ?", (source_id,)).fetchone()[0]

    def test_griddled_batter_is_as_dense_as_its_siblings(self):
        masala_dosa = self.kcal("INDB:0132")
        for source_id in ("INDB:0503", "INDB:0884", "INDB:0770", "INDB:0506", "INDB:0927"):
            self.assertLess(self.kcal(source_id), masala_dosa * 1.5, source_id)

    def test_a_boiled_dal_is_not_at_dry_weight(self):
        self.assertLess(self.kcal("INDB:0931"), 150)

    def test_a_cooked_rice_dish_is_not_at_dry_weight(self):
        self.assertLess(self.kcal("INDB:0117"), 200)

    def test_a_dosa_keeps_its_energy_and_gains_its_weight(self):
        grams = self.con.execute(
            "SELECT s.gram_weight FROM servings s JOIN foods f ON f.id = s.food_id WHERE f.source_id = 'INDB:0503' AND s.description = '1 dosa'"
        ).fetchone()[0]

        self.assertAlmostEqual(137.5, self.kcal("INDB:0503") * grams / 100, delta=1.0)
        self.assertGreater(grams, 60)


def frying(frying_oil, kept, water="", sid="T:1", name="Poori"):
    return {"sourceId": sid, "food": name, "correction": "excess_frying_oil", "frying_oil_g": str(frying_oil),
            "oil_kept_g": str(kept), "water_g": str(water), "reason": "r", "reference": "ref"}


class CorrectionAndImportTests(unittest.TestCase):
    def poori(self):
        # INDB's poori: 76.9 g of every 100 g is the pot of frying oil, and its energy is 4/4/9 of its macros
        return food("T:1", "Poori", kcal=736.68, protein=1.35, carbs=8.22, fat=77.6, unit="poori", weight=124.9,
                    fiberGPer100g="1", sodiumMgPer100g="31.7", saturatedFatGPer100g="8")

    def test_water_is_estimated_by_difference(self):
        # 100 - 1.35 - 77.6 - 8.22 - 1 g, less ash: salt 2.54 x 31.7 mg
        self.assertAlmostEqual(11.75, b.estimated_water(self.poori()), delta=0.01)

    def test_the_frying_oil_comes_out_but_what_the_food_keeps_and_the_water_is_set(self):
        poori = b.apply_curation([self.poori()], curation(corrections=[frying(76.9, 19, 25)]))[0]
        value = lambda column: float(poori[column])
        # 100 g of INDB's poori is 11.35 g of dough solids; as eaten they are 56% of it, so it weighs 20.27 g
        self.assertAlmostEqual(25.0, b.estimated_water(poori), delta=0.01)
        self.assertAlmostEqual(124.9 * 0.2027, value("servingWeightG"), delta=0.05)
        self.assertAlmostEqual(1.35 * 100 / 20.27, value("proteinGPer100g"), delta=0.01)
        # The dough's own fat, concentrated, plus the 19 g of oil it keeps
        self.assertAlmostEqual((77.6 - 76.9) * 100 / 20.27 + 19, value("fatGPer100g"), delta=0.01)
        # The oil's energy leaves at the 9 kcal/g INDB counts, so the energy still fits the macros
        self.assertAlmostEqual(4 * value("proteinGPer100g") + 4 * value("carbohydratesGPer100g") + 9 * value("fatGPer100g"),
                               value("caloriesKcalPer100g"), delta=0.05)
        self.assertAlmostEqual(8 / 77.6, value("saturatedFatGPer100g") / value("fatGPer100g"), delta=0.0001)

    def test_without_a_water_target_only_the_oil_leaves(self):
        poori = b.apply_curation([self.poori()], curation(corrections=[frying(76.9, 4)]))[0]
        # 23.1 g of the food is left, with 4 g of oil per 100 g of it: 24.06 g
        self.assertAlmostEqual(1.35 * 100 / 24.06, float(poori["proteinGPer100g"]), delta=0.01)
        self.assertAlmostEqual(11.75 * 100 / 24.06, b.estimated_water(poori), delta=0.05)

    def test_a_food_cannot_keep_more_oil_than_it_was_fried_in(self):
        self.assertIn("keeps more oil than the recipe fried in", problems_of([self.poori()], curation(corrections=[frying(10, 95)])))

    def test_the_frying_oil_cannot_be_more_than_the_foods_fat(self):
        self.assertIn("must be above 0 and at most the food's 77.6 g fat", problems_of([self.poori()], curation(corrections=[frying(80, 19)])))

    def test_values_from_after_a_frying_correction_keeps_the_weighed_portion(self):
        sources = [{"source": "Test source"}, {"source": "Measured"}]
        reference = food("R:1", "Puri, measured", kcal=366, protein=7.2, carbs=36.5, fat=22.3, source="Measured", role="reference")
        fix = [{"sourceId": "T:1", "food": "Poori", "correction": "values_from", "from": "R:1", "reason": "r", "reference": "ref"},
               frying(76.9, 19, 25)]
        poori = b.apply_curation([self.poori(), reference], curation(sources=sources, corrections=fix))[0]
        self.assertEqual("366", poori["caloriesKcalPer100g"])
        self.assertAlmostEqual(124.9 * 0.2027, float(poori["servingWeightG"]), delta=0.05)

    def test_values_from_copies_a_reference_row_in_the_foods_own_carbohydrate_terms(self):
        sources = [{"source": "Test source", "carbohydrate": "available"}, {"source": "Measured", "carbohydrate": "total"}]
        reference = food("R:1", "Lassi, measured", kcal=65, protein=2.6, carbs=12.3, fat=0.9, source="Measured", role="reference",
                         fiberGPer100g="0.5")
        fix = [{"sourceId": "T:2", "food": "Sweet lassi", "correction": "values_from", "from": "R:1", "reason": "r", "reference": "ref"}]
        foods = b.apply_curation([food("T:2", "Sweet lassi", kcal=35.7), reference], curation(sources=sources, corrections=fix))
        self.assertEqual(["T:2"], [f["sourceId"] for f in foods])
        self.assertEqual("65", foods[0]["caloriesKcalPer100g"])
        # 11.8 g available in the lassi's terms, then the fibre goes back on: 12.3 g total, as measured
        self.assertEqual("12.3", foods[0]["carbohydratesGPer100g"])

    def test_a_reference_row_no_correction_uses_stops_the_build(self):
        reference = food("R:1", "Unused", role="reference")
        self.assertIn("a reference row that no correction or recipe uses", problems_of([self.poori(), reference], curation()))

    def test_available_carbohydrate_gets_its_fibre_added(self):
        sources = [{"source": "Test source", "carbohydrate": "available"}]
        roti = b.apply_curation([food("T:1", "Roti", carbs=35.65, fiberGPer100g="6.3")], curation(sources=sources))[0]
        self.assertEqual("41.95", roti["carbohydratesGPer100g"])

    def test_extra_portions_ship_after_every_imported_rows_serving_ids(self):
        rows = [food("T:1", "Plain dosa", unit="dosa", weight=36), food("U:1", "Rice, raw", portions="1 cup=185|1 tbsp=12")]
        servings = built(rows, curation(), "SELECT id, description, unit_type, gram_weight FROM servings ORDER BY id")
        # Id 4 is held for a portion an override could give the rice; the extra portions follow it
        self.assertEqual([(1, "100 g", "g", 100.0), (2, "1 dosa", "dosa", 36.0), (3, "100 g", "g", 100.0),
                          (5, "1 cup", "cup", 185.0), (6, "1 tbsp", "tbsp", 12.0)], servings)
        self.assertIn("must read 'description=grams'", problems_of([food("U:1", "Rice, raw", portions="1 cup")], curation()))

    def test_a_food_names_a_known_food_group(self):
        rows = [food("U:1", "Paneer", category="Dairy"), food("T:1", "Plain dosa")]
        self.assertEqual([("Paneer", "Dairy"), ("Plain dosa", "Indian Foods & Recipes")],
                         built(rows, curation(), "SELECT f.name, c.name FROM foods f JOIN categories c ON c.id = f.category_id ORDER BY f.name"))
        self.assertIn("category 'Snacks' is not one of", problems_of([food("U:1", "Chips", category="Snacks")], curation()))


def recipe(rid="ghee-rice", name="Ghee rice", **cells):
    return dict({"id": rid, "name": name, "reference": "ref"}, **cells)


def line(rid, sid, name, grams):
    return {"recipe": rid, "ingredient": sid, "food": name, "grams": str(grams)}


class RecipeTests(unittest.TestCase):
    def setUp(self):
        # Rice: 12 g of every 100 g is water by difference; ghee: none
        self.rows = [food("T:1", "Rice", kcal=360, protein=7, carbs=80, fat=1), food("T:2", "Ghee", kcal=900, protein=0, carbs=0, fat=100)]
        self.lines = [line("ghee-rice", "T:1", "Rice", 100), line("ghee-rice", "T:2", "Ghee", 10)]

    def cooked(self, recipes, lines=None):
        foods = b.apply_curation(self.rows, curation(recipes=recipes, recipe_ingredients=self.lines if lines is None else lines))
        return {f["sourceId"]: f for f in foods}

    def test_a_recipe_adds_up_its_ingredients_over_its_cooked_weight(self):
        rice = self.cooked([recipe(cooked_g="300")])["MacroBase:ghee-rice"]
        self.assertAlmostEqual((360 + 90) / 3, float(rice["caloriesKcalPer100g"]), places=3)
        self.assertAlmostEqual(11 / 3, float(rice["fatGPer100g"]), places=3)
        self.assertEqual("MacroBase standard recipes", rice["source"])

    def test_a_water_target_sets_the_cooked_weight_and_pieces_give_the_portion(self):
        rice = self.cooked([recipe(water_g="70", pieces="4", unit="ball")])["MacroBase:ghee-rice"]
        # 88 g of the rice and all 10 g of the ghee are not water: 98 g, 30% of the cooked weight
        mass = 98 / 0.3
        self.assertAlmostEqual(450 / mass * 100, float(rice["caloriesKcalPer100g"]), places=3)
        self.assertEqual("1 ball", rice["servingDescription"])
        self.assertAlmostEqual(mass / 4, float(rice["servingWeightG"]), places=3)

    def test_a_cooked_weight_below_the_ingredients_solids_is_refused(self):
        problems = problems_of(self.rows, curation(recipes=[recipe(cooked_g="50")], recipe_ingredients=self.lines))
        self.assertIn("50 g as eaten is less than the 98.0 g of its ingredients that is not water", problems)

    def test_an_ingredient_must_be_a_catalog_food_named_as_it_is(self):
        lines = [line("ghee-rice", "T:1", "Basmati", 100), line("ghee-rice", "T:9", "Salt", 1)]
        problems = problems_of(self.rows, curation(recipes=[recipe()], recipe_ingredients=lines))
        self.assertIn("ingredient T:1 is Rice, not 'Basmati'", problems)
        self.assertIn("ingredient T:9 is not a catalog food", problems)

    def test_a_recipe_can_use_one_above_it_and_an_ingredient_recipe_does_not_ship(self):
        lines = self.lines + [line("rice-bowl", "MacroBase:ghee-rice", "Ghee rice", 200)]
        foods = self.cooked([recipe(cooked_g="300", role="ingredient"), recipe("rice-bowl", "Rice bowl")], lines)
        self.assertNotIn("MacroBase:ghee-rice", foods)
        self.assertAlmostEqual(150, float(foods["MacroBase:rice-bowl"]["caloriesKcalPer100g"]), places=3)
        problems = problems_of(self.rows, curation(recipes=[recipe("rice-bowl", "Rice bowl"), recipe(cooked_g="300")], recipe_ingredients=lines))
        self.assertIn("ingredient MacroBase:ghee-rice is a recipe below this one", problems)

    def test_a_reference_row_a_recipe_uses_is_used(self):
        self.rows[0]["sodiumMgPer100g"], self.rows[1]["sodiumMgPer100g"] = "5", "0"
        rows = self.rows + [food("R:1", "Salt, reference", kcal=0, protein=0, carbs=0, fat=0, role="reference", sodiumMgPer100g="38758")]
        lines = self.lines + [line("ghee-rice", "R:1", "Salt, reference", 2)]
        foods = b.apply_curation(rows, curation(recipes=[recipe(cooked_g="300")], recipe_ingredients=lines))
        self.assertAlmostEqual((5 + 38758 * 2 / 100) / 3, float({f["sourceId"]: f for f in foods}["MacroBase:ghee-rice"]["sodiumMgPer100g"]), places=2)

    def test_a_nutrient_ingredients_with_most_of_the_solids_do_not_state_stays_blank(self):
        # Only the ghee (10 of the 98 g of solids) states fibre: the rice's missing fibre would mislead
        self.rows[1]["fiberGPer100g"] = "0"
        self.assertEqual("", self.cooked([recipe(cooked_g="300")])["MacroBase:ghee-rice"]["fiberGPer100g"])
        # 8 g of ghee stating no sugars is under a tenth of the 96 g of solids, so it counts as none
        self.rows[0]["sugarsGPer100g"] = "1"
        lines = [line("ghee-rice", "T:1", "Rice", 100), line("ghee-rice", "T:2", "Ghee", 8)]
        self.assertAlmostEqual(1 / 3, float(self.cooked([recipe(cooked_g="300")], lines)["MacroBase:ghee-rice"]["sugarsGPer100g"]), places=3)


class CurationFileTests(unittest.TestCase):
    def read(self, text):
        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, "f.csv"), "w", encoding="utf-8") as f:
                f.write(text)
            return b.read_curation("f.csv", tmp)

    def test_comments_and_an_excel_byte_order_mark_are_ignored(self):
        self.assertEqual([{"sourceId": "INDB:0001", "reason": "x"}], self.read("\ufeff# comment\nsourceId,reason\nINDB:0001, x \n"))

    def test_a_row_with_an_unquoted_comma_is_reported(self):
        with self.assertRaises(b.CatalogError) as raised:
            self.read("sourceId,reason\nINDB:0001,too specific, rare\n")
        self.assertIn("row 2 has more cells than the header", raised.exception.problems[0])


class CommittedCatalogTests(unittest.TestCase):
    def test_the_committed_catalog_is_what_the_build_produces_and_its_version_is_pinned(self):
        with tempfile.TemporaryDirectory() as tmp:
            cur = b.load_curation()
            fingerprint = b.build(b.read_imported_rows(cur["sources"]), cur, os.path.join(tmp, "catalog.db"))["fingerprint"]
        self.assertEqual(fingerprint, b.database_fingerprint(b.TARGET_DB), "run tools/catalog/build_catalog.py")
        self.assertEqual(fingerprint, b.read_fingerprint_file(), "run tools/catalog/build_catalog.py")
        self.assertEqual([], b.check_version(fingerprint, b.read_versions()))

    def test_an_index_counts_in_the_fingerprint(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "catalog.db")
            before = b.build([food("T:1", "Idli")], curation(), path)["fingerprint"]
            con = sqlite3.connect(path)
            con.execute("CREATE INDEX idx_test ON food_search(priority)")
            con.commit()
            con.close()
            self.assertNotEqual(before, b.database_fingerprint(path))


if __name__ == "__main__":
    unittest.main()
