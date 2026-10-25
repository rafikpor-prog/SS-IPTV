package pl.rafik.ssiptv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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

/** Ekrany główne SS-IPTV. Cały interfejs jest natywny i działa bez WebView. */
public final class MainActivity extends Activity {
    private static final int PICK_PLAYLIST = 4301;
    private static final String ALL = "Wszystkie";
    private static final String FAVORITES = "Ulubione";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ChannelDatabase database;
    private SecureSettings secure;
    private LinearLayout main;
    private LinearLayout content;
    private LinearLayout chips;
    private ListView channelList;
    private TextView sectionCaption;
    private TextView sourceStatus;
    private ChannelAdapter adapter;
    private String tab = "Kanały";
    private String selectedGroup = ALL;
    private String query = "";
    private List<Channel> allChannels = new ArrayList<>();
    private Map<Long, ChannelDatabase.Guide> guides = new HashMap<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        database = new ChannelDatabase(this);
        secure = new SecureSettings(this);
        showTab("Kanały");
        reloadData();
        EpgSync.schedule(this);
        EpgSync.refreshIfStale(this);
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
            Map<Long, ChannelDatabase.Guide> programs = database.readGuides();
            runOnUiThread(() -> {
                allChannels = entries;
                guides = programs;
                if ("Kanały".equals(tab)) {
                    updateChips();
                    filterChannels();
                } else if ("EPG".equals(tab)) {
                    showTab("EPG");
                }
            });
        });
    }

    private void showTab(String name) {
        tab = name;
        main = Ui.column(this);
        main.setBackgroundColor(Ui.BG);
        setContentView(main);
        main.addView(brandHeader(), new LinearLayout.LayoutParams(-1, Ui.dp(this, 78)));
        content = Ui.column(this);
        main.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        switch (name) {
            case "Źródła": showSources(); break;
            case "EPG": showGuide(); break;
            case "Więcej": showSettings(); break;
            default: showChannels(); break;
        }
        main.addView(bottomNavigation(), new LinearLayout.LayoutParams(-1, Ui.dp(this, 68)));
    }

    private View brandHeader() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 8));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bar.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 47), Ui.dp(this, 47)));

        LinearLayout titles = Ui.column(this);
        titles.setPadding(Ui.dp(this, 10), 0, 0, 0);
        titles.addView(Ui.text(this, "SS-IPTV", 21, Ui.WHITE, true));
        TextView slogan = Ui.text(this, "TWOJA TELEWIZJA. TWÓJ WYBÓR.", 9, Ui.ACCENT, true);
        slogan.setLetterSpacing(0.08f);
        titles.addView(slogan);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));

        TextView source = Ui.text(this, "●  LIVE", 11, Ui.ACCENT, true);
        source.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        source.setBackground(Ui.rounded(this, Ui.SURFACE, 50));
        bar.addView(source);
        return bar;
    }

    private View bottomNavigation() {
        LinearLayout bottom = Ui.row(this);
        bottom.setPadding(Ui.dp(this, 9), Ui.dp(this, 4), Ui.dp(this, 9), Ui.dp(this, 5));
        bottom.setBackgroundColor(Ui.SURFACE);
        String[] names = {"Kanały", "Źródła", "EPG", "Więcej"};
        String[] icons = {"▣", "⊕", "▤", "⚙"};
        for (int i = 0; i < names.length; i++) {
            final String itemName = names[i];
            boolean chosen = itemName.equals(tab);
            LinearLayout item = Ui.column(this);
            item.setGravity(Gravity.CENTER);
            TextView icon = Ui.text(this, icons[i], 23, chosen ? Ui.ACCENT : Ui.MUTED, false);
            icon.setGravity(Gravity.CENTER);
            TextView label = Ui.text(this, itemName, 11, chosen ? Ui.ACCENT : Ui.SECONDARY, chosen);
            label.setGravity(Gravity.CENTER);
            item.addView(icon);
            item.addView(label);
            item.setOnClickListener(v -> showTab(itemName));
            bottom.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
        return bottom;
    }

    private void showChannels() {
        LinearLayout home = Ui.column(this);
        home.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        content.addView(home, new LinearLayout.LayoutParams(-1, -1));

        GradientDrawable heroBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF16435B, 0xFF10243F, 0xFF211E45});
        heroBg.setCornerRadius(Ui.dp(this, 22));
        LinearLayout hero = Ui.column(this);
        hero.setBackground(heroBg);
        hero.setPadding(Ui.dp(this, 22), Ui.dp(this, 16), Ui.dp(this, 22), Ui.dp(this, 14));
        TextView eyebrow = Ui.text(this, "ODKRYWAJ • OGLĄDAJ • WRACAJ", 10, Ui.ACCENT, true);
        eyebrow.setLetterSpacing(0.12f);
        hero.addView(eyebrow);
        TextView slogan = Ui.text(this, "Wszystkie kanały.\nJedno miejsce.", 23, Ui.WHITE, true);
        slogan.setLineSpacing(0, 0.93f);
        hero.addView(slogan, Ui.margins(this, -1, -2, 0, 7, 0, 1));
        TextView heroFoot = Ui.text(this, "Twoje listy M3U, konto IPTV i EPG", 12, Ui.SECONDARY, false);
        hero.addView(heroFoot);
        home.addView(hero, Ui.margins(this, -1, -2, 0, 0, 0, 14));

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
        home.addView(categories, Ui.margins(this, -1, 48, 0, 0, 0, 5));
        updateChips();

        LinearLayout heading = Ui.row(this);
        TextView title = Ui.text(this, "Kanały na żywo", 18, Ui.WHITE, true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        sectionCaption = Ui.text(this, "0 kanałów", 12, Ui.ACCENT, true);
        heading.addView(sectionCaption);
        home.addView(heading, Ui.margins(this, -1, 42, 0, 0, 0, 4));

        FrameLayout listArea = new FrameLayout(this);
        channelList = new ListView(this);
        channelList.setDivider(null);
        channelList.setDividerHeight(Ui.dp(this, 6));
        channelList.setSelector(android.R.color.transparent);
        channelList.setCacheColorHint(Ui.BG);
        channelList.setVerticalScrollBarEnabled(false);
        adapter = new ChannelAdapter();
        channelList.setAdapter(adapter);
        channelList.setOnItemClickListener((parent, view, position, id) -> {
            Channel channel = adapter.visible.get(position);
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra(PlayerActivity.EXTRA_ID, channel.id);
            startActivity(intent);
        });
        listArea.addView(channelList, new FrameLayout.LayoutParams(-1, -1));
        TextView empty = Ui.text(this, "Brak kanałów.\nDodaj playlistę M3U lub konto w zakładce Źródła.", 15,
                Ui.SECONDARY, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(this, 30), Ui.dp(this, 10), Ui.dp(this, 30), Ui.dp(this, 10));
        listArea.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        channelList.setEmptyView(empty);
        home.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1));
        filterChannels();
    }

    private void updateChips() {
        if (chips == null || !"Kanały".equals(tab)) return;
        chips.removeAllViews();
        Map<String, Integer> groups = new LinkedHashMap<>();
        for (Channel channel : allChannels) {
            groups.put(channel.group, groups.getOrDefault(channel.group, 0) + 1);
        }
        ArrayList<String> names = new ArrayList<>(groups.keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        ArrayList<String> filterNames = new ArrayList<>();
        filterNames.add(ALL);
        filterNames.add(FAVORITES);
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
            chips.addView(chip, Ui.margins(this, -2, 34, 0, 0, 8, 0));
        }
    }

    private void filterChannels() {
        if (adapter == null || !"Kanały".equals(tab)) return;
        ArrayList<Channel> result = new ArrayList<>();
        for (Channel c : allChannels) {
            if (FAVORITES.equals(selectedGroup) && !c.favorite) continue;
            if (!ALL.equals(selectedGroup) && !FAVORITES.equals(selectedGroup) &&
                    !c.group.equals(selectedGroup)) continue;
            ChannelDatabase.Guide guide = guides.get(c.id);
            String prog = guide == null || guide.current == null ? "" : guide.current;
            if (!query.isEmpty() && !c.name.toLowerCase(Locale.ROOT).contains(query) &&
                    !prog.toLowerCase(Locale.ROOT).contains(query)) continue;
            result.add(c);
        }
        adapter.visible = result;
        adapter.notifyDataSetChanged();
        if (sectionCaption != null) sectionCaption.setText(result.size() + " kanałów");
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
        TextView importButton = Ui.button(this, "Wybierz plik M3U  ＋", true, v -> {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.setType("*/*");
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(picker, PICK_PLAYLIST);
        });
        m3u.addView(importButton, Ui.margins(this, -1, -2, 0, 3, 0, 12));
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
                List<Channel> items = new XtreamClient(credentials).fetchLiveChannels();
                database.replaceSource(ChannelDatabase.XTREAM, items);
                secure.saveServer(normalized, username, pass);
                return "Pobrano " + items.size() + " kanałów z serwera";
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
                        List<Channel> items = new XtreamClient(creds).fetchLiveChannels();
                        database.replaceSource(ChannelDatabase.XTREAM, items);
                        return "Odświeżono " + items.size() + " kanałów";
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
            LinearLayout row = Ui.row(this);
            row.setPadding(Ui.dp(this, 13), Ui.dp(this, 11), Ui.dp(this, 10), Ui.dp(this, 11));
            row.setBackground(Ui.rounded(this, Ui.SURFACE, 13));
            TextView number = Ui.text(this, String.format(Locale.ROOT, "%02d", i + 1),
                    14, Ui.ACCENT, true);
            row.addView(number, Ui.margins(this, 34, -1, 0, 0, 5, 0));
            TextView address = Ui.text(this, EpgSync.SOURCES[i], 11, Ui.SECONDARY, false);
            address.setMaxLines(2);
            row.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
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
            item.setOnClickListener(v -> play(channel));
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
        about.addView(Ui.text(this, "SS-IPTV  •  wersja 1.0.0", 19, Ui.WHITE, true));
        about.addView(Ui.text(this, "Niezależny odtwarzacz Twoich własnych źródeł IPTV. " +
                "Aplikacja nie dostarcza kanałów ani subskrypcji.",
                13, Ui.SECONDARY, false), Ui.margins(this, -1, -2, 0, 8, 0, 10));
        about.addView(Ui.text(this, "Listy M3U są w prywatnej pamięci aplikacji. Hasło " +
                "serwera jest szyfrowane przez Android Keystore.",
                12, Ui.ACCENT, false));
        page.addView(about, Ui.margins(this, -1, -2, 0, 0, 0, 16));

        LinearLayout actions = card();
        actions.addView(Ui.text(this, "ZARZĄDZAJ DANYMI", 11, Ui.MUTED, true));
        actions.addView(Ui.button(this, "Usuń listę M3U z urządzenia", false,
                v -> confirmDelete("Usunąć lokalną playlistę M3U?", () -> {
                    database.deleteSource(ChannelDatabase.M3U);
                    new java.io.File(getFilesDir(), "playlist.m3u").delete();
                })), Ui.margins(this, -1, -2, 0, 12, 0, 10));
        actions.addView(Ui.button(this, "Usuń konto IPTV i jego kanały", false,
                v -> confirmDelete("Usunąć zapisane konto i kanały IPTV?", () -> {
                    database.deleteSource(ChannelDatabase.XTREAM);
                    secure.clearServer();
                })));
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
                    if (returnHome) showTab("Kanały");
                    else if ("EPG".equals(tab)) showTab("EPG");
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
                            showTab("Kanały");
                            reloadData();
                        });
                    });
                }).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_PLAYLIST && resultCode == RESULT_OK &&
                data != null && data.getData() != null) {
            Uri uri = data.getData();
            performTask("Importowanie pliku M3U…", () -> {
                int amount = PlaylistLoader.importDocument(getApplicationContext(), uri).size();
                return "Zaimportowano " + amount + " kanałów";
            }, true);
        }
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
            LinearLayout row = Ui.row(MainActivity.this);
            row.setPadding(Ui.dp(MainActivity.this, 12), Ui.dp(MainActivity.this, 11),
                    Ui.dp(MainActivity.this, 10), Ui.dp(MainActivity.this, 11));
            row.setBackground(Ui.rounded(MainActivity.this, Ui.SURFACE, 15));

            TextView channelIcon = Ui.text(MainActivity.this,
                    channel.name.substring(0, Math.min(2, channel.name.length())).toUpperCase(Locale.ROOT),
                    15, Ui.ACCENT, true);
            channelIcon.setGravity(Gravity.CENTER);
            channelIcon.setBackground(Ui.rounded(MainActivity.this, Ui.CARD, 13));
            row.addView(channelIcon, new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 48),
                    Ui.dp(MainActivity.this, 48)));

            LinearLayout labels = Ui.column(MainActivity.this);
            labels.setPadding(Ui.dp(MainActivity.this, 12), 0, Ui.dp(MainActivity.this, 5), 0);
            TextView name = Ui.text(MainActivity.this, channel.name, 15, Ui.WHITE, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(name);
            String category = channel.group;
            TextView group = Ui.text(MainActivity.this, category, 11, Ui.MUTED, false);
            group.setSingleLine(true);
            labels.addView(group);
            if (guide != null && guide.current != null && !guide.current.isEmpty()) {
                TextView now = Ui.text(MainActivity.this, "●  " + guide.current, 11, Ui.ACCENT, false);
                now.setSingleLine(true);
                now.setEllipsize(android.text.TextUtils.TruncateAt.END);
                labels.addView(now);
            }
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

            TextView favorite = Ui.text(MainActivity.this, channel.favorite ? "★" : "☆",
                    24, channel.favorite ? 0xFFFFBE69 : Ui.MUTED, false);
            favorite.setGravity(Gravity.CENTER);
            favorite.setContentDescription(channel.favorite ? "Usuń z ulubionych" : "Dodaj do ulubionych");
            favorite.setOnClickListener(v -> {
                channel.favorite = !channel.favorite;
                boolean flag = channel.favorite;
                io.execute(() -> database.setFavorite(channel.id, flag));
                filterChannels();
            });
            row.addView(favorite, new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 38),
                    Ui.dp(MainActivity.this, 50)));
            return row;
        }
    }
}
