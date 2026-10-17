package com.macrobase.app.feature.importexport

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.core.config.PortabilityConfig
import com.macrobase.app.core.util.briefErrorText
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Ph
import com.macrobase.app.core.designsystem.components.MbButton
import com.macrobase.app.core.designsystem.components.MbButtonKind
import com.macrobase.app.core.designsystem.components.MbIcon
import com.macrobase.app.core.designsystem.components.MbIconCircle
import com.macrobase.app.core.designsystem.components.MbPanel
import com.macrobase.app.core.designsystem.components.isScrolled
import com.macrobase.app.core.navigation.ReportContentScrolled
import com.macrobase.app.domain.model.BackupPreview
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.ImportResult
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.domain.usecase.ExportUserDataUseCase
import com.macrobase.app.domain.usecase.ImportUserDataUseCase
import com.macrobase.app.domain.usecase.ValidateBackupUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate

data class ImportExportUiState(
    val isExporting: Boolean = false,
    val exportSuccessMessage: String? = null,
    val exportErrorMessage: String? = null,
    val isValidating: Boolean = false,
    val isImporting: Boolean = false,
    val previewData: BackupPreview? = null,
    val validationError: String? = null,
    val showOverwriteConfirmation: Boolean = false,
    val importResult: ImportResult? = null
)

class ImportExportViewModel(
    private val exportUserDataUseCase: ExportUserDataUseCase,
    private val validateBackupUseCase: ValidateBackupUseCase,
    private val importUserDataUseCase: ImportUserDataUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportExportUiState())
    val uiState: StateFlow<ImportExportUiState> = _uiState.asStateFlow()

    /**
     * The archive is built in memory first, so a failure while reading the data never touches
     * the chosen file (it may be an older backup the user picked to replace). Only a file this
     * export started writing is removed with [discardOutput] when the write fails, so no
     * half-written archive is left to be restored later (BUG-017). [discardOutput] returns
     * whether the file was deleted.
     */
    fun exportBackup(outputStreamSupplier: () -> OutputStream?, discardOutput: () -> Boolean = { false }) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportSuccessMessage = null, exportErrorMessage = null) }
            var writeStarted = false
            try {
                val archive = ByteArrayOutputStream().also { exportUserDataUseCase.writeBackupArchive(it) }.toByteArray()
                val stream = outputStreamSupplier()
                if (stream == null) {
                    _uiState.update { it.copy(isExporting = false, exportErrorMessage = "Failed to open destination file.") }
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    stream.use { out ->
                        writeStarted = true
                        out.write(archive)
                    }
                }
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportSuccessMessage = "Backup exported successfully!"
                    )
                }
            } catch (e: CancellationException) {
                // Leaving the screen is not a failure; an unfinished file fails validation on restore
                throw e
            } catch (e: Exception) {
                val deleted = writeStarted && discardOutput()
                val note = if (deleted) " The incomplete file was deleted." else ""
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportErrorMessage = "Export failed: ${e.message ?: "Unknown error"}.$note"
                    )
                }
            }
        }
    }

    fun validateAndPreviewBackup(inputStreamSupplier: () -> InputStream?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isValidating = true, validationError = null, previewData = null) }
            try {
                val stream = inputStreamSupplier()
                if (stream == null) {
                    _uiState.update { it.copy(isValidating = false, validationError = "Failed to open selected backup file.") }
                    return@launch
                }
                val validation = stream.use { validateBackupUseCase(it) }
                if (validation.isValid && validation.backupData != null && validation.manifest != null) {
                    val data = validation.backupData
                    val preview = BackupPreview(
                        exportedAt = validation.manifest.exportedAt,
                        appVersion = validation.manifest.appVersion,
                        backupVersion = validation.manifest.backupVersion,
                        // What will actually be restored, not what the manifest claims (BUG-017)
                        counts = BackupRecordCountsDto(
                            diaryEntries = data.diaryEntries.size,
                            customFoods = data.customFoods.size,
                            recipes = data.recipes.size,
                            weightEntries = data.weightEntries.size,
                            waterEntries = data.waterEntries.size,
                            goals = if (data.goals != null) 1 else 0,
                            preferences = if (data.preferences != null) 1 else 0
                        ),
                        backupData = data
                    )
                    _uiState.update { it.copy(isValidating = false, previewData = preview) }
                } else {
                    _uiState.update {
                        it.copy(
                            isValidating = false,
                            validationError = validation.errorMessage ?: "Invalid backup file."
                        )
                    }
                }
            } catch (e: OutOfMemoryError) {
                _uiState.update { it.copy(isValidating = false, validationError = PortabilityConfig.BACKUP_TOO_LARGE_FOR_DEVICE_MESSAGE) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isValidating = false,
                        validationError = "Failed to parse backup: ${briefErrorText(e, "Corrupted file")}"
                    )
                }
            }
        }
    }

    fun onSelectImportMode(mode: ImportMode) {
        val preview = _uiState.value.previewData ?: return
        if (mode == ImportMode.OVERWRITE) {
            _uiState.update { it.copy(showOverwriteConfirmation = true) }
        } else {
            executeImport(preview.backupData, ImportMode.MERGE)
        }
    }

    fun confirmOverwriteImport() {
        val preview = _uiState.value.previewData ?: return
        _uiState.update { it.copy(showOverwriteConfirmation = false) }
        executeImport(preview.backupData, ImportMode.OVERWRITE)
    }

    private fun executeImport(backupData: MacroBaseBackupData, mode: ImportMode) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, previewData = null) }
            val result = importUserDataUseCase(backupData, mode)
            _uiState.update { it.copy(isImporting = false, importResult = result) }
        }
    }

    fun dismissPreview() {
        _uiState.update { it.copy(previewData = null, validationError = null) }
    }

    fun dismissOverwriteConfirmation() {
        _uiState.update { it.copy(showOverwriteConfirmation = false) }
    }

    fun dismissResult() {
        _uiState.update { it.copy(importResult = null, exportSuccessMessage = null, exportErrorMessage = null) }
    }
}

/**
 * Data & backup. The scaffold draws the pinned "Data & backup" header, so this screen starts with
 * its lead line, then the status banners and the export and restore cards.
 */
@Composable
fun ImportExportScreen(
    viewModel: ImportExportViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(PortabilityConfig.BACKUP_MIME_TYPE)
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportBackup(
                // "wt" truncates, so replacing a larger older backup leaves no stale bytes behind;
                // providers without "wt" get the default mode, as before
                outputStreamSupplier = {
                    runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                        ?: context.contentResolver.openOutputStream(uri)
                },
                discardOutput = { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false) }
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.validateAndPreviewBackup {
                context.contentResolver.openInputStream(uri)
            }
        }
    }

    val scrollState = rememberScrollState()
    ReportContentScrolled(scrollState.isScrolled)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = AppSpacing.screenMargin)
    ) {
        Text(
            text = "Export and restore your offline MacroBase database. You own 100% of your personal nutrition data.",
            style = AppTypography.Body2.copy(fontSize = 14.5.sp, lineHeight = 21.sp),
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )

        // Status / Banner Feedback
        uiState.exportSuccessMessage?.let { msg ->
            StatusBanner(message = msg, isSuccess = true)
        }

        uiState.exportErrorMessage?.let { err ->
            StatusBanner(message = err, isSuccess = false)
        }

        uiState.validationError?.let { err ->
            // Bounded even if a message ever grows: it once held a whole backup file (BUG-047)
            StatusBanner(message = err, isSuccess = false, maxLines = 4)
        }

        // 1. Export User Data Card
        BackupCard(
            icon = Ph.UploadSimple.light,
            title = "Export user data",
            description = "Packages your food diary logs, custom foods, recipes, weight history, hydration records, and goals into a portable ZIP archive.",
            modifier = Modifier.padding(top = 16.dp),
            details = {
                Row(verticalAlignment = Alignment.Top) {
                    MbIcon(
                        icon = Ph.Info.light,
                        contentDescription = null,
                        size = 15.dp,
                        tint = AppColors.TextMuted,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Stored completely offline on your device. Never uploaded to the cloud.",
                        style = AppTypography.Caption,
                        color = AppColors.TextMuted
                    )
                }
                Text(
                    // Auto Backup excludes MacroBase data from Google's cloud (BUG-018)
                    text = "Android's Google cloud backup does not include MacroBase data. Export a backup file to keep a copy.",
                    style = AppTypography.Caption,
                    color = AppColors.TextMuted
                )
            }
        ) {
            if (uiState.isExporting) {
                BusyRow(label = "Creating backup archive...")
            } else {
                MbButton(
                    text = "Export Backup File",
                    icon = Ph.Export.regular,
                    onClick = {
                        val defaultName = "${PortabilityConfig.BACKUP_ZIP_FILENAME_PREFIX}${LocalDate.now()}.zip"
                        exportLauncher.launch(defaultName)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 2. Restore User Data Card
        BackupCard(
            icon = Ph.DownloadSimple.light,
            title = "Restore user data",
            description = "Restore your records from a previously exported backup archive. You can choose to merge missing entries or replace your existing database.",
            modifier = Modifier.padding(top = 12.dp)
        ) {
            if (uiState.isValidating || uiState.isImporting) {
                val label = if (uiState.isValidating) "Validating backup archive..." else "Restoring database transactionally..."
                BusyRow(label = label)
            } else {
                MbButton(
                    text = "Select Backup File to Restore",
                    kind = MbButtonKind.Secondary,
                    icon = Ph.Database.light,
                    onClick = {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Schema & Compatibility Footer
        Text(
            text = "Backup Format: ${PortabilityConfig.BACKUP_FORMAT_NAME} (Schema v${PortabilityConfig.DATABASE_SCHEMA_VERSION})",
            style = AppTypography.Caption,
            color = AppColors.TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 26.dp)
        )

        Spacer(modifier = Modifier.height(AppSpacing.xxl))
    }

    // ==========================================
    // MODAL DIALOGS
    // ==========================================

    // 1. Backup Preview Dialog
    uiState.previewData?.let { preview ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPreview() },
            title = { Text(text = "Backup Archive Preview", style = AppTypography.Header1) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(text = "Created: ${preview.exportedAt}", style = AppTypography.Caption, color = AppColors.TextSecondary)
                    Text(text = "App Version: ${preview.appVersion} (Format v${preview.backupVersion})", style = AppTypography.Caption, color = AppColors.TextSecondary)
                    Spacer(modifier = Modifier.height(AppSpacing.lg))
                    Text(text = "Archive Contents:", style = AppTypography.Body1, color = AppColors.TextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))

                    CountList(
                        rows = listOf(
                            "Diary Entries" to preview.counts.diaryEntries,
                            "Custom Foods" to preview.counts.customFoods,
                            "Recipes" to preview.counts.recipes,
                            "Weight Entries" to preview.counts.weightEntries,
                            "Water Logs" to preview.counts.waterEntries,
                            "Goals Configuration" to preview.counts.goals,
                            "Profile Preferences" to preview.counts.preferences
                        )
                    )

                    Spacer(modifier = Modifier.height(AppSpacing.lg))
                    Text(text = "Select Restore Mode:", style = AppTypography.Body2, color = AppColors.TextSecondary)
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    MbButton(
                        text = "Merge with Existing Data",
                        onClick = { viewModel.onSelectImportMode(ImportMode.MERGE) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    MbButton(
                        text = "Overwrite Entire Database",
                        kind = MbButtonKind.Danger,
                        onClick = { viewModel.onSelectImportMode(ImportMode.OVERWRITE) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    MbButton(
                        text = "Cancel",
                        kind = MbButtonKind.Secondary,
                        onClick = { viewModel.dismissPreview() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            containerColor = AppColors.SheetFill,
            shape = AppShapes.Dialog
        )
    }

    // 2. Overwrite Confirmation Dialog
    if (uiState.showOverwriteConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissOverwriteConfirmation() },
            icon = { MbIcon(icon = Ph.Warning.light, contentDescription = null, size = 36.dp, tint = AppColors.Error) },
            title = { Text(text = "Confirm Database Overwrite", style = AppTypography.Header1) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                    Text(
                        text = "This action will completely REPLACE all your existing food diary entries, custom foods, recipes, weight history, and water logs with the backup data.",
                        style = AppTypography.Body1,
                        color = AppColors.TextPrimary
                    )
                    Text(
                        text = "Note: Your built-in 50k food catalog will NOT be affected.",
                        style = AppTypography.Caption,
                        color = AppColors.AccentStrong
                    )
                    Text(
                        text = "This cannot be undone. Are you sure you want to overwrite?",
                        style = AppTypography.Body2,
                        color = AppColors.Error
                    )
                }
            },
            confirmButton = {
                MbButton(
                    text = "Overwrite Database",
                    kind = MbButtonKind.Danger,
                    onClick = { viewModel.confirmOverwriteImport() }
                )
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissOverwriteConfirmation() }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.SheetFill,
            shape = AppShapes.Dialog
        )
    }

    // 3. Import Result Dialog
    uiState.importResult?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissResult() },
            icon = {
                val icon = if (result.isSuccess) Ph.CheckCircle.regular else Ph.WarningCircle.regular
                val tint = if (result.isSuccess) AppColors.Success else AppColors.Error
                MbIcon(icon = icon, contentDescription = null, size = 36.dp, tint = tint)
            },
            title = {
                val title = if (result.isSuccess) "Restore Complete" else "Restore Failed"
                Text(text = title, style = AppTypography.Header1)
            },
            text = {
                Column {
                    Text(
                        text = result.message.ifBlank { result.errorMessage ?: "" },
                        style = AppTypography.Body1,
                        color = AppColors.TextPrimary
                    )
                    if (result.isSuccess) {
                        Spacer(modifier = Modifier.height(AppSpacing.lg))
                        CountList(
                            rows = listOf(
                                "Records Imported" to result.recordsImported,
                                "Records Updated" to result.recordsUpdated,
                                "Records Skipped" to result.recordsSkipped,
                                "Conflicts Resolved" to result.conflictsResolved
                            )
                        )
                    }
                }
            },
            confirmButton = {
                MbButton(
                    text = "Done",
                    onClick = { viewModel.dismissResult() }
                )
            },
            containerColor = AppColors.SheetFill,
            shape = AppShapes.Dialog
        )
    }
}

/**
 * A backup action card (the demo's bk-card): an icon circle beside the heading, the description and
 * any [details], then the card's action ([action]: its button, or a spinner while it works).
 */
@Composable
private fun BackupCard(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    details: (@Composable ColumnScope.() -> Unit)? = null,
    action: @Composable ColumnScope.() -> Unit
) {
    MbPanel(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MbIconCircle(icon = icon, size = 44.dp, iconSize = 22.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Text(text = title, style = AppTypography.Header2, color = AppColors.TextPrimary)
                Text(
                    text = description,
                    style = AppTypography.Body2.copy(fontSize = 13.sp),
                    color = AppColors.TextSecondary
                )
                if (details != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    details()
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        action()
    }
}

/** The spinner and its label, in the place of the card's button while the work runs. */
@Composable
private fun BusyRow(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            color = AppColors.Primary,
            trackColor = AppColors.BarTrack,
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = label, style = AppTypography.Body2, color = AppColors.TextSecondary)
    }
}

/** A success or error message: a rounded panel tinted in the status colour, with its icon. */
@Composable
private fun StatusBanner(message: String, isSuccess: Boolean, maxLines: Int = Int.MAX_VALUE) {
    val tint = if (isSuccess) AppColors.Success else AppColors.Error
    val shape = AppShapes.CardSmall
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(shape)
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.35f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MbIcon(
            icon = if (isSuccess) Ph.CheckCircle.regular else Ph.WarningCircle.regular,
            contentDescription = null,
            size = 20.dp,
            tint = tint
        )
        Text(
            text = message,
            style = AppTypography.Body1.copy(fontSize = 14.sp),
            color = AppColors.TextPrimary,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Label / value rows (the backup's counts), with a thin line between rows. */
@Composable
private fun CountList(rows: List<Pair<String, Int>>) {
    val shape = AppShapes.InputBox
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.RowFill)
            .border(1.dp, AppColors.Edge, shape)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        rows.forEachIndexed { index, (label, count) ->
            if (index > 0) HorizontalDivider(color = AppColors.Divider)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = label, style = AppTypography.Body2, color = AppColors.TextSecondary)
                Text(
                    text = "$count",
                    style = AppTypography.Body1,
                    color = AppColors.TextPrimary,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}
