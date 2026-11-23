#!/usr/bin/env bash
set -euo pipefail

apk="$1"
test_apk="$2"
tools="$3"
revision="$4"
build_number="$5"
destination="$6"
expected="${ANDROID_CI_DEBUG_CERT_SHA256:-}"
expected="${expected//:/}"
expected="${expected,,}"
android_results="${ANDROID_TEST_RESULTS_JSON:-}"
if [[ "${GITHUB_ACTIONS:-}" == true || -n "$expected" ]]; then
  if [[ ! "$expected" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Для CI APK требуется закреплённый ANDROID_CI_DEBUG_CERT_SHA256." >&2
    exit 1
  fi
fi
if [[ "${GITHUB_ACTIONS:-}" == true && ! -s "$android_results" ]]; then
  echo "Для публикации APK необходим подтверждённый отчёт Android-тестов." >&2
  exit 1
fi
package_info=$("$tools/aapt2" dump badging "$apk")
test_info=$("$tools/aapt2" dump badging "$test_apk")
resources=$("$tools/aapt2" dump resources "$apk")

case "$package_info" in
  "package: name='org.adaptivedpi.android' "*) ;;
  *) echo "Неожиданный applicationId в APK." >&2; exit 1 ;;
esac
case "$test_info" in
  "package: name='org.adaptivedpi.android.test' "*) ;;
  *) echo "Неожиданный package name инструментальных тестов." >&2; exit 1 ;;
esac
test_manifest=$("$tools/aapt2" dump xmltree --file AndroidManifest.xml "$test_apk")
instrumentation=$(awk '/E: instrumentation / {inside=1; next} /E: / {inside=0} inside' <<< "$test_manifest")
if ! grep -Eq 'android:targetPackage\([^)]*\)="org\.adaptivedpi\.android"' <<< "$instrumentation" ||
   ! grep -Eq 'android:name\([^)]*\)="androidx\.test\.runner\.AndroidJUnitRunner"' <<< "$instrumentation"; then
  echo "Неверная цель или runner инструментальных тестов." >&2
  exit 1
fi

if ! grep -q '^application-debuggable[[:space:]]*$' <<< "$package_info"; then
  echo "APK не является отладочной сборкой." >&2
  exit 1
fi

verify_string() {
  if ! awk -v key="string/$1" -v expected="$2" '
    $1 == "resource" && $3 == key {
      if (getline > 0 && index($0, "\"" expected "\"")) found = 1
    }
    END { exit !found }
  ' <<< "$resources"; then
    echo "В APK нет ожидаемого значения ресурса $1." >&2
    exit 1
  fi
}

verify_string diagnostic_export_all "Экспортировать диагностику"
verify_string diagnostic_export_error "Экспорт отчёта об ошибке"
verify_string transport_export "Экспорт журнала транспорта"
verify_string vpn_switch_title "Обнаружен другой VPN"
verify_string vpn_journal_title "Журнал событий VPN"
verify_string build_git_revision "$revision"
verify_string build_number "$build_number"

entries=$(unzip -Z1 "$apk")
for abi in arm64-v8a x86_64; do
  if ! grep -Fxq "lib/$abi/libadaptive-hev.so" <<< "$entries"; then
    echo "В APK нет HEV для $abi." >&2
    exit 1
  fi
done
for component in hev-socks5-tunnel hev-socks5-core hev-task-system lwip yaml; do
  if ! grep -Fxq "assets/native-licenses/$component.txt" <<< "$entries"; then
    echo "В APK нет лицензии $component." >&2
    exit 1
  fi
done
certificate() {
  local signers
  local -a fingerprints
  signers=$(java -jar "$tools/lib/apksigner.jar" verify --print-certs "$1") || return 1
  mapfile -t fingerprints < <(awk -F ': ' '/^Signer #[0-9]+ certificate SHA-256 digest: / { print $2 }' <<< "$signers" | tr -d '\r')
  if (( ${#fingerprints[@]} != 1 )) || [[ ! "${fingerprints[0]}" =~ ^[0-9a-fA-F]{64}$ ]]; then
    echo "У APK должен быть ровно один сертификат подписи." >&2
    return 1
  fi
  printf '%s\n' "${fingerprints[0],,}"
}
"$tools/zipalign" -c -P 16 4 "$apk"
"$tools/zipalign" -c -P 16 4 "$test_apk"
fingerprint=$(certificate "$apk")
if [[ "$fingerprint" != "$(certificate "$test_apk")" ]]; then
  echo "APK приложения и Android-тестов подписаны разными сертификатами." >&2
  exit 1
fi
if [[ -n "$expected" && "$fingerprint" != "$expected" ]]; then
  echo "Сертификат APK не совпадает с ANDROID_CI_DEBUG_CERT_SHA256." >&2
  exit 1
fi
for file in "$apk" "$test_apk"; do
  if [[ "$(python3 scripts/apk-certificate.py "$file")" != "$fingerprint" ]]; then
    echo "Подпись APK не удалось прочитать средством проверки для Pixel 7." >&2
    exit 1
  fi
done
mkdir -p "$destination"
cp "$apk" "$destination/app-debug.apk"
cp "$test_apk" "$destination/app-debug-androidTest.apk"
cd "$destination"
if [[ -n "$android_results" ]]; then
  count=$(python3 - "$android_results" <<'PY'
import json
from pathlib import Path
import sys

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if not summary["executed"] or summary["failed"] or summary["skipped"]:
    sys.exit("Нет полного успешного инструментального прогона")
print(summary["executed"])
PY
  )
  checks="JVM, lint debug/release и Android: $count тестов на эмуляторе, ошибок 0, пропусков 0"
else
  checks='Тесты CI: сведения отсутствуют'
fi
{
  printf 'Git: %s\nBuild: %s\n' "$revision" "$build_number"
  printf '%s\n%s\n' "${package_info%%$'\n'*}" "${test_info%%$'\n'*}"
  printf 'Test target: org.adaptivedpi.android\nSigner #1 certificate SHA-256 digest: %s\n%s\n' "$fingerprint" "$checks"
  sha256sum app-debug.apk app-debug-androidTest.apk
} > build-info.txt
python3 - "$revision" "$build_number" "$fingerprint" "$checks" "$android_results" \
  "${package_info%%$'\n'*}" "${test_info%%$'\n'*}" > build-info.json <<'PY'
from pathlib import Path
import hashlib
import json
import re
import sys

commit, build, certificate, checks, android_results, app_info, test_info = sys.argv[1:]

def apk_info(name, badging):
    package, code, version = re.match(r"package: name='([^']+)' versionCode='(\d*)' versionName='([^']*)'", badging).groups()
    return {"package": package, "versionCode": int(code) if code else None, "versionName": version or None,
            "sha256": hashlib.sha256(Path(name).read_bytes()).hexdigest()}

app = apk_info("app-debug.apk", app_info)
test = apk_info("app-debug-androidTest.apk", test_info)
expected_code = 25 if build == "local" else max(25, 1 + int(build.split(".")[0]))
if app["versionCode"] != expected_code or app["versionName"] != "0.1.1":
    sys.exit("В APK указана неверная версия для этого запуска.")
test.update(targetPackage=app["package"], runner="androidx.test.runner.AndroidJUnitRunner")
result = json.loads(Path(android_results).read_text(encoding="utf-8")) if android_results else None
print(json.dumps({"commit": commit, "build": build, "certificateSha256": certificate,
                  "checks": checks, "androidTests": result, "app": app, "test": test}, ensure_ascii=False, indent=2))
PY
