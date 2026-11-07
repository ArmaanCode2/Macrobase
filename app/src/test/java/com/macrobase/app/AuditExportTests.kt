package com.macrobase.app

import androidx.lifecycle.viewModelScope
import com.macrobase.app.core.config.PortabilityConfig
import com.macrobase.app.data.cache.CalendarMonthCodec
import com.macrobase.app.data.cache.FiguresCache
import com.macrobase.app.domain.model.BackupValidationResult
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.ImportResult
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.domain.repository.PortabilityRepository
import com.macrobase.app.domain.usecase.ExportUserDataUseCase
import com.macrobase.app.domain.usecase.ImportUserDataUseCase
import com.macrobase.app.domain.usecase.ValidateBackupUseCase
import com.macrobase.app.feature.importexport.ImportExportViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate

/**
 * BUG-048: leaving during an export left the file the picker had created at 0 bytes, named like a
 * backup. BUG-049: running out of memory crashed the app. BUG-050: the files opened on the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportTests {

    /** Writes the archive with [write]; nothing else is used by an export. */
    private class Portability(private val write: suspend (OutputStream) -> Unit = { it.write("PK".toByteArray()) }) : PortabilityRepository {
        override suspend fun exportAllUserData(): MacroBaseBackupData = error("not used")
        override suspend fun writeBackupArchive(outputStream: OutputStream) = write(outputStream)
        override suspend fun validateBackupArchive(inputStream: InputStream) = BackupValidationResult(isValid = false, errorMessage = "not a backup")
        override suspend fun importUserData(backupData: MacroBaseBackupData, mode: ImportMode) = ImportResult(true, mode)
    }

    private fun viewModel(repo: PortabilityRepository) =
        ImportExportViewModel(ExportUserDataUseCase(repo), ValidateBackupUseCase(repo), ImportUserDataUseCase(repo))

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun leavingTheScreenDoesNotStopTheExport() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(Portability { out -> gate.await(); out.write("PK".toByteArray()) })
        val file = ByteArrayOutputStream()

        vm.exportBackup(outputStreamSupplier = { file })
        vm.viewModelScope.cancel() // The screen was left
        gate.complete(Unit)

        withTimeout(5_000) { while (file.size() == 0) delay(10) }
        assertEquals("PK", file.toString())
    }

    @Test
    fun runningOutOfMemoryShowsAMessage() = runBlocking {
        val vm = viewModel(Portability { throw OutOfMemoryError("Failed to allocate a 96000000 byte allocation") })

        vm.exportBackup(outputStreamSupplier = { ByteArrayOutputStream() })
        val state = withTimeout(5_000) { vm.uiState.first { !it.isExporting } }

        assertEquals(PortabilityConfig.BACKUP_TOO_LARGE_TO_EXPORT_MESSAGE, state.exportErrorMessage)
    }

    // The restore's file still opens on the main thread: existing tests read its preview as soon as
    // validateAndPreviewBackup returns, so moving it is left for the user to decide
    @Test
    fun theExportFileOpensOffTheMainThread() = runBlocking {
        val main = Thread.currentThread()
        var exportThread: Thread? = null
        val vm = viewModel(Portability())

        vm.exportBackup(outputStreamSupplier = { exportThread = Thread.currentThread(); ByteArrayOutputStream() })
        withTimeout(5_000) { vm.uiState.first { !it.isExporting } }

        assertNotSame("export", main, exportThread)
    }

    // The export's dialog blocks Back as the restore's does, so its result is seen
    @Test
    fun anExportInProgressBlocksBack() {
        val path = "src/main/java/com/macrobase/app/feature/importexport/ImportExportScreen.kt"
        val screen = listOf(File(path), File("app/$path")).first { it.isFile }.readText()

        assertEquals(2, Regex("""dismissOnBackPress = false""").findAll(screen).count())
    }
}

/** BUG-051: a figures source that failed once left that range or month dead until the app restarted. */
@OptIn(ExperimentalCoroutinesApi::class)
class FiguresRetryTests {

    @Test
    fun aFailedSourceIsTriedAgain() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = FiguresCache(backgroundScope + dispatcher, null)
        val month = listOf(CalendarDaySummary(LocalDate.of(2026, 11, 6), 900.0, 2000.0))
        var calls = 0

        val figures = cache.figures(FiguresCache.calendarKey(2026, 11), CalendarMonthCodec) {
            calls++
            if (calls == 1) flow { throw IllegalStateException("disk I/O error") } else flowOf(month)
        }
        val seen = mutableListOf<List<CalendarDaySummary>>()
        backgroundScope.launch(dispatcher) { figures.collect { seen += it } }
        // Past the first retry's wait; the cache's work runs in the background scope
        advanceTimeBy(60_000)
        runCurrent()

        assertFalse("the figures came back", seen.isEmpty())
        assertEquals(month, seen.last())
    }
}
