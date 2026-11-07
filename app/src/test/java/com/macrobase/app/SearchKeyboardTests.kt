package com.macrobase.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun source(path: String): String =
    listOf(File("src/main/java/com/macrobase/app/$path"), File("app/src/main/java/com/macrobase/app/$path"))
        .first { it.isFile }.readText().replace("\r\n", "\n")

/**
 * Search opened with the keyboard closed, so every search took a second tap on the field. The project has no
 * Compose UI tests, so these check the code that focuses the field.
 */
class SearchKeyboardTests {

    // Focus asked for before the window has it opens no keyboard; coming back from a food must not open it again.
    // In this order: the guard, the wait, the focus that starts typing, the keyboard, and the mark that it ran
    @Test
    fun theFocusWaitsForTheWindowAndHappensOncePerVisit() {
        val helper = source("core/designsystem/components/MbSearch.kt")
        val steps = listOf(
            "var focused by rememberSaveable { mutableStateOf(false) }",
            "if (focused) return@LaunchedEffect",
            "snapshotFlow { windowInfo.isWindowFocused }.first { it }",
            "focusRequester.requestFocus()",
            "keyboard?.show()",
            "focused = true"
        )

        assertTrue(helper.contains("fun rememberSearchFocus(): FocusRequester"))
        steps.zipWithNext().forEach { (before, after) ->
            assertTrue("$before before $after", helper.indexOf(before) in 0 until helper.indexOf(after))
        }
    }

    @Test
    fun theSearchScreenFocusesItsField() {
        val search = source("feature/search/SearchScreen.kt")

        assertTrue(search.contains("val searchFocus = rememberSearchFocus()"))
        assertTrue(Regex("""BasicTextField\([\s\S]{0,600}?\.focusRequester\(searchFocus\)""").containsMatchIn(search))
    }

    // With the keyboard up from the start, the last results must still scroll into view above it
    @Test
    fun theSearchResultsEndAboveTheKeyboard() {
        val search = source("feature/search/SearchScreen.kt")
        val screen = search.substring(search.indexOf("fun SearchScreen("))

        assertTrue(Regex("""Column\(\s*modifier = modifier\s*\.fillMaxSize\(\)\s*\.imePadding\(\)""").containsMatchIn(screen))
    }

    @Test
    fun addIngredientFocusesItsSearch() {
        val recipes = source("feature/recipes/RecipesScreen.kt")

        assertTrue(recipes.contains("val ingredientFocus = rememberSearchFocus()"))
        assertTrue(Regex("""placeholder = "Search ingredient\.\.\.",[\s\S]{0,400}?\.focusRequester\(ingredientFocus\)""").containsMatchIn(recipes))
    }
}
