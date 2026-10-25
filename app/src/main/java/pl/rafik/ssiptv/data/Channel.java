package pl.rafik.ssiptv.data;

public final class Channel {
    public long id;
    public String name;
    public String group;
    public String tvgId;
    public String logo;
    public String url;
    public String source;
    public boolean favorite;
    public boolean vod;
    public long lastWatched;
    public long playbackMs;
    public long durationMs;

    public Channel(String name, String group, String tvgId, String logo, String url, String source) {
        this.name = clean(name, "Bez nazwy");
        this.group = clean(group, "Inne");
        this.tvgId = clean(tvgId, "");
        this.logo = clean(logo, "");
        this.url = clean(url, "");
        this.source = clean(source, "M3U");
    }

    public Channel asVod() {
        this.vod = true;
        return this;
    }

    private static String clean(String text, String fallback) {
        return text == null || text.trim().isEmpty() ? fallback : text.trim();
    }
}
