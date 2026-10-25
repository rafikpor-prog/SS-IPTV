package pl.rafik.ssiptv.epg;

import org.junit.Test;

import static org.junit.Assert.*;

public final class EpgDateParserTest {
    @Test public void convertsUtcAndOffsetsWithoutExpensiveFormatters() {
        assertEquals(1792890000000L, EpgDateParser.parse("20261025010000 +0000"));
        assertEquals(1792890000000L, EpgDateParser.parse("20261025030000 +0200"));
        assertEquals(1792890000000L, EpgDateParser.parse("20261024220000 -0300"));
        assertEquals(1792890000000L, EpgDateParser.parse("202610250100 +0000"));
    }

    @Test public void rejectsInvalidTimestamps() {
        assertEquals(0, EpgDateParser.parse(null));
        assertEquals(0, EpgDateParser.parse("20261325010000 +0000"));
        assertEquals(0, EpgDateParser.parse("20261025010000 +0069"));
    }
}
