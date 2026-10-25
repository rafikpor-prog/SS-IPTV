package pl.rafik.ssiptv.epg;

import org.junit.Test;

import static org.junit.Assert.*;

public final class EpgMatcherTest {
    @Test public void matchesPolishDiacriticsAndQualityLabels() {
        assertTrue(EpgMatcher.keys("CANAL+ SPORT 2 HD").contains("canalsport2"));
        assertTrue(EpgMatcher.keys("KANAŁ SPORT 4K").contains("kanalsport"));
    }

    @Test public void removesCountrySuffixFromEpgChannelIds() {
        assertTrue(EpgMatcher.keys("TVP1.pl").contains("tvp1"));
        assertTrue(EpgMatcher.keys("TVP 1 HD.PL").contains("tvp1"));
    }

    @Test public void keepsDistinctChannelNumbersAndNeverMatchesEmpty() {
        assertFalse(EpgMatcher.keys("TVP 1 HD").contains("tvp2"));
        assertTrue(EpgMatcher.keys(null).isEmpty());
    }
}
