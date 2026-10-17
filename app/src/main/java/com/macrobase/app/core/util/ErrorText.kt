package com.macrobase.app.core.util

/**
 * Text from a file or an exception, made safe to show on one line: printable ASCII only (anything
 * else becomes "?"), at most [max] characters.
 */
fun printableText(text: String, max: Int): String {
    val cut = if (text.length > max) text.take(max) + "..." else text
    return buildString(cut.length) {
        for (c in cut) append(if (c in ' '..'~') c else '?')
    }
}

/**
 * An exception's message, safe to show: its first line, at most [max] characters, or [fallback]
 * when it has none. Backup parser messages give a position, not the user's data (BUG-047).
 */
fun briefErrorText(e: Throwable, fallback: String, max: Int = 200): String {
    val message = e.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    return if (message.isEmpty()) fallback else printableText(message, max)
}
