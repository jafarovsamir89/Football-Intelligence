package az.simplesoft.footballintelligence.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class FootballDataCsvImporterTest {
    @Test fun parsesFootballDataResultsAndSkipsUnfinishedRows() {
        val csv = """Div,Date,HomeTeam,AwayTeam,FTHG,FTAG,FTR
E0,12/08/23,Arsenal,Nottingham Forest,2,1,H
E0,19/08/23,Arsenal,Fulham,,,
""".trimIndent()

        val report = FootballDataCsvImporter.parse(csv)

        assertEquals(2, report.rowsRead)
        assertEquals(1, report.results.size)
        assertEquals(1, report.rejectedRows)
        assertEquals("E0", report.results.single().competitionCode)
        assertEquals(LocalDate.of(2023, 8, 12), report.results.single().eventDate)
        assertEquals(2, report.results.single().homeGoals)
    }

    @Test fun handlesQuotedCommasEscapedQuotesAndSemicolonDelimiter() {
        val csv = "Div;Date;HomeTeam;AwayTeam;FTHG;FTAG\n" +
            "E0;2024-02-03;\"Town, United\";\"The \"\"Reds\"\"\";1;0\n"

        val result = FootballDataCsvImporter.parse(csv).results.single()

        assertEquals("Town, United", result.homeTeam)
        assertEquals("The \"Reds\"", result.awayTeam)
        assertEquals(LocalDate.of(2024, 2, 3), result.eventDate)
    }

    @Test fun usesDocumentedTwoDigitYearPivotAndCompetitionFallback() {
        val csv = """Date,HomeTeam,AwayTeam,FTHG,FTAG
31/12/99,Old Town,City,0,0
01/01/24,New Town,United,1,2
""".trimIndent()

        val report = FootballDataCsvImporter.parse(csv, fallbackCompetitionCode = "E0")

        assertEquals(LocalDate.of(1999, 12, 31), report.results[0].eventDate)
        assertEquals(LocalDate.of(2024, 1, 1), report.results[1].eventDate)
        assertEquals("E0", report.results[0].competitionCode)
    }

    @Test fun acceptsDocumentedLegacyGoalColumnAliases() {
        val csv = """Div,Date,HomeTeam,AwayTeam,HG,AG
E0,01/09/99,Alpha,Beta,3,2
""".trimIndent()

        val result = FootballDataCsvImporter.parse(csv).results.single()

        assertEquals(3, result.homeGoals)
        assertEquals(2, result.awayGoals)
    }

    @Test fun duplicateRowsAreReportedAndOnlyFirstIsKept() {
        val csv = """Div,Date,HomeTeam,AwayTeam,FTHG,FTAG
E0,01/09/24,Alpha,Beta,1,0
e0,01/09/24, alpha ,BETA,2,0
""".trimIndent()

        val report = FootballDataCsvImporter.parse(csv)

        assertEquals(1, report.results.size)
        assertEquals(1, report.duplicateRows)
        assertEquals(0, report.rejectedRows)
    }

    @Test fun rejectsMissingRequiredColumnsAndUnterminatedQuotes() {
        assertThrows(IllegalStateException::class.java) {
            FootballDataCsvImporter.parse("Date,HomeTeam,AwayTeam\n01/01/24,A,B")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FootballDataCsvImporter.parse("Div,Date,HomeTeam,AwayTeam,FTHG,FTAG\nE0,01/01/24,\"A,B,1,0")
        }
    }

    @Test fun decodesUtf8AndLegacyWindowsCsvText() {
        assertEquals("Café", FootballDataCsvImporter.decode("Café".toByteArray(Charsets.UTF_8)))
        assertEquals("Café", FootballDataCsvImporter.decode(byteArrayOf(0x43, 0x61, 0x66, 0xE9.toByte())))
    }

    @Test fun dateOnlyResultsBecomeAvailableAfterTheWholeFollowingDay() {
        val expected = LocalDate.of(2024, 8, 10).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expected, FootballDataCsvImporter.conservativeAvailabilityMillis(LocalDate.of(2024, 8, 8)))
    }

    @Test fun parsesOnlyListedMatchStatisticsAndIgnoresPriceColumns() {
        val csv = """Div,Date,HomeTeam,AwayTeam,FTHG,FTAG,HS,AS,HST,AST,HC,AC,HF,AF,HY,AY,HR,AR,B365H,B365D,B365A
            E0,01/11/26,Arsenal,Manchester City,2,0,14,5,8,1,7,2,11,14,1,3,0,0,2.10,3.20,3.50
        """.trimIndent()

        val stats = FootballDataCsvImporter.parse(csv).results.single().matchStats!!

        assertEquals(14, stats.homeShots)
        assertEquals(5, stats.awayShots)
        assertEquals(8, stats.homeShotsOnTarget)
        assertEquals(7, stats.homeCorners)
        assertEquals(3, stats.awayYellowCards)
        assertEquals(0, stats.homeRedCards)
    }

    @Test fun missingOrInvalidStatisticsRemainUnavailableWithoutRejectingVerifiedScore() {
        val csv = """Div,Date,HomeTeam,AwayTeam,FTHG,FTAG,HS,AS,HC,AC
            E0,01/11/26,Alpha,Beta,1,0,,,,
            E0,02/11/26,Gamma,Delta,2,0,-1,3,2,1
        """.trimIndent()

        val report = FootballDataCsvImporter.parse(csv)

        assertEquals(2, report.results.size)
        assertEquals(null, report.results[0].matchStats)
        assertEquals(3, report.results[1].matchStats?.awayShots)
        assertEquals(null, report.results[1].matchStats?.homeShots)
        assertEquals(0, report.rejectedRows)
    }
}
