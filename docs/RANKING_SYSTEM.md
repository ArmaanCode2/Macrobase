# MacroBase Lifestyle Ranking System V2

## Overview

MacroBase includes a competitive-style lifestyle ranking system that measures overall nutrition and tracking consistency. The rank does not evaluate calories alone; instead, it synthesizes daily food logging consistency, calorie target adherence, macronutrient distribution, and weight trends using the health metrics MacroBase already tracks.

Water consumption is strictly excluded from all ranking calculations (0% weight).

---

## 1. Rank Ladder and Tiers

The ranking ladder contains exactly 8 ranks. Each rank has exactly 3 tiers (`I`, `II`, `III`), forming 24 distinct progression milestones.

| Rank Index | Rank Name | Tiers | Rating Score Range |
| :--- | :--- | :--- | :--- |
| 0 | Fat Bear | Fat Bear I, Fat Bear II, Fat Bear III | 0 – 299 |
| 1 | Average | Average I, Average II, Average III | 300 – 599 |
| 2 | Carb Merchant | Carb Merchant I, Carb Merchant II, Carb Merchant III | 600 – 899 |
| 3 | Protein Alchemist | Protein Alchemist I, Protein Alchemist II, Protein Alchemist III | 900 – 1199 |
| 4 | Gym Cat | Gym Cat I, Gym Cat II, Gym Cat III | 1200 – 1499 |
| 5 | Muscle Buster | Muscle Buster I, Muscle Buster II, Muscle Buster III | 1500 – 1799 |
| 6 | Fart Monster | Fart Monster I, Fart Monster II, Fart Monster III | 1800 – 2099 |
| 7 | GigaChad | GigaChad I, GigaChad II, GigaChad III | 2100 – 2399 |

- **Minimum Rating Floor**: `0` (Fat Bear I, 0 RR)
- **Maximum Rating Ceiling**: `2399` (GigaChad III, 99 RR)
- **Total Progression Range**: `2400` points (0..2399)

---

## 2. RR (Rating Rank) Points and Tier Progression

- Each tier contains exactly **100 RR** (0 to 99 RR).
- Each full rank contains **300 RR** (3 tiers x 100 RR).
- Reaching 100 RR in a tier triggers immediate promotion to the next tier with point overflow carried over.
- Dropping below 0 RR triggers demotion to the previous tier with underflow carried over.
- At the floor (`0` RR in Fat Bear I), rating cannot become negative.
- At the ceiling (`99` RR in GigaChad III), rating cannot exceed 2399.

### Promotion Example:
- Current: Fat Bear I, 95 RR
- Daily RR Delta: +10 RR
- Result: Fat Bear II, 5 RR (95 + 10 = 105 -> promoted with 5 RR carry-over)

### Demotion Example:
- Current: Average I, 4 RR
- Daily RR Delta: -10 RR
- Result: Fat Bear III, 94 RR (304 - 10 = 294 -> demoted to tier index 2 with 94 RR)

---

## 3. Core Rating Model & Component Weights

The composite Lifestyle Score (0 to 100) is evaluated as follows:

$$\text{LifestyleScore} = (\text{Consistency} \times 0.35) + (\text{CaloriePerformance} \times 0.25) + (\text{MacroBalance} \times 0.25) + (\text{WeightTrend} \times 0.15)$$

| Component | Weight | Source |
| :--- | :--- | :--- |
| Consistency | 35% | Days logged vs eligible days in the 30-day window |
| Calorie Performance | 25% | Daily average calorie intake vs target calorie goal |
| Macro Balance | 25% | Protein (40%), Carbs (30%), Fat (30%) adherence |
| Weight Trend | 15% | Body weight trajectory vs target weight |
| Water Hydration | 0% | Completely excluded from rank calculation |

---

## 4. Component Scoring Rules

### A. Consistency (35%)
- Evaluates the proportion of days logged over the rolling 30-day window.
- Score = `(loggedDaysCount / eligibleDays) * 100`.
- Sustainable regular logging is rewarded. A single missed day does not severely impact the rolling 30-day average, but high consistency alone cannot compensate for massive caloric overages.

### B. Calorie Performance (25%)
Calorie scoring penalizes both extreme caloric restriction and large caloric surpluses:

| Actual / Target Calorie Ratio | Score Range | Classification |
| :--- | :--- | :--- |
| 90% – 105% | 100.0 | Excellent adherence |
| 80% – 90% | 90.0 – 100.0 | Very good adherence |
| 105% – 115% | 75.0 – 90.0 | Good / moderate overage (e.g. 200 kcal over) |
| 70% – 80% | 60.0 – 90.0 | Below target, moderate |
| 115% – 125% | 50.0 – 75.0 | Moderate penalty |
| 50% – 70% | 25.0 – 60.0 | Strong penalty (undereating) |
| 125% – 150% | 15.0 – 50.0 | Strong penalty (surplus) |
| 25% – 50% | 0.0 – 25.0 | Very strong penalty (crash dieting) |
| 150% – 200% | 0.0 – 15.0 | Very strong penalty |
| < 25% or > 200% | 0.0 | Floor (e.g. 6000 kcal against 2000 kcal goal) |

### C. Macro Balance (25%)
Evaluates protein, carbohydrate, and fat intake against user-configured targets:
- $\text{MacroBalanceScore} = (\text{ProteinScore} \times 0.40) + (\text{CarbScore} \times 0.30) + (\text{FatScore} \times 0.30)$
- **Protein Scoring**:
  - 90% – 110% of target: 100.0
  - 80% – 90% or 110% – 125%: 85.0
  - 65% – 80% or 125% – 140%: 60.0
  - 45% – 65% or 140% – 175%: 30.0
  - 20% – 45% or 175% – 225%: 10.0
  - < 20% or > 225%: 0.0
- **Carbohydrates & Fat Scoring**:
  - 85% – 115% of target: 100.0
  - 70% – 85% or 115% – 130%: 75.0
  - 50% – 70% or 130% – 160%: 40.0
  - 25% – 50% or 160% – 200%: 15.0
  - < 25% or > 200%: 0.0

### D. Weight Trend (15%)
Evaluates progress toward the user's configured target weight:
- **Loss Target** (`currentWeight > targetWeight + 0.5kg`):
  - Trending down toward target: 100.0
  - Stable: 70.0
  - Trending away: 20.0
- **Gain Target** (`currentWeight < targetWeight - 0.5kg`):
  - Trending up toward target: 100.0
  - Stable: 70.0
  - Trending away: 20.0
- **Maintain Target** (`|currentWeight - targetWeight| <= 0.5kg`):
  - Stable (|delta| <= 0.5kg): 100.0
  - Mild drift (|delta| <= 1.0kg): 70.0
  - Large drift: 40.0
- **Missing or Insufficient Data**:
  - If fewer than 2 weight entries exist, the weight component defaults to a neutral `50.0` rather than 0.

---

## 5. Evaluation Window & Provisional Period

1. **Rolling Window**: Primary evaluation spans the previous 30 calendar days.
2. **Provisional Status** (`0 – 6 logged days`):
   - User is marked as `PROVISIONAL`.
   - RR movement is held at 0 so a user cannot jump to high ranks from 1 or 2 lucky days.
   - Display shows: `"Keep logging for 7 days to establish your first rating."`
3. **Reduced Movement** (`7 – 13 logged days`):
   - RR movement is dampened by 50% (`factor = 0.5`).
4. **Full Rating Movement** (`14+ logged days`):
   - Full RR movement takes effect.

---

## 6. Daily RR Movement Mapping

- A lifestyle score of `60.0` represents neutral baseline (0 RR delta).
- Scores above 60.0 generate positive RR up to `+25 RR` maximum per evaluation.
- Scores below 60.0 generate negative RR down to `-25 RR` maximum per evaluation.
- Mapping formula:

$$\text{RR Delta} = \text{clamp}\left(\text{round}((\text{LifestyleScore} - 60) \times 0.6), -25, +25\right) \times \text{Dampening}$$

### Examples:
- LifestyleScore = 90: $\Delta \text{RR} = (90 - 60) \times 0.6 = +18$
- LifestyleScore = 75: $\Delta \text{RR} = (75 - 60) \times 0.6 = +9$
- LifestyleScore = 60: $\Delta \text{RR} = (60 - 60) \times 0.6 = 0$
- LifestyleScore = 50: $\Delta \text{RR} = (50 - 60) \times 0.6 = -6$
- LifestyleScore = 40: $\Delta \text{RR} = (40 - 60) \times 0.6 = -12$
- LifestyleScore = 20: $\Delta \text{RR} = (20 - 60) \times 0.6 = -24$

---

## 7. Water Hydration Exclusion Guarantee

Water tracking data (`WaterRepository`, `WaterEntry`, hydration goals) is strictly isolated and never injected into the ranking domain or repository layer. Changes to water intake have zero effect on `LifestyleScore`, `RRDelta`, or `RankTier`.

---

## 8. Scientific Evidence & Design Principles

The MacroBase Lifestyle Ranking model is informed by published behavioral nutrition and digital health literature:
1. **Consistency of Self-Monitoring**: Systematic reviews (Burke et al., 2011; Beleigoli et al., 2019; Berry et al., 2021) demonstrate that regular self-monitoring frequency is strongly correlated with dietary adherence and weight management success.
2. **Calorie Adherence Range vs. Crash Dieting**: Evidence shows that sustainable caloric deficits (10–20%) lead to superior long-term adherence and fat loss compared to severe restriction (<50% of TDEE), which correlates with metabolic slowdown, binge-restrict cycles, and high dropout rates.
3. **Calibrated Feedback Loops**: Clear, bidirectional feedback (earning RR for consistency/adherence, losing RR for significant overages) provides positive reinforcement without false praise for catastrophic surplus days.

**Key Citations**:
- Burke, L. E., Wang, J., & Sevick, M. A. (2011). *Self-monitoring in weight loss: a systematic review of the literature*. Journal of the American Dietetic Association, 111(1), 92–102.
- Beleigoli, A. M., et al. (2019). *Web-Based Digital Health Interventions for Weight Loss and Lifestyle Habit Changes in Overweight and Obese Adults: Systematic Review and Meta-Analysis*. Journal of Medical Internet Research, 21(1), e11989.
- Berry, R., et al. (2021). *Dietary self-monitoring and long-term weight management in digital health interventions: systematic review*. PMC8279441.
- Patel, M. L., et al. (2021). *Evaluating adherence metrics in digital health weight-loss interventions*. Ann Behav Med, 55(11), 1083–1092.

