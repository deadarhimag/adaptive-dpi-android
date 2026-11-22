#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$TransportTarget,
    [string]$ArtifactDirectory,
    [string]$Serial,
    [string]$LogDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$appPackage = 'org.adaptivedpi.android'
$testPackage = "$appPackage.test"
$runner = 'androidx.test.runner.AndroidJUnitRunner'
$testClass = "$appPackage.TunTransportInstrumentedTest"
$fixture = $null
$testProcess = $null
$captureAndroidLog = $false
$selected = $null
$exitStatus = 1

function Find-Adb {
    $roots = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
    foreach ($root in $roots) {
        if ($root) {
            $candidate = Join-Path $root 'platform-tools\adb.exe'
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
        }
    }
    $command = Get-Command adb.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { return $command.Source }
    throw 'ADB не найден. Добавьте папку platform-tools в PATH или укажите папку Android SDK в ANDROID_HOME.'
}

function Invoke-Device {
    param([string[]]$ArgumentList)
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $result = & $adb -s $selected @ArgumentList 2>&1 | ForEach-Object { $_.ToString() }
        $adbExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($adbExitCode -ne 0) {
        throw "Ошибка ADB ($($ArgumentList[0]), код $adbExitCode): $($result -join ' ')"
    }
    return $result
}

function Get-Certificate {
    param([string]$Apk)
    $hash = & $python @pythonPrefix -X utf8 $certificateScript $Apk 2>&1
    if ($LASTEXITCODE -ne 0 -or ($hash -join '') -notmatch '^[0-9a-f]{64}$') {
        throw "Не удалось прочитать сертификат $(Split-Path -Leaf $Apk): $($hash -join ' ')"
    }
    return [string]$hash
}

function Test-NeedsInstall {
    param([string]$Package, $Metadata)
    $packages = Invoke-Device @('shell', 'pm', 'list', 'packages', '--show-versioncode', '--user', $userId, $Package)
    $entry = ($packages | Where-Object { $_ -match ('^package:' + [regex]::Escape($Package) + '(?:\s|$)') }) -join ''
    if (-not $entry) { return $true }
    if ($entry -notmatch '\bversionCode:(\d+)') { throw "Не удалось определить установленную версию $Package." }
    if ([long]$Matches[1] -gt [long]$Metadata.versionCode) {
        throw "На телефоне установлена более новая версия $Package. Скачайте APK из более свежего запуска GitHub Actions."
    }
    $installed = Invoke-Device @('shell', 'pm', 'path', '--user', $userId, $Package)
    $base = ($installed | Where-Object { $_ -match '^package:.+/base\.apk\s*$' } | Select-Object -First 1)
    if (-not $base) { throw "Не удалось найти установленный base.apk пакета $Package." }
    $copy = Join-Path $scratch "$Package.apk"
    Invoke-Device @('pull', $base.Substring(8).Trim(), $copy) | Out-Null
    if ((Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash -eq $Metadata.sha256) { return $false }
    if ((Get-Certificate $copy) -ne $info.certificateSha256) {
        throw "Установленный $Package подписан другим ключом. Нужен однократный переход на постоянную CI-подпись. См. PIXEL7_TESTING_RU.md."
    }
    return $true
}

try {
    if (-not $ArtifactDirectory) {
        $ArtifactDirectory = if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'build-info.json')) { $PSScriptRoot } else { (Get-Location).Path }
    }
    $artifact = (Resolve-Path -LiteralPath $ArtifactDirectory).Path
    if (-not $LogDirectory) {
        $LogDirectory = Join-Path (Get-Location).Path ('pixel7-logs-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    }
    $logs = (New-Item -ItemType Directory -Path $LogDirectory -Force).FullName
    $scratch = (New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) ('adaptive-pixel7-' + [guid]::NewGuid().ToString('N')))).FullName
    $certificateScript = Join-Path $PSScriptRoot 'apk-certificate.py'
    $fixtureScript = Join-Path $PSScriptRoot 'transport-fixture.py'
    $appApk = Join-Path $artifact 'app-debug.apk'
    $testApk = Join-Path $artifact 'app-debug-androidTest.apk'
    $infoPath = Join-Path $artifact 'build-info.json'
    foreach ($file in @($appApk, $testApk, $infoPath, $certificateScript, $fixtureScript)) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Не найден файл $file. Распакуйте архив GitHub Actions целиком." }
    }

    $adb = Find-Adb
    $python = $null
    foreach ($name in @('python.exe', 'python3.exe', 'py.exe')) {
        $command = Get-Command $name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $command) { continue }
        $pythonPrefix = @()
        if ($name -eq 'py.exe') { $pythonPrefix = @('-3') }
        $version = & $command.Source @pythonPrefix --version 2>&1
        if ($LASTEXITCODE -eq 0 -and ($version -join '') -match '^Python 3\.(\d+)\.' -and [int]$Matches[1] -ge 10) {
            $python = $command.Source
            break
        }
    }
    if (-not $python) { throw 'Не найден Python 3.10 или новее в PATH.' }

    $ip = $null
    if (-not [Net.IPAddress]::TryParse($TransportTarget, [ref]$ip) -or $ip.ToString() -ne $TransportTarget -or
        $TransportTarget -notmatch '^(10\.|172\.(1[6-9]|2[0-9]|3[01])\.|192\.168\.)') {
        throw 'Укажите частный IPv4 компьютера, например 192.168.0.157.'
    }
    $localAddresses = [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
        Where-Object { $_.OperationalStatus -eq 'Up' } |
        ForEach-Object { $_.GetIPProperties().UnicastAddresses } |
        ForEach-Object { $_.Address.ToString() }
    if ($TransportTarget -notin $localAddresses) { throw "Адрес $TransportTarget не назначен активному адаптеру этого ПК." }

    $info = Get-Content -LiteralPath $infoPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($info.commit -notmatch '^[0-9a-f]{40}$' -or $info.certificateSha256 -notmatch '^[0-9a-f]{64}$' -or
        $info.app.package -ne $appPackage -or $info.test.package -ne $testPackage -or
        $info.test.targetPackage -ne $appPackage -or $info.test.runner -ne $runner -or
        $info.app.versionCode -notmatch '^[1-9]\d*$' -or -not $info.app.versionName) {
        throw 'Сведения о сборке не соответствуют ожидаемым пакетам приложения и Android-тестов.'
    }
    foreach ($entry in @(@{ Apk = $appApk; Info = $info.app }, @{ Apk = $testApk; Info = $info.test })) {
        if ((Get-FileHash -LiteralPath $entry.Apk -Algorithm SHA256).Hash -ne $entry.Info.sha256 -or
            (Get-Certificate $entry.Apk) -ne $info.certificateSha256) {
            throw "Файл $(Split-Path -Leaf $entry.Apk) не совпадает со сведениями о сборке. Скачайте оба APK из одного архива."
        }
    }
    Write-Host "Проверена сборка $($info.app.versionName) ($($info.app.versionCode)), коммит $($info.commit.Substring(0,12))."

    $devices = & $adb devices -l
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось получить список устройств ADB.' }
    $pixels = @(foreach ($line in $devices) {
        if ($line -match '^(\S+)\s+device\b') {
            $id = $Matches[1]
            if ($id -notmatch ':\d+$|\._adb-tls-connect\._tcp\.?$' -or ($Serial -and $id -ne $Serial)) { continue }
            $model = & $adb -s $id shell getprop ro.product.model
            if ($LASTEXITCODE -eq 0 -and ($model -join '').Trim() -eq 'Pixel 7') { $id }
        }
    })
    if ($pixels.Count -ne 1) {
        throw 'Нужен один доступный Pixel 7 по ADB Wi-Fi. Проверьте беспроводную отладку. Для нескольких устройств укажите -Serial.'
    }
    $selected = $pixels[0]
    $userId = ((Invoke-Device @('shell', 'am', 'get-current-user')) -join '').Trim()
    if ($userId -notmatch '^\d+$') { throw 'Не удалось определить активного пользователя Android.' }
    $services = Invoke-Device @('shell', 'dumpsys', 'activity', 'services', "$appPackage/.vpn.AdaptiveVpnService")
    if ($services -match 'ServiceRecord\{.*\sorg\.adaptivedpi\.android/\S*AdaptiveVpnService\b') {
        throw 'На телефоне уже запущен Adaptive DPI VPN. Остановите его в приложении и повторите команду.'
    }
    $installApp = Test-NeedsInstall $appPackage $info.app
    $installTests = Test-NeedsInstall $testPackage $info.test

    $fixtureLog = Join-Path $logs 'fixture.log'
    $fixtureErrors = Join-Path $logs 'fixture-errors.log'
    $fixtureArguments = $pythonPrefix + @('-X', 'utf8', '-u', ('"{0}"' -f $fixtureScript), '--bind', $TransportTarget)
    $fixture = Start-Process -FilePath $python -ArgumentList $fixtureArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $fixtureLog -RedirectStandardError $fixtureErrors
    $ready = $false
    for ($attempt = 0; $attempt -lt 30 -and -not $fixture.HasExited; $attempt++) {
        if (Select-String -LiteralPath $fixtureLog -Pattern '^ready$' -Quiet) { $ready = $true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) { throw "Стенд не запустился на $TransportTarget. Проверьте fixture-errors.log и порты 39052 и 39053." }
    Write-Host "Стенд запущен на $TransportTarget. Доступ с телефона будет проверен до включения VPN."

    if ($installApp) { Invoke-Device @('install', '--user', $userId, '-r', $appApk) | Write-Host }
    if ($installTests) { Invoke-Device @('install', '--user', $userId, '-r', '-t', $testApk) | Write-Host }
    if (-not $installApp -and -not $installTests) { Write-Host 'Оба APK уже установлены.' }

    Write-Host 'Разблокируйте Pixel 7. Тест сам запустит оба VPN-сеанса. Подтвердите только системный запрос VPN, если он появится.'
    Write-Host 'Согласие ожидается до двух минут. Кнопку «Запустить проверочный сеанс» нажимать не нужно.'
    $captureAndroidLog = $true
    $testLog = Join-Path $logs 'instrumentation.log'
    $testErrors = Join-Path $logs 'instrumentation-errors.log'
    $arguments = @('-s', $selected, 'shell', 'am', 'instrument', '-w', '-r', '--user', $userId,
        '-e', 'class', $testClass, '-e', 'transportTarget', $TransportTarget, '-e', 'requireTransportFixture', 'true',
        '-e', 'waitForVpnConsent', 'true', "$testPackage/$runner")
    $testProcess = Start-Process -FilePath $adb -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $testLog -RedirectStandardError $testErrors
    # Windows PowerShell сохраняет код завершения при удержании дескриптора процесса.
    $null = $testProcess.Handle
    if (-not $testProcess.WaitForExit(360000)) {
        throw 'Android не завершил тест за шесть минут. Если VPN остался включён, остановите его в приложении.'
    }
    $testProcess.WaitForExit()
    $result = Get-Content -LiteralPath $testLog -Raw -Encoding UTF8
    if ($testProcess.ExitCode -ne 0 -or -not $result -or $result -notmatch '(?m)^INSTRUMENTATION_CODE:\s*-1\s*$' -or
        $result -notmatch '(?m)^OK \(1 test\)\s*$') {
        throw 'Сквозной тест завершился ошибкой. Проверьте instrumentation.log, согласие VPN и доступ телефона к стенду.'
    }
    foreach ($session in 1, 2) {
        foreach ($exchange in 'TCP:5/5', 'UDP:5/5', 'DNS/UDP:30/46', 'closed') {
            if ($result -notmatch ('(?m)^INSTRUMENTATION_STATUS: transportProgress=' + [regex]::Escape("session$session=$exchange") + '\s*$')) {
                throw "В отчёте сеанса $session нет подтверждения результата $exchange."
            }
        }
    }
    Write-Host 'УСПЕХ. В двух сеансах через TUN прошли TCP и UDP (5/5 байт) и DNS/UDP (30/46 байт).'
    Write-Host 'VPN перезапустился. После каждой остановки маршрут удалён, активных соединений 0.'
    Write-Host "Журналы: $logs"
    $exitStatus = 0
} catch {
    Write-Host "ОШИБКА. $($_.Exception.Message)" -ForegroundColor Red
    if (Test-Path Variable:logs) {
        $_.Exception.Message | Set-Content -LiteralPath (Join-Path $logs 'error.log') -Encoding UTF8
        if ($captureAndroidLog) {
            try {
                Invoke-Device @('logcat', '-d', '-t', '4000', '-v', 'threadtime',
                    'TunTransportTest:I', 'AdaptiveHev:I', 'AdaptiveTun:I', '*:S') |
                    Set-Content -LiteralPath (Join-Path $logs 'android.log') -Encoding UTF8
            } catch { Write-Host 'Не удалось прочитать журнал Android через ADB.' }
        }
        Write-Host "Журналы: $logs"
    }
} finally {
    foreach ($process in @($testProcess, $fixture)) {
        if ($null -ne $process -and -not $process.HasExited) {
            try { $process.Kill(); $process.WaitForExit(5000) | Out-Null } catch { Write-Host 'Не удалось остановить процесс теста.' }
        }
    }
    if (Test-Path Variable:scratch) {
        Get-ChildItem -LiteralPath $scratch -Filter '*.apk' -File | Remove-Item -Force
        Remove-Item -LiteralPath $scratch -Force
    }
}
exit $exitStatus
