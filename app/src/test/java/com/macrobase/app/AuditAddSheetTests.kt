package com.macrobase.app

import com.macrobase.app.core.navigation.FirstTapOnly
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BUG-023: the "+" sheet's rows stay tappable while it slides away, and every tap ran its action:
 * a copied meal went into the basket twice, and two Basket screens stacked.
 */
class AddSheetTapTests {

    private fun source(path: String): String =
        listOf(File("src/main/java/com/macrobase/app/$path"), File("app/src/main/java/com/macrobase/app/$path"))
            .first { it.isFile }.readText()

    @Test
    fun onlyTheFirstTapActs() {
        val taps = FirstTapOnly()

        assertTrue(taps.claim())
        assertFalse(taps.claim())
        assertFalse(taps.claim())
    }

    // Home's basket button too: a double tap stacked two Baskets
    @Test
    fun everyBasketButtonOpensOneBasket() {
        for (path in listOf("core/navigation/NavGraph.kt", "core/navigation/MacroBaseScaffold.kt")) {
            assertFalse(path, Regex("""navigate\(Screen\.Basket\.route\)(?! \{ launchSingleTop = true \})""").containsMatchIn(source(path)))
        }
    }

    @Test
    fun theSheetActsOnceAndTheBasketOpensOnce() {
        assertTrue(source("core/navigation/AddFoodSheet.kt").contains("if (!firstTap.claim()) return"))
        assertTrue(
            Regex("""val openBasket: \(\) -> Unit = \{\s*navController\.navigate\(Screen\.Basket\.route\) \{ launchSingleTop = true \}""")
                .containsMatchIn(source("core/navigation/MacroBaseScaffold.kt"))
        )
    }
}
