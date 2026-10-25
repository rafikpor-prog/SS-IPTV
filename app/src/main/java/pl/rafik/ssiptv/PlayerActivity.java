package pl.rafik.ssiptv;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.TrafficStats;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaPlayer;
import android.media.MediaFormat;
import android.media.TimedText;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.MediaController;
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
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.AspectRatioFrameLayout;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.HashMap;
import java.util.Collections;
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
    private FullscreenVideoView platformView;
    private MediaPlayer platformPlayer;
    private Player.Listener exoListener;
    private DefaultHttpDataSource.Factory httpFactory;
    private LibVLC libVlc;
    private org.videolan.libvlc.MediaPlayer vlcPlayer;
    private VLCVideoLayout vlcView;
    private boolean usingVlc;
    private boolean usingPlatform;
    private boolean leftScreen;
    private boolean userPaused;
    private int vlcRecoveryAttempts;
    private int exoRecoveryAttempts;
    private int qualityCap = Integer.MAX_VALUE;
    private boolean adaptiveStream;
    private int lowBufferSamples;
    private long engineStartedAt;
    private long lastVlcTrafficBytes = -1;
    private boolean firstFrameReady;
    private boolean hasVlcVideoOutput;
    private int engineSwaps;
    private int startupRetries;
    private boolean retryScheduled;
    private String selectedEngine = "android";
    private String activeEngine = "android";
    private boolean codecFallbackAttempted;
    private boolean unavailablePending;
    private TextView subtitleText;
    private long lastPlaybackEventAt;
    private long lastSamplePosition = -1;
    private long lastSampleTraffic = -1;
    private FrameLayout loadingOverlay;
    private ImageView loadingSpinner;
    private TextView loadingMessage;
    private TextView loadingChannel;
    private Runnable pendingZap;
    private long lastCatalogId;
    private final PlaybackWatchdog watchdog = new PlaybackWatchdog();
    private Channel channel;
    private ChannelDatabase database;
    private volatile Map<Long, ChannelDatabase.Guide> epgSnapshot = Collections.emptyMap();
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
            if (!isFinishing()) refreshEpgSnapshot();
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
    private final Runnable loadingTick = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (!leftScreen && channel != null) {
                long now = SystemClock.elapsedRealtime();
                if (!firstFrameReady && !retryScheduled) {
                    long waitSeconds = Math.max(0, now - engineStartedAt) / 1000;
                    if (loadingMessage != null && loadingOverlay.getVisibility() == View.VISIBLE)
                        loadingMessage.setText(engineName() +
                                " · Łączenie z kanałem · " + waitSeconds + " s");
                    if (waitSeconds >= (channel.vod ? 45 : 16))
                        recoverPlayback("Przekroczono czas oczekiwania na obraz");
                } else if (firstFrameReady) {
                    sampleQuickProgress(now);
                    boolean paused = userPaused || channel.vod && !usingPlatform && !usingVlc &&
                            player != null && !player.getPlayWhenReady();
                    if (!paused && now - lastPlaybackEventAt > (channel.vod ? 15_000 : 7_000)) {
                        showLoading(channel.vod ? "Oczekiwanie na dalszą część filmu…" :
                                "Oczekiwanie na kolejne dane kanału…");
                        setPlaybackStatus("Przerwa w transmisji · wznawianie");
                        if (now - lastPlaybackEventAt > (channel.vod ? 45_000 : 18_000))
                            recoverPlayback("Brak postępu odtwarzania");
                    }
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    /** Domyślne VideoView pomniejsza obraz SD 4:3. Ten wariant wypełnia powierzchnię
     * bez przycinania kadru (na ekranie 16:9 obraz 4:3 może być rozciągnięty). */
    private static final class FullscreenVideoView extends VideoView {
        private boolean fillScreen;

        FullscreenVideoView(android.content.Context context) { super(context); }

        void setFillScreen(boolean fill) {
            if (fillScreen != fill) {
                fillScreen = fill;
                requestLayout();
            }
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (fillScreen) {
                setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec),
                        View.MeasureSpec.getSize(heightMeasureSpec));
            } else super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

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
        platformView = new FullscreenVideoView(this);
        platformView.setVisibility(View.GONE);
        platformView.setFocusable(false);
        platformView.setFocusableInTouchMode(false);
        root.addView(platformView, new FrameLayout.LayoutParams(-1, -1));
        playerView = new PlayerView(this);
        // W TV nie pokazujemy panelu Media3 nad OSD i nie pozwalamy mu przechwytywać ↑/↓.
        // W VOD natywny pasek pauzy, przewijania i wznowienia pozostaje dostępny.
        playerView.setUseController(channel.vod);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setFocusable(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        playerView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) showOsd();
            return false;
        });
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        vlcView = new VLCVideoLayout(this);
        vlcView.setVisibility(View.GONE);
        vlcView.setFocusable(false);
        root.addView(vlcView, new FrameLayout.LayoutParams(-1, -1));

        subtitleText = Ui.text(this, "", 21, Ui.WHITE, true);
        subtitleText.setGravity(Gravity.CENTER);
        subtitleText.setShadowLayer(4f, 1f, 2f, Color.BLACK);
        subtitleText.setPadding(Ui.dp(this, 26), Ui.dp(this, 9),
                Ui.dp(this, 26), Ui.dp(this, 9));
        FrameLayout.LayoutParams subtitles = new FrameLayout.LayoutParams(-1, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        subtitles.bottomMargin = Ui.dp(this, 32);
        root.addView(subtitleText, subtitles);

        LogoLoader.reduceMemoryForPlayback();
        addLoadingIndicator(root);
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
        refreshEpgSnapshot();
        handler.postDelayed(watchTick, PlaybackWatchdog.CHECK_INTERVAL_MS);
        handler.postDelayed(loadingTick, 1000);
        updateNetworkOsd();
        showOsd();
    }

    /** Odtwarzacz jest odtwarzany wewnątrz tej samej aktywności przy zmianie kanału. */
    private void startPlayback() {
        ++playGeneration;
        vlcRecoveryAttempts = 0;
        exoRecoveryAttempts = 0;
        engineSwaps = 0;
        startupRetries = 0;
        retryScheduled = false;
        unavailablePending = false;
        codecFallbackAttempted = false;
        if (subtitleText != null) subtitleText.setText("");
        if (audioWarning != null) {
            audioWarning.setText("");
            audioWarning.setVisibility(View.GONE);
        }
        firstFrameReady = false;
        hasVlcVideoOutput = false;
        lastSamplePosition = -1;
        lastSampleTraffic = -1;
        qualityCap = Integer.MAX_VALUE;
        lowBufferSamples = 0;
        userPaused = false;
        watchdog.reset(SystemClock.elapsedRealtime());
        StreamSpec stream;
        try { stream = resolveStream(); }
        catch (Exception error) {
            Log.w("SS-IPTV-Player", "Invalid stream", error);
            showSourceUnavailable();
            return;
        }
        String path = stream.url.split("\\?", 2)[0].toLowerCase(Locale.ROOT);
        adaptiveStream = path.endsWith(".m3u8") || path.endsWith(".mpd");
        selectedEngine = new SecureSettings(this).playbackEngine();
        activeEngine = selectedEngine;
        startSelectedEngine(stream);
    }

    /** W awarii sieci i buforowania ponawiamy tylko wybrany przez użytkownika silnik. */
    private void startSelectedEngine(StreamSpec stream) {
        switch (activeEngine) {
            case "vlc": startVlc(stream); break;
            case "media3": startExo(stream); break;
            default: startPlatform(stream); break;
        }
    }

    /** Jednorazowe przejście na bibliotekę z dodatkowymi kodekami WYŁĄCZNIE
     * po zdiagnozowaniu audio; awaria sieci nadal korzysta z tego samego silnika. */
    private void fallbackForAudioCodec(String codec) {
        if (leftScreen || codecFallbackAttempted || usingVlc) return;
        codecFallbackAttempted = true;
        activeEngine = "vlc";
        if (channel.vod) rememberVodPosition();
        if (audioWarning != null) {
            audioWarning.setText("Kodek " + codec + " · dekodowanie VLC");
            audioWarning.setVisibility(View.VISIBLE);
        }
        showLoading("Dostosowuję dekoder audio · VLC");
        long generation = playGeneration;
        handler.post(() -> {
            if (generation != playGeneration || leftScreen) return;
            try { startVlc(resolveStream()); }
            catch (Exception e) { recoverPlayback("Nie można włączyć dekodera audio"); }
        });
    }

    private boolean nativeAudioUnsupported(MediaPlayer nativePlayer) {
        try {
            int knownAudio = 0;
            MediaCodecList codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            MediaPlayer.TrackInfo[] tracks = nativePlayer.getTrackInfo();
            for (int i = 0; i < tracks.length; i++) {
                MediaPlayer.TrackInfo info = tracks[i];
                if (info.getTrackType() != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) continue;
                MediaFormat format = info.getFormat();
                if (format == null || !format.containsKey(MediaFormat.KEY_MIME)) return false;
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("audio/")) return false;
                knownAudio++;
                if (codecs.findDecoderForFormat(format) != null) {
                    // Jeśli system nie wybrał żadnej ścieżki, wskaż pierwszą
                    // obsługiwaną przez MediaCodec, zamiast odtwarzać niemy obraz.
                    try {
                        if (nativePlayer.getSelectedTrack(
                                MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) < 0)
                            nativePlayer.selectTrack(i);
                    } catch (RuntimeException ignored) { }
                    return false;
                }
            }
            return knownAudio > 0;
        } catch (Exception ignored) { return false; }
    }

    private String engineName() {
        return usingPlatform ? "Android" : usingVlc ? "VLC" : "Media3";
    }

    private static boolean hasNativeHardwareDecoder() {
        try {
            for (MediaCodecInfo info : new MediaCodecList(
                    MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) continue;
                boolean hardware = android.os.Build.VERSION.SDK_INT >= 29 ?
                        info.isHardwareAccelerated() :
                        !info.getName().startsWith("OMX.google.") &&
                        !info.getName().startsWith("c2.android.");
                if (!hardware) continue;
                for (String mime : info.getSupportedTypes()) {
                    if ("video/avc".equalsIgnoreCase(mime) ||
                            "video/hevc".equalsIgnoreCase(mime)) return true;
                }
            }
        } catch (RuntimeException ignored) { }
        return false;
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

    /** Android MediaPlayer/MediaCodec: pierwszy silnik, jeśli sprzęt udostępnia dekoder. */
    private void startPlatform(StreamSpec spec) {
        final long generation = ++playGeneration;
        releaseExo();
        releaseVlc();
        if (libVlc != null) { libVlc.release(); libVlc = null; }
        platformPlayer = null;
        usingPlatform = true;
        usingVlc = false;
        firstFrameReady = false;
        lastSamplePosition = -1;
        lastSampleTraffic = -1;
        engineStartedAt = SystemClock.elapsedRealtime();
        watchdog.reset(engineStartedAt);
        playerView.setVisibility(View.GONE);
        vlcView.setVisibility(View.GONE);
        platformView.setVisibility(View.VISIBLE);
        platformView.setOnPreparedListener(nativePlayer -> {
            if (generation != playGeneration || leftScreen) return;
            platformPlayer = nativePlayer;
            if (nativeAudioUnsupported(nativePlayer)) {
                fallbackForAudioCodec("nieobsługiwany przez Android");
                return;
            }
            nativePlayer.setOnTimedTextListener((mp, timedText) -> {
                if (generation == playGeneration && subtitleText != null)
                    subtitleText.setText(timedText == null ? "" : timedText.getText());
            });
            nativePlayer.setOnVideoSizeChangedListener((mp, width, height) -> {
                if (generation == playGeneration) showQuality(height);
            });
            if (!new SecureSettings(this).subtitlesEnabled()) {
                try {
                    for (int type : new int[]{MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE,
                            MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT}) {
                        int selected = nativePlayer.getSelectedTrack(type);
                        if (selected >= 0) nativePlayer.deselectTrack(selected);
                    }
                } catch (RuntimeException ignored) { }
            }
            if (channel.vod && channel.playbackMs > 0)
                platformView.seekTo((int) Math.min(Integer.MAX_VALUE, channel.playbackMs));
            if (nativePlayer.getVideoHeight() > 0)
                showQuality(Math.min(nativePlayer.getVideoWidth(), nativePlayer.getVideoHeight()));
            nativePlayer.setOnInfoListener((mp, what, extra) -> {
                if (generation != playGeneration || !usingPlatform || leftScreen) return true;
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    markFirstFrameReady("Android");
                } else if (what == MediaPlayer.MEDIA_INFO_AUDIO_NOT_PLAYING) {
                    fallbackForAudioCodec("audio bez dźwięku");
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    if (!firstFrameReady || !platformView.isPlaying())
                        showLoading("Android · Oczekiwanie na dane strumienia");
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END &&
                        platformView.isPlaying() && firstFrameReady) {
                    markFirstFrameReady("Android");
                }
                return true;
            });
            platformView.start();
            setPlaybackStatus("Android · Przygotowuję obraz");
        });
        platformView.setOnErrorListener((mp, what, extra) -> {
            if (generation == playGeneration && usingPlatform && !leftScreen) {
                Log.w("SS-IPTV-Player", "System MediaPlayer error " + what + "/" + extra);
                if (what == MediaPlayer.MEDIA_ERROR_UNSUPPORTED)
                    fallbackForAudioCodec("nieobsługiwany format");
                else recoverPlayback("Android: błąd strumienia " + what);
            }
            return true;
        });
        platformView.setOnCompletionListener(mp -> {
            if (generation != playGeneration || !usingPlatform || leftScreen) return;
            if (channel.vod) {
                if (channel.id > 0) storage.execute(() -> database.savePlayback(channel.id, 0, 0));
            } else recoverPlayback("Android · koniec strumienia TV");
        });
        try {
            if (channel.vod) platformView.setMediaController(new MediaController(this));
            else platformView.setMediaController(null);
            platformView.setVideoURI(Uri.parse(spec.url), spec.headers);
            showLoading("Android · Łączenie ze strumieniem");
            setPlaybackStatus("Android · Łączenie");
            platformView.start();
            root.requestFocus();
        } catch (Exception | LinkageError error) {
            Log.w("SS-IPTV-Player", "System MediaPlayer unavailable", error);
            recoverPlayback("Błąd inicjalizacji Android MediaPlayer");
        }
    }

    private void releasePlatform() {
        platformPlayer = null;
        if (platformView != null) {
            platformView.stopPlayback();
            platformView.setVisibility(View.GONE);
        }
    }

    /** Silnik natywny VideoLAN jest domyślny dla telewizji, również dla kodeków audio TS. */
    private void startVlc(StreamSpec spec) {
        final long generation = ++playGeneration;
        releasePlatform();
        releaseExo();
        usingPlatform = false;
        usingVlc = true;
        firstFrameReady = false;
        hasVlcVideoOutput = false;
        lastSamplePosition = -1;
        lastSampleTraffic = -1;
        engineStartedAt = SystemClock.elapsedRealtime();
        watchdog.reset(engineStartedAt);
        lastVlcTrafficBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        playerView.setVisibility(View.GONE);
        vlcView.setVisibility(View.VISIBLE);
        try {
            if (libVlc == null) {
                ArrayList<String> options = new ArrayList<>();
                options.add("--network-caching=" + (channel.vod ? 4500 : 1800));
                options.add("--live-caching=1800");
                options.add("--file-caching=" + (channel.vod ? 3500 : 1200));
                libVlc = new LibVLC(getApplicationContext(), options);
                libVlc.setUserAgent("SS-IPTV/1.7 (Android TV)", "SS-IPTV/1.7");
            }
            if (vlcPlayer == null) {
                vlcPlayer = new org.videolan.libvlc.MediaPlayer(libVlc);
                vlcPlayer.attachViews(vlcView, null, false, false);
            } else {
                vlcPlayer.setEventListener(null);
                vlcPlayer.stop();
            }
            org.videolan.libvlc.MediaPlayer nativePlayer = vlcPlayer;
            final boolean[] resumedVod = {false};
            final boolean[] subtitlesInitialized = {false};
            // Zawsze pokazujemy cały kadr: bez crop, rozciągania i zoomu.
            nativePlayer.setScale(0f);
            nativePlayer.setAspectRatio(null);
            Media media = new Media(libVlc, Uri.parse(spec.url));
            media.setHWDecoderEnabled(true, false);
            // Krótki bufor ogranicza RAM i opóźnienia przy zappingu na Android Box.
            media.addOption(":network-caching=" + (channel.vod ? 4500 : 1800));
            media.addOption(":live-caching=1800");
            media.addOption(":http-reconnect=true");
            for (Map.Entry<String, String> entry : spec.headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase("User-Agent"))
                    media.addOption(":http-user-agent=" + entry.getValue());
                else if (entry.getKey().equalsIgnoreCase("Referer"))
                    media.addOption(":http-referrer=" + entry.getValue());
            }
            nativePlayer.setMedia(media);
            media.release();
            nativePlayer.setEventListener(event -> {
                final int type = event.type;
                final long eventTime = type == org.videolan.libvlc.MediaPlayer.Event.TimeChanged ?
                        event.getTimeChanged() : -1L;
                final int vout = type == org.videolan.libvlc.MediaPlayer.Event.Vout ?
                        event.getVoutCount() : 0;
                handler.post(() -> {
                if (generation != playGeneration || !usingVlc || leftScreen) return;
                long now = SystemClock.elapsedRealtime();
                switch (type) {
                    case org.videolan.libvlc.MediaPlayer.Event.Playing:
                        if (!subtitlesInitialized[0]) {
                            subtitlesInitialized[0] = true;
                            if (!new SecureSettings(this).subtitlesEnabled())
                                nativePlayer.setSpuTrack(-1);
                        }
                        if (channel.vod && !resumedVod[0]) {
                            resumedVod[0] = true;
                            if (channel.playbackMs > 0) nativePlayer.setTime(channel.playbackMs);
                        }
                        watchdog.heartbeat(now);
                        showVlcQuality(nativePlayer);
                        if (hasVlcVideoOutput) markFirstFrameReady("VLC");
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.TimeChanged:
                        watchdog.observe(now, eventTime);
                        if (eventTime > 0) lastPlaybackEventAt = now;
                        if (hasVlcVideoOutput && nativePlayer.isPlaying())
                            markFirstFrameReady("VLC");
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.Vout:
                        showVlcQuality(nativePlayer);
                        if (vout > 0) hasVlcVideoOutput = true;
                        if (hasVlcVideoOutput && nativePlayer.isPlaying())
                            markFirstFrameReady("VLC");
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.Opening:
                    case org.videolan.libvlc.MediaPlayer.Event.Buffering:
                        // VLC wysyła Buffering także PO starcie obrazu.
                        // Nie zmieniaj statusu na "Buforowanie", gdy wideo trwa.
                        if (!firstFrameReady) {
                            showLoading("VLC · Łączenie ze strumieniem");
                            setPlaybackStatus("VLC · Łączenie");
                        } else if (!nativePlayer.isPlaying()) {
                            showLoading("VLC · Wznawianie transmisji");
                            setPlaybackStatus("VLC · Przerwa w transmisji");
                        }
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.EncounteredError:
                        recoverPlayback("VLC zgłosił błąd strumienia");
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.EndReached:
                        if (channel.vod) finishVodPlayback();
                        else recoverPlayback("Strumień TV został zakończony");
                        break;
                    default: break;
                }
                });
            });
            setPlaybackStatus("VLC · Łączenie");
            showLoading("VLC · Łączenie ze strumieniem");
            nativePlayer.play();
            root.requestFocus();
        } catch (Exception | LinkageError error) {
            Log.w("SS-IPTV-Player", "VLC unavailable", error);
            recoverPlayback("Błąd uruchamiania VLC");
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
        releasePlatform();
        releaseVlc();
        if (libVlc != null) {
            libVlc.release();
            libVlc = null;
        }
        usingVlc = false;
        usingPlatform = false;
        firstFrameReady = false;
        lastSamplePosition = -1;
        lastSampleTraffic = -1;
        engineStartedAt = SystemClock.elapsedRealtime();
        watchdog.reset(engineStartedAt);
        vlcView.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        try {
            if (httpFactory == null) httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent("SS-IPTV/1.7 Android TV")
                    .setConnectTimeoutMs(10000)
                    .setReadTimeoutMs(13000)
                    .setAllowCrossProtocolRedirects(true);
            httpFactory.setDefaultRequestProperties(spec.headers);
            // Zamiast niszczyć sprzętowy dekoder przy każdym CH+/CH- wykorzystujemy
            // ten sam ExoPlayer. Ograniczamy bufor TV do 16 MiB.
            ExoPlayer exo = player;
            if (exo == null) {
                ActivityManager memory = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                boolean smallBox = memory != null && memory.getMemoryClass() <= 128;
                int maxBufferBytes = (channel.vod ? (smallBox ? 12 : 24) :
                        (smallBox ? 8 : 16)) * 1024 * 1024;
                DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(channel.vod ? 6000 : 2500,
                                channel.vod ? 25000 : 12000, 600, 1500)
                        .setTargetBufferBytes(maxBufferBytes)
                        .build();
                DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                        .setEnableDecoderFallback(true);
                exo = new ExoPlayer.Builder(this)
                        .setRenderersFactory(renderers)
                        .setLoadControl(loadControl)
                        .setMediaSourceFactory(new DefaultMediaSourceFactory(
                                new DefaultDataSource.Factory(this, httpFactory)))
                        .build();
                player = exo;
            } else {
                if (exoListener != null) exo.removeListener(exoListener);
                exo.stop();
                exo.clearMediaItems();
            }
            exo.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA).build(), true);
            playerView.setPlayer(exo);
            playerView.setUseController(channel.vod);
            playerView.requestFocus();
            SecureSettings playbackSettings = new SecureSettings(this);
            exo.setTrackSelectionParameters(exo.getTrackSelectionParameters().buildUpon()
                    .setPreferredAudioLanguage("pl")
                    .setPreferredTextLanguage("pl")
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !playbackSettings.subtitlesEnabled())
                    .build());
            MediaItem.Builder media = new MediaItem.Builder().setUri(spec.url);
            String path = spec.url.split("\\?", 2)[0].toLowerCase(Locale.ROOT);
            if (path.endsWith(".m3u8")) media.setMimeType(MimeTypes.APPLICATION_M3U8);
            else if (path.endsWith(".mpd")) media.setMimeType(MimeTypes.APPLICATION_MPD);
            else if (path.startsWith("rtsp://")) media.setMimeType(MimeTypes.APPLICATION_RTSP);
            exo.setMediaItem(media.build());
            if (qualityCap < Integer.MAX_VALUE) applyQualityCap();
            if (channel.vod && channel.playbackMs > 0) exo.seekTo(channel.playbackMs);
            exoListener = new Player.Listener() {
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
                                "Brak kodeka audio · uruchamiam VLC" : "");
                        audioWarning.setVisibility(audioFound && !audioSupported ?
                                View.VISIBLE : View.GONE);
                    }
                    if (audioFound && !audioSupported)
                        fallbackForAudioCodec("nieobsługiwany przez Media3");
                }
                @Override public void onPlaybackStateChanged(int state) {
                    if (generation != playGeneration) return;
                    if (state == Player.STATE_READY) {
                        watchdog.heartbeat(SystemClock.elapsedRealtime());
                        if (firstFrameReady) setPlaybackStatus("Media3 · Odtwarzanie");
                        else setPlaybackStatus("Media3 · Przygotowuję obraz");
                    } else if (state == Player.STATE_BUFFERING) {
                        if (!firstFrameReady) showLoading("Media3 · Pobieranie danych kanału");
                        else showLoading("Media3 · Wznawianie strumienia");
                        setPlaybackStatus("Media3 · Oczekiwanie na dane");
                    }
                    if (state == Player.STATE_ENDED) {
                        if (channel.vod) finishVodPlayback();
                        else recoverPlayback("Zakończył się strumień TV");
                    }
                }
                @Override public void onPlayerError(PlaybackException error) {
                    if (generation != playGeneration) return;
                    Log.w("SS-IPTV-Player", "Media3 " + error.getErrorCodeName());
                    if (isUnavailableHttp(error)) {
                        showSourceUnavailable();
                    } else if (error instanceof ExoPlaybackException &&
                            ((ExoPlaybackException) error).rendererFormat != null &&
                            ((ExoPlaybackException) error).rendererFormat.sampleMimeType != null &&
                            ((ExoPlaybackException) error).rendererFormat.sampleMimeType
                                    .startsWith("audio/")) {
                        fallbackForAudioCodec("błąd dekodowania ścieżki");
                    } else recoverPlayback("Media3: " + error.getErrorCodeName());
                }
                @Override public void onRenderedFirstFrame() {
                    if (generation == playGeneration) markFirstFrameReady("Media3");
                }
                @Override public void onIsPlayingChanged(boolean playing) {
                    if (generation == playGeneration && playing && firstFrameReady)
                        markFirstFrameReady("Media3");
                }
            };
            exo.addListener(exoListener);
            setPlaybackStatus("Media3 · Łączenie");
            showLoading("Media3 · Łączenie ze strumieniem");
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
            if (exoListener != null) player.removeListener(exoListener);
            exoListener = null;
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
        if (usingPlatform && platformView != null) {
            if (platformView.isPlaying())
                watchdog.observe(now, platformView.getCurrentPosition());
        } else if (usingVlc && vlcPlayer != null) {
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
        if (!usingVlc && !usingPlatform && player != null) {
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
        boolean paused = userPaused || leftScreen || channel.vod && !usingPlatform && !usingVlc &&
                player != null && !player.getPlayWhenReady();
        if (watchdog.isStalled(now, network.connected, paused))
            recoverPlayback("Brak postępu odtwarzania przez 60 s");
    }

    /** Lekki odczyt postępu bez ponownego tworzenia dekodera ani listy kanałów. */
    private void sampleQuickProgress(long now) {
        long pos = usingPlatform && platformView != null ? platformView.getCurrentPosition() :
                usingVlc && vlcPlayer != null ? vlcPlayer.getTime() :
                player != null ? player.getCurrentPosition() : -1;
        boolean playing = usingPlatform ? platformView != null && platformView.isPlaying() :
                usingVlc ? vlcPlayer != null && vlcPlayer.isPlaying() :
                player != null && player.isPlaying();
        boolean progressing = playing && pos >= 0 && lastSamplePosition >= 0 &&
                (pos > lastSamplePosition + 200 || pos + 1000 < lastSamplePosition);
        lastSamplePosition = pos;
        long traffic = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        if (playing && pos <= 0 && traffic >= 0 && lastSampleTraffic >= 0 &&
                traffic > lastSampleTraffic + 8192) progressing = true;
        lastSampleTraffic = traffic;
        if (progressing) {
            lastPlaybackEventAt = now;
            watchdog.heartbeat(now);
            if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE)
                markFirstFrameReady(engineName());
        }
    }

    private void recoverPlayback(String reason) {
        if (leftScreen || isFinishing() || isDestroyed() || retryScheduled ||
                unavailablePending) return;
        Log.w("SS-IPTV-Player", reason + " | engine=" + engineName());
        boolean networkAvailable = NetworkIndicator.read(this).connected;
        if (UnavailableChannelPolicy.shouldAdvance(channel.vod, networkAvailable,
                firstFrameReady, startupRetries)) {
            showSourceUnavailable();
            return;
        }
        if (channel.vod) rememberVodPosition();
        showOsd();
        // Przy HLS/DASH ogranicz bitrate w działającym Media3, bez przełączania
        // dekodera. Inne formaty nie mają wariantów jakości po stronie klienta.
        if (!usingPlatform && !usingVlc && adaptiveStream && qualityCap > 1_000_000) {
            qualityCap = qualityCap == Integer.MAX_VALUE ? 4_000_000 :
                    Math.max(1_000_000, qualityCap / 2);
            applyQualityCap();
        }
        retryScheduled = true;
        int delaySeconds = networkAvailable ? Math.min(20, 2 << Math.min(startupRetries, 4)) : 10;
        startupRetries++;
        String status = networkAvailable ? "Ponawianie za " + delaySeconds + " s · " +
                engineName() : "Brak sieci · sprawdzę ponownie za 10 s";
        showLoading(status);
        setPlaybackStatus(status);
        long generation = playGeneration;
        handler.postDelayed(() -> {
            if (generation != playGeneration || leftScreen || isFinishing()) return;
            retryScheduled = false;
            if (!NetworkIndicator.read(this).connected) {
                recoverPlayback("Sieć nadal niedostępna");
                return;
            }
            try { startSelectedEngine(resolveStream()); }
            catch (Exception error) { recoverPlayback("Błędny adres strumienia"); }
        }, delaySeconds * 1000L);
    }

    /** Błędy HTTP oznaczające martwy albo odrzucony adres, a nie zwykłe buforowanie. */
    private static boolean isUnavailableHttp(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                int code = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                return code == 401 || code == 403 || code == 404 || code == 410;
            }
        }
        return false;
    }

    private void showSourceUnavailable() {
        if (unavailablePending || leftScreen) return;
        unavailablePending = true;
        retryScheduled = true;
        String message = channel.vod ? "Materiał jest niedostępny. Sprawdź adres lub serwer." :
                "Źródło kanału niedostępne · kolejny kanał za 5 sekund";
        showLoading(message);
        if (loadingSpinner != null) {
            loadingSpinner.clearAnimation();
            loadingSpinner.setVisibility(View.GONE);
        }
        setPlaybackStatus(message);
        showOsd();
        if (channel.vod) return;
        long generation = playGeneration;
        handler.postDelayed(() -> {
            if (generation != playGeneration || leftScreen || isFinishing()) return;
            unavailablePending = false;
            retryScheduled = false;
            zap(1);
        }, 5000);
    }

    private void rememberVodPosition() {
        long position = usingPlatform && platformView != null ? platformView.getCurrentPosition() :
                usingVlc && vlcPlayer != null ? vlcPlayer.getTime() :
                player != null ? player.getCurrentPosition() : -1;
        if (position > 0 && position != C.TIME_UNSET) channel.playbackMs = position;
    }

    private void finishVodPlayback() {
        if (!channel.vod) return;
        channel.playbackMs = 0;
        if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
        if (channel.id > 0) storage.execute(() -> database.savePlayback(channel.id, 0, 0));
        setPlaybackStatus("Koniec materiału · wybierz kolejny film lub odcinek");
        showOsd();
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

    /** Widoczny także po schowaniu OSD — bez czarnego ekranu bez wyjaśnienia. */
    private void addLoadingIndicator(FrameLayout parent) {
        loadingOverlay = new FrameLayout(this);
        loadingOverlay.setBackgroundColor(0xD808111A);
        loadingOverlay.setFocusable(false);
        loadingOverlay.setClickable(false);
        LinearLayout card = Ui.column(this);
        card.setGravity(Gravity.CENTER);
        card.setPadding(Ui.dp(this, 28), Ui.dp(this, 23), Ui.dp(this, 28), Ui.dp(this, 23));
        card.setBackground(Ui.rounded(this, 0xE41B3042, 18));
        loadingSpinner = new ImageView(this);
        loadingSpinner.setImageResource(R.drawable.ic_player_loading);
        card.addView(loadingSpinner, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        loadingChannel = Ui.text(this, channel.name, 19, Ui.WHITE, true);
        loadingChannel.setGravity(Gravity.CENTER);
        loadingChannel.setMaxLines(2);
        card.addView(loadingChannel, Ui.margins(this, -1, -2, 0, 13, 0, 6));
        loadingMessage = Ui.text(this, "Łączenie ze strumieniem…", 13, Ui.ACCENT, false);
        loadingMessage.setGravity(Gravity.CENTER);
        card.addView(loadingMessage);
        loadingOverlay.addView(card, new FrameLayout.LayoutParams(
                Math.min(Ui.dp(this, 380), getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 40)),
                -2, Gravity.CENTER));
        parent.addView(loadingOverlay, new FrameLayout.LayoutParams(-1, -1));
        loadingOverlay.setVisibility(View.GONE);
    }

    private void showLoading(String state) {
        if (loadingOverlay == null) return;
        if (!unavailablePending && loadingSpinner != null)
            loadingSpinner.setVisibility(View.VISIBLE);
        loadingChannel.setText(channel.name);
        loadingMessage.setText(state);
        if (loadingOverlay.getVisibility() != View.VISIBLE) {
            loadingOverlay.setVisibility(View.VISIBLE);
            RotateAnimation rotate = new RotateAnimation(0f, 360f,
                    Animation.RELATIVE_TO_SELF, .5f,
                    Animation.RELATIVE_TO_SELF, .5f);
            rotate.setDuration(1050);
            rotate.setRepeatCount(Animation.INFINITE);
            rotate.setInterpolator(new LinearInterpolator());
            loadingSpinner.startAnimation(rotate);
        }
    }

    private void markFirstFrameReady(String engine) {
        boolean firstReady = !firstFrameReady;
        boolean overlayWasVisible = loadingOverlay != null &&
                loadingOverlay.getVisibility() == View.VISIBLE;
        if (!firstReady && !overlayWasVisible) return;
        firstFrameReady = true;
        startupRetries = 0;
        lastPlaybackEventAt = SystemClock.elapsedRealtime();
        watchdog.heartbeat(lastPlaybackEventAt);
        if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
        if (loadingSpinner != null) loadingSpinner.clearAnimation();
        if (bufferingText != null) bufferingText.setText(engine + " · Odtwarzanie");
        if (firstReady && channel != null && channel.id > 0) {
            final long watchedId = channel.id;
            storage.execute(() -> database.markWatched(watchedId));
        }
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
        if (!channel.vod)
            actionsLine.addView(instruction, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout actions = Ui.row(this);
        View vodAudioAction = null;
        if (channel.vod) {
            addColorAction(actions, "−30s", R.drawable.ic_seek_back, Ui.SECONDARY,
                    v -> seekVod(-30_000));
            addColorAction(actions, "Pauza/Play", R.drawable.ic_player_play, Ui.ACCENT,
                    v -> toggleVodPause());
            addColorAction(actions, "+30s", R.drawable.ic_seek_forward, Ui.SECONDARY,
                    v -> seekVod(30_000));
            vodAudioAction = addColorAction(actions, "Audio", R.drawable.ic_audio_track, Ui.ACCENT,
                    v -> showAudioTracks());
            addColorAction(actions, "Napisy", R.drawable.ic_subtitles, Ui.ACCENT,
                    v -> showSubtitleTracks());
        }
        firstOsdAction = addColorAction(actions, "Ulubione", R.drawable.ic_nav_heart, 0xFFDD5165, v -> toggleFavorite());
        addColorAction(actions, "OSD", R.drawable.ic_player_info, 0xFF35AD83, v -> toggleOsd());
        addColorAction(actions, "Opis", R.drawable.ic_nav_epg, 0xFFE2B456, v -> toggleDescription());
        addColorAction(actions, "Ustawienia", R.drawable.ic_nav_settings, 0xFF5792D4, v -> toggleSettings());
        // Na Android TV wyszukiwanie najbliższego widoku potrafi przeskoczyć z
        // przycisków VOD na serduszko nad nimi. Stała kolejność D-pada temu zapobiega.
        for (int i = 0; i < actions.getChildCount(); i++)
            actions.getChildAt(i).setId(View.generateViewId());
        for (int i = 0; i < actions.getChildCount(); i++) {
            View action = actions.getChildAt(i);
            if (i > 0) action.setNextFocusLeftId(actions.getChildAt(i - 1).getId());
            if (i + 1 < actions.getChildCount())
                action.setNextFocusRightId(actions.getChildAt(i + 1).getId());
        }
        // OK lub pierwsza strzałka uruchamia nawigację przy wyborze audio.
        if (vodAudioAction != null) firstOsdAction = vodAudioAction;
        if (channel.vod) {
            HorizontalScrollView scrollActions = new HorizontalScrollView(this);
            scrollActions.setHorizontalScrollBarEnabled(false);
            scrollActions.addView(actions);
            actionsLine.addView(scrollActions, new LinearLayout.LayoutParams(-1, -2));
        } else actionsLine.addView(actions);
        osd.addView(actionsLine);

        FrameLayout.LayoutParams osdParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(osd, osdParams);
    }

    private void showQuality(int height) {
        updateVideoSizing(height);
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

    private void updateVideoSizing(int height) {
        boolean stretch = height > 0 && height <= 576 &&
                new SecureSettings(this).fillSdScreen();
        if (playerView != null) playerView.setResizeMode(stretch ?
                AspectRatioFrameLayout.RESIZE_MODE_FILL : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        if (platformView != null) platformView.setFillScreen(stretch);
        if (usingVlc && vlcPlayer != null) {
            if (stretch) {
                android.util.DisplayMetrics display = getResources().getDisplayMetrics();
                vlcPlayer.setAspectRatio(display.widthPixels + ":" + display.heightPixels);
            } else vlcPlayer.setAspectRatio(null);
        }
    }

    private void showOsd() {
        if (osd == null) return;
        osd.setVisibility(View.VISIBLE);
        updateProgressLabel();
        updateProgramLabels();
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
        ChannelDatabase.Guide guide = epgSnapshot.get(channel.id);
        long now = System.currentTimeMillis();
        boolean currentAvailable = guide != null && guide.end > now &&
                guide.current != null && !guide.current.trim().isEmpty();
        nowText.setText(currentAvailable ? "Teraz  ·  " + guide.current :
                "Teraz  ·  Brak dopasowanego programu w EPG");
        boolean nextAvailable = guide != null && guide.nextStart > now &&
                guide.next != null && !guide.next.trim().isEmpty();
        nextText.setText(nextAvailable ? "Następnie  ·  " + guide.next :
                "Następnie  ·  Brak danych");
    }

    private void refreshEpgSnapshot() {
        if (storage.isShutdown()) return;
        storage.execute(() -> {
            Map<Long, ChannelDatabase.Guide> recent = database.readGuides();
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                epgSnapshot = recent;
                updateProgramLabels();
            });
        });
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
                ChannelDatabase.Guide guide = epgSnapshot.get(channel.id);
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
        settingsBody.addView(Ui.text(this, "Wybrany odtwarzacz: " +
                        (usingPlatform ? "Android / systemowy MediaPlayer" :
                                usingVlc ? "VLC / VideoLAN" : "Media3 / ExoPlayer"),
                12, Ui.SECONDARY, true), Ui.margins(this, -1, -2, 0, 12, 0, 5));
        for (String engine : new String[]{"android", "media3", "vlc"}) {
            String label = "android".equals(engine) ? "Systemowy Android" :
                    "media3".equals(engine) ? "Media3 / ExoPlayer" : "VLC / VideoLAN";
            settingsBody.addView(Ui.button(this,
                    (engine.equals(settings.playbackEngine()) ? "Aktywny: " : "Wybierz: ") + label,
                    engine.equals(settings.playbackEngine()), v -> {
                        settings.setPlaybackEngine(engine);
                        toggleSettings();
                        if (!engine.equals(activeEngine)) startPlayback();
                    }), Ui.margins(this, -1, -2, 0, 4, 0, 4));
        }
        settingsBody.addView(Ui.button(this, "Wybierz ścieżkę dźwięku", false,
                v -> showAudioTracks()), Ui.margins(this, -1, -2, 0, 9, 0, 5));
        settingsBody.addView(Ui.button(this, "Ścieżki napisów / wyłącz napisy", false,
                v -> showSubtitleTracks()), Ui.margins(this, -1, -2, 0, 6, 0, 5));
        settingsBody.addView(Ui.button(this,
                settings.fillSdScreen() ? "SD: wypełniaj ekran (wyłącz)" :
                        "SD: zachowuj proporcje (włącz wypełnianie)", false, v -> {
                    settings.setFillSdScreen(!settings.fillSdScreen());
                    showQuality(currentVideoHeight());
                    toggleSettings();
                    toggleSettings();
                }), Ui.margins(this, -1, -2, 0, 6, 0, 5));
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
        if (usingPlatform) {
            if (platformPlayer == null) { noTracks("Dźwięk", "Odtwarzacz jeszcze wczytuje ścieżki."); return; }
            try {
                MediaPlayer.TrackInfo[] info = platformPlayer.getTrackInfo();
                ArrayList<Integer> indices = new ArrayList<>();
                ArrayList<String> options = new ArrayList<>();
                int selected = platformPlayer.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO);
                for (int i = 0; i < info.length; i++) {
                    if (info[i].getTrackType() != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) continue;
                    String lang = info[i].getLanguage();
                    options.add((selected == i ? "Wybrana: " : "") + "Audio " +
                            (lang == null ? "" : lang) + " · ścieżka " + (indices.size() + 1));
                    indices.add(i);
                }
                if (indices.isEmpty()) { noTracks("Dźwięk", "Źródło nie zgłasza ścieżek audio."); return; }
                new AlertDialog.Builder(this).setTitle("Android · ścieżki dźwięku")
                        .setItems(options.toArray(new String[0]), (d, which) -> {
                            try { if (platformPlayer != null) platformPlayer.selectTrack(indices.get(which)); }
                            catch (RuntimeException e) { Toast.makeText(this,
                                    "Nie można wybrać ścieżki na tym urządzeniu", Toast.LENGTH_LONG).show(); }
                        }).setNegativeButton("Anuluj", null).show();
            } catch (RuntimeException e) {
                noTracks("Dźwięk", "Systemowy dekoder nie udostępnia wyboru ścieżek.");
            }
            return;
        }
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

    private void noTracks(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("OK", null).show();
    }

    /** Ścieżki tekstowe i wybór „Wyłącz” we wszystkich trzech silnikach. */
    private void showSubtitleTracks() {
        SecureSettings preferences = new SecureSettings(this);
        if (usingVlc && vlcPlayer != null) {
            org.videolan.libvlc.MediaPlayer.TrackDescription[] tracks = vlcPlayer.getSpuTracks();
            ArrayList<String> options = new ArrayList<>();
            ArrayList<Integer> ids = new ArrayList<>();
            options.add("Wyłącz napisy");
            ids.add(-1);
            if (tracks != null) for (org.videolan.libvlc.MediaPlayer.TrackDescription track : tracks) {
                if (track.id < 0) continue;
                options.add((track.id == vlcPlayer.getSpuTrack() ? "Aktywne: " : "") +
                        track.name);
                ids.add(track.id);
            }
            new AlertDialog.Builder(this).setTitle("VLC · napisy")
                    .setItems(options.toArray(new String[0]), (dialog, choice) -> {
                        if (vlcPlayer == null) return;
                        boolean enabled = choice > 0;
                        preferences.setSubtitlesEnabled(enabled);
                        vlcPlayer.setSpuTrack(ids.get(choice));
                        if (!enabled) subtitleText.setText("");
                        showOsd();
                    }).setNegativeButton("Anuluj", null).show();
            return;
        }
        if (usingPlatform) {
            if (platformPlayer == null) { noTracks("Napisy", "Poczekaj na załadowanie filmu."); return; }
            try {
                MediaPlayer.TrackInfo[] infos = platformPlayer.getTrackInfo();
                ArrayList<Integer> indices = new ArrayList<>();
                ArrayList<String> options = new ArrayList<>();
                options.add("Wyłącz napisy");
                for (int i = 0; i < infos.length; i++) {
                    int type = infos[i].getTrackType();
                    if (type != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE &&
                            type != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) continue;
                    indices.add(i);
                    options.add("Napisy " + infos[i].getLanguage() + " · " +
                            (type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE ?
                                    "wbudowane" : "tekstowe"));
                }
                new AlertDialog.Builder(this).setTitle("Android · napisy")
                        .setItems(options.toArray(new String[0]), (dialog, choice) -> {
                            if (platformPlayer == null) return;
                            try {
                                if (choice == 0) {
                                    for (int type : new int[]{MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE,
                                            MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT}) {
                                        int selected = platformPlayer.getSelectedTrack(type);
                                        if (selected >= 0) platformPlayer.deselectTrack(selected);
                                    }
                                    subtitleText.setText("");
                                } else platformPlayer.selectTrack(indices.get(choice - 1));
                                preferences.setSubtitlesEnabled(choice > 0);
                            } catch (RuntimeException e) {
                                Toast.makeText(this, "Napisy niedostępne w systemowym dekoderze",
                                        Toast.LENGTH_LONG).show();
                            }
                        }).setNegativeButton("Anuluj", null).show();
            } catch (RuntimeException e) {
                noTracks("Napisy", "Urządzenie nie obsługuje wyboru napisów tego źródła.");
            }
            return;
        }
        if (player == null) { noTracks("Napisy", "Poczekaj na załadowanie filmu."); return; }
        ArrayList<String> choices = new ArrayList<>();
        ArrayList<TrackSelectionOverride> overrides = new ArrayList<>();
        choices.add("Wyłącz napisy");
        choices.add("Automatycznie (preferuj polskie)");
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format format = group.getTrackFormat(i);
                choices.add((group.isTrackSelected(i) ? "Aktywne: " : "") +
                        (format.language == null ? "Bez języka" : format.language) +
                        (format.label == null ? "" : " · " + format.label));
                overrides.add(new TrackSelectionOverride(group.getMediaTrackGroup(), i));
            }
        }
        new AlertDialog.Builder(this).setTitle("Media3 · napisy")
                .setItems(choices.toArray(new String[0]), (dialog, choice) -> {
                    if (player == null) return;
                    TrackSelectionParameters.Builder params = player.getTrackSelectionParameters()
                            .buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, choice == 0);
                    if (choice > 1) params.setOverrideForType(overrides.get(choice - 2));
                    player.setTrackSelectionParameters(params.build());
                    preferences.setSubtitlesEnabled(choice > 0);
                    if (choice == 0) subtitleText.setText("");
                    showOsd();
                }).setNegativeButton("Anuluj", null).show();
    }

    private int currentVideoHeight() {
        if (usingPlatform && platformPlayer != null) return platformPlayer.getVideoHeight();
        if (usingVlc && vlcPlayer != null && vlcPlayer.getCurrentVideoTrack() != null)
            return vlcPlayer.getCurrentVideoTrack().height;
        return player != null ? player.getVideoSize().height : 0;
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
        ++playGeneration; // Odrzuć zaplanowane ponowienia poprzedniego kanału.
        numberInput.setLength(0);
        numberOverlay.setVisibility(View.GONE);
        updateChannelOsd();
        showLoading("Przełączanie kanału…");
        if (pendingZap != null) handler.removeCallbacks(pendingZap);
        // Kilka szybkich naciśnięć pilota oznacza jeden ostatni wybór.
        pendingZap = () -> {
            pendingZap = null;
            startPlayback();
        };
        handler.postDelayed(pendingZap, 260);
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
        int channelStep = PlayerRemoteRules.channelStepForKeyCode(keyCode);
        if (channelStep != 0 && !channel.vod) { zap(channelStep); return true; }
        int vodSeekOffset = PlayerRemoteRules.vodSeekOffsetForKeyCode(keyCode);
        if (channel.vod && vodSeekOffset != 0) { seekVod(vodSeekOffset); return true; }
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
            case KeyEvent.KEYCODE_DPAD_UP:
                if (PlayerRemoteRules.shouldZapWithArrows(channel.vod, settingsPanel != null,
                        osdNavigationMode, descriptionOpen)) { zap(1); return true; }
                if (channel.vod && settingsPanel == null && !osdNavigationMode && !descriptionOpen) {
                    enterOsdNavigation(); return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (PlayerRemoteRules.shouldZapWithArrows(channel.vod, settingsPanel != null,
                        osdNavigationMode, descriptionOpen)) { zap(-1); return true; }
                if (channel.vod && settingsPanel == null && !osdNavigationMode && !descriptionOpen) {
                    enterOsdNavigation(); return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (numberInput.length() > 0) { commitChannelNumber(); return true; }
                if (!osdNavigationMode && settingsPanel == null) {
                    enterOsdNavigation(); return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!osdNavigationMode && settingsPanel == null && !descriptionOpen) {
                    enterOsdNavigation(); return true;
                }
                return false;
            default: return false;
        }
    }

    private void seekVod(long offsetMs) {
        if (!channel.vod) return;
        long position = usingPlatform ? platformView.getCurrentPosition() :
                usingVlc && vlcPlayer != null ? vlcPlayer.getTime() :
                player != null ? player.getCurrentPosition() : 0;
        long duration = usingPlatform ? platformView.getDuration() :
                usingVlc && vlcPlayer != null ? vlcPlayer.getLength() :
                player != null ? player.getDuration() : -1;
        long target = Math.max(0, position + offsetMs);
        if (duration > 0) target = Math.min(target, Math.max(0, duration - 1000));
        if (usingPlatform) platformView.seekTo((int) Math.min(Integer.MAX_VALUE, target));
        else if (usingVlc && vlcPlayer != null) vlcPlayer.setTime(target);
        else if (player != null) player.seekTo(target);
        channel.playbackMs = target;
        showOsd();
    }

    private void toggleVodPause() {
        if (!channel.vod) return;
        boolean playing = usingPlatform ? platformView.isPlaying() :
                usingVlc && vlcPlayer != null ? vlcPlayer.isPlaying() :
                player != null && player.getPlayWhenReady();
        userPaused = playing;
        if (usingPlatform) {
            if (playing) platformView.pause(); else platformView.start();
        } else if (usingVlc && vlcPlayer != null) {
            if (playing) vlcPlayer.pause(); else vlcPlayer.play();
        } else if (player != null) {
            if (playing) player.pause(); else player.play();
        }
        showOsd();
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
        long pos = usingPlatform && platformView != null ? platformView.getCurrentPosition() :
                usingVlc && vlcPlayer != null ? vlcPlayer.getTime() :
                player != null ? player.getCurrentPosition() : channel.playbackMs;
        long dur = usingPlatform && platformView != null ? platformView.getDuration() :
                usingVlc && vlcPlayer != null ? vlcPlayer.getLength() :
                player != null ? player.getDuration() : channel.durationMs;
        progress.setText("Postęp: " + formatMinutes(pos) +
                (dur > 0 ? " / " + formatMinutes(dur) : ""));
    }

    private String formatMinutes(long millis) {
        long minutes = Math.max(0, millis) / 60000;
        return (minutes / 60) + ":" + String.format(Locale.ROOT, "%02d", minutes % 60);
    }

    private void saveProgress() {
        if (!channel.vod || channel.id <= 0) return;
        if (!usingPlatform && !usingVlc && player == null) return;
        long pos = Math.max(0, usingPlatform && platformView != null ?
                platformView.getCurrentPosition() : usingVlc && vlcPlayer != null ?
                vlcPlayer.getTime() : player.getCurrentPosition());
        long dur = Math.max(0, usingPlatform && platformView != null ?
                platformView.getDuration() : usingVlc && vlcPlayer != null ?
                vlcPlayer.getLength() : player.getDuration());
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
        if (usingPlatform && platformView != null) platformView.start();
        if (vlcPlayer != null && !vlcPlayer.isPlaying()) vlcPlayer.play();
    }

    @Override protected void onStop() {
        leftScreen = true;
        saveProgress();
        if (player != null) player.pause();
        if (usingPlatform && platformView != null && platformView.isPlaying()) platformView.pause();
        if (vlcPlayer != null && vlcPlayer.isPlaying()) vlcPlayer.pause();
        super.onStop();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        saveProgress();
        releasePlatform();
        releaseExo();
        releaseVlc();
        if (libVlc != null) {
            libVlc.release();
            libVlc = null;
        }
        storage.shutdown();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        LogoLoader.onTrimMemory(level);
    }
}
