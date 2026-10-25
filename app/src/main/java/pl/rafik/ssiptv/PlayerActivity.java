package pl.rafik.ssiptv;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService storage = Executors.newSingleThreadExecutor();
    private ExoPlayer player;
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
    private final StringBuilder numberInput = new StringBuilder();
    private final Runnable commitNumber = this::commitChannelNumber;
    private TextView heart;
    private TextView quality;
    private TextView progress;
    private final Runnable hideOsd = () -> {
        if (osd != null) osd.setVisibility(View.GONE);
    };
    private final Runnable saveTick = new Runnable() {
        @Override public void run() {
            saveProgress();
            handler.postDelayed(this, 5000);
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
            Toast.makeText(this, "Nie znaleziono kanału lub filmu", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        storage.execute(() -> database.markWatched(channel.id));

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        playerView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) showOsd();
            return false;
        });
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        addOsd(root);
        TextView info = Ui.text(this, "ⓘ", 23, Ui.WHITE, true);
        info.setGravity(Gravity.CENTER);
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

        try {
            String stream = channel.url;
            if (stream.startsWith("xtream://")) {
                XtreamClient.Credentials creds = new SecureSettings(this).credentials();
                if (creds == null) throw new IllegalStateException("Brak danych serwera Xtream");
                stream = XtreamClient.streamUrl(creds, stream);
            }
            Map<String, String> headers = new HashMap<>();
            if (stream.contains("|")) {
                String[] segments = stream.split("\\|", 2);
                stream = segments[0];
                for (String pair : segments[1].split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2 &&
                            (kv[0].equalsIgnoreCase("User-Agent") || kv[0].equalsIgnoreCase("Referer")))
                        headers.put(kv[0], kv[1]);
                }
            }
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent("SS-IPTV/1.2 Android TV")
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(20000)
                    .setAllowCrossProtocolRedirects(true);
            if (!headers.isEmpty()) http.setDefaultRequestProperties(headers);
            player = new ExoPlayer.Builder(this)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(
                            new DefaultDataSource.Factory(this, http)))
                    .build();
            playerView.setPlayer(player);
            playerView.requestFocus();
            MediaItem.Builder media = new MediaItem.Builder().setUri(stream);
            String path = stream.split("\\?", 2)[0].toLowerCase(Locale.ROOT);
            if (path.endsWith(".m3u8")) media.setMimeType(MimeTypes.APPLICATION_M3U8);
            else if (path.startsWith("rtsp://")) media.setMimeType(MimeTypes.APPLICATION_RTSP);
            player.setMediaItem(media.build());
            if (channel.vod && channel.playbackMs > 0) {
                player.seekTo(channel.playbackMs);
            }
            player.addListener(new Player.Listener() {
                @Override public void onVideoSizeChanged(VideoSize size) {
                    showQuality(Math.min(size.width, size.height));
                }
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_ENDED && channel.vod) {
                        storage.execute(() -> database.savePlayback(channel.id, 0, 0));
                    }
                }
                @Override public void onPlayerError(PlaybackException error) {
                    Toast.makeText(PlayerActivity.this,
                            "Błąd strumienia: " + error.getErrorCodeName(), Toast.LENGTH_LONG).show();
                    showOsd();
                }
            });
            player.prepare();
            player.play();
            handler.postDelayed(saveTick, 5000);
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
        showOsd();
    }

    private void addOsd(FrameLayout root) {
        osd = Ui.column(this);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF5071828, 0xE0112C40, 0x6B0C2338});
        osd.setBackground(background);
        osd.setPadding(Ui.dp(this, 16), Ui.dp(this, 9),
                Ui.dp(this, 16), Ui.dp(this, 13));

        LinearLayout top = Ui.row(this);
        TextView back = Ui.button(this, "‹  Wróć", false, v -> finish());
        top.addView(back);
        TextView hint = Ui.text(this, channel.vod ? "  VIDEO ON DEMAND" : "  ●  NA ŻYWO",
                12, Ui.ACCENT, true);
        top.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        quality = Ui.text(this, "AUTO", 12, Ui.WHITE, true);
        quality.setGravity(Gravity.CENTER);
        quality.setPadding(Ui.dp(this, 13), Ui.dp(this, 7),
                Ui.dp(this, 13), Ui.dp(this, 7));
        quality.setBackground(Ui.rounded(this, 0xFF526175, 8));
        top.addView(quality);
        osd.addView(top);

        LinearLayout details = Ui.row(this);
        details.setPadding(0, Ui.dp(this, 10), 0, 0);
        FrameLayout logobox = new FrameLayout(this);
        logobox.setBackground(Ui.rounded(this, Ui.SURFACE, 13));
        TextView initials = Ui.text(this, channel.name.substring(0,
                Math.min(2, channel.name.length())).toUpperCase(Locale.ROOT),
                16, Ui.ACCENT, true);
        initials.setGravity(Gravity.CENTER);
        logobox.addView(initials, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(this);
        image.setPadding(Ui.dp(this, 4), Ui.dp(this, 4),
                Ui.dp(this, 4), Ui.dp(this, 4));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logobox.addView(image, new FrameLayout.LayoutParams(-1, -1));
        LogoLoader.load(image, channel.logo);
        details.addView(logobox, new LinearLayout.LayoutParams(Ui.dp(this, 68), Ui.dp(this, 68)));

        LinearLayout labels = Ui.column(this);
        labels.setPadding(Ui.dp(this, 15), 0, 0, 0);
        TextView name = Ui.text(this, channel.name, 21, Ui.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(name);
        ChannelDatabase.Guide guide = database.readGuides().get(channel.id);
        String now = channel.vod ? "Odtwarzany film VOD" :
                guide == null || guide.current == null || guide.current.isEmpty() ?
                        "Program EPG jeszcze niedostępny" : "Teraz: " + guide.current;
        labels.addView(Ui.text(this, now, 13, Ui.ACCENT, false));
        String next = channel.vod ? "Wznawianie i postęp odtwarzania" :
                guide == null || guide.next == null || guide.next.isEmpty() ?
                        "Następny program: brak danych" : "Następnie: " + guide.next;
        labels.addView(Ui.text(this, next, 12, Ui.SECONDARY, false));
        progress = Ui.text(this, "", 11, Ui.SECONDARY, false);
        if (channel.vod) labels.addView(progress);
        details.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        heart = Ui.text(this, channel.favorite ? "♥" : "♡", 29,
                channel.favorite ? 0xFFFF728D : Ui.WHITE, false);
        heart.setGravity(Gravity.CENTER);
        heart.setContentDescription(channel.favorite ? "Usuń z ulubionych" : "Dodaj do ulubionych");
        heart.setOnClickListener(v -> toggleFavorite());
        Ui.tvFocus(heart);
        details.addView(heart, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 60)));
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

        LinearLayout actions = Ui.row(this);
        actions.setPadding(0, Ui.dp(this, 12), 0, 0);
        addColorAction(actions, "●  Ulubione", 0xFFA93544, v -> toggleFavorite());
        addColorAction(actions, "●  OSD", 0xFF1F8155, v -> toggleOsd());
        addColorAction(actions, "●  Opis", 0xFFB38B15, v -> toggleDescription());
        addColorAction(actions, "●  Ustawienia", 0xFF215EAA, v -> toggleSettings());
        osd.addView(actions);

        osd.addView(Ui.text(this,
                "CH+/CH− lub ↑↓ kanały  •  1–3 cyfry numer kanału  •  OK: zatwierdź  •  " +
                "strzałki + OK: sterowanie OSD", 10, Ui.SECONDARY, false),
                Ui.margins(this, -1, -2, 0, 10, 0, 0));

        FrameLayout.LayoutParams osdParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(osd, osdParams);
    }

    private void showQuality(int height) {
        if (quality == null) return;
        String label;
        int color;
        if (height >= 2160) { label = "◆ 4K"; color = 0xFF7A48BF; }
        else if (height >= 1080) { label = "▣ FULL HD"; color = 0xFF166AB0; }
        else if (height >= 720) { label = "▣ HD"; color = 0xFF087D84; }
        else if (height > 0) { label = "▣ SD"; color = 0xFF526175; }
        else { label = "AUTO"; color = 0xFF526175; }
        quality.setText(label);
        quality.setBackground(Ui.rounded(this, color, 8));
    }

    private void showOsd() {
        if (osd == null) return;
        osd.setVisibility(View.VISIBLE);
        updateProgressLabel();
        handler.removeCallbacks(hideOsd);
        if (!descriptionOpen) handler.postDelayed(hideOsd, 8500);
    }

    private void addColorAction(LinearLayout actions, String label, int background,
                                View.OnClickListener listener) {
        TextView button = Ui.text(this, label, 12, Ui.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(this, 8), Ui.dp(this, 11), Ui.dp(this, 8), Ui.dp(this, 11));
        button.setBackground(Ui.rounded(this, background, 9));
        button.setOnClickListener(listener);
        Ui.tvFocus(button);
        actions.addView(button, new LinearLayout.LayoutParams(0, Ui.dp(this, 43), 1));
    }

    private void toggleFavorite() {
        channel.favorite = !channel.favorite;
        boolean value = channel.favorite;
        heart.setText(value ? "♥" : "♡");
        heart.setTextColor(value ? 0xFFFF728D : Ui.WHITE);
        heart.setContentDescription(value ? "Usuń z ulubionych" : "Dodaj do ulubionych");
        storage.execute(() -> database.setFavorite(channel.id, value));
        showOsd();
    }

    private void toggleOsd() {
        if (osd == null) return;
        if (osd.getVisibility() == View.VISIBLE) {
            osd.setVisibility(View.GONE);
            handler.removeCallbacks(hideOsd);
            if (playerView != null) playerView.requestFocus();
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
                descriptionScroll.requestFocus();
            } else {
                descriptionScroll.setVisibility(View.GONE);
                if (heart != null) heart.requestFocus();
            }
        }
        showOsd();
    }

    /** Boczny panel działa także pilotem bez niebieskiego klawisza. */
    private void toggleSettings() {
        if (settingsPanel != null) {
            root.removeView(settingsPanel);
            settingsPanel = null;
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
        settingsBody.addView(Ui.text(this, "⚙  Ustawienia odtwarzania", 19, Ui.WHITE, true));
        settingsBody.addView(Ui.text(this, "Wybierz aktywną listę • EPG • sterowanie",
                12, Ui.SECONDARY, false), Ui.margins(this, -1, -2, 0, 5, 0, 12));
        SecureSettings settings = new SecureSettings(this);
        settingsBody.addView(Ui.text(this, settings.epgStatus(), 12, Ui.ACCENT, false));
        String active = settings.activeSource();
        settingsBody.addView(Ui.button(this, active.isEmpty() ?
                "✓  Wszystkie listy" : "◯  Wszystkie listy", active.isEmpty(), v -> {
            settings.setActiveSource("");
            toggleSettings();
            toggleSettings();
        }), Ui.margins(this, -1, -2, 0, 13, 0, 5));
        for (ChannelDatabase.Playlist source : database.playlists()) {
            boolean selected = source.source.equals(active);
            settingsBody.addView(Ui.button(this, (selected ? "✓  " : "◯  ") +
                    source.title, selected, v -> {
                settings.setActiveSource(source.source);
                toggleSettings();
                toggleSettings();
            }), Ui.margins(this, -1, -2, 0, 0, 0, 5));
        }
        settingsBody.addView(Ui.button(this, "Zamknij  ✕", false, v -> toggleSettings()),
                Ui.margins(this, -1, -2, 0, 12, 0, 0));
        settingsBody.getChildAt(settingsBody.getChildCount()-1).requestFocus();
        showOsd();
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
        List<Channel> tv = liveChannels();
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
        if (player != null) player.stop();
        android.content.Intent intent = new android.content.Intent(this, PlayerActivity.class);
        intent.putExtra(EXTRA_ID, target.id);
        startActivity(intent);
        overridePendingTransition(0, 0);
        finish();
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

    /** Najpierw przechwytujemy skróty TV, bo wewnętrzny PlayerView może połykać klawisze. */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            boolean shortcut = (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) ||
                    (key >= KeyEvent.KEYCODE_NUMPAD_0 && key <= KeyEvent.KEYCODE_NUMPAD_9) ||
                    key == KeyEvent.KEYCODE_PROG_RED || key == KeyEvent.KEYCODE_PROG_GREEN ||
                    key == KeyEvent.KEYCODE_PROG_YELLOW || key == KeyEvent.KEYCODE_PROG_BLUE ||
                    key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_CHANNEL_DOWN ||
                    key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN ||
                    (numberInput.length() > 0 && (key == KeyEvent.KEYCODE_DPAD_CENTER ||
                            key == KeyEvent.KEYCODE_ENTER));
            if (shortcut && onKeyDown(key, event)) return true;
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
        List<Channel> channels = liveChannels();
        if (ordinal > 0 && ordinal <= channels.size()) switchTo(channels.get(ordinal - 1));
        else Toast.makeText(this, "Brak kanału nr " + ordinal, Toast.LENGTH_SHORT).show();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() == 0) {
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
                case KeyEvent.KEYCODE_PROG_BLUE: toggleSettings(); return true;
                case KeyEvent.KEYCODE_CHANNEL_UP: zap(1); return true;
                case KeyEvent.KEYCODE_CHANNEL_DOWN: zap(-1); return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (settingsPanel == null && !channel.vod &&
                            !isOsdNavigationFocused()) { zap(1); return true; }
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (settingsPanel == null && !channel.vod &&
                            !isOsdNavigationFocused()) { zap(-1); return true; }
                    break;
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    if (numberInput.length() > 0) { commitChannelNumber(); return true; }
                    if (osd.getVisibility() != View.VISIBLE && settingsPanel == null) {
                        showOsd();
                        heart.requestFocus();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (settingsPanel == null && osd.getVisibility() != View.VISIBLE) {
                        showOsd();
                        heart.requestFocus();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (settingsPanel == null && osd.getVisibility() != View.VISIBLE) {
                        toggleDescription();
                        heart.requestFocus();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_BACK:
                    if (settingsPanel != null) { toggleSettings(); return true; }
                    if (descriptionOpen) { toggleDescription(); return true; }
                    if (osd.getVisibility() == View.VISIBLE) { toggleOsd(); return true; }
                    break;
                default: break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean isOsdNavigationFocused() {
        if (osd == null || osd.getVisibility() != View.VISIBLE) return false;
        View focus = getCurrentFocus();
        return focus != null && focus != playerView && focus != root;
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
        if (!channel.vod || player == null) return;
        long pos = Math.max(0, player.getCurrentPosition());
        long dur = Math.max(0, player.getDuration());
        if (dur > 0 && pos > dur - 15000) pos = 0;
        final long lastPosition = pos;
        storage.execute(() -> database.savePlayback(channel.id, lastPosition, dur));
        updateProgressLabel();
    }

    @Override protected void onStart() {
        super.onStart();
        if (player != null) player.play();
    }

    @Override protected void onStop() {
        saveProgress();
        if (player != null) player.pause();
        super.onStop();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        saveProgress();
        if (player != null) {
            player.release();
            player = null;
        }
        storage.shutdown();
        super.onDestroy();
    }
}
