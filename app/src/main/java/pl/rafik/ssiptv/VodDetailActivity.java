package pl.rafik.ssiptv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pl.rafik.ssiptv.data.Channel;
import pl.rafik.ssiptv.data.ChannelDatabase;
import pl.rafik.ssiptv.data.SecureSettings;
import pl.rafik.ssiptv.data.XtreamClient;
import pl.rafik.ssiptv.ui.LogoLoader;
import pl.rafik.ssiptv.ui.Ui;

/** Karta filmu/serialu z okładką, pełnym opisem, sezonami i pamięcią postępu. */
public final class VodDetailActivity extends Activity {
    public static final String EXTRA_ID = "vod_id";
    public static final String EXTRA_SERIES = "series_key";
    public static final String EXTRA_SOURCE = "source_key";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ChannelDatabase database;
    private Channel selected;
    private String seriesId = "";
    private String sourceId = "";
    private LinearLayout page;
    private boolean metadataRequested;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        database = new ChannelDatabase(this);
        seriesId = getIntent().getStringExtra(EXTRA_SERIES);
        sourceId = getIntent().getStringExtra(EXTRA_SOURCE);
        if (seriesId == null) seriesId = "";
        if (sourceId == null) sourceId = "";
        long id = getIntent().getLongExtra(EXTRA_ID, -1);
        for (Channel candidate : database.allChannels()) {
            if (candidate.id == id) { selected = candidate; break; }
        }
        if (selected == null && !seriesId.isEmpty()) {
            for (Channel item : database.allChannels()) {
                if (item.isEpisode() && item.seriesId.equals(seriesId) &&
                        item.source.equals(sourceId)) {
                    selected = new Channel(seriesId, item.group, "", item.logo, "", sourceId);
                    selected.seriesId = seriesId;
                    selected.kind = "SERIES";
                    selected.description = item.description;
                    break;
                }
            }
        }
        if (selected == null) {
            Toast.makeText(this, "Nie znaleziono filmu lub serialu", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (selected.isEpisode()) {
            final Channel episode = selected;
            for (Channel item : database.allChannels()) {
                if (item.isSeries() && item.seriesId.equals(seriesId) &&
                        item.source.equals(sourceId)) { selected = item; break; }
            }
            if (selected == episode) {
                selected = new Channel(seriesId, episode.group, "", episode.logo, "", sourceId);
                selected.kind = "SERIES";
                selected.seriesId = seriesId;
                selected.description = episode.description;
            }
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Ui.BG);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        page = Ui.column(this);
        page.setBackgroundColor(Ui.BG);
        setContentView(page);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });
        page.requestApplyInsets();
        render();
        requestMetadata();
    }

    @Override protected void onResume() {
        super.onResume();
        if (selected != null && page != null) render();
    }

    private void render() {
        page.removeAllViews();
        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));
        header.addView(Ui.button(this, "‹  Wróć", false, v -> finish()));
        TextView brand = Ui.text(this, "SS-IPTV  /  " + (selected.isSeries() ? "SERIALE" : "VOD"),
                13, Ui.ACCENT, true);
        brand.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        header.addView(brand, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1));
        page.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = Ui.column(this);
        body.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 26));
        scroll.addView(body);

        LinearLayout hero = Ui.row(this);
        hero.setGravity(Gravity.TOP);
        hero.setPadding(Ui.dp(this, 13), Ui.dp(this, 16), Ui.dp(this, 13), Ui.dp(this, 17));
        hero.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF174257, 0xFF122C43, 0xFF0C2235}));
        FrameLayout artwork = new FrameLayout(this);
        artwork.setBackground(Ui.rounded(this, 0xFF0A2438, 12));
        TextView artFallback = Ui.text(this, selected.isSeries() ? "▤" : "▶", 54,
                Ui.ACCENT, true);
        artFallback.setGravity(Gravity.CENTER);
        artwork.addView(artFallback, new FrameLayout.LayoutParams(-1, -1));
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artwork.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        LogoLoader.load(poster, selected.logo);
        hero.addView(artwork, new LinearLayout.LayoutParams(Ui.dp(this, 132), Ui.dp(this, 186)));

        LinearLayout texts = Ui.column(this);
        texts.setPadding(Ui.dp(this, 17), Ui.dp(this, 4), 0, 0);
        texts.addView(Ui.text(this, selected.isSeries() ? "SERIAL • SEZONY I ODCINKI" :
                "FILM • VIDEO ON DEMAND", 10, Ui.ACCENT, true));
        TextView name = Ui.text(this, selected.name, 22, Ui.WHITE, true);
        name.setMaxLines(4);
        texts.addView(name, Ui.margins(this, -1, -2, 0, 8, 0, 9));
        texts.addView(Ui.text(this, selected.group, 12, Ui.SECONDARY, false));
        if (!selected.isSeries()) {
            Channel film = currentChannel(selected.id);
            long position = film == null ? selected.playbackMs : film.playbackMs;
            texts.addView(Ui.button(this, position > 0 ?
                    "▶  Kontynuuj od " + formatTime(position) : "▶  Odtwórz film", true,
                    v -> play(selected.id)), Ui.margins(this, -1, -2, 0, 15, 0, 0));
            if (position > 0) texts.addView(Ui.button(this, "↺  Od początku", false, v -> {
                database.savePlayback(selected.id, 0, 0);
                play(selected.id);
            }), Ui.margins(this, -1, -2, 0, 8, 0, 0));
        }
        hero.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(hero);

        body.addView(Ui.text(this, "OPIS", 11, Ui.ACCENT, true),
                Ui.margins(this, -1, -2, 2, 20, 0, 7));
        String description = selected.description;
        if (description == null || description.trim().isEmpty()) description =
                "Dostawca listy nie udostępnił opisu. Otwórz materiał, aby rozpocząć oglądanie.";
        TextView plot = Ui.text(this, description, 15, Ui.WHITE, false);
        plot.setLineSpacing(Ui.dp(this, 4), 1.08f);
        body.addView(plot);

        if (selected.isSeries()) renderSeasons(body);
    }

    private void renderSeasons(LinearLayout body) {
        List<Channel> episodes = seriesEpisodes();
        body.addView(Ui.text(this, "SEZONY I ODCINKI", 17, Ui.WHITE, true),
                Ui.margins(this, -1, -2, 0, 24, 0, 9));
        if (episodes.isEmpty()) {
            body.addView(Ui.text(this,
                    "Pobieranie odcinków… Jeśli serwer nie udostępnia katalogu, pojawi się komunikat.",
                    13, Ui.SECONDARY, false));
            return;
        }
        Map<Integer, List<Channel>> bySeason = new TreeMap<>();
        for (Channel item : episodes) bySeason.computeIfAbsent(item.season,
                key -> new ArrayList<>()).add(item);
        for (Map.Entry<Integer, List<Channel>> season : bySeason.entrySet()) {
            LinearLayout section = Ui.column(this);
            section.setPadding(Ui.dp(this, 13), Ui.dp(this, 14), Ui.dp(this, 13), Ui.dp(this, 14));
            section.setBackground(Ui.rounded(this, Ui.SURFACE, 16));
            String label = season.getKey() == 0 ? "Odcinki specjalne" : "Sezon " + season.getKey();
            section.addView(Ui.text(this, label + "  •  " + season.getValue().size() +
                    " odc.", 18, Ui.ACCENT, true));
            season.getValue().sort((a,b) -> Integer.compare(a.episode,b.episode));
            for (Channel episode : season.getValue()) {
                LinearLayout episodeRow = Ui.row(this);
                episodeRow.setPadding(Ui.dp(this, 7), Ui.dp(this, 12),
                        Ui.dp(this, 5), Ui.dp(this, 10));
                LinearLayout details = Ui.column(this);
                details.addView(Ui.text(this, String.format(Locale.ROOT,
                        "ODCINEK %02d", episode.episode), 10, Ui.ACCENT, true));
                TextView title = Ui.text(this, episode.name, 15, Ui.WHITE, true);
                title.setMaxLines(2);
                details.addView(title);
                if (episode.playbackMs > 0) details.addView(Ui.text(this,
                        "▶ Kontynuuj od " + formatTime(episode.playbackMs),
                        11, Ui.SECONDARY, false));
                else if (episode.lastWatched > 0) details.addView(Ui.text(this,
                        "✓ Obejrzany", 11, Ui.SECONDARY, false));
                episodeRow.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
                episodeRow.addView(Ui.text(this, "▶", 20, Ui.ACCENT, true));
                episodeRow.setBackground(Ui.rounded(this, Ui.CARD, 9));
                episodeRow.setOnClickListener(v -> play(episode.id));
                Ui.tvFocus(episodeRow);
                section.addView(episodeRow, Ui.margins(this, -1, -2, 0, 9, 0, 0));
                if (episode.description != null && !episode.description.trim().isEmpty()) {
                    TextView desc = Ui.text(this, episode.description, 11, Ui.SECONDARY, false);
                    desc.setMaxLines(3);
                    section.addView(desc, Ui.margins(this, -1, -2, 5, 4, 5, 4));
                }
            }
            body.addView(section, Ui.margins(this, -1, -2, 0, 8, 0, 10));
        }
    }

    private List<Channel> seriesEpisodes() {
        List<Channel> result = new ArrayList<>();
        for (Channel item : database.allChannels()) if (item.isEpisode() &&
                item.seriesId.equals(seriesId) && item.source.equals(sourceId)) result.add(item);
        return result;
    }

    private Channel currentChannel(long id) {
        for (Channel item : database.allChannels()) if (item.id == id) return item;
        return null;
    }

    private void requestMetadata() {
        if (metadataRequested || !ChannelDatabase.XTREAM.equals(selected.source)) return;
        metadataRequested = true;
        worker.execute(() -> {
            try {
                XtreamClient.Credentials creds = new SecureSettings(getApplicationContext()).credentials();
                if (creds == null) return;
                XtreamClient api = new XtreamClient(creds);
                if (selected.isSeries()) {
                    List<Channel> episodes = api.fetchSeriesEpisodes(selected);
                    if (!episodes.isEmpty()) database.replaceSeriesEpisodes(
                            selected.source, selected.seriesId, episodes);
                } else {
                    String[] extra = api.fetchMovieMetadata(selected);
                    database.updateMetadata(selected.id, extra[0], extra[1]);
                    Channel updated = currentChannel(selected.id);
                    if (updated != null) selected = updated;
                }
                runOnUiThread(this::render);
            } catch (Exception failure) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Nie udało się odświeżyć informacji VOD: " + failure.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void play(long id) {
        Intent player = new Intent(this, PlayerActivity.class);
        player.putExtra(PlayerActivity.EXTRA_ID, id);
        startActivity(player);
    }

    private static String formatTime(long millis) {
        long minutes = Math.max(0, millis) / 60000;
        return String.format(Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60);
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
