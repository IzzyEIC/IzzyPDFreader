# Izzy PDF Reader

Minimalna aplikacja na Androida do podglądu plików PDF i Word (.docx). Bez reklam, bez uprawnień, bez zależności — korzysta z wbudowanego `PdfRenderer` (Android 7.0+).

- Otwieranie przyciskiem **Otwórz PDF** albo z innej aplikacji (menedżer plików, mail, przeglądarka → „Otwórz w…”).
- Strony przewijane w pionie, dopasowane do szerokości ekranu.
- Przybliżanie: szczypanie dwoma palcami albo podwójne stuknięcie.
- Wyszukiwanie tekstu z podświetleniem wyników (Android 15, a na Androidzie 11–14 po aktualnych aktualizacjach systemowych Google Play; na starszych przycisk szukania jest ukryty).
- Zapamiętywanie miejsca, w którym skończono czytać każdy plik.
- Lista ostatnio otwieranych plików (przytrzymaj pozycję, aby ją usunąć).
- Przejście do strony o podanym numerze (menu ⋮).
- Podgląd .docx: tekst, nagłówki, formatowanie, listy, tabele, obrazki i linki — bez dokładnego układu stron jak w Wordzie. Przybliżanie i wyszukiwanie działają na każdym telefonie. Stary format .doc nie jest obsługiwany.

## Budowanie

**Android Studio:** File → Open → ten folder → Run (albo Build → Build APK).

**GitHub Actions:** każde wypchnięcie na `main` buduje aplikację i publikuje `IzzyPDFreader.apk` w zakładce **Releases**. APK jest podpisany stałym kluczem z sekretów repo (`SIGNING_KEYSTORE_BASE64`, `SIGNING_PASSWORD`), więc nowe wersje instalują się na starsze.

**Klucz podpisu:** kopia leży lokalnie w folderze `signing/` (wykluczony z gita). Nie usuwaj go — bez tego klucza nie da się wydać aktualizacji; trzeba by odinstalować aplikację i zainstalować od nowa.
