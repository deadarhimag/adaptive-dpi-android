#requires -Version 5.1
# Снимок и восстановление состояния Pixel 7 вокруг проверок на раннере pixel7 (задача 1.6a).
# Находит телефон и сам не запускает тестов. Действия:
#   Snapshot  - проверяет предварительные условия и записывает исходное состояние в $StateFile;
#   WifiCycle - выключает и снова включает Wi-Fi (нужно подключение по USB, иначе ADB по Wi-Fi оборвётся);
#   Collect   - сохраняет журнал приложения и данные батареи;
#   Restore   - возвращает только то, что меняли проверки, и пишет в итог, что вернуть не удалось.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Snapshot', 'WifiCycle', 'Collect', 'Restore')][string]$Action,
    [Parameter(Mandatory)][string]$StateFile,
    [string]$Serial,
    [string]$LogDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$appPackage = 'org.adaptivedpi.android'
$wirelessPattern = ':\d+$|\._adb-tls-connect\._tcp\.?$'
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

function Select-Pixel {
    $devices = & $adb devices -l
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось получить список устройств ADB.' }
    $found = @(foreach ($line in $devices) {
        if ($line -match '^(\S+)\s+device\b') {
            $id = $Matches[1]
            if ($Serial -and $id -ne $Serial) { continue }
            $model = & $adb -s $id shell getprop ro.product.model
            if ($LASTEXITCODE -eq 0 -and ($model -join '').Trim() -eq 'Pixel 7') { $id }
        }
    })
    if ($found.Count -ne 1) {
        throw 'Нужен ровно один доступный Pixel 7 в списке adb devices. Для нескольких устройств укажите serial.'
    }
    return $found[0]
}

function Get-WifiOn {
    $value = ((Invoke-Device @('shell', 'settings', 'get', 'global', 'wifi_on')) -join '').Trim()
    if ($value -notmatch '^[012]$') { throw "Не удалось определить состояние Wi-Fi (settings вернул '$value')." }
    return $value -ne '0'
}

function Wait-Wifi {
    param([bool]$Expected, [int]$Seconds)
    for ($second = 0; $second -lt $Seconds; $second++) {
        if ((Get-WifiOn) -eq $Expected) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Test-ForeignVpn {
    $text = (Invoke-Device @('shell', 'dumpsys', 'connectivity')) -join "`n"
    # Если формат вывода изменился и записей сетей нет, нельзя утверждать, что VPN нет: задание не начинается.
    if ($text -notmatch 'NetworkAgentInfo\{') { throw 'Не удалось прочитать список сетей из dumpsys connectivity: формат вывода не распознан.' }
    return $text -match 'NetworkAgentInfo\{[^\r\n]*\bVPN\b'
}

function Test-OurService {
    $services = Invoke-Device @('shell', 'dumpsys', 'activity', 'services', "$appPackage/.vpn.AdaptiveVpnService")
    return ($services -join "`n") -match 'ServiceRecord\{.*\sorg\.adaptivedpi\.android/\S*AdaptiveVpnService\b'
}

try {
    $adb = Find-Adb
    if ($Action -eq 'Snapshot') {
        $selected = Select-Pixel
        $call = ((Invoke-Device @('shell', 'dumpsys', 'telephony.registry')) -join "`n")
        if ($call -match 'mCallState=([1-9])') { throw 'Телефон занят: идёт или поступает звонок.' }
        if (Test-OurService) { throw 'На телефоне уже запущен Adaptive DPI VPN. Остановите его в приложении.' }
        if (Test-ForeignVpn) { throw 'На телефоне уже активен VPN. Отключите его: проверка не начинается, чтобы не менять чужой VPN.' }
        $state = [ordered]@{
            serial = $selected
            wireless = [bool]($selected -match $wirelessPattern)
            wifiOn = Get-WifiOn
            vpnActive = $false
            takenAt = (Get-Date).ToUniversalTime().ToString('o')
        }
        $state | ConvertTo-Json | Set-Content -LiteralPath $StateFile -Encoding UTF8
        Write-Host "Снимок состояния: Wi-Fi $(if ($state.wifiOn) { 'включён' } else { 'выключен' }), VPN нет, подключение $(if ($state.wireless) { 'по Wi-Fi' } else { 'по USB' })."
    } else {
        $state = Get-Content -LiteralPath $StateFile -Raw -Encoding UTF8 | ConvertFrom-Json
        $selected = [string]$state.serial
        if ($Action -eq 'WifiCycle') {
            if ($state.wireless) { throw 'Переключение Wi-Fi разорвёт ADB по Wi-Fi. Подключите телефон по USB и укажите его serial.' }
            if (-not $state.wifiOn) { throw 'Wi-Fi был выключен до проверки: сценарий переключения не выполняется, чтобы не менять сеть.' }
            Invoke-Device @('shell', 'svc', 'wifi', 'disable') | Out-Null
            if (-not (Wait-Wifi $false 15)) { throw 'Wi-Fi не выключился за 15 секунд.' }
            Start-Sleep -Seconds 5
            Invoke-Device @('shell', 'svc', 'wifi', 'enable') | Out-Null
            if (-not (Wait-Wifi $true 30)) { throw 'Wi-Fi не включился за 30 секунд.' }
            Write-Host 'Wi-Fi выключен и снова включён.'
        } elseif ($Action -eq 'Collect') {
            if (-not $LogDirectory) { throw 'Для Collect нужен LogDirectory.' }
            $logs = (New-Item -ItemType Directory -Path $LogDirectory -Force).FullName
            Invoke-Device @('logcat', '-d', '-v', 'threadtime') |
                Select-String -Pattern 'AdaptiveTun|AdaptiveHev|TunTransportTest|org\.adaptivedpi\.android' |
                ForEach-Object { $_.Line } | Set-Content -LiteralPath (Join-Path $logs 'android.log') -Encoding UTF8
            # Данные батареи Android накапливает с последнего сброса, а не с начала сценария.
            Invoke-Device @('shell', 'dumpsys', 'batterystats', $appPackage) |
                Set-Content -LiteralPath (Join-Path $logs 'batterystats.txt') -Encoding UTF8
            Write-Host "Журналы сохранены в $logs"
        } else {
            $problems = @()
            try {
                if ((Get-WifiOn) -ne [bool]$state.wifiOn) {
                    Invoke-Device @('shell', 'svc', 'wifi', $(if ($state.wifiOn) { 'enable' } else { 'disable' })) | Out-Null
                    if (-not (Wait-Wifi ([bool]$state.wifiOn) 30)) { $problems += 'Wi-Fi не удалось вернуть в исходное состояние.' }
                }
            } catch { $problems += "Wi-Fi: $($_.Exception.Message)" }
            try {
                if (Test-OurService) { $problems += 'VPN приложения остался запущенным: остановите его в приложении.' }
            } catch { $problems += "Служба VPN: $($_.Exception.Message)" }
            if ($problems.Count -gt 0) {
                foreach ($problem in $problems) { Write-Host "НЕ ВОССТАНОВЛЕНО. $problem" -ForegroundColor Red }
                if ($env:GITHUB_STEP_SUMMARY) { Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Value ("**Не восстановлено:** " + ($problems -join ' ')) }
                throw 'Исходное состояние телефона восстановлено не полностью.'
            }
            Write-Host 'Исходное состояние телефона восстановлено.'
        }
    }
    $exitStatus = 0
} catch {
    Write-Host "ОШИБКА. $($_.Exception.Message)" -ForegroundColor Red
}
exit $exitStatus
