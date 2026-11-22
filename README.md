# adaptive-dpi-android

Android-приложение для диагностики сети и адаптивного обхода DPI без root. Доступны проверочный сеанс Android VPN и отдельный экспериментальный режим HEV для локального стенда.

Кнопка «Запустить проверочный сеанс» использует TUN без маршрутов или DNS-серверов. Эксперимент со стендом захватывает один частный IPv4-адрес для самого приложения. Отдельный ручной режим «Трафик приложения» захватывает IPv4 выбранного установленного пакета, например Chrome, и блокирует IPv6 для этого пакета. Сеанс можно остановить с главного экрана и из уведомления. На Android 13 и новее для отображения уведомления в шторке требуется разрешение системы. [Проверка экспериментального тракта и Chrome на Pixel 7](docs/PIXEL7_TESTING_RU.md)

## Сборка на компьютере №2

Понадобятся JDK 17, Git, Python 3.10+, Android SDK с Platform 36 и Build Tools 35.0.0 и NDK `28.2.13676358`. Установите компоненты через Android Studio SDK Manager или `sdkmanager`, примите лицензии SDK. При запуске из терминала укажите `JAVA_HOME` на JDK 17 и `ANDROID_HOME` на каталог SDK. Android Studio может сама записать путь SDK в локальный `local.properties`.

На Windows разместите рабочую копию в пути без кириллицы, например `C:\Dev\adaptive-dpi-android`. AGP проверяет путь до начала сборки.

Проект фиксирует Gradle 8.13 через Wrapper с SHA-256, Android Gradle Plugin 8.13.2, Kotlin и Compose Compiler 2.3.21, Compose BOM 2024.06.00. Эта версия BOM включает Compose UI 1.6.8 и позволяет собрать этап 1 без нативной библиотеки Graphics-Path. Устанавливать Gradle отдельно не требуется. Минимальная версия Android — API 29, версия компиляции и целевая версия — API 36. [Совместимость AGP](https://developer.android.com/build/releases/agp-8-13-0-release-notes) · [Совместимость Kotlin](https://kotlinlang.org/docs/gradle-configure-project.html) · [Состав Compose BOM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2024.06.00/compose-bom-2024.06.00.pom)

На Windows запустите Gradle Wrapper из корня репозитория.

```powershell
python scripts/prepare-hev.py
.\gradlew.bat testDebugUnitTest :engine:test
.\gradlew.bat assembleDebug
```

На Linux и macOS сначала установите право на запуск скрипта.

```sh
python3 scripts/prepare-hev.py
chmod +x ./gradlew
./gradlew testDebugUnitTest :engine:test
./gradlew assembleDebug
```

Debug APK появится в `app/build/outputs/apk/debug/app-debug.apk`. Отчёт о тестах состояний VPN находится в `vpn/build/reports/tests/testDebugUnitTest/index.html`.

## Сборка в GitHub Actions

Workflow [«Сборка Android»](.github/workflows/android-build.yml) запускается при push в `main`, при pull request и вручную через **Run workflow**. После успешных тестов и сборки откройте в репозитории **Actions → Сборка Android → нужный запуск → Artifacts**. В архиве `adaptive-dpi-android-debug-<коммит>-<запуск>.<попытка>` лежат `app-debug.apk`, `app-debug-androidTest.apk`, сведения о сборке и скрипт тестирования Pixel 7. На телефоне проверьте коммит и номер в **Настройки → О приложении**. [Инструкция для Pixel 7](docs/PIXEL7_TESTING_RU.md) · [Скачивание артефактов в GitHub](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts)

Для обновления поверх установленной версии Android требует совпадения пакета и сертификата подписи. Сравните поле «certificate SHA-256 digest» из сведений о новой сборке с результатом `apksigner verify --print-certs` для установленного APK. Путь к установленному файлу можно получить командой `adb shell pm path org.adaptivedpi.android` и скопировать `base.apk` командой `adb pull`. Это не затрагивает данные приложения. [Правила обновления Android](https://developer.android.com/google/play/app-updates)

### Однократная настройка подписи CI debug

Создайте отдельный ключ только для CI debug. На Windows запустите команду из PowerShell с JDK в `PATH`. Введите два собственных пароля в приглашениях `keytool`. Сохраните keystore и пароли в своём защищённом хранилище для последующих обновлений.

```powershell
$ciKeystore = Join-Path $env:USERPROFILE 'adaptive-dpi-ci-debug.jks'
keytool -genkeypair -storetype JKS -keystore $ciKeystore -alias adaptive-dpi-ci-debug -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=adaptive-dpi-android CI Debug'
```

В GitHub откройте **Settings → Secrets and variables → Actions → Secrets**. Создайте `ANDROID_CI_DEBUG_STORE_PASSWORD` и `ANDROID_CI_DEBUG_KEY_PASSWORD` с паролями хранилища и ключа. Чтобы загрузить `ANDROID_CI_DEBUG_KEYSTORE_BASE64` без вывода секретного значения в терминал, используйте авторизованный [GitHub CLI](https://cli.github.com/):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes($ciKeystore)) | gh secret set ANDROID_CI_DEBUG_KEYSTORE_BASE64 --repo deadarhimag/adaptive-dpi-android
keytool -list -v -storetype JKS -keystore $ciKeystore -alias adaptive-dpi-ci-debug
```

Во вкладке **Variables** создайте `ANDROID_CI_DEBUG_CERT_SHA256` со значением строки SHA-256 сертификата из `keytool` (двоеточия и любой регистр допустимы). Workflow проверяет оба пароля и отпечаток до сборки, а затем сверяет подпись готового APK. Сборки pull request из форка завершаются с явным сообщением о недоступности подписи. Локальный Gradle продолжает использовать стандартный debug-ключ. [Настройка GitHub Actions Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets) · [Подпись Android-приложений](https://developer.android.com/studio/publish/app-signing)

Для CI debug `versionCode` равен `2 + GITHUB_RUN_NUMBER`. Повторная попытка одного запуска сохраняет код, следующий отдельный запуск увеличивает его. Проверьте два APK из последовательных запусков командой `apksigner verify --print-certs` или по `build-info.txt`. Отпечатки должны совпасть с переменной, а код второй сборки должен быть выше. APK со старым сертификатом потребует однократной переустановки. [Номера запусков GitHub Actions](https://docs.github.com/en/actions/reference/workflows-and-actions/variables) · [Версии Android-приложения](https://developer.android.com/studio/publish/versioning)

## Модули

- `app` — экран состояния VPN, настройки и диагностика.
- `designsystem` — общая тема, формы и компоненты интерфейса.
- `vpn` — согласие Android, проверочный сеанс, уведомление, состояния службы и модульные тесты.
- `engine` — контракты владения TUN и исходящий TCP/UDP-транспорт этапа 2.1 с JVM-проверками на локальных серверах. [Граница подключения к VPN](docs/ARCHITECTURE.md#исходящие-сокеты-этапа-21)
- `docs` — [архитектура](docs/ARCHITECTURE.md), [план реализации](docs/IMPLEMENTATION_PLAN.md) и [обзор кандидатов и лицензий](docs/DEPENDENCIES_AND_LICENSES.md).
