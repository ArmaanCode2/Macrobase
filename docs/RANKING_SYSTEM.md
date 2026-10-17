# MacroBase Ranking System (rules version 4)

The rank rewards days eaten inside the calorie zone of the user's strategy, with good food. The
code is `core/config/RankScoringConfig.kt` (rules) and `data/repository/rank/RankRepositoryImpl.kt`
(replay). Water and body weight do not affect the rank.

---

## 1. Rank ladder (unchanged)

| Rank | Rating | Tiers |
| :--- | :--- | :--- |
| Fat Bear | 0 – 299 | I, II, III |
| Average | 300 – 599 | I, II, III |
| Carb Merchant | 600 – 899 | I, II, III |
| Protein Alchemist | 900 – 1199 | I, II, III |
| Gym Cat | 1200 – 1499 | I, II, III |
| Muscle Buster | 1500 – 1799 | I, II, III |
| Fart Monster | 1800 – 2099 | I, II, III |
| GigaChad | 2100 – 2399 | I, II, III |

Each tier holds 100 RR. The rating stays between 0 and 2,399.

---

## 2. A day's score

    day score = calorie credit (0 – 1) x food score (0 – 100)

### 2.1 Calorie credit

Judged against the **maintenance calories and strategy in force that day**. The daily calorie goal
is not used: strategy and maintenance changes start the next day, so they cannot be edited to suit
a day already eaten. Protein and the carb and fat goals do follow the targets saved for the day, as
the dashboard does (BUG-024), so editing them late in a day changes that day's food score.

| Strategy | Full credit | Credit fades (straight line) | No credit |
| :--- | :--- | :--- | :--- |
| Cutting | 10 – 25% below maintenance | 5 – 10% and 25 – 35% below | within 5% of maintenance, above it, or more than 35% below |
| Bulking | 10 – 20% above maintenance | 5 – 10% and 20 – 30% above | within 5% of maintenance, below it, or more than 30% above |
| Maintaining | within 5% of maintenance | 5 – 10% away | more than 10% away |

Example, cutting with 2,000 kcal maintenance: 1,500 – 1,800 kcal is full credit, 1,850 kcal half,
1,900 kcal or more none, 1,300 kcal or less none.

### 2.2 Food score

    food score = 40% protein + 45% food quality + 15% carb and fat balance

| Part | Full marks | Zero |
| :--- | :--- | :--- |
| Protein | the protein goal up to 1.5x it (80 at 2x) | half the goal or less |
| Carb and fat balance | each within 20% of its gram goal | 50% off |

Food quality, from the Dietary Guidelines for Americans:

| Measure | Share | Full marks | Zero |
| :--- | :--- | :--- | :--- |
| Fiber | 40% | 14 g or more per 1,000 kcal | 4 g or less per 1,000 kcal |
| Saturated fat | 25% | 10% of calories or less (15% scores 40) | 20% or more |
| Sodium | 20% | 2,300 mg a day or less | 4,000 mg or more (the known foods' sodium is scaled up to the whole day) |
| Total sugar | 15% | 20% of calories or less | 35% or more |

The diary stores total sugar, fruit and milk included, not the added sugar the guideline limits,
so the sugar line is lenient.

**Unknown values.** Each measure is judged on the entries that state it. The share of the day's
calories from entries that do not counts as 50. A logged 0.0 is a real zero (AGENTS.md 2.4).
Entries logged before the diary kept these nutrients borrow them from the built-in food they logged,
scaled by the entry's calories, when the food's name still matches. Built-in food ids are large
hashes; ids in the custom food and recipe ranges are never looked up. This is read only: diary
entries are never changed.

Known limits:
- Custom foods state saturated fat from database version 5 on. A custom food saved earlier has it
  unknown until the user edits the food. Entries already logged from it keep their snapshot, so
  their saturated fat stays unknown, unless the user opens such an entry and saves it: Edit Mode
  then fills unknown nutrients from the food as it is now (AGENTS.md 5.3).
- A recipe stores the sum of what its ingredients state. When only some ingredients state a
  nutrient, the recipe looks fully covered: fiber then reads low, and saturated fat, sodium and sugar
  read better than they are. A recipe that mixes custom foods saved before and after version 5
  often does this for saturated fat.

---

## 3. RR per day

Each rating expects a day score, rising smoothly between these points:

| Rating | 0 | 300 | 600 | 900 | 1200 | 1500 | 1800 | 2100 | 2399 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| Expected score | 40 | 50 | 58 | 65 | 72 | 79 | 85 | 90 | 94 |

    RR = round((day score - expected score) x 0.8), between -15 and +20

- A day with no calorie credit scores 0, so it always gives -15 RR.
- A missed day gives **-20 RR**, more than any logged day can lose: logging an honest bad day
  always beats skipping it.
- Because the expectation rises with the rating, a steady day score settles inside a tier.

**Clean week.** Moving up into Fart Monster (1,800) or GigaChad (2,100) needs a clean week ending
within the last 14 days: 7 days in a row with at most one off day. A day is off when it is not
logged or has less than half the calorie credit, so skipping a bad day does not help here either.
Without a clean week the rating stops at the top of the rank below; the Rank screen says so under
the RR bar and on the days that were held.

---

## 4. Replay and today

- The rating is replayed from the first logged day over the whole diary (BUG-023), each day against
  the goal in force that day (BUG-024). Nothing about the rank is stored, so a rules change
  recalculates every rank; the Rank screen then shows a one-time notice (`RULES_VERSION`).
- The rank counts only days from the first day with a saved goal
  (`GoalsRepository.getGoalHistoryStart`): the earliest dated record in the goal history, not the
  `2000-01-01` record that stands in for the days before the first change. Goal history began
  with the 10 Oct 2026 code, so an earlier day's goal is only a guess and the day is left out.
  A history with no saved day up to today, empty included, gets today's goal saved on the next
  read, so the rank counts from that day. Left-out days stay in the diary and in Statistics. The
  score card, the logged-day count, the provisional days and the clean week all start there too,
  and the last history page names the day ("Your rank counts from Oct 10, the first day with a
  saved goal."), with the year when it is not this year. If that left out every logged day, the
  rank shows the one-time notice anyway. Backups carry the goal history, so a restore keeps the day; a backup without
  history (before format 1.2.0, or an empty one) leaves the rank counting from the restore.
- Nothing moves until 7 logged days have ended (provisional); then every day counts, the first ones
  included.
- **Seasons.** The rank starts again from Fat Bear I every year on the anniversary of the first
  logged day (`RankSeason`): a first log on 29 Aug 2026 resets on 29 Aug 2027, then every 29 Aug.
  A first log on 29 Feb resets on 28 Feb in other years. The date comes from the diary, so nothing
  is stored and a restored backup keeps it. Each season is replayed on its own from its first
  logged day, with 7 provisional days again, so the reset day shows that day's own RR and never a
  drop from last season's rating. The rank screen history covers the current season only. The
  rank counts from the later of the season start and the first day with a saved goal.
- **Best rank.** The rank card shows the highest rank and tier of the current season and the first
  day that rank and tier were reached ("Best this season: Gym Cat II • Sep 3"), once the rating
  is established and above 0. After a reset a one-time "New season" notice names last season's
  best, when that season had one; dismissing it stores the season's start
  (`seen_rank_season_start`), so it returns only after the next reset. It is not in backups, so a
  restore on another phone shows it once more.
- **Rank History (Statistics).** A fifth Statistics tab draws the rating after each day that ended
  (`RankProfile.ratingHistory`, every season, each replayed on its own) for 30 days, 90 days, this
  season or all. The height covers only the ranks the line reaches, with a line where each rank
  starts; a dashed "Reset" line marks the day a new season started; tapping the line names the
  day ("Oct 12 • Fat Bear I • 83 RR", with the year for another year's day). A season is drawn
  only once it has 7 logged days, so the first days after a reset are not a line at 0 RR. Below
  it: this season's best rank and each past season with its best ("Aug 29, 2026 – Aug 28, 2027
  • best Gym Cat II"); a year with nothing logged is not listed. Before the first rank is
  established it says "Log 7 days to get your first rank." The tab builds the rank only while it
  is on screen.
- The season start follows the diary: food logged on a wrong old date (a year back) moves the reset
  until that entry is deleted, and deleting the first logged days moves it later.
- The rank ladder (the i button) says when the rank resets and how many days are left ("Resets on
  Aug 29, 2027 • 319 days left"); before anything is logged it says the rank resets one year
  after the first logged day.
- Today is in progress (BUG-032): its RR is a preview and enters the rating after midnight. While
  today's calories are below the zone and still earn no credit, the screen shows "In progress"
  instead of the preview.
- The score card covers the last 30 days that have ended, from the first counted day: protein, food
  quality, balance and calorie credit are averaged over the logged days; the headline day score is
  averaged over every day, a missed day counting 0. It judges the food, not the rank, so a new
  season does not empty it.
- The history lists every counted day, 30 to a page: the first page is today and the 29 days
  before it, `>` shows the 30 days before those and `<` goes back. Each row names the day
  ("Today", "Yesterday", "Oct 11"), the rank and RR after it, and the RR change. A third line says
  why when the day lost RR, or would have at the bottom of the ladder: "Missed day", "Calories
  outside your zone", or the part that cost the most points ("Calories off target", "Protein off
  target", "Food quality low", "Carbs and fat off target"). A gain held for a clean week says "Next
  rank needs a clean week". Today gets a reason only when its preview is a loss and its calories
  are no longer below the zone. The `<  Sep 15 – Oct 14  >` row under the list names the page;
  the screen opens on the first page.

---

## 5. Sources

- Helms, Aragon & Fitschen (2014), *Evidence-based recommendations for natural bodybuilding contest
  preparation*, JISSN 11:20: weight loss of about 0.5 – 1% of body weight a week.
- Iraki, Fitschen, Espinar & Helms (2019), *Nutrition Recommendations for Bodybuilders in the
  Off-Season*, Sports 7(7):154: a surplus of about 10 – 20% above maintenance.
- Dietary Guidelines for Americans 2020 – 2025: added sugar and saturated fat under 10% of calories,
  sodium under 2,300 mg a day. Dietary Guidelines 2010: fiber 14 g per 1,000 kcal.
