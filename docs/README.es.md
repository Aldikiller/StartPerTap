# StartPerTap

[English](../README.md) · [Deutsch](README.de.md) · **Español** · [Français](README.fr.md) · [Українська](README.uk.md)

Lanzador gratuito para Android con fichas grandes para personas con baja visión.

## Descarga

[StartPerTap 0.16 (APK)](https://github.com/Aldikiller/StartPerTap/releases/download/v0.16/StartPerTap-0.16.apk)

Instala el APK en Android 10 o posterior. Abre StartPerTap y configura tus fichas en **Candado → PIN**. Para cambiar la pantalla de inicio, elige **Elegir StartPerTap por defecto** en los ajustes.

El APK firmado es una versión preliminar. Las pruebas en dispositivos siguen en curso.

## Funciones

- Hasta 36 fichas para aplicaciones, contactos y enlaces. Desliza para cambiar de página y ordena las fichas en los ajustes.
- Elige colores y abre reuniones de Zoom, enlaces de llamada de WhatsApp, vídeos o mediatecas.
- PIN para evitar cambios accidentales. Puedes restablecerlo tras dos confirmaciones.
- Modo silencio y acceso a los ajustes de ampliación de Android.

## Compilar desde el código fuente

Instala JDK 17+, Android SDK Platform 35, las Build Tools para Windows y las herramientas de línea de comandos de Android. Ejecuta el comando de PowerShell. El script crea una clave local para las firmas de prueba.

```powershell
.\build.ps1 -Sdk 'C:\Android\Sdk' -Jdk 'C:\Java\jdk' -BuildTools '35.0.0' -OutputApk 'C:\Temp\StartPerTap.apk'
```

[MIT](../LICENSE) · Aldikiller
