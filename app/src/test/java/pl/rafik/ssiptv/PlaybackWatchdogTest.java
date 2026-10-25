package pl.rafik.ssiptv;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackWatchdogTest {
    @Test public void restartOnlyAfterSixtySecondsWithoutProgress() {
        PlaybackWatchdog watchdog = new PlaybackWatchdog();
        watchdog.reset(1000);
        assertFalse(watchdog.isStalled(31000, true, false));
        assertTrue(watchdog.isStalled(61000, true, false));
    }

    @Test public void progressAndLiveTimestampJumpRefreshDeadline() {
        PlaybackWatchdog watchdog = new PlaybackWatchdog();
        watchdog.reset(1000);
        watchdog.observe(30000, 0);
        watchdog.observe(59000, 1000);
        assertFalse(watchdog.isStalled(90000, true, false));
        watchdog.observe(90000, 0);
        assertTrue(watchdog.isStalled(120000, true, false));
    }

    @Test public void neverReconnectWhileOfflineOrPaused() {
        PlaybackWatchdog watchdog = new PlaybackWatchdog();
        watchdog.reset(1000);
        assertFalse(watchdog.isStalled(240000, false, false));
        assertFalse(watchdog.isStalled(240000, true, true));
        assertFalse(watchdog.isStalled(240001, true, false));
    }

    @Test public void heartbeatKeepsPlaybackAlive() {
        PlaybackWatchdog watchdog = new PlaybackWatchdog();
        watchdog.reset(1000);
        watchdog.heartbeat(30000);
        assertFalse(watchdog.isStalled(69000, true, false));
    }
}
