package com.macrobase.app.feature.rank

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Ph
import com.macrobase.app.core.designsystem.components.MbIcon
import com.macrobase.app.core.designsystem.components.MbPanel
import com.macrobase.app.core.designsystem.components.MbSheet
import com.macrobase.app.domain.model.rank.Rank
import com.macrobase.app.domain.model.rank.RankTier
import com.macrobase.app.domain.model.rank.Tier
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * "Resets on Aug 29, 2027 \u2022 319 days left", then why. Before anything is logged there is
 * no date yet.
 */
internal fun rankResetText(nextReset: LocalDate?, today: LocalDate): String {
    if (nextReset == null) {
        return "Your rank starts again from Fat Bear one year after the first day you log food, and every year after that."
    }
    val daysLeft = ChronoUnit.DAYS.between(today, nextReset)
    val date = nextReset.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
    // A profile from before midnight can still be on screen on the reset day
    val left = when {
        daysLeft <= 0L -> "Resets today"
        daysLeft == 1L -> "Resets on $date \u2022 1 day left"
        else -> "Resets on $date \u2022 $daysLeft days left"
    }
    return "$left. Your rank starts again from Fat Bear every year on the date you first logged food."
}

/**
 * The rank ladder sheet: the progression and reset rules stay fixed at the top while only the list
 * of 24 tiers scrolls. The list opens one row above the current tier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankLadderSheet(
    currentRankTier: RankTier,
    currentTierRR: Int,
    /** The day the rank next starts again from Fat Bear; null before anything is logged. */
    nextRankReset: LocalDate?,
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    val allTiers = remember {
        Rank.entries.flatMap { rank ->
            Tier.entries.map { tier ->
                RankTier(rank, tier)
            }
        }
    }

    val listState = rememberLazyListState()

    // Auto-scroll to current rank when opened
    LaunchedEffect(currentRankTier) {
        val targetIndex = currentRankTier.tierGlobalIndex
        if (targetIndex in allTiers.indices) {
            listState.scrollToItem(maxOf(0, targetIndex - 1))
        }
    }

    MbSheet(
        onDismiss = onDismissRequest,
        title = "Rank ladder",
        sheetState = sheetState,
        scrollable = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            // The rules, fixed above the list
            RuleBox(
                title = "Progression: I \u2192 II \u2192 III \u2192 next rank",
                text = "Each tier requires 100 RR (0\u201399 RR). You gain RR when a day scores above what your rank expects and lose RR when it falls short, so higher ranks need better days. Moving up into Fart Monster or GigaChad also needs a clean week: 7 days in a row in your calorie zone, with one day off or missed allowed."
            )
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            RuleBox(
                title = "Rank reset",
                text = rankResetText(nextRankReset, LocalDate.now())
            )
            Spacer(modifier = Modifier.height(AppSpacing.md))

            // The complete 24-tier list; only this part scrolls
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(allTiers, key = { _, item -> item.displayName }) { index, item ->
                    val isCurrent = item == currentRankTier
                    val isStarting = index == 0
                    val isMax = index == allTiers.lastIndex

                    RankLadderItemRow(
                        rankTier = item,
                        isCurrent = isCurrent,
                        currentTierRR = currentTierRR,
                        isStarting = isStarting,
                        isMax = isMax
                    )

                    // A small caret between rank groups (after Tier III)
                    if (item.tier == Tier.III && !isMax) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            MbIcon(icon = Ph.CaretDown.light, contentDescription = null, size = 16.dp, tint = AppColors.TextMuted)
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(AppSpacing.xl))
                }
            }
        }
    }
}

@Composable
private fun RuleBox(title: String, text: String) {
    MbPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(text = title, style = AppTypography.Header3, color = AppColors.TextPrimary)
        Text(
            text = text,
            style = AppTypography.Body2.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun RankLadderItemRow(
    rankTier: RankTier,
    isCurrent: Boolean,
    currentTierRR: Int,
    isStarting: Boolean,
    isMax: Boolean
) {
    val drawableRes = remember(rankTier.rank) {
        RankImageResolver.getRankDrawableRes(rankTier)
    }
    val shape = RoundedCornerShape(18.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isCurrent) AppColors.ChipOnFill else AppColors.RowFill)
            .border(1.dp, if (isCurrent) AppColors.ChipOnBorder else AppColors.Edge, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rank image, 46 x 69
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 69.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AppColors.IconCircleFill)
                .border(1.dp, AppColors.Edge, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (drawableRes != 0) {
                Image(
                    painter = painterResource(id = drawableRes),
                    contentDescription = rankTier.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp)
                )
            } else {
                MbIcon(icon = Ph.Trophy.light, contentDescription = null, size = 22.dp, tint = AppColors.Primary)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = rankTier.displayName,
                style = AppTypography.Header3,
                color = if (isCurrent) AppColors.AccentStrong else AppColors.TextPrimary
            )
            Text(
                text = if (isCurrent) "$currentTierRR / 100 RR" else "0\u201399 RR",
                style = AppTypography.Body2.copy(fontSize = 12.5.sp),
                color = if (isCurrent) AppColors.CalorieText else AppColors.TextSecondary
            )
            if (isStarting || isMax || isCurrent) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isStarting) LadderBadge(text = "Starting rank", fill = Color.Transparent, ink = AppColors.TextSecondary, border = AppColors.ChipBorder)
                    if (isMax) LadderBadge(text = "Max rank", fill = AppColors.MaxRankBadge, ink = AppColors.MaxRankInk, border = null)
                    if (isCurrent) LadderBadge(text = "Your current rank", fill = Color.Transparent, ink = AppColors.ChipOnInk, border = AppColors.ChipOnBorder, showCheck = true)
                }
            }
        }
    }
}

@Composable
private fun LadderBadge(text: String, fill: Color, ink: Color, border: Color?, showCheck: Boolean = false) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(fill)
            .then(if (border != null) Modifier.border(1.dp, border, CircleShape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (showCheck) {
            MbIcon(icon = Ph.CheckCircleFill, contentDescription = null, size = 12.dp, tint = ink)
        }
        Text(text = text, style = AppTypography.Caption.copy(fontSize = 11.sp), color = ink)
    }
}
