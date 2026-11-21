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
"$tools/zipalign" -c -P 16 4 "$apk"

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
