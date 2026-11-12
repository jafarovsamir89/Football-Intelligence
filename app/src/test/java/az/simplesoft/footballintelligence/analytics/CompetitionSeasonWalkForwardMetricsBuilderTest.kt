package az.simplesoft.footballintelligence.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompetitionSeasonWalkForwardMetricsBuilderTest {
    @Test fun groupsWalkForwardScoresByExactStoredSeasonAndKeepsUnknownSeparate() {
        val historyAndTargets=(0 until 24).map { index ->
            val kickoff=index*10_000L
            val home="Team${index%4}"
            val away="Team${(index+1)%4}"
            val homeGoals=if(index%3==0) 2 else 0
            val awayGoals=if(index%3==0) 0 else 1
            HistoricalMatch(kickoff,home,away,homeGoals,awayGoals,availableAtMillis=kickoff+3_000L) to
                WalkForwardTarget(
                    id="fixture-$index",
                    forecastCutoffMillis=kickoff,
                    homeTeam=home,
                    awayTeam=away,
                    homeGoals=homeGoals,
                    awayGoals=awayGoals,
                    season=when {
                        index < 12 -> "2023-24"
                        index < 20 -> "2024-25"
                        else -> null
                    },
                )
        }
        val dataset=EloExperimentDataset(
            competition="League A",
            history=historyAndTargets.map { it.first },
            targets=historyAndTargets.map { it.second },
        )
        val report=EloWalkForwardEngine.evaluate(
            history=dataset.history,
            targets=dataset.targets,
            minimumHistoryMatches=0,
            minimumMatchesPerTeam=0,
        )

        val seasonal=CompetitionSeasonWalkForwardMetricsBuilder.build(listOf(dataset),listOf(report))

        assertEquals(listOf("2023-24","2024-25",null),seasonal.map { it.season })
        assertEquals(listOf(12,8,4),seasonal.map { it.targetCount })
        assertEquals(listOf(12,8,4),seasonal.map { it.forecastCount })
        assertTrue(seasonal.all { it.meanBrierScore != null && it.meanLogLoss != null })
        assertEquals("League A",seasonal.first().competition)
    }

    @Test fun doesNotCreateMetricsForTargetsWithoutPredictions() {
        val target=WalkForwardTarget("one",1_000,"A","B",1,0,season=null)
        val dataset=EloExperimentDataset("League",emptyList(),listOf(target))
        val report=EloWalkForwardEngine.evaluate(emptyList(),listOf(target))
        val row=CompetitionSeasonWalkForwardMetricsBuilder.build(listOf(dataset),listOf(report)).single()

        assertEquals(1,row.targetCount)
        assertEquals(0,row.forecastCount)
        assertEquals(1,row.skippedForInsufficientHistory)
        assertNull(row.meanBrierScore)
        assertNull(row.meanLogLoss)
        assertNull(row.season)
    }
}
