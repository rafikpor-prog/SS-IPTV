package pl.rafik.ssiptv.data;
public final class CatalogActivation {
 private CatalogActivation() {}
 // M3U episode-only sources synthesize a series card with a negative ID.
 public static boolean canOpen(long id, boolean series, String seriesId, String source) {
   return id > 0 || (id < 0 && series && seriesId != null && !seriesId.isEmpty() && source != null && !source.isEmpty());
 }
 public static int position(int selected, int firstVisible, int count) {
   if (count <= 0) return -1;
   if (selected >= 0 && selected < count) return selected;
   return firstVisible >= 0 && firstVisible < count ? firstVisible : -1;
 }
}
