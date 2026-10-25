package pl.rafik.ssiptv.data;

import android.content.Context;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.os.ParcelFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
        try (InputStream in = openDocumentStream(ctx, fileUri)) {
            if (in == null) throw new IOException("Nie można otworzyć wskazanego pliku");
            return install(ctx, in, title, "");
        }
    }

    private static InputStream openDocumentStream(Context context, Uri uri) throws IOException {
        if (uri == null) throw new IOException("Nie wskazano pliku M3U");
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            if (uri.getPath() == null) throw new IOException("Plik nie ma ścieżki");
            return new FileInputStream(uri.getPath());
        }
        try {
            InputStream direct = context.getContentResolver().openInputStream(uri);
            if (direct != null) return direct;
        } catch (SecurityException ex) {
            throw new IOException("Brak dostępu do pliku. Wybierz go ponownie w menedżerze plików.", ex);
        } catch (IOException first) {
            // Niektórzy dostawcy plików dla Android TV udostępniają deskryptor,
            // ale nie implementują ContentResolver.openInputStream().
            try {
                ParcelFileDescriptor pfd = context.getContentResolver()
                        .openFileDescriptor(uri, "r");
                if (pfd != null) return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            } catch (Exception ignored) { }
            throw first;
        }
        throw new IOException("Menedżer plików nie udostępnił zawartości M3U");
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
                return install(ctx, in, new URL(url).getHost() + " • M3U", url);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static List<Channel> install(Context ctx, InputStream input, String title,
                                         String remoteUrl) throws Exception {
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
                        filename, remoteUrl, System.currentTimeMillis());
                new SecureSettings(ctx).invalidateEpgCache();
            } catch (Exception failure) {
                target.delete();
                throw failure;
            }
            return channels;
        } finally {
            if (staging.exists()) staging.delete();
        }
    }

    /** Raz na 7 dni odnawiamy tylko listy zaimportowane z zapamiętanego URL. */
    public static void refreshRemoteSourcesIfDue(Context context, Runnable onChanged) {
        Context app = context.getApplicationContext();
        Thread worker = new Thread(() -> {
            ChannelDatabase db = new ChannelDatabase(app);
            boolean updated = false;
            long now = System.currentTimeMillis();
            for (ChannelDatabase.Playlist entry : db.playlists()) {
                if (!PlaylistCachePolicy.isRefreshDue(entry.remoteUrl, entry.lastSync, now))
                    continue;
                try {
                    updateStoredSource(app, db, entry);
                    updated = true;
                } catch (Exception error) {
                    // Sieć może być niedostępna. Zostawiamy całą poprzednią listę.
                    Log.w("SS-IPTV-Playlist", "Weekly refresh failed for " + entry.source +
                            " · " + error.getClass().getSimpleName());
                }
            }
            if (updated && onChanged != null)
                new Handler(Looper.getMainLooper()).post(onChanged);
        }, "ss-playlist-weekly");
        worker.start();
    }

    private static void updateStoredSource(Context app, ChannelDatabase db,
                                            ChannelDatabase.Playlist entry) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(entry.remoteUrl).openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(17000);
        File staging = new File(app.getFilesDir(), entry.file + ".weekly.tmp");
        try {
            if (connection.getResponseCode() / 100 != 2)
                throw new IOException("HTTP " + connection.getResponseCode());
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(staging)) {
                byte[] bytes = new byte[16384];
                long total = 0;
                for (int count; (count = in.read(bytes)) != -1; ) {
                    total += count;
                    if (total > MAX_BYTES) throw new IOException("M3U przekracza limit 32 MB");
                    out.write(bytes, 0, count);
                }
            }
            List<Channel> channels;
            try (FileInputStream in = new FileInputStream(staging)) {
                channels = M3uParser.parse(in);
            }
            if (channels.isEmpty()) throw new IOException("Pusta lista M3U");
            db.replaceSource(entry.source, channels);
            File original = new File(app.getFilesDir(), entry.file);
            if (!staging.renameTo(original)) {
                // Nie marnujemy poprawnie odnowionej bazy w razie błędu pliku.
                Log.w("SS-IPTV-Playlist", "Weekly M3U file could not replace the cache");
            }
            new SecureSettings(app).invalidateEpgCache();
            db.savePlaylist(entry.source, entry.title, entry.file, entry.remoteUrl,
                    System.currentTimeMillis());
        } finally {
            connection.disconnect();
            if (staging.exists()) staging.delete();
        }
    }
}
