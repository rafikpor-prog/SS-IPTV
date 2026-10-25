package pl.rafik.ssiptv;

/**
 * Obserwator rzeczywistego postępu odtwarzania. Cykliczny pomiar co 30 s
 * nie oznacza natychmiastowego restartu: tolerujemy do 60 s bez postępu.
 * Nie restartujemy odtwarzacza w pauzie ani przy braku sieci.
 */
public final class PlaybackWatchdog {
    public static final long CHECK_INTERVAL_MS = 30_000;
    public static final long STALL_TIMEOUT_MS = 60_000;
    private long lastProgressAt;
    private long lastPosition = -1;

    public void reset(long now) {
        lastProgressAt = now;
        lastPosition = -1;
    }

    public void observe(long now, long position) {
        if (position < 0) return;
        if (lastPosition < 0 || position >= lastPosition + 300 ||
                position + 2000 < lastPosition) {
            lastPosition = position;
            lastProgressAt = now;
        }
    }

    public void heartbeat(long now) {
        lastProgressAt = now;
    }

    public boolean isStalled(long now, boolean connected, boolean paused) {
        if (paused) {
            heartbeat(now);
            return false;
        }
        return connected && now - lastProgressAt >= STALL_TIMEOUT_MS;
    }

    public long stableFor(long now) {
        return Math.max(0, now - lastProgressAt);
    }
}
