package pl.rafik.ssiptv.data;
import org.junit.Test;
import static org.junit.Assert.*;
public class CatalogActivationTest {
 @Test public void selectedAndFallback(){assertEquals(3,CatalogActivation.position(3,0,10));assertEquals(4,CatalogActivation.position(-1,4,10));}
 @Test public void syntheticSeries(){assertTrue(CatalogActivation.canOpen(-5,true,"series","M3U"));assertFalse(CatalogActivation.canOpen(-5,false,"series","M3U"));assertFalse(CatalogActivation.canOpen(-5,true,"","M3U"));assertTrue(CatalogActivation.canOpen(7,false,null,null));}
 @Test public void emptyAndInvalid(){assertEquals(-1,CatalogActivation.position(0,0,0));assertEquals(-1,CatalogActivation.position(8,9,2));assertEquals(-1,CatalogActivation.position(-1,-1,2));}
}
