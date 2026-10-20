# MacroBase UI/UX Specification: the Mentor design

This describes the app's look from version 1.2.6, the "Mentor" design. Until 1.2.5 the app used a flat Material dark theme; that version of this document is in git history.

The values below are the ones in the code. When they disagree with this file, the code wins:

- Tokens: `core/designsystem/Color.kt` (`AppColors`, `AppBrushes`), `Typography.kt` (`AppTypography`), `Shape.kt` (`AppShapes`), `Spacing.kt` (`AppSpacing`), `Dimensions.kt`.
- Components: `core/designsystem/components/` (`Mb*` composables).
- Motion: `MbMotion` in `MbFoundation.kt`.

The design was ported from an HTML demo. Its working notes (`design.txt` and `implementation-phases.txt`) live outside the repo, in `designs/`, next to the project folder.

## 1. Principles

- Dark only, with no light mode and no theme picker. The canvas is pure black (`#000000`), so on AMOLED screens its pixels are off.
- One slow-moving green glow (the aura) sits behind the top of every screen except the scanner.
- Panels and rows are outlined and faintly tinted, with large rounded corners. There is no Material elevation on cards; only floating pieces (bar, view switch, "+", sheets) cast a shadow.
- One typeface, Jura, in Light (300) and Medium (500), in sentence case everywhere. The widget title "MACROBASE" is the one exception.
- Look only: the redesign changed no database table, calculation or backup format.

## 2. Colour

| Token | Value | Use |
|---|---|---|
| `Background` | `#000000` | Page canvas; also the window, status bar and navigation bar (`themes.xml`) |
| `Surface`, `SheetFill` | `#0A0D0B` | Sheets and dialogs |
| `PanelFill` + `AppBrushes.PanelTint` | white 1.8%, green tint at the top left | Panels |
| `RowTile` | `#0B0F0C` | Opaque rows that hide what is behind them (swipe rows) |
| `Edge` / `Edge2` | `rgba(150,255,170,0.10)` / `0.22` | Outlines of panels and rows / icon circles and sheets |
| `PillFill`, `PillInnerLine` | `#0D120F`, white 8% | Bottom bar, view switch, pinned basket backing |
| `Capsule`, `CapsuleBorder` | `#17261C`, `rgba(91,216,106,0.28)` | Behind the active tab or view |
| `NavActive` / `NavInactive` | `#6FE07C` / `#9AA39C` | Tab and view text and icons |
| `Primary` (`Accent`) | `#5BD86A` | Active states, links, ticks, focus |
| `OnPrimary` | `#050A06` | Ink on green fills (light text on green is only about 1.6:1) |
| `AccentStrong` | `#84E48F` | Rank name, emphasised values |
| `TextPrimary` / `TextSecondary` / `TextMuted` | `#EDF2EE` / `#A7B0A9` / `#838D85` | Text |
| `MacroProtein` / `MacroCarbs` / `MacroFat` | `#5BD86A` / `#D3F444` / `#33CB9E` | Macro rings and legends |
| `WaterCyan` | `#3FD0C9` | Water |
| `Error`, `ProgressOver` | `#FF6B5E` | Errors, "over", negative RR |
| `DangerFill` / `CopyFill` | `#B5372D` / `#7A4A12` | Swipe Delete / Copy, filled delete buttons |
| `Badge`, `BadgeInk` | `#D3F444`, `#0B1A06` | Basket count |
| `HeaderBand` | `#070907` + a white 6% line | A pinned header once content scrolls under it |
| Calendar cells | none white 4%, under `#193A20`, on target `#3CB956`, over `#75731F`, well over `#7D3626` | Calendar and consistency grid |

Gradients (`AppBrushes`):

- Brand, 90 degrees: `#35CE4F`, `#7FDC48`, `#D3F444`. Used for the calorie bar and the widget bar.
- Primary button: `#2DB04C`, `#62BF3F`, `#9CCB3A`, with dark ink.
- "+" button, 140 degrees: `#B6EF4A`, `#6FDC5A`, `#2FC6A0`, with a green shadow.

## 3. Type

Jura, bundled as `res/font/jura_light.ttf` and `jura_medium.ttf`. Weights 100-400 map to Light and 500-900 to Medium, so Compose never fakes a bold. Base tracking is +0.005em, and -0.03em on large numbers. Jura has no tabular figures, so right-align numbers in columns.

| Style | Size / line | Weight | Use |
|---|---|---|---|
| `Hero` | 46 / 46 sp | 500 | "776" in "776 / 2,000 kcal" |
| `HeroUnit` | 16 / 20 sp | 500 | "/ 2,000 kcal" |
| `BigStat` | 40 / 42 sp | 500 | Lifestyle score, weight |
| `ValueLg` | 32 / 36 sp | 500 | Large values |
| `Header1` | 18 / 24 sp | 500 | Screen titles, Home's day name |
| `Header2` | 17 / 22 sp | 500 | Section headings ("Breakfast") |
| `Header3` | 15 / 20 sp | 500 | Small headings |
| `Body1` | 15 / 21 sp | 500 | Row titles, body |
| `Body2` | 13.5 / 19 sp | 300 | Secondary lines, notes |
| `Button` | 15.5 / 20 sp | 500 | Buttons |
| `Chip` | 13.5 / 18 sp | 500 | Chips, tab and view labels |
| `Caption` | 12 / 16 sp | 500 | Labels, captions |

Material 3 components (dialogs, date pickers, text fields) get a Jura typography too (`MaterialJuraTypography`).

## 4. Shape, spacing and sizes

- Corners: panels 20 dp, small panels and tiles 16 dp, rows 18 dp, inputs 14 dp, calendar cells 10 dp, sheet tops 28 dp. Buttons, chips, the bar, the capsule, the "+" and avatars are fully round.
- Screen side margin 16 dp. Gap between Home sections 22 dp. Panel padding 16 dp. Gap between list rows 8 dp.
- Touch targets at least 44 dp. Icon buttons are 40-46 dp, bar items 48 dp tall, the "+" is 60 dp.

## 5. Shell and navigation

- **Bottom dock**, on the five tab roots only:
  - A full-width pill bar, 60 dp tall, 16 dp from the screen edges and the bottom inset, with the tabs Home, Foods, Stats, Rank and Profile.
  - The active tab is 98 dp wide and shows its label. A capsule slides behind it.
  - The round "+" (60 dp) sits above the bar's right end and opens the Add sheet.
  - On screens with several views (Foods: Custom foods / Recipes; Stats: Calendar, Weight, Consistency, Averages, Rank history), a pill view switch fills the rest of the "+" row. With more than two views it scrolls and snaps the active view to the centre.
  - A fade behind the dock runs from transparent to the canvas black.
- **Headers** (`MbHeader`): a back arrow, a centred 18 sp title and an optional action. They stay pinned and get the `HeaderBand` once content scrolls under them. Home, Search, Rank, Food Detail and the scanner draw their own tops.
- **Sheets** (`MbSheet`): `#0A0D0B`, 28 dp top corners, an `Edge2` top line, a grab handle and a scrim. The title is at the top left with a close button; actions sit in a footer row.
- **Pushed screens** (Search, Food Detail, Basket, forms, Water, Weight) and the scanner have no dock.

## 6. Home, top to bottom

1. **Header:** a 48 dp avatar ring with the first-name initial (opens Profile), "Hello, <name>" and "What are you eating today?".
2. **Basket button** (46 dp, with a count badge): pinned at the top right while Home scrolls. At the top of the page it sits in the header row. Once the page scrolls under it, it fades in the bar's pill fill and shadow (200 ms) so it stays readable over the cards.
3. **Calories panel**, 22 dp below the header:
   - "eaten / target kcal", with a 40 dp daily-summary button in the corner;
   - the 6 dp brand-gradient calorie bar (red and full when over);
   - three 78 dp macro rings with "eaten/target" in grams;
   - the search pill with its scan button.

   A sideways swipe on the panel steps a day: left for the next day, right for the day before, once the finger travels 48 dp (the calendar's month swipe distance).
4. **Day row**, 14 dp below the panel: `<  Today  >`. A 40 dp caret on each side steps a day, and the day name is centred between them ("Today", "Yesterday" or a date). Tapping the name opens the date picker.
5. **Meals**, starting 10 dp below the day row:
   - Breakfast, Lunch and Dinner always show; snacks only when they have entries.
   - Each meal has a header row: its name, a small "+", an info button and the meal's kcal.
   - Then one swipe row per food, or a dashed "No foods logged yet." tile.
6. **Trackers:** two compact cards. Water has its icon and "+250 mL" ("+8 oz" in imperial); Weight has its icon and "Log weight".

Home is a lazy list: only the rows on screen are built.

## 7. Components

| Component | Composable | Notes |
|---|---|---|
| Panel | `MbPanel` | Radius 20, `Edge` outline, panel fill and tint |
| Row tile | `MbRowTile` | Radius 18, icon circle, title with a secondary line, value and chevron |
| Buttons | `MbButton`, `MbDangerGhostButton`, `MbFilledPlusButton` | Primary gradient, secondary outline, danger; at least 48 dp |
| Icon button | `MbIconButton` | 40-46 dp, optionally outlined |
| Chips | `MbChip`, `MbChipRow` | Pill, 36-40 dp; rows scroll sideways |
| Text field | `MbTextField` | Radius 14, label above, help or error below, unit suffix inside |
| Search pill | `MbSearchPill`, `MbScanButton` | Black 25% fill, white 13% outline |
| Macro ring | `MbMacroRing` | 78 dp, 5 dp stroke, from 12 o'clock clockwise; no arc when nothing is eaten |
| Swipe row | Home `DashboardFoodItemRow`, Basket rows | Drag left for Delete and Copy (Home) or Delete (Basket); opens past 40%; one open at a time |
| "When" table | `MealAndDayPicker` | 3 x 3: three meals, three snacks, then Yesterday, Today and Choose a day |
| View switch | `MbViewSwitch` | In the dock, with the same capsule as the bar |
| Basket button | `MbBasketButton` | With the count badge |
| Aura | `MbAura` | Two radial glows, moved by layer transform only |
| Loading | `MbDelayedProgress` | Shows only after 300 ms, so quick loads never flash a spinner |

## 8. Motion and vibration

- Easing "ease" is `CubicBezierEasing(0.16, 1, 0.3, 1)`; "sine" is `(0.37, 0, 0.63, 1)`.
- Tab and view capsules slide in 340 ms, and the active tab's label fades in over 300 ms.
- Screen changes: a tab rises in over 220 ms, a pushed screen slides in from the right over 280 ms, and going back slides in from the left over 240 ms. The old screen leaves at once.
- Press feedback: every pressable control scales to 0.92-0.97 in 140 ms.
- Macro rings fill from empty when Home opens (700 ms) and from their old value when a log changes them. They don't refill when they scroll back into view.
- Swipe rows snap open or closed in 260 ms. Sheets slide up in 340 ms and down in 180 ms.
- Aura: layer A moves on a 34 x 21 dp ellipse every 14.4 s and stretches over 6.4 s. Layer B moves on a 64 x 32 dp ellipse every 22.4 s, stretches over 4.4 s and pulses over 5.6 s.
- Reduced motion: when the system animator scale is 0, everything shows its final state, and the aura stays still with layer B hidden.
- Vibration follows the phone's touch-vibration setting:
  - a clock tick on switching to another tab or view;
  - a gesture tick (Android 14+, a clock tick before that) when a swiped row reaches the point where letting go opens it.

## 9. Icons

Phosphor Icons 2.1.1 (MIT), converted to vector drawables named `res/drawable/ic_ph_<name>_<weight>.xml`. Inactive tabs and most icons use Light; active tabs and filled buttons use Regular; check-circle is Fill. They are accessed through the `Ph` enum in `PhosphorIcons.kt`.

## 10. Accessibility

- Text contrast is at least 4.5:1, including the greeting over the brightest moment of the aura. Primary button text on its gradient is 7:1 or better.
- Every icon-only control has a label. Examples: "Add food to Breakfast", "Breakfast nutrition", "Daily summary", "Scan nutrition label", "Basket, N items", "Previous day", "Next day", "Choose a day", and the tab names.
- Macro rings read as "Protein: 27 of 125 g".
- The consistency grid is drawn as one picture. While an accessibility service is on, each day is also a labelled, tappable spot.

## 11. Home-screen widget

The widget keeps the app's text and layout, restyled dark:
- the "MACROBASE" title and the date;
- the kcal eaten and the kcal remaining;
- the calorie bar with its percentage;
- one line of protein, carbs and fat against their targets.

## 12. Rules that apply to every screen

From AGENTS.md:
- Displayed nutrient, calorie and percent values use `roundToInt()`, never `.toInt()`.
- Every `String.format` passes `Locale.US`.
- Kotlin string literals use Unicode escapes for non-ASCII characters.
- A missing nutrient shows "-"; a real zero shows "0.0 g".
