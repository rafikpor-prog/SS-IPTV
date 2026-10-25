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

    public List<Channel> fetchLiveChannels() throws Exception {
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
        if (result.isEmpty()) throw new IOException("Serwer nie zwrócił kanałów na żywo.");
        return result;
    }

    public static String streamUrl(Credentials creds, String internalUrl) {
        String stream = internalUrl.substring("xtream://live/".length());
        return creds.server + "/live/" + encode(creds.username) + "/" +
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
