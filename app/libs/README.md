# BRouter offline-ядро

Щоб увімкнути **повний офлайн-роутинг із малюванням у нашому UI**, потрібно
додати JAR-и BRouter (GPLv3) у цю теку та надати дані сегментів.

## ⚠ Ліцензія

BRouter поширюється під **GPL-3.0**. Лінкування цих JAR-ів у застосунок
автоматично робить весь застосунок похідною роботою GPLv3 — потрібно
відкривати власні вихідники та розповсюджувати під GPLv3. Якщо це
неприйнятно — використовуйте інший режим («Офлайн (BRouter app)» через
intent), вбудований у застосунок без лінкування коду.

## Які файли потрібні

З релізу BRouter (https://github.com/abrensch/brouter/releases або
https://brouter.de/brouter/install.html), напр. версії 1.7.5, покладіть
сюди ці JAR-и (зазвичай вони лежать всередині `BRouter-<ver>.zip`):

- `brouter-core-1.7.5.jar`
- `brouter-mapaccess-1.7.5.jar`
- `brouter-expressions-1.7.5.jar`
- `brouter-codec-1.7.5.jar`
- `brouter-util-1.7.5.jar`

(імена можуть відрізнятися — Gradle підхопить будь-які `*.jar` у цій теці).

## Профілі та lookups

У `app/src/main/assets/brouter/` має бути:

```
brouter/
  lookups.dat
  profiles2/
    trekking.brf
    car-fast.brf
    shortest.brf
    hiking-mountain.brf
```

Завантажте з https://github.com/abrensch/brouter/tree/master/misc та
покладіть у assets. На першому запуску `OfflineBRouter.bootstrap()`
скопіює їх у внутрішнє сховище застосунку.

## Сегменти карт (rd5)

Сегменти `.rd5` (5°×5° тайли OSM-даних) користувач завантажує з
https://brouter.de/brouter/segments4/ через UI «Завантажити rd5
для регіону». Зберігаються у `getFilesDir()/brouter/segments4/`.
