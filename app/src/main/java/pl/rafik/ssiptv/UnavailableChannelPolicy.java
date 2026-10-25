package pl.rafik.ssiptv;

/** Wybieramy następny kanał tylko przy trwałym niepowodzeniu startu Live TV. */
public final class UnavailableChannelPolicy {
    private UnavailableChannelPolicy() {}

    public static boolean shouldAdvance(boolean vod, boolean connected,
                                        boolean playedAtLeastOnce, int failures) {
        return !vod && connected && !playedAtLeastOnce && failures >= 2;
    }
}
