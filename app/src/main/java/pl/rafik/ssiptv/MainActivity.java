package pl.rafik.ssiptv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.util.Log;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pl.rafik.ssiptv.data.Channel;
import pl.rafik.ssiptv.data.ChannelDatabase;
import pl.rafik.ssiptv.data.PlaylistLoader;
import pl.rafik.ssiptv.data.SecureSettings;
import pl.rafik.ssiptv.data.XtreamClient;
import pl.rafik.ssiptv.epg.EpgSync;
import pl.rafik.ssiptv.ui.Ui;
import pl.rafik.ssiptv.ui.LogoLoader;

/** Ekrany główne SS-IPTV. Cały interfejs jest natywny i działa bez WebView. */
public final class MainActivity extends Activity {
    private static final int PICK_PLAYLIST = 4301;
    private static final String TAG = "SS-IPTV-Import";
    private static final String ALL = "Wszystkie";
    private static final String FAVORITES = "Ulubione";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable guideDisplayTick = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            io.execute(() -> {
                Map<Long, ChannelDatabase.Guide> current = database.readGuides();
                runOnUiThread(() -> {
                    guides = current;
                    if (adapter != null) adapter.notifyDataSetChanged();
                });
            });
            uiHandler.postDelayed(this, 60000);
        }
    };
    private ChannelDatabase database;
    private SecureSettings secure;
    private LinearLayout main;
    private LinearLayout content;
    private LinearLayout chips;
    private FrameLayout resultsFrame;
    private TextView sectionCaption;
    private TextView sourceStatus;
    private ChannelAdapter adapter;
    private String tab = "Telewizja";
    private String selectedGroup = ALL;
    private String query = "";
    private String previousTab = "Telewizja";
    private boolean tiled;
    private boolean mediaGrid = true;
    private boolean tvMode;
    private int tvGridColumns = 4;
    private FrameLayout tvRoot;
    private ScrollView tvDrawer;
    private boolean tvCategoryDrawerMode;
    private View tvDim;
    private TextView tvCount;
    private AdapterView<?> tvCollection;
    private List<Channel> allChannels = new ArrayList<>();
    private Map<Long, Integer> liveOrdinals = new HashMap<>();
    private Map<Long, ChannelDatabase.Guide> guides = new HashMap<>();
    private long loadedCatalogVersion = -1;
    private String loadedSource = "";
    private boolean catalogLoading;
    private boolean catalogReloadPending;
    private boolean initialResume = true;
    private boolean weeklyEpgPending;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        database = new ChannelDatabase(this);
        secure = new SecureSettings(this);
        Configuration device = getResources().getConfiguration();
        boolean detectedTv = (device.uiMode & Configuration.UI_MODE_TYPE_MASK) ==
                Configuration.UI_MODE_TYPE_TELEVISION ||
                getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                device.navigation == Configuration.NAVIGATION_DPAD ||
                (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) &&
                        device.orientation == Configuration.ORIENTATION_LANDSCAPE);
        int tvOverride = getPreferences(MODE_PRIVATE).getInt("tv-ui-override", -1);
        tvMode = tvOverride == 1 || (tvOverride == -1 && detectedTv);
        tvGridColumns = TvNavigation.gridColumns(device.screenWidthDp);
        tiled = getPreferences(MODE_PRIVATE).getBoolean(tvMode ? "tv-grid" : "grid", false);
        mediaGrid = getPreferences(MODE_PRIVATE).getBoolean("poster-grid", true);
        showTab("Telewizja");
        reloadData();
        // Cotygodniowy transfer nie może rywalizować z buforem oglądanego TV.
        // Uruchamiamy go dopiero po 30 s, jeśli użytkownik pozostał w katalogu.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing() || isDestroyed() || !hasWindowFocus()) return;
            PlaylistLoader.refreshRemoteSourcesIfDue(this, () -> {
                if (!isFinishing() && !isDestroyed()) {
                    weeklyEpgPending = true;
                    if (hasWindowFocus()) {
                        reloadData();
                        startWeeklyEpgIfPending();
                    }
                }
            });
        }, 30000);
        EpgSync.schedule(this);
        EpgSync.refreshIfStale(this);
        if (state == null) handleExternalPlaylist(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        uiHandler.removeCallbacks(guideDisplayTick);
        uiHandler.postDelayed(guideDisplayTick, 60000);
        if (database == null) return;
        // Powrót z PlayerActivity nie może ponownie budować tysięcy kafelków.
        // Reagujemy tylko na faktyczny import/usunięcie źródła lub zmianę listy.
        if (initialResume) {
            initialResume = false;
            return;
        }
        startWeeklyEpgIfPending();
        String source = secure.activeSource();
        if (!catalogLoading && (loadedCatalogVersion != ChannelDatabase.getCatalogVersion() ||
                !loadedSource.equals(source))) reloadData();
        else if (!allChannels.isEmpty()) {
            if (isCatalogTab() && ("Ulubione".equals(tab) || "Ostatnie".equals(tab)))
                filterChannels();
            else if (adapter != null) adapter.notifyDataSetChanged();
            io.execute(() -> {
                Map<Long, ChannelDatabase.Guide> current = database.readGuides();
                runOnUiThread(() -> {
                    guides = current;
                    if (adapter != null) adapter.notifyDataSetChanged();
                });
            });
        }
    }

    @Override protected void onDestroy() {
        uiHandler.removeCallbacks(guideDisplayTick);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onPause() {
        uiHandler.removeCallbacks(guideDisplayTick);
        super.onPause();
    }

    private void startWeeklyEpgIfPending() {
        if (!weeklyEpgPending) return;
        weeklyEpgPending = false;
        new Thread(() -> EpgSync.refresh(getApplicationContext()),
                "ss-weekly-epg").start();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        LogoLoader.onTrimMemory(level);
    }

    private void reloadData() {
        if (catalogLoading) {
            catalogReloadPending = true;
            return;
        }
        catalogLoading = true;
        io.execute(() -> {
            List<Channel> entries = database.allChannels();
            String active = secure.activeSource();
            long revision = ChannelDatabase.getCatalogVersion();
            if (!active.isEmpty()) entries.removeIf(item -> !active.equals(item.source));
            entries = withSeriesCards(entries, database.seriesFavorites());
            Map<Long, Integer> numbering = new HashMap<>();
            int liveNumber = 0;
            for (Channel item : entries) {
                if (!item.vod && !item.isSeries() && !item.isEpisode())
                    numbering.put(item.id, liveNumber++);
            }
            Map<Long, ChannelDatabase.Guide> programs = database.readGuides();
            List<Channel> catalog = entries;
            runOnUiThread(() -> {
                catalogLoading = false;
                loadedCatalogVersion = revision;
                loadedSource = active;
                allChannels = catalog;
                liveOrdinals = numbering;
                guides = programs;
                if (isCatalogTab()) {
                    updateChips();
                    filterChannels();
                } else if ("EPG".equals(tab)) {
                    showTab("EPG");
                }
                if (catalogReloadPending || loadedCatalogVersion != ChannelDatabase.getCatalogVersion() ||
                        !loadedSource.equals(secure.activeSource())) {
                    catalogReloadPending = false;
                    reloadData();
                }
            });
        });
    }

    /** M3U często zawiera tylko odcinki, więc tworzymy w katalogu kartę serialu. */
    private static List<Channel> withSeriesCards(List<Channel> entries,
                                                 java.util.Set<String> favoriteSeries) {
        List<Channel> result = new ArrayList<>(entries);
        Map<String, Channel> parents = new LinkedHashMap<>();
        for (Channel item : entries) if (item.isSeries())
            parents.put(item.source + "\u0000" + item.seriesId, item);
        for (Channel item : entries) {
            if (!item.isEpisode() || item.seriesId.isEmpty()) continue;
            String key = item.source + "\u0000" + item.seriesId;
            if (parents.containsKey(key)) continue;
            Channel parent = new Channel(item.seriesId, item.group, "", item.logo, "",
                    item.source);
            parent.kind = "SERIES";
            parent.seriesId = item.seriesId;
            parent.description = item.description;
            parent.favorite = favoriteSeries.contains(key);
            parent.id = -(result.size() + 1L);
            parents.put(key, parent);
            result.add(parent);
        }
        return result;
    }

    private void showTab(String name) {
        if (!"Ustawienia".equals(tab) && !"Ustawienia".equals(name)) previousTab = name;
        if (!name.equals(tab) && isCatalogName(name)) {
            selectedGroup = ALL;
            query = "";
        }
        tab = name;
        if (tvMode) {
            showTvScreen(name);
            return;
        }
        main = Ui.column(this);
        main.setBackgroundColor(Ui.BG);
        setContentView(main);
        main.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
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
        main.requestApplyInsets();
        main.addView(brandHeader(), new LinearLayout.LayoutParams(-1, Ui.dp(this, 72)));
        content = Ui.column(this);
        main.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        switch (name) {
            case "Źródła": showSources(); break;
            case "EPG": showGuide(); break;
            case "Ustawienia": showSettings(); break;
            default: showChannels(); break;
        }
        main.addView(bottomNavigation(), new LinearLayout.LayoutParams(-1, Ui.dp(this, 68)));
    }

    /** Układ 10-foot: cały ekran na treść, wysuwana na żądanie nawigacja z lewej. */
    private void showTvScreen(String name) {
        main = Ui.column(this);
        main.setBackgroundColor(Ui.BG);
        main.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14));
        setContentView(main);
        tvRoot = new FrameLayout(this);
        main.addView(tvRoot, new LinearLayout.LayoutParams(-1, -1));
        tvDrawer = null;
        tvDim = null;
        tvCategoryDrawerMode = false;
        LinearLayout layout = Ui.row(this);
        layout.setGravity(Gravity.TOP);
        tvRoot.addView(layout, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout rail = Ui.column(this);
        rail.setPadding(0, Ui.dp(this, 16), Ui.dp(this, 8), 0);
        TextView monogram = Ui.text(this, "SS", 22, Ui.ACCENT, true);
        monogram.setGravity(Gravity.CENTER);
        monogram.setBackground(Ui.rounded(this, Ui.SURFACE, 13));
        rail.addView(monogram, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
        TextView brand = Ui.text(this, "IPTV", 10, Ui.MUTED, true);
        brand.setGravity(Gravity.CENTER);
        brand.setLetterSpacing(0.16f);
        rail.addView(brand, Ui.margins(this, -1, 21, 0, 2, 0, 20));
        LinearLayout menu = Ui.column(this);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("Otwórz menu kategorii i sekcji");
        menu.setBackground(Ui.rounded(this, Ui.SURFACE, 12));
        ImageView menuIcon = new ImageView(this);
        menuIcon.setImageResource(R.drawable.ic_nav_menu);
        menuIcon.setColorFilter(Ui.ACCENT);
        menu.addView(menuIcon, new LinearLayout.LayoutParams(Ui.dp(this, 25), Ui.dp(this, 25)));
        TextView menuLabel = Ui.text(this, "MENU", 9, Ui.SECONDARY, true);
        menuLabel.setGravity(Gravity.CENTER);
        menuLabel.setLetterSpacing(0.06f);
        menu.addView(menuLabel, Ui.margins(this, -1, 19, 0, 2, 0, 0));
        menu.setOnClickListener(v -> openTvDrawer());
        Ui.tvFocus(menu);
        rail.addView(menu, new LinearLayout.LayoutParams(-1, Ui.dp(this, 66)));
        layout.addView(rail, new LinearLayout.LayoutParams(Ui.dp(this, 72), -1));

        content = Ui.column(this);
        content.setPadding(Ui.dp(this, 13), 0, 0, 0);
        layout.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        tvCount = null;
        tvCollection = null;
        chips = null;
        resultsFrame = null;
        if (isCatalogTab()) showTvChannels();
        else if ("Źródła".equals(name)) showSources();
        else if ("EPG".equals(name)) showGuide();
        else if ("Ustawienia".equals(name)) showSettings();

        tvDim = new View(this);
        tvDim.setBackgroundColor(0xB6000710);
        tvDim.setOnClickListener(v -> closeTvDrawer());
        tvDim.setVisibility(View.GONE);
        tvRoot.addView(tvDim, new FrameLayout.LayoutParams(-1, -1));
        tvDrawer = new ScrollView(this);
        tvDrawer.setFillViewport(false);
        tvDrawer.setVerticalScrollBarEnabled(false);
        tvDrawer.setBackground(Ui.rounded(this, 0xFF172231, 17));
        tvDrawer.setVisibility(View.GONE);
        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(
                Math.min(Ui.dp(this, 354),
                        getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 65)),
                -1, Gravity.LEFT);
        tvRoot.addView(tvDrawer, drawerParams);
        if (isCatalogTab() && tvCollection != null && adapter != null &&
                adapter.getCount() > 0) focusTvCollection();
        else menu.requestFocus();
    }

    private void showTvChannels() {
        boolean compactTv = tvMode &&
                getResources().getConfiguration().screenHeightDp < 500;
        if (isMediaTab()) {
            LinearLayout showcase = Ui.column(this);
            showcase.setPadding(Ui.dp(this, compactTv ? 12 : 20),
                    Ui.dp(this, compactTv ? 7 : 15),
                    Ui.dp(this, compactTv ? 12 : 20), Ui.dp(this, compactTv ? 7 : 14));
            showcase.setBackground(Ui.outlined(this, 0xFF162838, 0xFF345366, 16));
            if (!compactTv) {
                TextView eyebrow = Ui.text(this, "SS  /  BIBLIOTEKA", 11, Ui.ACCENT, true);
                eyebrow.setLetterSpacing(0.19f);
                showcase.addView(eyebrow);
            }
            showcase.addView(Ui.text(this, "VOD".equals(tab) ?
                    "Odkrywaj filmy" : "Odkrywaj seriale", compactTv ? 22 : 30, Ui.WHITE, true),
                    Ui.margins(this, -1, -2, 0, compactTv ? 0 : 4, 0, 0));
            if (!compactTv) showcase.addView(Ui.text(this,
                    "Wybierz gatunek, odkryj okładkę, wróć do oglądania.",
                    13, Ui.SECONDARY, false), Ui.margins(this, -1, -2, 0, 4, 0, 0));
            content.addView(showcase, Ui.margins(this, -1, -2, 0, 0, 0, compactTv ? 4 : 10));
            content.addView(Ui.text(this, "GATUNKI I KOLEKCJE", 12, Ui.SECONDARY, true),
                    Ui.margins(this, -1, compactTv ? 18 : 24, 4, 0, 0, compactTv ? 2 : 6));
            HorizontalScrollView categories = new HorizontalScrollView(this);
            categories.setHorizontalScrollBarEnabled(false);
            categories.setFillViewport(false);
            chips = Ui.row(this);
            categories.addView(chips);
            content.addView(categories, Ui.margins(this, -1, compactTv ? 40 : 52,
                    0, 0, 0, compactTv ? 4 : 12));
            updateChips();
        }
        if ("Telewizja".equals(tab)) {
            LinearLayout filters = Ui.row(this);
            TextView categoriesButton = Ui.text(this,
                    "KATEGORIE  /  " + selectedGroup + "  ▾", 13, Ui.ACCENT, true);
            categoriesButton.setGravity(Gravity.CENTER_VERTICAL);
            categoriesButton.setPadding(Ui.dp(this, 13), 0, Ui.dp(this, 13), 0);
            categoriesButton.setBackground(Ui.rounded(this, Ui.SURFACE, 11));
            categoriesButton.setOnClickListener(v -> {
                openTvDrawer();
                tvCategoryDrawerMode = true;
                populateTvDrawer();
            });
            Ui.tvFocus(categoriesButton);
            filters.addView(categoriesButton, Ui.margins(this, -2, 42, 0, 0, 9, 0));

            HorizontalScrollView categories = new HorizontalScrollView(this);
            categories.setHorizontalScrollBarEnabled(false);
            chips = Ui.row(this);
            categories.addView(chips);
            filters.addView(categories, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
            content.addView(filters, Ui.margins(this, -1, 42, 0, 0, 0, compactTv ? 4 : 9));
            updateChips();
        }
        LinearLayout titleRow = Ui.row(this);
        String heading = "Telewizja".equals(tab) ? "TELEWIZJA" :
                "VOD".equals(tab) ? "FILMY VOD" :
                "Seriale".equals(tab) ? "SERIALE" :
                "Ulubione".equals(tab) ? "ULUBIONE" : "OSTATNIO OGLĄDANE";
        TextView title = Ui.text(this, heading, compactTv ? 19 : 25, Ui.WHITE, true);
        titleRow.addView(title);
        tvCount = Ui.text(this, "", 14, Ui.ACCENT, true);
        tvCount.setPadding(Ui.dp(this, 20), 0, 0, 0);
        titleRow.addView(tvCount, new LinearLayout.LayoutParams(0, -2, 1));
        if ("Telewizja".equals(tab)) {
            TextView listToggle = tvModeToggle("Lista", !isTiled(), () -> setTiled(false));
            TextView gridToggle = tvModeToggle("Kafelki", isTiled(), () -> setTiled(true));
            titleRow.addView(listToggle, Ui.margins(this, -2, 37, 0, 0, 6, 0));
            titleRow.addView(gridToggle, Ui.margins(this, -2, 37, 0, 0, 0, 0));
        } else {
            TextView hint = Ui.text(this, isMediaTab() ?
                    "OK  Szczegóły     •     ←  Menu" :
                    "←  Menu i kategorie     •     OK  Odtwórz", 12,
                    Ui.SECONDARY, false);
            titleRow.addView(hint);
        }
        content.addView(titleRow, Ui.margins(this, -1, compactTv ? 44 : 62, 0, 0, 0, 5));
        resultsFrame = new FrameLayout(this);
        content.addView(resultsFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        renderCollection();
    }

    private TextView tvModeToggle(String label, boolean selected, Runnable action) {
        TextView toggle = Ui.text(this, label, 12, selected ? Ui.BG : Ui.WHITE, selected);
        toggle.setGravity(Gravity.CENTER);
        toggle.setPadding(Ui.dp(this, 15), 0, Ui.dp(this, 15), 0);
        toggle.setBackground(Ui.rounded(this, selected ? Ui.ACCENT : Ui.SURFACE, 10));
        toggle.setOnClickListener(v -> action.run());
        Ui.tvFocus(toggle);
        return toggle;
    }

    private void openTvDrawer() {
        if (!tvMode || tvDrawer == null || tvDrawer.getVisibility() == View.VISIBLE) return;
        tvCategoryDrawerMode = false;
        tvDim.setVisibility(View.VISIBLE);
        tvDrawer.setVisibility(View.VISIBLE);
        populateTvDrawer();
    }

    private void populateTvDrawer() {
        LinearLayout items = Ui.column(this);
        items.setPadding(Ui.dp(this, 18), Ui.dp(this, 21), Ui.dp(this, 18),
                Ui.dp(this, 24));
        tvDrawer.removeAllViews();
        tvDrawer.addView(items);
        LinearLayout identity = Ui.row(this);
        TextView mark = Ui.text(this, "SS", 19, Ui.ACCENT, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(Ui.rounded(this, Ui.CARD, 10));
        identity.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        LinearLayout brandText = Ui.column(this);
        brandText.setPadding(Ui.dp(this, 12), 0, 0, 0);
        brandText.addView(Ui.text(this, "SS IPTV", 22, Ui.WHITE, true));
        TextView detail = Ui.text(this, "TWOJA TELEWIZJA", 10, Ui.SECONDARY, true);
        detail.setLetterSpacing(0.13f);
        brandText.addView(detail);
        identity.addView(brandText);
        items.addView(identity, Ui.margins(this, -1, 65, 0, 0, 0, 15));
        View divider = new View(this);
        divider.setBackgroundColor(0xFF2E3B4A);
        items.addView(divider, Ui.margins(this, -1, 1, 0, 0, 0, 15));
        View initialFocus = null;
        if (tvCategoryDrawerMode && isCatalogTab()) {
            View back = tvDrawerItem("Wróć do menu", R.drawable.ic_nav_menu, false, () -> {
                tvCategoryDrawerMode = false;
                populateTvDrawer();
            });
            items.addView(back, Ui.margins(this, -1, 50, 0, 0, 0, 8));
            items.addView(Ui.text(this, "KATEGORIE   /   " + tab.toUpperCase(Locale.ROOT),
                    11, Ui.SECONDARY, true),
                    Ui.margins(this, -1, 34, 0, 0, 0, 6));
            for (String group : tvCategoryNames()) {
                boolean selected = group.equals(selectedGroup);
                View item = tvDrawerItem(group, 0, selected, () -> {
                    selectedGroup = group;
                    closeTvDrawer();
                    filterChannels();
                });
                items.addView(item, Ui.margins(this, -1, 46, 0, 1, 0, 1));
                if (selected) initialFocus = item;
            }
        } else {
            items.addView(Ui.text(this, "ODKRYWAJ", 11, Ui.SECONDARY, true),
                    Ui.margins(this, -1, -2, 0, 0, 0, 9));
            if (isCatalogTab()) {
                View categories = tvDrawerItem("Kategorie  ·  " + selectedGroup,
                        R.drawable.ic_nav_categories, true, () -> {
                    tvCategoryDrawerMode = true;
                    populateTvDrawer();
                });
                items.addView(categories, Ui.margins(this, -1, 53, 0, 2, 0, 12));
                initialFocus = categories;
            }
            final String[] tabs = {"Telewizja", "VOD", "Seriale", "Ulubione", "Ostatnie",
                    "EPG", "Źródła", "Ustawienia"};
            final int[] icons = {R.drawable.ic_nav_tv, R.drawable.ic_nav_movies,
                    R.drawable.ic_nav_series, R.drawable.ic_nav_heart,
                    R.drawable.ic_nav_history, R.drawable.ic_nav_epg,
                    R.drawable.ic_nav_sources, R.drawable.ic_nav_settings};
            for (int i = 0; i < tabs.length; i++) {
                String destination = tabs[i];
                View item = tvDrawerItem(destination, icons[i],
                        destination.equals(tab), () -> showTab(destination));
                items.addView(item, Ui.margins(this, -1, 48, 0, 1, 0, 1));
                if (initialFocus == null && destination.equals(tab)) initialFocus = item;
            }
            if (isCatalogTab()) {
                items.addView(Ui.text(this, "SPOSÓB WYŚWIETLANIA", 11, Ui.SECONDARY, true),
                        Ui.margins(this, -1, 35, 0, 13, 0, 4));
                items.addView(tvDrawerItem("Lista kanałów", R.drawable.ic_nav_list,
                        !isTiled(), () -> setTiled(false)),
                        Ui.margins(this, -1, 46, 0, 1, 0, 1));
                items.addView(tvDrawerItem("Kafelki", R.drawable.ic_nav_categories,
                        isTiled(), () -> setTiled(true)),
                        Ui.margins(this, -1, 46, 0, 1, 0, 1));
            }
            items.addView(tvDrawerItem("Wyjdź z aplikacji", R.drawable.ic_nav_menu,
                    false, this::exitApp), Ui.margins(this, -1, 48, 0, 16, 0, 0));
        }
        if (initialFocus != null) initialFocus.requestFocus();
    }

    private View tvDrawerItem(String caption, int iconResource, boolean selected, Runnable action) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 10), 0);
        row.setBackground(Ui.rounded(this, selected ? 0xFF293D4A : 0x001B2736, 9));
        View active = new View(this);
        active.setBackground(Ui.rounded(this, selected ? Ui.ACCENT : Color.TRANSPARENT, 4));
        row.addView(active, new LinearLayout.LayoutParams(Ui.dp(this, 3), Ui.dp(this, 26)));
        if (iconResource != 0) {
            ImageView symbol = new ImageView(this);
            symbol.setImageResource(iconResource);
            symbol.setColorFilter(selected ? Ui.ACCENT : Ui.SECONDARY);
            LinearLayout.LayoutParams iconParams = Ui.margins(this, 21, 21, 13, 0, 12, 0);
            row.addView(symbol, iconParams);
        }
        TextView label = Ui.text(this, caption, 15,
                selected ? Ui.WHITE : Ui.SECONDARY, selected);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0, -1, 1));
        row.setContentDescription(caption);
        row.setOnClickListener(v -> action.run());
        Ui.tvFocus(row);
        return row;
    }

    private List<String> tvCategoryNames() {
        ArrayList<String> names = new ArrayList<>();
        for (Channel channel : allChannels) {
            if ("Telewizja".equals(tab) && channel.vod) continue;
            if ("VOD".equals(tab) && !channel.isMovie()) continue;
            if ("Seriale".equals(tab) && !channel.isSeries()) continue;
            if ("Ulubione".equals(tab) && !channel.favorite) continue;
            if ("Ostatnie".equals(tab) && channel.lastWatched == 0) continue;
            if (!names.contains(channel.group)) names.add(channel.group);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        names.add(0, ALL);
        return names;
    }

    private void closeTvDrawer() {
        if (tvDrawer == null) return;
        tvDrawer.setVisibility(View.GONE);
        tvDim.setVisibility(View.GONE);
        focusTvCollection();
    }

    private void focusTvCollection() {
        if (tvCollection != null && adapter != null && adapter.getCount() > 0) {
            tvCollection.setFocusableInTouchMode(true);
            tvCollection.requestFocus();
            if (tvCollection.getSelectedItemPosition() < 0) tvCollection.setSelection(0);
        }
    }

    private View brandHeader() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 13), Ui.dp(this, 8), Ui.dp(this, 13), Ui.dp(this, 8));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bar.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));

        LinearLayout titles = Ui.column(this);
        titles.setPadding(Ui.dp(this, 10), 0, 0, 0);
        titles.addView(Ui.text(this, "SS-IPTV", 20, Ui.WHITE, true));
        TextView slogan = Ui.text(this, "TWOJA TELEWIZJA", 9, Ui.ACCENT, true);
        slogan.setLetterSpacing(0.08f);
        titles.addView(slogan);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));

        TextView source = Ui.text(this, "Źródła", 12, Ui.BG, true);
        source.setGravity(Gravity.CENTER);
        source.setPadding(Ui.dp(this, 11), Ui.dp(this, 10), Ui.dp(this, 11), Ui.dp(this, 10));
        source.setBackground(Ui.rounded(this, Ui.ACCENT, 11));
        source.setContentDescription("Dodaj plik M3U lub zaloguj się do IPTV");
        source.setOnClickListener(v -> showTab("Źródła"));
        Ui.tvFocus(source);
        bar.addView(source);

        ImageView settings = new ImageView(this);
        settings.setImageResource(R.drawable.ic_nav_settings);
        settings.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        settings.setContentDescription("Ustawienia aplikacji");
        settings.setOnClickListener(v -> showTab("Ustawienia"));
        Ui.tvFocus(settings);
        bar.addView(settings, Ui.margins(this, 42, 46, 7, 0, 0, 0));
        TextView exit = Ui.text(this, "Wyjdź", 12, Ui.SECONDARY, true);
        exit.setGravity(Gravity.CENTER);
        exit.setOnClickListener(v -> exitApp());
        Ui.tvFocus(exit);
        bar.addView(exit, Ui.margins(this, 64, 46, 5, 0, 0, 0));
        return bar;
    }

    private View bottomNavigation() {
        LinearLayout bottom = Ui.row(this);
        bottom.setPadding(Ui.dp(this, 9), Ui.dp(this, 4), Ui.dp(this, 9), Ui.dp(this, 5));
        bottom.setBackgroundColor(Ui.SURFACE);
        String[] names = {"Telewizja", "VOD", "Seriale", "Ulubione", "Ostatnie", "Źródła"};
        int[] icons = {R.drawable.ic_nav_tv, R.drawable.ic_nav_movies,
                R.drawable.ic_nav_series, R.drawable.ic_nav_heart,
                R.drawable.ic_nav_history, R.drawable.ic_nav_sources};
        for (int i = 0; i < names.length; i++) {
            final String itemName = names[i];
            boolean chosen = itemName.equals(tab);
            LinearLayout item = Ui.column(this);
            item.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            icon.setImageTintList(ColorStateList.valueOf(chosen ? Ui.ACCENT : Ui.MUTED));
            TextView label = Ui.text(this, itemName, 10, chosen ? Ui.ACCENT : Ui.SECONDARY, chosen);
            label.setGravity(Gravity.CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
            item.addView(label);
            item.setOnClickListener(v -> showTab(itemName));
            Ui.tvFocus(item);
            bottom.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
        return bottom;
    }

    private boolean isCatalogTab() {
        return isCatalogName(tab);
    }

    private static boolean isCatalogName(String name) {
        return "Telewizja".equals(name) || "VOD".equals(name) || "Seriale".equals(name) ||
                "Ulubione".equals(name) || "Ostatnie".equals(name);
    }

    private boolean isMediaTab() {
        return "VOD".equals(tab) || "Seriale".equals(tab);
    }

    private boolean isTiled() { return isMediaTab() ? mediaGrid : tiled; }

    private void showChannels() {
        LinearLayout home = Ui.column(this);
        home.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        content.addView(home, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout top = Ui.row(this);
        String headline = "Telewizja".equals(tab) ? "Telewizja na żywo" :
                "VOD".equals(tab) ? "Biblioteka filmów" :
                "Seriale".equals(tab) ? "Twoje seriale" :
                "Ulubione".equals(tab) ? "Twoje ulubione" : "Ostatnio oglądane";
        TextView title = Ui.text(this, headline, 22, Ui.WHITE, true);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView guide = Ui.text(this, "EPG ›", 12, Ui.ACCENT, true);
        guide.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        guide.setOnClickListener(v -> showTab("EPG"));
        top.addView(guide);
        home.addView(top, Ui.margins(this, -1, 42, 0, 0, 0, 2));
        String subline = "Telewizja".equals(tab) ? "Kanały, kategorie i program na żywo" :
                "VOD".equals(tab) ? "Okładki, opisy i kontynuowanie oglądania" :
                "Seriale".equals(tab) ? "Sezony, odcinki i zapis postępu każdego odcinka" :
                "Ulubione".equals(tab) ? "Ulubione kanały i filmy w jednym miejscu" :
                "Twoja historia odtwarzania";
        home.addView(Ui.text(this, subline, 12, Ui.SECONDARY, false),
                Ui.margins(this, -1, -2, 0, 0, 0, 12));

        EditText search = input("⌕  Szukaj kanału lub programu", false);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                filterChannels();
            }
            @Override public void afterTextChanged(Editable e) {}
        });
        if (!query.isEmpty()) search.setText(query);
        home.addView(search, Ui.margins(this, -1, 48, 0, 0, 0, 11));

        HorizontalScrollView categories = new HorizontalScrollView(this);
        categories.setHorizontalScrollBarEnabled(false);
        chips = Ui.row(this);
        categories.addView(chips);
        if ("Telewizja".equals(tab) || "VOD".equals(tab) || "Seriale".equals(tab))
            home.addView(categories, Ui.margins(this, -1, 48, 0, 0, 0, 5));
        updateChips();

        LinearLayout heading = Ui.row(this);
        sectionCaption = Ui.text(this, "0 kanałów", 12, Ui.ACCENT, true);
        heading.addView(sectionCaption, new LinearLayout.LayoutParams(0, -2, 1));
        TextView list = Ui.text(this, "Lista", 12, isTiled() ? Ui.SECONDARY : Ui.ACCENT, !isTiled());
        list.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        list.setBackground(Ui.rounded(this, Ui.SURFACE, 10));
        heading.addView(list, Ui.margins(this, -2, -2, 0, 0, 6, 0));
        TextView tiles = Ui.text(this, "Kafelki", 12, isTiled() ? Ui.ACCENT : Ui.SECONDARY, isTiled());
        tiles.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        tiles.setBackground(Ui.rounded(this, Ui.SURFACE, 10));
        heading.addView(tiles);
        list.setOnClickListener(v -> setTiled(false));
        tiles.setOnClickListener(v -> setTiled(true));
        home.addView(heading, Ui.margins(this, -1, 42, 0, 0, 0, 4));

        resultsFrame = new FrameLayout(this);
        home.addView(resultsFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        renderCollection();
    }

    private void setTiled(boolean enabled) {
        if (isTiled() == enabled) {
            if (tvMode) showTab(tab);
            return;
        }
        if (isMediaTab()) mediaGrid = enabled;
        else tiled = enabled;
        getPreferences(MODE_PRIVATE).edit()
                .putBoolean(isMediaTab() ? "poster-grid" : tvMode ? "tv-grid" : "grid", enabled).apply();
        showTab(tab);
    }

    private void renderCollection() {
        if (resultsFrame == null) return;
        resultsFrame.removeAllViews();
        adapter = new ChannelAdapter();
        android.widget.AdapterView<?> collection;
        if (isTiled()) {
            GridView grid = new GridView(this);
            grid.setNumColumns(tvMode ? (isMediaTab() ? Math.min(tvGridColumns, 5) : tvGridColumns) :
                    getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 4 : 2);
            grid.setHorizontalSpacing(Ui.dp(this, tvMode ? 16 : 9));
            grid.setVerticalSpacing(Ui.dp(this, tvMode ? 16 : 9));
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            grid.setClipToPadding(false);
            grid.setPadding(Ui.dp(this, tvMode ? 5 : 0), Ui.dp(this, 4),
                    Ui.dp(this, tvMode ? 7 : 0), Ui.dp(this, 4));
            grid.setAdapter(adapter);
            collection = grid;
        } else {
            ListView list = new ListView(this);
            list.setDivider(null);
            list.setDividerHeight(Ui.dp(this, tvMode ? 9 : 7));
            list.setSelector(android.R.color.transparent);
            list.setCacheColorHint(Ui.BG);
            list.setVerticalScrollBarEnabled(false);
            list.setAdapter(adapter);
            collection = list;
        }
        if (tvMode) {
            AbsListView items = (AbsListView) collection;
            items.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            if (items instanceof ListView) ((ListView) items).setItemsCanFocus(false);
            items.setSelector(Ui.tvSelection(this));
            items.setDrawSelectorOnTop(true);
            items.setFocusable(true);
            items.setFocusableInTouchMode(true);
            tvCollection = collection;
        }
        collection.setOnItemClickListener((parent, view, position, id) ->
                openContent(adapter.visible.get(position)));
        resultsFrame.addView((View) collection, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout empty = Ui.column(this);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(this, 26), Ui.dp(this, 18), Ui.dp(this, 26), Ui.dp(this, 18));
        empty.addView(Ui.text(this, "Tu jeszcze nic nie ma", 18, Ui.WHITE, true));
        TextView guidance = Ui.text(this,
                allChannels.isEmpty() ? "Dodaj plik M3U albo zaloguj się do serwera IPTV." :
                        "Nie znaleziono pozycji w tym widoku.", 13, Ui.SECONDARY, false);
        empty.addView(guidance, Ui.margins(this, -1, -2, 0, 9, 0, 14));
        empty.addView(Ui.button(this, "Dodaj źródło M3U / IPTV", true,
                v -> showTab("Źródła")));
        resultsFrame.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        collection.setEmptyView(empty);
        filterChannels();
    }

    private void updateChips() {
        if (chips == null || !isCatalogTab()) return;
        chips.removeAllViews();
        Map<String, Integer> groups = new LinkedHashMap<>();
        for (Channel channel : allChannels) {
            if ("Telewizja".equals(tab) && channel.vod) continue;
            if ("VOD".equals(tab) && !channel.isMovie()) continue;
            if ("Seriale".equals(tab) && !channel.isSeries()) continue;
            groups.put(channel.group, groups.getOrDefault(channel.group, 0) + 1);
        }
        ArrayList<String> names = new ArrayList<>(groups.keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        ArrayList<String> filterNames = new ArrayList<>();
        filterNames.add(ALL);
        filterNames.addAll(names);
        if (!filterNames.contains(selectedGroup)) selectedGroup = ALL;
        for (String name : filterNames) {
            boolean selected = name.equals(selectedGroup);
            TextView chip = Ui.text(this, name, 12, selected ? Ui.BG : Ui.SECONDARY, selected);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(this, 13), 0, Ui.dp(this, 13), 0);
            chip.setBackground(Ui.rounded(this, selected ? Ui.ACCENT : Ui.SURFACE, 50));
            chip.setOnClickListener(v -> {
                selectedGroup = name;
                updateChips();
                filterChannels();
                if (tvMode) for (int i = 0; i < chips.getChildCount(); i++) {
                    View view = chips.getChildAt(i);
                    if (view instanceof TextView &&
                            name.contentEquals(((TextView) view).getText())) {
                        view.requestFocus();
                        break;
                    }
                }
            });
            Ui.tvFocus(chip);
            chips.addView(chip, Ui.margins(this, -2, 34, 0, 0, 8, 0));
        }
    }

    private void filterChannels() {
        if (adapter == null || !isCatalogTab()) return;
        ArrayList<Channel> result = new ArrayList<>();
        for (Channel c : allChannels) {
            if ("Telewizja".equals(tab) && c.vod) continue;
            if ("VOD".equals(tab) && !c.isMovie()) continue;
            if ("Seriale".equals(tab) && !c.isSeries()) continue;
            if ("Ostatnie".equals(tab) && c.isSeries() && c.id < 0) continue;
            if ("Ulubione".equals(tab) && !c.favorite) continue;
            if ("Ostatnie".equals(tab) && c.lastWatched == 0) continue;
            if (!ALL.equals(selectedGroup) &&
                    !c.group.equals(selectedGroup)) continue;
            ChannelDatabase.Guide guide = guides.get(c.id);
            String prog = guide == null || guide.current == null ? "" : guide.current;
            if (!query.isEmpty() && !c.name.toLowerCase(Locale.ROOT).contains(query) &&
                    !prog.toLowerCase(Locale.ROOT).contains(query)) continue;
            result.add(c);
        }
        if ("Ostatnie".equals(tab)) {
            result.sort((left, right) -> Long.compare(right.lastWatched, left.lastWatched));
        }
        adapter.visible = result;
        adapter.notifyDataSetChanged();
        if (sectionCaption != null) sectionCaption.setText(result.size() + " pozycji");
        if (tvCount != null) tvCount.setText(result.size() + " pozycji  •  " + selectedGroup);
        if (tvMode && tvCollection != null && result.size() > 0) {
            if (tvCollection.getSelectedItemPosition() < 0 ||
                    tvCollection.getSelectedItemPosition() >= result.size()) {
                tvCollection.setSelection(0);
            }
            if (tvDrawer != null && tvDrawer.getVisibility() != View.VISIBLE &&
                    !tvCollection.hasFocus()) tvCollection.post(this::focusTvCollection);
        }
    }

    private ScrollView scrollPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        return scroll;
    }

    private LinearLayout paddedPage(ScrollView scroll) {
        LinearLayout column = Ui.column(this);
        column.setPadding(Ui.dp(this, 17), Ui.dp(this, 13), Ui.dp(this, 17), Ui.dp(this, 22));
        scroll.addView(column);
        return column;
    }

    private LinearLayout card() {
        LinearLayout card = Ui.column(this);
        card.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18));
        card.setBackground(Ui.outlined(this, Ui.SURFACE, 0xFF21445B, 20));
        return card;
    }

    private void showSources() {
        LinearLayout page = paddedPage(scrollPage());
        page.addView(Ui.text(this, "Dodaj swoje źródła", 25, Ui.WHITE, true));
        page.addView(Ui.text(this, "Kanały zapiszą się w pamięci urządzenia. Ty kontrolujesz dostęp.",
                13, Ui.SECONDARY, false), Ui.margins(this, -1, -2, 0, 6, 0, 20));

        LinearLayout m3u = card();
        m3u.addView(Ui.text(this, "◈  Playlista M3U", 19, Ui.WHITE, true));
        m3u.addView(Ui.text(this, "Import z pamięci telefonu lub z własnego linku. Kategorie są odczytywane automatycznie.",
                13, Ui.SECONDARY, false), Ui.margins(this, -1, -2, 0, 8, 0, 10));
        TextView importButton = Ui.button(this, "Wybierz plik M3U", true,
                v -> choosePlaylistFile(false));
        m3u.addView(importButton, Ui.margins(this, -1, -2, 0, 3, 0, 12));
        m3u.addView(Ui.button(this, "Android Box: alternatywny wybór pliku", false,
                v -> choosePlaylistFile(true)), Ui.margins(this, -1, -2, 0, 0, 0, 10));
        m3u.addView(Ui.text(this, "Możesz też otworzyć plik .m3u w menedżerze plików, " +
                "wybrać «Otwórz za pomocą» i wskazać SS-IPTV.", 12, Ui.SECONDARY, false),
                Ui.margins(this, -1, -2, 0, 0, 0, 13));
        m3u.addView(Ui.text(this, "LUB WKLEJ ADRES PLAYLISTY", 10, Ui.MUTED, true));
        EditText m3uUrl = input("https://twoj-serwer/lista.m3u", false);
        m3uUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        m3u.addView(m3uUrl, Ui.margins(this, -1, 48, 0, 8, 0, 9));
        m3u.addView(Ui.button(this, "Pobierz i zapisz M3U", false, v -> {
            String url = m3uUrl.getText().toString().trim();
            performTask("Pobieranie playlisty M3U…", () -> {
                int total = PlaylistLoader.importWeb(getApplicationContext(), url).size();
                return "Zapisano lokalnie " + total + " kanałów M3U";
            }, true);
        }));
        page.addView(m3u, Ui.margins(this, -1, -2, 0, 0, 0, 16));

        LinearLayout xtream = card();
        xtream.addView(Ui.text(this, "✦  Logowanie IPTV", 19, Ui.WHITE, true));
        xtream.addView(Ui.text(this, "Dostęp zgodny z API Xtream: adres serwera, login i hasło.",
                13, Ui.SECONDARY, false), Ui.margins(this, -1, -2, 0, 8, 0, 10));
        xtream.addView(Ui.text(this, "ADRES SERWERA", 10, Ui.MUTED, true));
        EditText server = input("https://serwer.example:port", false);
        String savedServer = secure.serverName();
        if (!savedServer.isEmpty()) server.setText(savedServer);
        xtream.addView(server, Ui.margins(this, -1, 48, 0, 7, 0, 12));
        xtream.addView(Ui.text(this, "LOGIN", 10, Ui.MUTED, true));
        EditText user = input("Nazwa użytkownika", false);
        xtream.addView(user, Ui.margins(this, -1, 48, 0, 7, 0, 12));
        xtream.addView(Ui.text(this, "HASŁO", 10, Ui.MUTED, true));
        EditText password = input("Hasło konta", true);
        xtream.addView(password, Ui.margins(this, -1, 48, 0, 7, 0, 13));
        xtream.addView(Ui.button(this, "Połącz i pobierz kanały  →", true, v -> {
            String address = server.getText().toString().trim();
            String username = user.getText().toString().trim();
            String pass = password.getText().toString();
            if (username.isEmpty() || pass.isEmpty()) {
                toast("Podaj login i hasło");
                return;
            }
            Runnable connect = () -> performTask("Łączenie z serwerem IPTV…", () -> {
                String normalized = XtreamClient.normalizeServer(address);
                XtreamClient.Credentials credentials = new XtreamClient.Credentials(
                        normalized, username, pass);
                List<Channel> items = new XtreamClient(credentials).fetchChannels();
                XtreamClient.Credentials oldAccount = null;
                try { oldAccount = secure.credentials(); } catch (Exception ignored) { }
                if (oldAccount != null && (!oldAccount.server.equals(normalized) ||
                        !oldAccount.username.equals(username))) {
                    database.deleteSource(ChannelDatabase.XTREAM);
                }
                database.replaceSource(ChannelDatabase.XTREAM, items);
                database.savePlaylist(ChannelDatabase.XTREAM, "IPTV • " + normalized, "");
                secure.saveServer(normalized, username, pass);
                return "Pobrano " + items.size() + " pozycji TV i VOD";
            }, true);
            if (address.startsWith("http://")) {
                new AlertDialog.Builder(this)
                        .setTitle("Połączenie bez szyfrowania")
                        .setMessage("Serwer używa HTTP. Login i hasło mogą być przesyłane bez szyfrowania. Kontynuować?")
                        .setNegativeButton("Anuluj", null)
                        .setPositiveButton("Rozumiem", (dialog, which) -> connect.run())
                        .show();
            } else connect.run();
        }), Ui.margins(this, -1, -2, 0, 0, 0, 10));
        if (!savedServer.isEmpty()) {
            xtream.addView(Ui.button(this, "Odśwież zapisane konto", false, v ->
                    performTask("Odświeżanie konta…", () -> {
                        XtreamClient.Credentials creds = secure.credentials();
                        if (creds == null) throw new IllegalStateException("Brak zapisanego konta");
                        List<Channel> items = new XtreamClient(creds).fetchChannels();
                        database.replaceSource(ChannelDatabase.XTREAM, items);
                        database.savePlaylist(ChannelDatabase.XTREAM, "IPTV • " + creds.server, "");
                        return "Odświeżono " + items.size() + " pozycji TV i VOD";
                    }, true)));
        }
        page.addView(xtream);

        sourceStatus = Ui.text(this, "Gotowe na dodanie playlisty.", 12, Ui.ACCENT, false);
        sourceStatus.setGravity(Gravity.CENTER);
        page.addView(sourceStatus, Ui.margins(this, -1, 44, 0, 8, 0, 0));
    }

    private void showGuide() {
        LinearLayout page = paddedPage(scrollPage());
        page.addView(Ui.text(this, "Program TV · EPG", 25, Ui.WHITE, true));
        page.addView(Ui.text(this, "Przewodnik dopasowuje audycje do Twoich kanałów.",
                13, Ui.SECONDARY, false), Ui.margins(this, -1, -2, 0, 6, 0, 20));

        LinearLayout summary = card();
        summary.addView(Ui.text(this, "AUTOMATYCZNA AKTUALIZACJA", 11, Ui.ACCENT, true));
        summary.addView(Ui.text(this, secure.epgStatus(), 17, Ui.WHITE, true),
                Ui.margins(this, -1, -2, 0, 11, 0, 8));
        long last = secure.lastSync();
        String date = last > 0 ? DateFormat.getDateTimeInstance().format(last) : "Jeszcze nie pobrano";
        summary.addView(Ui.text(this, "Ostatnio: " + date + " • zapisane w urządzeniu • co 5 dni",
                12, Ui.SECONDARY, false));
        summary.addView(Ui.button(this, "Synchronizuj EPG teraz", true, v ->
                performTask("Sprawdzanie źródeł EPG…",
                        () -> EpgSync.refresh(getApplicationContext()), false)),
                Ui.margins(this, -1, -2, 0, 16, 0, 0));
        page.addView(summary, Ui.margins(this, -1, -2, 0, 0, 0, 20));

        page.addView(Ui.text(this, "ŹRÓDŁA XMLTV", 11, Ui.MUTED, true));
        for (int i = 0; i < EpgSync.SOURCES.length; i++) {
            LinearLayout row = Ui.column(this);
            row.setPadding(Ui.dp(this, 13), Ui.dp(this, 11), Ui.dp(this, 10), Ui.dp(this, 11));
            row.setBackground(Ui.rounded(this, Ui.SURFACE, 13));
            TextView address = Ui.text(this, EpgSync.SOURCES[i], 11, Ui.SECONDARY, false);
            address.setMaxLines(2);
            row.addView(address);
            row.addView(Ui.text(this, secure.epgSourceStatus(i), 12,
                    Ui.ACCENT, false), Ui.margins(this, -1, -2, 0, 5, 0, 0));
            page.addView(row, Ui.margins(this, -1, -2, 0, 8, 0, 0));
        }

        page.addView(Ui.text(this, "NA ANTENIE", 11, Ui.MUTED, true),
                Ui.margins(this, -1, -2, 0, 24, 0, 7));
        int count = 0;
        for (Channel channel : allChannels) {
            ChannelDatabase.Guide guide = guides.get(channel.id);
            if (guide == null || guide.current == null || guide.current.isEmpty()) continue;
            LinearLayout item = Ui.column(this);
            item.setPadding(Ui.dp(this, 13), Ui.dp(this, 10), Ui.dp(this, 13), Ui.dp(this, 10));
            item.setBackground(Ui.rounded(this, Ui.SURFACE, 12));
            item.addView(Ui.text(this, channel.name, 13, Ui.ACCENT, true));
            item.addView(Ui.text(this, guide.current, 14, Ui.WHITE, false));
            if (guide.next != null && !guide.next.isEmpty()) {
                item.addView(Ui.text(this, "Dalej: " + guide.next, 11, Ui.MUTED, false));
            }
            item.setOnClickListener(v -> openContent(channel));
            Ui.tvFocus(item);
            page.addView(item, Ui.margins(this, -1, -2, 0, 5, 0, 0));
            if (++count >= 25) break;
        }
        if (count == 0) page.addView(Ui.text(this,
                "Programy pojawią się tutaj po dodaniu kanałów i synchronizacji EPG.",
                13, Ui.SECONDARY, false), Ui.margins(this, -1, -2, 0, 5, 0, 0));
    }

    private void showSettings() {
        LinearLayout page = paddedPage(scrollPage());
        page.addView(Ui.text(this, "Ustawienia", 25, Ui.WHITE, true));
        page.addView(Ui.text(this, "Prosto. Prywatnie. Pod Twoją kontrolą.", 13,
                Ui.SECONDARY, false), Ui.margins(this, -1, -2, 0, 5, 0, 20));
        LinearLayout about = card();
        about.addView(Ui.text(this, "SS-IPTV  •  wersja 1.8.0", 19, Ui.WHITE, true));
        about.addView(Ui.text(this, "Niezależny odtwarzacz Twoich własnych źródeł IPTV. " +
                "Aplikacja nie dostarcza kanałów ani subskrypcji.",
                13, Ui.SECONDARY, false), Ui.margins(this, -1, -2, 0, 8, 0, 10));
        about.addView(Ui.text(this, "Listy M3U są w prywatnej pamięci aplikacji. Hasło " +
                "serwera jest szyfrowane przez Android Keystore.",
                12, Ui.ACCENT, false));
        page.addView(about, Ui.margins(this, -1, -2, 0, 0, 0, 16));

        LinearLayout screenCard = card();
        screenCard.addView(Ui.text(this, "Interfejs ekranu", 18, Ui.WHITE, true));
        screenCard.addView(Ui.text(this,
                "Tryb TV: pełnoekranowa lista, boczne menu kategorii i nawigacja pilotem. " +
                "Możesz włączyć go również na Android Box bez certyfikacji Android TV.",
                13, Ui.SECONDARY, false), Ui.margins(this, -1, -2, 0, 8, 0, 12));
        screenCard.addView(Ui.button(this, tvMode ? "Tryb TV: wyłącz" :
                "Włącz tryb TV dla pilota", true, v -> {
            boolean next = !tvMode;
            getPreferences(MODE_PRIVATE).edit().putInt("tv-ui-override", next ? 1 : 0).apply();
            tvMode = next;
            tiled = getPreferences(MODE_PRIVATE).getBoolean(tvMode ? "tv-grid" : "grid", false);
            showTab("Telewizja");
        }));
        page.addView(screenCard, Ui.margins(this, -1, -2, 0, 0, 0, 16));

        LinearLayout playerCard = card();
        playerCard.addView(Ui.text(this, "Domyślny odtwarzacz", 19, Ui.WHITE, true));
        playerCard.addView(Ui.text(this,
                "Jedna wybrana technologia obsługuje TV i VOD. Przy utracie połączenia " +
                "aplikacja ponawia próbę tylko w tym silniku — bez automatycznego przełączania.",
                13, Ui.SECONDARY, false), Ui.margins(this, -1, -2, 0, 8, 0, 12));
        for (String engine : new String[]{"android", "media3", "vlc"}) {
            String label = "android".equals(engine) ? "Android / systemowy" :
                    "media3".equals(engine) ? "Media3 / ExoPlayer" : "VLC / VideoLAN";
            boolean current = engine.equals(secure.playbackEngine());
            playerCard.addView(Ui.button(this, (current ? "Aktywny: " : "Wybierz: ") + label,
                    current, v -> {
                        secure.setPlaybackEngine(engine);
                        showTab("Ustawienia");
                    }), Ui.margins(this, -1, -2, 0, 0, 0, 6));
        }
        page.addView(playerCard, Ui.margins(this, -1, -2, 0, 0, 0, 16));

        LinearLayout playlistsCard = card();
        playlistsCard.addView(Ui.text(this, "Aktywna lista kanałów", 19, Ui.WHITE, true));
        playlistsCard.addView(Ui.text(this, "Wybierz źródło do przeglądania i zmiany kanałów " +
                "pilotem. Możesz też pokazywać wszystkie listy naraz.", 13, Ui.SECONDARY, false),
                Ui.margins(this, -1, -2, 0, 7, 0, 12));
        String active = secure.activeSource();
        TextView everything = Ui.button(this,
                (active.isEmpty() ? "Aktywne: " : "") + "Wszystkie listy", active.isEmpty(), v -> {
                    secure.setActiveSource("");
                    showTab("Ustawienia");
                    reloadData();
                });
        playlistsCard.addView(everything, Ui.margins(this, -1, -2, 0, 0, 0, 9));
        List<ChannelDatabase.Playlist> sources = database.playlists();
        for (ChannelDatabase.Playlist source : sources) {
            boolean chosen = source.source.equals(active);
            playlistsCard.addView(Ui.button(this, (chosen ? "Aktywna: " : "") +
                    source.title + "  (" + source.count + ")", chosen, v -> {
                        secure.setActiveSource(source.source);
                        showTab("Ustawienia");
                        reloadData();
                    }), Ui.margins(this, -1, -2, 0, 0, 0, 8));
            playlistsCard.addView(Ui.button(this, "Usuń: " + source.title, false, v ->
                    confirmDelete("Usunąć listę " + source.title + "?", () -> {
                        database.deleteSource(source.source);
                        if (source.file != null && !source.file.isEmpty()) {
                            new java.io.File(getFilesDir(), source.file).delete();
                        }
                        if (source.source.equals(secure.activeSource())) secure.setActiveSource("");
                        if (ChannelDatabase.XTREAM.equals(source.source)) secure.clearServer();
                    })), Ui.margins(this, -1, -2, 0, 0, 0, 15));
        }
        if (sources.isEmpty()) playlistsCard.addView(Ui.text(this,
                "Nie dodano jeszcze żadnej listy.", 12, Ui.MUTED, false));
        page.addView(playlistsCard, Ui.margins(this, -1, -2, 0, 0, 0, 16));

        LinearLayout remoteCard = card();
        remoteCard.addView(Ui.text(this, "Pilot Android TV", 18, Ui.WHITE, true));
        remoteCard.addView(Ui.text(this, "W katalogu: ← menu kategorii, ↑↓ wybór, OK odtwórz, " +
                "czerwony: ulubione. Podczas odtwarzania: " +
                "CH+/CH− lub ↑/↓: kanał. Cyfry 1–3: numer " +
                "kanału (zatwierdź OK albo poczekaj). Czerwony: ulubione. Zielony: OSD. " +
                "Żółty: opis. Niebieski: ustawienia. Strzałki + OK obsługują " +
                "przyciski ekranowe także bez kolorowych klawiszy.", 13,
                Ui.SECONDARY, false), Ui.margins(this, -1, -2, 0, 8, 0, 0));
        page.addView(remoteCard, Ui.margins(this, -1, -2, 0, 0, 0, 16));

        LinearLayout epgCard = card();
        epgCard.addView(Ui.text(this, "EPG • status źródeł", 19, Ui.WHITE, true));
        epgCard.addView(Ui.text(this, secure.epgStatus(), 13, Ui.ACCENT, false),
                Ui.margins(this, -1, -2, 0, 7, 0, 12));
        EditText customEpg = input("https://twoj-serwer.pl/epg.xml.gz", false);
        customEpg.setSingleLine(true);
        customEpg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        customEpg.setText(secure.customEpgUrl());
        epgCard.addView(Ui.text(this, "Własny adres XMLTV / XMLTV.GZ", 13, Ui.WHITE, true),
                Ui.margins(this, -1, -2, 0, 7, 0, 3));
        epgCard.addView(customEpg, Ui.margins(this, -1, 48, 0, 0, 0, 7));
        epgCard.addView(Ui.button(this, "Zapisz adres EPG", false, v -> {
            try {
                secure.setCustomEpgUrl(customEpg.getText().toString());
                Toast.makeText(this, "Zapisano EPG. Rozpocznę synchronizację w tle.",
                        Toast.LENGTH_LONG).show();
                EpgSync.refreshIfStale(getApplicationContext());
                showTab("Ustawienia");
            } catch (IllegalArgumentException error) {
                customEpg.setError(error.getMessage());
            }
        }), Ui.margins(this, -1, -2, 0, 0, 0, 12));
        java.util.List<String> epgUrls = EpgSync.configuredSources(secure);
        for (int i = 0; i < epgUrls.size(); i++) {
            epgCard.addView(Ui.text(this, epgUrls.get(i), 11, Ui.SECONDARY, false));
            epgCard.addView(Ui.text(this, secure.epgSourceStatus(i), 12, Ui.WHITE, true),
                    Ui.margins(this, -1, -2, 0, 2, 0, 9));
        }
        epgCard.addView(Ui.button(this, "↻  Odśwież wszystkie źródła EPG", true,
                v -> performTask("Synchronizowanie EPG…",
                        () -> EpgSync.refresh(getApplicationContext()), false)));
        epgCard.addView(Ui.button(this, "Zobacz program telewizyjny  →", false,
                v -> showTab("EPG")), Ui.margins(this, -1, -2, 0, 9, 0, 0));
        page.addView(epgCard, Ui.margins(this, -1, -2, 0, 0, 0, 16));

        LinearLayout actions = card();
        actions.addView(Ui.text(this, "ZARZĄDZAJ DANYMI", 11, Ui.MUTED, true));
        actions.addView(Ui.button(this, "Dodaj plik M3U lub konto IPTV", true,
                v -> showTab("Źródła")), Ui.margins(this, -1, -2, 0, 12, 0, 12));
        actions.addView(Ui.text(this, "Każdą listę możesz usunąć osobno w sekcji «Aktywna lista kanałów».",
                12, Ui.SECONDARY, false));
        actions.addView(Ui.button(this, "Wyjdź z SS-IPTV", false, v -> exitApp()),
                Ui.margins(this, -1, -2, 0, 12, 0, 0));
        page.addView(actions);

        page.addView(Ui.text(this, "Wskazówka: korzystaj z legalnie udostępnionych " +
                "playlist i własnego konta. Strumienie HTTP mogą przesyłać dane bez szyfrowania.",
                12, Ui.MUTED, false), Ui.margins(this, -1, -2, 2, 20, 2, 0));
    }

    private EditText input(String hint, boolean password) {
        EditText field = new EditText(this);
        field.setTextColor(Ui.WHITE);
        field.setHintTextColor(Ui.MUTED);
        field.setTextSize(14);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        field.setBackground(Ui.outlined(this, Ui.CARD, 0xFF285065, 12));
        if (password) field.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return field;
    }

    private interface Task { String run() throws Exception; }

    private void performTask(String progress, Task task, boolean returnHome) {
        if (sourceStatus != null) sourceStatus.setText(progress);
        toast(progress);
        io.execute(() -> {
            String result;
            boolean success;
            try {
                result = task.run();
                success = true;
            } catch (Exception error) {
                result = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                success = false;
            }
            final String message = result;
            final boolean ok = success;
            runOnUiThread(() -> {
                if (sourceStatus != null) sourceStatus.setText(message);
                toast(message);
                if (ok) {
                    if (returnHome) showTab(firstAvailableTab());
                    else if ("EPG".equals(tab) || "Ustawienia".equals(tab)) showTab(tab);
                    reloadData();
                    if (returnHome) {
                        Thread epg = new Thread(() -> EpgSync.refresh(getApplicationContext()),
                                "ss-epg-after-source");
                        epg.start();
                    }
                }
            });
        });
    }

    private String firstAvailableTab() {
        boolean movies = false;
        boolean series = false;
        for (Channel item : database.allChannels()) {
            if (!item.vod && !item.isSeries()) return "Telewizja";
            if (item.isMovie()) movies = true;
            if (item.isSeries() || item.isEpisode()) series = true;
        }
        return movies ? "VOD" : series ? "Seriale" : "Telewizja";
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void play(Channel channel) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_ID, channel.id);
        startActivity(intent);
    }

    private void openContent(Channel channel) {
        if (channel.isSeries() || channel.isEpisode() || channel.isMovie()) {
            Intent intent = new Intent(this, VodDetailActivity.class);
            intent.putExtra(VodDetailActivity.EXTRA_ID, channel.id);
            if (channel.isEpisode() || channel.isSeries()) {
                intent.putExtra(VodDetailActivity.EXTRA_SERIES, channel.seriesId);
                intent.putExtra(VodDetailActivity.EXTRA_SOURCE, channel.source);
            }
            startActivity(intent);
        } else play(channel);
    }

    /** Gwarantowany OK na zaznaczonym wierszu/kafelku; nie oddajemy go serduszku. */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (tvMode && tvCollection != null && tvCollection.hasFocus() &&
                (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER ||
                        event.getKeyCode() == KeyEvent.KEYCODE_ENTER ||
                        event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 &&
                    adapter != null) {
                int selected = tvCollection.getSelectedItemPosition();
                if (selected >= 0 && selected < adapter.visible.size())
                    openContent(adapter.visible.get(selected));
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.getRepeatCount() == 0) {
            exitApp();
            return true;
        }
        if (tvMode && event.getRepeatCount() == 0) {
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
                if (tvDrawer != null && tvDrawer.getVisibility() == View.VISIBLE)
                    closeTvDrawer();
                else openTvDrawer();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && tvDrawer != null &&
                    tvDrawer.getVisibility() == View.VISIBLE) {
                closeTvDrawer();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && tvCollection != null &&
                    tvCollection.hasFocus()) {
                int selected = tvCollection.getSelectedItemPosition();
                if (TvNavigation.opensDrawerFromLeft(selected, tiled, tvGridColumns)) {
                    openTvDrawer();
                    return true;
                }
            }
            if (keyCode == KeyEvent.KEYCODE_PROG_RED && tvCollection != null &&
                    tvCollection.hasFocus() && adapter != null) {
                int selected = tvCollection.getSelectedItemPosition();
                if (selected >= 0 && selected < adapter.visible.size()) {
                    toggleFavorite(adapter.visible.get(selected));
                    return true;
                }
            }
            if (keyCode == KeyEvent.KEYCODE_BACK && tvDrawer != null &&
                    tvDrawer.getVisibility() == View.VISIBLE) {
                if (tvCategoryDrawerMode) {
                    tvCategoryDrawerMode = false;
                    populateTvDrawer();
                } else closeTvDrawer();
                return true;
            }
        }
        if (event.getRepeatCount() == 0 && keyCode == KeyEvent.KEYCODE_PROG_BLUE) {
            showTab("Ustawienia".equals(tab) ? previousTab : "Ustawienia");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed() {
        if (tvMode && tvDrawer != null && tvDrawer.getVisibility() == View.VISIBLE) {
            if (tvCategoryDrawerMode) {
                tvCategoryDrawerMode = false;
                populateTvDrawer();
            } else closeTvDrawer();
        } else if (tvMode && !"Telewizja".equals(tab)) {
            showTab("Telewizja");
        } else super.onBackPressed();
    }

    /** Zamyka cały stos aktywności i usuwa zadanie z ostatnich aplikacji.
     * Następne uruchomienie przechodzi ponownie przez onCreate i czyta lokalny katalog. */
    private void exitApp() {
        uiHandler.removeCallbacks(guideDisplayTick);
        finishAndRemoveTask();
    }

    private void confirmDelete(String title, Runnable operation) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("Te dane zostaną usunięte z aplikacji. Pozostałe źródła pozostaną bez zmian.")
                .setNegativeButton("Anuluj", null)
                .setPositiveButton("Usuń", (dialog, which) -> {
                    io.execute(() -> {
                        operation.run();
                        runOnUiThread(() -> {
                            toast("Usunięto wybrane dane");
                            showTab("Telewizja");
                            reloadData();
                        });
                    });
                }).show();
    }

    /** Na części Android Boxów nie ma DocumentsUI — wybór pliku nie może kończyć aplikacji. */
    private void choosePlaylistFile(boolean alternate) {
        ArrayList<Intent> candidates = new ArrayList<>();
        if (alternate) {
            candidates.add(filePicker(Intent.ACTION_OPEN_DOCUMENT, true));
            candidates.add(filePicker(Intent.ACTION_GET_CONTENT, true));
            candidates.add(filePicker(Intent.ACTION_GET_CONTENT, false));
        } else {
            // Najpierw systemowy SAF: trwały grant odczytu bez STORAGE permission.
            candidates.add(filePicker(Intent.ACTION_OPEN_DOCUMENT, true));
            candidates.add(filePicker(Intent.ACTION_GET_CONTENT, false));
            candidates.add(filePicker(Intent.ACTION_GET_CONTENT, true));
        }
        for (Intent candidate : candidates) {
            try {
                startActivityForResult(candidate, PICK_PLAYLIST);
                return;
            } catch (RuntimeException failure) {
                Log.w(TAG, "Brak dostępnego wyboru plików: " + candidate.getAction(), failure);
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Brak menedżera wyboru plików")
                .setMessage("Android Box nie ma aplikacji obsługującej wybór plików. " +
                        "Zainstaluj menedżer plików obsługujący «Otwórz za pomocą», " +
                        "a następnie otwórz w nim plik M3U przez SS-IPTV. " +
                        "Możesz również dodać playlistę przez jej adres URL.")
                .setPositiveButton("Rozumiem", null)
                .show();
    }

    private Intent filePicker(String action, boolean openable) {
        Intent intent = new Intent(action);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (openable) intent.addCategory(Intent.CATEGORY_OPENABLE);
        return intent;
    }

    /** Import po wybraniu SS-IPTV w oknie „Otwórz za pomocą” menedżera plików. */
    private void handleExternalPlaylist(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(action) && !Intent.ACTION_SEND.equals(action)) return;
        Uri uri = Intent.ACTION_VIEW.equals(action) ? intent.getData() :
                intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null && intent.getClipData() != null &&
                intent.getClipData().getItemCount() > 0)
            uri = intent.getClipData().getItemAt(0).getUri();
        if (uri == null) return;
        String scheme = uri.getScheme();
        if (!("content".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme))) {
            toast("Otwieranie pliku wymaga adresu content:// lub file://");
            return;
        }
        String title = externalFileName(uri);
        String type = intent.getType();
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("video/") &&
                !title.toLowerCase(Locale.ROOT).endsWith(".m3u")) {
            Intent player = new Intent(this, PlayerActivity.class);
            player.putExtra(PlayerActivity.EXTRA_EXTERNAL_URI, uri.toString());
            player.putExtra(PlayerActivity.EXTRA_EXTERNAL_NAME, title);
            player.setData(uri);
            player.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(player);
            return;
        }
        Uri selected = uri;
        showTab("Źródła");
        new AlertDialog.Builder(this)
                .setTitle("Dodać playlistę M3U?")
                .setMessage("SS-IPTV zaimportuje wskazaną listę do prywatnej pamięci " +
                        "aplikacji. Dotychczasowe listy pozostaną dostępne.")
                .setNegativeButton("Anuluj", null)
                .setPositiveButton("Importuj", (dialog, which) -> importPlaylistUri(selected))
                .show();
    }

    private String externalFileName(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || name.trim().isEmpty()) name = "Zewnętrzne wideo";
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String label = cursor.getString(0);
                if (label != null && !label.trim().isEmpty()) name = label;
            }
        } catch (RuntimeException ignored) { /* Menedżer plików może nie obsługiwać metadanych. */ }
        return name;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            // Ponowne uruchomienie z ekranu Android TV zawsze zaczyna od kanałów.
            showTab("Telewizja");
            reloadData();
        } else handleExternalPlaylist(intent);
    }

    private void importPlaylistUri(Uri uri) {
        performTask("Importowanie pliku M3U…", () -> {
            int amount = PlaylistLoader.importDocument(getApplicationContext(), uri).size();
            return "Zaimportowano " + amount + " kanałów";
        }, true);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_PLAYLIST || resultCode != RESULT_OK) return;
        Uri uri = data == null ? null : data.getData();
        if (uri == null && data != null && data.getClipData() != null &&
                data.getClipData().getItemCount() > 0)
            uri = data.getClipData().getItemAt(0).getUri();
        if (uri == null) {
            toast("Menedżer plików nie zwrócił pliku. Wypróbuj alternatywny wybór.");
            return;
        }
        if (data != null && (data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Wciąż możemy czytać z jednorazowego grantu GET_CONTENT.
            }
        }
        importPlaylistUri(uri);
    }

    private FrameLayout logo(Channel channel, int size) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(Ui.rounded(this, Ui.CARD, 13));
        TextView fallback = Ui.text(this, channel.name.substring(0,
                Math.min(2, channel.name.length())).toUpperCase(Locale.ROOT), 15, Ui.ACCENT, true);
        fallback.setGravity(Gravity.CENTER);
        frame.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5));
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        LogoLoader.load(image, channel.logo);
        return frame;
    }

    /** Pełnoekranowa miniatura filmu/serialu; nie mylimy jej z logo kanału. */
    private FrameLayout poster(Channel item) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(Ui.rounded(this, 0xFF263A4A, 14));
        frame.setClipToOutline(true);
        TextView missing = Ui.text(this, item.name, 17, 0xFFB6D5E2, true);
        missing.setGravity(Gravity.CENTER);
        missing.setMaxLines(3);
        missing.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        frame.addView(missing, new FrameLayout.LayoutParams(-1, -1));
        ImageView artwork = new ImageView(this);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(artwork, new FrameLayout.LayoutParams(-1, -1));
        LogoLoader.load(artwork, item.logo);
        // Subtelny gradient zostawia czytelną etykietę bez zasłaniania okładki.
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00111B26, 0x22111B26, 0xB8000810}));
        frame.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        TextView badge = Ui.text(this, item.isSeries() ? "SERIAL" : "FILM",
                10, Ui.WHITE, true);
        badge.setLetterSpacing(0.11f);
        badge.setPadding(Ui.dp(this, 9), Ui.dp(this, 5), Ui.dp(this, 9), Ui.dp(this, 5));
        badge.setBackground(Ui.rounded(this, 0xCB142C40, 6));
        FrameLayout.LayoutParams placement = new FrameLayout.LayoutParams(-2, -2,
                Gravity.LEFT | Gravity.BOTTOM);
        placement.setMargins(Ui.dp(this, 10), 0, 0, Ui.dp(this, 10));
        frame.addView(badge, placement);
        return frame;
    }

    private void toggleFavorite(Channel channel) {
        channel.favorite = !channel.favorite;
        boolean saved = channel.favorite;
        if (channel.id < 0 && channel.isSeries()) io.execute(() ->
                database.setSeriesFavorite(channel.source, channel.seriesId, saved));
        else io.execute(() -> database.setFavorite(channel.id, saved));
        filterChannels();
    }

    private String channelDescription(Channel channel, ChannelDatabase.Guide guide) {
        if (channel.vod) {
            if (channel.isEpisode()) return "Sezon " + channel.season +
                    " · odcinek " + channel.episode +
                    (channel.playbackMs > 0 ? " · kontynuuj" : "");
            if (channel.playbackMs > 0)
                return "Kontynuuj od " + (channel.playbackMs / 60000) + " min";
            return "Film VOD • " + channel.group;
        }
        if (channel.isSeries()) return "Serial • zobacz sezony i odcinki";
        if (guide != null && guide.current != null && !guide.current.isEmpty())
            return "Teraz: " + guide.current;
        return channel.group + "  •  EPG w przygotowaniu";
    }

    private final class ChannelAdapter extends BaseAdapter {
        List<Channel> visible = new ArrayList<>();
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return visible.get(position).id; }
        @Override public boolean hasStableIds() { return true; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Channel channel = visible.get(position);
            ChannelDatabase.Guide guide = guides.get(channel.id);
            if (isTiled()) return makeTile(channel, guide);
            return makeRow(channel, guide);
        }

        private View makeRow(Channel channel, ChannelDatabase.Guide guide) {
            LinearLayout row = Ui.row(MainActivity.this);
            if (tvMode) row.setContentDescription("Odtwórz: " + channel.name);
            row.setPadding(Ui.dp(MainActivity.this, 12), Ui.dp(MainActivity.this, 11),
                    Ui.dp(MainActivity.this, 10), Ui.dp(MainActivity.this, 11));
            row.setBackground(Ui.rounded(MainActivity.this, Ui.SURFACE, 15));
            boolean media = channel.isMovie() || channel.isSeries();
            row.addView(media ? poster(channel) : logo(channel, 56), new LinearLayout.LayoutParams(
                    Ui.dp(MainActivity.this, media ? 78 : 56),
                    Ui.dp(MainActivity.this, media ? 108 : 56)));

            LinearLayout labels = Ui.column(MainActivity.this);
            labels.setPadding(Ui.dp(MainActivity.this, 12), 0, Ui.dp(MainActivity.this, 5), 0);
            TextView name = Ui.text(MainActivity.this, channel.name, 16, Ui.WHITE, true);
            name.setMaxLines(3);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setContentDescription("Nazwa kanału: " + channel.name);
            labels.addView(name);
            if (!channel.vod && !channel.isSeries()) labels.addView(Ui.text(MainActivity.this,
                    "Kanał " + (liveIndex(channel) + 1), 11, Ui.ACCENT, true));
            TextView group = Ui.text(MainActivity.this, channel.group, 11, Ui.MUTED, false);
            group.setSingleLine(true);
            labels.addView(group);
            TextView now = Ui.text(MainActivity.this, channelDescription(channel, guide), 11,
                    Ui.ACCENT, false);
            now.setSingleLine(true);
            now.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(now);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

            ImageView favorite = new ImageView(MainActivity.this);
            favorite.setImageResource(R.drawable.ic_nav_heart);
            favorite.setImageTintList(ColorStateList.valueOf(channel.favorite ? 0xFFFF728D : Ui.MUTED));
            favorite.setPadding(Ui.dp(MainActivity.this, 8), Ui.dp(MainActivity.this, 14),
                    Ui.dp(MainActivity.this, 8), Ui.dp(MainActivity.this, 14));
            favorite.setContentDescription(channel.favorite ? "Usuń z ulubionych" : "Dodaj do ulubionych");
            if (!tvMode) {
                favorite.setOnClickListener(v -> toggleFavorite(channel));
                Ui.tvFocus(favorite);
            }
            row.addView(favorite, new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 38),
                    Ui.dp(MainActivity.this, 50)));
            return row;
        }

        private View makeTile(Channel channel, ChannelDatabase.Guide guide) {
            LinearLayout tile = Ui.column(MainActivity.this);
            if (tvMode) tile.setContentDescription("Otwórz: " + channel.name);
            tile.setPadding(Ui.dp(MainActivity.this, 10), Ui.dp(MainActivity.this, 10),
                    Ui.dp(MainActivity.this, 10), Ui.dp(MainActivity.this, 13));
            tile.setBackground(Ui.rounded(MainActivity.this, Ui.SURFACE, 17));

            boolean isPoster = channel.vod || channel.isSeries();
            FrameLayout art = isPoster ? poster(channel) : new FrameLayout(MainActivity.this);
            if (!isPoster) {
                FrameLayout symbol = logo(channel, 100);
                FrameLayout.LayoutParams center = new FrameLayout.LayoutParams(
                        Ui.dp(MainActivity.this, 94), Ui.dp(MainActivity.this, 94), Gravity.CENTER);
                art.addView(symbol, center);
            }
            ImageView heart = new ImageView(MainActivity.this);
            heart.setImageResource(R.drawable.ic_nav_heart);
            heart.setImageTintList(ColorStateList.valueOf(channel.favorite ? 0xFFFF728D : Ui.WHITE));
            heart.setPadding(Ui.dp(MainActivity.this, 7), Ui.dp(MainActivity.this, 7),
                    Ui.dp(MainActivity.this, 7), Ui.dp(MainActivity.this, 7));
            heart.setBackground(Ui.rounded(MainActivity.this, 0xD4142C42, 50));
            heart.setContentDescription(channel.favorite ? "Usuń z ulubionych" : "Dodaj do ulubionych");
            if (!tvMode) {
                heart.setOnClickListener(v -> toggleFavorite(channel));
                Ui.tvFocus(heart);
            }
            FrameLayout.LayoutParams heartParams = new FrameLayout.LayoutParams(
                    Ui.dp(MainActivity.this, 35), Ui.dp(MainActivity.this, 35), Gravity.RIGHT | Gravity.TOP);
            art.addView(heart, heartParams);
            tile.addView(art, new LinearLayout.LayoutParams(-1, Ui.dp(MainActivity.this,
                    isPoster ? (tvMode ?
                            (getResources().getConfiguration().screenHeightDp < 500 ? 124 : 225)
                            : 205) : 102)));

            TextView name = Ui.text(MainActivity.this, channel.name, 15, Ui.WHITE, true);
            name.setMaxLines(3);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setMinHeight(Ui.dp(MainActivity.this, 46));
            name.setPadding(Ui.dp(MainActivity.this, 8), Ui.dp(MainActivity.this, 6),
                    Ui.dp(MainActivity.this, 8), Ui.dp(MainActivity.this, 6));
            name.setBackground(Ui.rounded(MainActivity.this, Ui.CARD, 9));
            name.setContentDescription("Nazwa kanału: " + channel.name);
            tile.addView(name, Ui.margins(MainActivity.this, -1, -2, 0, 7, 0, 6));
            TextView desc = Ui.text(MainActivity.this, channelDescription(channel, guide),
                    11, Ui.ACCENT, false);
            desc.setMaxLines(2);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tile.addView(desc);
            if (!isPoster) tile.addView(Ui.text(MainActivity.this,
                    "Nr " + (liveIndex(channel) + 1), 10, Ui.MUTED, false));
            return tile;
        }
    }

    private int liveIndex(Channel target) {
        Integer index = liveOrdinals.get(target.id);
        return index == null ? 0 : index;
    }
}
