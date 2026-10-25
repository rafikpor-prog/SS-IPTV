# SS-IPTV

Niezależna, natywna aplikacja Android do odtwarzania własnych źródeł IPTV. Projekt powstał od zera; nie zawiera kanałów, playlist ani dostępu do żadnego operatora.

## Funkcje 1.6.0

### Stabilność i obsługa obrazu na Android Box — aktualizacja 1.6.0

- Domyślny start kanału Live przełączono na **Media3 z obsługą sprzętowego MediaCodec**. **VLC** jest drugą, automatycznie wybieraną drogą, jeśli pierwszy silnik nie wyrenderuje obrazu do ok. **11 sekund** lub nie obsłuży strumienia. Oba silniki można przełączać ręcznie w ustawieniach OSD.
- Odtwarzacz nie jest odtwarzany od zera przy każdym `CH+/CH-`: Media3 używa jednej instancji ExoPlayer i dekodera, a VLC wykorzystuje ten sam MediaPlayer przy kolejnych próbach w tym silniku. Wielokrotne szybkie naciśnięcia pilota są **łączone w jedno przełączenie ostatnio wskazanego kanału** (260 ms). Zmniejsza to alokacje pamięci i ryzyko blokowania Android Box.
- Bufor TV Media3 skrócono do docelowych **2,5–12 sekund** z limitem **16 MiB** (8 MiB na urządzeniu z małą klasą pamięci); VOD ma oddzielnie do 24/12 MiB. VLC korzysta z bufora około 1,8 s. Wielkości są parametrami żądanymi — nie każda transmisja i nie każdy kodek musi osiągnąć takie opóźnienie.
- Pojawił się **centralny wskaźnik łączenia** z wektorową animacją, nazwą kanału, silnikiem i czasem oczekiwania. Jest widoczny również po automatycznym schowaniu OSD. Po błędzie widać komunikat o kolejnej próbie, zamiast czarnego ekranu.
- OSD rozróżnia **odtwarzanie** i **rzeczywiste oczekiwanie na dane**. Powtarzalne zdarzenia `Buffering` z VLC nie zastępują komunikatu odtwarzania, jeżeli dekoder nadal pokazuje obraz.
- Obraz zachowuje **oryginalne proporcje** (`FIT` / automatyczna skala libVLC): bez wymuszonego powiększenia i obcinania krawędzi.
- Przewodnik EPG odczytywany jest do podręcznej mapy w tle, aktualizowanej co minutę; pokazuje program i następną audycję, jeśli źródło XMLTV zostało dopasowane. Gdy danych nie ma, komunikat wskazuje **brak dopasowania EPG**, nie pozorne buforowanie.
- Po powrocie z odtwarzacza nie przebudowujemy całego katalogu. Kanały są w SQLite, a lista pozostaje również w pamięci procesu. Internetowe M3U wprowadzane od tej wersji zapamiętują źródłowy URL i mogą być automatycznie aktualizowane **co 7 dni**; listy importowane z pliku pozostają w pamięci lokalnej bez samoczynnego pobierania. Starsze importy URL bez zapamiętanego adresu należy zaimportować ponownie jeden raz, aby włączyć tygodniowe odświeżanie.

### Poprzednie funkcje

### Stabilność IPTV — aktualizacja 1.5.0

- **VLC / libVLC 3.7.5** jest teraz podstawowym odtwarzaczem **Telewizji na żywo**. Obsługuje natywne kodeki, demultipleksery transport stream i ścieżki dźwiękowe, w tym takie, których sprzętowy dekoder danego Android Box nie potrafi odtworzyć. **Media3/ExoPlayer** działa jako silnik awaryjny i domyślny odtwarzacz VOD (z zachowaniem zapisanego momentu filmu/odcinka).
- Przy błędzie VLC aplikacja próbuje jeszcze raz nawiązać połączenie, a następnie uruchamia strumień w Media3. Po niepowodzeniu Media3 podejmowane są ograniczone próby wznowienia i powrotu do VLC. W **OSD → Ustawienia odtwarzania** można także ręcznie wybrać drugi silnik.
- **Nadzór co 30 sekund** monitoruje stan połączenia i upływ czasu od ostatniego postępu. Po **60 sekundach bez postępu** następuje automatyczne odzyskiwanie transmisji. Restart jest wyłączony w pauzie i przy braku sieci, żeby nie powodować zapętleń.
- Pierwszy wybór jakości obrazu jest **automatyczny, bez sztucznego limitu**. W silniku Media3 dla wielowariantowych HLS/DASH można obniżać limit przepływności w trakcie odtwarzania bez przerywania sesji, jeśli powtarza się niedobór bufora. Gdy adaptacja nie pomaga i stream zamarznie, potrzebne jest ponowne połączenie. Zwykły TS z jednym wariantem nie pozwala aplikacji samodzielnie zmniejszyć jakości — konieczny jest inny URL od dostawcy.
- Na OSD jest osobny **wektorowy wskaźnik stanu sieci**: Wi-Fi ze stopniem sygnału (jeśli system udostępnia RSSI), Ethernet, sieć mobilna, brak połączenia lub nieznany sygnał. To siła sygnału lokalnej sieci, nie test szybkości serwera IPTV. Ikony w interfejsie odtwarzania przygotowano jako Android VectorDrawable (ścieżki SVG), bez emoji.
- Listy M3U są zapisane trwale w prywatnych plikach i bazie SQLite jak wcześniej. Dodatkowa migawka katalogu w pamięci jest używana przy powrotach do TV i między ekranami — wielotysięcznej listy nie trzeba pobierać ani parsować od nowa przy każdym otwarciu. Migawka jest odświeżana po dodaniu/usunięciu/zmianie listy.
- Dwie platformy ARM: `arm64-v8a`, `armeabi-v7a`. Nie ma bibliotek VLC dla emulatorów x86 w wydaniu instalacyjnym, aby zmniejszyć rozmiar APK. Informacje o silniku i licencji: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

### Wcześniejsze funkcje

### Android TV i odtwarzacz — aktualizacja 1.4.0

- Po otwarciu aplikacji z launchera Android TV zawsze wyświetla się katalog **Telewizja**. Nie jest przywracany ekran ustawień lub VOD zamiast listy kanałów.
- Pilot: podczas oglądania TV strzałki **↑/↓** przełączają kanały (również gdy OSD jest tylko automatycznie widoczne); **CH+/CH−** również działają. Klawisze są obsługiwane zanim trafią do kontrolera wideo. Zmiana kanału następuje wewnątrz tego samego ekranu, bez otwierania kolejnej aktywności.
- Naciśnięcie **OK**, **←** lub **→** podczas oglądania Live uruchamia *świadomy tryb fokusu OSD*. Strzałkami przechodzisz po jego czterech małych akcjach, zatwierdzasz OK, a **Wstecz** powraca do zmiany kanałów. Automatyczne OSD nie zatrzymuje przełączania strzałkami.
- OSD jest bardziej kompaktowe: zamiast dużych kolorowych bloków ma niewielkie kontrolki z kolorowymi punktami. Tytuł aktualnie emitowanego programu ma większy i wyraźniejszy font, osobno od nazwy i logo kanału.
- Interfejs TV został ujednolicony: stonowane kolory, panel boczny bez grubej obwódki, natywne wektorowe ikony kategorii i sekcji, subtelne lecz widoczne zaznaczenie fokusu.
- Media3/ExoPlayer utrzymuje bufor strumieniowania ustawiony na 15–60 s (w granicach okna live i dostępnej pamięci), start od ok. 2 s danych, wznowienie po zacięciu od 5 s. Błędy sieciowe uruchamiają najwyżej dwie próby ponownego połączenia. Wielkość rzeczywistego bufora zależy od dostawcy IPTV.
- Włączono zapasowy dekoder systemowy, gdy pierwszy dekoder MediaCodec nie uruchomi się. W panelu odtwarzania można wybrać inną obsługiwaną **ścieżkę dźwiękową**; w przypadku braku dekodera pojawia się informacja. Nie wszystkie kodeki (np. DTS na niektórych boxach) są gwarantowane bez dodatkowych bibliotek.
- Dopasowanie EPG z każdego poprawnie pobranego XMLTV obejmuje `tvg-id`, wiele nazw `display-name`, polskie znaki oraz warianty HD/FHD/4K i dopiski `.pl`. Częściowe braki i błędy źródeł nie usuwają nieprzeterminowanych wpisów przewodnika. W statusie źródła widać liczbę rzeczywistych dopasowań.
- Import M3U wykorzystuje także `tvg-name` jako identyfikator dopasowania EPG, gdy dostawca nie podał `tvg-id`.

### Wcześniejsze funkcje

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

Wymagania: JDK 17, Android SDK Platform 36, Build Tools 36.0.0 i sieć do pierwszego pobrania zależności. Projekt używa Android Gradle Plugin 8.9.1, Gradle 8.11.1, Media3 1.8.1 i libVLC 3.7.5. Android od API 26.

    ./gradlew :app:testDebugUnitTest :app:assembleDebug

Plik: app/build/outputs/apk/debug/app-debug.apk.

## Wydania z tym samym certyfikatem

Prywatny plik JKS i hasła przechowuj **poza publicznym repozytorium**. Wersja 1.6.0 ma versionCode 8 i jest podpisywana tym samym kluczem co 1.0.0–1.5.0, aby można ją było instalować jako aktualizację pakietu pl.rafik.ssiptv. Zmień versionCode przy każdej kolejnej aktualizacji, nie zmieniaj applicationId.

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
