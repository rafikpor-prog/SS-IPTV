package pl.rafik.ssiptv.data;
import java.text.Normalizer;
import java.util.Locale;
public final class LibrarySearch {
    private LibrarySearch() {}
    static String fold(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT).replace('ł','l'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }
    public static boolean matches(String query, String... fields) {
        String q = fold(query).trim();
        if (q.isEmpty()) return true;
        if (q.length() > 160) return false;
        StringBuilder text = new StringBuilder();
        for (String f : fields) text.append(fold(f)).append(' ');
        for (String word : q.split("\\s+")) if (text.indexOf(word) < 0) return false;
        return true;
    }
}
