"""Проверка границы DEX самостоятельно загружаемых компонентов AndroidTest APK."""

import argparse
import re
import subprocess
import tempfile
from pathlib import Path
from zipfile import BadZipFile, ZipFile


PACKAGE = "Lorg/adaptivedpi/android/"
STANDALONE = ("SafTestProvider", "AppTransportProbe")
TEST_ONLY = (*STANDALONE, "SafFixture", "SafFixtureInstrumentedTest", "DiagnosticDocumentRecoveryChecks")
PLATFORM = ("Landroid/", "Ljava/", "Ljavax/", "Ldalvik/annotation/")
INTRINSICS = "Lkotlin/jvm/internal/Intrinsics;"
CLASS = re.compile(r"^\s*Class descriptor\s*:\s*'([^']+)'")
REFERENCE = re.compile(r"L[\w/$]+;")


def family(descriptor, name):
    return descriptor == f"{PACKAGE}{name};" or descriptor.startswith(f"{PACKAGE}{name}$")


def inspect(apk, dexdump, disassemble):
    classes = {}
    with ZipFile(apk) as archive, tempfile.TemporaryDirectory() as temp:
        entries = [name for name in archive.namelist() if re.fullmatch(r"classes(?:[0-9]+)?\.dex", name)]
        if not entries:
            raise ValueError(f"{apk.name}: отсутствует DEX")
        for entry in entries:
            path = Path(temp) / entry
            path.write_bytes(archive.read(entry))
            options = ["-a", "-d"] if disassemble else []
            with subprocess.Popen([dexdump, *options, path], stdout=subprocess.PIPE,
                                  encoding="utf-8", errors="replace") as dump:
                dependencies = None
                annotations = set()
                reading_annotations = False
                for line in dump.stdout:
                    if line.startswith("Class #"):
                        # dexdump выводит аннотации перед дескриптором следующего класса.
                        dependencies = None
                        reading_annotations = line.rstrip().endswith("annotations:")
                        continue
                    match = CLASS.match(line)
                    if match:
                        descriptor = match[1]
                        if descriptor in classes:
                            raise ValueError(f"{apk.name}: повтор класса {descriptor}")
                        dependencies = annotations.copy() if disassemble and any(
                            family(descriptor, name) for name in STANDALONE
                        ) else None
                        classes[descriptor] = dependencies
                        annotations.clear()
                    elif reading_annotations:
                        annotations.update(REFERENCE.findall(line))
                    elif dependencies is not None:
                        dependencies.update(REFERENCE.findall(line))
                if dump.wait() != 0:
                    raise ValueError(f"dexdump завершился с ошибкой для {apk.name}/{entry}")
            path.unlink()
    print(f"{apk.name}: DEX {len(entries)}, Intrinsics определён: {INTRINSICS in classes}")
    return classes


def verify(dexdump, debug, android_test, release):
    for apk in (debug, release):
        classes = inspect(apk, dexdump, disassemble=False)
        unexpected = sorted(clazz for clazz in classes if any(family(clazz, name) for name in TEST_ONLY))
        if unexpected:
            raise ValueError(f"{apk.name}: в приложении обнаружены классы AndroidTest: {unexpected}")
        print(f"{apk.name}: классы SAF-фикстуры и тестового приёмника отсутствуют")

    classes = inspect(android_test, dexdump, disassemble=True)
    for name in STANDALONE:
        if f"{PACKAGE}{name};" not in classes:
            raise ValueError(f"{android_test.name}: нет класса {name}")
        own = {descriptor for descriptor in classes if family(descriptor, name)}
        unexpected = {}
        for descriptor in sorted(own):
            references = classes[descriptor]
            if not references:
                raise ValueError(f"Не удалось прочитать ссылки DEX класса {descriptor}")
            foreign = sorted(ref for ref in references if ref not in own and not ref.startswith(PLATFORM))
            if foreign:
                unexpected[descriptor] = foreign
        if unexpected:
            raise ValueError(f"{name}: зависимости вне Android/Java и собственной группы DEX: {unexpected}")
        print(f"{name}: проверено классов {len(own)}, зависимости ограничены Android/Java и своей группой")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dexdump", type=Path)
    parser.add_argument("debug", type=Path)
    parser.add_argument("android_test", type=Path)
    parser.add_argument("release", type=Path)
    args = parser.parse_args()
    try:
        verify(args.dexdump, args.debug, args.android_test, args.release)
    except (OSError, BadZipFile, ValueError, KeyError) as error:
        parser.exit(1, f"Состав DEX не подтверждён: {error}\n")


if __name__ == "__main__":
    main()
