package com.macrobase.app.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Something the user asked to delete, held until they confirm, so a mis-tap beside Edit never
 * destroys a row (BUG-005). It lives in the ViewModel, so rotation keeps the question open.
 */
class PendingDelete<T : Any> {
    /** [label] names the target in the question. */
    data class Request<T : Any>(val target: T, val label: String)

    private val _request = MutableStateFlow<Request<T>?>(null)
    val request: StateFlow<Request<T>?> = _request.asStateFlow()

    fun ask(target: T, label: String) {
        _request.value = Request(target, label)
    }

    fun cancel() {
        _request.value = null
    }

    /** What was asked about, once; null when nothing is waiting. */
    fun confirm(): T? = _request.value?.target.also { _request.value = null }
}
