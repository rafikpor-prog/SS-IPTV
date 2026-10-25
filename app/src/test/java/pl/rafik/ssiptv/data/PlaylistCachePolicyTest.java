package pl.rafik.ssiptv.data;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaylistCachePolicyTest {
    @Test public void activeCacheIsNotReparsed() {
        long now = 2_000_000_000_000L;
        assertFalse(PlaylistCachePolicy.isRefreshDue("https://example.com/tv.m3u",
                now - PlaylistCachePolicy.REFRESH_INTERVAL_MS + 1000, now));
    }

    @Test public void remotePlaylistBecomesDueAfterSevenDays() {
        long now = 2_000_000_000_000L;
        assertTrue(PlaylistCachePolicy.isRefreshDue("https://example.com/tv.m3u",
                now - PlaylistCachePolicy.REFRESH_INTERVAL_MS, now));
    }

    @Test public void LocalFilesAndUnknownOldLinksAreNeverFetched() {
        long now = 2_000_000_000_000L;
        assertFalse(PlaylistCachePolicy.isRefreshDue("", 1, now));
        assertFalse(PlaylistCachePolicy.isRefreshDue("content://provider/tv.m3u", 1, now));
        assertFalse(PlaylistCachePolicy.isRefreshDue("https://example.com/tv.m3u", 0, now));
    }
}
