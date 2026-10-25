package pl.rafik.ssiptv;

import org.junit.Test;

import static org.junit.Assert.*;

public final class UnavailableChannelPolicyTest {
    @Test public void advancesAfterRepeatedFailedLiveStarts() {
        assertFalse(UnavailableChannelPolicy.shouldAdvance(false, true, false, 0));
        assertFalse(UnavailableChannelPolicy.shouldAdvance(false, true, false, 1));
        assertTrue(UnavailableChannelPolicy.shouldAdvance(false, true, false, 2));
    }

    @Test public void neverSkipsVodNetworkLossOrLiveRebuffering() {
        assertFalse(UnavailableChannelPolicy.shouldAdvance(true, true, false, 9));
        assertFalse(UnavailableChannelPolicy.shouldAdvance(false, false, false, 9));
        assertFalse(UnavailableChannelPolicy.shouldAdvance(false, true, true, 9));
    }
}
