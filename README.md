# PDF Reader

Minimalna aplikacja na Androida do podglądu plików PDF. Bez reklam, bez uprawnień, bez zależności — korzysta z wbudowanego `PdfRenderer` (Android 7.0+).

- Otwieranie przyciskiem **Otwórz PDF** albo z innej aplikacji (menedżer plików, mail, przeglądarka → „Otwórz w…”).
- Strony przewijane w pionie, dopasowane do szerokości ekranu.
- Przybliżanie: szczypanie dwoma palcami albo podwójne stuknięcie.
- Wyszukiwanie tekstu z podświetleniem wyników (Android 15, a na Androidzie 11–14 po aktualnych aktualizacjach systemowych Google Play; na starszych przycisk szukania jest ukryty).
- Zapamiętywanie miejsca, w którym skończono czytać każdy plik.
- Lista ostatnio otwieranych plików (przytrzymaj pozycję, aby ją usunąć).
- Przejście do strony o podanym numerze (menu ⋮).

## Budowanie

**Android Studio:** File → Open → ten folder → Run (albo Build → Build APK).

**GitHub Actions:** po wypchnięciu repo na GitHub workflow `Build APK` zbuduje plik, do pobrania w zakładce Actions → artefakt `pdf-reader-apk`.
