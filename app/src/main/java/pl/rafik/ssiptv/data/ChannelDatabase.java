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

    public static final class Guide {
        public String current = "";
        public String next = "";
        public long start;
        public long end;
        public long nextStart;
    }

    public ChannelDatabase(Context context) {
        super(context, "ss_iptv.db", null, 2);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE channels (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, group_name TEXT NOT NULL, tvg_id TEXT, " +
                "logo TEXT, stream_url TEXT NOT NULL, source TEXT NOT NULL, " +
                "favorite INTEGER NOT NULL DEFAULT 0, " +
                "is_vod INTEGER NOT NULL DEFAULT 0, last_watched INTEGER NOT NULL DEFAULT 0, " +
                "playback_ms INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX channel_group_index ON channels(group_name)");
        db.execSQL("CREATE INDEX channel_source_index ON channels(source)");
        db.execSQL("CREATE TABLE epg (channel_id INTEGER PRIMARY KEY, " +
                "title TEXT, next_title TEXT, start_ms INTEGER, end_ms INTEGER, next_ms INTEGER)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE channels ADD COLUMN is_vod INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channels ADD COLUMN last_watched INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channels ADD COLUMN playback_ms INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channels ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0");
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
            db.delete("channels", "source=?", new String[]{source});
            for (Channel item : channels) {
                ContentValues values = new ContentValues();
                values.put("name", item.name);
                values.put("group_name", item.group);
                values.put("tvg_id", item.tvgId);
                values.put("logo", item.logo);
                values.put("stream_url", item.url);
                values.put("source", source);
                long[] state = previous.get(item.url);
                values.put("favorite", state == null ? 0 : state[0]);
                values.put("is_vod", item.vod ? 1 : 0);
                values.put("last_watched", state == null ? 0 : state[1]);
                values.put("playback_ms", state == null ? 0 : state[2]);
                values.put("duration_ms", state == null ? 0 : state[3]);
                db.insertOrThrow("channels", null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return channels.size();
    }

    public synchronized void deleteSource(String source) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("epg", "channel_id IN (SELECT _id FROM channels WHERE source=?)", new String[]{source});
            db.delete("channels", "source=?", new String[]{source});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<Channel> allChannels() {
        ArrayList<Channel> items = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("channels", null, null, null, null, null,
                "favorite DESC, group_name COLLATE NOCASE, name COLLATE NOCASE")) {
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
                items.add(item);
            }
        }
        return items;
    }

    public synchronized void setFavorite(long id, boolean favorite) {
        ContentValues values = new ContentValues();
        values.put("favorite", favorite ? 1 : 0);
        getWritableDatabase().update("channels", values, "_id=?", new String[]{Long.toString(id)});
    }

    public synchronized void markWatched(long id) {
        ContentValues values = new ContentValues();
        values.put("last_watched", System.currentTimeMillis());
        getWritableDatabase().update("channels", values, "_id=?", new String[]{Long.toString(id)});
    }

    public synchronized void savePlayback(long id, long position, long duration) {
        ContentValues values = new ContentValues();
        values.put("playback_ms", Math.max(0, position));
        values.put("duration_ms", Math.max(0, duration));
        getWritableDatabase().update("channels", values, "_id=?", new String[]{Long.toString(id)});
    }

    public synchronized Map<Long, Guide> readGuides() {
        Map<Long, Guide> guides = new HashMap<>();
        try (Cursor c = getReadableDatabase().query("epg", null, null, null, null, null, null)) {
            while (c.moveToNext()) {
                Guide g = new Guide();
                g.current = c.getString(c.getColumnIndexOrThrow("title"));
                g.next = c.getString(c.getColumnIndexOrThrow("next_title"));
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
