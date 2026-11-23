#!/usr/bin/env bash
set -euo pipefail

sdk="${ANDROID_HOME:?}/cmdline-tools/latest/bin"
emulator="$ANDROID_HOME/emulator/emulator"
adb="$ANDROID_HOME/platform-tools/adb"
image='system-images;android-34;default;x86_64'
mode="${1:-baseline}"
case "$mode" in
  baseline) avd_name=diagnostics-ci; port=5554; log_dir="${RUNNER_TEMP:?}/tun-ci" ;;
  --extended) avd_name=transport-ci; port=5556; log_dir="${RUNNER_TEMP:?}/tun-ci-extended" ;;
  *) echo "Неизвестный набор Android-тестов: $mode" >&2; exit 2 ;;
esac
serial="emulator-$port"
mkdir -p "$log_dir"
emulator_log="$log_dir/emulator.log"
emulator_pid=
fixture_pid=
tls_dir=
consent_in_progress=false
blackhole_installed=false
blackhole=(-o lo -d 127.0.0.1 -p tcp --dport 39056 --syn -m comment --comment adaptive-tun-connect-ci -j DROP)

fail() {
  echo "::error::$*" >&2
  exit 1
}

capture_consent_diagnostics() {
  timeout 8s "$adb" -s "$serial" logcat -d -t 5000 -v threadtime \
    VpnConsentTest:I VpnConfirm:V VpnDialogs:V VpnService:V Vpn:V ActivityTaskManager:I TestRunner:I '*:S' 2>/dev/null \
    | awk '!/ActivityTaskManager:/ || /org\.adaptivedpi\.android|com\.android\.vpndialogs/' \
    | head -c 131072 > "$log_dir/consent-system.log" || true
  {
    timeout 6s "$adb" -s "$serial" shell dumpsys window windows \
      | grep -E 'Window #[0-9]+ Window|mCurrentFocus=|mFocusedApp=' || true
    timeout 6s "$adb" -s "$serial" shell dumpsys activity activities \
      | grep -E 'mResumedActivity:|topResumedActivity=' || true
  } | head -c 16384 > "$log_dir/consent-foreground.txt" || true

  # Снимок со стороны теста сделан до закрытия Activity. При жёстком тайм-ауте
  # пробуем получить текущее состояние с живого эмулятора.
  local file limit
  for file in consent-screen.jpg consent-windows.xml; do
    limit=524288
    if [[ "$file" == consent-windows.xml ]]; then limit=65536; fi
    if ! timeout 6s "$adb" -s "$serial" exec-out run-as org.adaptivedpi.android \
        cat "cache/vpn-consent-ci/$file" 2>/dev/null | head -c "$((limit + 1))" > "$log_dir/$file" \
        || [[ ! -s "$log_dir/$file" || $(wc -c < "$log_dir/$file") -gt "$limit" ]]; then
      rm -f -- "$log_dir/$file"
    fi
  done
  if [[ ! -s "$log_dir/consent-screen.jpg" ]]; then
    if ! timeout 6s "$adb" -s "$serial" exec-out screencap -p | head -c 8388609 > "$log_dir/consent-screen.png" \
        || [[ ! -s "$log_dir/consent-screen.png" || $(wc -c < "$log_dir/consent-screen.png") -gt 8388608 ]]; then
      rm -f -- "$log_dir/consent-screen.png"
    fi
  fi
  if [[ ! -s "$log_dir/consent-windows.xml" ]] &&
      timeout 8s "$adb" -s "$serial" shell uiautomator dump /data/local/tmp/consent-window.xml >/dev/null 2>&1; then
    # В артефакт попадают только структурные атрибуты, без текста элементов.
    if ! timeout 6s "$adb" -s "$serial" exec-out cat /data/local/tmp/consent-window.xml \
        | head -c 262145 | python3 -c '
import re
import sys
from xml.etree import ElementTree as ET

raw = sys.stdin.buffer.read(262145)
if len(raw) > 262144:
    sys.exit(1)
source = ET.fromstring(raw)
root = ET.Element("hierarchy", capture="after-test")
keys = ("package", "class", "resource-id", "clickable", "enabled", "bounds")
remaining = 96

def copy_nodes(source, destination, depth):
    global remaining
    for node in source.findall("node"):
        if not remaining or depth == 12:
            break
        remaining -= 1
        attrs = {k: re.sub(r"[^a-zA-Z0-9_.:/\[\],$-]", "", node.get(k, "")[:96]) for k in keys}
        copy_nodes(node, ET.SubElement(destination, "node", attrs), depth + 1)

copy_nodes(source, root, 0)
sys.stdout.buffer.write(ET.tostring(root, encoding="utf-8", xml_declaration=True))
' > "$log_dir/consent-windows.xml" 2>/dev/null; then
      rm -f -- "$log_dir/consent-windows.xml"
    fi
    timeout 5s "$adb" -s "$serial" shell rm -f /data/local/tmp/consent-window.xml >/dev/null 2>&1 || true
  fi
}

capture_vpn_journal() {
  local user paths
  if ! user=$(timeout 6s "$adb" -s "$serial" shell am get-current-user | tr -d '\r') ||
      [[ ! "$user" =~ ^[0-9]+$ ]]; then
    echo 'Не удалось определить текущего пользователя Android.'
    return
  fi
  echo "Пользователь Android: $user"
  if ! paths=$(timeout 6s "$adb" -s "$serial" shell pm path --user "$user" org.adaptivedpi.android) ||
      [[ "$paths" != package:* ]]; then
    echo 'Установку APK Adaptive DPI у текущего пользователя подтвердить не удалось.'
    return
  fi
  echo 'APK Adaptive DPI установлен.'
  if ! timeout 10s "$adb" -s "$serial" exec-out run-as org.adaptivedpi.android --user "$user" \
      cat no_backup/vpn-events/journal | head -c 65537 > "$log_dir/vpn-journal.txt" \
      || [[ $(wc -c < "$log_dir/vpn-journal.txt") -gt 65536 ]]; then
    rm -f -- "$log_dir/vpn-journal.txt"
    echo 'Журнал недоступен. См. снимок VpnLifecycleTest в android.log.'
  fi
}

cleanup() {
  local status=$?
  if (( status != 0 )); then
    printf 'Код выхода шага: %s\n' "$status" > "$log_dir/status.txt"
    if [[ -n "$emulator_pid" ]]; then
      if $consent_in_progress; then
        capture_consent_diagnostics
      fi
      timeout 10s "$adb" devices -l > "$log_dir/adb-devices.txt" 2>&1 || true
      timeout 10s "$adb" -s "$serial" logcat -d -t 5000 -v threadtime \
        TunTransportTest:I AppTransportTest:I ExtendedTunTest:I VpnLifecycleTest:I AdaptiveHev:I AdaptiveTun:I Vpn:I AndroidRuntime:E TestRunner:I '*:S' \
        > "$log_dir/android.log" 2>&1 || true
      capture_vpn_journal > "$log_dir/vpn-journal-status.txt" 2>&1 || true
      echo 'Последние строки журнала эмулятора:' >&2
      sed -i '/Sending adb public key/d; /androidboot\.qemu\.adb\.pubkey=/d' "$emulator_log" || true
      tail -n 100 "$emulator_log" >&2 || true
      echo 'Диагностика инструментального теста:' >&2
      tail -n 100 "$log_dir/android.log" >&2 || true
    fi
  fi
  if [[ -n "$emulator_pid" ]]; then
    kill "$emulator_pid" 2>/dev/null || true
    sed -i '/Sending adb public key/d; /androidboot\.qemu\.adb\.pubkey=/d' "$emulator_log" || true
  fi
  if [[ -n "$fixture_pid" ]]; then
    kill "$fixture_pid" 2>/dev/null || true
  fi
  if $blackhole_installed; then
    sudo iptables -w -nvxL OUTPUT | awk '/adaptive-tun-connect-ci/ {print $1}' > "$log_dir/blocked-syn-count.txt" || true
    sudo iptables -w -D OUTPUT "${blackhole[@]}" || true
  fi
  if [[ -n "$tls_dir" ]]; then
    rm -f -- "$tls_dir/key.pem" "$tls_dir/cert.pem"
    rmdir -- "$tls_dir"
  fi
}
trap cleanup EXIT

"$sdk/sdkmanager" "emulator" "platform-tools" "$image" || fail "Не удалось установить системный образ $image."
[[ -c /dev/kvm ]] || fail "На runner отсутствует /dev/kvm."
sudo chown "$(id -u)" /dev/kvm || fail "Не удалось настроить доступ к /dev/kvm."
"$emulator" -accel-check || fail "Эмулятору недоступно аппаратное ускорение KVM."

ANDROID_AVD_HOME=$(mktemp -d "$RUNNER_TEMP/diagnostics-avd.XXXXXX")
export ANDROID_AVD_HOME
avd_path="$ANDROID_AVD_HOME/$avd_name.avd"
echo "Создание AVD $avd_name в $ANDROID_AVD_HOME"
if ! "$sdk/avdmanager" --verbose create avd --name "$avd_name" --package "$image" \
    --device pixel_7 --path "$avd_path" <<< 'no'; then
  fail "avdmanager не смог создать AVD $avd_name из образа $image."
fi
if [[ ! -s "$ANDROID_AVD_HOME/$avd_name.ini" || ! -s "$avd_path/config.ini" ]]; then
  ls -la "$ANDROID_AVD_HOME" >&2
  fail "avdmanager не записал конфигурацию AVD $avd_name в $ANDROID_AVD_HOME."
fi
df -h "$avd_path" > "$log_dir/disk-space.txt"

avds=$("$emulator" -list-avds) || fail "Эмулятор не смог прочитать каталог AVD $ANDROID_AVD_HOME."
if ! grep -Fxq "$avd_name" <<< "$avds"; then
  printf 'Эмулятор видит следующие AVD:\n%s\n' "$avds" >&2
  fail "Эмулятор не видит AVD $avd_name в $ANDROID_AVD_HOME."
fi

"$emulator" -avd "$avd_name" -port "$port" -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader > "$emulator_log" 2>&1 &
emulator_pid=$!
echo "Ожидание Android на $serial"
deadline=$((SECONDS + 300))
boot_completed=
until [[ "$boot_completed" == 1 ]]; do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    status=0
    wait "$emulator_pid" || status=$?
    fail "Эмулятор $avd_name завершился до загрузки Android (код $status)."
  fi
  (( SECONDS < deadline )) || fail "Android на $serial не загрузился за 300 секунд."
  boot_completed=$(timeout 5s "$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  if [[ "$boot_completed" != 1 ]]; then sleep 2; fi
done

"$adb" -s "$serial" shell input keyevent 82 || fail "ADB не смог разблокировать экран $serial."
"$adb" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$adb" -s "$serial" shell pm grant org.adaptivedpi.android android.permission.POST_NOTIFICATIONS
consent_in_progress=true
timeout 60s "$adb" -s "$serial" shell am instrument -w -r \
  -e class org.adaptivedpi.android.VpnConsentInstrumentedTest#grantsVpnThroughAndroidConfirmationDialog \
  -e verifyFreshVpnConsent true -e captureVpnConsentFailure true \
  org.adaptivedpi.android.test/androidx.test.runner.AndroidJUnitRunner \
  | tr -d '\r' | tee "$log_dir/consent.log"
grep -Fxq 'OK (1 test)' "$log_dir/consent.log" || fail 'Системный диалог согласия VPN не пройден.'
consent_in_progress=false
fixture_log="$log_dir/fixture.log"
# 10.0.2.2 в госте — адрес loopback хоста для обоих сценариев TUN.
tls_dir=$(mktemp -d "$RUNNER_TEMP/transport-tls.XXXXXX")
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=10.0.2.2' \
  -addext 'subjectAltName=IP:10.0.2.2' -keyout "$tls_dir/key.pem" -out "$tls_dir/cert.pem" \
  2> "$log_dir/openssl.log"
fixture_certificate=$(openssl x509 -in "$tls_dir/cert.pem" -outform DER | base64 -w0)
fixture_args=()
if [[ "$mode" == --extended ]]; then fixture_args+=(--extended); fi
python3 scripts/transport-fixture.py --bind 127.0.0.1 "${fixture_args[@]}" \
  --https-certificate "$tls_dir/cert.pem" --https-key "$tls_dir/key.pem" > "$fixture_log" 2>&1 &
fixture_pid=$!
for ((attempt=0; attempt<20; attempt++)); do
  if grep -qx ready "$fixture_log"; then break; fi
  if ! kill -0 "$fixture_pid" 2>/dev/null; then break; fi
  sleep 1
done
if ! grep -qx ready "$fixture_log"; then
  cat "$fixture_log" >&2
  fail "Локальный стенд TCP/UDP/DNS не запустился."
fi
if [[ "$mode" == --extended ]]; then
  # Только исходящие SYN к выделенному loopback-порту стенда. Android работает без root.
  sudo iptables -w -I OUTPUT 1 "${blackhole[@]}"
  blackhole_installed=true
  timeout 540s "$adb" -s "$serial" shell am instrument -w -r \
    -e class org.adaptivedpi.android.ExtendedTunTransportInstrumentedTest \
    -e transportTarget 10.0.2.2 \
    org.adaptivedpi.android.test/androidx.test.runner.AndroidJUnitRunner \
    | tr -d '\r' | tee "$log_dir/extended-instrumentation.log"
  python3 .github/scripts/verify-extended-transport-results.py \
    "$log_dir/extended-instrumentation.log" "$log_dir/results.json"
  sudo iptables -w -nvxL OUTPUT | awk '/adaptive-tun-connect-ci/ {print $1}' > "$log_dir/blocked-syn-count.txt"
  grep -Eq '^[1-9][0-9]*$' "$log_dir/blocked-syn-count.txt" || fail 'Стенд не зафиксировал SYN блокируемого connect.'
  timeout 10s "$adb" -s "$serial" logcat -d -t 5000 -v brief ExtendedTunTest:I AdaptiveTun:I '*:S' \
    > "$log_dir/extended-android.log" || true
  exit 0
fi
test_filter=$(python3 .github/scripts/verify-android-test-results.py --filter)
ANDROID_SERIAL="$serial" ./gradlew --no-daemon --no-configuration-cache :app:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class="$test_filter" \
  -Pandroid.testInstrumentationRunnerArguments.transportTarget=10.0.2.2 \
  -Pandroid.testInstrumentationRunnerArguments.transportCertificate="$fixture_certificate" \
  -Pandroid.testInstrumentationRunnerArguments.requireTransportFixture=true \
  2>&1 | tee "$log_dir/gradle-tests.log"
python3 .github/scripts/verify-android-test-results.py app/build/outputs/androidTest-results/connected \
  "$log_dir/consent.log" "$log_dir/results.json"
timeout 10s "$adb" -s "$serial" logcat -d -t 5000 -v brief TunTransportTest:I AppTransportTest:I AdaptiveTun:I '*:S' || true
