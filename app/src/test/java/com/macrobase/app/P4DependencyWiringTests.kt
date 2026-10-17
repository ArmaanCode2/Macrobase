package com.macrobase.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import com.macrobase.app.core.config.DatabaseConfig
import com.macrobase.app.core.di.appModules
import com.macrobase.app.core.di.databaseModule
import com.macrobase.app.core.di.repositoryModule
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.database.dao.DiaryDao
import com.macrobase.app.data.database.entity.DiaryEntryEntity
import com.macrobase.app.data.provider.FoodDataProvider
import com.macrobase.app.domain.model.BackupManifestDto
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.DiaryEntryBackupDto
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.repository.PortabilityRepository
import com.macrobase.app.domain.usecase.MoveDiaryEntriesUseCase
import com.macrobase.app.feature.dashboard.HomeViewModel
import com.macrobase.app.feature.widget.MacroBaseWidgetProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/**
 * The app's own Koin modules connect the P4 fixes: a repository built with a missing argument
 * would compile and pass its unit tests while the feature stays off on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P4DependencyWiringTests {

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var koin: Koin

    @Before
    fun setUp() {
        // Installed the way a phone has it, so the catalog opens without reading app assets
        val catalog = listOf(File("src/main/assets/databases/built_in_foods.db"), File("app/src/main/assets/databases/built_in_foods.db"))
            .first { it.isFile }
        val installed = app.getDatabasePath(DatabaseConfig.BUILT_IN_FOODS_DB_NAME)
        installed.parentFile?.mkdirs()
        catalog.copyTo(installed, overwrite = true)
        koin = koinApplication {
            androidContext(app)
            modules(databaseModule, repositoryModule)
        }.koin
    }

    @After
    fun tearDown() {
        koin.get<UserDatabase>().close()
        koin.close()
    }

    // The meal card's Move does nothing without its use case, so the dashboard must be built with it
    @Test
    fun theDashboardCanMoveAWholeMeal() {
        val all = koinApplication {
            androidContext(app)
            modules(appModules)
        }.koin
        try {
            val home = all.get<HomeViewModel>()
            val move = HomeViewModel::class.java.getDeclaredField("moveDiaryEntriesUseCase")
                .apply { isAccessible = true }
                .get(home)
            assertTrue(move is MoveDiaryEntriesUseCase)
        } finally {
            all.get<UserDatabase>().close()
            all.close()
        }
    }

    // BUG-055: the food repository gets the diary, so "Recently logged" has something to show
    @Test
    fun recentlyLoggedReadsTheDiary() = runBlocking {
        val paneer = koin.get<FoodDataProvider>().searchFoods("paneer", 1).first()
        koin.get<DiaryDao>().insertEntry(
            DiaryEntryEntity(
                uuid = "d-1",
                dateEpochDay = LocalDate.now().toEpochDay(),
                mealType = "LUNCH",
                foodId = paneer.id,
                foodName = paneer.name,
                userQuantity = 1.0,
                servingDescription = "100 g",
                gramWeight = 100.0,
                loggedCalories = 260.0,
                loggedProtein = 18.0,
                loggedCarbs = 4.0,
                loggedFat = 20.0
            )
        )

        assertEquals(listOf(paneer.id), koin.get<FoodRepository>().getRecentFoods(10).map { it.id })
    }

    // BUG-048: a restore refreshes the home-screen widget
    @Test
    fun anImportRefreshesTheWidget() = runBlocking {
        val info = AppWidgetProviderInfo().apply { provider = ComponentName(app, MacroBaseWidgetProvider::class.java) }
        shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(7, info)
        val backup = MacroBaseBackupData(
            manifest = BackupManifestDto(exportedAt = "2026-10-10T08:00:00Z", timeZone = "UTC", counts = BackupRecordCountsDto()),
            diaryEntries = listOf(
                DiaryEntryBackupDto(
                    uuid = "d-2",
                    dateEpochDay = LocalDate.now().toEpochDay(),
                    dateString = LocalDate.now().toString(),
                    mealType = "BREAKFAST",
                    foodId = 1L,
                    foodName = "Oats",
                    userQuantity = 1.0,
                    servingDescription = "100 g",
                    gramWeight = 100.0,
                    loggedCalories = 380.0,
                    loggedProtein = 13.0,
                    loggedCarbs = 67.0,
                    loggedFat = 7.0,
                    createdAt = 1_000L
                )
            )
        )

        val result = koin.get<PortabilityRepository>().importUserData(backup, ImportMode.MERGE)

        assertTrue(result.errorMessage, result.isSuccess)
        assertTrue(shadowOf(app).broadcastIntents.any { it.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE })
    }
}
