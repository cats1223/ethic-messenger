<p align="center">
  <img src="assets/splash.png" alt="Ethic Messenger" width="320">
</p>

# Ethic Messenger — приложения для Android и iOS

Мобильные приложения мессенджера **Ethic Messenger**: чаты, голосовые сообщения, фото и файлы,
аудио- и видеозвонки, шумоподавление.

**Android** — нативное приложение на Kotlin + Jetpack Compose (Material 3, чёрно-белая тема).
Звонки идут через нативный WebRTC ([LiveKit](https://livekit.io)) как настоящие телефонные:
громкость звонка, работа в фоне с уведомлением, громкая связь, Bluetooth-гарнитуры.

**iPhone** — нативное приложение на SwiftUI: на iOS 26 интерфейс в стиле Liquid Glass
(системные вкладки и панели, стеклянные кнопки звонка и поле ввода). Звонки через LiveKit Swift SDK.

## Установка

Готовые файлы — во вкладке **[Releases](../../releases/latest)**.

### Android
1. Скачайте `Ethic-Messenger.apk`.
2. Откройте файл и разрешите установку из этого источника.
3. При первом звонке разрешите доступ к микрофону и камере.

### iPhone / iPad
1. Скачайте `Ethic-Messenger.ipa`.
2. Установите через [Sideloadly](https://sideloadly.io) или [AltStore](https://altstore.io) со своим Apple ID.
3. С бесплатным Apple ID приложение работает 7 дней — потом переподпишите его в той же программе.

## Сборка

Сборка идёт автоматически в GitHub Actions (`.github/workflows/build.yml`): Android собирается на Ubuntu,
подписывается ключом из секретов репозитория (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`) и выкладывается в релиз.

Запустить вручную: **Actions → Сборка приложений → Run workflow**, указать версию.

Локально: `cd android && ./gradlew assembleDebug` (debug-сборка ходит на локальный сервер `http://10.0.2.2:3999`).

## Структура

| Путь | Что это |
| --- | --- |
| `android/app/src/main/java/com/ethic/messenger/data/` | API, WebSocket, состояние приложения |
| `android/app/src/main/java/com/ethic/messenger/call/` | Звонки: LiveKit, фоновая служба |
| `android/app/src/main/java/com/ethic/messenger/media/` | Рингтоны и звуки, голосовые сообщения |
| `android/app/src/main/java/com/ethic/messenger/ui/` | Экраны на Jetpack Compose |
| `ios/project.yml` | Проект iOS (генерируется XcodeGen) |
| `ios/Ethic/` | Приложение iOS на SwiftUI |
| `assets/` | Логотип и иконка |
