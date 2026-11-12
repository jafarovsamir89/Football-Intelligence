package az.simplesoft.footballintelligence.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenLigaDbParserTest {
    @Test fun discoversOnlyFootballAndDeduplicatesLeagueShortcuts() {
        val leagues = OpenLigaDbParser.parseAvailableLeagues(
            """[
              {"leagueId":1,"leagueName":"Bundesliga","leagueShortcut":"bl1","leagueSeason":"2026","sport":{"sportId":1,"sportName":"Fußball"}},
              {"leagueId":2,"leagueName":"Basketball","leagueShortcut":"bl1","leagueSeason":"2026","sport":{"sportId":2,"sportName":"Basketball"}},
              {"leagueId":3,"leagueName":"Premier League","leagueShortcut":"pl","leagueSeason":"2026","sport":{"sportId":1,"sportName":"Fußball"}}
            ]""".trimIndent(),
            season = "2026",
        )
        assertEquals(listOf("bl1", "pl"), leagues.map { it.shortcut })
    }

    @Test fun parsesExactUtcKickoffAndOnlyFullTimeResult() {
        val json = """[
          {"matchID":83156,"matchDateTimeUTC":"2026-08-28T18:30:00Z","group":{"groupOrderID":1},
           "team1":{"teamName":"FC Bayern München"},"team2":{"teamName":"VfB Stuttgart"},
           "matchIsFinished":true,"matchResults":[
             {"resultTypeID":1,"resultTypeKind":"HalfTime","pointsTeam1":1,"pointsTeam2":0},
             {"resultTypeID":2,"resultTypeKind":"After90Minutes","pointsTeam1":2,"pointsTeam2":1},
             {"resultTypeID":3,"resultTypeKind":"AfterExtraTime","pointsTeam1":3,"pointsTeam2":1},
             {"resultTypeID":4,"resultTypeKind":"AfterPenalties","pointsTeam1":5,"pointsTeam2":4}]},
          {"matchID":83157,"matchDateTimeUTC":"2026-08-29T13:30:00Z",
           "team1":{"teamName":"Hamburg"},"team2":{"teamName":"St. Pauli"},"matchIsFinished":false,"matchResults":[]}
        ]""".trimIndent()
        val league = OpenLigaLeague(4937, "Bundesliga", "bl1", "2026")
        val captured = Instant.parse("2026-11-09T20:00:00Z").toEpochMilli()
        val rows = OpenLigaDbParser.parseMatches(json, league, captured, "https://api.openligadb.de/getmatchdata/bl1/2026")

        assertEquals(2, rows.size)
        assertEquals("openligadb:83156", "${rows[0].providerId}:${rows[0].providerMatchId}")
        assertEquals(KickoffPrecision.INSTANT, rows[0].kickoffPrecision)
        assertEquals(Instant.parse("2026-08-28T18:30:00Z").toEpochMilli(), rows[0].kickoffMillis)
        assertEquals(2, rows[0].homeGoals)
        assertEquals(1, rows[0].awayGoals)
        assertEquals(captured, rows[0].safeAvailableAtMillis)
        assertFalse(rows[1].finished)
        assertNull(rows[1].safeAvailableAtMillis)
    }

    @Test fun refusesPenaltyOnlyResultsAndUnfinishedRows() {
        val json = """[
          {"matchID":83158,"matchDateTimeUTC":"2026-08-30T13:30:00Z",
           "team1":{"teamName":"Arsenal"},"team2":{"teamName":"Chelsea"},"matchIsFinished":true,
           "matchResults":[{"resultTypeID":2,"resultTypeKind":"AfterExtraTime","pointsTeam1":2,"pointsTeam2":1},
                           {"resultTypeID":3,"resultTypeKind":"AfterPenalties","pointsTeam1":5,"pointsTeam2":4}]},
          {"matchID":83159,"matchDateTimeUTC":"2026-08-30T16:30:00Z",
           "team1":{"teamName":"Milan"},"team2":{"teamName":"Inter"},"matchIsFinished":false,
           "matchResults":[{"resultTypeID":2,"resultTypeKind":"After90Minutes","pointsTeam1":1,"pointsTeam2":0}]}
        ]""".trimIndent()
        val rows=OpenLigaDbParser.parseMatches(
            json,OpenLigaLeague(4937,"Cup","cup","2026"),1_800_000_000_000L,"https://api.openligadb.de/cup",
        )

        assertEquals(2,rows.size)
        assertTrue(rows.none { it.finished })
        assertTrue(rows.all { it.safeAvailableAtMillis == null })
    }

    @Test fun supportsLegacyFinalResultIdOnlyWhenSemanticKindIsAbsent() {
        val json = """[
          {"matchID":83160,"matchDateTimeUTC":"2026-08-31T18:30:00Z",
           "team1":{"teamName":"Team Alpha"},"team2":{"teamName":"Team Beta"},"matchIsFinished":true,
           "matchResults":[{"resultTypeID":2,"pointsTeam1":1,"pointsTeam2":0}]}
        ]""".trimIndent()
        val row=OpenLigaDbParser.parseMatches(
            json,OpenLigaLeague(4937,"League","lg","2026"),1_800_000_000_000L,"https://api.openligadb.de/lg",
        ).single()

        assertEquals(1,row.homeGoals)
        assertEquals(0,row.awayGoals)
    }

    @Test fun parsesCurrentGroupAndLastChangedTimestamp() {
        assertEquals(9, OpenLigaDbParser.parseCurrentGroupOrderId("""{"groupOrderID":9}"""))
        assertNull(OpenLigaDbParser.parseCurrentGroupOrderId("{}"))
        assertEquals(
            Instant.parse("2026-09-02T15:36:25.313Z").toEpochMilli(),
            OpenLigaDbParser.parseLastChangedAtMillis("2026-09-02T15:36:25.313"),
        )
        assertTrue(OpenLigaDbParser.parseLastChangedAtMillis("not-a-date") == null)
    }

    @Test fun rejectsEpochPlaceholderDatesThatContradictDeclaredSeason() {
        val json="""[{"matchID":999,"matchDateTimeUTC":"1970-01-01T00:00:00Z",
            "team1":{"teamName":"Home"},"team2":{"teamName":"Away"},"matchResults":[]}]"""
        val rows=OpenLigaDbParser.parseMatches(json,OpenLigaLeague(1,"League","x","2026"),
            Instant.parse("2026-11-09T20:00:00Z").toEpochMilli(),"https://api.openligadb.de/getmatchdata/x/2026")
        assertTrue(rows.isEmpty())
    }

    @Test fun rejectsSourcePlaceholderTeams() {
        val json="""[
          {"matchID":997,"matchDateTimeUTC":"2026-11-13T18:30:00Z",
           "team1":{"teamName":"Empty Stands Dummy Team"},"team2":{"teamName":"Altona 93"},"matchResults":[]},
          {"matchID":998,"matchDateTimeUTC":"2026-11-13T20:30:00Z",
           "team1":{"teamName":"Arsenal"},"team2":{"teamName":"TBD"},"matchResults":[]}
        ]"""
        val rows=OpenLigaDbParser.parseMatches(json,OpenLigaLeague(1,"League","x","2026"),
            Instant.parse("2026-11-09T20:00:00Z").toEpochMilli(),"https://api.openligadb.de/getmatchdata/x/2026")
        assertTrue(rows.isEmpty())
    }
}
