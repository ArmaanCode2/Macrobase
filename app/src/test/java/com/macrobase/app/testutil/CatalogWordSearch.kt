package com.macrobase.app.testutil

import com.macrobase.app.data.provider.LocalFoodDatabaseProvider
import java.sql.Connection

/**
 * The names [query] finds through LocalFoodDatabaseProvider's word search, best first, on a JDBC
 * connection to the catalog. The substring fallback for fragments is not included.
 */
fun Connection.catalogWordSearch(query: String, limit: Int = 30): List<String> {
    val words = LocalFoodDatabaseProvider.searchWords(query)
    if (words.isEmpty()) return emptyList()
    return prepareStatement(LocalFoodDatabaseProvider.wordSearchSql(words.size)).use { statement ->
        LocalFoodDatabaseProvider.wordSearchArgs(words, limit).forEachIndexed { i, arg -> statement.setString(i + 1, arg) }
        statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }
    }
}
