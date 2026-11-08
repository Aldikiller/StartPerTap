# StartPerTap

[English](../README.md) · [Deutsch](README.de.md) · [Español](README.es.md) · [Français](README.fr.md) · **Українська**

Безкоштовний лаунчер Android із великими плитками для людей зі слабким зором.

## Завантаження

[StartPerTap 0.16 (APK)](https://github.com/Aldikiller/StartPerTap/releases/download/v0.16/StartPerTap-0.16.apk)

Установіть APK на Android 10 або новішу версію. Відкрийте StartPerTap і налаштуйте плитки через **Замок → PIN-код**. У налаштуваннях виберіть **Вибрати StartPerTap за замовчуванням**, щоб змінити головний екран.

Підписаний APK є попередньою версією. Тестування на пристроях триває.

## Можливості

- До 36 плиток для програм, контактів і посилань. Гортайте сторінки та змінюйте порядок плиток у налаштуваннях.
- Вибирайте кольори та відкривайте зустрічі Zoom, посилання на виклики WhatsApp, відео чи медіатеки.
- PIN-код захищає від випадкових змін. Його можна скинути після двох підтверджень.
- Тихий режим і доступ до налаштувань збільшення екрана Android.

## Збирання з вихідного коду

Установіть JDK 17+, Android SDK Platform 35, Build Tools для Windows та інструменти командного рядка Android. Виконайте команду PowerShell. Скрипт створить локальний ключ для тестових підписів.

```powershell
.\build.ps1 -Sdk 'C:\Android\Sdk' -Jdk 'C:\Java\jdk' -BuildTools '35.0.0' -OutputApk 'C:\Temp\StartPerTap.apk'
```

[MIT](../LICENSE) · Aldikiller
