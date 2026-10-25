package pl.rafik.ssiptv.epg;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

import pl.rafik.ssiptv.data.Channel;
import pl.rafik.ssiptv.data.ChannelDatabase;
import pl.rafik.ssiptv.data.SecureSettings;

/** Automatyczny przewodnik XMLTV; cztery źródła i dopasowanie po tvg-id/nazwie. */
public final class EpgSync {
    public static final String[] SOURCES = {
            "https://epg.ovh/pltv.xml",
            "https://epg.polishteam.one/guide.xml",
            "https://epg.ovh/pl.xml",
            "https://epg.ovh/plar.xml"
    };
    // Źródło epg.ovh publikuje pięciodniowe przewodniki. Harmonogram programów
    // zapisujemy w SQLite i aktualizujemy tylko po pięciu dniach.
    public static final long REFRESH_INTERVAL_MS = EpgRefreshPolicy.INTERVAL_MS;
    private static final long MAX_XML_BYTES = 140L * 1024 * 1024;
    private static final int JOB_ID = 271041;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private EpgSync() {}

    public static void schedule(Context ctx) {
        JobScheduler scheduler = ctx.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        if (scheduler.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, EpgJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(REFRESH_INTERVAL_MS)
                .build();
        scheduler.schedule(job);
    }

    public static void refreshIfStale(Context context) {
        Context app = context.getApplicationContext();
        if (isRefreshDue(app)) {
            Thread worker = new Thread(() -> refresh(app), "ss-epg-initial");
            worker.start();
        }
    }

    static boolean isRefreshDue(Context context) {
        SecureSettings settings = new SecureSettings(context);
        return EpgRefreshPolicy.isDue(System.currentTimeMillis(),
                settings.lastSync(), settings.lastEpgAttempt());
    }

    public static String refresh(Context context) {
        if (!RUNNING.compareAndSet(false, true)) return "Synchronizacja EPG już trwa";
        try {
            return performRefresh(context.getApplicationContext());
        } finally {
            RUNNING.set(false);
        }
    }

    private static String performRefresh(Context context) {
        ChannelDatabase db = new ChannelDatabase(context);
        SecureSettings settings = new SecureSettings(context);
        settings.saveEpgAttempt(System.currentTimeMillis());
        List<Channel> channels = db.allChannels();
        if (channels.isEmpty()) return "Dodaj kanały, aby dopasować program EPG";
        Map<String, List<Long>> keys = new HashMap<>();
        for (Channel c : channels) {
            if (c.vod) continue;
            addKey(keys, c.tvgId, c.id);
            addKey(keys, c.name, c.id);
        }
        if (keys.isEmpty()) return "Brak kanałów TV do dopasowania — EPG nie jest potrzebne dla VOD";

        int sourceOk = -1;
        int stored = 0;
        long now = System.currentTimeMillis();
        for (int i = 0; i < SOURCES.length; i++) {
            if (Thread.currentThread().isInterrupted()) break;
            if (sourceOk >= 0) {
                settings.saveEpgSourceStatus(i, "Pominięto — EPG zapisano ze źródła " + (sourceOk + 1));
                continue;
            }
            settings.saveEpgSourceStatus(i, "Trwa odczyt…");
            try (ChannelDatabase.EpgImport writer = db.beginEpgImport()) {
                // Oficjalne .gz są nawet 8-10 razy mniejsze niż surowy XMLTV.
                String url = SOURCES[i].startsWith("https://epg.ovh/") ?
                        SOURCES[i].replace(".xml", ".gz") : SOURCES[i];
                int matched = readXmltv(url, keys, writer, now);
                if (matched == 0) throw new IOException("Brak dopasowań do listy kanałów");
                writer.commit();
                stored = matched;
                sourceOk = i;
                settings.saveEpgSourceStatus(i, "Zapisano " + matched +
                        " audycji • " +
                        new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
                                .format(new Date(System.currentTimeMillis())));
            } catch (Exception e) {
                String reason = connectionError(e);
                if (reason.length() > 70) reason = reason.substring(0, 70);
                settings.saveEpgSourceStatus(i, "Niedostępne: " + reason + " · dane lokalne zachowane");
            }
        }
        if (sourceOk >= 0) {
            String status = "EPG offline: " + stored + " audycji • źródło " +
                    (sourceOk + 1) + "/4 • ponowne pobranie za 5 dni";
            settings.saveEpgStatus(System.currentTimeMillis(), status);
            return status;
        }
        String status = "Źródła EPG niedostępne — używam zapisanych danych. " +
                "Kolejna próba za 6 godzin";
        settings.saveEpgStatus(0, status);
        return status;
    }

    private static String connectionError(Exception error) {
        if (error instanceof SocketTimeoutException) return "Przekroczono czas połączenia";
        if (error instanceof UnknownHostException) return "Nie można odnaleźć serwera";
        if (error instanceof ConnectException) return "Serwer odrzucił połączenie";
        String description = error.getMessage();
        if (description == null || description.isEmpty()) return error.getClass().getSimpleName();
        if (description.contains("failed to connect") || description.contains("timed out"))
            return "Serwer nie odpowiada";
        return description;
    }

    private static void addKey(Map<String, List<Long>> keys, String text, long id) {
        for (String key : EpgMatcher.keys(text)) {
            List<Long> ids = keys.computeIfAbsent(key, ignored -> new ArrayList<>());
            if (!ids.contains(id)) ids.add(id);
        }
    }

    private static List<Long> matchingIds(Map<String, List<Long>> keys, String name) {
        for (String key : EpgMatcher.keys(name)) {
            List<Long> match = keys.get(key);
            if (match != null && !match.isEmpty()) return match;
        }
        return null;
    }

    private static int readXmltv(String address, Map<String, List<Long>> keys,
                                  ChannelDatabase.EpgImport writer,
                                  long now) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(6500);
        conn.setReadTimeout(18000);
        conn.setRequestProperty("Accept-Encoding", "gzip");
        conn.setRequestProperty("User-Agent", "SS-IPTV/1.0 (XMLTV)");
        try {
            if (conn.getResponseCode() / 100 != 2) {
                throw new IOException("HTTP " + conn.getResponseCode());
            }
            try (InputStream raw = conn.getInputStream()) {
                InputStream decoded = "gzip".equalsIgnoreCase(conn.getContentEncoding()) ||
                        address.endsWith(".gz") ? new GZIPInputStream(raw) : raw;
                InputStream bounded = new FilterInputStream(decoded) {
                    long readCount;
                    @Override public int read() throws IOException {
                        int one = super.read();
                        if (one != -1 && ++readCount > MAX_XML_BYTES) throw new IOException("EPG zbyt duże");
                        return one;
                    }
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        int n = super.read(b, off, len);
                        if (n > 0 && (readCount += n) > MAX_XML_BYTES) throw new IOException("EPG zbyt duże");
                        return n;
                    }
                };
                return parse(bounded, keys, writer, now);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static int parse(InputStream xml, Map<String, List<Long>> keys,
                              ChannelDatabase.EpgImport writer,
                              long now) throws Exception {
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(xml, null);
        Map<String, List<Long>> ids = new HashMap<>();
        String stationId = "";
        String stationName = "";
        List<Long> stationMatches = new ArrayList<>();
        String programmeId = "";
        String programmeTitle = "";
        String programmeDescription = "";
        long start = 0;
        long stop = 0;
        int type = parser.getEventType();
        int applied = 0;
        while (type != XmlPullParser.END_DOCUMENT) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Przerwano EPG");
            if (type == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if ("channel".equals(tag)) {
                    stationId = parser.getAttributeValue(null, "id");
                    stationName = "";
                    stationMatches.clear();
                    List<Long> match = matchingIds(keys, stationId);
                    if (match != null) stationMatches.addAll(match);
                } else if ("display-name".equals(tag) && stationId != null && !stationId.isEmpty()) {
                    stationName = parser.nextText();
                    List<Long> match = matchingIds(keys, stationName);
                    if (match != null) for (Long id : match) {
                        if (!stationMatches.contains(id)) stationMatches.add(id);
                    }
                } else if ("programme".equals(tag)) {
                    programmeId = parser.getAttributeValue(null, "channel");
                    start = parseDate(parser.getAttributeValue(null, "start"));
                    stop = parseDate(parser.getAttributeValue(null, "stop"));
                    programmeTitle = "";
                    programmeDescription = "";
                } else if ("title".equals(tag) && programmeId != null && !programmeId.isEmpty()) {
                    programmeTitle = parser.nextText();
                } else if ("desc".equals(tag) && programmeId != null && !programmeId.isEmpty()) {
                    programmeDescription = parser.nextText();
                }
            } else if (type == XmlPullParser.END_TAG) {
                String tag = parser.getName();
                if ("channel".equals(tag) && stationId != null) {
                    if (stationMatches.isEmpty()) {
                        List<Long> match = matchingIds(keys, stationName);
                        if (match != null) stationMatches.addAll(match);
                    }
                    if (!stationMatches.isEmpty()) ids.put(stationId,
                            new ArrayList<>(stationMatches));
                    stationId = "";
                    stationName = "";
                } else if ("programme".equals(tag) && programmeId != null) {
                    List<Long> matched = ids.get(programmeId);
                    if (matched == null) matched = matchingIds(keys, programmeId);
                    if (matched != null && !programmeTitle.trim().isEmpty() &&
                            stop > now && start < now + REFRESH_INTERVAL_MS && stop > start) {
                        String title = programmeTitle.trim();
                        if (title.length() > 180) title = title.substring(0, 180);
                        String details = programmeDescription.length() > 2000 ?
                                programmeDescription.substring(0, 2000) : programmeDescription;
                        for (long id : matched) {
                            writer.add(id, start, stop, title, details);
                            applied++;
                        }
                    }
                    programmeId = "";
                }
            }
            type = parser.next();
        }
        return applied;
    }

    private static long parseDate(String xmlDate) {
        if (xmlDate == null) return 0;
        String[] parts = xmlDate.trim().split("\\s+");
        if (parts.length == 0 || parts[0].length() < 12) return 0;
        String digits = parts[0];
        if (digits.length() == 12) digits += "00";
        if (digits.length() < 14) return 0;
        String zone = parts.length > 1 && parts[1].matches("[+-][0-9]{4}") ? parts[1] : "+0000";
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Date parsed = format.parse(digits.substring(0, 14) + " " + zone);
            return parsed == null ? 0 : parsed.getTime();
        } catch (ParseException e) {
            return 0;
        }
    }
}
