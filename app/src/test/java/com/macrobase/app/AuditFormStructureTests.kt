package com.macrobase.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BUG-002: state held in the composition is lost on rotation and on a trip to the scanner. There is
 * no Compose test setup here, so this pins the structure; AuditFormStateTests pins the behaviour.
 */
class FormStructureTests {

    private fun source(path: String): String =
        listOf(File("src/main/java/com/macrobase/app/$path"), File("app/src/main/java/com/macrobase/app/$path"))
            .first { it.isFile }.readText()

    private val customFoods = source("feature/customfoods/CustomFoodsScreen.kt")
    private val recipes = source("feature/recipes/RecipesScreen.kt")

    @Test
    fun theFormsDoNotKeepWhatWasTypedInTheComposition() {
        assertFalse(customFoods.contains("var name by remember"))
        assertFalse(customFoods.contains("var calories by remember"))
        assertFalse(recipes.contains("var name by remember"))
        assertFalse(recipes.contains("var ingredients by remember"))
    }

    @Test
    fun theScannedDraftIsNotDroppedAsSoonAsItFillsTheForm() {
        assertFalse(customFoods.contains("var activeDraftForBanner"))
    }

    @Test
    fun bothFormsAskBeforeDiscardingChanges() {
        assertTrue(customFoods.contains("BackHandler(enabled = viewModel.hasUnsavedChanges())"))
        assertTrue(recipes.contains("BackHandler(enabled = viewModel.hasUnsavedChanges())"))
    }

    // BUG-013: a macro the scanner missed saves as 0, so the form has to say so
    @Test
    fun aBlankMacroSaysItSavesAsZero() {
        assertTrue(com.macrobase.app.feature.customfoods.blankMacroHelp("").orEmpty().contains("0 g"))
        assertTrue(com.macrobase.app.feature.customfoods.blankMacroHelp("12") == null)
        assertTrue(customFoods.contains("helpText = blankMacroHelp(form.protein)"))
        assertTrue(customFoods.contains("helpText = blankMacroHelp(form.carbs)"))
        assertTrue(customFoods.contains("helpText = blankMacroHelp(form.fat)"))
    }

    // The header arrow must reach those handlers, as the system Back does
    @Test
    fun theHeaderArrowGoesThroughBackHandling() {
        val scaffold = source("core/navigation/MacroBaseScaffold.kt")

        assertFalse(scaffold.contains("onBack = { navController.popBackStack() }"))
        assertTrue(scaffold.contains("onBackPressedDispatcher"))
    }
}
