# StartPerTap

[English](../README.md) · **Deutsch** · [Español](README.es.md) · [Français](README.fr.md) · [Українська](README.uk.md)

Kostenloser Android-Launcher mit großen Kacheln für Menschen mit Sehbeeinträchtigung.

## Download

[StartPerTap 0.16 (APK)](https://github.com/Aldikiller/StartPerTap/releases/download/v0.16/StartPerTap-0.16.apk)

Installiere die APK auf Android 10 oder neuer. Öffne StartPerTap und richte deine Kacheln unter **Schloss → PIN** ein. Wähle in den Einstellungen **StartPerTap als Standard wählen**, um die Android-Startseite zu ersetzen.

Die signierte APK ist eine Vorabversion. Die Gerätetests laufen.

## Funktionen

- Bis zu 36 Kacheln für Apps, Kontakte und Links. Wische zwischen den Seiten und sortiere die Kacheln in den Einstellungen.
- Wähle Kachelfarben und öffne Zoom-Meetings, WhatsApp-Anruflinks, Videos oder Mediatheken.
- PIN-Schutz gegen versehentliche Änderungen. Die PIN lässt sich nach zwei Bestätigungen zurücksetzen.
- Ruhemodus und Zugang zu den Android-Einstellungen für die Bildschirmvergrößerung.

## Aus dem Quellcode bauen

Installiere JDK 17+, Android SDK Platform 35, die Windows Build Tools und die Android-Kommandozeilenwerkzeuge. Führe dann den PowerShell-Befehl aus. Das Buildskript erstellt einen lokalen Schlüssel für Testsignaturen.

```powershell
.\build.ps1 -Sdk 'C:\Android\Sdk' -Jdk 'C:\Java\jdk' -BuildTools '35.0.0' -OutputApk 'C:\Temp\StartPerTap.apk'
```

[MIT](../LICENSE) · Aldikiller
