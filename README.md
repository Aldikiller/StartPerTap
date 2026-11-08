# StartPerTap

**English** · [Deutsch](docs/README.de.md) · [Español](docs/README.es.md) · [Français](docs/README.fr.md) · [Українська](docs/README.uk.md)

Free Android launcher with large tiles for people with low vision.

## Download

[StartPerTap 0.16 (APK)](https://github.com/Aldikiller/StartPerTap/releases/download/v0.16/StartPerTap-0.16.apk)

Install the APK on Android 10 or later. Open StartPerTap and use **Lock → PIN** to configure your tiles. To replace your home screen, choose **Set StartPerTap as default** in the settings.

The signed APK is a preview build. Device testing is ongoing.

## Watch on YouTube

[English](https://youtu.be/7284c3nULzI) · [Deutsch](https://youtu.be/8ppZHYamE14) · [Español](https://youtu.be/V37xc_blXws) · [Français](https://youtu.be/0t8F8R3NWXQ) · [Українська](https://youtu.be/AOm8y0MFCAM)

The videos show concept mockups. The images below are screenshots from the app.

## Screenshots from Google Pixel

<img src="docs/StartPerTap-Pixel-Startseite.jpg" alt="StartPerTap home screen with four large tiles" width="230"> <img src="docs/StartPerTap-Pixel-Einstellungen.jpg" alt="StartPerTap settings" width="230"> <img src="docs/StartPerTap-Pixel-Kacheln.jpg" alt="Tile management and accessibility options" width="230">

[About StartPerTap 0.16](docs/StartPerTap-Pixel-Info.jpg)

## Features

- Up to 36 tiles for apps, contacts and links. Swipe between pages and reorder tiles in the settings.
- Choose tile colors and open Zoom meetings, WhatsApp call links, videos or media libraries.
- PIN protection against accidental changes. Reset the PIN after two confirmations.
- Quiet mode and access to Android screen magnification settings.

## Build from source

With JDK 17+, Android SDK Platform 35, Windows Build Tools and Android command-line tools installed, run the PowerShell command below. The build script creates a local signing key for testing.

```powershell
.\build.ps1 -Sdk 'C:\Android\Sdk' -Jdk 'C:\Java\jdk' -BuildTools '35.0.0' -OutputApk 'C:\Temp\StartPerTap.apk'
```

[MIT](LICENSE) · Aldikiller
