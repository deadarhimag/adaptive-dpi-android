"""Validate Gradle's original JUnit XML against an explicit scenario inventory."""

from collections import Counter
import hashlib
from pathlib import Path
import xml.etree.ElementTree as ET


TASKS = {"engine": ":engine:test", "vpn": ":vpn:testDebugUnitTest"}
RESULTS = {
    "engine": Path("engine/build/test-results/test"),
    "vpn": Path("vpn/build/test-results/testDebugUnitTest"),
}
STATUSES = ("passed", "failures", "errors", "skipped")
MAX_XML_BYTES = 32 * 1024 * 1024


def inventory(directory):
    return {
        path.name: {"size": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        for path in sorted(directory.glob("TEST-*.xml")) if path.is_file() and not path.is_symlink()
    }


def parse_suite(path):
    if path.stat().st_size > MAX_XML_BYTES:
        raise ValueError("oversized XML")
    data = path.read_text(encoding="utf-8-sig")
    if "<!DOCTYPE" in data or "<!ENTITY" in data:
        raise ValueError("unexpected DTD")
    suite = ET.fromstring(data)
    if suite.tag != "testsuite" or not suite.get("name"):
        raise ValueError("invalid testsuite")
    observed = Counter()
    cases = []
    for case in suite.findall("./testcase"):
        name = (case.get("classname"), case.get("name"))
        if not all(name) or name[0] != suite.get("name"):
            raise ValueError("invalid testcase identity")
        markers = [child.tag for child in case if child.tag in ("failure", "error", "skipped")]
        if len(markers) > 1:
            raise ValueError("conflicting testcase outcomes")
        status = {"failure": "failures", "error": "errors", "skipped": "skipped"}.get(
            next(iter(markers), None), "passed"
        )
        observed[status] += 1
        cases.append((name, status))
    for counter in ("tests", "failures", "errors", "skipped"):
        value = suite.get(counter, "")
        expected = len(cases) if counter == "tests" else observed[counter]
        if not value.isascii() or not value.isdecimal() or int(value) != expected:
            raise ValueError(f"testsuite {counter} does not match testcase")
    if not cases:
        raise ValueError("empty testsuite")
    return suite.get("name"), cases


def expected_cases(catalog, module):
    if catalog.get("schemaVersion") != 1 or set(catalog["modules"]) != set(TASKS):
        raise ValueError("invalid JVM catalogue")
    classes = catalog["modules"][module]
    cases = [(classname, name) for classname, methods in classes.items() for name in methods]
    if not cases or len(set(cases)) != len(cases) or any(not all(case) for case in cases):
        raise ValueError("empty or duplicate JVM catalogue identities")
    return set(cases)


def execution(module, expected_identity, events):
    start, finish = (events.get(name) for name in ("start", "finish"))
    for event in (start, finish):
        if event is not None and (event.get("identity") != expected_identity or event.get("task") != TASKS[module]):
            return "interrupted", "event belongs to a different run or task"
    if start is None:
        return ("not_started", None) if finish is None else ("interrupted", "missing start event")
    if finish is None:
        return "interrupted", "missing finish event"
    if start.get("gradle") != finish.get("gradle") or not isinstance(start.get("gradle"), str):
        return "interrupted", "inconsistent Gradle version"
    outcome = finish.get("outcome")
    if outcome in {"passed", "failed", "skipped"}:
        return outcome, finish.get("skipReason")
    return "interrupted", "invalid Gradle task outcome"


def validate(module, expected, directory, expected_identity, collection, events):
    metadata_errors = []
    task_outcome, reason = execution(module, expected_identity, events)
    if collection.get("identity") != expected_identity or collection.get("task") != TASKS[module]:
        metadata_errors.append("XML collection belongs to a different run or task")
    files = inventory(directory)
    if collection.get("files") != files:
        metadata_errors.append("XML inventory does not match collection")
    if collection.get("collectionError"):
        metadata_errors.append(collection["collectionError"])
    malformed, by_case, suites = {}, Counter(), Counter()
    outcomes = Counter()
    for name in files:
        try:
            suite, cases = parse_suite(directory / name)
        except (ET.ParseError, ValueError, OSError) as error:
            malformed[name] = str(error)
            continue
        suites[suite] += 1
        for key, status in cases:
            by_case[key] += 1
            outcomes[status] += 1
    actual = set(by_case)
    missing = sorted(expected - actual)
    unexpected = sorted(actual - expected)
    duplicates = sorted(key for key, count in by_case.items() if count > 1)
    repeated_suites = sorted(suite for suite, count in suites.items() if count > 1)
    results = (
        "missing" if not files else "incomplete"
        if any((missing, unexpected, duplicates, repeated_suites, malformed, metadata_errors)) else "complete"
    )
    counts = {status: outcomes[status] for status in STATUSES}
    counts.update(missing=len(missing), duplicate=sum(count - 1 for count in by_case.values()),
                  unexpected=len(unexpected))
    return {
        "task": TASKS[module], "execution": task_outcome, "executionDetail": reason, "results": results,
        "expected": len(expected), "counts": counts, "missing": missing, "duplicate": duplicates,
        "unexpected": unexpected, "duplicateSuites": repeated_suites, "malformed": malformed,
        "metadataErrors": metadata_errors,
        "success": task_outcome == "passed" and results == "complete" and counts["passed"] == len(expected),
    }
