package pl.rafik.ssiptv;

/** Jeden punkt decyzji: kiedy strzałka jest zmianą kanału, a kiedy fokusem OSD. */
public final class PlayerRemoteRules {
    private PlayerRemoteRules() {}

    /** Android TV: CH+/CH- = 166/167; część boxów wysyła PageUp/PageDown
     * albo MediaNext/MediaPrevious. W VOD te klawisze zostają dla nawigacji filmu. */
    public static int channelStepForKeyCode(int keyCode) {
        switch (keyCode) {
            case 166: // KEYCODE_CHANNEL_UP
            case 92:  // KEYCODE_PAGE_UP
            case 87:  // KEYCODE_MEDIA_NEXT
                return 1;
            case 167: // KEYCODE_CHANNEL_DOWN
            case 93:  // KEYCODE_PAGE_DOWN
            case 88:  // KEYCODE_MEDIA_PREVIOUS
                return -1;
            default: return 0;
        }
    }

    /** Przewijanie VOD tylko dedykowanymi klawiszami multimedialnymi.
     * Strzałki D-pada 21/22 muszą pozostać dostępne do wyboru audio i napisów. */
    public static int vodSeekOffsetForKeyCode(int keyCode) {
        switch (keyCode) {
            case 89: return -30_000; // KEYCODE_MEDIA_REWIND
            case 90: return 30_000;  // KEYCODE_MEDIA_FAST_FORWARD
            default: return 0;
        }
    }

    public static boolean shouldZapWithArrows(boolean vod, boolean settingsVisible,
                                              boolean osdControlsFocused, boolean descriptionVisible) {
        return !vod && !settingsVisible && !osdControlsFocused && !descriptionVisible;
    }
}
