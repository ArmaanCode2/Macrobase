package com.macrobase.app

import com.macrobase.app.core.config.DatabaseConfig
import com.macrobase.app.data.database.BuiltInDatabaseManager
import com.macrobase.app.data.provider.LocalFoodDatabaseProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Everyday searches through LocalFoodDatabaseProvider.searchFoods and the bundled catalog. Robolectric's
 * SQLite has no FTS5, and neither has Android's (AOSP builds it with FTS3 and FTS4 only), so this is
 * the search a phone runs. The cases live in tools/catalog/search_cases.csv: an "ok" case must find
 * its food first, and a "gap" case is a known miss that fails the test once it passes, so the file
 * always shows which searches work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogSearchTests {

    private data class SearchCase(val query: String, val expect: Regex, val top: Int, val status: String, val foundToday: String)

    private lateinit var provider: LocalFoodDatabaseProvider

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        // Both catalogs, installed the way a phone has them, so the managers open them instead of
        // copying from assets: the app merges the branded products into every search (BUG-046)
        for (name in listOf(DatabaseConfig.BUILT_IN_FOODS_DB_NAME, DatabaseConfig.BRANDED_FOODS_DB_NAME)) {
            val installed = context.getDatabasePath(name)
            installed.parentFile?.mkdirs()
            listOf(File("src/main/assets/databases/$name"), File("app/src/main/assets/databases/$name"))
                .first { it.isFile }
                .copyTo(installed, overwrite = true)
        }
        provider = LocalFoodDatabaseProvider(
            BuiltInDatabaseManager(context),
            BuiltInDatabaseManager(context, BuiltInDatabaseManager.Catalog.BRANDED)
        )
    }

    @Test
    fun everydaySearchesFindTheirFoodFirst() = runBlocking {
        val cases = readCases()
        assertTrue("search_cases.csv has only ${cases.size} cases", cases.size >= 100)

        val problems = cases.mapNotNull { case ->
            // The limit the Search screen asks for (SearchFoodsUseCase)
            val found = provider.searchFoods(case.query, 30).map { it.name }
            val passes = found.take(case.top).any { case.expect.containsMatchIn(it) }
            when {
                case.status == "ok" && !passes ->
                    "'${case.query}' finds ${found.firstOrNull() ?: "nothing"} first, expected /${case.expect.pattern}/"
                case.status == "gap" && passes ->
                    "'${case.query}' now finds ${found.first()}: change its status to ok in search_cases.csv"
                case.status == "gap" && found.isEmpty() && case.foundToday != NOTHING ->
                    "'${case.query}' found ${case.foundToday} when the case was written and finds nothing now"
                else -> null
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun everyCaseHasAKnownStatus() {
        val statuses = readCases().map { it.status }.toSet()
        assertEquals(emptySet<String>(), statuses - setOf("ok", "gap"))
    }

    private fun readCases(): List<SearchCase> {
        val file = listOf(File("../tools/catalog/search_cases.csv"), File("tools/catalog/search_cases.csv")).first { it.isFile }
        val rows = file.readLines(Charsets.UTF_8)
            .map { it.removePrefix("\uFEFF") }
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .map(::splitCsvLine)
        val header = rows.first()
        return rows.drop(1).map { cells ->
            val row = header.zip(cells).toMap()
            SearchCase(
                query = row.getValue("query"),
                expect = Regex(row.getValue("expect"), RegexOption.IGNORE_CASE),
                top = row.getValue("top").toInt(),
                status = row.getValue("status"),
                foundToday = row.getValue("found_today")
            )
        }
    }

    /** One CSV line: commas separate cells, double quotes wrap a cell and "" is a quote inside one. */
    private fun splitCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { cells.add(cell.toString()); cell.clear() }
                else -> cell.append(c)
            }
            i++
        }
        cells.add(cell.toString())
        return cells
    }

    private companion object {
        const val NOTHING = "(nothing)"
    }
}
