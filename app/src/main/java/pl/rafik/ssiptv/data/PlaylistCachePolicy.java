package pl.rafik.ssiptv.data;

/** Local-first: nie pobieramy listy przy każdym wejściu do aplikacji. */
public final class PlaylistCachePolicy {
    public static final long REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000;

    private PlaylistCachePolicy() { }

    public static boolean isRefreshDue(String sourceUrl, long lastSyncMs, long nowMs) {
        if (sourceUrl == null || !sourceUrl.startsWith("http://") &&
                !sourceUrl.startsWith("https://")) return false;
        return lastSyncMs > 0 && nowMs >= lastSyncMs &&
                nowMs - lastSyncMs >= REFRESH_INTERVAL_MS;
    }
}
