package com.macrobase.app.core.designsystem.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.util.SafeWrites

/** A save or delete that failed (BUG-022). It stays until the user closes it; a dialog behind it stays open. */
@Composable
fun WriteFailedDialog(writes: SafeWrites) {
    val message by writes.error.collectAsState()
    val text = message ?: return
    AlertDialog(
        onDismissRequest = writes::dismiss,
        title = { Text(text = "Not saved", style = AppTypography.Header1) },
        text = { Text(text = text, style = AppTypography.Body1, color = AppColors.TextPrimary) },
        confirmButton = { MbButton(text = "OK", onClick = writes::dismiss) },
        containerColor = AppColors.SheetFill,
        shape = AppShapes.Dialog
    )
}
