"""Tests for import_basic_foods.py. Run with: python -m unittest discover tools/catalog"""

import unittest

import import_basic_foods as imp

MICRO = chr(0xB5)  # the micro sign in CoFID's vitamin column names


def cofid(proximates=None, vitamins=None):
    return {"1.3 Proximates": proximates or {}, "1.4 Inorganics": {}, "1.5 Vitamins": vitamins or {}}


class CofidTests(unittest.TestCase):
    def test_cells_read_trace_as_zero_and_not_measured_as_missing(self):
        self.assertEqual(0.0, imp.cofid_number("Tr"))
        self.assertIsNone(imp.cofid_number("N"))
        self.assertIsNone(imp.cofid_number(""))
        self.assertEqual(1.2, imp.cofid_number("(1.2)"))

    def test_carbohydrate_and_sugars_become_weights(self):
        # Basmati rice, raw: starch over 1.10 and sucrose over 1.05; maltose and lactose are none
        values = imp.cofid_values(cofid({"Carbohydrate (g)": "84.8", "Starch (g)": "84.6", "Total sugars (g)": "0.2",
                                         "Sucrose (g)": "0.1", "Maltose (g)": "Tr", "Lactose (g)": "0"}))
        self.assertAlmostEqual(84.8 - 84.6 * (1 - 1 / 1.10) - 0.1 * (1 - 1 / 1.05), values["carbohydratesGPer100g"], places=6)
        self.assertAlmostEqual(0.2 - 0.1 * (1 - 1 / 1.05), values["sugarsGPer100g"], places=6)

    def test_carbohydrate_with_unknown_parts_is_divided_by_105(self):
        values = imp.cofid_values(cofid({"Carbohydrate (g)": "12.6", "Starch (g)": "N", "Total sugars (g)": "2.1"}))
        self.assertAlmostEqual(12.0, values["carbohydratesGPer100g"], places=6)
        self.assertAlmostEqual(2.0, values["sugarsGPer100g"], places=6)

    def test_fibre_falls_back_to_nsp_when_aoac_was_not_measured(self):
        self.assertEqual(1.4, imp.cofid_values(cofid({"AOAC fibre (g)": "", "NSP (g)": "1.4"}))["fiberGPer100g"])
        self.assertEqual(2.0, imp.cofid_values(cofid({"AOAC fibre (g)": "2.0", "NSP (g)": "1.4"}))["fiberGPer100g"])
        self.assertIsNone(imp.cofid_values(cofid({"AOAC fibre (g)": "N", "NSP (g)": "N"}))["fiberGPer100g"])

    def test_vitamin_a_is_retinol_plus_carotene_over_12_and_fatty_acids_are_milligrams(self):
        values = imp.cofid_values(cofid({"Mono FA /100g food (g)": "1.5"},
                                        {f"Retinol ({MICRO}g)": "10", f"Carotene ({MICRO}g)": "120"}))
        self.assertEqual(20.0, values["vitaminAMcgPer100g"])
        self.assertEqual(1500.0, values["mufaMgPer100g"])
        self.assertIsNone(imp.cofid_values(cofid(vitamins={f"Retinol ({MICRO}g)": "10", f"Carotene ({MICRO}g)": "N"}))["vitaminAMcgPer100g"])


class UsdaTests(unittest.TestCase):
    def test_energy_prefers_specific_factors_and_carbohydrate_is_by_difference(self):
        foundation = {"nutrients": {2047: 106.0, 2048: 112.2, 1050: 3.0, 1293: 0.5}}
        values = imp.usda_values(foundation)
        self.assertEqual(112.2, values["caloriesKcalPer100g"])
        self.assertIsNone(values["carbohydratesGPer100g"])
        self.assertEqual(500.0, values["pufaMgPer100g"])
        self.assertEqual(119.0, imp.usda_values({"nutrients": {1008: 119.0, 2048: 120.0}})["caloriesKcalPer100g"])


class PortionTests(unittest.TestCase):
    available = [("1 cup, chopped", 91.0), ("1 cup", 150.0), ("1 medium (3 in. dia)", 118.0)]

    def test_portions_are_picked_by_their_ending_and_can_be_relabelled_or_given(self):
        picked = imp.pick_portions(self.available, "cup, chopped|medium (3 in. dia)>1 medium|1 bowl=250", "row")
        self.assertEqual([("1 cup, chopped", 91.0), ("1 medium", 118.0), ("1 bowl", 250.0)], picked)

    def test_a_portion_must_match_exactly_one_source_portion(self):
        with self.assertRaises(imp.ImportError_):
            imp.pick_portions(self.available, "tbsp", "row")
        self.assertEqual([("1 cup", 150.0)], imp.pick_portions(self.available, "1 cup", "row"))


if __name__ == "__main__":
    unittest.main()
