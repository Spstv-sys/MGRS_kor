# MGRS Координати (Android)

Android-застосунок на Kotlin для визначення поточних координат у форматі **MGRS** з точністю до метра, з інтерактивною картою OpenStreetMap.

## Можливості

- Інтерактивна карта (osmdroid / OpenStreetMap, без API-ключа)
- Кнопка **«Визначити координати»** — отримує позицію з GPS через `FusedLocationProviderClient` з пріоритетом `HIGH_ACCURACY`
- Конвертація WGS84 → **MGRS** через офіційну бібліотеку `mil.nga:mgrs`
- Адаптивна точність MGRS відповідно до похибки GPS:
  - <1 м → 5 цифр (1 м)
  - <10 м → 4 цифри (10 м)
  - <100 м → 3 цифри (100 м)
  - <1 км → 2 цифри (1 км)
  - …
- Показ широти/довготи та похибки GPS у метрах
- Маркер на карті в поточній позиції; текст MGRS можна виділити та скопіювати

## Вимоги

- Android Studio Iguana+ (AGP 8.5+)
- JDK 17
- Android SDK 34, мінімальна підтримка — API 23 (Android 6.0)

## Збірка

```powershell
# Згенерувати Gradle wrapper (одноразово)
gradle wrapper --gradle-version 8.7

# Збірка debug-APK
.\gradlew.bat :app:assembleDebug

# Встановити на під'єднаний пристрій
.\gradlew.bat :app:installDebug
```

APK з'явиться у `app/build/outputs/apk/debug/app-debug.apk`.

## Дозволи

При першому натисканні кнопки застосунок запитає `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`. Для роботи необхідно увімкнений GPS / служби локації.

## Структура

```
app/
  src/main/
    AndroidManifest.xml
    java/com/example/mgrskor/MainActivity.kt
    res/layout/activity_main.xml
    res/values/{strings,themes,colors}.xml
build.gradle.kts          (root)
settings.gradle.kts
app/build.gradle.kts
```

## Примітки

- Для офлайн-карт osmdroid кешує тайли у внутрішньому сховищі застосунку.
- Якщо потрібна стандартна Google Maps замість OSM — заміни залежність `osmdroid-android` на `play-services-maps` і додай `MAPS_API_KEY` у `AndroidManifest.xml`.
