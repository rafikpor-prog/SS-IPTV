package pl.rafik.ssiptv.epg;

/** Decyzja o pobieraniu EPG niezależna od UI i systemowego harmonogramu. */
public final class EpgRefreshPolicy {
    public static final long INTERVAL_MS = 5L * 24 * 60 * 60 * 1000;
    public static final long FAILURE_RETRY_MS = 6L * 60 * 60 * 1000;

    private EpgRefreshPolicy() { }

    public static boolean isDue(long now, long lastSuccess, long lastAttempt) {
        if (lastSuccess > 0 && now - lastSuccess < INTERVAL_MS) return false;
        return lastAttempt <= 0 || now - lastAttempt >= FAILURE_RETRY_MS;
    }
}
