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
validator=.github/scripts/verify-extended-transport-results.py

avd() { timeout 10s "$adb" -s "$serial" "$@"; }
[[ $(avd shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
mkdir -p "$logs"
rm -f -- "$logs/result.json" "$logs/result.json.tmp" "$logs/junit-results.json" "$logs/TEST-VpnProcessRecovery.xml"

setup_pid=
experiment_started=false
run_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
read_app_pids() { avd shell "pidof $app || [ \$? -eq 1 ]" | tr -d '\r'; }
setup_running() {
  kill -0 "$setup_pid" 2>/dev/null &&
    [[ -r "$logs/setup-instrumentation.log" ]] &&
    ! grep -Eq '^INSTRUMENTATION_(FAILED|ABORTED|CODE):' "$logs/setup-instrumentation.log"
}

cleanup() {
  local status=$? clean=false processes
  trap - EXIT
  if $experiment_started; then
    if avd shell am force-stop "$app" > "$logs/force-stop.log" 2>&1; then
      for ((attempt=0; attempt<5; attempt++)); do
        if processes=$(read_app_pids) && [[ -z "$processes" ]] &&
            avd shell ip -o address show > "$logs/final-addresses.txt" &&
            ! grep -qF '192.0.2.1/32' "$logs/final-addresses.txt"; then
          clean=true
          break
        fi
        sleep 1
      done
    fi
    if [[ -n "$setup_pid" ]]; then
      kill "$setup_pid" 2>/dev/null || true
      wait "$setup_pid" 2>/dev/null || true
    fi
    if ! $clean; then
      echo 'Не подтверждена очистка VPN и процесса после эксперимента.' >&2
      if (( status == 0 )); then status=1; fi
    fi
  fi
  avd logcat -d -t 3000 -v threadtime VpnRecoveryTest:I AdaptiveTun:I AdaptiveHev:I Vpn:I AndroidRuntime:E '*:S' \
    > "$logs/android.log" 2>&1 || true
  if (( status == 0 )); then
    if ! printf '{"scenario":"S1-VPN-PROCESS-01","passed":true,"runId":"%s","pid":%s,"sessionId":"%s"}\n' \
        "$run_id" "$pid" "$session" > "$logs/result.json.tmp" ||
        ! mv -- "$logs/result.json.tmp" "$logs/result.json"; then
      status=1
    fi
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

avd shell ip -o address show > "$logs/initial-addresses.txt"
if grep -qF '192.0.2.1/32' "$logs/initial-addresses.txt"; then
  echo 'На AVD уже есть TUN приложения.' >&2
  exit 1
fi

: > "$logs/setup-instrumentation.log"
experiment_started=true
timeout -k 5s 60s "$adb" -s "$serial" shell am instrument -w -r \
  -e class "$suite#$setup" -e transportTarget 10.0.2.2 -e recoveryRunId "$run_id" \
  "$runner" > "$logs/setup-instrumentation.log" 2>&1 &
setup_pid=$!
ready=''
ready_deadline=$((SECONDS + 25))
while (( SECONDS < ready_deadline )); do
  if ! setup_running; then
    echo 'Подготовительный тест завершился до SIGKILL.' >&2
    exit 1
  fi
  avd logcat -d -v brief VpnRecoveryTest:I '*:S' > "$logs/ready.log"
  if ready=$(python3 "$validator" --ready-run "$run_id" "$logs/ready.log"); then
    break
  else
    status=$?
    if (( status != 3 )); then exit "$status"; fi
  fi
  sleep 1
done
if [[ -z "$ready" ]]; then
  echo 'Истёк срок ожидания READY текущего эксперимента.' >&2
  exit 1
fi
read -r pid session <<< "$ready"
avd shell run-as "$app" cat no_backup/diagnostics/active-vpn > "$logs/active-marker.txt" ||
  { echo 'Не удалось прочитать активный маркер VPN.' >&2; exit 1; }
awk -F '\t' -v id="$session" '($1 == 1 || $1 == 2) && $2 == id { matched++ } END { exit matched != 1 }' "$logs/active-marker.txt" ||
  { echo 'Активный маркер VPN не соответствует сеансу из READY.' >&2; exit 1; }
avd shell ip -o address show > "$logs/active-addresses.txt"
grep -qF '192.0.2.1/32' "$logs/active-addresses.txt"

# Сигнал из процесса shell через run-as уничтожает только подтверждённый PID тестируемого debug APK.
setup_running || { echo 'Подготовительный тест завершился до SIGKILL.' >&2; exit 1; }
[[ $(read_app_pids) == "$pid" ]] || { echo 'PID из READY не принадлежит текущему процессу приложения.' >&2; exit 1; }
avd shell run-as "$app" kill -KILL "$pid"
released=false
for ((attempt=0; attempt<15; attempt++)); do
  avd shell ip -o address show > "$logs/released-addresses.txt"
  processes=$(read_app_pids)
  if [[ " $processes " != *" $pid "* ]] && ! grep -qF '192.0.2.1/32' "$logs/released-addresses.txt"; then
    released=true
    break
  fi
  sleep 1
done
"$released"
wait "$setup_pid" || true
setup_pid=

timeout -k 5s 35s "$adb" -s "$serial" shell am instrument -w -r \
  -e class "$suite#$test" -e recoveredVpnError VpnInterrupted -e recoveredVpnSession "$session" \
  -e oldVpnPid "$pid" -e recoveryRunId "$run_id" -e transportTarget 10.0.2.2 "$runner" \
  | tr -d '\r' | tee "$logs/recovery-instrumentation.log"
python3 "$validator" --process-recovery \
  "$logs/recovery-instrumentation.log" "$logs/TEST-VpnProcessRecovery.xml" "$logs/junit-results.json"
