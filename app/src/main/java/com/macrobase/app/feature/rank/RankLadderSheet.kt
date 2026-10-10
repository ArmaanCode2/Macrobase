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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.domain.model.rank.Rank
import com.macrobase.app.domain.model.rank.RankTier
import com.macrobase.app.domain.model.rank.Tier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankLadderSheet(
    currentRankTier: RankTier,
    currentTierRR: Int,
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

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = AppColors.Surface,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.90f)
                .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm)
        ) {
            // 1. Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Flag,
                        contentDescription = null,
                        tint = AppColors.Primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text(
                        text = "RANK LADDER",
                        style = AppTypography.Header2.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        ),
                        color = AppColors.TextPrimary
                    )
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.xs))

            // 2. Progression Info Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(AppColors.SurfaceAlt)
                    .border(1.dp, AppColors.Divider, RoundedCornerShape(4.dp))
                    .padding(horizontal = AppSpacing.sm, vertical = 8.dp)
            ) {
                Column {
                    Text(
                        text = "PROGRESSION: I → II → III → NEXT RANK",
                        style = AppTypography.Caption.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            color = AppColors.Primary
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Each tier requires 100 RR (0–99 RR). Lifestyle consistency determines daily RR gain/loss.",
                        style = AppTypography.Caption.copy(fontSize = 11.sp),
                        color = AppColors.TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.sm))

            // 3. Complete 24-Tier List
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
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

                    // Show progression connector between rank groups (after Tier III)
                    if (item.tier == Tier.III && !isMax) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = null,
                                tint = AppColors.Primary.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
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

    val backgroundColor = if (isCurrent) {
        AppColors.Primary.copy(alpha = 0.12f)
    } else {
        AppColors.Surface
    }

    val borderColor = if (isCurrent) {
        AppColors.Primary
    } else {
        AppColors.Divider
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .border(if (isCurrent) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(4.dp))
            .padding(horizontal = AppSpacing.sm, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rank Image Thumbnail
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(AppColors.SurfaceAlt),
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
                    Icon(
                        imageVector = Icons.Default.Flag,
                        contentDescription = null,
                        tint = AppColors.Primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(AppSpacing.sm))

            // Rank Name & Status
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = rankTier.displayName.uppercase(),
                        style = AppTypography.Body1.copy(
                            fontWeight = if (isCurrent) FontWeight.Black else FontWeight.Bold
                        ),
                        color = if (isCurrent) AppColors.Primary else AppColors.TextPrimary
                    )

                    if (isStarting) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(2.dp))
                                .background(AppColors.SurfaceAlt)
                                .border(0.5.dp, AppColors.TextSecondary, RoundedCornerShape(2.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "STARTING RANK",
                                style = AppTypography.Caption.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 9.sp
                                ),
                                color = AppColors.TextSecondary
                            )
                        }
                    }

                    if (isMax) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFFFFA000))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "MAX RANK",
                                style = AppTypography.Caption.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp
                                ),
                                color = Color.Black
                            )
                        }
                    }

                    if (isCurrent) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(2.dp))
                                .background(AppColors.Primary)
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "YOUR CURRENT RANK",
                                style = AppTypography.Caption.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp
                                ),
                                color = Color.Black
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = if (isCurrent) "${currentTierRR} / 100 RR" else "0–99 RR",
                    style = AppTypography.Caption.copy(
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 11.sp
                    ),
                    color = if (isCurrent) AppColors.CalorieText else AppColors.TextSecondary
                )
            }

            if (isCurrent) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Current Rank",
                    tint = AppColors.Primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
