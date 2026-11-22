#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "::error::$1" >&2
  exit 1
}

missing=()
for name in ANDROID_CI_DEBUG_KEYSTORE_BASE64 ANDROID_CI_DEBUG_STORE_PASSWORD \
  ANDROID_CI_DEBUG_KEY_PASSWORD ANDROID_CI_DEBUG_CERT_SHA256; do
  [[ -n "${!name:-}" ]] || missing+=("$name")
done
if (( ${#missing[@]} > 0 )); then
  fail "Настройте параметры постоянной debug-подписи в GitHub Actions: ${missing[*]}."
fi

expected="${ANDROID_CI_DEBUG_CERT_SHA256//:/}"
expected="${expected,,}"
[[ "$expected" =~ ^[0-9a-f]{64}$ ]] ||
  fail "Переменная ANDROID_CI_DEBUG_CERT_SHA256 должна содержать SHA-256 сертификата."

umask 077
directory=$(mktemp -d "${RUNNER_TEMP:?}/adaptive-ci-signing.XXXXXX")
keystore="$directory/debug.jks"
prepared=false
trap 'if [[ "$prepared" == false ]]; then rm -f -- "$keystore"; rmdir -- "$directory"; fi' EXIT

if ! printf '%s' "${ANDROID_CI_DEBUG_KEYSTORE_BASE64//$'\r'/}" | base64 --decode > "$keystore" 2>/dev/null ||
   [[ ! -s "$keystore" ]]; then
  fail "Не удалось декодировать CI debug keystore из ANDROID_CI_DEBUG_KEYSTORE_BASE64."
fi
unset ANDROID_CI_DEBUG_KEYSTORE_BASE64

alias_name=adaptive-dpi-ci-debug
if ! keytool -certreq -storetype JKS -keystore "$keystore" -alias "$alias_name" \
    -storepass:env ANDROID_CI_DEBUG_STORE_PASSWORD -keypass:env ANDROID_CI_DEBUG_KEY_PASSWORD \
    >/dev/null 2>&1; then
  fail "Не удалось открыть CI debug ключ. Проверьте формат JKS, псевдоним adaptive-dpi-ci-debug и оба пароля."
fi

if ! fingerprint=$(keytool -exportcert -storetype JKS -keystore "$keystore" -alias "$alias_name" \
    -storepass:env ANDROID_CI_DEBUG_STORE_PASSWORD 2>/dev/null | sha256sum); then
  fail "Не удалось получить сертификат CI debug ключа."
fi
[[ "${fingerprint%% *}" == "$expected" ]] ||
  fail "Сертификат CI debug keystore не совпадает с ANDROID_CI_DEBUG_CERT_SHA256."

printf 'ANDROID_CI_DEBUG_KEYSTORE_PATH=%s\nANDROID_CI_DEBUG_CERT_SHA256=%s\n' "$keystore" "$expected" >> "${GITHUB_ENV:?}"
prepared=true
echo "CI debug keystore и закреплённый сертификат проверены."
