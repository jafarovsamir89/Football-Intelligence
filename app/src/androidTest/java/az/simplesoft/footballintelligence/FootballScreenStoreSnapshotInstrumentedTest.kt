package az.simplesoft.footballintelligence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import az.simplesoft.footballintelligence.analytics.ForecastedMatch
import az.simplesoft.footballintelligence.analytics.OutcomeProbabilities
import az.simplesoft.footballintelligence.data.BookmakerOddsSnapshot
import az.simplesoft.footballintelligence.data.OddsProviderStatus
import az.simplesoft.footballintelligence.data.StoreFootballScreenRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FootballScreenStoreSnapshotInstrumentedTest {
    @Test
    fun snapshotLoadsDashboardAndSelectedFixtureDetailsFromIsolatedDatabase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fi-screen-snapshot-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val matchId = 990001
        val kickoffMillis = Instant.parse("2032-12-01T20:00:00Z").toEpochMilli()
        val store = Store(context, databaseName)
        var repositoryClosed = false

        try {
            store.upsert(
                listOf(
                    Match(
                        id = matchId,
                        league = "Snapshot League",
                        home = "Snapshot Home FC",
                        away = "Snapshot Away FC",
                        kickoff = Instant.ofEpochMilli(kickoffMillis).toString(),
                        finished = false,
                        homeScore = null,
                        awayScore = null,
                        sourceKey = "snapshot-test:$matchId",
                    ),
                ),
            )
            store.saveQuote(matchId, "Example Book", 2.0, 3.2, 4.1)
            store.savePick(matchId, "snapshot-test", "H")
            val firstOddsCapture = System.currentTimeMillis() - 120_000L
            val oddsEventId = "snapshot-odds-$matchId"
            store.saveBookmakerCapture(
                "misli",
                firstOddsCapture,
                OddsProviderStatus.LIVE,
                listOf(bookmakerQuote("misli", oddsEventId, matchId, kickoffMillis, firstOddsCapture, 2.0)),
            )
            store.saveBookmakerCapture(
                "misli",
                firstOddsCapture + 60_000L,
                OddsProviderStatus.LIVE,
                listOf(bookmakerQuote("misli", oddsEventId, matchId, kickoffMillis, firstOddsCapture + 60_000L, 2.2)),
            )
            store.saveBookmakerCapture(
                "topaz",
                firstOddsCapture + 60_000L,
                OddsProviderStatus.LIVE,
                listOf(bookmakerQuote("topaz", "topaz-$matchId", matchId, kickoffMillis, firstOddsCapture + 60_000L, 2.1)),
            )
            val forecastedSnapshots = (1..101).map { daysBeforeKickoff ->
                ForecastedMatch(
                    matchId = matchId,
                    homeTeam = "Snapshot Home FC",
                    awayTeam = "Snapshot Away FC",
                    kickoffMillis = kickoffMillis,
                    probabilities = OutcomeProbabilities(.55, .25, .20),
                    forecastedAtMillis = kickoffMillis - daysBeforeKickoff * 86_400_000L,
                    historyMatchCount = 12,
                    modelVersion = "snapshot-test-model",
                    competition = "Snapshot League",
                )
            }
            assertEquals(101, store.recordProspectiveForecastSnapshots(forecastedSnapshots, kickoffMillis - 1L))

            val repository = StoreFootballScreenRepository(store)
            val snapshot = repository.readScreenSnapshot(matchId)
            val identities = repository.fixtureIdentities()

            assertEquals(20, store.writableDatabase.version)
            assertEquals(matchId, snapshot.matches.single().id)
            assertEquals("Snapshot League", snapshot.matches.single().league)
            assertEquals("Example Book", snapshot.selectedQuotes.single().bookmaker)
            assertEquals("H", snapshot.selectedPicks.single().outcome)
            assertEquals(3, snapshot.selectedBookmakerOddsHistory.size)
            assertEquals(listOf("misli", "misli", "topaz"), snapshot.selectedBookmakerOddsHistory.map { it.providerId })
            assertEquals(listOf(firstOddsCapture, firstOddsCapture + 60_000L, firstOddsCapture + 60_000L), snapshot.selectedBookmakerOddsHistory.map { it.capturedAtMillis })
            assertTrue(snapshot.ticketMatches.isEmpty())
            assertTrue(snapshot.historicalByLeague.isEmpty())
            assertEquals(100, snapshot.prospectiveForecastHistory.size)
            assertEquals(101, snapshot.prospectiveForecastEvaluationHistory.size)
            assertEquals(matchId, identities.single().matchId)

            repository.close()
            repositoryClosed = true
            Store(context, databaseName).use { reopened ->
                assertEquals(20, reopened.writableDatabase.version)
                assertEquals(matchId, reopened.matches().single().id)
                assertEquals(3, reopened.bookmakerOddsHistoryForMatch(matchId).size)
            }
        } finally {
            if (!repositoryClosed) store.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun bookmakerQuote(
        providerId: String,
        eventId: String,
        matchId: Int,
        kickoffMillis: Long,
        capturedAtMillis: Long,
        home: Double,
    ) = BookmakerOddsSnapshot(
        providerId = providerId,
        eventId = eventId,
        sourceUrl = "https://www.${if (providerId == "misli") "misli.az/idman-novleri/futbol" else "etopaz.az"}",
        capturedAtMillis = capturedAtMillis,
        kickoffMillis = kickoffMillis,
        market = "1X2",
        homeTeam = "Snapshot Home FC",
        awayTeam = "Snapshot Away FC",
        home = home,
        draw = 3.2,
        away = 4.1,
        eventDate = "2032-12-01",
        timeZoneId = "Asia/Baku",
        matchId = matchId,
    )
}
