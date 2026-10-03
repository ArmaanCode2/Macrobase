# MacroBase Calculation Engine Specification

This document provides the definitive mathematical specification for all nutrition calculations, portion scaling algorithms, recipe formulations, and adherence metrics in MacroBase.

---

## 1. Portion Serving Multiplier Engine

Every food item in MacroBase is scaled dynamically based on its source (`FoodSource.BUILT_IN` vs. `FoodSource.CUSTOM_USER` / `FoodSource.RECIPE`), portion definition, and user-entered quantity.

### 1.1. Built-in Food Scaling (`Serving.calculateGramMultiplier`)
Built-in catalog foods from `built_in_foods.db` store nutritional data standardized to a **100g** (or 100mL) baseline.

```kotlin
fun calculateGramMultiplier(userQuantity: Double): Double {
    if (userQuantity <= 0.0) return 0.0
    val effectiveQuantity = if (quantity == 100.0 && gramWeight == 100.0) 1.0 else quantity
    if (gramWeight > 0.0 && effectiveQuantity > 0.0) {
        val totalGrams = (gramWeight / effectiveQuantity) * userQuantity
        return totalGrams / 100.0
    }
    if (effectiveQuantity > 0.0) {
        return userQuantity / effectiveQuantity
    }
    return 1.0
}
```

#### Mathematical Formulation
1. If $\text{userQuantity} \le 0 \implies \text{multiplier} = 0.0$.
2. Normalized Portion Quantity:
   $$\text{effectiveQuantity} = \begin{cases} 1.0 & \text{if } \text{quantity} = 100.0 \land \text{gramWeight} = 100.0 \\ \text{quantity} & \text{otherwise} \end{cases}$$
3. When gram weight is known ($\text{gramWeight} > 0$):
   $$\text{totalGrams} = \left(\frac{\text{gramWeight}}{\text{effectiveQuantity}}\right) \times \text{userQuantity}$$
   $$\text{multiplier} = \frac{\text{totalGrams}}{100.0}$$
4. When gram weight is unknown ($\text{gramWeight} \le 0$):
   $$\text{multiplier} = \frac{\text{userQuantity}}{\text{effectiveQuantity}}$$

---

### 1.2. Custom Food Scaling (`CalculateNutritionForServingUseCase`)
User-created custom foods represent nutrition for **1 base portion** (e.g. 1 bowl, 1 slice, 100g), rather than a 100g standardized catalog reference.

```kotlin
class CalculateNutritionForServingUseCase {
    operator fun invoke(food: Food, serving: Serving, userQuantity: Double): Nutrition {
        if (userQuantity <= 0.0) return Nutrition.ZERO
        val multiplier = if (food.isUserOwned || food.source == FoodSource.CUSTOM_USER) {
            val defaultServing = food.servings.firstOrNull { it.isDefault } 
                ?: food.servings.firstOrNull() 
                ?: serving

            if (serving.id == defaultServing.id || serving.description.equals(defaultServing.description, ignoreCase = true)) {
                userQuantity
            } else if (serving.gramWeight > 0.0 && defaultServing.gramWeight > 0.0) {
                userQuantity * (serving.gramWeight / defaultServing.gramWeight)
            } else if (defaultServing.quantity > 0.0 && serving.quantity > 0.0 && serving.quantity != defaultServing.quantity) {
                userQuantity * (serving.quantity / defaultServing.quantity)
            } else {
                userQuantity
            }
        } else {
            serving.calculateGramMultiplier(userQuantity)
        }
        return food.nutrition.scale(multiplier)
    }
}
```

#### Mathematical Formulation for Custom Foods
- Default Serving Baseline ($\text{serving} = \text{defaultServing}$):
  $$\text{multiplier} = \text{userQuantity}$$
  *Example*: User logs 1 serving of Granola (serving size 100g, 350 kcal): $\text{userQuantity} = 1.0 \implies \text{multiplier} = 1.0 \implies 350\text{ kcal}$.
- Sub-portion Gram/ML Scaling (e.g., selecting `"1 g"` secondary serving):
  $$\text{multiplier} = \text{userQuantity} \times \left(\frac{\text{serving.gramWeight}}{\text{defaultServing.gramWeight}}\right)$$
  *Example*: User logs 50g of Granola using the `"1 g"` portion ($\text{gramWeight} = 1.0$, $\text{defaultServing.gramWeight} = 100.0$):
  $$\text{multiplier} = 50.0 \times \left(\frac{1.0}{100.0}\right) = 0.50 \implies 175\text{ kcal}$$

---

## 2. Nutrition Model Arithmetic & Net Carbs

### 2.1. Scaling Nutrition (`Nutrition.scale`)
All macronutrients and nullable micronutrients are multiplied by the scalar factor:
$$\text{scaled}(N) = N \times \text{multiplier}$$
If a micronutrient is `null`, it remains `null`:
$$\text{null} \times \text{multiplier} = \text{null}$$

### 2.2. Summing Nutrition (`Nutrition.plus`)
$$\text{Calories}_{total} = \text{Calories}_A + \text{Calories}_B$$
$$\text{Protein}_{total} = \text{Protein}_A + \text{Protein}_B$$
$$\text{Carbs}_{total} = \text{Carbs}_A + \text{Carbs}_B$$
$$\text{Fat}_{total} = \text{Fat}_A + \text{Fat}_B$$

#### Nullable Micronutrient Summation (`addNullable`)
$$\text{addNullable}(a, b) = \begin{cases} \text{null} & \text{if } a = \text{null} \land b = \text{null} \\ (a \text{ ?: } 0.0) + (b \text{ ?: } 0.0) & \text{otherwise} \end{cases}$$

### 2.3. Net Carbohydrates Formula
$$\text{Net Carbs} = \max\Big(0.0, \, \text{Total Carbs} - (\text{Dietary Fiber} \text{ ?: } 0.0)\Big)$$
Implemented in Kotlin as:
```kotlin
val netCarbsGrams: Double
    get() = (carbsGrams - (fiberGrams ?: 0.0)).coerceAtLeast(0.0)
```

---

## 3. Recipe Formulations

### 3.1. Total Recipe Nutrition
A recipe consists of $N$ ingredients, each defined by a food item, selected serving, and quantity:
$$\text{Total Nutrition}_{recipe} = \sum_{i=1}^{N} \text{CalculateNutritionForServingUseCase}(\text{food}_i, \text{serving}_i, \text{quantity}_i)$$

### 3.2. Per-Serving Recipe Nutrition
Divided by the integer number of servings produced:
$$\text{Nutrition Per Serving} = \text{Total Nutrition}_{recipe} \cdot \left(\frac{1.0}{\text{servingsProduced}}\right)$$

---

## 4. Daily Dashboard Aggregation & Metrics

Given a collection of $M$ diary entries logged for a date:

### 4.1. Core Totals
$$\text{Intake Calories} = \sum_{j=1}^{M} \text{entry}_j.\text{calculatedNutrition.calories}$$
$$\text{Intake Protein} = \sum_{j=1}^{M} \text{entry}_j.\text{calculatedNutrition.proteinGrams}$$
$$\text{Intake Carbs} = \sum_{j=1}^{M} \text{entry}_j.\text{calculatedNutrition.carbsGrams}$$
$$\text{Intake Fat} = \sum_{j=1}^{M} \text{entry}_j.\text{calculatedNutrition.fatGrams}$$
$$\text{Intake Fiber} = \sum_{j=1}^{M} (\text{entry}_j.\text{calculatedNutrition.fiberGrams} \text{ ?: } 0.0)$$
$$\text{Intake Sugar} = \sum_{j=1}^{M} (\text{entry}_j.\text{calculatedNutrition.sugarGrams} \text{ ?: } 0.0)$$
$$\text{Intake Sodium} = \sum_{j=1}^{M} (\text{entry}_j.\text{calculatedNutrition.sodiumMg} \text{ ?: } 0.0)$$

### 4.2. Calorie Energy Contribution & Macro Split
Standard Atwater energy factors:
- Protein: $4\text{ kcal/g}$
- Carbohydrates: $4\text{ kcal/g}$
- Fat: $9\text{ kcal/g}$

$$\text{Cals}_P = \text{Protein} \times 4$$
$$\text{Cals}_C = \text{Carbs} \times 4$$
$$\text{Cals}_F = \text{Fat} \times 9$$
$$\text{Total Macro Cals} = \text{Cals}_P + \text{Cals}_C + \text{Cals}_F$$

$$\text{Protein } \% = \frac{\text{Cals}_P}{\text{Total Macro Cals}} \times 100\%$$
$$\text{Carbs } \% = \frac{\text{Cals}_C}{\text{Total Macro Cals}} \times 100\%$$
$$\text{Fat } \% = \frac{\text{Cals}_F}{\text{Total Macro Cals}} \times 100\%$$

### 4.3. Calorie Balance & Remaining
$$\text{calorieBalance} = \text{calorieGoal} - \text{totalCaloriesIntake}$$
$$\text{isOverBudget} = \text{totalCaloriesIntake} > \text{calorieGoal}$$
$$\text{remainingCalories} = \max(0.0, \, \text{calorieBalance})$$
$$\text{overBudgetAmount} = \max(0.0, \, \text{totalCaloriesIntake} - \text{calorieGoal})$$

---

## 5. Adherence & Performance Classification

Defined in `CalendarPerformanceConfig.evaluatePerformance(calories, goal)`:

| Performance Category | Condition | UI Color Token | Meaning |
| :--- | :--- | :--- | :--- |
| **`EMPTY_MISSED`** | $\text{calories} \le 0.0 \lor \text{goal} \le 0.0$ | `CalendarEmpty` (`#262626`) | Unlogged or empty day |
| **`UNDER_BUDGET`** | $0.0 < \frac{\text{calories}}{\text{goal}} \le 0.85$ | `CalendarGreenSubtle` (`#1B5E20`) | Below 85% of budget |
| **`OPTIMAL_TARGET`** | $0.85 < \frac{\text{calories}}{\text{goal}} \le 1.05$ | `CalendarGreenOptimal` (`#00C853`) | On target (85% – 105%) |
| **`MODERATE_OVER`** | $1.05 < \frac{\text{calories}}{\text{goal}} \le 1.20$ | `CalendarRedWarning` (`#FFA000` / `#E53935`) | Slightly over budget (105% – 120%) |
| **`HIGH_OVER_TARGET`**| $\frac{\text{calories}}{\text{goal}} > 1.20$ | `CalendarRedAlert` (`#B71C1C`) | High over target (> 120%) |

### 5.1. Consistency Score
For any date interval $[D_{start}, D_{end}]$:
$$\text{Eligible Days} = \big|\{d \in [D_{start}, D_{end}] : d \le \text{today}\}\big|$$
$$\text{Logged Days} = \big|\{d \in [D_{start}, D_{end}] : d \le \text{today} \land \text{calories}_d > 0\}\big|$$
$$\text{Consistency Score} = \text{round}\left(\frac{\text{Logged Days}}{\text{Eligible Days}} \times 100\right)$$

### 5.2. % Days of Green
$$\text{Optimal Days} = \big|\{d \in [D_{start}, D_{end}] : d \le \text{today} \land 0.85 < \frac{\text{calories}_d}{\text{goal}} \le 1.05\}\big|$$
$$\text{Green Percentage} = \text{round}\left(\frac{\text{Optimal Days}}{\text{Logged Days}} \times 100\right)$$

---

## 6. Rounding Invariants

1. **Integer Display**: Always use `kotlin.math.roundToInt()` (or `round().toInt()`) instead of `.toInt()`.
   $$\text{roundToInt}(1999.8) = 2000, \quad 1999.8.\text{toInt}() = 1999 \text{ [FATAL TRUNCATION]}$$
2. **Decimal Formatting**: Standardize formatting with explicit US locale:
   ```kotlin
   String.format(Locale.US, "%.1f", value)
   ```
