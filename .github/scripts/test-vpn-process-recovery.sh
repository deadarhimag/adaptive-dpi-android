#!/usr/bin/env bash
set -euo pipefail

adb="${ANDROID_HOME:?}/platform-tools/adb"
serial="${1:?Укажите серийный номер AVD}"
logs="${2:?Укажите каталог результатов}"
[[ "$serial" =~ ^emulator-[0-9]+$ ]]
app=org.adaptivedpi.android
runner="$app.test/androidx.test.runner.AndroidJUnitRunner"
suite="$app.DiagnosticsInstrumentedTest"
setup=armsVpnForExternalProcessDeath
test=anAbortedVpnProcessIsReportedAfterTheNextLaunch

avd() { timeout 10s "$adb" -s "$serial" "$@"; }
[[ $(avd shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
mkdir -p "$logs"
rm -f -- "$logs/result.json" "$logs/junit-results.json" "$logs/TEST-VpnProcessRecovery.xml"

setup_pid=
cleanup() {
  local status=$?
  trap - EXIT
  avd logcat -d -t 3000 -v threadtime VpnRecoveryTest:I AdaptiveTun:I AdaptiveHev:I Vpn:I AndroidRuntime:E '*:S' \
    > "$logs/android.log" 2>&1 || true
  if [[ -n "$setup_pid" ]]; then
    avd shell am force-stop "$app" >/dev/null 2>&1 || true
    kill "$setup_pid" 2>/dev/null || true
    wait "$setup_pid" 2>/dev/null || true
  fi
  exit "$status"
}
trap cleanup EXIT

avd shell ip -o address show > "$logs/initial-addresses.txt"
if grep -qF '192.0.2.1/32' "$logs/initial-addresses.txt"; then
  echo 'На AVD уже есть TUN приложения.' >&2
  exit 1
fi

timeout 60s "$adb" -s "$serial" shell am instrument -w -r \
  -e class "$suite#$setup" -e transportTarget 10.0.2.2 "$runner" > "$logs/setup-instrumentation.log" 2>&1 &
setup_pid=$!
ready=''
for ((attempt=0; attempt<25; attempt++)); do
  avd logcat -d -v brief VpnRecoveryTest:I '*:S' > "$logs/ready.log"
  ready=$(sed -nE 's/^.*: READY ([1-9][0-9]*) ([a-f0-9-]{36})\r?$/\1 \2/p' "$logs/ready.log")
  [[ -z "$ready" ]] || break
  kill -0 "$setup_pid"
  sleep 1
done
[[ "$ready" =~ ^([1-9][0-9]*)\ ([a-f0-9-]{36})$ ]]
pid="${BASH_REMATCH[1]}"
session="${BASH_REMATCH[2]}"
[[ $(avd shell pidof "$app" | tr -d '\r') == "$pid" ]]
avd shell run-as "$app" test -s no_backup/diagnostics/active-vpn
avd shell ip -o address show > "$logs/active-addresses.txt"
grep -qF '192.0.2.1/32' "$logs/active-addresses.txt"

# Сигнал из процесса shell через run-as уничтожает только подтверждённый PID тестируемого debug APK.
avd shell run-as "$app" kill -KILL "$pid"
released=false
for ((attempt=0; attempt<15; attempt++)); do
  avd shell ip -o address show > "$logs/released-addresses.txt"
  processes=$(avd shell pidof "$app" | tr -d '\r') || processes=''
  if [[ "$processes" != "$pid" ]] && ! grep -qF '192.0.2.1/32' "$logs/released-addresses.txt"; then
    released=true
    break
  fi
  sleep 1
done
"$released"
wait "$setup_pid" || true
setup_pid=

timeout 35s "$adb" -s "$serial" shell am instrument -w -r \
  -e class "$suite#$test" -e recoveredVpnError VpnInterrupted -e recoveredVpnSession "$session" \
  -e oldVpnPid "$pid" -e transportTarget 10.0.2.2 "$runner" | tr -d '\r' | tee "$logs/recovery-instrumentation.log"
python3 .github/scripts/verify-extended-transport-results.py --process-recovery \
  "$logs/recovery-instrumentation.log" "$logs/TEST-VpnProcessRecovery.xml" "$logs/junit-results.json"
printf '{"scenario":"S1-VPN-PROCESS-01","passed":true,"sessionId":"%s"}\n' "$session" > "$logs/result.json"
