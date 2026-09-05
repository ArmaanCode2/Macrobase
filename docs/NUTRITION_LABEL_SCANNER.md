# MacroBase Nutrition Label Scanner

## 1. Overview & Canonical Philosophy

The MacroBase Nutrition Label Scanner is an offline-first, on-device computer vision and tabular parsing pipeline that extracts nutritional facts from consumer food packaging and pre-fills user custom food entries.

### The Canonical Basis Rule

**MacroBase's canonical nutrition representation for all scanned food products is strictly: PER 100 GRAMS.**

Every nutrient value is normalized to a 100g basis before it enters the Custom Food database. From this canonical `PER_100_G` representation, a derived `PER_1_G` representation (`per100g / 100.0`) is computed dynamically in code.

The user review UI offers a clean toggle:
- `[ Per 100 g ]`: Populates Custom Food with serving size `100 g` and canonical 100g values.
- `[ Per 1 g ]`: Populates Custom Food with serving size `1 g` and derived 1g values.

---

## 2. Supported Packaging Bases

```kotlin
enum class NutritionBasis(val displayName: String) {
    PER_100_G("Per 100g"),       // Authoritative canonical food basis
    PER_1_G("Per 1g"),           // Derived canonical micro-basis
    PER_SERVING("Per Serving"),   // Package-defined portion
    PER_100_ML("Per 100ml"),     // Volume-based liquids
    PER_PACKAGE("Per Package"),   // Entire container
    UNKNOWN("Unknown Basis")      // Unresolved
}
```

---

## 3. Source Basis Detection & Priority Hierarchy

When a label contains multiple columns or declarations, the parser evaluates them in strict order of priority:

1. **Explicit Per 100 g**: Authoritative. Direct printed values are adopted with zero recalculation.
2. **Explicit Per 100 ml**: Adopted for liquid products. Never converted to grams assuming $1\text{ ml} = 1\text{ g}$ unless explicit density is supplied.
3. **Per Serving (with verified gram mass)**: Normalized to `PER_100_G` using the exact serving mass in grams.
4. **Per Portion (with verified gram mass)**: Normalized to `PER_100_G`.
5. **Per Package / Container (with verified gram mass)**: Normalized to `PER_100_G`.
6. **Unknown / Serving without gram weight**: Kept as `PER_SERVING`; cannot normalize to `PER_100_G`. Marked `needsReview = true`.

---

## 4. Serving Normalization Mathematics

For labels printed exclusively per serving, each nutrient is independently scaled to canonical 100g:

$$\text{Factor} = \frac{100.0}{\text{servingMassG}}$$
$$\text{normalizedPer100g} = \text{round}_2(\text{sourceValue} \times \text{Factor})$$
$$\text{normalizedPer1g} = \text{round}_4\left(\frac{\text{normalizedPer100g}}{100.0}\right)$$

### Examples

| Printed on Label | Serving Size | Normalized Per 100g | Derived Per 1g |
|---|---|---|---|
| Calories: 100 kcal | 20 g | **500.0 kcal** | **5.0 kcal** |
| Protein: 4.0 g | 20 g | **20.0 g** | **0.20 g** |
| Carbohydrates: 10.0 g | 20 g | **50.0 g** | **0.50 g** |
| Fat: 3.0 g | 20 g | **15.0 g** | **0.15 g** |
| Protein: 9.6 g | 32 g | **30.0 g** | **0.30 g** |
| Calories: 230 kcal | 1/2 cup (65 g) | **353.8 kcal** | **3.54 kcal** |

---

## 5. Dual Column Cross-Check & Discrepancy Detection

When a package prints **both** *Per 100g* and *Per Serving* columns:
1. The **Per 100g** column is authoritative and adopted directly for the canonical draft (`isDerived = false`).
2. The **Per Serving** column is retained in `draft.perServingValues`.
3. The parser cross-checks consistency:

$$\left| (\text{per100g} \times \frac{\text{servingMassG}}{100.0}) - \text{perServing} \right| \le \max(2.0, \text{perServing} \times 0.20)$$

If values conflict beyond label rounding tolerance, the draft flags `needsReview = true` and emits an explanatory warning.

---

## 6. Protection Guarantees

### A. Double-Conversion Protection
Every nutrient tracks its `sourceBasis` and `currentBasis`. If a value is already canonical `PER_100_G`, re-normalization is strictly blocked:
```kotlin
fun safeScaleToPer100g(current: ParsedNutrientValue, servingMassG: Double?): ParsedNutrientValue {
    if (current.basis == NutritionBasis.PER_100_G || current.sourceBasis == NutritionBasis.PER_100_G) {
        return current // Never scale twice!
    }
    return normalizeToPer100g(...)
}
```

### B. Daily Value (%RDA / %DV) Protection
Numbers followed by `%` or aligned under Daily Value headers are strictly categorized as `ColumnType.RDA`. Nutrient quantities (e.g. `28g`, `500mg`) are never mistakenly assigned to the RDA column.

### C. No Invented Serving Weights
If a label states `"Serving Size: 1 bar"` without gram mass:
- The system **never** invents an arbitrary mass (e.g. 30g, 40g).
- `normalizedPer100g` and `normalizedPer1g` remain `null`.
- `detectedBasis` remains `PER_SERVING`.
- `needsReview = true` is set, guiding the user to provide the serving weight.

### D. Energy Disambiguation (kJ vs. kcal)
- If both `kJ` and `kcal` are visible, `kcal` is adopted directly as canonical energy.
- If only `kJ` is visible, $\text{kcal} = \frac{\text{kJ}}{4.184}$ is computed, marked `isEstimated = true` and `needsReview = true`.

### E. Micrograms & Salt Conversions
- Salt is converted to sodium: $\text{Sodium (mg)} = \frac{\text{Salt (g)}}{2.54} \times 1000$.
- Microgram units (`mcg`, `µg`, `μg`) are converted to milligrams.

### F. Spatial Column Grid & Table Orientation Validation
- Multi-column tables (e.g., Indian FSSAI labels with `Per 100g`, `Per Serving (30g)`, `Per Serving %RDA`) lock horizontal column coordinates across the whole table.
- Element-to-column mapping assigns values based on horizontal spatial distance rather than raw reading order.
- Table-wide orientation scoring cross-checks the mathematical relationship ($C_{100} \times \frac{\text{serving}}{100} \approx C_{\text{serv}}$) and auto-corrects inverted columns.

### G. Comparison Operators & Threshold Semantics
- Values with comparison operators such as `<1 mg` or `<0.3 mg` preserve their semantics via `ComparisonOperator.LESS_THAN` and `sourceValue = 1.0`.
- Macro amounts are conservatively estimated at half the threshold (`0.5 mg`) for caloric balance while flagging `isEstimated = true`.

### H. Contextual Decimal Loss Recovery
- Detects dropped decimal points (e.g., `77 g` instead of `7.7 g` alongside `2.3 g` per 30g serving) and automatically recovers the original value when $\frac{\text{val}}{10} \times \frac{\text{serving}}{100} \approx \text{perServing}$.

### I. %RDA / %DV Column Isolation
- Daily Value percentages are isolated into `draft.rdaValues` (e.g., `rdaValues["calories"] = 6.4`, `rdaValues["protein"] = 40.9`) and never pollute primary nutrient gram amounts.

---

## 7. Interactive Review UI Flow

Before sending scanned data to the Custom Food form:
1. **Basis Selector**: User toggles between `[ Per 100 g ]` and `[ Per 1 g ]`.
2. **Transparency Banner**: If normalized from serving size, displays:
   `"ℹ Normalized from 32 g serving to per 100 g."`
3. **Field Accordion**: Tapping any field shows:
   - Original printed value: e.g. `9.6 g (Per 32 g Serving)`
   - Raw OCR snippet: `"Protein 9.6g"`
   - Canonical Per 100g: `30.0 g`
   - Derived Per 1g: `0.30 g`
   - Comparison Operator: e.g. `< 1 mg`
   - Confidence level (`HIGH`, `MEDIUM`, `LOW`) and extraction method.
4. **Apply Action**: Populates `EditCustomFoodScreen` with the chosen basis (`100g` or `1g`), allowing full manual editing before saving.
