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
        assertEquals("Sport Test", parsed.get(1).tvgId);
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

    @Test public void groupsSeriesEpisodesIntoSeasonsAndKeepsDescriptions() throws Exception {
        String fixture = "#EXTM3U\n" +
                "#EXTINF:-1 group-title=\"Seriale\" tvg-logo=\"https://example.org/cover.jpg\" " +
                "tvg-description=\"Opis pierwszego odcinka\",Przykładowy Serial S01E02 Odcinek 2\n" +
                "https://example.org/serial/s01e02.mp4\n" +
                "#EXTINF:-1 group-title=\"Seriale\",Przykładowy Serial S02E01 Odcinek 1\n" +
                "https://example.org/serial/s02e01.mp4\n";
        List<Channel> items = M3uParser.parse(new ByteArrayInputStream(
                fixture.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, items.size());
        assertTrue(items.get(0).isEpisode());
        assertEquals("Przykładowy Serial", items.get(0).seriesId);
        assertEquals(1, items.get(0).season);
        assertEquals(2, items.get(0).episode);
        assertEquals(2, items.get(1).season);
        assertEquals(1, items.get(1).episode);
        assertEquals("Opis pierwszego odcinka", items.get(0).description);
        assertEquals("https://example.org/cover.jpg", items.get(0).logo);
    }

    @Test public void readsUtf16PlaylistsAndSingleQuotedGroups() throws Exception {
        String fixture = "#EXTM3U\n" +
                "#EXTINF:-1 group-title='Filmy, polskie' tvg-logo='https://example.org/poster.jpg',Film A\n" +
                "https://example.org/movies/1.mp4\n";
        byte[] raw = fixture.getBytes(StandardCharsets.UTF_16LE);
        byte[] utf16 = new byte[raw.length + 2];
        utf16[0] = (byte) 0xFF;
        utf16[1] = (byte) 0xFE;
        System.arraycopy(raw, 0, utf16, 2, raw.length);
        List<Channel> parsed = M3uParser.parse(new ByteArrayInputStream(utf16));
        assertEquals(1, parsed.size());
        assertEquals("Filmy, polskie", parsed.get(0).group);
        assertEquals("https://example.org/poster.jpg", parsed.get(0).logo);
        assertTrue(parsed.get(0).isMovie());
    }

    @Test public void keepsSeriesGroupsAndOneByOneEpisodeNotation() throws Exception {
        String fixture = "#EXTM3U\n" +
                "#EXTINF:-1 group-title=Seriale,Miniserial 1x02\n" +
                "https://example.org/a.m3u8\n" +
                "#EXTINF:-1 group-title=Seriale,Inny serial\n" +
                "https://example.org/b.m3u8\n";
        List<Channel> parsed = M3uParser.parse(new ByteArrayInputStream(
                fixture.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, parsed.size());
        assertTrue(parsed.get(0).isEpisode());
        assertEquals("Miniserial", parsed.get(0).seriesId);
        assertEquals(2, parsed.get(0).episode);
        assertTrue(parsed.get(1).isEpisode());
        assertEquals("Seriale", parsed.get(1).group);
    }
}
