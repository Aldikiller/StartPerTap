# StartPerTap

[English](../README.md) · [Deutsch](README.de.md) · [Español](README.es.md) · **Français** · [Українська](README.uk.md)

Lanceur Android gratuit avec de grandes tuiles pour les personnes malvoyantes.

## Téléchargement

[StartPerTap 0.16 (APK)](https://github.com/Aldikiller/StartPerTap/releases/download/v0.16/StartPerTap-0.16.apk)

Installez l’APK sur Android 10 ou une version ultérieure. Ouvrez StartPerTap et configurez vos tuiles via **Cadenas → Code PIN**. Dans les paramètres, choisissez **Choisir StartPerTap par défaut** pour changer d’écran d’accueil.

L’APK signé est une version préliminaire. Les tests sur appareils sont en cours.

## Fonctionnalités

- Jusqu’à 36 tuiles pour les applications, contacts et liens. Balayez pour changer de page et réorganisez les tuiles dans les paramètres.
- Choisissez les couleurs et ouvrez des réunions Zoom, des liens d’appel WhatsApp, des vidéos ou des médiathèques.
- Code PIN pour éviter les modifications accidentelles. Réinitialisation après deux confirmations.
- Mode silencieux et accès aux paramètres de grossissement d’Android.

## Compiler depuis le code source

Installez JDK 17+, Android SDK Platform 35, les Build Tools pour Windows et les outils en ligne de commande Android. Exécutez la commande PowerShell. Le script crée une clé locale pour les signatures de test.

```powershell
.\build.ps1 -Sdk 'C:\Android\Sdk' -Jdk 'C:\Java\jdk' -BuildTools '35.0.0' -OutputApk 'C:\Temp\StartPerTap.apk'
```

[MIT](../LICENSE) · Aldikiller
