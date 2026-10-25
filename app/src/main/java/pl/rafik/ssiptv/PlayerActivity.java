package pl.rafik.ssiptv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.C;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pl.rafik.ssiptv.data.Channel;
import pl.rafik.ssiptv.data.ChannelDatabase;
import pl.rafik.ssiptv.data.SecureSettings;
import pl.rafik.ssiptv.data.XtreamClient;
import pl.rafik.ssiptv.ui.LogoLoader;
import pl.rafik.ssiptv.ui.Ui;

/**
 * Odtwarzacz TV/VOD z OSD, ulubionymi, rzeczywistą jakością z VideoSize
 * i zapamiętywaniem postępu VOD w prywatnej bazie urządzenia.
 */
public final class PlayerActivity extends Activity {
    public static final String EXTRA_ID = "channel_id";
    public static final String EXTRA_EXTERNAL_URI = "external_video_uri";
    public static final String EXTRA_EXTERNAL_NAME = "external_video_name";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService storage = Executors.newSingleThreadExecutor();
    private ExoPlayer player;
    private LibVLC libVlc;
    private org.videolan.libvlc.MediaPlayer vlcPlayer;
    private VLCVideoLayout vlcView;
    private boolean usingVlc;
    private boolean leftScreen;
    private boolean userPaused;
    private int vlcRecoveryAttempts;
    private int exoRecoveryAttempts;
    private int qualityCap = Integer.MAX_VALUE;
    private boolean adaptiveStream;
    private int lowBufferSamples;
    private long engineStartedAt;
    private long lastVlcTrafficBytes = -1;
    private final PlaybackWatchdog watchdog = new PlaybackWatchdog();
    private Channel channel;
    private ChannelDatabase database;
    private LinearLayout osd;
    private FrameLayout root;
    private PlayerView playerView;
    private ScrollView settingsPanel;
    private TextView expandedDescription;
    private ScrollView descriptionScroll;
    private TextView numberOverlay;
    private boolean descriptionOpen;
    private boolean osdNavigationMode;
    private int consumedRemoteKey = KeyEvent.KEYCODE_UNKNOWN;
    private int recoveryAttempts;
    private long playGeneration;
    private TextView channelNameText;
    private TextView initialsText;
    private ImageView osdLogo;
    private TextView nowText;
    private TextView nextText;
    private TextView bufferingText;
    private TextView networkText;
    private ImageView networkIcon;
    private TextView audioWarning;
    private View firstOsdAction;
    private List<Channel> cachedLive = new ArrayList<>();
    private final StringBuilder numberInput = new StringBuilder();
    private final Runnable commitNumber = this::commitChannelNumber;
    private ImageView heart;
    private TextView quality;
    private TextView progress;
    private final Runnable hideOsd = () -> {
        if (osd != null && !osdNavigationMode && !descriptionOpen && settingsPanel == null)
            osd.setVisibility(View.GONE);
    };
    private final Runnable updateEpg = new Runnable() {
        @Override public void run() {
            if (!isFinishing()) updateProgramLabels();
            handler.postDelayed(this, 60000);
        }
    };
    private final Runnable saveTick = new Runnable() {
        @Override public void run() {
            saveProgress();
            handler.postDelayed(this, 5000);
        }
    };
    private final Runnable watchTick = new Runnable() {
        @Override public void run() {
            if (!isFinishing() && !isDestroyed()) {
                updateNetworkOsd();
                inspectPlayback();
                handler.postDelayed(this, PlaybackWatchdog.CHECK_INTERVAL_MS);
            }
        }
    };

    @Override public void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        database = new ChannelDatabase(this);
        long id = getIntent().getLongExtra(EXTRA_ID, -1);
        for (Channel item : database.allChannels()) {
            if (item.id == id) { channel = item; break; }
        }
        if (channel == null) {
            String externalUri = getIntent().getStringExtra(EXTRA_EXTERNAL_URI);
            if (externalUri != null && !externalUri.isEmpty() &&
                    (externalUri.startsWith("content://") || externalUri.startsWith("file://") ||
                            externalUri.startsWith("https://") || externalUri.startsWith("http://"))) {
                channel = new Channel(getIntent().getStringExtra(EXTRA_EXTERNAL_NAME),
                        "Wideo zewnętrzne", "", "", externalUri, "EXTERNAL").asVod();
                channel.id = -1;
            }
        }
        if (channel == null) {
            Toast.makeText(this, "Nie znaleziono kanału lub filmu", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        cachedLive = liveChannels();
        if (channel.id > 0) storage.execute(() -> database.markWatched(channel.id));

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        setContentView(root);
        playerView = new PlayerView(this);
        // W TV nie pokazujemy panelu Media3 nad OSD i nie pozwalamy mu przechwytywać ↑/↓.
        // W VOD natywny pasek pauzy, przewijania i wznowienia pozostaje dostępny.
        playerView.setUseController(channel.vod);
        playerView.setFocusable(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        playerView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) showOsd();
            return false;
        });
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        vlcView = new VLCVideoLayout(this);
        vlcView.setVisibility(View.GONE);
        vlcView.setFocusable(false);
        root.addView(vlcView, new FrameLayout.LayoutParams(-1, -1));

        addOsd(root);
        ImageView info = new ImageView(this);
        info.setImageResource(R.drawable.ic_player_info);
        info.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        info.setContentDescription("Pokaż informacje o kanale i sterowanie OSD");
        info.setBackground(Ui.rounded(this, 0xC0102D43, 50));
        info.setOnClickListener(v -> showOsd());
        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(
                Ui.dp(this, 42), Ui.dp(this, 42), Gravity.RIGHT | Gravity.BOTTOM);
        infoParams.setMargins(0, 0, Ui.dp(this, 14), Ui.dp(this, 14));
        root.addView(info, infoParams);

        numberOverlay = Ui.text(this, "", 34, Ui.WHITE, true);
        numberOverlay.setGravity(Gravity.CENTER);
        numberOverlay.setPadding(Ui.dp(this, 20), Ui.dp(this, 12),
                Ui.dp(this, 20), Ui.dp(this, 12));
        numberOverlay.setBackground(Ui.rounded(this, 0xEE102D43, 17));
        FrameLayout.LayoutParams digitParams = new FrameLayout.LayoutParams(-2, -2,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        digitParams.setMargins(0, 0, Ui.dp(this, 34), 0);
        root.addView(numberOverlay, digitParams);
        numberOverlay.setVisibility(View.GONE);

        startPlayback();
        handler.postDelayed(saveTick, 5000);
        handler.postDelayed(updateEpg, 60000);
        handler.postDelayed(watchTick, PlaybackWatchdog.CHECK_INTERVAL_MS);
        updateNetworkOsd();
        showOsd();
    }

    /** Odtwarzacz jest odtwarzany wewnątrz tej samej aktywności przy zmianie kanału. */
    private void startPlayback() {
        ++playGeneration;
        vlcRecoveryAttempts = 0;
        exoRecoveryAttempts = 0;
        qualityCap = Integer.MAX_VALUE;
        lowBufferSamples = 0;
        userPaused = false;
        watchdog.reset(SystemClock.elapsedRealtime());
        StreamSpec stream;
        try { stream = resolveStream(); }
        catch (Exception error) {
            Log.w("SS-IPTV-Player", "Invalid stream", error);
            setPlaybackStatus("Nie można odczytać adresu strumienia");
            return;
        }
        String path = stream.url.split("\\?", 2)[0].toLowerCase(Locale.ROOT);
        adaptiveStream = path.endsWith(".m3u8") || path.endsWith(".mpd");
        if (!channel.vod) startVlc(stream);
        else startExo(stream);
    }

    private static final class StreamSpec {
        String url;
        final Map<String, String> headers = new HashMap<>();
    }

    private StreamSpec resolveStream() throws Exception {
        StreamSpec spec = new StreamSpec();
        spec.url = channel.url;
        if (spec.url.startsWith("xtream://")) {
            XtreamClient.Credentials creds = new SecureSettings(this).credentials();
            if (creds == null) throw new IllegalStateException("Brak danych serwera Xtream");
            spec.url = XtreamClient.streamUrl(creds, spec.url);
        }
        if (spec.url.contains("|")) {
            String[] segments = spec.url.split("\\|", 2);
            spec.url = segments[0];
            for (String pair : segments[1].split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && (kv[0].equalsIgnoreCase("User-Agent") ||
                        kv[0].equalsIgnoreCase("Referer")))
                    spec.headers.put(kv[0], kv[1]);
            }
        }
        return spec;
    }

    /** Silnik natywny VideoLAN jest domyślny dla telewizji, również dla kodeków audio TS. */
    private void startVlc(StreamSpec spec) {
        final long generation = ++playGeneration;
        releaseExo();
        releaseVlc();
        usingVlc = true;
        engineStartedAt = SystemClock.elapsedRealtime();
        watchdog.reset(engineStartedAt);
        lastVlcTrafficBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        playerView.setVisibility(View.GONE);
        vlcView.setVisibility(View.VISIBLE);
        try {
            if (libVlc == null) {
                ArrayList<String> options = new ArrayList<>();
                options.add("--network-caching=5000");
                options.add("--live-caching=5000");
                options.add("--file-caching=5000");
                libVlc = new LibVLC(getApplicationContext(), options);
                libVlc.setUserAgent("SS-IPTV/1.5 (Android TV)", "SS-IPTV/1.5");
            }
            org.videolan.libvlc.MediaPlayer nativePlayer =
                    new org.videolan.libvlc.MediaPlayer(libVlc);
            vlcPlayer = nativePlayer;
            nativePlayer.attachViews(vlcView, null, false, false);
            Media media = new Media(libVlc, Uri.parse(spec.url));
            // Czas bufora to cel, nie gwarancja 5 s dla krótkiego okna live.
            media.addOption(":network-caching=5000");
            media.addOption(":live-caching=5000");
            media.addOption(":http-reconnect=true");
            for (Map.Entry<String, String> entry : spec.headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase("User-Agent"))
                    media.addOption(":http-user-agent=" + entry.getValue());
                else if (entry.getKey().equalsIgnoreCase("Referer"))
                    media.addOption(":http-referrer=" + entry.getValue());
            }
            nativePlayer.setMedia(media);
            media.release();
            nativePlayer.setEventListener(event -> handler.post(() -> {
                if (generation != playGeneration || !usingVlc || leftScreen) return;
                long now = SystemClock.elapsedRealtime();
                switch (event.type) {
                    case org.videolan.libvlc.MediaPlayer.Event.Playing:
                        setPlaybackStatus("VLC · Odtwarzanie");
                        watchdog.heartbeat(now);
                        showVlcQuality(nativePlayer);
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.TimeChanged:
                        watchdog.observe(now, event.getTimeChanged());
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.Vout:
                        showVlcQuality(nativePlayer);
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.Opening:
                    case org.videolan.libvlc.MediaPlayer.Event.Buffering:
                        setPlaybackStatus("VLC · Buforowanie");
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.EncounteredError:
                        recoverPlayback("VLC zgłosił błąd strumienia");
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.EndReached:
                        recoverPlayback("Strumień TV został zakończony");
                        break;
                    default: break;
                }
            }));
            setPlaybackStatus("VLC · Łączenie");
            nativePlayer.play();
            root.requestFocus();
        } catch (Exception | LinkageError error) {
            Log.w("SS-IPTV-Player", "VLC unavailable, switching engine", error);
            startExo(spec);
        }
    }

    private void showVlcQuality(org.videolan.libvlc.MediaPlayer nativePlayer) {
        if (nativePlayer == null) return;
        org.videolan.libvlc.interfaces.IMedia.VideoTrack video =
                nativePlayer.getCurrentVideoTrack();
        if (video != null) showQuality(Math.min(video.width, video.height));
    }

    /** Media3 zapewnia natywne ABR dla HLS/DASH oraz drugą drogę dekodowania. */
    private void startExo(StreamSpec spec) {
        final long generation = ++playGeneration;
        releaseVlc();
        releaseExo();
        usingVlc = false;
        engineStartedAt = SystemClock.elapsedRealtime();
        watchdog.reset(engineStartedAt);
        vlcView.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        try {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent("SS-IPTV/1.5 Android TV")
                    .setConnectTimeoutMs(18000)
                    .setReadTimeoutMs(30000)
                    .setAllowCrossProtocolRedirects(true);
            if (!spec.headers.isEmpty()) http.setDefaultRequestProperties(spec.headers);
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15000, 60000, 2000, 5000)
                    .build();
            DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                    .setEnableDecoderFallback(true);
            ExoPlayer exo = new ExoPlayer.Builder(this)
                    .setRenderersFactory(renderers)
                    .setLoadControl(loadControl)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(
                            new DefaultDataSource.Factory(this, http)))
                    .build();
            player = exo;
            exo.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA).build(), true);
            playerView.setPlayer(exo);
            playerView.setUseController(channel.vod);
            playerView.requestFocus();
            MediaItem.Builder media = new MediaItem.Builder().setUri(spec.url);
            String path = spec.url.split("\\?", 2)[0].toLowerCase(Locale.ROOT);
            if (path.endsWith(".m3u8")) media.setMimeType(MimeTypes.APPLICATION_M3U8);
            else if (path.endsWith(".mpd")) media.setMimeType(MimeTypes.APPLICATION_MPD);
            else if (path.startsWith("rtsp://")) media.setMimeType(MimeTypes.APPLICATION_RTSP);
            exo.setMediaItem(media.build());
            if (qualityCap < Integer.MAX_VALUE) applyQualityCap();
            if (channel.vod && channel.playbackMs > 0) exo.seekTo(channel.playbackMs);
            exo.addListener(new Player.Listener() {
                @Override public void onVideoSizeChanged(VideoSize size) {
                    if (generation == playGeneration)
                        showQuality(Math.min(size.width, size.height));
                }
                @Override public void onTracksChanged(Tracks tracks) {
                    if (generation != playGeneration) return;
                    boolean audioFound = false;
                    boolean audioSupported = false;
                    for (Tracks.Group group : tracks.getGroups()) {
                        if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
                        audioFound = true;
                        if (group.isSupported()) audioSupported = true;
                    }
                    if (audioWarning != null) {
                        audioWarning.setText(audioFound && !audioSupported ?
                                "Brak dekodera audio · spróbuj VLC" : "");
                        audioWarning.setVisibility(audioFound && !audioSupported ?
                                View.VISIBLE : View.GONE);
                    }
                }
                @Override public void onPlaybackStateChanged(int state) {
                    if (generation != playGeneration) return;
                    if (state == Player.STATE_READY) {
                        watchdog.heartbeat(SystemClock.elapsedRealtime());
                        setPlaybackStatus("Media3 · Odtwarzanie");
                    } else if (state == Player.STATE_BUFFERING) {
                        setPlaybackStatus("Media3 · Buforowanie");
                    }
                    if (state == Player.STATE_ENDED && channel.vod && channel.id > 0)
                        storage.execute(() -> database.savePlayback(channel.id, 0, 0));
                }
                @Override public void onPlayerError(PlaybackException error) {
                    if (generation != playGeneration) return;
                    Log.w("SS-IPTV-Player", "Media3 " + error.getErrorCodeName());
                    recoverPlayback("Media3: " + error.getErrorCodeName());
                }
            });
            setPlaybackStatus("Media3 · Łączenie");
            exo.prepare();
            exo.play();
        } catch (Exception | LinkageError error) {
            Log.w("SS-IPTV-Player", "Media3 unavailable", error);
            setPlaybackStatus("Błąd uruchamiania odtwarzacza");
            showOsd();
        }
    }

    private void releaseExo() {
        if (playerView != null) playerView.setPlayer(null);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void releaseVlc() {
        if (vlcPlayer != null) {
            vlcPlayer.setEventListener(null);
            vlcPlayer.stop();
            vlcPlayer.detachViews();
            vlcPlayer.release();
            vlcPlayer = null;
        }
    }

    /** Co 30 s diagnozujemy postęp, a po 60 s bez postępu odtwarzamy ponownie. */
    private void inspectPlayback() {
        NetworkIndicator network = NetworkIndicator.read(this);
        if (!network.connected) {
            setPlaybackStatus("Brak sieci · oczekiwanie na połączenie");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (usingVlc && vlcPlayer != null) {
            long nativePosition = vlcPlayer.getTime();
            watchdog.observe(now, nativePosition);
            // Część dostawców MPEG-TS nie udostępnia zegara PTS (getTime()==0).
            // Wówczas napływ danych + aktywny dekoder stanowi pomocniczy sygnał,
            // inaczej sprawna stacja byłaby błędnie restartowana co minutę.
            long traffic = TrafficStats.getUidRxBytes(android.os.Process.myUid());
            if (nativePosition <= 0 && traffic >= 0 && lastVlcTrafficBytes >= 0 &&
                    traffic > lastVlcTrafficBytes + 8192 && vlcPlayer.isPlaying())
                watchdog.heartbeat(now);
            lastVlcTrafficBytes = traffic;
        }
        if (!usingVlc && player != null) {
            if (player.isPlaying()) watchdog.observe(now, player.getCurrentPosition());
            // Przy wielu wariantach w master HLS/DASH zmiana limitu odbywa się
            // w locie przez selektor ABR, bez przeładowania całej transmisji.
            if (adaptiveStream && player.isPlaying() &&
                    player.getTotalBufferedDuration() < 3500) lowBufferSamples++;
            else lowBufferSamples = 0;
            if (lowBufferSamples >= 2 && qualityCap > 1_000_000) {
                qualityCap = qualityCap == Integer.MAX_VALUE ? 4_000_000 :
                        Math.max(1_000_000, qualityCap / 2);
                applyQualityCap();
                setPlaybackStatus("Media3 · Adaptacja przepływności bez restartu");
                lowBufferSamples = 0;
            }
            if (adaptiveStream && qualityCap != Integer.MAX_VALUE &&
                    now - engineStartedAt >= 180_000 && player.isPlaying() &&
                    player.getTotalBufferedDuration() > 12000 && lowBufferSamples == 0) {
                qualityCap = Integer.MAX_VALUE;
                applyQualityCap();
                setPlaybackStatus("Media3 · Automatyczna najwyższa jakość");
            }
        }
        boolean paused = userPaused || leftScreen || channel.vod && player != null &&
                !player.getPlayWhenReady();
        if (watchdog.isStalled(now, network.connected, paused))
            recoverPlayback("Brak postępu odtwarzania przez 60 s");
    }

    private void recoverPlayback(String reason) {
        if (leftScreen || isFinishing() || isDestroyed()) return;
        if (!NetworkIndicator.read(this).connected) {
            setPlaybackStatus("Brak sieci · czekam na połączenie");
            return;
        }
        Log.w("SS-IPTV-Player", reason + " | engine=" + (usingVlc ? "VLC" : "Media3"));
        StreamSpec spec;
        try { spec = resolveStream(); }
        catch (Exception error) {
            setPlaybackStatus("Nieprawidłowy adres strumienia");
            return;
        }
        showOsd();
        if (usingVlc) {
            if (vlcRecoveryAttempts++ == 0) {
                setPlaybackStatus("VLC · Ponowne łączenie");
                startVlc(spec);
            } else {
                // Inny demultiplekser/dekoder często pomaga przy problematycznych TS.
                setPlaybackStatus("Zmiana odtwarzacza: Media3");
                startExo(spec);
            }
        } else {
            if (adaptiveStream && qualityCap > 1_000_000) {
                qualityCap = qualityCap == Integer.MAX_VALUE ? 4_000_000 :
                        Math.max(1_000_000, qualityCap / 2);
                applyQualityCap();
                setPlaybackStatus("Media3 · Niższy wariant HLS/DASH, jeśli dostępny");
                watchdog.reset(SystemClock.elapsedRealtime());
                if (player != null) { player.prepare(); player.play(); }
            } else if (exoRecoveryAttempts++ < 2) {
                setPlaybackStatus("Media3 · Ponowne łączenie");
                startExo(spec);
            } else {
                // Ograniczony cykl z krótkim opóźnieniem; nie blokuje interfejsu.
                setPlaybackStatus("Ponowna próba VLC za 5 s");
                long generation = playGeneration;
                handler.postDelayed(() -> {
                    if (generation == playGeneration && !leftScreen) {
                        vlcRecoveryAttempts = 0;
                        startVlc(spec);
                    }
                }, 5000);
            }
        }
    }

    private void applyQualityCap() {
        if (player == null || !adaptiveStream) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setMaxVideoBitrate(qualityCap).build());
    }

    private void setPlaybackStatus(String status) {
        if (bufferingText != null) {
            bufferingText.setText(status);
            bufferingText.setVisibility(View.VISIBLE);
        }
    }

    private void updateNetworkOsd() {
        if (networkText == null || networkIcon == null) return;
        NetworkIndicator status = NetworkIndicator.read(this);
        networkIcon.setImageResource(status.icon);
        networkText.setText(status.label);
        networkText.setTextColor(status.connected ? Ui.SECONDARY : 0xFFFF8297);
    }

    private void addOsd(FrameLayout root) {
        osd = Ui.column(this);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF30B1420, 0xDE101C2A, 0xB20F1C2C});
        osd.setBackground(background);
        osd.setPadding(Ui.dp(this, 24), Ui.dp(this, 10),
                Ui.dp(this, 24), Ui.dp(this, 9));

        LinearLayout top = Ui.row(this);
        TextView back = Ui.text(this, "‹  Powrót", 11, Ui.SECONDARY, true);
        back.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        back.setOnClickListener(v -> finish());
        Ui.tvFocus(back);
        top.addView(back);
        TextView hint = Ui.text(this, channel.vod ? "  VIDEO ON DEMAND" : "  NA ŻYWO",
                11, Ui.SECONDARY, true);
        top.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        bufferingText = Ui.text(this, "", 10, Ui.ACCENT, false);
        bufferingText.setPadding(0, 0, Ui.dp(this, 14), 0);
        top.addView(bufferingText);
        networkIcon = new ImageView(this);
        networkIcon.setContentDescription("Stan połączenia sieciowego");
        top.addView(networkIcon, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        networkText = Ui.text(this, "Sprawdzam sieć", 10, Ui.SECONDARY, false);
        networkText.setPadding(Ui.dp(this, 5), 0, Ui.dp(this, 10), 0);
        top.addView(networkText);
        audioWarning = Ui.text(this, "", 11, 0xFFFFC57A, true);
        audioWarning.setPadding(0, 0, Ui.dp(this, 12), 0);
        audioWarning.setVisibility(View.GONE);
        top.addView(audioWarning);
        quality = Ui.text(this, "AUTO", 11, Ui.WHITE, true);
        quality.setGravity(Gravity.CENTER);
        quality.setPadding(Ui.dp(this, 10), Ui.dp(this, 5),
                Ui.dp(this, 10), Ui.dp(this, 5));
        quality.setBackground(Ui.rounded(this, 0xFF526175, 8));
        top.addView(quality);
        osd.addView(top);

        LinearLayout details = Ui.row(this);
        details.setPadding(0, Ui.dp(this, 7), 0, 0);
        FrameLayout logobox = new FrameLayout(this);
        logobox.setBackground(Ui.rounded(this, Ui.SURFACE, 13));
        initialsText = Ui.text(this, channel.name.substring(0,
                Math.min(2, channel.name.length())).toUpperCase(Locale.ROOT),
                15, Ui.ACCENT, true);
        initialsText.setGravity(Gravity.CENTER);
        logobox.addView(initialsText, new FrameLayout.LayoutParams(-1, -1));
        osdLogo = new ImageView(this);
        osdLogo.setPadding(Ui.dp(this, 4), Ui.dp(this, 4),
                Ui.dp(this, 4), Ui.dp(this, 4));
        osdLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logobox.addView(osdLogo, new FrameLayout.LayoutParams(-1, -1));
        LogoLoader.load(osdLogo, channel.logo);
        details.addView(logobox, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));

        LinearLayout labels = Ui.column(this);
        labels.setPadding(Ui.dp(this, 13), 0, Ui.dp(this, 8), 0);
        channelNameText = Ui.text(this, channel.name, 18, Ui.WHITE, true);
        channelNameText.setMaxLines(2);
        channelNameText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        channelNameText.setContentDescription("Nazwa kanału: " + channel.name);
        labels.addView(channelNameText);
        nowText = Ui.text(this, "", 19, Ui.ACCENT, true);
        nowText.setMaxLines(2);
        nowText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(nowText);
        nextText = Ui.text(this, "", 12, Ui.SECONDARY, false);
        nextText.setMaxLines(1);
        nextText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(nextText);
        updateProgramLabels();
        progress = Ui.text(this, "", 11, Ui.SECONDARY, false);
        if (channel.vod) labels.addView(progress);
        details.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        heart = new ImageView(this);
        heart.setImageResource(R.drawable.ic_nav_heart);
        heart.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12));
        heart.setImageTintList(ColorStateList.valueOf(channel.favorite ? 0xFFFF728D : Ui.WHITE));
        heart.setContentDescription(channel.favorite ? "Usuń z ulubionych" : "Dodaj do ulubionych");
        heart.setOnClickListener(v -> toggleFavorite());
        Ui.tvFocus(heart);
        details.addView(heart, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 48)));
        osd.addView(details);

        expandedDescription = Ui.text(this, "", 13, Ui.WHITE, false);
        expandedDescription.setPadding(Ui.dp(this, 10), Ui.dp(this, 10),
                Ui.dp(this, 10), Ui.dp(this, 10));
        expandedDescription.setBackground(Ui.rounded(this, 0xEC143A51, 11));
        descriptionScroll = new ScrollView(this);
        descriptionScroll.setFillViewport(false);
        descriptionScroll.setFocusable(true);
        descriptionScroll.addView(expandedDescription);
        descriptionScroll.setVisibility(View.GONE);
        osd.addView(descriptionScroll, Ui.margins(this, -1, 140, 0, 9, 0, 3));

        LinearLayout actionsLine = Ui.row(this);
        actionsLine.setPadding(0, Ui.dp(this, 7), 0, 0);
        TextView instruction = Ui.text(this,
                channel.vod ? "OK: sterowanie OSD   |   Pauza i przewijanie" :
                        "Góra/dół: kanały   |   Lewo/OK: sterowanie OSD", 10, Ui.MUTED, false);
        actionsLine.addView(instruction, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout actions = Ui.row(this);
        firstOsdAction = addColorAction(actions, "Ulubione", R.drawable.ic_nav_heart, 0xFFDD5165, v -> toggleFavorite());
        addColorAction(actions, "OSD", R.drawable.ic_player_info, 0xFF35AD83, v -> toggleOsd());
        addColorAction(actions, "Opis", R.drawable.ic_nav_epg, 0xFFE2B456, v -> toggleDescription());
        addColorAction(actions, "Ustawienia", R.drawable.ic_nav_settings, 0xFF5792D4, v -> toggleSettings());
        actionsLine.addView(actions);
        osd.addView(actionsLine);

        FrameLayout.LayoutParams osdParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(osd, osdParams);
    }

    private void showQuality(int height) {
        if (quality == null) return;
        String label;
        int color;
        if (height >= 2160) { label = "4K"; color = 0xFF7A48BF; }
        else if (height >= 1080) { label = "FULL HD"; color = 0xFF166AB0; }
        else if (height >= 720) { label = "HD"; color = 0xFF087D84; }
        else if (height > 0) { label = "SD"; color = 0xFF526175; }
        else { label = "AUTO"; color = 0xFF526175; }
        quality.setText(label);
        quality.setBackground(Ui.rounded(this, color, 8));
    }

    private void showOsd() {
        if (osd == null) return;
        osd.setVisibility(View.VISIBLE);
        updateProgressLabel();
        handler.removeCallbacks(hideOsd);
        if (!descriptionOpen && !osdNavigationMode && settingsPanel == null)
            handler.postDelayed(hideOsd, 6800);
    }

    private void updateProgramLabels() {
        if (nowText == null || nextText == null || channel == null) return;
        if (channel.vod) {
            nowText.setText(channel.isEpisode() ? "Odcinek serialu" : "Film VOD");
            nextText.setText("Kontynuuj od miejsca zakończenia");
            return;
        }
        ChannelDatabase.Guide guide = database.readGuides().get(channel.id);
        long now = System.currentTimeMillis();
        boolean currentAvailable = guide != null && guide.end > now &&
                guide.current != null && !guide.current.trim().isEmpty();
        nowText.setText(currentAvailable ? "Teraz  ·  " + guide.current :
                "Teraz  ·  Oczekiwanie na EPG");
        boolean nextAvailable = guide != null && guide.nextStart > now &&
                guide.next != null && !guide.next.trim().isEmpty();
        nextText.setText(nextAvailable ? "Następnie  ·  " + guide.next :
                "Następnie  ·  Brak danych");
    }

    private void updateChannelOsd() {
        if (channelNameText == null) return;
        channelNameText.setText(channel.name);
        channelNameText.setContentDescription("Nazwa kanału: " + channel.name);
        initialsText.setText(channel.name.substring(0,
                Math.min(2, channel.name.length())).toUpperCase(Locale.ROOT));
        LogoLoader.load(osdLogo, channel.logo);
        heart.setImageTintList(ColorStateList.valueOf(channel.favorite ? 0xFFFF728D : Ui.WHITE));
        showQuality(0);
        updateProgramLabels();
        osdNavigationMode = false;
        focusPlayback();
        showOsd();
    }

    /** Tryb nawigacji po OSD uruchamiamy tylko świadomie — nigdy automatycznie. */
    private void enterOsdNavigation() {
        osdNavigationMode = true;
        showOsd();
        if (firstOsdAction != null) firstOsdAction.requestFocus();
    }

    private void exitOsdNavigation() {
        osdNavigationMode = false;
        focusPlayback();
        showOsd();
    }

    private void focusPlayback() {
        if (usingVlc && root != null) root.requestFocus();
        else if (playerView != null) playerView.requestFocus();
    }

    private View addColorAction(LinearLayout actions, String label, int vectorIcon, int accent,
                                View.OnClickListener listener) {
        LinearLayout cell = Ui.row(this);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        cell.setBackground(Ui.rounded(this, 0xB21C2A38, 8));
        ImageView symbol = new ImageView(this);
        symbol.setImageResource(vectorIcon);
        symbol.setImageTintList(ColorStateList.valueOf(accent));
        cell.addView(symbol, new LinearLayout.LayoutParams(Ui.dp(this, 15), Ui.dp(this, 15)));
        TextView button = Ui.text(this, label, 10, Ui.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(this, 5), 0, 0, 0);
        cell.addView(button);
        cell.setOnClickListener(listener);
        Ui.tvFocus(cell);
        actions.addView(cell, Ui.margins(this, -2, 31, 3, 0, 0, 0));
        return cell;
    }

    private void toggleFavorite() {
        if (channel.id <= 0) {
            Toast.makeText(this, "Ulubione działają dla kanałów zapisanych na liście.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        channel.favorite = !channel.favorite;
        boolean value = channel.favorite;
        heart.setImageTintList(ColorStateList.valueOf(value ? 0xFFFF728D : Ui.WHITE));
        heart.setContentDescription(value ? "Usuń z ulubionych" : "Dodaj do ulubionych");
        storage.execute(() -> database.setFavorite(channel.id, value));
        showOsd();
    }

    private void toggleOsd() {
        if (osd == null) return;
        if (osd.getVisibility() == View.VISIBLE) {
            osd.setVisibility(View.GONE);
            handler.removeCallbacks(hideOsd);
            osdNavigationMode = false;
            focusPlayback();
        } else showOsd();
    }

    private void toggleDescription() {
        descriptionOpen = !descriptionOpen;
        if (expandedDescription != null) {
            if (descriptionOpen) {
                ChannelDatabase.Guide guide = database.readGuides().get(channel.id);
                String title = channel.vod ? channel.name : guide != null ? guide.current : channel.name;
                String body = channel.vod ? channel.description :
                        guide != null ? guide.description : "";
                if (body == null || body.trim().isEmpty()) body =
                        "Dostawca nie przekazał szczegółowego opisu tej pozycji.";
                expandedDescription.setText(title + "\n\n" + body);
                descriptionScroll.setVisibility(View.VISIBLE);
                osdNavigationMode = true;
                descriptionScroll.requestFocus();
            } else {
                descriptionScroll.setVisibility(View.GONE);
                osdNavigationMode = true;
                if (firstOsdAction != null) firstOsdAction.requestFocus();
            }
        }
        showOsd();
    }

    /** Boczny panel działa także pilotem bez niebieskiego klawisza. */
    private void toggleSettings() {
        if (settingsPanel != null) {
            root.removeView(settingsPanel);
            settingsPanel = null;
            osdNavigationMode = false;
            focusPlayback();
            showOsd();
            return;
        }
        settingsPanel = new ScrollView(this);
        settingsPanel.setFillViewport(false);
        settingsPanel.setVerticalScrollBarEnabled(false);
        LinearLayout settingsBody = Ui.column(this);
        settingsBody.setPadding(Ui.dp(this, 17), Ui.dp(this, 17),
                Ui.dp(this, 17), Ui.dp(this, 17));
        settingsPanel.addView(settingsBody);
        settingsPanel.setBackground(Ui.outlined(this, 0xF20D263A, Ui.ACCENT, 18));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.min(Ui.dp(this, 350),
                        getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 36)),
                Math.min(Ui.dp(this, 520),
                        getResources().getDisplayMetrics().heightPixels - Ui.dp(this, 42)),
                Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        params.setMargins(Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20));
        root.addView(settingsPanel, params);
        settingsBody.addView(Ui.text(this, "Ustawienia odtwarzania", 19, Ui.WHITE, true));
        settingsBody.addView(Ui.text(this, "Wybierz listę | EPG | sterowanie",
                12, Ui.SECONDARY, false), Ui.margins(this, -1, -2, 0, 5, 0, 12));
        SecureSettings settings = new SecureSettings(this);
        settingsBody.addView(Ui.text(this, settings.epgStatus(), 12, Ui.ACCENT, false));
        settingsBody.addView(Ui.text(this, "Odtwarzacz: " + (usingVlc ? "VLC / VideoLAN" : "Media3 / ExoPlayer"),
                12, Ui.SECONDARY, true), Ui.margins(this, -1, -2, 0, 12, 0, 5));
        settingsBody.addView(Ui.button(this, "Przełącz silnik odtwarzania", false,
                v -> {
                    StreamSpec spec;
                    try { spec = resolveStream(); }
                    catch (Exception error) { return; }
                    if (usingVlc) startExo(spec);
                    else startVlc(spec);
                    toggleSettings();
                }), Ui.margins(this, -1, -2, 0, 5, 0, 5));
        settingsBody.addView(Ui.button(this, "Wybierz ścieżkę dźwięku", false,
                v -> showAudioTracks()), Ui.margins(this, -1, -2, 0, 9, 0, 5));
        String active = settings.activeSource();
        settingsBody.addView(Ui.button(this, active.isEmpty() ?
                "Wszystkie listy (aktywne)" : "Wszystkie listy", active.isEmpty(), v -> {
            settings.setActiveSource("");
            cachedLive = liveChannels();
            toggleSettings();
            toggleSettings();
        }), Ui.margins(this, -1, -2, 0, 13, 0, 5));
        for (ChannelDatabase.Playlist source : database.playlists()) {
            boolean selected = source.source.equals(active);
            settingsBody.addView(Ui.button(this, (selected ? "Aktywna: " : "") +
                    source.title, selected, v -> {
                settings.setActiveSource(source.source);
                cachedLive = liveChannels();
                toggleSettings();
                toggleSettings();
            }), Ui.margins(this, -1, -2, 0, 0, 0, 5));
        }
        settingsBody.addView(Ui.button(this, "Zamknij", false, v -> toggleSettings()),
                Ui.margins(this, -1, -2, 0, 12, 0, 0));
        settingsBody.getChildAt(4).requestFocus();
        showOsd();
    }

    /** Wybór alternatywnej ścieżki dźwiękowej, gdy pierwsza w strumieniu milczy. */
    private void showAudioTracks() {
        if (usingVlc && vlcPlayer != null) {
            org.videolan.libvlc.MediaPlayer.TrackDescription[] tracks = vlcPlayer.getAudioTracks();
            if (tracks == null || tracks.length == 0) {
                new AlertDialog.Builder(this).setTitle("Ścieżki audio VLC")
                        .setMessage("Strumień nie zgłosił jeszcze ścieżek audio. Spróbuj ponownie po załadowaniu kanału.")
                        .setPositiveButton("OK", null).show();
                return;
            }
            String[] labels = new String[tracks.length];
            int selected = vlcPlayer.getAudioTrack();
            for (int i = 0; i < tracks.length; i++)
                labels[i] = (tracks[i].id == selected ? "Wybrana: " : "") + tracks[i].name;
            new AlertDialog.Builder(this).setTitle("Ścieżki audio VLC")
                    .setItems(labels, (dialog, which) -> {
                        if (vlcPlayer != null) vlcPlayer.setAudioTrack(tracks[which].id);
                        showOsd();
                    }).setNegativeButton("Anuluj", null).show();
            return;
        }
        if (player == null) return;
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<TrackSelectionOverride> choices = new ArrayList<>();
        labels.add("Automatyczny wybór dźwięku");
        boolean audioPresent = false;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            audioPresent = true;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format f = group.getTrackFormat(i);
                String language = f.language == null || f.language.isEmpty() ?
                        "bez języka" : f.language;
                String codec = f.sampleMimeType == null ? "audio" : f.sampleMimeType;
                labels.add((group.isTrackSelected(i) ? "Wybrana: " : "") +
                        language + (f.label == null ? "" : " · " + f.label) + " · " + codec);
                choices.add(new TrackSelectionOverride(group.getMediaTrackGroup(), i));
            }
        }
        if (choices.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Dźwięk")
                    .setMessage(audioPresent ?
                            "Urządzenie nie obsługuje kodeka audio tego kanału. " +
                            "Sprawdź inny strumień lub dekoder systemowy." :
                            "Ten strumień nie zgłosił jeszcze ścieżek audio. " +
                            "Poczekaj na załadowanie kanału i spróbuj ponownie.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Wybierz ścieżkę dźwięku")
                .setItems(labels.toArray(new String[0]), (dialog, selection) -> {
                    if (player == null) return;
                    TrackSelectionParameters.Builder params =
                            player.getTrackSelectionParameters().buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO);
                    if (selection > 0)
                        params.setOverrideForType(choices.get(selection - 1));
                    player.setTrackSelectionParameters(params.build());
                    showOsd();
                }).setNegativeButton("Anuluj", null).show();
    }

    private List<Channel> liveChannels() {
        List<Channel> result = new ArrayList<>();
        String active = new SecureSettings(this).activeSource();
        for (Channel item : database.allChannels()) {
            if (!item.vod && !item.isSeries() && !item.isEpisode() &&
                    (active.isEmpty() || active.equals(item.source))) result.add(item);
        }
        return result;
    }

    private void zap(int direction) {
        if (channel.vod) return;
        List<Channel> tv = cachedLive;
        if (tv.isEmpty()) return;
        int current = -1;
        for (int i = 0; i < tv.size(); i++) if (tv.get(i).id == channel.id) {
            current = i;
            break;
        }
        int next = (current + direction + tv.size()) % tv.size();
        switchTo(tv.get(next));
    }

    private void switchTo(Channel target) {
        if (target.id == channel.id) { showOsd(); return; }
        if (settingsPanel != null) toggleSettings();
        descriptionOpen = false;
        if (descriptionScroll != null) descriptionScroll.setVisibility(View.GONE);
        channel = target;
        numberInput.setLength(0);
        numberOverlay.setVisibility(View.GONE);
        if (channel.id > 0) storage.execute(() -> database.markWatched(channel.id));
        updateChannelOsd();
        startPlayback();
    }

    private void typeDigit(int digit) {
        if (channel.vod) return;
        if (numberInput.length() >= 3) numberInput.setLength(0);
        numberInput.append(digit);
        numberOverlay.setText(numberInput.toString());
        numberOverlay.setVisibility(View.VISIBLE);
        handler.removeCallbacks(commitNumber);
        if (numberInput.length() == 3) handler.postDelayed(commitNumber, 300);
        else handler.postDelayed(commitNumber, 1600);
    }

    /** Tylko jawnie otwarty fokus OSD przejmuje strzałki. W pozostałych sytuacjach ↑↓ to kanały. */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP && key == consumedRemoteKey) {
            consumedRemoteKey = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (key == consumedRemoteKey && event.getRepeatCount() > 0) return true;
            if (event.getRepeatCount() == 0 && handleRemoteKey(key)) {
                consumedRemoteKey = key;
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void commitChannelNumber() {
        if (numberInput.length() == 0) return;
        int ordinal;
        try { ordinal = Integer.parseInt(numberInput.toString()); }
        catch (NumberFormatException error) { ordinal = 0; }
        numberInput.setLength(0);
        numberOverlay.setVisibility(View.GONE);
        List<Channel> channels = cachedLive;
        if (ordinal > 0 && ordinal <= channels.size()) switchTo(channels.get(ordinal - 1));
        else Toast.makeText(this, "Brak kanału nr " + ordinal, Toast.LENGTH_SHORT).show();
    }

    private boolean handleRemoteKey(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            typeDigit(keyCode - KeyEvent.KEYCODE_0);
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            typeDigit(keyCode - KeyEvent.KEYCODE_NUMPAD_0);
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_PROG_RED: toggleFavorite(); return true;
            case KeyEvent.KEYCODE_PROG_GREEN: toggleOsd(); return true;
            case KeyEvent.KEYCODE_PROG_YELLOW: toggleDescription(); return true;
            case KeyEvent.KEYCODE_PROG_BLUE:
            case KeyEvent.KEYCODE_MENU: toggleSettings(); return true;
            case KeyEvent.KEYCODE_INFO: toggleOsd(); return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: zap(1); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: zap(-1); return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (PlayerRemoteRules.shouldZapWithArrows(channel.vod, settingsPanel != null,
                        osdNavigationMode, descriptionOpen)) { zap(1); return true; }
                return false;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (PlayerRemoteRules.shouldZapWithArrows(channel.vod, settingsPanel != null,
                        osdNavigationMode, descriptionOpen)) { zap(-1); return true; }
                return false;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (numberInput.length() > 0) { commitChannelNumber(); return true; }
                if (!osdNavigationMode && settingsPanel == null && !channel.vod) {
                    enterOsdNavigation(); return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!osdNavigationMode && settingsPanel == null) {
                    enterOsdNavigation(); return true;
                }
                return false;
            default: return false;
        }
    }

    @Override public void onBackPressed() {
        if (settingsPanel != null) toggleSettings();
        else if (descriptionOpen) toggleDescription();
        else if (osdNavigationMode) exitOsdNavigation();
        else if (osd != null && osd.getVisibility() == View.VISIBLE) toggleOsd();
        else super.onBackPressed();
    }

    private void updateProgressLabel() {
        if (!channel.vod || progress == null) return;
        long pos = player != null ? player.getCurrentPosition() : channel.playbackMs;
        long dur = player != null ? player.getDuration() : channel.durationMs;
        progress.setText("Postęp: " + formatMinutes(pos) +
                (dur > 0 ? " / " + formatMinutes(dur) : ""));
    }

    private String formatMinutes(long millis) {
        long minutes = Math.max(0, millis) / 60000;
        return (minutes / 60) + ":" + String.format(Locale.ROOT, "%02d", minutes % 60);
    }

    private void saveProgress() {
        if (!channel.vod || channel.id <= 0 || player == null) return;
        long pos = Math.max(0, player.getCurrentPosition());
        long dur = Math.max(0, player.getDuration());
        if (dur > 0 && pos > dur - 15000) pos = 0;
        final long lastPosition = pos;
        storage.execute(() -> database.savePlayback(channel.id, lastPosition, dur));
        updateProgressLabel();
    }

    @Override protected void onStart() {
        super.onStart();
        leftScreen = false;
        watchdog.reset(SystemClock.elapsedRealtime());
        if (player != null) player.play();
        if (vlcPlayer != null && !vlcPlayer.isPlaying()) vlcPlayer.play();
    }

    @Override protected void onStop() {
        leftScreen = true;
        saveProgress();
        if (player != null) player.pause();
        if (vlcPlayer != null && vlcPlayer.isPlaying()) vlcPlayer.pause();
        super.onStop();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        saveProgress();
        releaseExo();
        releaseVlc();
        if (libVlc != null) {
            libVlc.release();
            libVlc = null;
        }
        storage.shutdown();
        super.onDestroy();
    }
}
