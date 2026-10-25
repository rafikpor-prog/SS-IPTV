package pl.rafik.ssiptv.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Prywatna baza: kanały, ulubione i podręczny przewodnik EPG. */
public final class ChannelDatabase extends SQLiteOpenHelper {
    public static final String M3U = "M3U";
    public static final String XTREAM = "XTREAM";
    // Baza SQLite pozostaje trwałym źródłem danych; pamięciowa migawka przyspiesza
    // powrót do TV i wejście w player bez ponownego odczytu tysięcy rekordów.
    private static final Object CATALOG_LOCK = new Object();
    private static List<Channel> catalogSnapshot;
    private static long catalogVersion;

    /** Zmienia się tylko po zmianie rzeczywistej zawartości źródeł/katalogu. */
    public static long getCatalogVersion() {
        synchronized (CATALOG_LOCK) { return catalogVersion; }
    }

    private static void invalidateCatalog() {
        synchronized (CATALOG_LOCK) {
            catalogSnapshot = null;
            catalogVersion++;
        }
    }

    public static final class Playlist {
        public String source;
        public String title;
        public String file;
        public String remoteUrl;
        public long lastSync;
        public int count;
    }

    public static final class Guide {
        public String current = "";
        public String next = "";
        public String description = "";
        public long start;
        public long end;
        public long nextStart;
    }

    public ChannelDatabase(Context context) {
        super(context, "ss_iptv.db", null, 4);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE channels (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, group_name TEXT NOT NULL, tvg_id TEXT, " +
                "logo TEXT, stream_url TEXT NOT NULL, source TEXT NOT NULL, " +
                "favorite INTEGER NOT NULL DEFAULT 0, " +
                "is_vod INTEGER NOT NULL DEFAULT 0, last_watched INTEGER NOT NULL DEFAULT 0, " +
                "playback_ms INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER NOT NULL DEFAULT 0, " +
                "kind TEXT NOT NULL DEFAULT 'TV', description TEXT NOT NULL DEFAULT '', " +
                "series_id TEXT NOT NULL DEFAULT '', season_no INTEGER NOT NULL DEFAULT 0, " +
                "episode_no INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX channel_group_index ON channels(group_name)");
        db.execSQL("CREATE INDEX channel_source_index ON channels(source)");
        db.execSQL("CREATE INDEX channel_catalog_order ON channels(" +
                "source COLLATE NOCASE, position, name COLLATE NOCASE)");
        db.execSQL("CREATE TABLE epg (channel_id INTEGER PRIMARY KEY, " +
                "title TEXT, next_title TEXT, description TEXT NOT NULL DEFAULT '', " +
                "start_ms INTEGER, end_ms INTEGER, next_ms INTEGER)");
        db.execSQL("CREATE TABLE playlists (source TEXT PRIMARY KEY, title TEXT NOT NULL, " +
                "file_path TEXT NOT NULL DEFAULT '', " +
                "remote_url TEXT NOT NULL DEFAULT '', last_synced_ms INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE series_favorites (source TEXT NOT NULL, series_id TEXT NOT NULL, " +
                "PRIMARY KEY(source,series_id))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE channels ADD COLUMN is_vod INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channels ADD COLUMN last_watched INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channels ADD COLUMN playback_ms INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channels ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE channels ADD COLUMN kind TEXT NOT NULL DEFAULT 'TV'");
            db.execSQL("ALTER TABLE channels ADD COLUMN description TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE channels ADD COLUMN series_id TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE channels ADD COLUMN season_no INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channels ADD COLUMN episode_no INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channels ADD COLUMN position INTEGER NOT NULL DEFAULT 0");
            db.execSQL("UPDATE channels SET position=_id");
            db.execSQL("UPDATE channels SET kind='MOVIE' WHERE is_vod=1");
            db.execSQL("ALTER TABLE epg ADD COLUMN description TEXT NOT NULL DEFAULT ''");
            db.execSQL("CREATE TABLE playlists (source TEXT PRIMARY KEY, title TEXT NOT NULL, " +
                    "file_path TEXT NOT NULL DEFAULT '')");
            db.execSQL("CREATE TABLE series_favorites (source TEXT NOT NULL, series_id TEXT NOT NULL, " +
                    "PRIMARY KEY(source,series_id))");
            db.execSQL("INSERT INTO playlists(source,title,file_path) " +
                    "SELECT 'M3U','Pierwsza lista M3U','playlist.m3u' WHERE EXISTS " +
                    "(SELECT 1 FROM channels WHERE source='M3U')");
            db.execSQL("INSERT INTO playlists(source,title,file_path) " +
                    "SELECT 'XTREAM','Konto IPTV','' WHERE EXISTS " +
                    "(SELECT 1 FROM channels WHERE source='XTREAM')");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE playlists ADD COLUMN remote_url TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE playlists ADD COLUMN last_synced_ms INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE INDEX channel_catalog_order ON channels(" +
                    "source COLLATE NOCASE, position, name COLLATE NOCASE)");
        }
    }

    public synchronized void savePlaylist(String source, String title, String file) {
        savePlaylist(source, title, file, "", 0);
    }

    public synchronized void savePlaylist(String source, String title, String file,
                                          String remoteUrl, long syncTime) {
        ContentValues values = new ContentValues();
        values.put("source", source);
        values.put("title", title);
        values.put("file_path", file == null ? "" : file);
        values.put("remote_url", remoteUrl == null ? "" : remoteUrl);
        values.put("last_synced_ms", Math.max(0, syncTime));
        getWritableDatabase().insertWithOnConflict("playlists", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<Playlist> playlists() {
        ArrayList<Playlist> result = new ArrayList<>();
        String sql = "SELECT p.source,p.title,p.file_path,COUNT(c._id)," +
                "p.remote_url,p.last_synced_ms FROM playlists p " +
                "LEFT JOIN channels c ON c.source=p.source GROUP BY p.source " +
                "HAVING COUNT(c._id)>0 ORDER BY p.title COLLATE NOCASE";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                Playlist item = new Playlist();
                item.source = cursor.getString(0);
                item.title = cursor.getString(1);
                item.file = cursor.getString(2);
                item.count = cursor.getInt(3);
                item.remoteUrl = cursor.getString(4);
                item.lastSync = cursor.getLong(5);
                result.add(item);
            }
        }
        return result;
    }

    public synchronized Set<String> seriesFavorites() {
        Set<String> favorites = new HashSet<>();
        try (Cursor cursor = getReadableDatabase().query("series_favorites",
                new String[]{"source", "series_id"}, null, null, null, null, null)) {
            while (cursor.moveToNext()) favorites.add(cursor.getString(0) + "\u0000" +
                    cursor.getString(1));
        }
        return favorites;
    }

    public synchronized void setSeriesFavorite(String source, String seriesId, boolean favorite) {
        SQLiteDatabase db = getWritableDatabase();
        if (!favorite) db.delete("series_favorites", "source=? AND series_id=?",
                new String[]{source, seriesId});
        else {
            ContentValues values = new ContentValues();
            values.put("source", source);
            values.put("series_id", seriesId);
            db.insertWithOnConflict("series_favorites", null, values,
                    SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    public synchronized int replaceSource(String source, List<Channel> channels) {
        SQLiteDatabase db = getWritableDatabase();
        Map<String, long[]> previous = new HashMap<>();
        try (Cursor c = db.query("channels",
                new String[]{"stream_url", "favorite", "last_watched", "playback_ms", "duration_ms"},
                "source=?",
                new String[]{source}, null, null, null)) {
            while (c.moveToNext()) previous.put(c.getString(0),
                    new long[]{c.getInt(1), c.getLong(2), c.getLong(3), c.getLong(4)});
        }
        db.beginTransaction();
        try {
            db.delete("epg", "channel_id IN (SELECT _id FROM channels WHERE source=?)", new String[]{source});
            if (XTREAM.equals(source)) db.delete("channels", "source=? AND kind!='EPISODE'",
                    new String[]{source});
            else db.delete("channels", "source=?", new String[]{source});
            int position = 0;
            for (Channel item : channels) {
                item.ordinal = ++position;
                ContentValues values = values(item, source);
                long[] state = previous.get(item.url);
                values.put("favorite", state == null ? 0 : state[0]);
                values.put("last_watched", state == null ? 0 : state[1]);
                values.put("playback_ms", state == null ? 0 : state[2]);
                values.put("duration_ms", state == null ? 0 : state[3]);
                db.insertOrThrow("channels", null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        invalidateCatalog();
        return channels.size();
    }

    private ContentValues values(Channel item, String source) {
        ContentValues values = new ContentValues();
        values.put("name", item.name);
        values.put("group_name", item.group);
        values.put("tvg_id", item.tvgId);
        values.put("logo", item.logo);
        values.put("stream_url", item.url);
        values.put("source", source);
        values.put("is_vod", item.vod ? 1 : 0);
        values.put("kind", item.kind);
        values.put("description", item.description);
        values.put("series_id", item.seriesId);
        values.put("season_no", item.season);
        values.put("episode_no", item.episode);
        values.put("position", item.ordinal);
        return values;
    }

    /** Episodów dostarczonych przez Xtream nie traci się przy odświeżaniu serialu. */
    public synchronized void replaceSeriesEpisodes(String source, String seriesId, List<Channel> episodes) {
        SQLiteDatabase db = getWritableDatabase();
        Map<String, long[]> old = new HashMap<>();
        try (Cursor c = db.query("channels",
                new String[]{"stream_url", "favorite", "last_watched", "playback_ms", "duration_ms"},
                "source=? AND series_id=? AND kind='EPISODE'", new String[]{source, seriesId},
                null, null, null)) {
            while (c.moveToNext()) old.put(c.getString(0),
                    new long[]{c.getInt(1), c.getLong(2), c.getLong(3), c.getLong(4)});
        }
        db.beginTransaction();
        try {
            db.delete("channels", "source=? AND series_id=? AND kind='EPISODE'",
                    new String[]{source, seriesId});
            for (Channel item : episodes) {
                item.ordinal = item.season * 10000 + item.episode;
                ContentValues values = values(item, source);
                long[] state = old.get(item.url);
                values.put("favorite", state == null ? 0 : state[0]);
                values.put("last_watched", state == null ? 0 : state[1]);
                values.put("playback_ms", state == null ? 0 : state[2]);
                values.put("duration_ms", state == null ? 0 : state[3]);
                db.insertOrThrow("channels", null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        invalidateCatalog();
    }

    public synchronized void updateMetadata(long id, String description, String poster) {
        ContentValues values = new ContentValues();
        if (description != null && !description.trim().isEmpty()) values.put("description", description);
        if (poster != null && !poster.trim().isEmpty()) values.put("logo", poster);
        if (values.size() > 0) getWritableDatabase().update("channels", values,
                "_id=?", new String[]{Long.toString(id)});
        if (values.size() > 0) invalidateCatalog();
    }

    public synchronized void deleteSource(String source) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("epg", "channel_id IN (SELECT _id FROM channels WHERE source=?)", new String[]{source});
            db.delete("channels", "source=?", new String[]{source});
            db.delete("playlists", "source=?", new String[]{source});
            db.delete("series_favorites", "source=?", new String[]{source});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        invalidateCatalog();
    }

    public synchronized List<Channel> allChannels() {
        synchronized (CATALOG_LOCK) {
            if (catalogSnapshot != null) return new ArrayList<>(catalogSnapshot);
            ArrayList<Channel> items = new ArrayList<>();
            try (Cursor c = getReadableDatabase().query("channels", null, null, null, null, null,
                    "source COLLATE NOCASE, position, name COLLATE NOCASE")) {
                while (c.moveToNext()) {
                    Channel item = new Channel(c.getString(c.getColumnIndexOrThrow("name")),
                            c.getString(c.getColumnIndexOrThrow("group_name")),
                            c.getString(c.getColumnIndexOrThrow("tvg_id")),
                            c.getString(c.getColumnIndexOrThrow("logo")),
                            c.getString(c.getColumnIndexOrThrow("stream_url")),
                            c.getString(c.getColumnIndexOrThrow("source")));
                    item.id = c.getLong(c.getColumnIndexOrThrow("_id"));
                    item.favorite = c.getInt(c.getColumnIndexOrThrow("favorite")) == 1;
                    item.vod = c.getInt(c.getColumnIndexOrThrow("is_vod")) == 1;
                    item.lastWatched = c.getLong(c.getColumnIndexOrThrow("last_watched"));
                    item.playbackMs = c.getLong(c.getColumnIndexOrThrow("playback_ms"));
                    item.durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms"));
                    item.kind = c.getString(c.getColumnIndexOrThrow("kind"));
                    item.description = c.getString(c.getColumnIndexOrThrow("description"));
                    item.seriesId = c.getString(c.getColumnIndexOrThrow("series_id"));
                    item.season = c.getInt(c.getColumnIndexOrThrow("season_no"));
                    item.episode = c.getInt(c.getColumnIndexOrThrow("episode_no"));
                    item.ordinal = c.getInt(c.getColumnIndexOrThrow("position"));
                    items.add(item);
                }
            }
            catalogSnapshot = items;
            return new ArrayList<>(items);
        }
    }

    public synchronized void setFavorite(long id, boolean favorite) {
        ContentValues values = new ContentValues();
        values.put("favorite", favorite ? 1 : 0);
        getWritableDatabase().update("channels", values, "_id=?", new String[]{Long.toString(id)});
        synchronized (CATALOG_LOCK) {
            if (catalogSnapshot != null) for (Channel item : catalogSnapshot)
                if (item.id == id) { item.favorite = favorite; break; }
        }
    }

    public synchronized void markWatched(long id) {
        ContentValues values = new ContentValues();
        values.put("last_watched", System.currentTimeMillis());
        getWritableDatabase().update("channels", values, "_id=?", new String[]{Long.toString(id)});
        synchronized (CATALOG_LOCK) {
            if (catalogSnapshot != null) for (Channel item : catalogSnapshot)
                if (item.id == id) { item.lastWatched = values.getAsLong("last_watched"); break; }
        }
    }

    public synchronized void savePlayback(long id, long position, long duration) {
        ContentValues values = new ContentValues();
        values.put("playback_ms", Math.max(0, position));
        values.put("duration_ms", Math.max(0, duration));
        getWritableDatabase().update("channels", values, "_id=?", new String[]{Long.toString(id)});
        synchronized (CATALOG_LOCK) {
            if (catalogSnapshot != null) for (Channel item : catalogSnapshot)
                if (item.id == id) { item.playbackMs = Math.max(0, position);
                    item.durationMs = Math.max(0, duration); break; }
        }
    }

    public synchronized Map<Long, Guide> readGuides() {
        Map<Long, Guide> guides = new HashMap<>();
        try (Cursor c = getReadableDatabase().query("epg", null, null, null, null, null, null)) {
            while (c.moveToNext()) {
                Guide g = new Guide();
                g.current = c.getString(c.getColumnIndexOrThrow("title"));
                g.next = c.getString(c.getColumnIndexOrThrow("next_title"));
                g.description = c.getString(c.getColumnIndexOrThrow("description"));
                g.start = c.getLong(c.getColumnIndexOrThrow("start_ms"));
                g.end = c.getLong(c.getColumnIndexOrThrow("end_ms"));
                g.nextStart = c.getLong(c.getColumnIndexOrThrow("next_ms"));
                guides.put(c.getLong(c.getColumnIndexOrThrow("channel_id")), g);
            }
        }
        return guides;
    }

    public synchronized void replaceGuides(Map<Long, Guide> guides) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("epg", null, null);
            for (Map.Entry<Long, Guide> entry : guides.entrySet()) {
                Guide g = entry.getValue();
                ContentValues values = new ContentValues();
                values.put("channel_id", entry.getKey());
                values.put("title", g.current);
                values.put("next_title", g.next);
                values.put("description", g.description);
                values.put("start_ms", g.start);
                values.put("end_ms", g.end);
                values.put("next_ms", g.nextStart);
                db.insertOrThrow("epg", null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
