package com.macrobase.app

import android.app.AlarmManager
import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.room.Room
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.repository.GoalsRepositoryImpl
import com.macrobase.app.data.repository.PortabilityRepositoryImpl
import com.macrobase.app.data.repository.PreferencesRepositoryImpl
import com.macrobase.app.data.repository.preferencesCorruptionHandler
import com.macrobase.app.domain.model.BackupManifestDto
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.DiaryEntryBackupDto
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.feature.widget.MacroBaseWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.RejectedExecutionException

/** BUG-053: an unreadable or corrupt settings file means defaults, not a crash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P4PhaseTwoSettingsStorageTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val context get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A store whose first [failures] reads fail, then reads [stored]; writes always fail. */
    private class FlakyStore(private var failures: Int, private val stored: Preferences) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow {
            if (failures > 0) {
                failures--
                throw IOException("disk busy")
            }
            emit(stored)
        }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("read only")
    }

    private val stored1800 = mutablePreferencesOf(doublePreferencesKey("daily_calorie_goal") to 1800.0)

    // A brief fault is retried, so the user never sees default goals for it
    @Test
    fun aBriefReadFaultNeverShowsDefaults() = runTest {
        val goals = GoalsRepositoryImpl(context, FlakyStore(failures = 2, stored = stored1800))

        assertEquals(1800.0, goals.observeGoals().first().dailyCalorieGoal, 0.0)
    }

    // After a longer fault the defaults stand in, and the real goals replace them once readable
    @Test
    fun theRealGoalsReplaceTheDefaultsWhenTheFileCanBeReadAgain() = runTest {
        val goals = GoalsRepositoryImpl(context, FlakyStore(failures = 6, stored = stored1800))
        val defaults = GoalsRepositoryImpl(context, emptyStore("fresh")).observeGoals().first()

        val seen = goals.observeGoals().take(2).toList()

        assertEquals(defaults, seen[0])
        assertEquals(1800.0, seen[1].dailyCalorieGoal, 0.0)
    }

    // A backup must hold the stored settings or fail: defaults in a backup would replace the goal
    // history and profile of whichever phone restores it
    @Test
    fun aBackupFailsInsteadOfExportingDefaults() {
        val goals = GoalsRepositoryImpl(context, BrokenStore(IOException("disk error")))
        val preferences = PreferencesRepositoryImpl(context, BrokenStore(IOException("disk error")))

        assertThrows(IOException::class.java) { runBlocking { goals.exportGoalBackup() } }
        assertThrows(IOException::class.java) { runBlocking { preferences.getPreferencesForBackup() } }
    }

    @Test
    fun aBackupOfReadableSettingsHoldsTheStoredValues() = runBlocking {
        val store = emptyStore("stored")
        store.edit { it[doublePreferencesKey("daily_calorie_goal")] = 1800.0 }

        assertEquals(1800.0, GoalsRepositoryImpl(context, store).exportGoalBackup().dailyCalorieGoal, 0.0)
    }

    /** A store whose every read and write fails, as a failing disk does. */
    private class BrokenStore(private val error: Exception) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw error }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw error
    }

    private fun emptyStore(name: String) =
        PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "$name.preferences_pb") }

    @Test
    fun goalsFallBackToDefaultsWhenTheFileCannotBeRead() = runTest {
        val defaults = GoalsRepositoryImpl(context, emptyStore("fresh")).observeGoals().first()
        val goals = GoalsRepositoryImpl(context, BrokenStore(IOException("disk error")))

        assertEquals(defaults, goals.observeGoals().first())
        // getGoals first tries to promote a scheduled change, which is a write
        assertEquals(defaults, goals.getGoals())
        val today = LocalDate.now()
        assertEquals(listOf(defaults, defaults), goals.getGoalsForDates(listOf(today.minusDays(3), today)))
        assertFalse(goals.hasSavedGoals())
    }

    @Test
    fun preferencesFallBackToDefaultsWhenTheFileCannotBeRead() = runTest {
        val defaults = PreferencesRepositoryImpl(context, emptyStore("fresh")).observePreferences().first()
        val preferences = PreferencesRepositoryImpl(context, BrokenStore(IOException("disk error")))

        assertEquals(defaults, preferences.observePreferences().first())
        assertEquals(defaults, preferences.getPreferences())
        assertFalse(preferences.hasSavedPreferences())
    }

    // A failed save is still reported, so the user is not told it worked (BUG-026)
    @Test
    fun aSaveThatFailsStillFails() {
        val preferences = PreferencesRepositoryImpl(context, BrokenStore(IOException("disk full")))

        assertThrows(IOException::class.java) { runBlocking { preferences.updatePreferences(defaultPreferences()) } }
    }

    // Only a read failure is turned into defaults; a programming error still surfaces
    @Test
    fun anErrorThatIsNotAReadFailureStillFails() {
        val goals = GoalsRepositoryImpl(context, BrokenStore(IllegalStateException("bug")))

        assertThrows(IllegalStateException::class.java) { runBlocking { goals.observeGoals().first() } }
    }

    // A truncated file is replaced with an empty one, after which reads and writes work again
    @Test
    fun aCorruptFileIsReplacedAndStaysUsable() = runBlocking {
        val file = File(tempFolder.root, "goals.preferences_pb")
        // A length-delimited field that claims 127 bytes but holds 1: protobuf rejects it as truncated
        file.writeBytes(byteArrayOf(0x0A, 0x7F, 0x01))
        val store = PreferenceDataStoreFactory.create(corruptionHandler = preferencesCorruptionHandler, scope = scope) { file }
        val defaults = GoalsRepositoryImpl(context, emptyStore("fresh")).observeGoals().first()

        assertEquals(defaults, GoalsRepositoryImpl(context, store).observeGoals().first())
        val key = doublePreferencesKey("daily_calorie_goal")
        store.edit { it[key] = 1800.0 }
        assertEquals(1800.0, store.data.first()[key]!!, 0.0)
    }

    // Without the handler the same file fails every read, so the test above proves the handler works
    @Test
    fun withoutTheHandlerTheSameFileFails() {
        val file = File(tempFolder.root, "goals.preferences_pb").apply { writeBytes(byteArrayOf(0x0A, 0x7F, 0x01)) }
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }

        assertThrows(IOException::class.java) { runBlocking { store.data.first() } }
    }

    private fun defaultPreferences() = runBlocking {
        PreferencesRepositoryImpl(context, emptyStore("defaults")).getPreferences()
    }
}

/** BUG-048: the widget refreshes at midnight, when the clock or zone changes, and after an import. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P4PhaseTwoWidgetTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val india = ZoneId.of("Asia/Kolkata")

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ---------------------------------------------------------------------------------------
    // The next midnight
    // ---------------------------------------------------------------------------------------

    @Test
    fun midnightIsTheStartOfTomorrow() {
        val morning = ZonedDateTime.of(LocalDateTime.of(2026, 10, 10, 10, 0), india)

        assertEquals(
            ZonedDateTime.of(LocalDateTime.of(2026, 10, 11, 0, 0), india).toInstant().toEpochMilli(),
            MacroBaseWidgetProvider.nextMidnightMillis(morning)
        )
    }

    @Test
    fun oneSecondBeforeMidnightGivesOneSecondLater() {
        val late = ZonedDateTime.of(LocalDateTime.of(2026, 10, 10, 23, 59, 59), india)

        assertEquals(late.toInstant().toEpochMilli() + 1_000L, MacroBaseWidgetProvider.nextMidnightMillis(late))
    }

    // Where a DST change skips midnight, the refresh goes to the first valid time of the day
    @Test
    fun aDaylightSavingDayThatSkipsMidnightUsesItsFirstValidTime() {
        val (zone, transition) = listOf("America/Santiago", "America/Havana", "Asia/Beirut", "America/Asuncion")
            .map { ZoneId.of(it) }
            .flatMap { zone ->
                zone.rules.transitions.map { zone to it }
            }
            .first { (_, t) -> t.isGap && t.dateTimeBefore.toLocalTime() == LocalTime.MIDNIGHT && t.dateTimeBefore.year >= 2015 }
        val dayBefore = ZonedDateTime.of(transition.dateTimeBefore.minusHours(12), zone)

        assertEquals(transition.instant.toEpochMilli(), MacroBaseWidgetProvider.nextMidnightMillis(dayBefore))
    }

    // ---------------------------------------------------------------------------------------
    // The alarm
    // ---------------------------------------------------------------------------------------

    @Test
    fun theRefreshIsBookedForMidnightWithoutWakingThePhone() {
        val now = ZonedDateTime.of(LocalDateTime.of(2026, 10, 10, 15, 0), india)

        MacroBaseWidgetProvider.scheduleMidnightRefresh(context, now)

        val alarm = alarms.scheduledAlarms.single()
        assertEquals(AlarmManager.RTC, alarm.type)
        assertEquals(MacroBaseWidgetProvider.nextMidnightMillis(now), alarm.triggerAtMs)
        assertEquals(MacroBaseWidgetProvider.MIDNIGHT_WINDOW_MILLIS, alarm.windowLengthMs)
        val target = shadowOf(alarm.operation).savedIntent
        assertEquals(MacroBaseWidgetProvider.ACTION_MIDNIGHT_REFRESH, target.action)
        assertEquals(MacroBaseWidgetProvider::class.java.name, target.component?.className)
    }

    @Test
    fun bookingAgainReplacesTheEarlierAlarm() {
        val now = ZonedDateTime.of(LocalDateTime.of(2026, 10, 10, 15, 0), india)

        MacroBaseWidgetProvider.scheduleMidnightRefresh(context, now)
        MacroBaseWidgetProvider.scheduleMidnightRefresh(context, now.plusDays(1))

        val alarm = alarms.scheduledAlarms.single()
        assertEquals(MacroBaseWidgetProvider.nextMidnightMillis(now.plusDays(1)), alarm.triggerAtMs)
    }

    // Every widget update, the 30-minute periodic one included, books the next midnight
    @Test
    fun aWidgetUpdateBooksTheMidnightRefresh() {
        val provider = MacroBaseWidgetProvider()
        context.registerReceiver(provider, IntentFilter(AppWidgetManager.ACTION_APPWIDGET_UPDATE), android.content.Context.RECEIVER_NOT_EXPORTED)

        context.sendBroadcast(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setPackage(context.packageName)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(7))
        )
        shadowOf(Looper.getMainLooper()).idle()

        val alarm = alarms.peekNextScheduledAlarm()
        assertNotNull(alarm)
        assertEquals(MacroBaseWidgetProvider.ACTION_MIDNIGHT_REFRESH, shadowOf(alarm!!.operation).savedIntent.action)
        context.unregisterReceiver(provider)
    }

    @Test
    fun removingTheLastWidgetCancelsTheAlarm() {
        MacroBaseWidgetProvider.scheduleMidnightRefresh(context)

        MacroBaseWidgetProvider().onDisabled(context)

        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // Broadcasts that refresh
    // ---------------------------------------------------------------------------------------

    private fun bindWidget(id: Int) {
        val info = AppWidgetProviderInfo().apply { provider = ComponentName(context, MacroBaseWidgetProvider::class.java) }
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(id, info)
    }

    private fun widgetUpdatesSent() = shadowOf(context).broadcastIntents
        .filter { it.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE }

    // The receiver only hears these broadcasts if the manifest asks for them
    @Test
    fun theManifestRoutesClockAndZoneChangesToTheWidget() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val android = "http://schemas.android.com/apk/res/android"
        val receivers = document.getElementsByTagName("receiver")
        val widget = (0 until receivers.length).map { receivers.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(android, "name") == ".feature.widget.MacroBaseWidgetProvider" }
        val actions = widget.getElementsByTagName("action").let { list ->
            (0 until list.length).map { (list.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") }
        }

        assertTrue(actions.toString(), Intent.ACTION_TIME_CHANGED in actions)
        assertTrue(actions.toString(), Intent.ACTION_TIMEZONE_CHANGED in actions)
        // Not delivered to manifest receivers since Android 8; the midnight alarm covers it
        assertTrue(actions.toString(), Intent.ACTION_DATE_CHANGED !in actions)
    }

    @Test
    fun aClockChangeRefreshesTheWidget() {
        bindWidget(7)

        MacroBaseWidgetProvider().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))

        val update = widgetUpdatesSent().single()
        assertTrue(update.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)!!.contentEquals(intArrayOf(7)))
    }

    @Test
    fun aTimeZoneChangeAndTheMidnightAlarmRefreshTheWidget() {
        bindWidget(7)

        MacroBaseWidgetProvider().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        MacroBaseWidgetProvider().onReceive(context, Intent(MacroBaseWidgetProvider.ACTION_MIDNIGHT_REFRESH))

        assertEquals(2, widgetUpdatesSent().size)
    }

    // Some phones hold back the midnight alarm and the 30-minute update while the app is closed;
    // opening the app redraws the widget, so it never waits for the next food logged
    @Test
    fun openingTheAppRefreshesTheWidget() {
        bindWidget(7)

        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).create().start()

        val update = widgetUpdatesSent().single()
        assertTrue(update.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)!!.contentEquals(intArrayOf(7)))
    }

    @Test
    fun anUnrelatedBroadcastDoesNotRefresh() {
        bindWidget(7)

        MacroBaseWidgetProvider().onReceive(context, Intent(Intent.ACTION_LOCALE_CHANGED))

        assertTrue(widgetUpdatesSent().isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // After an import
    // ---------------------------------------------------------------------------------------

    private var refreshes = 0
    private lateinit var db: UserDatabase

    /** With [failTransactions], every database transaction fails, as on a full disk. */
    private fun portability(failTransactions: Boolean = false): PortabilityRepositoryImpl {
        val goalsStore = PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "goals.preferences_pb") }
        val prefsStore = PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "prefs.preferences_pb") }
        db = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { if (failTransactions) throw RejectedExecutionException("no thread") else it.run() }
            .build()
        return PortabilityRepositoryImpl(
            db,
            GoalsRepositoryImpl(context, goalsStore),
            PreferencesRepositoryImpl(context, prefsStore),
            onUserDataChanged = { refreshes++ }
        )
    }

    /** Today's breakfast and nothing else: no goals, no profile. */
    private fun todaysFood() = MacroBaseBackupData(
        manifest = BackupManifestDto(exportedAt = "2026-10-10T08:00:00Z", timeZone = "UTC", counts = BackupRecordCountsDto()),
        diaryEntries = listOf(
            DiaryEntryBackupDto(
                uuid = "d-1",
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

    @Test
    fun aMergeThatLeavesTheGoalsAloneStillRefreshesTheWidget() = runBlocking {
        val result = portability().importUserData(todaysFood(), ImportMode.MERGE)

        assertTrue(result.errorMessage, result.isSuccess)
        assertEquals(1, refreshes)
        db.close()
    }

    @Test
    fun anOverwriteRefreshesTheWidgetOnce() = runBlocking {
        val result = portability().importUserData(todaysFood(), ImportMode.OVERWRITE)

        assertTrue(result.errorMessage, result.isSuccess)
        assertEquals(1, refreshes)
        db.close()
    }

    @Test
    fun aFailedImportDoesNotRefresh() = runBlocking {
        val result = portability(failTransactions = true).importUserData(todaysFood(), ImportMode.MERGE)

        assertFalse(result.isSuccess)
        assertEquals(0, refreshes)
        db.close()
    }
}
