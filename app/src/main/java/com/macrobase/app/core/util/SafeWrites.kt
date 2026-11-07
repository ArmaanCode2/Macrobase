package com.macrobase.app.core.util

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the user sees when a save or delete fails: on a phone that is most often a full disk (BUG-022). */
const val WRITE_FAILED_MESSAGE = "MacroBase couldn't save this change. Your phone may be low on storage. Please try again."

/**
 * The database writes a screen starts, and the last one that failed (BUG-022). A failure such as a
 * full disk becomes [error] for the screen to show, instead of reaching the thread and closing the
 * app; cancellation still cancels. [onSaved] runs only once the write worked, so a dialog can stay
 * open until then.
 */
class SafeWrites(private val scope: CoroutineScope) {
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun dismiss() {
        _error.value = null
    }

    fun launch(
        onSaved: () -> Unit = {},
        onFailed: () -> Unit = {},
        failedMessage: String = WRITE_FAILED_MESSAGE,
        write: suspend () -> Unit
    ) {
        scope.launch {
            try {
                write()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "A write failed: ${e.message}", e)
                onFailed()
                _error.value = failedMessage
                return@launch
            }
            onSaved()
        }
    }

    private companion object {
        const val TAG = "SafeWrites"
    }
}
