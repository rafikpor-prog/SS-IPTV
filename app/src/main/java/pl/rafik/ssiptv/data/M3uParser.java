package pl.rafik.ssiptv.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser standardowych playlist extended M3U. Celowo nie pobiera strumieni:
 * wszystkie adresy pozostają lokalnie w bazie danych aplikacji.
 */
public final class M3uParser {
    private M3uParser() {}

    private static final Pattern ATTRIBUTE = Pattern.compile("([a-zA-Z][a-zA-Z0-9-]*)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern EPISODE = Pattern.compile("(?i)^(.+?)[ ._\\-]*[sS](\\d{1,2})[ ._\\-]*[eE](\\d{1,3})(?:\\b|[ ._\\-].*)$");
    public static final int MAX_CHANNELS = 50000;

    public static List<Channel> parse(InputStream input) throws IOException {
        List<Channel> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String name = "";
            String group = "Inne";
            String id = "";
            String logo = "";
            String description = "";
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("\uFEFF")) line = line.substring(1);
                if (line.startsWith("#EXTINF")) {
                    String metadata = line;
                    int comma = unquotedComma(line);
                    name = comma >= 0 ? line.substring(comma + 1).trim() : "";
                    if (comma >= 0) metadata = line.substring(0, comma);
                    id = attribute(metadata, "tvg-id");
                    if (id.isEmpty()) id = attribute(metadata, "tvg-name");
                    logo = attribute(metadata, "tvg-logo");
                    description = attribute(metadata, "tvg-description");
                    group = attribute(metadata, "group-title");
                    if (group.isEmpty()) group = "Inne";
                    if (name.isEmpty()) name = attribute(metadata, "tvg-name");
                } else if (line.startsWith("#EXTGRP:")) {
                    group = line.substring("#EXTGRP:".length()).trim();
                } else if (!line.isEmpty() && !line.startsWith("#")) {
                    if (line.startsWith("http://") || line.startsWith("https://") ||
                            line.startsWith("rtsp://") || line.startsWith("udp://")) {
                        String display = name.isEmpty() ? "Kanał " + (result.size() + 1) : name;
                        Channel channel = new Channel(display, group, id, logo, line, "M3U");
                        channel.description = description;
                        String category = group.toLowerCase(java.util.Locale.ROOT);
                        String streamPath = line.toLowerCase(java.util.Locale.ROOT).split("\\?", 2)[0];
                        Matcher episodeName = EPISODE.matcher(display);
                        if (episodeName.matches() && (category.contains("serial") ||
                                category.contains("series") || category.contains("vod") ||
                                streamPath.endsWith(".mp4") || streamPath.endsWith(".mkv"))) {
                            channel.vod = true;
                            channel.kind = "EPISODE";
                            channel.seriesId = episodeName.group(1).trim().replace('.', ' ');
                            channel.season = Integer.parseInt(episodeName.group(2));
                            channel.episode = Integer.parseInt(episodeName.group(3));
                        } else if (category.contains("vod") || category.contains("film") ||
                                category.contains("movie") || category.contains("kino") ||
                                streamPath.endsWith(".mp4") || streamPath.endsWith(".mkv") ||
                                streamPath.endsWith(".avi")) channel.asVod();
                        result.add(channel);
                        if (result.size() >= MAX_CHANNELS) {
                            throw new IOException("Lista przekracza limit " + MAX_CHANNELS + " kanałów.");
                        }
                    }
                    name = "";
                    group = "Inne";
                    id = "";
                    logo = "";
                    description = "";
                }
            }
        }
        return result;
    }

    private static String attribute(String metadata, String key) {
        Matcher matcher = ATTRIBUTE.matcher(metadata);
        while (matcher.find()) {
            if (matcher.group(1).equalsIgnoreCase(key)) return matcher.group(2);
        }
        return "";
    }

    private static int unquotedComma(String value) {
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') quoted = !quoted;
            if (ch == ',' && !quoted) return i;
        }
        return -1;
    }
}
