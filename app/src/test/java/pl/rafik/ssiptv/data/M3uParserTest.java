package pl.rafik.ssiptv.data;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public final class M3uParserTest {
    @Test public void readsNamesCategoriesEpgIdsAndUrls() throws Exception {
        String fixture = "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"TVP1.pl\" tvg-logo=\"https://logo.example/1.png\" " +
                "group-title=\"Polska, ogólne\",TVP 1 HD\n" +
                "https://stream.example/live/1.m3u8\n" +
                "#EXTINF:-1 group-title=\"Sport\" tvg-name=\"Sport Test\",Sport Extra\n" +
                "#EXTVLCOPT:http-user-agent=IPTV\n" +
                "http://stream.example/2.ts\n";
        List<Channel> parsed = M3uParser.parse(new ByteArrayInputStream(
                fixture.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, parsed.size());
        assertEquals("TVP 1 HD", parsed.get(0).name);
        assertEquals("TVP1.pl", parsed.get(0).tvgId);
        assertEquals("Polska, ogólne", parsed.get(0).group);
        assertEquals("https://stream.example/live/1.m3u8", parsed.get(0).url);
        assertEquals("Sport", parsed.get(1).group);
        assertEquals("Sport Extra", parsed.get(1).name);
    }

    @Test public void honorsExtgrpAndResetsBetweenChannels() throws Exception {
        String fixture = "\uFEFF#EXTM3U\n" +
                "#EXTINF:-1,Film Jeden\n" +
                "#EXTGRP:Filmy\n" +
                "https://example.org/1\n" +
                "https://example.org/2\n";
        List<Channel> items = M3uParser.parse(new ByteArrayInputStream(
                fixture.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, items.size());
        assertEquals("Filmy", items.get(0).group);
        assertEquals("Inne", items.get(1).group);
    }

    @Test public void detectsVideoOnDemandWithoutMisclassifyingLive() throws Exception {
        String fixture = "#EXTM3U\n" +
                "#EXTINF:-1 group-title=\"VOD Filmy\",Film 2026\n" +
                "https://example.org/movie/9.mp4\n" +
                "#EXTINF:-1 group-title=\"Polska\",Kanał Live\n" +
                "https://example.org/live/1.m3u8\n";
        List<Channel> items = M3uParser.parse(new ByteArrayInputStream(
                fixture.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, items.size());
        assertTrue(items.get(0).vod);
        assertFalse(items.get(1).vod);
    }
}
