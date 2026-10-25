package pl.rafik.ssiptv;

import org.junit.Test;

import static org.junit.Assert.*;

public final class TvNavigationTest {
    @Test public void listCanAlwaysOpenLeftMenu() {
        assertTrue(TvNavigation.opensDrawerFromLeft(0, false, 4));
        assertTrue(TvNavigation.opensDrawerFromLeft(39, false, 4));
    }

    @Test public void gridOpensMenuOnlyFromFirstColumn() {
        assertTrue(TvNavigation.opensDrawerFromLeft(0, true, 4));
        assertTrue(TvNavigation.opensDrawerFromLeft(8, true, 4));
        assertFalse(TvNavigation.opensDrawerFromLeft(1, true, 4));
        assertFalse(TvNavigation.opensDrawerFromLeft(11, true, 4));
    }

    @Test public void gridColumnsFitNarrowAndWideTelevisions() {
        assertEquals(2, TvNavigation.gridColumns(420));
        assertEquals(4, TvNavigation.gridColumns(960));
        assertEquals(6, TvNavigation.gridColumns(1900));
    }
}
