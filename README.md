# Calculo

*A private, self-hosted tracker of a personal investment portfolio: XTB exports and Polish EDO bonds in one place, with returns calculated in PLN.*

Calculo to prywatna aplikacja do śledzenia portfela inwestycyjnego. Wczytuje eksporty z XTB (akcje, ETF-y), dolicza obligacje EDO wpisane ręcznie i pokazuje wartość portfela, wpłaty, zysk i stopę zwrotu w złotych. Działa na Twoim komputerze lub serwerze domowym, a dane zostają u Ciebie.

## Uruchomienie

Wymagany jest Docker z Docker Compose.

```bash
cp .env.example .env
# edytuj .env: ustaw własne hasła (wartość "change-me" jest odrzucana)
docker compose up --build
```

Aplikacja jest dostępna pod `http://localhost:8080`. Logujesz się hasłem z `CALCULO_PASSWORD`.

Zmienne w pliku `.env`:

| Zmienna | Znaczenie |
| --- | --- |
| `POSTGRES_PASSWORD` | hasło bazy danych (baza nie jest wystawiona na zewnątrz) |
| `CALCULO_PASSWORD` | hasło do logowania w aplikacji |
| `CALCULO_BOND_ACCOUNT_NAME` | nazwa konta z obligacjami wyświetlana w aplikacji (domyślnie `Obligacje`). Liczy się tylko przy zakładaniu tego konta: późniejsza zmiana nie wpływa na konto, które już istnieje |
| `CALCULO_SUFFIX_MAP` | zamiana przyrostków tickerów XTB na przyrostki dostawcy notowań, w formie `PRZYROSTEK=ZAMIANA` rozdzielone przecinkami (domyślnie `PL=WA`, czyli `AAA.PL` to `AAA.WA`). Pusta zamiana usuwa przyrostek: `PL=WA,US=` zamienia też `XXX.US` na `XXX`. Wartość zastępuje całą domyślną mapę, więc zostaw w niej `PL=WA` |

Brak którejś z wymaganych zmiennych kończy się czytelnym komunikatem przy starcie. Dane bazy leżą w wolumenie Dockera `calculo-db`, więc przeżywają restart i aktualizację obrazu. Kontenery startują same po restarcie komputera, dopóki ich nie zatrzymasz (`docker compose stop`).

## Tryb demo

Tryb demo wypełnia aplikację fikcyjnym portfelem (instrumenty `AAA.DE`, `BBB.PL`, `CCC.PL`, wpłaty, zakupy i obligacje EDO) i używa deterministycznych cen bez dostępu do sieci:

```bash
docker compose -f compose.yaml -f compose.demo.yaml up --build
```

Demo ma własny wolumen bazy (`calculo-demo-db`), więc nie miesza się z Twoimi danymi. Dane startowe są dodawane tylko do pustej bazy, więc ponowne uruchomienie niczego nie dubluje. Fikcyjne ceny nie mogą trafić do prawdziwych danych, więc tryb demo nie wystartuje na bazie z innymi kontami niż demo (również gdy wgrasz w nim prawdziwy eksport). Usunięcie demo: `docker compose -f compose.yaml -f compose.demo.yaml down -v`.

## Dostęp z telefonu

Aplikacja nasłuchuje na wszystkich interfejsach portu 8080. W tej samej sieci Wi-Fi otwórz `http://<IP-komputera>:8080`.

Uwaga: to ruch bez szyfrowania (brak HTTPS). Korzystaj z tego wyłącznie w zaufanej sieci domowej. Nie przekierowuj portu 8080 na routerze do internetu (zob. sekcję poniżej).

## Pobranie eksportu z XTB z pełną historią

1. W panelu XTB wybierz właściwe konto (np. IKE albo zwykłe).
2. Wygeneruj raport historii konta w formacie XLSX z zakresem dat od otwarcia konta do dziś, tak aby obejmował całą historię. Plik musi zawierać arkusze `Cash Operations` i `Open Positions`. Dokładne położenie opcji zależy od aktualnego wyglądu panelu XTB.
3. W Calculo wejdź w „Aktualizuj dane” i wczytaj plik. Aplikacja najpierw pokaże podsumowanie, a dopiero potem zapisze dane.

Plik można wczytać ponownie: powtórzone operacje są rozpoznawane i pomijane. Przy kilku kontach wczytaj osobny plik dla każdego. Gdy zakresy dat się nie pokrywają, aplikacja ostrzeże, że zakupy nie zgadzają się z otwartymi pozycjami.

## Obligacje EDO i inflacja

Zakupy obligacji EDO dodajesz ręcznie w „Aktualizuj dane” (data i liczba sztuk). Oprocentowanie pierwszego roku i marża serii są pobierane automatycznie lub wpisywane ręcznie na ekranie „Stopy i inflacja”. Od drugiego roku odsetki zależą od inflacji: gdy GUS ogłasza nowy odczyt, dopisz go na ekranie „Stopy i inflacja” (miesiąc ogłoszenia i wartość w procentach). Aplikacja pokazuje, których miesięcy jeszcze brakuje.

## Notowania

Notowania i kursy odświeżają się przy starcie aplikacji, po każdym imporcie, codziennie o 18:30 i po kliknięciu „Odśwież notowania” na ekranie głównym. Gdy serwis notowań odpowie, że zapytań jest za dużo (HTTP 429), aplikacja przerywa odświeżanie i nie pyta go ponownie przed czasem, który wskazał, a gdy go nie wskazał, przez godzinę. Ekran główny pokazuje wtedy ten czas. Po restarcie aplikacji ta przerwa jest liczona od nowa przy pierwszej odmowie.

## Ograniczenia

- Brak obsługi sprzedaży i wypłat. Zakres: wpłaty, zakupy, dywidendy i odsetki.
- Brak sald początkowych: historia musi zaczynać się od pierwszej wpłaty.
- Jeden użytkownik, jedno hasło.
- Brak HTTPS i brak gotowego wdrożenia poza siecią domową.
- Ceny i kursy walut pochodzą z publicznych źródeł (notowania z Yahoo Finance, kursy z NBP) i mogą się opóźniać lub być chwilowo niedostępne.
- Stopa zwrotu nie jest ważona czasem.

## Co przed wystawieniem do internetu

Aplikacja jest zaprojektowana dla sieci domowej. Zanim udostępnisz ją publicznie:

1. Włącz HTTPS (np. reverse proxy z certyfikatem) i ustaw ciasteczka sesji jako `Secure`.
2. Przejrzyj logowanie: hasło jest jedno, a limit prób jest prosty. Rozważ dodatkową warstwę uwierzytelniania na proxy.
3. Ustaw regularne kopie bazy, np. `docker compose exec db pg_dump -U calculo calculo > kopia.sql`, i sprawdź, że potrafisz je odtworzyć.
4. Nie zostawiaj przykładowych haseł z `.env.example`.

## Rozwój lokalny

```bash
docker compose -f compose.yaml -f compose.dev.yaml up -d db   # baza na 127.0.0.1:5432
cd backend && mvn spring-boot:run                             # backend na :8080
cd frontend && npm ci && npm run dev                          # frontend na :5173, proxy do :8080
```

Plik `compose.dev.yaml` udostępnia bazę tylko na `127.0.0.1`, a `compose.yaml` nie publikuje portu bazy wcale. Zmienne `POSTGRES_PASSWORD` i `CALCULO_PASSWORD` ustaw w środowisku powłoki także przy uruchamianiu backendu. Testy: `mvn verify` w `backend/` (wymaga Dockera, bo używa Testcontainers) i `npm test` w `frontend/`.

Moduły backendu (`backend/src/main/java/dev/calculo`): `xtb` (parser eksportu), `bonds` (obligacje EDO), `prices` (ceny i kursy), `portfolio` (obliczenia), `security` (logowanie), `demo` (dane przykładowe).

## Licencja

Licencja nie została jeszcze wybrana. Do czasu jej wybrania wszelkie prawa są zastrzeżone.
