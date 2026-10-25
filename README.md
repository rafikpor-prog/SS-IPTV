# SS-IPTV

Niezależna, natywna aplikacja Android do odtwarzania własnych źródeł IPTV. Projekt powstał od zera; nie zawiera kanałów, playlist ani dostępu do żadnego operatora.

## Funkcje 1.0.0

- Import pliku Extended M3U przez systemowy wybór pliku albo pobranie playlisty z URL.
- Fizyczny zapis ostatniej playlisty w prywatnym katalogu aplikacji jako plik playlist.m3u oraz zapis kanałów w SQLite.
- Kategorie z group-title / EXTGRP, wyszukiwarka i ulubione.
- Logowanie do kompatybilnego serwera Xtream Codes przez adres URL, login i hasło, odczyt kategorii i kanałów Live z player_api.php.
- Dane logowania szyfrowane AES-256-GCM kluczem z Android Keystore. Tokenów z Xtream nie trzymamy jako pełnych URL w bazie kanałów.
- Odtwarzanie Media3/ExoPlayer: HLS, typowe MPEG-TS, RTSP oraz progresywne formaty wspierane przez ExoPlayer.
- Automatyczne EPG XMLTV przy uruchomieniu i po imporcie kanałów; w tle cyklicznie co 12 godzin na sieci nielimitowanej/Wi-Fi (JobScheduler), ręczne odświeżanie i lokalny cache.
- Cztery źródła EPG: epg.ovh/pltv.xml, epg.polishteam.one/guide.xml, epg.ovh/pl.xml, epg.ovh/plar.xml.
- Polski interfejs w ciemnym stylu, własna ikona, podgląd aktualnego programu na kanałach.

## Budowanie

Wymagania: JDK 17, Android SDK Platform 36, Build Tools 36.0.0 i sieć do pierwszego pobrania zależności. Projekt używa Android Gradle Plugin 8.9.1, Gradle 8.11.1, Media3 1.8.1. Android od API 26.

    ./gradlew :app:testDebugUnitTest :app:assembleDebug

Plik: app/build/outputs/apk/debug/app-debug.apk.

## Wydania z tym samym certyfikatem

Prywatny plik JKS i hasła przechowuj **poza publicznym repozytorium**. Klucz, którym podpisano pierwszą wersję, musi zostać zachowany do kolejnych aktualizacji pakietu pl.rafik.ssiptv. Zmień versionCode przy każdej aktualizacji, nie zmieniaj applicationId.

    ./gradlew :app:assembleRelease \
      -PsigningStoreFile=/bezpieczna/sciezka/ss-iptv-release.jks \
      -PsigningStorePassword=HASLO_KLUCZA \
      -PsigningKeyAlias=ss-iptv-release \
      -PsigningKeyPassword=HASLO_KLUCZA

Uwaga: parametry wiersza poleceń mogą być widoczne w historii terminala; w automatyzacji używaj tajnych zmiennych / konfiguracji poza repo. Ścieżki plików klucza i jego hasła znajdują się na liście .gitignore.

## Bezpieczeństwo i ograniczenia

- Aplikacja używa tylko źródeł podanych przez użytkownika. Nie omija DRM ani zabezpieczeń dostępu.
- Serwery HTTP i strumienie HTTP są obsługiwane dla zgodności z IPTV, lecz loginy przesyłane HTTP **nie są chronione szyfrowaniem**. Zalecane jest HTTPS.
- EPG jest zależne od dostępności zewnętrznych serwerów i jakości identyfikatorów tvg-id. W pierwszej wersji przechowywany jest bieżący i następny program, bez pełnej siatki 7-dniowej.
- Odtwarzanie może zależeć od kodeków urządzenia i sposobu dostarczania streamu przez operatora.
- Brak synchronizacji pomiędzy urządzeniami: listy i ulubione pozostają wyłącznie lokalnie.
- Nie testowano wszystkich serwerów Xtream. Niektóre niestandardowe odmiany API mogą wymagać dostosowania.
- Import zastępuje poprzednią listę M3U; konto Xtream stanowi drugie, niezależne źródło kanałów.

## Rozwój

Źródła w app/src/main/java/pl/rafik/ssiptv, testy parsera w app/src/test. Workflow .github/workflows/android.yml tworzy debug APK przy każdej zmianie. Klucza release nie wgrywaj na GitHub; podpisuj wydania lokalnie lub w bezpiecznej usłudze CI z sekretami.

© Projekt użytkownika repozytorium SS-IPTV.
