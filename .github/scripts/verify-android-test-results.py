import argparse
import json
import re
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree


PACKAGE = "org.adaptivedpi.android."
CONSENT_CLASS = PACKAGE + "VpnConsentInstrumentedTest"
CONSENT_METHOD = "grantsVpnThroughAndroidConfirmationDialog"
CLASSES = {
    PACKAGE + "BuildAndDiagnosticsUiTest": 7,
    PACKAGE + "VpnLifecycleInstrumentedTest": 6,
    PACKAGE + "TunTransportInstrumentedTest": 1,
    PACKAGE + "ApplicationTransportInstrumentedTest": 1,
}
DIAGNOSTICS_CLASS = PACKAGE + "DiagnosticsInstrumentedTest"
DIAGNOSTICS_METHODS = (
    "sharesOnlyAReadableDiagnosticCacheFileAndUsesTheDocumentPickerForSaving",
    "sharesTheExactCombinedReportSnapshotAsUtf8",
    "savesAndSharesIdenticalTransportTextAndKeepsRecentAttachmentsReadable",
    "parallelLargeTxtAttachmentsKeepIndependentUrisNamesAndBytes",
    "criticalErrorPostsOneNotificationAndOpensItsDiagnosticEvent",
)


def verify(results, consent_log):
    consent = consent_log.read_text(encoding="utf-8")
    for line in (f"INSTRUMENTATION_STATUS: class={CONSENT_CLASS}",
                 f"INSTRUMENTATION_STATUS: test={CONSENT_METHOD}",
                 "INSTRUMENTATION_CODE: -1"):
        if line not in consent.splitlines():
            raise ValueError(f"В протоколе системного согласия отсутствует {line}")
    if not re.search(r"^OK \(1 test\)$", consent, re.MULTILINE):
        raise ValueError("Системное согласие на VPN не подтверждено успешным тестом")

    reports = sorted(results.rglob("TEST-*.xml"))
    if not reports:
        raise ValueError("Gradle не создал ни одного XML-отчёта Android-тестов")

    cases = set()
    for path in reports:
        root = ElementTree.parse(path).getroot()
        for suite in root.iter("testsuite"):
            if any(int(suite.get(key, "0")) for key in ("failures", "errors", "skipped")):
                raise ValueError(f"Есть ошибки или пропуски в {path.name}")
        for case in root.iter("testcase"):
            key = (case.attrib["classname"], case.attrib["name"])
            if key in cases or any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                raise ValueError(f"Тест повторён, пропущен или завершился ошибкой: {key}")
            cases.add(key)

    counts = Counter(klass for klass, _ in cases)
    if counts != Counter({**CLASSES, DIAGNOSTICS_CLASS: len(DIAGNOSTICS_METHODS)}):
        raise ValueError(f"Фактический набор Android-тестов не совпадает с ожидаемым: {dict(counts)}")
    for method in DIAGNOSTICS_METHODS:
        if (DIAGNOSTICS_CLASS, method) not in cases:
            raise ValueError(f"Не выполнен обязательный тест диагностики {method}")

    return {"executed": len(cases) + 1, "failed": 0, "skipped": 0, "mainSuite": len(cases),
            "systemVpnConsent": 1, "classes": dict(sorted({**counts, CONSENT_CLASS: 1}.items()))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--filter", action="store_true")
    parser.add_argument("paths", nargs="*", type=Path)
    args = parser.parse_args()
    if args.filter:
        print(",".join((*CLASSES, *(f"{DIAGNOSTICS_CLASS}#{method}" for method in DIAGNOSTICS_METHODS))))
        return
    if len(args.paths) != 3:
        parser.error("Укажите каталог отчётов, протокол согласия и путь итогового JSON")
    results, consent_log, summary_file = args.paths
    try:
        summary = verify(results, consent_log)
        summary_file.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, KeyError, ElementTree.ParseError) as error:
        parser.exit(1, f"Android-тесты не подтверждены: {error}\n")
    print(f"Android-тесты: выполнено {summary['executed']}, ошибок 0, пропусков 0.")


if __name__ == "__main__":
    main()
