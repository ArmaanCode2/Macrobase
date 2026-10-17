package com.macrobase.app.data.repository

import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException

private const val TAG = "MacroBaseSettings"

/**
 * Replaces a settings file that cannot be parsed with an empty one, so the app starts on defaults
 * instead of failing on every read (BUG-053). The values in a corrupt file are lost either way.
 */
internal val preferencesCorruptionHandler = ReplaceFileCorruptionHandler { e ->
    Log.w(TAG, "A settings file was corrupt and was reset to defaults", e)
    emptyPreferences()
}

/** Quick retries before defaults stand in, so a brief read fault never shows them. */
private const val QUICK_RETRIES = 3L
private const val FIRST_RETRY_DELAY_MS = 100L
private const val MAX_RETRY_DELAY_MS = 30_000L

/**
 * The stored settings. When the file cannot be read, empty settings (so every value falls back to
 * its default) stand in after a few quick retries, and reading goes on in the background, so the
 * real settings replace the defaults once the file can be read again (BUG-053). Any other error
 * is a bug and still fails.
 */
internal fun Flow<Preferences>.emptyOnIoError(name: String): Flow<Preferences> = retryWhen { cause, attempt ->
    if (cause !is IOException) return@retryWhen false
    if (attempt == QUICK_RETRIES) {
        Log.w(TAG, "Could not read the $name settings; using defaults until they can be read", cause)
        emit(emptyPreferences())
    }
    delay((FIRST_RETRY_DELAY_MS shl attempt.coerceAtMost(9L).toInt()).coerceAtMost(MAX_RETRY_DELAY_MS))
    true
}
