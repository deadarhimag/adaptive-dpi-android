#!/usr/bin/env bash
set -euo pipefail

apk="$1"
tools="$2"
revision="$3"
build_number="$4"
destination="$5"
package_info=$("$tools/aapt2" dump badging "$apk")
resources=$("$tools/aapt2" dump resources "$apk")

case "$package_info" in
  "package: name='org.adaptivedpi.android' "*) ;;
  *) echo "Неожиданный applicationId в APK." >&2; exit 1 ;;
esac

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
verify_string vpn_journal_title "Журнал событий VPN"
verify_string build_git_revision "$revision"
verify_string build_number "$build_number"

signers=$(java -jar "$tools/lib/apksigner.jar" verify --print-certs "$apk")
fingerprint=$(grep -F 'certificate SHA-256 digest:' <<< "$signers")
mkdir -p "$(dirname "$destination")"
cp "$apk" "$destination"
cd "$(dirname "$destination")"
{
  printf 'Git: %s\nBuild: %s\n' "$revision" "$build_number"
  printf '%s\n' "${package_info%%$'\n'*}" "$fingerprint"
  sha256sum "$(basename "$destination")"
} > build-info.txt
