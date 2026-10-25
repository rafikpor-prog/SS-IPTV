package pl.rafik.ssiptv.data;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/** Zachowuje oryginalną playlistę M3U w prywatnej pamięci aplikacji. */
public final class PlaylistLoader {
    private PlaylistLoader() {}
    private static final long MAX_BYTES = 32L * 1024 * 1024;

    public static List<Channel> importDocument(Context ctx, Uri fileUri) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(fileUri)) {
            if (in == null) throw new IOException("Nie można otworzyć wskazanego pliku");
            return install(ctx, in);
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
                return install(ctx, in);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static List<Channel> install(Context ctx, InputStream input) throws Exception {
        File staging = new File(ctx.getFilesDir(), "playlist.download");
        File target = new File(ctx.getFilesDir(), "playlist.m3u");
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
            new ChannelDatabase(ctx).replaceSource(ChannelDatabase.M3U, channels);
            if (!staging.renameTo(target)) {
                throw new IOException("Nie udało się zachować pliku M3U lokalnie");
            }
            return channels;
        } finally {
            if (staging.exists()) staging.delete();
        }
    }
}
