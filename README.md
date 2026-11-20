# Telefondienst (Android)

Trage Telefondienst-Stunden (8–16 Uhr, stundenweise) pro Tag ein und lass dich
eine einstellbare Zeit vor Dienstbeginn erinnern.

## Installation für Nutzer (APK)
Die fertige App kann direkt auf dem Android-Smartphone installiert werden:
1. Unter [Releases](https://github.com/Noack1978/Telefondienst-App/releases) die neueste Version aufrufen.
2. Die Datei `app-release.apk` herunterladen.
3. Auf dem Smartphone öffnen und installieren (ggf. „Installation aus unbekannten Quellen“ für den Browser/Dateimanager erlauben).

## Funktionen
- Tag wählen (◀ ▶ / Vorherige Woche / Heute / Nächste Woche)
- Stunden 8–9 … 15–16 per Häkchen eintragen, „Ganzer Tag“ / „Leeren“
- Feiertagserkennung für alle Bundesländer (an Feiertagen kein Dienst)
- Statistik zu geleisteten Stunden (aktuelle Woche, Monat, Jahr)
- Erinnerung vor Dienstbeginn (5, 10, 15, 30 oder 60 Minuten)
- Zusammenhängende Stunden = ein Block, Erinnerung nur zum Blockbeginn
- Erinnerungen überleben Neustart des Handys
- Homescreen-Widget mit Anzeige des nächsten Dienstes
- Vollständige Datensicherung (Export, Import sowie automatische Sicherung in frei wählbaren Ordner via Storage Access Framework)

## Berechtigungen
- Benachrichtigungen (Android 13+)
- „Alarme & Erinnerungen“ (Android 12+) für pünktliche Alarme
- Akku-Optimierung ausschalten (optional, für zuverlässige Alarme im Standby)

## Projekt in Android Studio öffnen (Entwicklung)
1. Repository klonen oder ZIP entpacken.
2. In Android Studio über *Open* das Verzeichnis öffnen.
3. Gradle-Sync abwarten und auf Gerät/Emulator starten.
