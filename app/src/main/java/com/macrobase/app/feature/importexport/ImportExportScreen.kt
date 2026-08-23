package com.macrobase.app.feature.importexport

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.core.config.PortabilityConfig
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.components.PrimaryButton
import com.macrobase.app.domain.model.BackupPreview
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.ImportResult
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.domain.usecase.ExportUserDataUseCase
import com.macrobase.app.domain.usecase.ImportUserDataUseCase
import com.macrobase.app.domain.usecase.ValidateBackupUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    fun exportBackup(outputStreamSupplier: () -> OutputStream?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportSuccessMessage = null, exportErrorMessage = null) }
            try {
                val stream = outputStreamSupplier()
                if (stream == null) {
                    _uiState.update { it.copy(isExporting = false, exportErrorMessage = "Failed to open destination file.") }
                    return@launch
                }
                stream.use { out ->
                    exportUserDataUseCase.writeBackupArchive(out)
                }
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportSuccessMessage = "Backup exported successfully!"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportErrorMessage = "Export failed: ${e.message ?: "Unknown error"}"
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
                    val preview = BackupPreview(
                        exportedAt = validation.manifest.exportedAt,
                        appVersion = validation.manifest.appVersion,
                        backupVersion = validation.manifest.backupVersion,
                        counts = validation.manifest.counts,
                        backupData = validation.backupData
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
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isValidating = false,
                        validationError = "Failed to parse backup: ${e.message ?: "Corrupted file"}"
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
            viewModel.exportBackup {
                context.contentResolver.openOutputStream(uri)
            }
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .padding(AppSpacing.lg)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "Data & Backup", style = AppTypography.Header1)
        Text(
            text = "Export and restore your offline MacroBase database. You own 100% of your personal nutrition data.",
            style = AppTypography.Body2,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = AppSpacing.xs)
        )

        Spacer(modifier = Modifier.height(AppSpacing.lg))

        // Status / Banner Feedback
        uiState.exportSuccessMessage?.let { msg ->
            Card(
                shape = AppShapes.Card,
                colors = CardDefaults.cardColors(containerColor = AppColors.SuccessSurface),
                modifier = Modifier.fillMaxWidth().padding(bottom = AppSpacing.md)
            ) {
                Row(modifier = Modifier.padding(AppSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AppColors.Primary)
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    Text(text = msg, style = AppTypography.Body1, color = AppColors.TextPrimary)
                }
            }
        }

        uiState.exportErrorMessage?.let { err ->
            Card(
                shape = AppShapes.Card,
                colors = CardDefaults.cardColors(containerColor = AppColors.ErrorSurface),
                modifier = Modifier.fillMaxWidth().padding(bottom = AppSpacing.md)
            ) {
                Row(modifier = Modifier.padding(AppSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = AppColors.TextPrimary)
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    Text(text = err, style = AppTypography.Body1, color = AppColors.TextPrimary)
                }
            }
        }

        uiState.validationError?.let { err ->
            Card(
                shape = AppShapes.Card,
                colors = CardDefaults.cardColors(containerColor = AppColors.ErrorSurface),
                modifier = Modifier.fillMaxWidth().padding(bottom = AppSpacing.md)
            ) {
                Row(modifier = Modifier.padding(AppSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = AppColors.TextPrimary)
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    Text(text = err, style = AppTypography.Body1, color = AppColors.TextPrimary)
                }
            }
        }

        // 1. Export User Data Card
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.lg)) {
                Text(text = "Export User Data", style = AppTypography.Header2)
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = "Packages your food diary logs, custom foods, recipes, weight history, hydration records, and goals into a portable ZIP archive.",
                    style = AppTypography.Body2,
                    color = AppColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = AppColors.WaterCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text(
                        text = "Stored completely offline on your device. Never uploaded to the cloud.",
                        style = AppTypography.Caption,
                        color = AppColors.TextMuted
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.md))

                if (uiState.isExporting) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = AppColors.Primary, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(AppSpacing.sm))
                        Text(text = "Creating backup archive...", style = AppTypography.Body2)
                    }
                } else {
                    PrimaryButton(
                        text = "📤 Export Backup File",
                        onClick = {
                            val defaultName = "${PortabilityConfig.BACKUP_ZIP_FILENAME_PREFIX}${LocalDate.now()}.zip"
                            exportLauncher.launch(defaultName)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.lg))

        // 2. Restore User Data Card
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.lg)) {
                Text(text = "Restore User Data", style = AppTypography.Header2)
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = "Restore your records from a previously exported backup archive. You can choose to merge missing entries or replace your existing database.",
                    style = AppTypography.Body2,
                    color = AppColors.TextSecondary
                )

                Spacer(modifier = Modifier.height(AppSpacing.md))

                if (uiState.isValidating || uiState.isImporting) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = AppColors.Primary, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(AppSpacing.sm))
                        val label = if (uiState.isValidating) "Validating backup archive..." else "Restoring database transactionally..."
                        Text(text = label, style = AppTypography.Body2)
                    }
                } else {
                    PrimaryButton(
                        text = "📥 Select Backup File to Restore",
                        onClick = {
                            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.xl))

        // Schema & Compatibility Footer
        Text(
            text = "Backup Format: ${PortabilityConfig.BACKUP_FORMAT_NAME} (Schema v${PortabilityConfig.DATABASE_SCHEMA_VERSION})",
            style = AppTypography.Caption,
            color = AppColors.TextMuted
        )
    }

    // ==========================================
    // MODAL DIALOGS
    // ==========================================

    // 1. Backup Preview Dialog
    uiState.previewData?.let { preview ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPreview() },
            title = { Text(text = "Backup Archive Preview", style = AppTypography.Header2) },
            text = {
                Column {
                    Text(text = "Created: ${preview.exportedAt}", style = AppTypography.Caption, color = AppColors.TextSecondary)
                    Text(text = "App Version: ${preview.appVersion} (Format v${preview.backupVersion})", style = AppTypography.Caption, color = AppColors.TextSecondary)
                    Spacer(modifier = Modifier.height(AppSpacing.md))
                    Text(text = "Archive Contents:", style = AppTypography.Body1)
                    Spacer(modifier = Modifier.height(AppSpacing.xs))

                    CountBadgeRow("Diary Entries", preview.counts.diaryEntries)
                    CountBadgeRow("Custom Foods", preview.counts.customFoods)
                    CountBadgeRow("Recipes", preview.counts.recipes)
                    CountBadgeRow("Weight Entries", preview.counts.weightEntries)
                    CountBadgeRow("Water Logs", preview.counts.waterEntries)
                    CountBadgeRow("Goals Configuration", preview.counts.goals)
                    CountBadgeRow("Profile Preferences", preview.counts.preferences)

                    Spacer(modifier = Modifier.height(AppSpacing.md))
                    Text(text = "Select Restore Mode:", style = AppTypography.Body2)
                }
            },
            confirmButton = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { viewModel.onSelectImportMode(ImportMode.MERGE) },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("➕ Merge with Existing Data", color = AppColors.Background)
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    Button(
                        onClick = { viewModel.onSelectImportMode(ImportMode.OVERWRITE) },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.AlertRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("⚠️ Overwrite Entire Database", color = AppColors.TextPrimary)
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    OutlinedButton(
                        onClick = { viewModel.dismissPreview() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel", color = AppColors.TextSecondary)
                    }
                }
            },
            containerColor = AppColors.Surface
        )
    }

    // 2. Overwrite Confirmation Dialog
    if (uiState.showOverwriteConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissOverwriteConfirmation() },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = AppColors.AlertRed, modifier = Modifier.size(36.dp)) },
            title = { Text(text = "Confirm Database Overwrite", style = AppTypography.Header2) },
            text = {
                Column {
                    Text(
                        text = "This action will completely REPLACE all your existing food diary entries, custom foods, recipes, weight history, and water logs with the backup data.",
                        style = AppTypography.Body1
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Text(
                        text = "Note: Your built-in 50k food catalog will NOT be affected.",
                        style = AppTypography.Caption,
                        color = AppColors.WaterCyan
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Text(
                        text = "This cannot be undone. Are you sure you want to overwrite?",
                        style = AppTypography.Body2,
                        color = AppColors.AlertRedLight
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmOverwriteImport() },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.AlertRed)
                ) {
                    Text("Overwrite Database", color = AppColors.TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissOverwriteConfirmation() }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.Surface
        )
    }

    // 3. Import Result Dialog
    uiState.importResult?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissResult() },
            icon = {
                val icon = if (result.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error
                val tint = if (result.isSuccess) AppColors.Primary else AppColors.AlertRed
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(36.dp))
            },
            title = {
                val title = if (result.isSuccess) "Restore Complete" else "Restore Failed"
                Text(text = title, style = AppTypography.Header2)
            },
            text = {
                Column {
                    Text(text = result.message.ifBlank { result.errorMessage ?: "" }, style = AppTypography.Body1)
                    if (result.isSuccess) {
                        Spacer(modifier = Modifier.height(AppSpacing.md))
                        CountBadgeRow("Records Imported", result.recordsImported)
                        CountBadgeRow("Records Updated", result.recordsUpdated)
                        CountBadgeRow("Records Skipped", result.recordsSkipped)
                        CountBadgeRow("Conflicts Resolved", result.conflictsResolved)
                    }
                }
            },
            confirmButton = {
                PrimaryButton(
                    text = "Done",
                    onClick = { viewModel.dismissResult() }
                )
            },
            containerColor = AppColors.Surface
        )
    }
}

@Composable
private fun CountBadgeRow(label: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = AppTypography.Body2, color = AppColors.TextSecondary)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(AppColors.SurfaceAlt)
                .padding(horizontal = AppSpacing.sm, vertical = 2.dp)
        ) {
            Text(text = "$count", style = AppTypography.Caption, color = AppColors.Primary)
        }
    }
}
