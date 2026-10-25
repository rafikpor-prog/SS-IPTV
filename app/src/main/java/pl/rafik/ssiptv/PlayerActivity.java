package pl.rafik.ssiptv;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.util.HashMap;
import java.util.Map;

import pl.rafik.ssiptv.data.Channel;
import pl.rafik.ssiptv.data.ChannelDatabase;
import pl.rafik.ssiptv.data.SecureSettings;
import pl.rafik.ssiptv.data.XtreamClient;
import pl.rafik.ssiptv.ui.Ui;

/** Pełnoekranowy odtwarzacz HLS / TS / MP4 / RTSP oparty o AndroidX Media3. */
public final class PlayerActivity extends Activity {
    public static final String EXTRA_ID = "channel_id";
    private ExoPlayer player;

    @Override public void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.BLACK);
        setContentView(container);
        PlayerView playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        container.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        long id = getIntent().getLongExtra(EXTRA_ID, -1);
        Channel channel = null;
        for (Channel c : new ChannelDatabase(this).allChannels()) {
            if (c.id == id) { channel = c; break; }
        }
        if (channel == null) {
            Toast.makeText(this, "Nie znaleziono kanału", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        LinearLayout overlay = Ui.row(this);
        overlay.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        overlay.setBackgroundColor(0xB0081727);
        TextView back = Ui.button(this, "‹  Wróć", false, view -> finish());
        overlay.addView(back);
        TextView channelName = Ui.text(this, "  " + channel.name, 17, Ui.WHITE, true);
        overlay.addView(channelName);
        FrameLayout.LayoutParams overlayLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        container.addView(overlay, overlayLp);

        try {
            String stream = channel.url;
            if (stream.startsWith("xtream://")) {
                XtreamClient.Credentials creds = new SecureSettings(this).credentials();
                if (creds == null) throw new IllegalStateException("Brakuje danych serwera Xtream");
                stream = XtreamClient.streamUrl(creds, stream);
            }
            Map<String, String> headers = new HashMap<>();
            if (stream.contains("|")) {
                String[] segments = stream.split("\\|", 2);
                stream = segments[0];
                for (String pair : segments[1].split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2 &&
                            (kv[0].equalsIgnoreCase("User-Agent") || kv[0].equalsIgnoreCase("Referer"))) {
                        headers.put(kv[0], kv[1]);
                    }
                }
            }
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent("SS-IPTV/1.0 Android")
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(20000)
                    .setAllowCrossProtocolRedirects(true);
            if (!headers.isEmpty()) http.setDefaultRequestProperties(headers);
            player = new ExoPlayer.Builder(this)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(
                            new DefaultDataSource.Factory(this, http)))
                    .build();
            playerView.setPlayer(player);
            MediaItem.Builder media = new MediaItem.Builder().setUri(stream);
            String path = stream.split("\\?", 2)[0].toLowerCase(java.util.Locale.ROOT);
            if (path.endsWith(".m3u8")) media.setMimeType(MimeTypes.APPLICATION_M3U8);
            else if (path.startsWith("rtsp://")) media.setMimeType(MimeTypes.APPLICATION_RTSP);
            player.setMediaItem(media.build());
            player.addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException error) {
                    Toast.makeText(PlayerActivity.this,
                            "Nie udało się odtworzyć strumienia: " + error.getErrorCodeName(),
                            Toast.LENGTH_LONG).show();
                }
            });
            player.prepare();
            player.play();
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (player != null) player.play();
    }

    @Override protected void onStop() {
        if (player != null) player.pause();
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
