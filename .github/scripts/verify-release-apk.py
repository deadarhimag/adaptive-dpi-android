"""Проверка unsigned release APK и подготовка отдельного артефакта CI."""

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path
from zipfile import BadZipFile, ZipFile


PACKAGE = "org.adaptivedpi.android"
VERSION_CODE = "25"
VERSION_NAME = "0.1.1"
ABIS = ("arm64-v8a", "x86_64")
APK_NAME = "app-release-unsigned.apk"


def tool_output(*command):
    return subprocess.check_output(command, encoding="utf-8")


def verify(apk, aapt2, apksigner, llvm_nm, revision, build):
    badging = tool_output(aapt2, "dump", "badging", apk)
    package = re.match(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
    if not package or package.groups() != (PACKAGE, VERSION_CODE, VERSION_NAME):
        raise ValueError("Неожиданный applicationId или версия unsigned release APK")
    if re.search(r"^application-debuggable\s*$", badging, re.MULTILINE):
        raise ValueError("Release APK оказался отладочным")
    native_code = re.search(r"^native-code:(.*)$", badging, re.MULTILINE)
    if not native_code or sorted(re.findall(r"'([^']+)'", native_code[1])) != list(ABIS):
        raise ValueError("Release APK не содержит ожидаемый набор ABI")
    resources = tool_output(aapt2, "dump", "resources", apk)
    for name, value in (("build_git_revision", revision), ("build_number", build)):
        quoted = re.escape(f'"{value}"')
        if not re.search(rf"^ *resource \S+ string/{name}[^\n]*\n[^\n]*{quoted}", resources, re.MULTILINE):
            raise ValueError(f"В release APK неверное значение ресурса {name}")

    with ZipFile(apk) as archive, apk.open("rb") as data, tempfile.TemporaryDirectory() as temp:
        # Блок подписи v2/v3 находится непосредственно перед оглавлением ZIP.
        if any(re.fullmatch(r"META-INF/(?:[^/]+\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)", name, re.I) for name in archive.namelist()):
            raise ValueError("В release APK обнаружены файлы JAR-подписи")
        data.seek(archive.start_dir - len(b"APK Sig Block 42"))
        if data.read(16) == b"APK Sig Block 42":
            raise ValueError("В release APK обнаружен APK Signing Block")
        for abi in ABIS:
            library = Path(temp) / f"{abi}.so"
            library.write_bytes(archive.read(f"lib/{abi}/libadaptive-hev.so"))
            symbols = {line.split()[-1] for line in tool_output(llvm_nm, "--dynamic", "--defined-only", library).splitlines() if line.strip()}
            if "Java_org_adaptivedpi_android_vpn_HevBridge_counters" not in symbols:
                raise ValueError(f"В release библиотеке HEV для {abi} отсутствуют штатные JNI-символы")
            if any(symbol.startswith(("Java_org_adaptivedpi_android_vpn_TransportTestHooks_",
                                      "Java_org_adaptivedpi_android_vpn_analysis_NativeSocketOps_")) for symbol in symbols):
                raise ValueError(f"В release библиотеке HEV для {abi} найден тестовый JNI-вход")

    signature = subprocess.run(["java", "-jar", apksigner, "verify", apk], capture_output=True, check=False)
    if signature.returncode == 0:
        raise ValueError("apksigner обнаружил подпись у release APK")
    if b"DOES NOT VERIFY" not in signature.stderr + signature.stdout:
        raise ValueError("Не удалось выполнить apksigner verify для release APK")
    with apk.open("rb") as data:
        sha256 = hashlib.file_digest(data, "sha256").hexdigest()
    return {
        "file": APK_NAME, "package": PACKAGE, "versionCode": int(VERSION_CODE), "versionName": VERSION_NAME,
        "abis": list(ABIS), "signed": False, "sha256": sha256,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("aapt2", type=Path)
    parser.add_argument("apksigner", type=Path)
    parser.add_argument("llvm_nm", type=Path)
    parser.add_argument("debug_build_info", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    try:
        debug = json.loads(args.debug_build_info.read_text(encoding="utf-8"))
        info = {
            "commit": debug["commit"],
            "build": debug["build"],
            "androidTests": debug["androidTests"],
            "extendedAndroidTests": debug["extendedAndroidTests"],
        }
        info["app"] = verify(args.apk, args.aapt2, args.apksigner, args.llvm_nm, info["commit"], info["build"])
        info["checks"] = ["applicationId и версия", "ABI arm64-v8a и x86_64", "JNI тестов отсутствует", "APK без подписи"]
        args.destination.mkdir(parents=True, exist_ok=True)
        shutil.copy2(args.apk, args.destination / APK_NAME)
        (args.destination / "build-info.json").write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Проверен unsigned release APK {VERSION_NAME} ({VERSION_CODE}) для {', '.join(ABIS)}")
    except (OSError, ValueError, KeyError, BadZipFile, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Release APK не подтверждён: {error}\n")


if __name__ == "__main__":
    main()
