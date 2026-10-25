package pl.rafik.ssiptv.data;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
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

    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([a-zA-Z][a-zA-Z0-9-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,]+))");
    private static final Pattern EPISODE = Pattern.compile(
            "(?i)^(.+?)[ ._\\-\\[(]*(?:s(\\d{1,2})[ ._\\-]*e(\\d{1,3})|" +
                    "(\\d{1,2})x(\\d{1,3}))(?:\\b|[ ._\\-\\]].*)$");
    public static final int MAX_CHANNELS = 50000;

    public static List<Channel> parse(InputStream input) throws IOException {
        List<Channel> result = new ArrayList<>();
        BufferedInputStream probe = new BufferedInputStream(input);
        probe.mark(4);
        int a = probe.read();
        int b = probe.read();
        Charset charset = StandardCharsets.UTF_8;
        if (a == 0xFF && b == 0xFE) charset = StandardCharsets.UTF_16LE;
        else if (a == 0xFE && b == 0xFF) charset = StandardCharsets.UTF_16BE;
        else probe.reset();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(probe, charset))) {
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
                        boolean seriesGroup = category.contains("serial") ||
                                category.contains("series") || category.contains("tv show") ||
                                category.contains("sezon");
                        if (episodeName.matches() && (seriesGroup || category.contains("vod") ||
                                streamPath.endsWith(".mp4") || streamPath.endsWith(".mkv"))) {
                            channel.vod = true;
                            channel.kind = "EPISODE";
                            channel.seriesId = episodeName.group(1).trim().replace('.', ' ');
                            channel.season = Integer.parseInt(episodeName.group(2) != null ?
                                    episodeName.group(2) : episodeName.group(4));
                            channel.episode = Integer.parseInt(episodeName.group(3) != null ?
                                    episodeName.group(3) : episodeName.group(5));
                        } else if (seriesGroup) {
                            // Niektóre M3U opisują seriale wyłącznie grupą, bez S01E01.
                            channel.vod = true;
                            channel.kind = "EPISODE";
                            channel.seriesId = display;
                            channel.season = 1;
                            channel.episode = 1;
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
            if (matcher.group(1).equalsIgnoreCase(key)) {
                if (matcher.group(2) != null) return matcher.group(2);
                if (matcher.group(3) != null) return matcher.group(3);
                return matcher.group(4);
            }
        }
        return "";
    }

    private static int unquotedComma(String value) {
        char quoted = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (quoted == 0 && (ch == '"' || ch == '\'')) quoted = ch;
            else if (quoted == ch) quoted = 0;
            if (ch == ',' && quoted == 0) return i;
        }
        return -1;
    }
}
