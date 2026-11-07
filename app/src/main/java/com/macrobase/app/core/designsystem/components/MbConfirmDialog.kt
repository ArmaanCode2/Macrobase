package com.macrobase.app.core.designsystem.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppTypography

/** Asks before something is lost. Only [confirmText] goes ahead; anything else leaves things as they are. */
@Composable
fun MbConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = "Cancel"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = AppTypography.Header1) },
        text = { Text(text = text, style = AppTypography.Body1, color = AppColors.TextPrimary) },
        confirmButton = { MbButton(text = confirmText, kind = MbButtonKind.Danger, onClick = onConfirm) },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText, color = AppColors.TextSecondary)
            }
        },
        containerColor = AppColors.SheetFill,
        shape = AppShapes.Dialog
    )
}
