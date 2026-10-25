package pl.rafik.ssiptv.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kompatybilny z typowymi serwerami Xtream Codes / player_api.php.
 * Obsługa wyłącznie autoryzowanego konta użytkownika, bez obchodzenia DRM.
 */
public final class XtreamClient {
    public static final class Credentials {
        public final String server;
        public final String username;
        public final String password;

        public Credentials(String server, String username, String password) {
            this.server = server;
            this.username = username;
            this.password = password;
        }
    }

    private final Credentials creds;
    public XtreamClient(Credentials creds) { this.creds = creds; }

    public static String normalizeServer(String input) throws IOException {
        String value = input.trim();
        if (!value.startsWith("https://") && !value.startsWith("http://")) {
            throw new IOException("Podaj adres z https:// lub http://");
        }
        URL parsed = new URL(value);
        if (parsed.getHost().isEmpty()) throw new IOException("Nieprawidłowy adres serwera");
        String base = parsed.getProtocol() + "://" + parsed.getAuthority() + parsed.getPath();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/player_api.php")) base = base.substring(0, base.length() - "/player_api.php".length());
        return base;
    }

    private String authQuery() {
        return "username=" + encode(creds.username) + "&password=" + encode(creds.password);
    }

    private static String encode(String part) {
        try {
            return URLEncoder.encode(part, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String api(String action) {
        return creds.server + "/player_api.php?" + authQuery() +
                (action.isEmpty() ? "" : "&action=" + encode(action));
    }

    public List<Channel> fetchChannels() throws Exception {
        JSONObject auth = new JSONObject(request(api("")));
        JSONObject user = auth.optJSONObject("user_info");
        if (user == null || user.optInt("auth") != 1 ||
                "Disabled".equalsIgnoreCase(user.optString("status"))) {
            throw new IOException("Serwer nie potwierdził dostępu. Sprawdź login, hasło i abonament.");
        }

        Map<String, String> categories = new HashMap<>();
        JSONArray cats = new JSONArray(request(api("get_live_categories")));
        for (int i = 0; i < cats.length(); i++) {
            JSONObject cat = cats.optJSONObject(i);
            if (cat != null) categories.put(cat.optString("category_id"),
                    cat.optString("category_name", "Inne"));
        }

        List<Channel> result = new ArrayList<>();
        JSONArray streams = new JSONArray(request(api("get_live_streams")));
        for (int i = 0; i < streams.length(); i++) {
            JSONObject stream = streams.optJSONObject(i);
            if (stream == null) continue;
            String id = stream.optString("stream_id", "");
            if (id.isEmpty()) continue;
            String group = categories.getOrDefault(stream.optString("category_id"), "Inne");
            String extension = stream.optString("container_extension", "ts");
            if (!extension.matches("[a-zA-Z0-9]{1,5}")) extension = "ts";
            // W bazie zapisujemy tylko ID kanału, a nie jawny URL z hasłem.
            String internal = "xtream://live/" + id + "." + extension;
            result.add(new Channel(stream.optString("name", "Kanał " + id), group,
                    stream.optString("epg_channel_id", ""),
                    stream.optString("stream_icon", ""), internal, ChannelDatabase.XTREAM));
        }
        // Starsze instalacje Xtream nie zawsze udostępniają sekcję VOD.
        // Wówczas nadal pokazujemy działające kanały Live.
        try {
            Map<String, String> vodCategories = new HashMap<>();
            JSONArray vodCats = new JSONArray(request(api("get_vod_categories")));
            for (int i = 0; i < vodCats.length(); i++) {
                JSONObject category = vodCats.optJSONObject(i);
                if (category != null) vodCategories.put(
                        category.optString("category_id"), category.optString("category_name", "Filmy"));
            }
            JSONArray movies = new JSONArray(request(api("get_vod_streams")));
            for (int i = 0; i < movies.length(); i++) {
                JSONObject movie = movies.optJSONObject(i);
                if (movie == null) continue;
                String id = movie.optString("stream_id", "");
                if (id.isEmpty()) continue;
                String extension = movie.optString("container_extension", "mp4");
                if (!extension.matches("[a-zA-Z0-9]{1,5}")) extension = "mp4";
                String internal = "xtream://movie/" + id + "." + extension;
                Channel film = new Channel(movie.optString("name", "Film " + id),
                        vodCategories.getOrDefault(movie.optString("category_id"), "Filmy"),
                        "", movie.optString("stream_icon", ""), internal,
                        ChannelDatabase.XTREAM).asVod();
                film.description = movie.optString("plot", "");
                result.add(film);
            }
        } catch (Exception optionalVodError) {
            if (result.isEmpty()) throw new IOException("Serwer nie zwrócił kanałów ani filmów VOD.");
        }
        try {
            Map<String, String> seriesCategories = new HashMap<>();
            try {
                JSONArray seriesCats = new JSONArray(request(api("get_series_categories")));
                for (int i = 0; i < seriesCats.length(); i++) {
                    JSONObject cat = seriesCats.optJSONObject(i);
                    if (cat != null) seriesCategories.put(cat.optString("category_id"),
                            cat.optString("category_name", "Seriale"));
                }
            } catch (Exception ignored) { /* Serie mogą działać bez osobnej listy kategorii. */ }
            JSONArray series = new JSONArray(request(api("get_series")));
            for (int i = 0; i < series.length(); i++) {
                JSONObject entry = series.optJSONObject(i);
                if (entry == null) continue;
                String id = entry.optString("series_id", "");
                if (id.isEmpty()) continue;
                Channel parent = new Channel(entry.optString("name", "Serial " + id),
                        seriesCategories.getOrDefault(entry.optString("category_id"), "Seriale"),
                        "", entry.optString("cover", ""), "xtream://catalog/" + id,
                        ChannelDatabase.XTREAM);
                parent.kind = "SERIES";
                parent.seriesId = "xtream:" + id;
                parent.description = entry.optString("plot", "");
                result.add(parent);
            }
        } catch (Exception optionalSeriesError) {
            // Część paneli Xtream nie obsługuje seriali — TV i filmy pozostają dostępne.
        }
        if (result.isEmpty()) throw new IOException("Serwer nie zwrócił kanałów ani filmów VOD.");
        return result;
    }

    /** Zachowujemy zgodność ze starszym kodem wywołującym API. */
    public List<Channel> fetchLiveChannels() throws Exception {
        return fetchChannels();
    }

    public List<Channel> fetchSeriesEpisodes(Channel series) throws Exception {
        if (!series.seriesId.startsWith("xtream:")) return new ArrayList<>();
        String id = series.seriesId.substring("xtream:".length());
        JSONObject details = new JSONObject(request(api("get_series_info") + "&series_id=" + encode(id)));
        JSONObject seasons = details.optJSONObject("episodes");
        List<Channel> result = new ArrayList<>();
        if (seasons == null) return result;
        JSONObject info = details.optJSONObject("info");
        String cover = info == null ? series.logo : info.optString("cover", series.logo);
        for (java.util.Iterator<String> keys = seasons.keys(); keys.hasNext();) {
            String seasonKey = keys.next();
            JSONArray entries = seasons.optJSONArray(seasonKey);
            if (entries == null) continue;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                String episodeId = entry.optString("id", "");
                if (episodeId.isEmpty()) continue;
                JSONObject meta = entry.optJSONObject("info");
                int season = entry.optInt("season", safeNumber(seasonKey));
                int episode = entry.optInt("episode_num", i + 1);
                String extension = entry.optString("container_extension", "mp4");
                if (!extension.matches("[a-zA-Z0-9]{1,5}")) extension = "mp4";
                Channel item = new Channel(entry.optString("title", "Odcinek " + episode),
                        series.group, "", meta == null ? cover :
                        meta.optString("movie_image", cover),
                        "xtream://series/" + episodeId + "." + extension,
                        ChannelDatabase.XTREAM);
                item.kind = "EPISODE";
                item.vod = true;
                item.seriesId = series.seriesId;
                item.season = season;
                item.episode = episode;
                item.description = meta == null ? "" : meta.optString("plot", "");
                result.add(item);
                if (result.size() >= 5000) return result;
            }
        }
        result.sort((a, b) -> a.season == b.season ? Integer.compare(a.episode, b.episode)
                : Integer.compare(a.season, b.season));
        return result;
    }

    public String[] fetchMovieMetadata(Channel movie) throws Exception {
        if (!movie.url.startsWith("xtream://movie/")) return new String[]{movie.description, movie.logo};
        String id = movie.url.substring("xtream://movie/".length()).split("\\.", 2)[0];
        JSONObject details = new JSONObject(request(api("get_vod_info") + "&vod_id=" + encode(id)));
        JSONObject info = details.optJSONObject("info");
        return info == null ? new String[]{movie.description, movie.logo} :
                new String[]{info.optString("plot", movie.description),
                        info.optString("movie_image", movie.logo)};
    }

    private static int safeNumber(String text) {
        try { return Integer.parseInt(text); } catch (NumberFormatException ignored) { return 0; }
    }

    public static String streamUrl(Credentials creds, String internalUrl) {
        String kind = internalUrl.startsWith("xtream://movie/") ? "movie" :
                internalUrl.startsWith("xtream://series/") ? "series" : "live";
        String stream = internalUrl.substring(("xtream://" + kind + "/").length());
        return creds.server + "/" + kind + "/" + encode(creds.username) + "/" +
                encode(creds.password) + "/" + stream;
    }

    private static String request(String address) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(25000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "SS-IPTV/1.0 Android");
        try {
            if (conn.getResponseCode() / 100 != 2)
                throw new IOException("Błąd HTTP " + conn.getResponseCode() + " z serwera.");
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 32 * 1024 * 1024)
                        throw new IOException("Zbyt duża odpowiedź serwera (limit 32 MB)");
                    out.write(buffer, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            conn.disconnect();
        }
    }
}
