package pl.rafik.ssiptv.data;
import org.junit.Test;
import static org.junit.Assert.*;
public class CatalogActivationTest {
 @Test public void selectedAndFallback(){assertEquals(3,CatalogActivation.position(3,0,10));assertEquals(4,CatalogActivation.position(-1,4,10));}
 @Test public void emptyAndInvalid(){assertEquals(-1,CatalogActivation.position(0,0,0));assertEquals(-1,CatalogActivation.position(8,9,2));assertEquals(-1,CatalogActivation.position(-1,-1,2));}
}
