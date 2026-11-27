package pl.rafik.ssiptv.data;
import org.junit.Test;
import static org.junit.Assert.*;
public class LibrarySearchTest {
 @Test public void diacriticsAndCase(){assertTrue(LibrarySearch.matches("LODZ zolc", "Łódź", "Żółć"));}
 @Test public void allTokens(){assertTrue(LibrarySearch.matches("sport hd", "CANAL HD", "Sport"));assertFalse(LibrarySearch.matches("sport uhd","Sport HD"));}
 @Test public void nullEmptyAndLiteral(){assertTrue(LibrarySearch.matches(" ", (String)null));assertFalse(LibrarySearch.matches("abc",(String)null));assertFalse(LibrarySearch.matches("%", "Film"));assertTrue(LibrarySearch.matches("%", "100%"));}
}
