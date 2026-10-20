package com.macrobase.app.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.macrobase.app.core.designsystem.AppColors
import kotlinx.coroutines.delay

/**
 * A spinner centred in [modifier] that appears only once loading has taken longer than [delayMs],
 * so a load that finishes quickly never flashes it.
 */
@Composable
fun MbDelayedProgress(modifier: Modifier = Modifier, delayMs: Long = 300L) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        visible = true
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (visible) CircularProgressIndicator(color = AppColors.Primary)
    }
}
