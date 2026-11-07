# Built-in food catalog

`build_catalog.py` is the only thing that writes `app/src/main/assets/databases/built_in_foods.db` (and its
`built_in_foods.fingerprint`). It reads every file `sources.csv` imports: the INDB recipes in
`macrobase_indian_foods_canonical.csv` (repository root) and the basic foods in `new_foods.csv`. It applies the curation
files in this folder, works out the standard recipes in `recipes.csv`, checks every food, and writes nothing if a check
fails.

`import_basic_foods.py` writes `new_foods.csv` from `basic_foods.csv` and the USDA FoodData Central (SR Legacy,
Foundation Foods and FNDDS) and UK CoFID downloads pinned in `sources.csv`. The downloads are not in the repository; run
it only to change the basic foods:

```
python tools/catalog/import_basic_foods.py --usda-sr FoodData_Central_sr_legacy_food_csv_2018-04.zip
    --usda-foundation FoodData_Central_foundation_food_csv_2026-04-30.zip
    --usda-fndds FoodData_Central_survey_food_csv_2024-10-31.zip --cofid cofid.xlsx
```

Branded products are a second catalog with its own files: see [Branded products](#branded-products).

```
python tools/catalog/build_catalog.py --check      # check, and that the committed catalog is current
python tools/catalog/build_catalog.py              # check, and write the catalog if its content changed
python tools/catalog/build_branded.py --check      # the same for the branded catalog
python -m unittest discover tools/catalog          # tests for the builds and the importers, including the committed catalogs
./gradlew testDebugUnitTest                        # app tests, including CatalogSearchTests and BrandedCatalogTests
```

Python 3, standard library only. The build writes the catalog only when its content changes, so running it twice
leaves git clean.

## Files

Every file is CSV with a header row. Lines starting with `#` are comments. Rows refer to a food by its `sourceId`
(for example `INDB:0845`), which never changes. Quote a cell that contains a comma.

| File | One row is | Columns |
|---|---|---|
| `sources.csv` | A data source foods may come from | `source` (as in the foods CSV), `licence`, `url`, `sha256` of the download, `imported_file`, `imported_sha256`, `carbohydrate` (`total` or `available`), `notes` |
| `basic_foods.csv` | A basic food to import, or a reference row | `name`, `category`, `source` (`usda-sr`, `usda-foundation`, `usda-fndds` or `cofid`), `code`, `portions`, `role`, `note` |
| `new_foods.csv` | An imported basic food (written by `import_basic_foods.py`) | The foods CSV columns plus `portions` and `role` |
| `exclusions.csv` | A food left out of the catalog | `sourceId`, `food`, `reason` |
| `corrections.csv` | A computed correction to one food | `sourceId`, `food` (checked), `correction`, `frying_oil_g`, `oil_kept_g`, `water_g`, `from`, `columns`, `reason`, `reference` |
| `recipes.csv` | A standard recipe | `id`, `name`, `category`, `cooked_g` or `water_g`, `pieces` and `unit`, `portions`, `reference`, `note`, `role` |
| `recipe_ingredients.csv` | One ingredient of a recipe | `recipe` (its `id`), `ingredient` (a `sourceId`, or `MacroBase:<id>` of a recipe above), `food` (its name, checked), `grams`, `note` |
| `overrides.csv` | One corrected value of one food | `sourceId`, `column` (a foods CSV column), `value` (blank clears it), `reason`, `reference` |
| `aliases.csv` | An extra name search finds a food by | `sourceId`, `food` (its name, checked), `alias`, `kind` (`spelling`, `hindi`, `regional`, `english` or `other`), `note` |
| `synonyms.csv` | Words that mean the same thing, for every food | `words` (members separated by `\|`, each one or more words), `kind`, `note` |
| `common_foods.csv` | How commonly a food is eaten | `sourceId`, `food` (its name, checked), `priority` (`3` the everyday version people mean, `2` a common dish, `1` common) |
| `data_exceptions.csv` | A food that fails a plausibility check | `sourceId`, `check`, `value`, `status`, `note` |
| `catalog_versions.csv` | A released catalog version | `database_version`, `content_fingerprint`, `pinned_on` |
| `search_cases.csv` | An everyday search and the food it should find first | `query`, `expect`, `top`, `status`, `found_today` |

The imported foods CSV is pinned by `imported_sha256`: correct a value in `overrides.csv`, never in the CSV itself.
The build refuses curation rows that no longer do anything (an exclusion or alias for an unknown food, an override that
sets the value the food already has, an exception for a check that now passes). Delete those rows.

## Carbohydrate, corrections and portions

The app reads carbohydrate as total carbohydrate with fibre in it (net carbs are carbs minus fibre), as USDA reports
it. INDB and CoFID report available carbohydrate, without fibre, so their sources have `carbohydrate` set to
`available` and the build adds fibre back where the food states it. Six CoFID foods (moong and urad dal, tofu, methi,
tinda and amla) state no fibre, so their carbohydrate stays available. INDB took about half its recipes' ingredients
from CoFID and a few from USDA without harmonising them, so its available carbohydrate is a mix. Overrides and
corrections are written in the food's own source's terms.

`import_basic_foods.py` converts CoFID's carbohydrate and sugars, which CoFID gives as monosaccharide equivalents, to
weight as the CoFID 2021 user guide says: starch divided by 1.10, and sucrose, maltose and lactose by 1.05. Where
CoFID did not measure AOAC fibre it takes NSP fibre, which runs lower. "Tr" (trace) imports as 0. CoFID's vitamin E
is alpha-tocopherol equivalents and its vitamin D includes 25-hydroxy vitamin D; USDA's are alpha-tocopherol and
vitamin D2 plus D3.

`corrections.csv` holds computed fixes, at most one of each kind per food:

- `excess_frying_oil`. INDB adds up the raw weight of every ingredient, the whole pot of frying oil included, and
  loses no water in the pan: a poori has 77.6 g fat per 100 g and weighs 125 g. `frying_oil_g` is the frying oil in
  100 g of INDB's recipe: the recipe sheet's oil for frying over the recipe's total weight (INDB's portion weight
  times the portions the recipe makes). `oil_kept_g` is the oil the food keeps per 100 g as eaten. `water_g`, for a
  food fried whole, is its water per 100 g as eaten; the build estimates the food's water by difference, with ash
  from its minerals. 100 g of INDB's food becomes m g. Every nutrient is concentrated by 100 / m. The fat loses the
  oil taken out, and the oil's fatty acids and vitamin E fall with the fat. The energy loses 9 kcal per gram of that
  oil, the factor INDB's energy uses, so it still matches 4/4/9 of the macros. The household portion becomes m% of
  its weight. The build refuses a food that keeps more oil than its recipe fried in.

  Each kind of fried food has one `oil_kept_g` and `water_g`, from CoFID's measured foods of that kind, named in
  `reference`:

  | Kind | Oil kept, water (g per 100 g) | CoFID 2021 food | INDB food corrected, against CoFID |
  |---|---|---|---|
  | Pooris, bhaturas, kachoris, gujiyas, fried toast | 19, 25.0 | 11-911 Puri, homemade | Poori 385 kcal, 22.3 g fat; 366 kcal, 22.3 g |
  | Pakoras, bhajias, vadas, bondas, fritters | 17, 40.5 | 15-826 to 15-832 Pakora/bhajia, homemade | Potato pakora 308 kcal; potato bhajia 315 kcal |
  | Samosas, spring rolls | 10, 44.5 | 19-326 Meat samosas, takeaway | Potato samosa 17.2 g fat; 17.3 g |
  | Sev, mathri, papdi, murukku | 19, 4.1 | 17-836 Sev/ganthia, homemade | Sev 458 kcal, 17.6 g protein; 478 kcal, 18.8 g |
  | Cutlets, kebabs, fried fish and meat | 7, 55.1 | 15-820 Lentil cutlets, fried, homemade | Vegetable cutlet 8.2 g fat; 8.3 g |
  | Cheelas and uttapams | 5, 57.0 | 11-959, 11-1102, 11-1103 Pancakes, savoury | Water only; the oil is a teaspoon each |
  | Gulab jamun, malpua | 6, 37.8 | 11-1081 and 11-1083 Gulab jamun | Gulab jamun with khoya 10.1 g fat; 6.6 to 12.8 g |
  | Dishes with fried pieces: kofta curries, dum aloo, manchurian, dahi vada, chaats | 4, its own | None: the pieces are about a third of the dish and keep about 12 g of oil per 100 g | |

  A poori goes from 738 kcal per 100 g (921 kcal each) to 385 kcal (102 kcal each, 26.6 g). Banana chips keep more
  oil than the crisp snacks, and INDB's gulab jamun with milk powder uses evaporated milk for the powder, so both
  then take `values_from` measured foods; their frying correction still weighs the portion.
- `cooked_water`. INDB adds up raw ingredients for griddled and boiled dishes too, so a dish with no pot of frying oil
  to take out can sit near the dry weight of its ingredients: a plain dosa had 381 kcal per 100 g and weighed 36 g.
  `water_g` is its water per 100 g as eaten, from a measured dish named in `reference`. 100 g of INDB's food becomes
  m g: every nutrient is concentrated by 100 / m and the household portion becomes m% of its weight, so one dosa keeps
  its energy and weighs 75 g. The build refuses a `water_g` the food already holds. With no measured dish in this
  repository to cite, leave the food as a `fix` row in `data_exceptions.csv` rather than estimate the water.

  | Kind | Water (g per 100 g) | Measured dish | Foods |
  |---|---|---|---|
  | Griddled batter | 57.0 | CoFID 2021 11-959, 11-1102, 11-1103 Pancakes, savoury | Plain dosa, jowar dosa, pesarattu, uttapam, besan bathua chilla |
  | Boiled dal | 74.2 | CoFID 2021 15-780 Curry, pigeon pea dahl | Maa chaane ki dal |
  | Cooked rice dishes | 66.9 | CoFID 2021 19-454 Biryani, chicken, takeaway | Tamarind rice |

- `values_from` with `from`: the `columns` (all nutrient columns when blank) come from a reference row of an imported
  file, a `basic_foods.csv` row with `role` set to `reference`. Reference rows never ship as foods, and the build
  refuses one that no correction uses. A food's `values_from` runs after its frying correction.

A basic food's `portions` are USDA's own household portions (`1 cup=185|1 tbsp=13`); a weight from elsewhere needs a
note in `basic_foods.csv` citing it. Plausibility bounds apply to INDB's portions only: USDA's are measured weights of
that food, and a cup of spinach really is 30 g. An INDB portion that is more or less than one serving is cleared in
`overrides.csv`, and that food is logged by grams.

Each food belongs to one of the groups in `CATEGORIES`; INDB recipes are "Indian Foods & Recipes". Append new groups at
the end, since their ids follow the list.

## Standard recipes

Dishes no source measures (vada pav, pani puri, momos, jalebi, dal tadka) are standard recipes: `recipes.csv` names
each one and `recipe_ingredients.csv` lists its raw ingredients in grams. An ingredient is a catalog food, a reference
row of `basic_foods.csv` (salt and spices, which do not ship as foods) or a recipe above it, written `MacroBase:<id>`.
The build adds up the ingredients' curated values (total carbohydrate, the frying oil already corrected) and divides by
what the batch weighs as eaten:

- `cooked_g`, the batch's weight as eaten, when it gains or loses water a recipe can state (a dough made with 100 g of
  water, a sweet boiled down);
- `water_g`, the water in 100 g as eaten, usually that of the nearest measured dish in CoFID or USDA, named in
  `reference` (dal tadka takes CoFID's pigeon pea dahl, 74.2 g); the build works out the weight from the ingredients'
  water, estimated by difference;
- neither, for a plate put together from foods as they are (chole bhature, sev puri).

A nutrient no ingredient states stays blank; otherwise a missing one counts as none, as the app adds up its own recipes.
A fried recipe lists the oil it keeps, taken from the frying classes in `corrections.csv`. `pieces` of `unit` give its
household portion (17 laddus from the motichoor laddu batch), and `portions` more of them, with cited weights. A recipe with `role`
`ingredient` (the koftas of malai kofta) only goes into other recipes. Recipes ship as `MacroBase:<id>`, from the
source "MacroBase standard recipes", after every imported food's serving ids.

Recipe ingredients, yields and piece counts are our own standard home recipes. Piece weights not from FNDDS (jalebi,
laddu, peda, tikki) are estimates worth weighing on real pieces.

## Branded products

`branded_foods.db` is a second read-only catalog: packaged products sold in India, from Open Food Facts. Open Food Facts
is under the Open Database Licence (ODbL 1.0), so this database is too, and it is kept apart from `built_in_foods.db`,
whose INDB data cannot be relicensed. The app opens both and merges their search results.

- `import_branded_foods.py --snapshot off_india_top10000.jsonl` reads a snapshot of the 10,000 most scanned products
  sold in India (search.openfoodfacts.org), pinned by sha256 in `branded/sources.csv`, and writes
  `branded/branded_foods.csv`. It keeps a product only when its barcode was registered in India (GS1 890), it names
  a brand and a product, it states energy, protein, carbohydrate and fat per 100 g, its energy is within 20% (and
  20 kcal) of 4 x protein + 4 x carbohydrate without fibre + 2 x fibre + 9 x fat, within its category's usual range
  and not below what a food of its kind has (a "ghee" at 194 kcal was filled in per serving), sugars and fibre fit in
  its carbohydrate, and sodium is under 10 g per 100 g. A salted snack's sodium of 0 is taken as not filled in. Pack
  sizes and prices come out of names, names start with the brand, and of one product listed twice (two spellings, or
  one brand's identical label) the most scanned stays.
- `build_branded.py` builds `branded_foods.db` and `branded_foods.fingerprint` from it, with the main catalog's tables,
  synonyms and checks, except that a label's fat or sodium may be above the main catalog's limits. Its ids never meet
  the main catalog's. `branded/exclusions.csv` leaves out a product, and its version is pinned in
  `branded/catalog_versions.csv`: bump `build_branded.DATABASE_VERSION` and
  `BuiltInDatabaseManager.BRANDED_DATABASE_VERSION` together.

Search ranks a product by how often it was scanned (5 scans or more ranks as a common food, 2 or more as one eaten
sometimes; the snapshot's most scanned product has 14). Values are as labelled; Indian labels state total
carbohydrate.

## Checks

These stop the build outright:

- A repeated `sourceId`, `id`, `uuid` or normalized name, or a blank name.
- An `id` in the custom food and recipe range (100,000,000 to 299,999,999), or one that is not the first 15 hex digits
  of the food's `uuid`.
- Blank calories, protein, carbs or fat; text where a number belongs; negative values; more than 100 g of any
  macronutrient per 100 g; more sodium than pure salt has (39,300 mg per 100 g).
- A source missing from `sources.csv`, or an imported file whose hash changed.
- A broken database: integrity check, one search row per food, exactly one default `100 g` serving per food.
- New content under a version already in `catalog_versions.csv` (see Releasing).

These need a row in `data_exceptions.csv` when a food fails them:

| Check | Fails when |
|---|---|
| `name_text` | The name has anything other than printable ASCII words separated by single spaces |
| `serving_text` | The serving unit or description has hidden or garbled characters |
| `energy_vs_macros` | Stated kcal differs from 4 x protein + 4 x carbohydrate without fibre + 2 x fibre + 9 x fat by more than 25% (and 20 kcal) |
| `macro_total` | Protein, carbs and fat add up to more than 102 g per 100 g |
| `fat_level` | More than 45 g fat per 100 g |
| `sodium_level` | More than 2,500 mg sodium per 100 g |
| `portion_weight` | One unit of INDB's household portion is outside `PORTION_BOUNDS_G` for its unit (1 piece over 200 g, for example) |
| `cooked_dish_water` | An INDB food named as a griddled or boiled dish (`COOKED_DISH`: dosa, cheela, chilla, uttapam, pesarattu, upma, daliya, poha, dal, rice) has under `COOKED_DISH_MIN_WATER_G` (45 g) of water per 100 g by difference. `energy_vs_macros` cannot see this, since a dish at dry weight still fits its macros. Breads, fried foods and sweets with those words in their names are `accepted` |

An exception row records the `value` the check saw, so it stops covering the food when that value changes. Its
`status` is `fix` for a known error still to correct, or `accepted` when the value is right; `note` says what is wrong
or why the value is right. The `fix` rows are the worklist for correcting INDB values.

## Search

Android's SQLite has no FTS5 (AOSP enables FTS3 and FTS4 only), so the catalog has no full-text table. The build writes
one `food_search` row per food, and `LocalFoodDatabaseProvider` searches it with plain SQL (`LIKE`, `CASE`,
`length`), which SQLite 3.18 on Android 8 already has:

- `terms` holds the words of the name and the curated aliases, plus every member of a `synonyms.csv` group that one of
  those words or phrases belongs to. A food is found when every query word starts one of its words, in any order:
  "tea" finds "Hot tea" but not "gateau", and "paneer chilli" finds "Chilli paneer".
- Results are ranked by: the name typed in full (`names`: the name, the name without its brackets, the parts of a slash
  name such as "Chapati/Roti", and the curated aliases), then foods holding every query word whole, then `priority`
  from `common_foods.csv`, then names that start with the query, then names holding every word, then shorter names.
  Text in brackets is not a name of its own: "(curry)" in "Cabbage rolls (curry)" describes the dish.
- Names holding the query inside a word come after all the word matches, so "ancake" finds "pancake" and "nut" also
  finds coconut and peanut dishes.

Words are folded the same way on both sides: lower case, accents on Latin letters removed, apostrophes and zero-width
joiners dropped ("lady's" is "ladys"), then split at anything that is not a letter, a combining mark or a digit
(`search_words` here, `LocalFoodDatabaseProvider.searchWords` in the app). The catalog's names are romanized, so a query
in Devanagari finds nothing yet.

An alias adds its words to the food's terms. Keep a generic word such as "milk" out of the alias of a high-priority
food, or that food comes first whenever someone searches for the word ("Coffee with milk" put instant coffee above
every food named milk). A `synonyms.csv` member of several words adds each word on its own, so it may not use a word
that is a member of another group: "patta gobhi" for cabbage would make "gobhi" (cauliflower) find cabbage.

The build writes the catalog's content fingerprint to `built_in_foods.fingerprint` beside it. `BuiltInDatabaseManager`
replaces an installed catalog whose fingerprint differs, so a test phone with an earlier build of an unreleased
version still gets the new content.

## Search cases

`CatalogSearchTests` runs each query through `LocalFoodDatabaseProvider.searchFoods` under Robolectric. Like the other
catalog tests it runs on desktop SQLite and the JVM's text handling, not on a phone; check a change on a device too.

`expect` is a case-insensitive regular expression that one of the first `top` results must match. A case with status
`ok` must pass. A `gap` case is a known miss: the test fails when it starts passing, so change it to `ok` then, and
also when a gap that found something when it was written finds nothing.

## Releasing a catalog change

1. Edit the curation files and run `build_catalog.py --check` until it passes.
2. Bump `DATABASE_VERSION` in `build_catalog.py` and `BuiltInDatabaseManager.EXPECTED_DATABASE_VERSION` together.
   Installed phones replace their catalog only when the version changes, and the build refuses changed content under a
   version already pinned.
3. Run `build_catalog.py`. It writes the catalog and pins the new version's fingerprint in `catalog_versions.csv`.
   While the new version is still being worked on and has not been committed or shipped, delete its row and rebuild
   after each change; once it ships, its row never changes.
4. Update the counts the app tests pin (foods, servings, food nutrients, portions) and mark newly passing search cases
   `ok`.
5. Run the Python tests and `./gradlew testDebugUnitTest`.
6. A branded catalog change follows the same steps with `build_branded.py`.

Renaming a food (an override of `name`) drops it from "Recently logged" for entries logged under the old name, and
editing those entries falls back to their logged snapshot, because both match catalog foods by name.

Serving ids stay fixed: every imported row takes one id for its `100 g` serving and one for a household unit, in file
order, whether or not it ships. INDB household portion weights came from INDB's per-unit-serving energy
(`unit_serving_energy_kcal / energy_kcal * 100`), by `tools/derive_indb_portion_weights.py`, now removed (see git
history). The derived weights are in the foods CSV.
