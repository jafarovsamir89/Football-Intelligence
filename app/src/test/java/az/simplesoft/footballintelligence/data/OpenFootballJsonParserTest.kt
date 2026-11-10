package az.simplesoft.footballintelligence.data

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFootballJsonParserTest {
    private val fixture = """
        {
          "name":"Premier League 2026/27",
          "matches":[
            {"round":"Matchday 1","date":"2026-08-21","time":"20:00","team1":"Arsenal FC","team2":"Coventry City FC","score":{"ft":[3,0]}},
            {"round":"Matchday 1","date":"2026-08-22","team1":"Hull City AFC","team2":"Manchester United FC"},
            {"round":"Matchday 1","date":"2026-08-21","team1":"Arsenal","team2":"Coventry City","score":{"ft":[3,0]}},
            {"round":"Matchday 2","date":"not-a-date","team1":"Club A","team2":"Club B"},
            {"round":"Matchday 2","date":"2026-08-24","team1":"Club C","team2":"Club C"}
          ]
        }
    """.trimIndent()

    @Test fun parsesCompletedAndUpcomingRowsWithoutTreatingLocalClockAsUtc() {
        val capturedAt = 1_800_000_000_000L
        val rows = OpenFootballJsonParser.parse(
            fixture,
            season = "2026-27",
            fileName = "en.1.json",
            sourceUrl = "https://raw.githubusercontent.com/openfootball/football.json/master/2026-27/en.1.json",
            capturedAtMillis = capturedAt,
        )

        assertEquals(2, rows.size)
        val completed = rows[0]
        assertEquals("Premier League 2026/27", completed.competitionName)
        assertEquals("Arsenal FC", completed.homeTeam)
        assertEquals(3, completed.homeGoals)
        assertEquals(0, completed.awayGoals)
        assertEquals(KickoffPrecision.DATE_ONLY, completed.kickoffPrecision)
        assertEquals(
            Instant.parse("2026-08-23T00:00:00Z").toEpochMilli(),
            completed.safeAvailableAtMillis,
        )
        val scheduled = rows[1]
        assertFalse(scheduled.finished)
        assertNull(scheduled.safeAvailableAtMillis)
        assertEquals(capturedAt, scheduled.capturedAtMillis)
    }

    @Test fun seasonPlannerUsesCurrentEuropeanFootballSeason() {
        assertEquals(
            listOf("2026-27", "2025-26", "2024-25", "2023-24", "2022-23"),
            OpenFootballJsonParser.planSeasons(LocalDate.parse("2026-11-09")),
        )
        assertEquals("2026-27", OpenFootballJsonParser.planSeasons(LocalDate.parse("2027-02-01")).first())
        assertEquals("2027-28", OpenFootballJsonParser.planSeasons(LocalDate.parse("2027-07-01")).first())
    }

    @Test fun rejectsUnsafeDirectoryNames() {
        assertNull(OpenFootballJsonParser.competitionFile("2026-27", "../private.json"))
        assertNull(OpenFootballJsonParser.competitionFile("2026/27", "en.1.json"))
        assertTrue(OpenFootballJsonParser.competitionFile("2026-27", "en.1.json") != null)
    }

    @Test fun discoversOnlyJsonFilesFromPublicSeasonDirectory() {
        val files = OpenFootballJsonParser.parseSeasonDirectory(
            """[
              {"name":"en.1.json","type":"file"},
              {"name":"README.md","type":"file"},
              {"name":"en.1.json","type":"file"},
              {"name":"nested","type":"dir"},
              {"name":"../bad.json","type":"file"}
            ]""",
            "2026-27",
        )
        assertEquals(listOf("en.1.json"), files.map { it.fileName })
    }

    @Test fun rejectsPlaceholderTeamNamesFromPublicFiles() {
        val rows=OpenFootballJsonParser.parse(
            """{"matches":[{"round":"Matchday 1","date":"2026-08-20","team1":"Empty Stands Dummy Team","team2":"Altona 93"}]}""",
            "2026-27","en.1.json","https://example.test/en.1.json",1L,
        )
        assertTrue(rows.isEmpty())
    }
}
