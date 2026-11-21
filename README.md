# adaptive-dpi-android

Android-приложение для диагностики сети и адаптивного обхода DPI без root. На этапе 1 реализованы русскоязычные экраны на Jetpack Compose и Material 3, переключение темы и проверочный сеанс Android VPN. Сетевой движок пока не подключён.

Кнопка «Запустить проверочный сеанс» запрашивает разрешение Android и запускает foreground-службу. Тестовый TUN охватывает только пакет приложения и не добавляет маршрутов или DNS-серверов. Пока движок отсутствует, «Умный обход» недоступен. Сеанс можно остановить с главного экрана и из уведомления. На Android 13 и новее для отображения уведомления в шторке требуется разрешение системы.

## Сборка на компьютере №2

Понадобятся JDK 17 и Android SDK с Platform 36 и Build Tools 35.0.0. Для установки из Android Studio откройте **SDK Manager**, установите **Android SDK Platform 36**, **Android SDK Build-Tools 35.0.0** и **Android SDK Platform-Tools**, примите лицензии SDK. При запуске из терминала укажите `JAVA_HOME` на JDK 17 и `ANDROID_HOME` на каталог SDK. Android Studio может сама записать путь SDK в локальный `local.properties`.

На Windows разместите рабочую копию в пути без кириллицы, например `C:\Dev\adaptive-dpi-android`. AGP проверяет путь до начала сборки.

Проект фиксирует Gradle 8.13 через Wrapper с SHA-256, Android Gradle Plugin 8.13.2, Kotlin и Compose Compiler 2.3.21, Compose BOM 2024.06.00. Эта версия BOM включает Compose UI 1.6.8 и позволяет собрать этап 1 без нативной библиотеки Graphics-Path. Устанавливать Gradle отдельно не требуется. Минимальная версия Android — API 29, версия компиляции и целевая версия — API 36. [Совместимость AGP](https://developer.android.com/build/releases/agp-8-13-0-release-notes) · [Совместимость Kotlin](https://kotlinlang.org/docs/gradle-configure-project.html) · [Состав Compose BOM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2024.06.00/compose-bom-2024.06.00.pom)

На Windows запустите Gradle Wrapper из корня репозитория.

```powershell
.\gradlew.bat testDebugUnitTest :engine:test
.\gradlew.bat assembleDebug
```

На Linux и macOS сначала установите право на запуск скрипта.

```sh
chmod +x ./gradlew
./gradlew testDebugUnitTest :engine:test
./gradlew assembleDebug
```

Debug APK появится в `app/build/outputs/apk/debug/app-debug.apk`. Отчёт о тестах состояний VPN находится в `vpn/build/reports/tests/testDebugUnitTest/index.html`.

## Сборка в GitHub Actions

Workflow [«Сборка Android»](.github/workflows/android-build.yml) запускается при push в `main`, при pull request и вручную через **Run workflow**. После успешных тестов и сборки откройте в репозитории **Actions → Сборка Android → нужный запуск → Artifacts**. Имя архива и APK включает короткий коммит и номер запуска с номером попытки, например `adaptive-dpi-android-debug-e6d85bee6c19-2.1`. В `build-info.txt` указаны полный коммит, версия пакета, сертификат подписи и SHA-256 APK. На телефоне проверьте коммит и номер в **Настройки → О приложении**. [Скачивание артефактов в GitHub](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts)

Для обновления поверх установленной версии Android требует совпадения пакета и сертификата подписи. Сравните поле «certificate SHA-256 digest» из сведений о новой сборке с результатом `apksigner verify --print-certs` для установленного APK. Путь к установленному файлу можно получить командой `adb shell pm path org.adaptivedpi.android` и скопировать `base.apk` командой `adb pull`. Это не затрагивает данные приложения. [Правила обновления Android](https://developer.android.com/google/play/app-updates)

Для последовательных CI-сборок `main` можно настроить один debug-ключ в секрете GitHub Actions `ANDROID_DEBUG_KEYSTORE_BASE64`. Секрет содержит Base64 существующего debug-keystore со стандартным псевдонимом `androiddebugkey` и стандартными паролями Android Debug. Для обновления уже установленного приложения нужен ключ, которым оно подписано. При отсутствии секрета runner подписывает APK своим debug-ключом. Сведения о сертификате каждой сборки доступны в `build-info.txt`. [Подпись Android-приложений](https://developer.android.com/studio/publish/app-signing)

## Модули

- `app` — экран состояния VPN, настройки и диагностика.
- `designsystem` — общая тема, формы и компоненты интерфейса.
- `vpn` — согласие Android, проверочный сеанс, уведомление, состояния службы и модульные тесты.
- `engine` — контракты будущего движка и владения дескриптором TUN.
- `docs` — [архитектура](docs/ARCHITECTURE.md), [план реализации](docs/IMPLEMENTATION_PLAN.md) и [обзор кандидатов и лицензий](docs/DEPENDENCIES_AND_LICENSES.md).
