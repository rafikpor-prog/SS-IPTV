package pl.rafik.ssiptv;

/** Jeden punkt decyzji: kiedy strzałka jest zmianą kanału, a kiedy fokusem OSD. */
public final class PlayerRemoteRules {
    private PlayerRemoteRules() {}

    public static boolean shouldZapWithArrows(boolean vod, boolean settingsVisible,
                                              boolean osdControlsFocused, boolean descriptionVisible) {
        return !vod && !settingsVisible && !osdControlsFocused && !descriptionVisible;
    }
}
