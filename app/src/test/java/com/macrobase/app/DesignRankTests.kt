package com.macrobase.app

import com.macrobase.app.domain.model.rank.Rank
import com.macrobase.app.domain.model.rank.RankTier
import com.macrobase.app.domain.model.rank.Tier
import com.macrobase.app.feature.rank.nextRankTier
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
}
