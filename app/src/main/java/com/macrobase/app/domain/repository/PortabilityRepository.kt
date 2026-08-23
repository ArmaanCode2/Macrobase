package com.macrobase.app.domain.repository

import com.macrobase.app.domain.model.BackupValidationResult
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.ImportResult
import com.macrobase.app.domain.model.MacroBaseBackupData
import java.io.InputStream
import java.io.OutputStream

/**
 * Repository interface governing offline data backup export, archive validation, and transactional restoration.
 */
interface PortabilityRepository {

    /**
     * Reads all user-owned data from Room and DataStore and constructs a portable Backup container.
     */
    suspend fun exportAllUserData(): MacroBaseBackupData

    /**
     * Writes the complete backup data as a ZIP archive with SHA-256 manifest to an OutputStream.
     */
    suspend fun writeBackupArchive(outputStream: OutputStream)

    /**
     * Inspects and validates a backup ZIP archive from an InputStream.
     */
    suspend fun validateBackupArchive(inputStream: InputStream): BackupValidationResult

    /**
     * Restores backup data transactionally into the local database and preferences.
     */
    suspend fun importUserData(backupData: MacroBaseBackupData, mode: ImportMode): ImportResult
}
