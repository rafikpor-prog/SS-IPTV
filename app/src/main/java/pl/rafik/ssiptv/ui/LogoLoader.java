package pl.rafik.ssiptv.ui;

import android.content.ComponentCallbacks2;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Mała pamięć podręczna logotypów/posterów. Brak dostępu sieciowego z wątku UI. */
public final class LogoLoader {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final ConcurrentHashMap<String, ArrayList<WeakReference<ImageView>>> PENDING =
            new ConcurrentHashMap<>();
    private LogoLoader() {}

    public static void reduceMemoryForPlayback() {
        // Podczas wideo pamięć powinna trafić do sprzętowego dekodera, nie logo.
        CACHE.trimToSize(1024);
    }

    public static void onTrimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) CACHE.evictAll();
    }

    public static void load(ImageView view, String address) {
        view.setTag(address);
        view.setImageDrawable(null);
        if (address == null || !(address.startsWith("https://") || address.startsWith("http://")))
            return;
        Bitmap cached = CACHE.get(address);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        ArrayList<WeakReference<ImageView>> waiting = new ArrayList<>();
        ArrayList<WeakReference<ImageView>> existing = PENDING.putIfAbsent(address, waiting);
        if (existing != null) {
            synchronized (existing) { existing.add(new WeakReference<>(view)); }
            return;
        }
        synchronized (waiting) { waiting.add(new WeakReference<>(view)); }
        POOL.execute(() -> {
            Bitmap bitmap = fetch(address);
            if (bitmap != null) CACHE.put(address, bitmap);
            ArrayList<WeakReference<ImageView>> listeners = PENDING.remove(address);
            if (bitmap == null || listeners == null) return;
            synchronized (listeners) {
                for (WeakReference<ImageView> listener : listeners) {
                    ImageView image = listener.get();
                    if (image != null) image.post(() -> {
                        if (address.equals(image.getTag())) image.setImageBitmap(bitmap);
                    });
                }
            }
        });
    }

    private static Bitmap fetch(String address) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "SS-IPTV/1.1 Android");
            if (conn.getResponseCode() / 100 != 2) return null;
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 2 * 1024 * 1024) return null;
                    out.write(buffer, 0, count);
                }
                byte[] bytes = out.toByteArray();
                BitmapFactory.Options info = new BitmapFactory.Options();
                info.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, info);
                BitmapFactory.Options scaled = new BitmapFactory.Options();
                scaled.inSampleSize = 1;
                while (info.outWidth / scaled.inSampleSize > 512 ||
                        info.outHeight / scaled.inSampleSize > 512) {
                    scaled.inSampleSize *= 2;
                }
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, scaled);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
