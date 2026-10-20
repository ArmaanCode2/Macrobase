package com.macrobase.app.data.cache

import com.macrobase.app.core.config.CalendarPerformanceConfig.PerformanceCategory
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.ConsistencyStatistics
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.HeatmapCellData
import com.macrobase.app.domain.model.HeatmapWeek
import com.macrobase.app.domain.model.MacroAveragesStatistics
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.model.WeightStatistics
import com.macrobase.app.domain.model.rank.BestRank
import com.macrobase.app.domain.model.rank.LifestyleScore
import com.macrobase.app.domain.model.rank.PastSeason
import com.macrobase.app.domain.model.rank.Rank
import com.macrobase.app.domain.model.rank.RankHistoryEntry
import com.macrobase.app.domain.model.rank.RankProfile
import com.macrobase.app.domain.model.rank.RankSeason
import com.macrobase.app.domain.model.rank.RankTier
import com.macrobase.app.domain.model.rank.RatingPoint
import com.macrobase.app.domain.model.rank.Tier
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import java.time.LocalDate

// Every field is written out explicitly, in the order below, so a snapshot holds exactly the figures shown.
// Enums are saved by name, so reordering an enum never misreads an old snapshot.

private fun DataOutputStream.writeDate(date: LocalDate) = writeLong(date.toEpochDay())
private fun DataInputStream.readDate(): LocalDate = LocalDate.ofEpochDay(readLong())

private inline fun <T> DataOutputStream.writeNullable(value: T?, block: DataOutputStream.(T) -> Unit) {
    writeBoolean(value != null)
    if (value != null) block(value)
}

private inline fun <T> DataInputStream.readNullable(block: DataInputStream.() -> T): T? = if (readBoolean()) block() else null

private inline fun <T> DataOutputStream.writeList(values: List<T>, block: DataOutputStream.(T) -> Unit) {
    writeInt(values.size)
    values.forEach { block(it) }
}

private inline fun <T> DataInputStream.readList(block: DataInputStream.() -> T): List<T> {
    val size = readInt()
    require(size in 0..MAX_LIST) { "List of $size" }
    return List(size) { block() }
}

/** Far more than any list a snapshot holds; a larger count means a damaged file. */
private const val MAX_LIST = 1_000_000

private fun DataOutputStream.writeRankTier(tier: RankTier) {
    writeUTF(tier.rank.name)
    writeUTF(tier.tier.name)
}

private fun DataInputStream.readRankTier() = RankTier(Rank.valueOf(readUTF()), Tier.valueOf(readUTF()))

private fun DataOutputStream.writeSeason(season: RankSeason) {
    writeInt(season.number)
    writeDate(season.start)
    writeDate(season.nextReset)
}

private fun DataInputStream.readSeason() = RankSeason(readInt(), readDate(), readDate())

private fun DataOutputStream.writeBest(best: BestRank) {
    writeRankTier(best.rankTier)
    writeInt(best.rating)
    writeDate(best.date)
}

private fun DataInputStream.readBest() = BestRank(readRankTier(), readInt(), readDate())

private fun DataOutputStream.writeHistoryEntry(e: RankHistoryEntry) {
    writeDate(e.date)
    writeRankTier(e.rankTier)
    writeInt(e.tierRR)
    writeInt(e.overallRating)
    writeInt(e.rrDelta)
    writeDouble(e.overallScore)
    writeInt(e.ratingBefore)
    writeRankTier(e.rankTierBefore)
    writeDouble(e.calorieScore)
    writeDouble(e.consistencyScore)
    writeDouble(e.proteinScore)
    writeDouble(e.qualityScore)
    writeDouble(e.balanceScore)
    writeDouble(e.expectedScore)
    writeDouble(e.calories)
    writeBoolean(e.isLogged)
    writeBoolean(e.isInProgress)
    writeBoolean(e.heldByCleanWeek)
}

private fun DataInputStream.readHistoryEntry() = RankHistoryEntry(
    date = readDate(),
    rankTier = readRankTier(),
    tierRR = readInt(),
    overallRating = readInt(),
    rrDelta = readInt(),
    overallScore = readDouble(),
    ratingBefore = readInt(),
    rankTierBefore = readRankTier(),
    calorieScore = readDouble(),
    consistencyScore = readDouble(),
    proteinScore = readDouble(),
    qualityScore = readDouble(),
    balanceScore = readDouble(),
    expectedScore = readDouble(),
    calories = readDouble(),
    isLogged = readBoolean(),
    isInProgress = readBoolean(),
    heldByCleanWeek = readBoolean()
)

/** The whole rank: the Rank tab and Stats' rank history both read it. */
object RankProfileCodec : SnapshotCodec<RankProfile> {
    override val version = 1

    override fun write(out: DataOutputStream, value: RankProfile) = with(out) {
        writeInt(value.currentRating)
        writeRankTier(value.rankTier)
        writeInt(value.tierRR)
        writeBoolean(value.isProvisional)
        writeInt(value.loggedDaysCount)
        with(value.lifestyleScore) {
            writeDouble(consistencyScore)
            writeDouble(calorieScore)
            writeDouble(proteinScore)
            writeDouble(qualityScore)
            writeDouble(balanceScore)
            writeDouble(overallScore)
            writeInt(loggedDays)
            writeInt(eligibleDays)
        }
        writeList(value.recentHistory) { writeHistoryEntry(it) }
        writeUTF(value.fitnessGoal.name)
        writeDouble(value.maintenanceCalories)
        writeNullable(value.pendingStrategyChange) { writeUTF(it) }
        writeDouble(value.calorieZone.start)
        writeDouble(value.calorieZone.endInclusive)
        writeBoolean(value.showRulesNotice)
        writeNullable(value.season) { writeSeason(it) }
        writeNullable(value.bestThisSeason) { writeBest(it) }
        writeNullable(value.lastSeasonBest) { writeBest(it) }
        writeBoolean(value.showNewSeasonNotice)
        writeList(value.ratingHistory) {
            writeDate(it.date)
            writeInt(it.rating)
            writeInt(it.seasonNumber)
        }
        writeList(value.pastSeasons) {
            writeSeason(it.season)
            writeNullable(it.best) { best -> writeBest(best) }
        }
        writeNullable(value.cleanWeekGate) { writeInt(it) }
        writeNullable(value.rankCountsFrom) { writeDate(it) }
    }

    override fun read(input: DataInputStream): RankProfile = with(input) {
        val currentRating = readInt()
        val rankTier = readRankTier()
        val tierRR = readInt()
        val isProvisional = readBoolean()
        val loggedDaysCount = readInt()
        val lifestyleScore = LifestyleScore(
            consistencyScore = readDouble(),
            calorieScore = readDouble(),
            proteinScore = readDouble(),
            qualityScore = readDouble(),
            balanceScore = readDouble(),
            overallScore = readDouble(),
            loggedDays = readInt(),
            eligibleDays = readInt()
        )
        val recentHistory = readList { readHistoryEntry() }
        val fitnessGoal = FitnessGoal.valueOf(readUTF())
        val maintenanceCalories = readDouble()
        val pendingStrategyChange = readNullable { readUTF() }
        val zoneStart = readDouble()
        val zoneEnd = readDouble()
        RankProfile(
            currentRating = currentRating,
            rankTier = rankTier,
            tierRR = tierRR,
            isProvisional = isProvisional,
            loggedDaysCount = loggedDaysCount,
            lifestyleScore = lifestyleScore,
            recentHistory = recentHistory,
            fitnessGoal = fitnessGoal,
            maintenanceCalories = maintenanceCalories,
            pendingStrategyChange = pendingStrategyChange,
            calorieZone = zoneStart..zoneEnd,
            showRulesNotice = readBoolean(),
            season = readNullable { readSeason() },
            bestThisSeason = readNullable { readBest() },
            lastSeasonBest = readNullable { readBest() },
            showNewSeasonNotice = readBoolean(),
            ratingHistory = readList { RatingPoint(readDate(), readInt(), readInt()) },
            pastSeasons = readList { PastSeason(readSeason(), readNullable { readBest() }) },
            cleanWeekGate = readNullable { readInt() },
            rankCountsFrom = readNullable { readDate() }
        )
    }
}

private fun DataOutputStream.writeCell(cell: HeatmapCellData) {
    writeDate(cell.date)
    writeInt(cell.dayOfWeek)
    writeDouble(cell.caloriesLogged)
    writeDouble(cell.dailyGoalCalories)
    writeInt(cell.percentageOfGoal)
    writeUTF(cell.category.name)
    writeBoolean(cell.isEligible)
    writeBoolean(cell.isFuture)
}

private fun DataInputStream.readCell() = HeatmapCellData(
    date = readDate(),
    dayOfWeek = readInt(),
    caloriesLogged = readDouble(),
    dailyGoalCalories = readDouble(),
    percentageOfGoal = readInt(),
    category = PerformanceCategory.valueOf(readUTF()),
    isEligible = readBoolean(),
    isFuture = readBoolean()
)

/** Stats' Consistency view for one range. */
object ConsistencyCodec : SnapshotCodec<ConsistencyStatistics> {
    override val version = 1

    override fun write(out: DataOutputStream, value: ConsistencyStatistics) = with(out) {
        writeDate(value.startDate)
        writeDate(value.endDate)
        writeInt(value.totalEligibleDays)
        writeInt(value.loggedDaysCount)
        writeInt(value.consistencyScorePercentage)
        writeList(value.weeks) { week ->
            writeInt(week.weekIndex)
            writeList(week.cells) { cell -> writeNullable(cell) { writeCell(it) } }
        }
        writeList(value.allCells) { writeCell(it) }
    }

    override fun read(input: DataInputStream) = with(input) {
        ConsistencyStatistics(
            startDate = readDate(),
            endDate = readDate(),
            totalEligibleDays = readInt(),
            loggedDaysCount = readInt(),
            consistencyScorePercentage = readInt(),
            weeks = readList { HeatmapWeek(readInt(), readList { readNullable { readCell() } }) },
            allCells = readList { readCell() }
        )
    }
}

/** Stats' Averages view for one range. */
object MacroAveragesCodec : SnapshotCodec<MacroAveragesStatistics> {
    override val version = 1

    override fun write(out: DataOutputStream, value: MacroAveragesStatistics) = with(out) {
        writeDate(value.startDate)
        writeDate(value.endDate)
        writeInt(value.loggedDaysCount)
        writeDouble(value.averageCalories)
        writeDouble(value.averageProteinGrams)
        writeDouble(value.averageCarbsGrams)
        writeDouble(value.averageFatGrams)
        writeDouble(value.actualCarbsPercent)
        writeDouble(value.actualProteinPercent)
        writeDouble(value.actualFatPercent)
        writeDouble(value.targetCarbsPercent)
        writeDouble(value.targetProteinPercent)
        writeDouble(value.targetFatPercent)
        writeDouble(value.targetCalories)
    }

    override fun read(input: DataInputStream) = with(input) {
        MacroAveragesStatistics(
            startDate = readDate(),
            endDate = readDate(),
            loggedDaysCount = readInt(),
            averageCalories = readDouble(),
            averageProteinGrams = readDouble(),
            averageCarbsGrams = readDouble(),
            averageFatGrams = readDouble(),
            actualCarbsPercent = readDouble(),
            actualProteinPercent = readDouble(),
            actualFatPercent = readDouble(),
            targetCarbsPercent = readDouble(),
            targetProteinPercent = readDouble(),
            targetFatPercent = readDouble(),
            targetCalories = readDouble()
        )
    }
}

/** Stats' Weight view for one range. */
object WeightStatisticsCodec : SnapshotCodec<WeightStatistics> {
    override val version = 1

    override fun write(out: DataOutputStream, value: WeightStatistics) = with(out) {
        writeDate(value.startDate)
        writeDate(value.endDate)
        writeNullable(value.initialWeightKg) { writeDouble(it) }
        writeNullable(value.latestWeightKg) { writeDouble(it) }
        writeNullable(value.minWeightKg) { writeDouble(it) }
        writeNullable(value.maxWeightKg) { writeDouble(it) }
        writeNullable(value.averageWeightKg) { writeDouble(it) }
        writeNullable(value.deltaWeightKg) { writeDouble(it) }
        writeNullable(value.percentageChange) { writeDouble(it) }
        writeList(value.entries) { entry ->
            writeLong(entry.id)
            writeDate(entry.date)
            writeDouble(entry.weightKg)
            writeNullable(entry.note) { writeUTF(it) }
            writeLong(entry.loggedAt.epochSecond)
            writeInt(entry.loggedAt.nano)
        }
    }

    override fun read(input: DataInputStream) = with(input) {
        WeightStatistics(
            startDate = readDate(),
            endDate = readDate(),
            initialWeightKg = readNullable { readDouble() },
            latestWeightKg = readNullable { readDouble() },
            minWeightKg = readNullable { readDouble() },
            maxWeightKg = readNullable { readDouble() },
            averageWeightKg = readNullable { readDouble() },
            deltaWeightKg = readNullable { readDouble() },
            percentageChange = readNullable { readDouble() },
            entries = readList {
                WeightEntry(
                    id = readLong(),
                    date = readDate(),
                    weightKg = readDouble(),
                    note = readNullable { readUTF() },
                    loggedAt = Instant.ofEpochSecond(readLong(), readInt().toLong())
                )
            }
        )
    }
}

/** A calendar month's day colours. */
object CalendarMonthCodec : SnapshotCodec<List<CalendarDaySummary>> {
    override val version = 1

    override fun write(out: DataOutputStream, value: List<CalendarDaySummary>) = with(out) {
        writeList(value) { day ->
            writeDate(day.date)
            writeDouble(day.loggedCalories)
            writeDouble(day.calorieGoal)
            writeBoolean(day.hasLogs)
            writeUTF(day.performanceCategory.name)
        }
    }

    override fun read(input: DataInputStream) = with(input) {
        readList {
            CalendarDaySummary(
                date = readDate(),
                loggedCalories = readDouble(),
                calorieGoal = readDouble(),
                hasLogs = readBoolean(),
                performanceCategory = PerformanceCategory.valueOf(readUTF())
            )
        }
    }
}
