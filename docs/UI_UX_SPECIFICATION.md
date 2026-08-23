Nutrition Tracker — Complete UI/UX Specification1. Design System1.1 Color System & Design TokensThe application utilizes a dark theme anchored by a signature vibrant emerald-green brand color, dark surface cards, clean data visualization accents, and high-contrast typography.┌────────────────────────────────────────────────────────────────────────┐
│                        CORE COLOR PALETTE MATRIX                       │
├───────────────────────┬──────────────┬─────────────────────────────────┤
│ Token Name            │ Inferred HEX │ Usage / Context                 │
├───────────────────────┼──────────────┼─────────────────────────────────┤
│ AppColors.Primary     │ #009639      │ Primary App Bars, Hero Action   │
│                       │              │ Buttons, Active Tab Fill        │
│ AppColors.PrimaryDark │ #007029      │ Pressed States, Header Contours │
│ AppColors.Background  │ #121212      │ Base Screen Canvas              │
│ AppColors.Surface     │ #1E1E1E      │ Card Surfaces, Dialog Windows   │
│ AppColors.SurfaceAlt  │ #242424      │ Date Headers, Sub-bars          │
│ AppColors.SurfaceInput│ #2C2C2C      │ Form Text Fields, Search Inputs │
│ AppColors.Divider     │ #333333      │ Row Separators, Section Borders │
│ AppColors.TextPrimary │ #FFFFFF      │ Screen Titles, Food Names       │
│ AppColors.TextSecondary│ #A0A0A0     │ Subtitles, Serving Units, Dates │
│ AppColors.TextDisabled│ #555555      │ Disabled Action Items           │
│ AppColors.CalorieText │ #00C853      │ Calorie Value Highlight Text    │
│ AppColors.MacroProtein│ #4A90E2      │ Protein Grams / Legend Slices   │
│ AppColors.MacroCarbs  │ #F5A623      │ Carbohydrates Grams / Slices    │
│ AppColors.MacroFat    │ #D0021B      │ Fat Grams / Legend Slices       │
│ AppColors.ProgressOver│ #E53935      │ Over-Calorie Limit Warning Bar  │
│ AppColors.WaterCyan   │ #00BCD4      │ Water Progress Fill & Chevrons  │
└───────────────────────┴──────────────┴─────────────────────────────────┘
*All HEX values marked as: Approximate — verify during implementation.*
Centralized Calendar Heatmap ColorsAppColors.CalendarGreenOptimal: #2E7D32 (Approximate — verify during implementation)
AppColors.CalendarGreenLight:   #4CAF50 (Approximate — verify during implementation)
AppColors.CalendarGreenSubtle:  #1B5E20 (Approximate — verify during implementation)
AppColors.CalendarRedWarning:   #C62828 (Approximate — verify during implementation)
AppColors.CalendarRedAlert:     #8E1C1C (Approximate — verify during implementation)
AppColors.CalendarEmpty:        #1E1E1E (Approximate — verify during implementation)
1.2 Typography System┌────────────────────────────────────────────────────────────────────────┐
│                          TYPOGRAPHY TOKENS                             │
├───────────────────────┬──────────┬───────────┬─────────────────────────┤
│ Typography Token      │ Size     │ Weight    │ Line Height / Tracking  │
├───────────────────────┼──────────┼───────────┼─────────────────────────┤
│ AppTypography.Header1 │ 20sp     │ Bold (700)│ 26sp / 0.15sp           │
│ AppTypography.Header2 │ 16sp     │ Bold (700)│ 22sp / 0.1sp            │
│ AppTypography.Header3 │ 14sp     │ SemiBold  │ 18sp / 0.1sp            │
│ AppTypography.Body1   │ 15sp     │ Regular   │ 20sp / 0.25sp           │
│ AppTypography.Body2   │ 13sp     │ Regular   │ 18sp / 0.25sp           │
│ AppTypography.Caption │ 11sp     │ Regular   │ 14sp / 0.4sp            │
│ AppTypography.ValueLg │ 28sp–34sp│ Bold (800)│ 36sp / 0.0sp            │
│ AppTypography.ValueMd │ 16sp–18sp│ Bold (700)│ 22sp / 0.0sp            │
│ AppTypography.Button  │ 15sp     │ SemiBold  │ 20sp / 0.5sp Uppercase  │
└───────────────────────┴──────────┴───────────┴─────────────────────────┘
*All sizes and line heights marked as: Approximate — verify during implementation.*
Primary Font Family: Standard Android System Font (Roboto or Inter).Hierarchy Application:Header1: Top AppBar Screen Titles ("Food Logging Calendar", "Edit Daily Goals").Header2: Section Headers ("BREAKFAST", "LUNCH", "Weight Graph").Body1: Food Item Names in Lists ("Paneer", "Tandoori Naan").Body2: Form Field Labels, Serving Info ("40 G", "1 Piece", "1 Cup").Caption: Macro Label Subtitles ("START", "CURRENT", "CHANGE", "Sun", "Mon").ValueLg: Nutrition Fact Hero Numbers ("Calories 428.7").ValueMd: Calorie indicators ("240 Cal", "97%").1.3 Spacing & Layout GridAll margins, paddings, and insets adhere to a strict 4dp/8dp base grid system:Spacing.xxs = 2dp   (Micro dividers, inner dot borders)
Spacing.xs  = 4dp   (Inner badge padding, tight element grouping)
Spacing.sm  = 8dp   (Item padding, icon-to-text spacing, grid gaps)
Spacing.md  = 12dp  (Row internal padding, sub-card spacing)
Spacing.lg  = 16dp  (Standard screen margins, card padding)
Spacing.xl  = 24dp  (Section spacing, bottom action bar separation)
Spacing.xxl = 32dp  (Top hero module separation)
1.4 Shapes, Corner Radii & Elevation┌────────────────────────────────────────────────────────────────────────┐
│                        SHAPE & ELEVATION TOKENS                        │
├───────────────────────┬────────────┬───────────┬───────────────────────┤
│ Element Type          │ Radius     │ Elevation │ Stroke / Border       │
├───────────────────────┼────────────┼───────────┼───────────────────────┤
│ Cards / Containers    │ 4dp–8dp    │ 2dp       │ None (Dark surface)   │
│ Calendar Day Cells    │ 4dp        │ 0dp       │ None                  │
│ Form Input Boxes      │ 4dp        │ 0dp       │ 1dp #333333           │
│ Action Buttons (Hero) │ 6dp–8dp    │ 2dp       │ None                  │
│ Pill / Outlined Btns  │ 4dp        │ 0dp       │ 1dp #444444           │
│ Dialog Windows        │ 12dp       │ 8dp       │ None                  │
│ Bottom Sheet Corners  │ 16dp (Top) │ 16dp      │ None                  │
└───────────────────────┴────────────┴───────────┴───────────────────────┘
*All values marked as: Approximate — verify during implementation.*
1.5 Iconography StyleIcon Library: Material Symbols Rounded / Material Design Icons (Outline & Filled).Color Rules: Default secondary icons render in AppColors.TextSecondary (#A0A0A0). Active navigation or highlighted icons render in AppColors.Primary (#009639) or AppColors.TextPrimary (#FFFFFF).Standard Bounding Sizes:AppBar Navigation / Action Icons: 24dp x 24dp (touch target minimum 48dp x 48dp).List Item Chevrons & Info Targets: 20dp x 20dp.Bottom Navigation Bar Icons: 24dp x 24dp.Floating Action / Hero Track Plus Button: 28dp x 28dp inside a 56dp x 56dp container.2. Application Navigation                               ┌───────────────────┐
                               │   Splash Screen   │
                               └─────────┬─────────┘
                                         │
                               ┌─────────▼─────────┐
                               │  Home Dashboard   │◄────────────────┐
                               └────┬─────────┬────┘                 │
                                    │         │                      │
          ┌─────────────────────────┘         └────────────────┐     │
          ▼                                                    ▼     │
┌───────────────────┐                                ┌───────────────────┐
│ Navigation Drawer │                                │ Bottom Navigation │
├───────────────────┤                                ├───────────────────┤
│ • My Recipes      │                                │ • Dashboard (Tab) │
│ • Custom Foods    │                                │ • Stats (Tab)     │
│ • Preferences     │                                │ • Track (+) (Act) │
│ • Daily Goals     │                                │ • Suggested (Tab) │
│ • Sign Out        │                                │ • Preferences     │
└───────────────────┘                                └───────────────────┘
2.1 Navigation Structure & Entry Points┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│                              APPLICATION NAVIGATION MATRIX                                   │
├─────────────────────┬───────────────────┬──────────────┬─────────────────────┬───────────────┤
│ Destination Screen  │ Entry Trigger     │ Scrollable   │ Back Button Action  │ Transition    │
├─────────────────────┼───────────────────┼──────────────┼─────────────────────┼───────────────┤
│ Home Dashboard      │ Bottom Nav: Home  │ Vertical     │ Exits app (Root)    │ Instant / Fade│
├─────────────────────┼───────────────────┼──────────────┼─────────────────────┼───────────────┤
│ Stats / Calendar    │ Bottom Nav: Stats │ Vertical     │ Returns to Home     │ Instant / Fade│
├─────────────────────┼───────────────────┼──────────────┼─────────────────────┼───────────────┤
│ Food Search         │ Top Search / (+)  │ Vertical     │ Closes Search       │ Slide Up      │
├─────────────────────┼───────────────────┼──────────────┼─────────────────────┼───────────────┤
│ Food Detail / Log   │ Tap Search Item   │ Vertical     │ Returns to Search   │ Slide Left    │
├─────────────────────┼───────────────────┼──────────────┼─────────────────────┼───────────────┤
│ Nutrition Facts     │ Tap (i) on Meal   │ Vertical     │ Returns to Dashboard│ Slide Left    │
├─────────────────────┼───────────────────┼──────────────┼─────────────────────┼───────────────┤
│ Custom Foods List   │ Drawer: Custom Fd │ Vertical     │ Returns to Home     │ Slide Left    │
├─────────────────────┼───────────────────┼──────────────┼─────────────────────┼───────────────┤
│ Edit Custom Food    │ Tap / Create New  │ Vertical     │ Discards edits (Dlg)│ Slide Left    │
├─────────────────────┼───────────────────┼──────────────┼─────────────────────┼───────────────┤
│ Daily Goals         │ Drawer: Goals     │ Vertical     │ Returns to Home     │ Slide Left    │
├─────────────────────┼───────────────────┼──────────────┼─────────────────────┼───────────────┤
│ My Recipes          │ Drawer: Recipes   │ Vertical     │ Returns to Home     │ Slide Left    │
├─────────────────────┼───────────────────┼──────────────┼─────────────────────┼───────────────┤
│ Preferences/Profile │ Bottom Nav / Draw │ Vertical     │ Returns to Home     │ Slide Left    │
├─────────────────────┼───────────────────┼──────────────┼─────────────────────┼───────────────┤
│ Import / Export     │ Preferences Sub   │ Non-scroll   │ Returns to Prefs    │ Slide Left    │
└─────────────────────┴───────────────────┴──────────────┴─────────────────────┴───────────────┘
2.2 Navigation Drawer ArchitectureWidth: 280dp from the left screen edge.Header: Solid Green #009639, displays App Title ("Track 1.10.8" or configured app name).Body Content:List Items: Icon (24dp) + Spacing (16dp) + Text Title (AppTypography.Body1).Divider line (#333333).Social / Promo Buttons: 3 full-width rounded green buttons ("Rate us on Google Play", "Send Track to a friend", "Like us on Facebook").App Branding Footer: Centered app mark and version info.3. Home Dashboard┌─────────────────────────────────────────────────────────────────────────┐
│ [≡]  [ Search foods to log                  ||||| ]                 [🛒]│ ◄ Top Bar
├─────────────────────────────────────────────────────────────────────────┤
│ [<]                         Tuesday, 08/18                           [>]│ ◄ Date Switcher
├─────────────────────────────────────────────────────────────────────────┤
│ 2168 cal intake             0 cal burned                        Over 168│ ◄ Calorie Summary
├─────────────────────────────────────────────────────────────────────────┤
│ [██████████████████████████████████████████████████████████████████████]│ ◄ Progress Bar
├─────────────────────────────────────────────────────────────────────────┤
│       96g Protein       │        271g Carb        │        76g Fat      │ ◄ Macro Row
├─────────────────────────────────────────────────────────────────────────┤
│ BREAKFAST  (+)                                                   (i) 644│ ◄ Section Header
│ ┌─────┐ Paneer                                                          │
│ │ 🔲  │ 40 G                                                    120  >  │ ◄ Food Row
│ └─────┘                                                                 │
│ ┌─────┐ Tandoori Naan                                                   │
│ │ 🔲  │ 2 Piece                                                 524  >  │
│ └─────┘                                                                 │
├─────────────────────────────────────────────────────────────────────────┤
│ LUNCH  (+)                                                       (i) 604│
│ ...                                                                     │
├─────────────────────────────────────────────────────────────────────────┤
│ [ 📊 View Daily Summary ]                                               │ ◄ Daily Summary
└─────────────────────────────────────────────────────────────────────────┘
3.1 Screen Anatomy & Visual HierarchyTop Navigation Bar:Height: 56dp, Background: AppColors.Primary (#009639).Left: Navigation Drawer Hamburger icon (24dp, White).Center: Embedded Search Input Pill. Height 36dp, Background AppColors.Surface (#1E1E1E), Corner Radius 4dp. Leading text: "Search foods to log" in AppColors.TextSecondary. Trailing: Barcode scan icon (20dp, White).Right: Shopping cart / Logging basket icon (24dp, White).Date Selector Ribbon:Height: 44dp, Background: AppColors.SurfaceAlt (#242424).Layout: Horizontal Row, Space-between.Controls: Left chevron (<), Bold centered Date Text ("Tuesday, 08/18"), Right chevron (>).Behavior: Tapping left navigates to SelectedDate - 1; tapping right navigates to SelectedDate + 1 (disabled if restricted by future logging preference). Tapping date text opens DatePicker Dialog.Calorie Target & Intake Summary:Layout: 3-column data row over a dark background (#1A1A1A).Left Column: Intake ("2168 cal intake") — AppTypography.Body2.Center Column: Burned ("0 cal burned") — AppTypography.Body2.Right Column: Balance ("remaining 2000" or "Over 168") — AppTypography.Body2, Bold.Calorie Limit Progress Indicator:Height: 4dp, Full-width.Normal State: Green #00C853 filling proportional to Intake / Goal.Over-budget State: Red #E53935 filling 100% width when Intake > Goal.Macronutrient Quick Strip:Height: 36dp, Background: #1A1A1A.Dividers: 1dp vertical borders #333333 between columns.3 Equal Columns:[Value]g Protein (Value in Blue #4A90E2, Label in #A0A0A0).[Value]g Carb (Value in Sky Blue #4A90E2, Label in #A0A0A0).[Value]g Fat (Value in Blue #4A90E2, Label in #A0A0A0).Meal Diary Sections (Breakfast, Lunch, PM Snack, Dinner, Snack):Section Header: Height 40dp, #1E1E1E background.Left: Section Title ("BREAKFAST", AppTypography.Header3, #FFFFFF).Adjacent: Green round Plus button (+) (Radius 18dp, #00C853) to trigger direct meal search.Right: Info icon (i) (20dp, #A0A0A0) opening meal nutrition breakdown, followed by total section calories ("644", AppTypography.Header3, #FFFFFF).Logged Food Row:Height: 64dp, Background: #121212, Bottom divider: 1dp #222222.Left: Food image thumbnail or category bowl icon (40dp x 40dp, Corner Radius 4dp).Center: Food Title (AppTypography.Body1, White), Subtitle ("40 G", AppTypography.Body2, #A0A0A0).Right: Calorie count ("120", AppTypography.Body1, Bold #00C853), Right chevron (>, #A0A0A0).Empty Section State:Single row height 36dp. Dimmed italic text: "No foods logged yet." (AppTypography.Body2, #777777).Supplementary Log Modules (Exercise, Weigh-In, Water):EXERCISE (+), WEIGH-IN (+), WATER (+) formatted identically to meal headers.Water Section shows total consumed volume (e.g., "0 L" or "2.5 L").Footer Action:"View Daily Summary" button with a pie chart icon, centered, height 48dp, background #1E1E1E.4. Food Search┌─────────────────────────────────────────────────────────────────────────┐
│ [<]  [ Search foods to log                  ||||| ]                 [🛒]│
├─────────────────────────────────────────────────────────────────────────┤
│ FOODS EATEN AROUND 9 AM                                                 │ ◄ Section Header
├─────────────────────────────────────────────────────────────────────────┤
│ ┌─────┐ Paneer                                                  240     │
│ │ 🔲  │ 80 G                                                    Cal     │
│ └─────┘                                                                 │
│ ┌─────┐ Chapati                                                 160     │
│ │ 🔲  │ 2 Serving                                               Cal     │
│ └─────┘                                                                 │
│ ┌─────┐ Dal                                                     222     │
│ │ 🔲  │ 1 Cup                                                   Cal     │
│ └─────┘                                                                 │
│ ┌─────┐ Caramel Soya Vegan Chunks                               107     │
│ │ 🔲  │ Carib 30 G                                              Cal     │
│ └─────┘                                                                 │
└─────────────────────────────────────────────────────────────────────────┘
4.1 Local Database & Search Performance ArchitectureDatabase Capacity: 50,000 foods stored offline in SQLite / Room with FTS5 (Full-Text Search) enabled.Debounce Window: 250 milliseconds between keystrokes before executing SQLite FTS queries.Pagination / Virtualization: Queries return results via Jetpack LazyColumn / Paging 3 in chunks of 30 items. Memory footprint remains bounded.4.2 Screen Components & BehaviorSearch Input Field:Top app bar embedded text field. Active autofocus upon entry.Clear button (X) appears when text length $> 0$.Search States:Empty Query / Initial State:Header: "FOODS EATEN AROUND [Current Time / Contextual Meal]" (AppTypography.Caption, Uppercase #888888).List: Chronologically or contextually suggested recent foods logged by the user.Active Query Results:Header: "SEARCH RESULTS ([Count])".Food Row Layout:Left: Food Icon / Brand Thumbnail (40dp x 40dp).Center Column: Food Name (Bold #FFFFFF, max 1 line, ellipsize end), Brand / Serving Size Subtitle (#A0A0A0).Right Column: Stacked Calories ("240", Bold #00C853, AppTypography.Body1) over unit label ("Cal", #A0A0A0, AppTypography.Caption).No Results State:Centered graphic icon (Magnifying glass + question mark), Title: "No Foods Found", Subtitle: "We couldn't find matching items in the offline database.", Action Button: "+ Create Custom Food" (AppColors.Primary).5. Food Detail & Logging┌─────────────────────────────────────────────────────────────────────────┐
│ [<]                     Breakfast - Wed, 08/19                          │ ◄ Top Bar
├─────────────────────────────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────────────────────────────┐ │
│ │ Nutrition Facts                                                     │ │ ◄ FDA Style Card
│ │ Amount Per Serving                                                  │ │
│ │ Calories                                                      428.7 │ │
│ │─────────────────────────────────────────────────────────────────────│ │
│ │                                                      % Daily Value* │ │
│ │ Total Fat 21g                                                   27% │ │
│ │   Saturated Fat 7g                                              35% │ │
│ │ Cholesterol 32mg                                                11% │ │
│ │ Sodium 204.6mg                                                   9% │ │
│ │ Total Carbohydrates 41.1g                                       15% │ │
│ │   Dietary Fiber 4.1g                                            15% │ │
│ │   Sugars 31g                                                        │ │
│ │ Protein 21.4g                                                       │ │
│ │─────────────────────────────────────────────────────────────────────│ │
│ │ Vitamin D 4.8mcg  24%  •  Calcium 483mg  37%  •  Iron 0mg  0%       │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────────────────┤
│ Net Carbs: 36.9 g                                                       │
│ Phosphorus ** : 381.2 mg                                                │
│ > View more micronutrients                                              │
├─────────────────────────────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────────────────────────────┐ │
│ │ Source of Calories                                                  │ │ ◄ Macro Breakdown
│ │         ( ● ) Pie Chart       ■ Protein 20%                         │ │
│ │                               ■ Carbs 37%                           │ │
│ │                               ■ Fat 43%                             │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
5.1 Logging Workflow & Quantity MultipliersWorkflow Sequence:Search Selection $\rightarrow$ Food Detail Screen $\rightarrow$ Serving Selector & Quantity Input $\rightarrow$ Meal Target Selection $\rightarrow$ Save / Log Action $\rightarrow$ Return to Diary with Confirmation Snackbar.Serving & Quantity Adjuster:Quantity Field: Form Input box (#2C2C2C), numeric decimal keyboard. Default 1.0.Unit Selector Dropdown: "Grams (g)", "Ounces (oz)", "Serving", "Piece", "Cup".Dynamic Calculation Rule: Changing the quantity or unit immediately recalculates all displayed values across the Nutrition Facts panel and Macro Pie Chart without server or database latency.Nutrition Facts Panel (Standard FDA Layout on Dark Surface):Border: 1dp #444444, Background: #181818, Padding: 12dp."Nutrition Facts" Header (24sp, Bold, White)."Calories": AppTypography.ValueLg (32sp, Bold).Nutrient rows with heavy and thin border dividers: Total Fat, Saturated Fat, Trans Fat, Cholesterol, Sodium, Total Carbohydrates, Dietary Fiber, Total Sugars, Protein, Micronutrients.Calorie Breakdown Chart (Source of Calories):Container Card: #1E1E1E, Corner Radius 6dp.Title: "Source of Calories" (AppTypography.Header3).Visual: Donut / Pie Chart (120dp x 120dp) alongside a 3-row Legend:Cyan square (#00BCD4): Protein [%]Gold/Brown square (#F5A623): Carbs [%]Magenta/Pink square (#E91E63): Fat [%]6. Custom Foods┌─────────────────────────────────────────────────────────────────────────┐
│ [<]                       Edit Custom Food                       Cancel │ ◄ Top Bar
├─────────────────────────────────────────────────────────────────────────┤
│ Food Name *               [ Whole Wheat Bread ]                         │
│ Brand                     [ Home Bakery       ]                         │
│ Serving Size *            [ 1.0               ]   Unit: [ Slice     v ] │
│ Calories *                [ 80                ]   kcal                  │
├─────────────────────────────────────────────────────────────────────────┤
│ Sodium                    [ 0                 ]   mg                    │
│ Total Carbohydrate        [ 12                ]   g                     │
│ Dietary Fiber             [ 1.8               ]   g                     │
│ Sugars                    [ 0                 ]   g                     │
│ Protein                   [ 1.3               ]   g                     │
│ Vitamin A                 [ 0                 ]   %dv                   │
│ Vitamin C                 [ 0                 ]   %dv                   │
│ Vitamin D                 [ 0                 ]   %dv                   │
│ Calcium                   [ 0                 ]   %dv                   │
│ Iron                      [ 0                 ]   %dv                   │
│ Phosphorus                [ 0                 ]   mg                    │
│ Potassium                 [ 0                 ]   mg                    │
├─────────────────────────────────────────────────────────────────────────┤
│ * denotes required fields                                               │
├─────────────────────────────────────────────────────────────────────────┤
│ [                             Update                                  ] │ ◄ Action Buttons
│ [                         (+) Log this food                           ] │
└─────────────────────────────────────────────────────────────────────────┘
6.1 Custom Food Form SpecificationHeader Bar: Green #009639. Left: Back icon <. Title: "Create Custom Food" / "Edit Custom Food". Right: "Cancel" text button.Input Row Structure:Height: 48dp per row, Space-between.Left Label: AppTypography.Body1 (#FFFFFF).Right Input: Centered text box (#262626 background, 1dp #3A3A3A border, 4dp radius, width 80dp–100dp), numeric input mode.Unit Tag: Suffix label (mg, g, %dv, kcal) aligned to the right edge (#A0A0A0).Mandatory Validation Fields:Food Name (Non-empty text string)Serving Size (Decimal $> 0$)Serving Unit (Non-empty text string)Calories (Integer $\ge 0$)Database Distinction:All user-created foods store a database flag is_custom = 1.Custom foods display an edit pencil icon in search lists, distinguishing them from immutable offline database records (is_custom = 0).Bottom Action Buttons:Primary Button: "Save" / "Update" (Height 48dp, Background #009639, Corner Radius 6dp).Secondary Action: "(+) Log this food" (Height 48dp, Background #007029 or Outlined Green, Corner Radius 6dp).7. Food Logging Calendar┌─────────────────────────────────────────────────────────────────────────┐
│ Food Logging Calendar                                                   │ ◄ Screen Title
├─────────────────────────────────────────────────────────────────────────┤
│ [<]                           July, 2026                             [>]│ ◄ Month Bar
├─────────────────────────────────────────────────────────────────────────┤
│   Sun     Mon     Tue     Wed     Thu     Fri     Sat                   │ ◄ Day Labels
│                                                                         │
│                    1       2       3       4                            │
│                  ┌───┐   ┌───┐   ┌───┐   ┌───┐                          │
│                  │ 1 │   │ 2 │   │ 3 │   │ 4 │   (Optimal Green)        │
│                  └───┘   └───┘   └───┘   └───┘                          │
│    5       6       7       8       9      10      11                    │
│  ┌───┐   ┌───┐   ┌───┐   ┌───┐   ┌───┐   ┌───┐   ┌───┐                  │
│  │ 5 │   │ 6 │   │ 7 │   │ 8 │   │ 9 │   │10 │   │11 │                  │
│  └───┘   └───┘   └───┘   └───┘   └───┘   └───┘   └───┘                  │
│   12      13      14      15      16      17      18                    │
│  ┌───┐   ┌───┐   ┌───┐   ┌───┐   ┌───┐   ┌───┐   ┌───┐                  │
│  │12 │   │13 │   │14 │   │15 │   │16 │   │17 │   │18 │   (12 = Red)     │
│  └───┘   └───┘   └───┘   └───┘   └───┘   └───┘   └───┘                  │
├─────────────────────────────────────────────────────────────────────────┤
│                             ■ ■ ■ ■ ■                                   │ ◄ 5-Step Legend
├────────────────────────────────────┬────────────────────────────────────┤
│            Days Missed             │          % Days of Green           │ ◄ Stats Summary
│               0 Days               │                97%                 │
├────────────────────────────────────┴────────────────────────────────────┤
│ Tap on any date on the calendar to review or add foods.                 │ ◄ Helper Note
└─────────────────────────────────────────────────────────────────────────┘
7.1 Calendar Layout & DimensionsContainer: Card surface #1E1E1E, Corner Radius 8dp, Padding 16dp.Month Bar: Sky Blue Chevrons (#00BCD4), Month & Year Title ("July, 2026", AppTypography.Header1, #FFFFFF).Week Header: 7 Columns ("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"), AppTypography.Caption, #A0A0A0.Date Cells:Square aspect ratio (40dp x 40dp), Corner Radius 4dp.Cell Spacing: 6dp horizontal and vertical grid gap.Date Number Typography: AppTypography.Header3 (14sp, Bold, #FFFFFF), centered.7.2 Configurable Performance Color AlgorithmThe calendar colors must not be hardcoded to fixed calorie ranges. The cell color is determined dynamically by comparing the total calories logged for that date ($C_{logged}$) against the user's daily calorie goal ($G_{daily}$) set in preferences.┌────────────────────────────────────────────────────────────────────────┐
│                   CALENDAR PERFORMANCE COLOR RULES                     │
├───────────────────────────────┬───────────────────┬────────────────────┤
│ Condition / Calorie Ratio     │ Visual Category   │ Token / Color HEX  │
├───────────────────────────────┼───────────────────┼────────────────────┤
│ No Foods Logged ($C = 0$)     │ Empty / Missed    │ Surface (#1E1E1E)  │
│ $0 < C \le 0.85 \times G$     │ Under Budget      │ Subtle Green       │
│                               │                   │ (#1B5E20)          │
│ $0.85 \times G < C \le 1.05\times G$│ Optimal Target│ Vibrant Green    │
│                               │ (Green Day)       │ (#2E7D32 / #4CAF50)│
│ $1.05 \times G < C \le 1.20\times G$│ Moderate Over │ Muted Red / Amber│
│                               │                   │ (#C62828)          │
│ $C > 1.20 \times G$           │ High Over Target  │ Bright Red         │
│                               │                   │ (#E53935)          │
└───────────────────────────────┴───────────────────┴────────────────────┘
*All thresholds and color codes are loaded from CalendarPerformanceConfig.*
7.3 Performance Summary FooterLegend Indicator: 5 centered mini-squares (8dp x 8dp, 4dp spacing) showing the color progression scale.Metrics Row:2 Equal Columns divided by a 1dp vertical line #333333.Left: "Days Missed" (#A0A0A0, 12sp) over "0 Days" (#FFFFFF, 18sp, Bold).Right: "% Days of Green" (#A0A0A0, 12sp) over "97%" (#FFFFFF, 18sp, Bold).Helper Label: Centered footer string "Tap on any date on the calendar to review or add foods." (#888888, 12sp).7.4 Date Click & Navigation ContractInteraction: User taps any active or empty date cell (e.g., July 12).Behavior: The app navigates directly to the Home Dashboard with SelectedDate set to 2026-07-12.Constraint: The Dashboard loads the exact data logged on that historical date. It must not default back to "Today".Back Navigation: Pressing the Android System Back button returns the user directly to the Food Logging Calendar preserving the current month view.8. Weight Tracking┌─────────────────────────────────────────────────────────────────────────┐
│ [<]                           Stats                                     │ ◄ Top Bar
├─────────────────────────────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────────────────────────────┐ │
│ │ Weight Graph                                                        │ │ ◄ Card Container
│ │                                                                     │ │
│ │ Interval                                              Last 3 months │ │ ◄ Time Range
│ │ From: 05/19/2026 [📅]           To: 08/19/2026 [📅]                 │ │
│ │─────────────────────────────────────────────────────────────────────│ │
│ │    83.6 kg                 78.7 kg                 ↓ -4.9 kg        │ │ ◄ Metric Strip
│ │     START                  CURRENT                 CHANGE (6%)      │ │
│ │─────────────────────────────────────────────────────────────────────│ │
│ │ 84 |                                                                │ │ ◄ Line Graph
│ │ 83 | ●                                                              │ │
│ │ 82 |   \  ●                                                         │ │
│ │ 81 |    \---\  ●                                                    │ │
│ │ 80 |         \---\  ●                                               │ │
│ │ 79 |              \---\  ●                                          │ │
│ │ 78 |                   \---\──●──●                                  │ │
│ │    └────────────────────────────────                                │ │
│ │      May 19  May 23  Jun 04  Jul 22  Aug 14                         │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
8.1 Graph Specification & LayoutCard Container: Surface #1E1E1E, Corner Radius 8dp, Padding 16dp.Header & Range Controls:Title: "Weight Graph" (AppTypography.Header2).Interval Dropdown: "Last 30 days", "Last 3 months", "Last 6 months", "1 Year", "All Time".Date Pickers: "From [Date] [CalendarIcon]" and "To [Date] [CalendarIcon]".Metric Strip:3-Column Horizontal Layout:Start Weight: [Value] kg (Bold White) over START (#888888).Current Weight: [Value] kg (Bold White) over CURRENT (#888888).Delta Change: ↓ [Value] kg (Bold Green #00C853) over CHANGE ([Percent]%).Graph Canvas Requirements:Y-Axis: Dynamic range bounded by $\text{MinWeight} - 1\text{kg}$ to $\text{MaxWeight} + 1\text{kg}$. Step interval 1kg. Labels aligned left (#888888, 11sp).X-Axis: Slanted date labels (-45^\circ angle, #888888, 10sp).Plot Line: Smooth cubic bezier curve, stroke width 2.5dp, color #81D4FA (Light Cyan).Data Points: White filled circular dots (6dp diameter) with Cyan border (2dp).Area Under Curve: Subtle vertical gradient fill from rgba(129, 212, 250, 0.25) at the line to rgba(18, 18, 18, 0.0) at the bottom axis.Empty State:If fewer than 2 weight entries exist: Graph canvas replaced with placeholder text "Log at least 2 weight entries to generate progress graph" with a "+ Log Weight" button.9. Water Tracking(Designed to match the established visual language — not directly visible in the reference screenshots.)┌─────────────────────────────────────────────────────────────────────────┐
│ 💧 Water Intake                                                         │ ◄ Card Header
├─────────────────────────────────────────────────────────────────────────┤
│ 1,750 mL / 2,500 mL                                                 70% │
├─────────────────────────────────────────────────────────────────────────┤
│ [████████████████████████████████████████░░░░░░░░░░░░░░░░░░░░]          │ ◄ Cyan Bar
├─────────────────────────────────────────────────────────────────────────┤
│ Quick Add:   [ +250 mL ]   [ +500 mL ]   [ +750 mL ]   [ + Custom ]     │ ◄ Pill Actions
└─────────────────────────────────────────────────────────────────────────┘
9.1 Module & Control SpecificationDaily Target: Loaded from user preferences (Default: 2500 mL or 84 oz).Progress Bar: Height 8dp, Corner Radius 4dp, Fill Color AppColors.WaterCyan (#00BCD4).Quick-Add Pill Buttons:Height 36dp, Background #262626, Border 1dp #00BCD4, Radius 18dp.Buttons: +250 mL, +500 mL, +750 mL, + Custom.Tap Behavior: Tapping any quick-add button triggers an immediate database increment and updates the progress indicator with a smooth spring animation.10. Statistics┌─────────────────────────────────────────────────────────────────────────┐
│ [<]                           Statistics                                │
├─────────────────────────────────────────────────────────────────────────┤
│ [ Weight Stats ]   [ Nutrition Consistency ]   [ Macro Averages ]       │ ◄ Tabs
├─────────────────────────────────────────────────────────────────────────┤
│ 30-Day Logging Consistency                                              │
│                                                                         │
│ Mon  ■ ■ ■ ■ ■ ■ ■                                                      │
│ Wed  ■ ■ ■ ■ ■ ■ ■                                                      │
│ Fri  ■ ■ ■ ■ ■ ■ ■      (GitHub-Style Weekly Heatmap Grid)              │
│                                                                         │
│ Less  ■ ■ ■ ■ ■  More                                                   │
├─────────────────────────────────────────────────────────────────────────┤
│ Consistency Score: 94% (28 of 30 Days Logged)                           │
└─────────────────────────────────────────────────────────────────────────┘
10.1 Multi-Metric Statistics ArchitectureWeight Statistics View: Integrates the complete Weight Graph module described in Section 8.Nutritional Consistency (Contribution Heatmap):GitHub-style matrix of 7 rows (Days of Week: Sun–Sat) $\times 12$ to 26 columns (Weeks).Cell Dimensions: 12dp x 12dp, Corner Radius 2dp, Gap 3dp.Color Scale: 5 levels of green intensity representing calorie adherence.Tap Interaction: Displays tooltip overlay showing exact date, calories logged, and % of target.Average Macro Distribution:Displays 7-day, 30-day, and 90-day aggregate pie charts comparing target macro ratios vs actual consumed ratios.11. Recipes(Designed to match the established visual language — not directly visible in the reference screenshots.)┌─────────────────────────────────────────────────────────────────────────┐
│ [<]                          My Recipes                           [ + ] │ ◄ Top Bar
├─────────────────────────────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────────────────────────────┐ │
│ │ High-Protein Oatmeal Bowl                                           │ │ ◄ Recipe Card
│ │ 3 Ingredients  •  1 Serving                                         │ │
│ │ 450 kcal  •  P: 35g  •  C: 55g  •  F: 8g (Per Serving)               │ │
│ │ [ Log Recipe ]                                           [ Edit ]   │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
11.1 Recipe Data & Calculation SpecificationRecipe Creator Schema:Recipe Title (Text)Servings Produced (Integer $\ge 1$)Ingredients List (Array of Food Item references with selected quantities and units).Automatic Nutrition Calculation:$$\text{Nutrient}_{\text{PerServing}} = \frac{\sum_{i=1}^{N} \text{Nutrient}(\text{Ingredient}_i)}{\text{ServingsProduced}}$$Logging Behavior: User can log a recipe directly into any meal slot (e.g., Breakfast), which adds the calculated composite nutrition values as a single entry or expands into individual ingredients based on user preference.12. Preferences & Daily Goals┌─────────────────────────────────────────────────────────────────────────┐
│ [<]                       Edit Daily Goals                          ?   │ ◄ Top Bar
├─────────────────────────────────────────────────────────────────────────┤
│ Daily Calorie Limit:      [ 2000 ] kcal                                 │
├─────────────────────────────────────────────────────────────────────────┤
│ % of Calories from                                                      │
│ Carbohydrates:            [ 50   ] %                             250.0g │
├─────────────────────────────────────────────────────────────────────────┤
│ % of Calories from                                                      │
│ Protein:                  [ 25   ] %                             125.0g │
├─────────────────────────────────────────────────────────────────────────┤
│ % of Calories from                                                      │
│ Fat:                      [ 25   ] %                              55.6g │
├─────────────────────────────────────────────────────────────────────────┤
│ Total Macro Percentage:                                            100% │
├─────────────────────────────────────────────────────────────────────────┤
│ [                                Save                                 ] │ ◄ Green Button
└─────────────────────────────────────────────────────────────────────────┘
12.1 Goal Configuration & Dynamic Macro MathCalorie Target Input: Box #262626, numeric integer keyboard.Macro Percentage Inputs & Dynamic Gram Conversion:$$\text{Carbohydrate (g)} = \frac{\text{DailyCalories} \times (\% \text{Carbs} / 100)}{4}$$$$\text{Protein (g)} = \frac{\text{DailyCalories} \times (\% \text{Protein} / 100)}{4}$$$$\text{Fat (g)} = \frac{\text{DailyCalories} \times (\% \text{Fat} / 100)}{9}$$Validation Rule: The form validates that $\% \text{Carbs} + \% \text{Protein} + \% \text{Fat} = 100\%$. If the sum $\ne 100\%$, a warning badge displays in amber #FFA000, and the Save button is disabled.12.2 User Profile & Unit PreferencesMeasure Systems: Metric (kg, cm, mL) vs Imperial (lbs, ft/in, fl oz).Profile Fields: First Name, Last Name, Time Zone dropdown, Weight, Height, Age.13. Import & Export (Data Portability)(Designed to match the established visual language — not directly visible in the reference screenshots.)┌─────────────────────────────────────────────────────────────────────────┐
│ [<]                       Data & Backup                                 │ ◄ Top Bar
├─────────────────────────────────────────────────────────────────────────┤
│ Export User Data                                                        │
│ Generate an offline JSON / SQLite backup of your diary, custom foods,  │
│ recipes, weight, and goals.                                             │
│ [ 📤 Export Backup File ]                                               │
├─────────────────────────────────────────────────────────────────────────┤
│ Restore User Data                                                       │
│ Restore your data from a previously exported backup file.               │
│ [ 📥 Select Backup File to Restore ]                                    │
├─────────────────────────────────────────────────────────────────────────┤
│ Backup Version Compatibility: Schema v1.0.0                             │
└─────────────────────────────────────────────────────────────────────────┘
13.1 Backup File Schema & Restoration RulesExport Scope: User Food Diary logs, Custom Foods table, Recipes table, Weight history, Water history, User Goals and Preferences.Excluded: The static built-in 50,000-food database (reduces backup file size from ~50MB to < 500KB).Restoration Conflict Handling:Merge: Adds missing items and updates existing records by UUID.Overwrite: Replaces user tables completely after user confirmation dialog ("Warning: This will overwrite current diary data").Validation: Checksum and schema_version validation before unpacking backup archive.14. Database-Agnostic UI ArchitectureThe UI Layer is entirely decoupled from the underlying SQLite / Room storage engines:┌────────────────────────────────────────────────────────────────────────┐
│                              UI LAYER                                  │
│             Jetpack Compose Composables / Screen Layouts               │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Observes UIState (StateFlow)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                           VIEWMODEL LAYER                              │
│              DiaryViewModel, SearchViewModel, StatsViewModel           │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Executes Use Cases
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                           USE CASE LAYER                               │
│        GetDailyDiaryUseCase, SearchFoodsUseCase, CalculateMacros       │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Domain Models (FoodItem, DiaryEntry)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                          REPOSITORY LAYER                              │
│              FoodRepositoryImpl, UserDataRepositoryImpl                │
└─────────────────┬────────────────────────────────────┬─────────────────┘
                  │                                    │
                  ▼                                    ▼
┌──────────────────────────────────┐ ┌───────────────────────────────────┐
│     STATIC FOOD DATA SOURCE      │ │       USER DATA DATA SOURCE       │
│  Offline 50k FTS SQLite Database │ │ Room User Logs, Custom Foods, Wt  │
└──────────────────────────────────┘ └───────────────────────────────────┘
15. Component Inventory┌─────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                       REUSABLE COMPONENT INVENTORY                                      │
├───────────────────────┬─────────────────────────────────┬───────────────────────────────┬───────────────┤
│ Component Name        │ Visual Description              │ Inputs / Parameters           │ Events / Out  │
├───────────────────────┼─────────────────────────────────┼───────────────────────────────┼───────────────┤
│ FoodSearchBar         │ Dark input box, scanner icon    │ query: String, hint: String   │ onQueryChange,│
│                       │                                 │                               │ onScanBarcode │
├───────────────────────┼─────────────────────────────────┼───────────────────────────────┼───────────────┤
│ FoodResultItem        │ 64dp list row, thumb, name, cal │ item: FoodItem                │ onClick,      │
│                       │                                 │                               │ onFavorite    │
├───────────────────────┼─────────────────────────────────┼───────────────────────────────┼───────────────┤
│ MacroProgressStrip    │ 3-column macro grams (P, C, F)  │ protein: Double, carb, fat    │ onMacroClick  │
├───────────────────────┼─────────────────────────────────┼───────────────────────────────┼───────────────┤
│ DailyMealSection      │ Header, (+) btn, (i) btn, items │ title: String, foods: List, cal│ onAddFood,    │
│                       │                                 │                               │ onInfoClick   │
├───────────────────────┼─────────────────────────────────┼───────────────────────────────┼───────────────┤
│ CalendarDayCell       │ 40dp box, color fill, date num  │ day: Int, status: Performance │ onDateClick   │
├───────────────────────┼─────────────────────────────────┼───────────────────────────────┼───────────────┤
│ CalendarLegend        │ 5 colored indicator blocks      │ levels: List<Color>           │ None          │
├───────────────────────┼─────────────────────────────────┼───────────────────────────────┼───────────────┤
│ WeightLineGraph       │ Bezier chart, axis, start/cur   │ entries: List<WeightEntry>    │ onRangeChange │
├───────────────────────┼─────────────────────────────────┼───────────────────────────────┼───────────────┤
│ NutritionFactsPanel   │ FDA style nutrition fact table  │ facts: NutritionFactModel     │ onUnitChange  │
├───────────────────────┼─────────────────────────────────┼───────────────────────────────┼───────────────┤
│ PrimaryButton         │ Full width green rounded button │ text: String, isEnabled: Bool │ onClick       │
├───────────────────────┼─────────────────────────────────┼───────────────────────────────┼───────────────┤
│ EmptyStateCard        │ Centered icon + prompt text     │ icon: IconRef, message: String│ onActionClick │
└───────────────────────┴─────────────────────────────────┴───────────────────────────────┴───────────────┘
16. Screen-by-Screen Specification Master Table┌─────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                    MASTER SCREEN SPECIFICATION TABLE                                    │
├───────────────────┬──────────────────────┬──────────────────────┬───────────────────────┬───────────────┤
│ Screen Name       │ Purpose              │ Entry Point          │ Main Components       │ Key Actions   │
├───────────────────┼──────────────────────┼──────────────────────┼───────────────────────┼───────────────┤
│ Home Dashboard    │ Primary daily diary  │ App Launch /         │ DateBar, CalorieCard, │ Log food,     │
│                   │ and macro tracking   │ Bottom Nav Home      │ MealSections, BottomNav│ change date  │
├───────────────────┼──────────────────────┼──────────────────────┼───────────────────────┼───────────────┤
│ Food Search       │ 50k offline food     │ Top Search Bar /     │ SearchField, LazyList,│ Search query, │
│                   │ discovery            │ (+) on Meal Section  │ Category Recents      │ select food   │
├───────────────────┼──────────────────────┼──────────────────────┼───────────────────────┼───────────────┤
│ Food Detail & Log │ Serving adjustment & │ Tap item in Search   │ NutritionFactsPanel,  │ Change qty,   │
│                   │ nutrient review      │ or Diary             │ MacroPieChart, LogBtn │ select meal   │
├───────────────────┼──────────────────────┼──────────────────────┼───────────────────────┼───────────────┤
│ Calendar Screen   │ Monthly calorie      │ Bottom Nav Stats /   │ MonthGrid, LegendBar, │ Tap date,     │
│                   │ adherence overview   │ Drawer               │ AdherenceStats        │ change month  │
├───────────────────┼──────────────────────┼──────────────────────┼───────────────────────┼───────────────┤
│ Weight Tracker    │ Body weight logging  │ Stats Tab /          │ IntervalDropdown,     │ Add weight,   │
│                   │ and trend graph      │ Diary Weigh-in (+)   │ WeightLineGraph, Stat │ change interval│
├───────────────────┼──────────────────────┼──────────────────────┼───────────────────────┼───────────────┤
│ Water Tracker     │ Hydration tracking   │ Diary Water (+)      │ WaterProgressBar,     │ Quick-add ml, │
│                   │                      │                      │ QuickAddPills         │ set goal      │
├───────────────────┼──────────────────────┼──────────────────────┼───────────────────────┼───────────────┤
│ Custom Foods List │ Library of user-     │ Drawer Menu:         │ SearchBar, FoodList,  │ Add new food, │
│                   │ created food items   │ Custom Foods         │ (+) FloatingActionButton│ edit custom  │
├───────────────────┼──────────────────────┼──────────────────────┼───────────────────────┼───────────────┤
│ Edit Custom Food  │ Form to create/edit  │ Custom Foods List /  │ NutrientInputTable,   │ Save food,    │
│                   │ user foods           │ Search fallback      │ SaveButton, LogButton │ cancel edits  │
├───────────────────┼──────────────────────┼──────────────────────┼───────────────────────┼───────────────┤
│ Edit Daily Goals  │ Configure calorie    │ Drawer Menu:         │ CalorieLimitInput,    │ Update goals, │
│                   │ and macro splits     │ Daily Goals          │ MacroPercentageInputs │ auto-calc g   │
├───────────────────┼──────────────────────┼──────────────────────┼───────────────────────┼───────────────┤
│ Recipe Manager    │ Multi-ingredient     │ Drawer Menu:         │ RecipeList,           │ Build recipe, │
│                   │ dish calculation     │ My Recipes           │ ServingMultiplierCard │ log per-serv  │
├───────────────────┼──────────────────────┼──────────────────────┼───────────────────────┼───────────────┤
│ Preferences       │ Units, profile,      │ Bottom Nav: Prefs /  │ ProfileInputsTable,   │ Change units, │
│                   │ app settings         │ Drawer Menu          │ UnitDropdownSelectors │ manage backup │
├───────────────────┼──────────────────────┼──────────────────────┼───────────────────────┼───────────────┤
│ Backup & Port     │ Data export & import │ Preferences Sub-item │ ExportCard, ImportBtn,│ Export JSON,  │
│                   │ portability          │ Data Management      │ ConflictDialog        │ restore DB    │
└───────────────────┴──────────────────────┴──────────────────────┴───────────────────────┴───────────────┘
17. Interaction & Gesture Specification┌────────────────────────────────────────────────────────────────────────┐
│                         INTERACTION EVENT MATRIX                       │
├───────────────────┬──────────────────────┬─────────────────────────────┤
│ User Interaction  │ UI Element Target    │ Resulting System Action     │
├───────────────────┼──────────────────────┼─────────────────────────────┤
│ Tap               │ Calendar Day Cell    │ Opens Dashboard on tapped   │
│                   │                      │ historical date             │
├───────────────────┼──────────────────────┼─────────────────────────────┤
│ Tap               │ Meal Header (+) Icon │ Opens Food Search with meal │
│                   │                      │ context pre-selected        │
├───────────────────┼──────────────────────┼─────────────────────────────┤
│ Tap               │ Meal Header (i) Icon │ Opens aggregated Nutrition  │
│                   │                      │ Facts modal for that meal   │
├───────────────────┼──────────────────────┼─────────────────────────────┤
│ Swipe Left        │ Logged Food Row      │ Reveals Red "Delete" action │
│                   │                      │ button with confirmation    │
├───────────────────┼──────────────────────┼─────────────────────────────┤
│ Long Press        │ Logged Food Row      │ Opens quick actions: Copy to│
│                   │                      │ another meal, Edit, Delete  │
├───────────────────┼──────────────────────┼─────────────────────────────┤
│ Horizontal Swipe  │ Date Ribbon          │ Slides smoothly to Previous │
│                   │                      │ or Next Day                 │
├───────────────────┼──────────────────────┼─────────────────────────────┤
│ System Back       │ From Search / Detail │ Returns to Diary preserving │
│                   │                      │ scroll & date context       │
└───────────────────┴──────────────────────┴─────────────────────────────┘
18. Responsive & Device Form Factor BehaviorCompact Devices (< 360dp width):Calendar day cell padding reduces from 6dp to 3dp. Date font size scales down from 14sp to 12sp.Macro indicator strip simplifies text layout (e.g., 96g Prot instead of 96g Protein).Standard & Large Phones (360dp – 480dp width):Default layout as specified in Sections 1–16.Dynamic Font Accessibility Scaling:Layouts utilize Android sp dimensions paired with fillMaxWidth() and constrained text rows.If user system font scale $> 1.2\times$, multi-column cards (such as the Macro strip or Calendar summary) convert to vertically stacked rows to prevent text truncation.Long Food Names Handling:Food item names in search and diary rows maintain maxLines = 1 with overflow = TextOverflow.Ellipsis.Full un-truncated names are displayed on the Food Detail screen with maxLines = 3.19. Performance Requirements (50k Food Database)FTS5 Indexing:Local SQLite database must index food_name and brand_name using SQLite FTS5 extension.Search queries execute using MATCH 'query*' with prefix matching. Query response time must not exceed 35 milliseconds on standard Android hardware.Memory Management & Virtualization:Zero bulk loading: The 50,000 food records are never loaded into RAM simultaneously.Database rows map to lightweight domain POJOs directly inside Room DAOs emitting PagingData.UI Main-Thread Protection:All database operations, calculations, JSON import/export routines, and aggregation queries must execute strictly on background threads (Dispatchers.IO).UI thread maintains 60fps / 120fps frame rates with zero jank during list scrolling.20. Centralized Configuration SpecificationsThe following tokens must be declared in centralized configuration objects rather than hard-coded into individual composables:┌────────────────────────────────────────────────────────────────────────┐
│                  CENTRALIZED CONFIGURATION MODULES                     │
├──────────────────────────┬─────────────────────────────────────────────┤
│ Configuration Object     │ Configured Constants & Properties           │
├──────────────────────────┼─────────────────────────────────────────────┤
│ AppColors                │ Primary, Surface, Background, Macros, Alert │
│ AppTypography            │ Headings, Body, Captions, Nutrition Numbers │
│ AppSpacing               │ xs (4dp), sm (8dp), md (12dp), lg (16dp)... │
│ CalendarAdherenceConfig  │ optimalMin (0.85), optimalMax (1.05),       │
│                          │ overBudgetThreshold (1.20)                  │
│ NutritionStandardConfig  │ Standard Daily Values: Sodium (2300mg),     │
│                          │ Fiber (28g), Calcium (1300mg), etc.         │
│ DatabaseConfig           │ DB_NAME ("foods_offline.db"), VERSION (1)   │
│ PortabilityConfig        │ BACKUP_SCHEMA_VERSION ("1.0.0")             │
└──────────────────────────┴─────────────────────────────────────────────┘
21. Originality & Legal Safety GuidelinesZero Proprietary Assets: Do not import or bundle proprietary bitmap assets, icons, copyrighted graphics, or brand trademarks from the reference screenshots.Original Icon Set: Utilize standard open-source Material Design icons.Branding Replacement: Replace any reference app naming with the project's own application name and neutral vector logo mark.Database Provenance: The 50,000 offline food dataset must originate from open public-domain nutrition datasets (such as USDA FoodData Central) formatted into the application's clean SQLite schema.Summary Inventories & Implementation BlueprintComplete Screen InventoryScreen_HomeDashboard (Main daily diary, macro progress, meal lists)Screen_FoodSearch (Offline FTS search with instant debounced results)Screen_FoodDetail (FDA nutrition table, quantity adjuster, macro donut)Screen_CalendarAdherence (Monthly heatmap, adherence stats, date switcher)Screen_WeightTracker (Bezier trend graph, date range picker, delta calculations)Screen_WaterTracker (Hydration progress bar, quick-add volume pills)Screen_Statistics (Multi-metric analytics, consistency heatmap)Screen_CustomFoodsList (User-created food directory)Screen_EditCustomFood (Nutrient input form with real-time validation)Screen_RecipeManager (Composite ingredient editor & serving calculator)Screen_DailyGoals (Calorie target and macro percentage configurator)Screen_PreferencesProfile (User biometric details and unit toggles)Screen_ImportExport (Encrypted/validated JSON/SQLite data portability)Data Provenance Matrix┌────────────────────────────────────────────────────────────────────────┐
│                         DATA PROVENANCE MATRIX                         │
├──────────────────────────┬─────────────────────────────────────────────┤
│ Data Element Category    │ Provenance / Storage Layer                  │
├──────────────────────────┼─────────────────────────────────────────────┤
│ 50,000 Static Foods      │ Read-Only Local SQLite Database (FTS5)      │
│ Daily Diary Logs         │ User Room Database (DiaryEntryEntity)       │
│ Custom Foods & Recipes   │ User Room Database (CustomFoodEntity)       │
│ Weight & Water Records   │ User Room Database (WeightEntity, WaterLog) │
│ User Calorie/Macro Goals │ Encrypted SharedPreferences / DataStore     │
│ Unit & Profile Settings  │ User Preferences DataStore                  │
│ Performance Thresholds   │ Centralized AppConfig Code Objects          │
└──────────────────────────┴─────────────────────────────────────────────┘