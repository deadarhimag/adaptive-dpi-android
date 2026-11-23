import argparse
import json
import re
from pathlib import Path
from xml.etree import ElementTree as ET


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


STATUS_FIELDS = {"id", "numtests", "current", "class", "test"}
OUTCOMES = {0: None, -1: "error", -2: "failure", -3: "skipped", -4: "skipped"}


def read_protocol(text):
    completed = {}
    active = None
    fields = {}
    summary = []
    in_result = False
    ended = False
    for line in text.splitlines():
        if ended and line.strip():
            raise ValueError("После итогового статуса runner остался вывод")
        if line.startswith(("INSTRUMENTATION_FAILED:", "INSTRUMENTATION_ABORTED:")):
            raise ValueError("Android завершил запуск instrumentation с ошибкой")
        if line.startswith("INSTRUMENTATION_CODE:"):
            if not in_result or fields or active is not None or line != "INSTRUMENTATION_CODE: -1":
                raise ValueError("Некорректный итоговый статус runner")
            ended = True
            continue
        if line.startswith("INSTRUMENTATION_RESULT: stream="):
            if in_result or fields or active is not None:
                raise ValueError("Runner начал итоговый вывод до завершения статусов тестов")
            in_result = True
            summary.append(line.removeprefix("INSTRUMENTATION_RESULT: stream=").strip())
            continue
        if line.startswith("INSTRUMENTATION_RESULT:"):
            raise ValueError("Неожиданное поле результата runner")
        if line.startswith("INSTRUMENTATION_STATUS: "):
            if in_result:
                raise ValueError("Статус теста после итогового вывода")
            key, _, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if key in STATUS_FIELDS:
                if key in fields:
                    raise ValueError(f"Повтор поля {key} в статусе runner")
                fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            if in_result or fields.keys() != STATUS_FIELDS or fields["id"] != "AndroidJUnitRunner":
                raise ValueError("Неполный статус или неверный runner")
            if fields["numtests"] != str(len(TESTS)) or fields["current"] != str(len(completed) + 1):
                raise ValueError("Runner сообщил неверный размер набора или номер теста")
            method = fields["test"]
            if fields["class"] != SUITE or method not in TESTS or method in completed:
                raise ValueError(f"Неожиданный или повторный тест: {fields['class']}#{method}")
            status = int(line.removeprefix("INSTRUMENTATION_STATUS_CODE: "))
            if status == 1 and active is None:
                active = method
            elif status in OUTCOMES and active == method:
                completed[method] = status
                active = None
            else:
                raise ValueError(f"{method}: неожиданный статус {status}")
            fields.clear()
        elif in_result and not ended:
            summary.append(line.strip())
    if not ended or completed.keys() != TESTS:
        raise ValueError(f"Неполный прогон. Нет результата для {sorted(TESTS - completed.keys())}")
    successes = [line for line in summary if line.startswith("OK (")]
    if all(status == 0 for status in completed.values()) and (
        successes != [f"OK ({len(TESTS)} tests)"] or any("FAILURES!!!" in line for line in summary)
    ):
        raise ValueError("Итог runner противоречит статусам тестов")
    elapsed = next((match[1] for line in summary if (match := re.fullmatch(r"Time: (\d+(?:\.\d+)?)", line))), None)
    return completed, elapsed


def write_junit(path, completed, elapsed):
    suite = ET.Element("testsuite", name=SUITE, tests=str(len(completed)))
    for outcome, attribute in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
        suite.set(attribute, str(sum(OUTCOMES[status] == outcome for status in completed.values())))
    if elapsed is not None:
        suite.set("time", elapsed)
    for method, status in completed.items():
        case = ET.SubElement(suite, "testcase", classname=SUITE, name=method)
        outcome = OUTCOMES[status]
        if outcome is not None:
            ET.SubElement(case, outcome, message=f"AndroidJUnitRunner status {status}")
    ET.indent(suite, space="  ")
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def verify_junit(path):
    suite = ET.parse(path).getroot()
    cases = suite.findall("testcase")
    expected = {(SUITE, name) for name in TESTS}
    if suite.tag != "testsuite" or suite.get("name") != SUITE or suite.get("tests") != str(len(TESTS)):
        raise ValueError("В JUnit XML неверный набор тестов")
    if len(cases) != len(TESTS) or {(case.get("classname"), case.get("name")) for case in cases} != expected:
        raise ValueError("В JUnit XML отсутствует тест или есть дубликат")
    if any(suite.get(key) != "0" for key in ("failures", "errors", "skipped")) or any(len(case) for case in cases):
        raise ValueError("JUnit XML содержит ошибку, отказ или пропуск")
    return {"executed": len(cases), "failed": 0, "errors": 0, "skipped": 0, "tests": sorted(TESTS)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("instrumentation", type=Path)
    parser.add_argument("junit", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        args.junit.unlink(missing_ok=True)
        args.output.unlink(missing_ok=True)
        completed, elapsed = read_protocol(args.instrumentation.read_text(encoding="utf-8"))
        write_junit(args.junit, completed, elapsed)
        result = verify_junit(args.junit)
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, ET.ParseError) as error:
        parser.exit(1, f"Расширенные тесты не подтверждены: {error}\n")
    print(f"Расширенные Android-тесты: {result['executed']}, ошибок 0, пропусков 0.")


if __name__ == "__main__":
    main()
