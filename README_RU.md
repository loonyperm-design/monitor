# Anycubic Kobra X — Нативный Android LAN Monitor (.apk проект)

Готовый проект нативного Android-приложения (Kotlin + WebView UI) для прямого перехвата телеметрии 3D-принтера **Anycubic Kobra X (modelId: 20030)** по локальной Wi-Fi сети без облака Anycubic Cloud.

## Что внутри
1. **`KobraXLanClient.kt`** — полная нативная реализация протокола LAN Mode для Kobra X:
   - Запрос `GET http://<IP>:18910/info` и получение 32-байтного токена.
   - Генерация MD5-подписи `MD5(MD5(token[0:16]) + ts + nonce)` и отправка `POST http://<IP>:18910/ctrl`.
   - Расшифровка ответа по алгоритму `AES-128-CBC` (ключ `token[16:32]`, вектор `data.token`).
   - Прямое TLS-подключение к локальному MQTT-брокеру принтера на порту `9883` (`MQTT 3.1.1`) и подписка на `anycubic/anycubicCloud/v1/printer/+/20030/<deviceId>/#`.
2. **`MainActivity.kt` + `kobra_x_monitor.html`** — интерактивный дашборд только для мониторинга:
   - Ручной ввод IP-адреса принтера.
   - Живые графики температур сопла и стола, обороты кулеров, прогресс слоев и статус всех 4 слотов фидера ACE GEN 2.
   - Интерактивная **3D и 2D карта высот стола (Bed Mesh 7×7)** и инспектор перехваченных JSON-пакетов.
   - Звуковая фанфара и системное Push-уведомление Android при достижении 100% печати.

## Как получить готовый `.apk` за 2 минуты без установки Android Studio (через GitHub Actions)
1. Создай бесплатный репозиторий на [GitHub](https://github.com/new) и загрузи туда все файлы из этого архива (папки `.github`, `app`, файлы `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`).
2. Перейди во вкладку **Actions** в своем репозитории — сборка **Build Anycubic Kobra X Monitor APK** запустится автоматически (или нажми **Run workflow**).
3. Через ~2 минуты открой завершенную сборку и в блоке **Artifacts** внизу скачай готовый архив **`KobraX-LAN-Monitor-APK`** с файлом `app-debug.apk`.
4. Установи `app-debug.apk` на телефон, убедись, что на принтере включен **LAN Mode** (`Settings → Network → LAN Mode`), вбей IP принтера в приложении и нажми **«Подключить IP»**!
