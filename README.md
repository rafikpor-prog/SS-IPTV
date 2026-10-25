# SS-IPTV

Niezależna, natywna aplikacja Android do odtwarzania własnych źródeł IPTV. Projekt powstał od zera; nie zawiera kanałów, playlist ani dostępu do żadnego operatora.

## Funkcje 1.3.0

### Nowy interfejs Android TV (1.3.0)

- Na telewizorze i Android Box ekran **Telewizja** zajmuje praktycznie całe okno: pełna przewijana lista z logotypem, czytelną nazwą kanału, numerem i aktualnym programem. Nie ma nakładających się zakładek, wyszukiwarki ani dolnego paska nawigacji.
- **←** na pierwszym kafelku/wierszu albo przycisk **☰** otwiera lewe menu. Na jego początku znajduje się **Kategorie**, a po wejściu lista kategorii M3U z osobnym przyciskiem powrotu. W menu są też Telewizja, VOD, Seriale, Ulubione, Ostatnie, EPG, Źródła, Ustawienia, przełącznik listy/kafelków.
- **↑↓** przesuwa widoczne zaznaczenie między kanałami, **OK** uruchamia odtwarzanie zaznaczonej pozycji. Menu i kafelki pokazują wyraźną turkusową ramkę fokusu (nawet przy ciemnym tle); **czerwony** dodaje/usuwa zaznaczony kanał do ulubionych.
- Kafelki są jednostką wyboru. Serduszka w środku nie przejmują pilota; przycisk OK działa na całym wierszu lub kafelku. W widoku telewizji na żywo ukryto natywne kontrolki Media3, aby nie przechwytywały strzałek **↑↓** zmieniających kanały. W VOD kontrolki pauzy i przewijania zostają.
- Tryb TV jest włączany automatycznie na urządzeniach Leanback, z konfiguracją telewizyjną lub pilotem D-pad. W **Ustawienia → Interfejs ekranu** można go ręcznie włączyć na nietypowym Android Box, który nie deklaruje wsparcia Android TV. Dostępne jest też przełączenie na układ mobilny.
- **MENU** otwiera/zamyka lewe menu; **Wstecz** najpierw zamyka podmenu kategorii, potem całe menu. Listy i kategorie M3U, ulubione, historia oraz logowanie pozostają zachowane po aktualizacji.

### Pozostałe funkcje

- Import pliku Extended M3U przez systemowy wybór pliku albo pobranie playlisty z URL.
- Każdy kolejny import M3U tworzy **oddzielną listę** z własnym plikiem w prywatnej pamięci urządzenia; poprzednie listy i ulubione nie są usuwane. W ustawieniach można wybrać aktywną listę albo połączyć widok wszystkich list.
- Kategorie z group-title / EXTGRP, wyszukiwarka i ulubione.
- Logowanie do kompatybilnego serwera Xtream Codes przez adres URL, login i hasło, odczyt kategorii i kanałów Live z player_api.php.
- Dane logowania szyfrowane AES-256-GCM kluczem z Android Keystore. Tokenów z Xtream nie trzymamy jako pełnych URL w bazie kanałów.
- Odtwarzanie Media3/ExoPlayer: HLS, typowe MPEG-TS, RTSP oraz progresywne formaty wspierane przez ExoPlayer.
- Automatyczne EPG XMLTV przy uruchomieniu i po imporcie kanałów; w tle cyklicznie co 12 godzin na sieci nielimitowanej/Wi-Fi (JobScheduler), ręczne odświeżanie i lokalny cache.
- Cztery źródła EPG: epg.ovh/pltv.xml, epg.polishteam.one/guide.xml, epg.ovh/pl.xml, epg.ovh/plar.xml.
- Polski interfejs w ciemnym stylu, własna ikona, podgląd aktualnego programu na kanałach.
- Widoczne u góry przyciski **+ Źródło** i **Ustawienia** oraz prawidłowy margines pod paskami systemowymi Androida.
- Na telefonach szybki dostęp do **Telewizja / VOD / Seriale / Ulubione / Ostatnie / Źródła** na dolnym pasku; na TV w wysuwanym panelu.
- Kanały i filmy jako **lista albo kafelki**, z automatycznie pobieranymi logotypami, kategorią i opisem programu.
- Serwery Xtream: Live, filmy (`get_vod_categories`, `get_vod_streams`) i seriale (`get_series_categories`, `get_series`, `get_series_info`). Zawartość serialu jest pobierana po otwarciu karty, następnie rozdzielana na sezony i odcinki. Proste seriale w M3U rozpoznawane są z nazw `S01E01`, `S01E02` itp.
- Karty filmów i seriali z okładką, pełnym opisem dostarczonym przez serwer, przyciskiem odtwarzania lub wznowienia oraz listą odcinków. Każdy film i **każdy odcinek** ma własny lokalnie zapamiętany postęp.
- OSD po rozpoczęciu odtwarzania: nazwa, logotyp, aktualny/następny program, serduszko i znaczek jakości obrazu SD / HD / FULL HD / 4K ustalany z rozdzielczości odtwarzanego wideo.
- W ustawieniach bieżący wynik odczytu każdego z czterech źródeł EPG oraz ręczne odświeżanie.
- Android TV: pozycja uruchamiania w launcherze TV z własnym banerem 16:9; sterowanie pilotem i podświetlanie elementów wybieranych D-padem.
- Pilot podczas odtwarzania telewizji: **CH+/CH− i ↑/↓** zmiana kanału; numery **1–3 cyfry** + OK (lub 1,6 s bezczynności) wybierają kolejny numer pozycji na aktywnej liście. **Czerwony** włącza/wyłącza ulubione, **zielony** pokazuje/ukrywa OSD, **żółty** rozwija/zwija opis, **niebieski** pokazuje/ukrywa panel ustawień oraz wyboru listy.
- Dla pilotów bez kolorów: **OK** otwiera OSD, **←** odsłania przyciski ekranowe, **→** otwiera opis; w OSD przyciski kolorów dostępne są poprzez nawigację strzałkami i OK. **Wstecz** zamyka panele przed opuszczeniem odtwarzania. W VOD sterowanie natywnym panelem odtwarzacza Media3 pozostaje dostępne.
- EPG XMLTV zapamiętuje także opis aktualnie emitowanego programu, wykorzystywany w rozszerzonym OSD.
- Android Box: wybór pliku M3U korzysta z kilku zgodnych z systemem sposobów uruchamiania menedżera plików (`ACTION_GET_CONTENT` i `ACTION_OPEN_DOCUMENT`). Brak odpowiedniej aplikacji na urządzeniu wywołuje komunikat z rozwiązaniem zamiast nieobsłużonego wyjątku.
- SS-IPTV pojawia się w systemowym menu **„Otwórz za pomocą”** dla typowych typów MIME playlist M3U oraz filmów `video/*`. Otwarta lista M3U jest importowana po potwierdzeniu; zwykły plik wideo trafia bezpośrednio do odtwarzacza.
- Nazwy kanałów są widoczne w osobnych polach tekstowych poza obszarem logo na liście, kafelkach i OSD. Długie nazwy mogą zajmować kilka wierszy.

## Budowanie

Wymagania: JDK 17, Android SDK Platform 36, Build Tools 36.0.0 i sieć do pierwszego pobrania zależności. Projekt używa Android Gradle Plugin 8.9.1, Gradle 8.11.1, Media3 1.8.1. Android od API 26.

    ./gradlew :app:testDebugUnitTest :app:assembleDebug

Plik: app/build/outputs/apk/debug/app-debug.apk.

## Wydania z tym samym certyfikatem

Prywatny plik JKS i hasła przechowuj **poza publicznym repozytorium**. Wersja 1.3.0 ma versionCode 5 i jest podpisywana tym samym kluczem co 1.0.0–1.2.1, aby można ją było instalować jako aktualizację pakietu pl.rafik.ssiptv. Zmień versionCode przy każdej kolejnej aktualizacji, nie zmieniaj applicationId.

    ./gradlew :app:assembleRelease \
      -PsigningStoreFile=/bezpieczna/sciezka/ss-iptv-release.jks \
      -PsigningStorePassword=HASLO_KLUCZA \
      -PsigningKeyAlias=ss-iptv-release \
      -PsigningKeyPassword=HASLO_KLUCZA

Uwaga: parametry wiersza poleceń mogą być widoczne w historii terminala; w automatyzacji używaj tajnych zmiennych / konfiguracji poza repo. Ścieżki plików klucza i jego hasła znajdują się na liście .gitignore.

## Bezpieczeństwo i ograniczenia

- Aplikacja używa tylko źródeł podanych przez użytkownika. Nie omija DRM ani zabezpieczeń dostępu.
- Serwery HTTP i strumienie HTTP są obsługiwane dla zgodności z IPTV, lecz loginy przesyłane HTTP **nie są chronione szyfrowaniem**. Zalecane jest HTTPS.
- EPG jest zależne od dostępności zewnętrznych serwerów i jakości identyfikatorów tvg-id. Przechowywany jest bieżący i następny program, bez pełnej siatki 7-dniowej.
- Dostępność opisów/okładek i odcinków zależy od informacji przesyłanych przez konto Xtream lub wpis M3U. Niestandardowe struktury seriali dostawców mogą wymagać dostosowania; DRM nie jest obsługiwane.
- Odtwarzanie może zależeć od kodeków urządzenia i sposobu dostarczania streamu przez operatora.
- Brak synchronizacji pomiędzy urządzeniami: listy i ulubione pozostają wyłącznie lokalnie.
- Nie testowano wszystkich serwerów Xtream. Niektóre niestandardowe odmiany API mogą wymagać dostosowania.
- Kompletne zachowanie pilotów, w szczególności mapowanie kolorowych przycisków przez producentów TV, wymaga sprawdzenia na urządzeniu. W testach projektu uruchomiono lokalne testy JVM i kompilację Android, ale nie testy rzeczywistego sprzętu i dostawcy Xtream.
- Systemowy wybór domyślnej aplikacji zależy od menedżera plików i typu MIME, który ten menedżer przypisuje do M3U. SS-IPTV nie wymusza ustawienia się jako domyślny odtwarzacz i nie przechwytuje wszystkich dokumentów urządzenia.
- Filmy otwierane jednorazowo z zewnętrznego menedżera plików nie są automatycznie dodawane do bazy list; trwała historia i postęp VOD dotyczą materiałów zaimportowanych do biblioteki.

## Rozwój

Źródła w app/src/main/java/pl/rafik/ssiptv, testy parsera w app/src/test. Workflow .github/workflows/android.yml tworzy debug APK przy każdej zmianie. Klucza release nie wgrywaj na GitHub; podpisuj wydania lokalnie lub w bezpiecznej usłudze CI z sekretami.

© Projekt użytkownika repozytorium SS-IPTV.
