package com.macrobase.app.domain.usecase

import com.macrobase.app.domain.model.BackupPreview
import com.macrobase.app.domain.model.BackupValidationResult
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.ImportResult
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.domain.repository.PortabilityRepository
import java.io.InputStream
import java.io.OutputStream

class ExportUserDataUseCase(
    private val portabilityRepository: PortabilityRepository
) {
    suspend fun exportData(): MacroBaseBackupData {
        return portabilityRepository.exportAllUserData()
    }

    suspend fun writeBackupArchive(outputStream: OutputStream) {
        portabilityRepository.writeBackupArchive(outputStream)
    }
}

class ValidateBackupUseCase(
    private val portabilityRepository: PortabilityRepository
) {
    suspend operator fun invoke(inputStream: InputStream): BackupValidationResult {
        return portabilityRepository.validateBackupArchive(inputStream)
    }
}

class ImportUserDataUseCase(
    private val portabilityRepository: PortabilityRepository
) {
    suspend operator fun invoke(backupData: MacroBaseBackupData, mode: ImportMode): ImportResult {
        return portabilityRepository.importUserData(backupData, mode)
    }
}
