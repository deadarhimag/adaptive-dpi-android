#!/usr/bin/env bash
set -euo pipefail

sdk="${ANDROID_HOME:?}/cmdline-tools/latest/bin"
emulator="$ANDROID_HOME/emulator/emulator"
adb="$ANDROID_HOME/platform-tools/adb"
image='system-images;android-34;default;x86_64'
avd_name=diagnostics-ci
port=5554
serial="emulator-$port"
log_dir="${RUNNER_TEMP:?}/tun-ci"
mkdir -p "$log_dir"
emulator_log="$log_dir/emulator.log"
emulator_pid=
fixture_pid=
tls_dir=

fail() {
  echo "::error::$*" >&2
  exit 1
}

cleanup() {
  local status=$?
  if (( status != 0 )); then
    printf 'Код выхода шага: %s\n' "$status" > "$log_dir/status.txt"
    if [[ -n "$emulator_pid" ]]; then
      timeout 10s "$adb" devices -l > "$log_dir/adb-devices.txt" 2>&1 || true
      timeout 10s "$adb" -s "$serial" logcat -d -t 5000 -v threadtime \
        TunTransportTest:I AppTransportTest:I AdaptiveHev:I AdaptiveTun:I Vpn:I AndroidRuntime:E TestRunner:I '*:S' \
        > "$log_dir/android.log" 2>&1 || true
      timeout 10s "$adb" -s "$serial" shell run-as org.adaptivedpi.android cat no_backup/vpn-events/journal \
        > "$log_dir/vpn-journal.txt" 2>&1 || true
      echo 'Последние строки журнала эмулятора:' >&2
      sed -i '/Sending adb public key/d; /androidboot\.qemu\.adb\.pubkey=/d' "$emulator_log" || true
      tail -n 100 "$emulator_log" >&2 || true
      echo 'Диагностика инструментального теста:' >&2
      tail -n 100 "$log_dir/android.log" >&2 || true
    fi
  fi
  if [[ -n "$emulator_pid" ]]; then
    kill "$emulator_pid" 2>/dev/null || true
  fi
  if [[ -n "$fixture_pid" ]]; then
    kill "$fixture_pid" 2>/dev/null || true
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
"$adb" -s "$serial" shell appops set org.adaptivedpi.android ACTIVATE_VPN allow
fixture_log="$log_dir/fixture.log"
# 10.0.2.2 в госте — адрес loopback хоста. VPN направляет только этот /32.
tls_dir=$(mktemp -d "$RUNNER_TEMP/transport-tls.XXXXXX")
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=10.0.2.2' \
  -addext 'subjectAltName=IP:10.0.2.2' -keyout "$tls_dir/key.pem" -out "$tls_dir/cert.pem" \
  2> "$log_dir/openssl.log"
fixture_certificate=$(openssl x509 -in "$tls_dir/cert.pem" -outform DER | base64 -w0)
python3 scripts/transport-fixture.py --bind 127.0.0.1 \
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
ANDROID_SERIAL="$serial" ./gradlew --no-daemon --no-configuration-cache :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.adaptivedpi.android.BuildAndDiagnosticsUiTest,org.adaptivedpi.android.TunTransportInstrumentedTest,org.adaptivedpi.android.ApplicationTransportInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.transportTarget=10.0.2.2 \
  -Pandroid.testInstrumentationRunnerArguments.transportCertificate="$fixture_certificate" \
  -Pandroid.testInstrumentationRunnerArguments.requireTransportFixture=true \
  2>&1 | tee "$log_dir/gradle-tests.log"
timeout 10s "$adb" -s "$serial" logcat -d -t 5000 -v brief TunTransportTest:I AppTransportTest:I AdaptiveTun:I '*:S' || true
