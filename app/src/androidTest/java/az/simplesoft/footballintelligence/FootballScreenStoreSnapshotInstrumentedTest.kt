package az.simplesoft.footballintelligence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

        try {
            Store(context, databaseName).use { store ->
                store.upsert(
                    listOf(
                        Match(
                            id = matchId,
                            league = "Snapshot League",
                            home = "Snapshot Home FC",
                            away = "Snapshot Away FC",
                            kickoff = Instant.parse("2026-12-01T20:00:00Z").toString(),
                            finished = false,
                            homeScore = null,
                            awayScore = null,
                            sourceKey = "snapshot-test:$matchId",
                        ),
                    ),
                )
                store.saveQuote(matchId, "Example Book", 2.0, 3.2, 4.1)
                store.savePick(matchId, "snapshot-test", "H")

                val repository = StoreFootballScreenRepository(store)
                val snapshot = repository.readScreenSnapshot(matchId)
                val identities = repository.fixtureIdentities()

                assertEquals(matchId, snapshot.matches.single().id)
                assertEquals("Snapshot League", snapshot.matches.single().league)
                assertEquals("Example Book", snapshot.selectedQuotes.single().bookmaker)
                assertEquals("H", snapshot.selectedPicks.single().outcome)
                assertTrue(snapshot.ticketMatches.isEmpty())
                assertTrue(snapshot.historicalByLeague.isEmpty())
                assertEquals(matchId, identities.single().matchId)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
