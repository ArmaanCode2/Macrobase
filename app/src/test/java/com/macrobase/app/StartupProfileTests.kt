package com.macrobase.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Performance phase 4: the startup profile (src/main/baseline-prof.txt). Android's compiler skips a
 * rule it cannot read or that matches no class without a word, so these check the rules themselves.
 */
class StartupProfileTests {

    private val profile = listOf(File("src/main/baseline-prof.txt"), File("app/src/main/baseline-prof.txt")).first { it.isFile }

    private val rules: List<String>
        get() = profile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    // The class part of a rule: what stands between its "L" and the ";" or "->" (a block comment
    // can't quote one, as Kotlin reads the slash and stars in it as a nested comment)
    private fun classPattern(rule: String) = rule.substringAfter('L').substringBefore(';').substringBefore("->")

    /** A profile wildcard as a regex: ** is anything, * anything within one name, ? one character. */
    private fun wildcard(pattern: String) = Regex(buildString {
        var i = 0
        while (i < pattern.length) {
            when {
                pattern.startsWith("**", i) -> { append(".*"); i += 2 }
                pattern[i] == '*' -> { append("[^/]*"); i++ }
                pattern[i] == '?' -> { append("[^/]"); i++ }
                else -> { append(Regex.escape(pattern[i].toString())); i++ }
            }
        }
    })

    @Test
    fun everyRuleIsWrittenInTheProfileFormat() {
        val classRule = Regex("""^L[\w/$*?]+;$""")
        val methodRule = Regex("""^[HSP]+L[\w/$*?]+;?->[\w<>$*?]+\([^)]*\)\S+$""")
        assertTrue("The profile has no rules", rules.isNotEmpty())
        rules.forEach { rule -> assertTrue(rule, classRule.matches(rule) || methodRule.matches(rule)) }
    }

    @Test
    fun everyRuleNamesTheAppsOwnCode() {
        rules.forEach { rule ->
            val fixed = classPattern(rule).substringBefore('*').substringBefore('?')
            val folder = fixed.substringBeforeLast('/') + "/"
            assertTrue(rule, folder.startsWith("com/macrobase/app/"))
            assertNotNull("No compiled code in $folder for: $rule", javaClass.classLoader!!.getResource(folder))
        }
    }

    @Test
    fun theStartupPathIsPrecompiled() {
        val hot = rules.filter { it.startsWith("H") }.map { wildcard(classPattern(it)) }
        listOf(
            "com.macrobase.app.MacroBaseApplication",
            "com.macrobase.app.MainActivity",
            "com.macrobase.app.core.navigation.MacroBaseScaffoldKt",
            "com.macrobase.app.feature.dashboard.HomeScreenKt",
            "com.macrobase.app.feature.dashboard.HomeViewModel"
        ).forEach { name ->
            Class.forName(name)
            val path = name.replace('.', '/')
            assertTrue("$name is not in a hot rule", hot.any { it.matches(path) })
        }
    }
}
