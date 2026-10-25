package pl.rafik.ssiptv.epg;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EpgRefreshPolicyTest {
    @Test public void cachedEpgIsNotDownloadedUntilFiveDaysPass() {
        long now = 1_900_000_000_000L;
        assertFalse(EpgRefreshPolicy.isDue(now, now - 4L * 24 * 60 * 60 * 1000, 0));
        assertTrue(EpgRefreshPolicy.isDue(now, now - 5L * 24 * 60 * 60 * 1000, 0));
    }

    @Test public void unavailableServerIsNotRetriedAtEveryLaunch() {
        long now = 1_900_000_000_000L;
        assertFalse(EpgRefreshPolicy.isDue(now, 0, now - 20_000));
        assertTrue(EpgRefreshPolicy.isDue(now, 0, now - 7L * 60 * 60 * 1000));
    }

    @Test public void freshInstallFetchesOnce() {
        assertTrue(EpgRefreshPolicy.isDue(1_900_000_000_000L, 0, 0));
    }
}
