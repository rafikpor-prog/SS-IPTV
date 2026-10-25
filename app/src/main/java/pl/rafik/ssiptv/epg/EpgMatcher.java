package pl.rafik.ssiptv.epg;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Dopasowanie nazw XMLTV niezależnie od wielkości liter, polskich znaków i dopisków HD/PL. */
public final class EpgMatcher {
    private EpgMatcher() {}

    private static final String QUALITY_SUFFIX =
            "(?iu)(?:[\\s\\[\\]()._\\-]+(?:full\\s*hd|fhd|uhd|4k|hd|sd|hevc|h265|h264|1080p|720p|pl))+[\\s\\[\\]()._\\-]*$";

    private static String normalize(String name) {
        if (name == null) return "";
        String latin = Normalizer.normalize(name.toLowerCase(Locale.ROOT)
                .replace('ł', 'l'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return latin.replaceAll("[^a-z0-9]", "");
    }

    public static List<String> keys(String name) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        String original = normalize(name);
        if (!original.isEmpty()) aliases.add(original);
        if (name != null) {
            String simpler = normalize(name.replaceAll(QUALITY_SUFFIX, ""));
            if (!simpler.isEmpty()) aliases.add(simpler);
        }
        return new ArrayList<>(aliases);
    }
}
