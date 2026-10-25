package pl.rafik.ssiptv;

/** Reguły nawigacji pilotem niezależne od sprzętu i widoków Androida. */
public final class TvNavigation {
    private TvNavigation() {}

    public static int gridColumns(int screenWidthDp) {
        return Math.max(2, Math.min(6, (screenWidthDp - 125) / 180));
    }

    /** W lewym skrajnym polu listy/siatki strzałka ← otwiera menu. */
    public static boolean opensDrawerFromLeft(int selected, boolean tiled, int columns) {
        return selected < 0 || !tiled || selected % Math.max(1, columns) == 0;
    }
}
