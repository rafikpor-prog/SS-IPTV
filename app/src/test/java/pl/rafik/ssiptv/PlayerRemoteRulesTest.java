package pl.rafik.ssiptv;

import org.junit.Test;

import static org.junit.Assert.*;

public final class PlayerRemoteRulesTest {
    @Test public void liveChannelZapsEvenWhileAutomaticOsdIsVisible() {
        assertTrue(PlayerRemoteRules.shouldZapWithArrows(false, false, false, false));
    }

    @Test public void manuallyFocusedOsdDoesNotZap() {
        assertFalse(PlayerRemoteRules.shouldZapWithArrows(false, false, true, false));
        assertFalse(PlayerRemoteRules.shouldZapWithArrows(false, true, false, false));
        assertFalse(PlayerRemoteRules.shouldZapWithArrows(false, false, false, true));
    }

    @Test public void vodArrowsDoNotChangeChannel() {
        assertFalse(PlayerRemoteRules.shouldZapWithArrows(true, false, false, false));
    }

    @Test public void channelKeysAndAlternativeRemotesUseSameDirection() {
        assertEquals(1, PlayerRemoteRules.channelStepForKeyCode(166));
        assertEquals(-1, PlayerRemoteRules.channelStepForKeyCode(167));
        assertEquals(1, PlayerRemoteRules.channelStepForKeyCode(92));
        assertEquals(-1, PlayerRemoteRules.channelStepForKeyCode(93));
        assertEquals(1, PlayerRemoteRules.channelStepForKeyCode(87));
        assertEquals(-1, PlayerRemoteRules.channelStepForKeyCode(88));
        assertEquals(0, PlayerRemoteRules.channelStepForKeyCode(24)); // głośność
    }
}
