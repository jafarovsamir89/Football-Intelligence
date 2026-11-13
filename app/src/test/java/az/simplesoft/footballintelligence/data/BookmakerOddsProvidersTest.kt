package az.simplesoft.footballintelligence.data

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmakerOddsProvidersTest {
    @Test fun misliIsIndependentAndDoesNotInventQuotes() {
        val provider = MisliOddsProvider()
        assertEquals("misli", provider.providerId)
        assertEquals(OddsProviderStatus.UNAVAILABLE, provider.status().status)
        assertTrue(provider.fetchPrematchOdds().isEmpty())
    }

    @Test fun topazRejectsObservedLiveMarketsForPrematch() {
        val provider = TopazOddsProvider()
        assertEquals("topaz", provider.providerId)
        assertEquals(OddsProviderStatus.UNAVAILABLE, provider.status().status)
        assertTrue(provider.fetchPrematchOdds().isEmpty())
    }

    @Test fun visibleStatusSeparatesFreshStaleAndEmptyFutureSnapshots() {
        val now=1_000_000L
        assertEquals("ОЖИДАЕТ ПРОВЕРКИ",visibleBookmakerStatus(null,null,0,now))
        assertEquals("LIVE",visibleBookmakerStatus(OddsProviderStatus.LIVE,now-1_000,2,now))
        assertEquals("НЕТ БУДУЩИХ ЛИНИЙ",visibleBookmakerStatus(OddsProviderStatus.LIVE,now-1_000,0,now))
        assertEquals("STALE",visibleBookmakerStatus(OddsProviderStatus.LIVE,now-30L*60*1000-1,2,now))
    }

    @Test fun foregroundCaptureGateCoalescesPageCallbacksAndEnforcesRefreshWindow() {
        val gate=ForegroundBookmakerCaptureGate(intervalMillis=30_000)
        assertTrue(gate.tryStart(1_000))
        assertTrue(gate.isCaptureInProgress())
        assertEquals(false,gate.tryStart(1_001))

        gate.finish(1_010)
        assertEquals(false,gate.isCaptureInProgress())
        assertEquals(30_000L,gate.millisUntilAllowed(1_010))
        assertEquals(false,gate.tryStart(31_009))
        assertTrue(gate.tryStart(31_010))
    }

    @Test fun pausedActivityCancelsPendingCaptureButKeepsCompletedRateLimit() {
        val gate=ForegroundBookmakerCaptureGate(intervalMillis=30_000)
        assertTrue(gate.tryStart(1_000))
        gate.cancelInProgress()
        assertFalse(gate.isCaptureInProgress())
        assertTrue(gate.tryStart(1_001))
        gate.finish(1_010)
        assertFalse(gate.tryStart(1_011))
    }

    @Test fun publicOddsCaptureRequiresResumedActivityAndRespectsFreshnessOnResume() {
        assertTrue(shouldCollectPublicOdds(Lifecycle.State.RESUMED))
        assertFalse(shouldCollectPublicOdds(Lifecycle.State.STARTED))
        assertFalse(shouldCollectPublicOdds(Lifecycle.State.CREATED))

        val now=1_800_000_000_000L
        assertEquals(0L,bookmakerCaptureDelayMillis(null,now))
        assertEquals(20L*60*1000,bookmakerCaptureDelayMillis(now-10L*60*1000,now))
        assertEquals(0L,bookmakerCaptureDelayMillis(now-31L*60*1000,now))
        assertEquals(0L,bookmakerCaptureDelayMillis(now+5L*60*1000,now))
    }

    @Test fun pausingActivityInvalidatesCallbacksFromAnOlderCaptureGeneration() {
        val session=ForegroundBookmakerCaptureSession(intervalMillis=30_000)
        val original=session.currentGeneration()
        assertTrue(session.gate.tryStart(1_000))
        session.invalidate()
        assertFalse(session.isCurrent(original))
        assertFalse(session.gate.isCaptureInProgress())
        assertTrue(session.gate.tryStart(1_001))
    }

    @Test fun newestSnapshotReplacesRepeatedRefreshForSameEventButKeepsOperatorsSeparate() {
        fun quote(provider:String,captured:Long,price:Double)=BookmakerOddsSnapshot(
            provider,"event-1","https://example.org/football",captured,2_000,"1X2","Home","Away",
            price,3.0,4.0,"2026-11-12","UTC",
        )
        val latest=latestBookmakerSnapshotsPerEvent(listOf(
            quote("misli",100,2.0),quote("misli",200,2.1),quote("topaz",150,2.2),
        ))
        assertEquals(2,latest.size)
        assertEquals(2.1,latest.single { it.providerId=="misli" }.home,0.0)
        assertEquals(2.2,latest.single { it.providerId=="topaz" }.home,0.0)
    }
}
