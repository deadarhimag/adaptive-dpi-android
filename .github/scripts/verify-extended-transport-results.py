import argparse
import json
import re
from pathlib import Path


SUITE = "org.adaptivedpi.android.ExtendedTunTransportInstrumentedTest"
TESTS = {
    "tcpDownloadSurvivesSeventySecondsThroughTun",
    "tcpUploadSurvivesSeventySecondsThroughTun",
    "fullyIdleTcpClosesThroughTunAndReleasesTheConnection",
    "stopClosesTcpHeldInPrepareBeforeServerAccepts",
    "stopCancelsAnAlreadyBlockingSystemConnect",
    "delayedNativePollWarnsAndStopKeepsResourcesUntilMutexReturns",
    "repeatedSessionsCarryTcpUdpAndDnsWithoutAccumulatingResources",
}


def verify(text):
    started = set()
    completed = set()
    active = None
    fields = {}
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            field, _, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            fields[field] = value
        if not line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            continue
        status = int(line.split(": ", 1)[1])
        method = fields.get("test")
        if fields.get("class") != SUITE or method not in TESTS:
            raise ValueError("В протоколе обнаружен неизвестный тест")
        if status == 1 and active is None and method not in started:
            started.add(method)
            active = method
        elif status == 0 and active == method:
            completed.add(method)
            active = None
        else:
            raise ValueError(f"{method}: неожиданный статус {status}")
        fields.clear()
    codes = re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)$", text, re.MULTILINE)
    if codes != ["-1"] or not re.search(rf"^OK \({len(TESTS)} tests\)$", text, re.MULTILINE):
        raise ValueError("Инструментальный runner не подтвердил полный успешный прогон")
    if active is not None or started != TESTS or completed != TESTS:
        raise ValueError(f"Нет подтверждения для тестов {sorted(TESTS - completed)}")
    return {"executed": len(completed), "failed": 0, "skipped": 0, "tests": sorted(completed)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("instrumentation", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        result = verify(args.instrumentation.read_text(encoding="utf-8"))
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError) as error:
        parser.exit(1, f"Расширенные тесты не подтверждены: {error}\n")
    print(f"Расширенные Android-тесты: {result['executed']}, ошибок 0, пропусков 0.")


if __name__ == "__main__":
    main()
