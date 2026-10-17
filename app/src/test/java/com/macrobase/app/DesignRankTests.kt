package com.macrobase.app

import com.macrobase.app.domain.model.rank.Rank
import com.macrobase.app.domain.model.rank.RankTier
import com.macrobase.app.domain.model.rank.Tier
import com.macrobase.app.domain.model.rank.RankHistoryEntry
import com.macrobase.app.feature.rank.historyPages
import com.macrobase.app.feature.rank.nextRankTier
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The redesigned Rank screen (design phase 7): "<n> RR to <next tier>" under the RR bar. */
class DesignRankTests {

    @Test
    fun theNextTierFollowsTheLadder() {
        assertEquals(RankTier(Rank.FAT_BEAR, Tier.II), nextRankTier(RankTier(Rank.FAT_BEAR, Tier.I)))
        assertEquals("Gym Cat III", nextRankTier(RankTier(Rank.GYM_CAT, Tier.II))?.displayName)
        // After a tier III comes the next rank's tier I
        assertEquals(RankTier(Rank.AVERAGE, Tier.I), nextRankTier(RankTier(Rank.FAT_BEAR, Tier.III)))
        assertEquals("GigaChad I", nextRankTier(RankTier(Rank.FART_MONSTER, Tier.III))?.displayName)
        // Nothing above the top
        assertNull(nextRankTier(RankTier(Rank.GIGACHAD, Tier.III)))
    }

    @Test
    fun recentRrMovementShowsFifteenDaysAPage() {
        val today = LocalDate.of(2026, 10, 17)
        val history = (0L until 40L).map { daysAgo ->
            RankHistoryEntry(
                date = today.minusDays(daysAgo),
                rankTier = RankTier(Rank.FAT_BEAR, Tier.I),
                tierRR = 0,
                overallRating = 0,
                rrDelta = 0,
                overallScore = 0.0
            )
        }
        val pages = historyPages(history)
        assertEquals(listOf(15, 15, 10), pages.map { it.size })
        // The first page holds today and the 14 days before it
        assertEquals(today, pages[0].first().date)
        assertEquals(today.minusDays(14), pages[0].last().date)
    }
}
