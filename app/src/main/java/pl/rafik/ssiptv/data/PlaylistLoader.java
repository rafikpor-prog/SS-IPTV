package pl.rafik.ssiptv.data;

import android.content.Context;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.UUID;

/** Zachowuje oryginalną playlistę M3U w prywatnej pamięci aplikacji. */
public final class PlaylistLoader {
    private PlaylistLoader() {}
    private static final long MAX_BYTES = 32L * 1024 * 1024;

    public static List<Channel> importDocument(Context ctx, Uri fileUri) throws Exception {
        String title = "Lista M3U";
        try (Cursor cursor = ctx.getContentResolver().query(fileUri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) title = cursor.getString(0);
        } catch (Exception ignored) { }
        try (InputStream in = ctx.getContentResolver().openInputStream(fileUri)) {
            if (in == null) throw new IOException("Nie można otworzyć wskazanego pliku");
            return install(ctx, in, title);
        }
    }

    public static List<Channel> importWeb(Context ctx, String url) throws Exception {
        if (!url.startsWith("https://") && !url.startsWith("http://"))
            throw new IOException("Link playlisty musi rozpoczynać się od https:// lub http://");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(30000);
        try {
            if (connection.getResponseCode() / 100 != 2)
                throw new IOException("Błąd pobierania M3U: HTTP " + connection.getResponseCode());
            try (InputStream in = connection.getInputStream()) {
                return install(ctx, in, new URL(url).getHost() + " • M3U");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static List<Channel> install(Context ctx, InputStream input, String title) throws Exception {
        String id = UUID.randomUUID().toString();
        String filename = "playlist_" + id + ".m3u";
        String source = "M3U:" + id;
        File staging = new File(ctx.getFilesDir(), filename + ".tmp");
        File target = new File(ctx.getFilesDir(), filename);
        try {
            try (FileOutputStream out = new FileOutputStream(staging)) {
                byte[] block = new byte[16384];
                long total = 0;
                int n;
                while ((n = input.read(block)) != -1) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("Playlist M3U przekracza limit 32 MB");
                    out.write(block, 0, n);
                }
            }
            List<Channel> channels;
            try (FileInputStream in = new FileInputStream(staging)) {
                channels = M3uParser.parse(in);
            }
            if (channels.isEmpty()) throw new IOException("W playliście nie znaleziono adresów kanałów");
            if (!staging.renameTo(target)) {
                throw new IOException("Nie udało się zachować pliku M3U lokalnie");
            }
            ChannelDatabase db = new ChannelDatabase(ctx);
            try {
                db.replaceSource(source, channels);
                db.savePlaylist(source, title == null || title.isEmpty() ? "Lista M3U" : title,
                        filename);
            } catch (Exception failure) {
                target.delete();
                throw failure;
            }
            return channels;
        } finally {
            if (staging.exists()) staging.delete();
        }
    }
}
