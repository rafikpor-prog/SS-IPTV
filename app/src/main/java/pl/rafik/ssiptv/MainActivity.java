package pl.rafik.ssiptv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
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
    private boolean tvMode;
    private int tvGridColumns = 4;
    private FrameLayout tvRoot;
    private ScrollView tvDrawer;
    private boolean tvCategoryDrawerMode;
    private View tvDim;
    private TextView tvCount;
    private AdapterView<?> tvCollection;
    private List<Channel> allChannels = new ArrayList<>();
    private Map<Long, ChannelDatabase.Guide> guides = new HashMap<>();

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
        showTab("Telewizja");
        reloadData();
        EpgSync.schedule(this);
        EpgSync.refreshIfStale(this);
        if (state == null) handleExternalPlaylist(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        if (database != null) reloadData();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void reloadData() {
        io.execute(() -> {
            List<Channel> entries = database.allChannels();
            String active = secure.activeSource();
            if (!active.isEmpty()) entries.removeIf(item -> !active.equals(item.source));
            entries = withSeriesCards(entries, database.seriesFavorites());
            Map<Long, ChannelDatabase.Guide> programs = database.readGuides();
            List<Channel> catalog = entries;
            runOnUiThread(() -> {
                allChannels = catalog;
                guides = programs;
                if (isCatalogTab()) {
                    updateChips();
                    filterChannels();
                } else if ("EPG".equals(tab)) {
                    showTab("EPG");
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
        rail.setPadding(0, Ui.dp(this, 11), Ui.dp(this, 8), 0);
        TextView menu = Ui.text(this, "☰", 26, Ui.ACCENT, true);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("Otwórz menu kategorii i sekcji");
        menu.setBackground(Ui.rounded(this, Ui.SURFACE, 12));
        menu.setOnClickListener(v -> openTvDrawer());
        Ui.tvFocus(menu);
        rail.addView(menu, new LinearLayout.LayoutParams(-1, Ui.dp(this, 66)));
        TextView brand = Ui.text(this, "SS", 13, Ui.SECONDARY, true);
        brand.setGravity(Gravity.CENTER);
        rail.addView(brand, Ui.margins(this, -1, 38, 0, 15, 0, 0));
        layout.addView(rail, new LinearLayout.LayoutParams(Ui.dp(this, 74), -1));

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
        tvDim.setBackgroundColor(0xAA020C15);
        tvDim.setOnClickListener(v -> closeTvDrawer());
        tvDim.setVisibility(View.GONE);
        tvRoot.addView(tvDim, new FrameLayout.LayoutParams(-1, -1));
        tvDrawer = new ScrollView(this);
        tvDrawer.setFillViewport(false);
        tvDrawer.setVerticalScrollBarEnabled(false);
        tvDrawer.setBackground(Ui.outlined(this, Ui.SURFACE, Ui.ACCENT, 16));
        tvDrawer.setVisibility(View.GONE);
        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(
                Math.min(Ui.dp(this, 348),
                        getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 65)),
                -1, Gravity.LEFT);
        tvRoot.addView(tvDrawer, drawerParams);
        if (isCatalogTab() && tvCollection != null && adapter != null &&
                adapter.getCount() > 0) focusTvCollection();
        else menu.requestFocus();
    }

    private void showTvChannels() {
        LinearLayout titleRow = Ui.row(this);
        String heading = "Telewizja".equals(tab) ? "TELEWIZJA" :
                "VOD".equals(tab) ? "FILMY VOD" :
                "Seriale".equals(tab) ? "SERIALE" :
                "Ulubione".equals(tab) ? "ULUBIONE" : "OSTATNIO OGLĄDANE";
        TextView title = Ui.text(this, heading, 25, Ui.WHITE, true);
        titleRow.addView(title);
        tvCount = Ui.text(this, "", 14, Ui.ACCENT, true);
        tvCount.setPadding(Ui.dp(this, 20), 0, 0, 0);
        titleRow.addView(tvCount, new LinearLayout.LayoutParams(0, -2, 1));
        TextView hint = Ui.text(this, "← Kategorie / Menu     OK ▶ Odtwórz", 12,
                Ui.SECONDARY, false);
        titleRow.addView(hint);
        content.addView(titleRow, Ui.margins(this, -1, 64, 0, 0, 0, 4));
        resultsFrame = new FrameLayout(this);
        content.addView(resultsFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        renderCollection();
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
        items.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18),
                Ui.dp(this, 24));
        tvDrawer.removeAllViews();
        tvDrawer.addView(items);
        items.addView(Ui.text(this, "SS-IPTV", 26, Ui.ACCENT, true),
                Ui.margins(this, -1, 47, 0, 0, 0, 6));
        View initialFocus = null;
        if (tvCategoryDrawerMode && isCatalogTab()) {
            TextView back = tvDrawerItem("‹   Wróć do menu", false, () -> {
                tvCategoryDrawerMode = false;
                populateTvDrawer();
            });
            items.addView(back, Ui.margins(this, -1, 50, 0, 0, 0, 8));
            items.addView(Ui.text(this, "KATEGORIE • " + tab, 12, Ui.ACCENT, true),
                    Ui.margins(this, -1, 34, 0, 0, 0, 6));
            for (String group : tvCategoryNames()) {
                boolean selected = group.equals(selectedGroup);
                TextView item = tvDrawerItem((selected ? "●  " : "○  ") + group,
                        selected, () -> {
                    selectedGroup = group;
                    closeTvDrawer();
                    filterChannels();
                });
                item.setSingleLine(true);
                item.setEllipsize(android.text.TextUtils.TruncateAt.END);
                items.addView(item, Ui.margins(this, -1, 43, 0, 2, 0, 2));
                if (selected) initialFocus = item;
            }
        } else {
            items.addView(Ui.text(this, "MENU", 12, Ui.SECONDARY, true),
                    Ui.margins(this, -1, -2, 0, 0, 0, 9));
            if (isCatalogTab()) {
                TextView categories = tvDrawerItem("▤   Kategorie: " + selectedGroup + "   ›",
                        true, () -> {
                    tvCategoryDrawerMode = true;
                    populateTvDrawer();
                });
                categories.setSingleLine(true);
                categories.setEllipsize(android.text.TextUtils.TruncateAt.END);
                items.addView(categories, Ui.margins(this, -1, 51, 0, 2, 0, 10));
                initialFocus = categories;
            }
            final String[] tabs = {"Telewizja", "VOD", "Seriale", "Ulubione", "Ostatnie",
                    "EPG", "Źródła", "Ustawienia"};
            final String[] icons = {"▣", "▶", "▤", "♥", "◷", "◫", "＋", "⚙"};
            for (int i = 0; i < tabs.length; i++) {
                String destination = tabs[i];
                TextView item = tvDrawerItem(icons[i] + "   " + destination,
                        destination.equals(tab), () -> showTab(destination));
                items.addView(item, Ui.margins(this, -1, 47, 0, 2, 0, 2));
                if (initialFocus == null && destination.equals(tab)) initialFocus = item;
            }
            if (isCatalogTab()) {
                items.addView(Ui.text(this, "WIDOK KANAŁÓW", 12, Ui.ACCENT, true),
                        Ui.margins(this, -1, 35, 0, 13, 0, 4));
                items.addView(tvDrawerItem("☰   Lista", !tiled, () -> setTiled(false)),
                        Ui.margins(this, -1, 43, 0, 2, 0, 2));
                items.addView(tvDrawerItem("▦   Kafelki", tiled, () -> setTiled(true)),
                        Ui.margins(this, -1, 43, 0, 2, 0, 2));
            }
        }
        if (initialFocus != null) initialFocus.requestFocus();
    }

    private TextView tvDrawerItem(String caption, boolean selected, Runnable action) {
        TextView button = Ui.text(this, caption, 16, selected ? Ui.ACCENT : Ui.WHITE, selected);
        button.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 9), 0);
        button.setBackground(Ui.rounded(this, selected ? 0xFF20495A : Ui.CARD, 10));
        button.setOnClickListener(v -> action.run());
        Ui.tvFocus(button);
        return button;
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

        TextView source = Ui.text(this, "＋ Źródło", 12, Ui.BG, true);
        source.setGravity(Gravity.CENTER);
        source.setPadding(Ui.dp(this, 11), Ui.dp(this, 10), Ui.dp(this, 11), Ui.dp(this, 10));
        source.setBackground(Ui.rounded(this, Ui.ACCENT, 11));
        source.setContentDescription("Dodaj plik M3U lub zaloguj się do IPTV");
        source.setOnClickListener(v -> showTab("Źródła"));
        Ui.tvFocus(source);
        bar.addView(source);

        TextView settings = Ui.text(this, "⚙", 23, Ui.WHITE, false);
        settings.setGravity(Gravity.CENTER);
        settings.setContentDescription("Ustawienia aplikacji");
        settings.setOnClickListener(v -> showTab("Ustawienia"));
        Ui.tvFocus(settings);
        bar.addView(settings, Ui.margins(this, 42, 46, 7, 0, 0, 0));
        return bar;
    }

    private View bottomNavigation() {
        LinearLayout bottom = Ui.row(this);
        bottom.setPadding(Ui.dp(this, 9), Ui.dp(this, 4), Ui.dp(this, 9), Ui.dp(this, 5));
        bottom.setBackgroundColor(Ui.SURFACE);
        String[] names = {"Telewizja", "VOD", "Seriale", "Ulubione", "Ostatnie", "Źródła"};
        String[] icons = {"▣", "▶", "▤", "♥", "◷", "＋"};
        for (int i = 0; i < names.length; i++) {
            final String itemName = names[i];
            boolean chosen = itemName.equals(tab);
            LinearLayout item = Ui.column(this);
            item.setGravity(Gravity.CENTER);
            TextView icon = Ui.text(this, icons[i], 21, chosen ? Ui.ACCENT : Ui.MUTED, false);
            icon.setGravity(Gravity.CENTER);
            TextView label = Ui.text(this, itemName, 10, chosen ? Ui.ACCENT : Ui.SECONDARY, chosen);
            label.setGravity(Gravity.CENTER);
            item.addView(icon);
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
        TextView list = Ui.text(this, "☰ Lista", 12, tiled ? Ui.SECONDARY : Ui.ACCENT, !tiled);
        list.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        list.setBackground(Ui.rounded(this, Ui.SURFACE, 10));
        heading.addView(list, Ui.margins(this, -2, -2, 0, 0, 6, 0));
        TextView tiles = Ui.text(this, "▦ Kafelki", 12, tiled ? Ui.ACCENT : Ui.SECONDARY, tiled);
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
        if (tiled == enabled) {
            if (tvMode) showTab(tab);
            return;
        }
        tiled = enabled;
        getPreferences(MODE_PRIVATE).edit()
                .putBoolean(tvMode ? "tv-grid" : "grid", enabled).apply();
        showTab(tab);
    }

    private void renderCollection() {
        if (resultsFrame == null) return;
        resultsFrame.removeAllViews();
        adapter = new ChannelAdapter();
        android.widget.AdapterView<?> collection;
        if (tiled) {
            GridView grid = new GridView(this);
            grid.setNumColumns(tvMode ? tvGridColumns :
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
        empty.addView(Ui.button(this, "＋  Dodaj źródło M3U / IPTV", true,
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
        TextView importButton = Ui.button(this, "Wybierz plik M3U  ＋", true,
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
        summary.addView(Ui.text(this, "●  AUTOMATYCZNA AKTUALIZACJA", 11, Ui.ACCENT, true));
        summary.addView(Ui.text(this, secure.epgStatus(), 17, Ui.WHITE, true),
                Ui.margins(this, -1, -2, 0, 11, 0, 8));
        long last = secure.lastSync();
        String date = last > 0 ? DateFormat.getDateTimeInstance().format(last) : "Jeszcze nie pobrano";
        summary.addView(Ui.text(this, "Ostatnio: " + date + " • co 12 godzin",
                12, Ui.SECONDARY, false));
        summary.addView(Ui.button(this, "↻  Synchronizuj EPG teraz", true, v ->
                performTask("Sprawdzanie czterech źródeł EPG…",
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
        about.addView(Ui.text(this, "SS-IPTV  •  wersja 1.3.0", 19, Ui.WHITE, true));
        about.addView(Ui.text(this, "Niezależny odtwarzacz Twoich własnych źródeł IPTV. " +
                "Aplikacja nie dostarcza kanałów ani subskrypcji.",
                13, Ui.SECONDARY, false), Ui.margins(this, -1, -2, 0, 8, 0, 10));
        about.addView(Ui.text(this, "Listy M3U są w prywatnej pamięci aplikacji. Hasło " +
                "serwera jest szyfrowane przez Android Keystore.",
                12, Ui.ACCENT, false));
        page.addView(about, Ui.margins(this, -1, -2, 0, 0, 0, 16));

        LinearLayout screenCard = card();
        screenCard.addView(Ui.text(this, "▣  Interfejs ekranu", 18, Ui.WHITE, true));
        screenCard.addView(Ui.text(this,
                "Tryb TV: pełnoekranowa lista, boczne menu kategorii i nawigacja pilotem. " +
                "Możesz włączyć go również na Android Box bez certyfikacji Android TV.",
                13, Ui.SECONDARY, false), Ui.margins(this, -1, -2, 0, 8, 0, 12));
        screenCard.addView(Ui.button(this, tvMode ? "✓  Tryb TV — wyłącz" :
                "Włącz tryb TV dla pilota", true, v -> {
            boolean next = !tvMode;
            getPreferences(MODE_PRIVATE).edit().putInt("tv-ui-override", next ? 1 : 0).apply();
            tvMode = next;
            tiled = getPreferences(MODE_PRIVATE).getBoolean(tvMode ? "tv-grid" : "grid", false);
            showTab("Telewizja");
        }));
        page.addView(screenCard, Ui.margins(this, -1, -2, 0, 0, 0, 16));

        LinearLayout playlistsCard = card();
        playlistsCard.addView(Ui.text(this, "◈  Aktywna lista kanałów", 19, Ui.WHITE, true));
        playlistsCard.addView(Ui.text(this, "Wybierz źródło do przeglądania i zmiany kanałów " +
                "pilotem. Możesz też pokazywać wszystkie listy naraz.", 13, Ui.SECONDARY, false),
                Ui.margins(this, -1, -2, 0, 7, 0, 12));
        String active = secure.activeSource();
        TextView everything = Ui.button(this,
                (active.isEmpty() ? "✓  " : "◯  ") + "Wszystkie listy", active.isEmpty(), v -> {
                    secure.setActiveSource("");
                    showTab("Ustawienia");
                    reloadData();
                });
        playlistsCard.addView(everything, Ui.margins(this, -1, -2, 0, 0, 0, 9));
        List<ChannelDatabase.Playlist> sources = database.playlists();
        for (ChannelDatabase.Playlist source : sources) {
            boolean chosen = source.source.equals(active);
            playlistsCard.addView(Ui.button(this, (chosen ? "✓  " : "◯  ") +
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
        remoteCard.addView(Ui.text(this, "⌁  Pilot Android TV", 18, Ui.WHITE, true));
        remoteCard.addView(Ui.text(this, "W katalogu: ← menu kategorii, ↑↓ wybór, OK odtwórz, " +
                "czerwony: ulubione. Podczas odtwarzania: " +
                "CH+/CH− lub ↑/↓: kanał. Cyfry 1–3: numer " +
                "kanału (zatwierdź OK albo poczekaj). Czerwony: ♥. Zielony: OSD. " +
                "Żółty: opis. Niebieski: ustawienia. Strzałki + OK obsługują " +
                "przyciski ekranowe także bez kolorowych klawiszy.", 13,
                Ui.SECONDARY, false), Ui.margins(this, -1, -2, 0, 8, 0, 0));
        page.addView(remoteCard, Ui.margins(this, -1, -2, 0, 0, 0, 16));

        LinearLayout epgCard = card();
        epgCard.addView(Ui.text(this, "EPG • status źródeł", 19, Ui.WHITE, true));
        epgCard.addView(Ui.text(this, secure.epgStatus(), 13, Ui.ACCENT, false),
                Ui.margins(this, -1, -2, 0, 7, 0, 12));
        for (int i = 0; i < EpgSync.SOURCES.length; i++) {
            epgCard.addView(Ui.text(this, EpgSync.SOURCES[i], 11, Ui.SECONDARY, false));
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
        actions.addView(Ui.button(this, "＋  Dodaj plik M3U lub konto IPTV", true,
                v -> showTab("Źródła")), Ui.margins(this, -1, -2, 0, 12, 0, 12));
        actions.addView(Ui.text(this, "Każdą listę możesz usunąć osobno w sekcji «Aktywna lista kanałów».",
                12, Ui.SECONDARY, false));
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
                    if (returnHome) showTab("Telewizja");
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
            // ACTION_GET_CONTENT częściej obsługują menedżery plików z Android Boxów.
            candidates.add(filePicker(Intent.ACTION_GET_CONTENT, false));
            candidates.add(filePicker(Intent.ACTION_GET_CONTENT, true));
            candidates.add(filePicker(Intent.ACTION_OPEN_DOCUMENT, true));
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
        handleExternalPlaylist(intent);
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
                return "▶ Kontynuuj od " + (channel.playbackMs / 60000) + " min";
            return "Film VOD • " + channel.group;
        }
        if (channel.isSeries()) return "Serial • zobacz sezony i odcinki";
        if (guide != null && guide.current != null && !guide.current.isEmpty())
            return "● Teraz: " + guide.current;
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
            if (tiled) return makeTile(channel, guide);
            return makeRow(channel, guide);
        }

        private View makeRow(Channel channel, ChannelDatabase.Guide guide) {
            LinearLayout row = Ui.row(MainActivity.this);
            if (tvMode) row.setContentDescription("Odtwórz: " + channel.name);
            row.setPadding(Ui.dp(MainActivity.this, 12), Ui.dp(MainActivity.this, 11),
                    Ui.dp(MainActivity.this, 10), Ui.dp(MainActivity.this, 11));
            row.setBackground(Ui.rounded(MainActivity.this, Ui.SURFACE, 15));
            row.addView(logo(channel, 56), new LinearLayout.LayoutParams(
                    Ui.dp(MainActivity.this, 56), Ui.dp(MainActivity.this, 56)));

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

            TextView favorite = Ui.text(MainActivity.this, channel.favorite ? "♥" : "♡",
                    25, channel.favorite ? 0xFFFF728D : Ui.MUTED, false);
            favorite.setGravity(Gravity.CENTER);
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

            FrameLayout art = new FrameLayout(MainActivity.this);
            FrameLayout symbol = logo(channel, 100);
            boolean poster = channel.vod || channel.isSeries();
            FrameLayout.LayoutParams center = new FrameLayout.LayoutParams(
                    Ui.dp(MainActivity.this, poster ? 126 : 94),
                    Ui.dp(MainActivity.this, poster ? 154 : 94), Gravity.CENTER);
            art.addView(symbol, center);
            TextView heart = Ui.text(MainActivity.this, channel.favorite ? "♥" : "♡", 25,
                    channel.favorite ? 0xFFFF728D : Ui.WHITE, false);
            heart.setGravity(Gravity.CENTER);
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
                    poster ? 160 : 102)));

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
            if (!poster) tile.addView(Ui.text(MainActivity.this,
                    "Nr " + (liveIndex(channel) + 1), 10, Ui.MUTED, false));
            return tile;
        }
    }

    private int liveIndex(Channel target) {
        int index = 0;
        for (Channel item : allChannels) {
            if (item.vod || item.isSeries() || item.isEpisode()) continue;
            if (item.id == target.id) return index;
            index++;
        }
        return index;
    }
}
