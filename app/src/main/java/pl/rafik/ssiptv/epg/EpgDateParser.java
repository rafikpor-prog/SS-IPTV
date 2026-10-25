package pl.rafik.ssiptv.epg;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Szybka konwersja dat XMLTV bez regexów i tworzenia SimpleDateFormat dla każdej audycji. */
public final class EpgDateParser {
    private EpgDateParser() {}

    public static long parse(String value) {
        if (value == null) return 0;
        String s = value.trim();
        if (s.length() < 12) return 0;
        int year = number(s, 0, 4);
        int month = number(s, 4, 6);
        int day = number(s, 6, 8);
        int hour = number(s, 8, 10);
        int minute = number(s, 10, 12);
        int second = s.length() > 12 && s.charAt(12) >= '0' && s.charAt(12) <= '9' ?
                number(s, 12, 14) : 0;
        if (year < 0 || month < 0 || day < 0 || hour < 0 || minute < 0 || second < 0)
            return 0;
        int offsetMinutes = 0;
        int start = s.indexOf(' ');
        if (start >= 0) {
            while (start < s.length() && s.charAt(start) == ' ') start++;
            if (start < s.length()) {
                char sign = s.charAt(start);
                if ((sign != '+' && sign != '-') || start + 5 > s.length()) return 0;
                int zoneHours = number(s, start + 1, start + 3);
                int zoneMinutes = number(s, start + 3, start + 5);
                if (zoneHours < 0 || zoneMinutes < 0 || zoneMinutes > 59) return 0;
                offsetMinutes = (zoneHours * 60 + zoneMinutes) * (sign == '-' ? -1 : 1);
            }
        }
        try {
            return LocalDateTime.of(year, month, day, hour, minute, second)
                    .toEpochSecond(ZoneOffset.ofTotalSeconds(offsetMinutes * 60)) * 1000L;
        } catch (DateTimeException e) {
            return 0;
        }
    }

    private static int number(String s, int start, int end) {
        if (s.length() < end) return -1;
        int value = 0;
        for (int i = start; i < end; i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return -1;
            value = value * 10 + ch - '0';
        }
        return value;
    }
}
