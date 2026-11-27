package pl.rafik.ssiptv.data;
public final class CatalogActivation {
 private CatalogActivation() {}
 public static int position(int selected, int firstVisible, int count) {
   if (count <= 0) return -1;
   if (selected >= 0 && selected < count) return selected;
   return firstVisible >= 0 && firstVisible < count ? firstVisible : -1;
 }
}
