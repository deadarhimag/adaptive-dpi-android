#!/usr/bin/env bash
set -euo pipefail

sdk="${ANDROID_HOME:?}/cmdline-tools/latest/bin"
emulator="$ANDROID_HOME/emulator/emulator"
adb="$ANDROID_HOME/platform-tools/adb"
image='system-images;android-34;default;x86_64'
avd_name=diagnostics-ci
port=5554
serial="emulator-$port"
emulator_log="${RUNNER_TEMP:?}/diagnostics-emulator.log"
emulator_pid=

fail() {
  echo "::error::$*" >&2
  exit 1
}

cleanup() {
  local status=$?
  if (( status != 0 )) && [[ -n "$emulator_pid" ]]; then
    echo 'Список устройств ADB:' >&2
    timeout 10s "$adb" devices -l >&2 || true
    echo 'Последние строки журнала эмулятора:' >&2
    tail -n 100 "$emulator_log" >&2 || true
  fi
  if [[ -n "$emulator_pid" ]]; then
    kill "$emulator_pid" 2>/dev/null || true
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
ANDROID_SERIAL="$serial" ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.adaptivedpi.android.BuildAndDiagnosticsUiTest
