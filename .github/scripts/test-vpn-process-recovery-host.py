"""Проверяет управляющий bash-сценарий с управляемым ADB и протоколы валидатора."""

import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from xml.etree import ElementTree as ET


DIRECTORY = Path(__file__).resolve().parent
ROOT = DIRECTORY.parents[1]
def validator(name, file):
    spec = importlib.util.spec_from_file_location(name, DIRECTORY / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


results = validator("results", "verify-extended-transport-results.py")
baseline = validator("baseline", "verify-android-test-results.py")
SESSION = "43089bcb-d98d-4c4a-a39b-c3d86c36c4c2"
OLD_RUN = "a7056073-fb69-4875-8bf6-7436dd6d1d9d"
SUITE = "org.adaptivedpi.android.DiagnosticsInstrumentedTest"
RECOVERY_TEST = "anAbortedVpnProcessIsReportedAfterTheNextLaunch"
PID = "8812"


def ready(run, pid=PID, session=SESSION, envelope=None):
    return f"I/VpnRecoveryTest({envelope or pid:>5}): READY {run} {pid} {session}"


def protocol(suite, tests):
    lines = []
    for index, test in enumerate(sorted(tests), 1):
        for status in (1, 0):
            fields = {"id": "AndroidJUnitRunner", "numtests": len(tests), "current": index, "class": suite, "test": test}
            lines.extend(f"INSTRUMENTATION_STATUS: {key}={value}" for key, value in fields.items())
            lines.append(f"INSTRUMENTATION_STATUS_CODE: {status}")
    unit = "test" if len(tests) == 1 else "tests"
    lines.extend(["INSTRUMENTATION_RESULT: stream=", "Time: 0.1", f"OK ({len(tests)} {unit})", "INSTRUMENTATION_CODE: -1"])
    return "\n".join(lines) + "\n"


def fake_adb(arguments):
    serial_flag, serial, *args = arguments
    assert (serial_flag, serial) == ("-s", "emulator-5554")
    work = Path(os.environ["RECOVERY_TEST_STATE"])
    behavior = os.environ["RECOVERY_TEST_BEHAVIOR"]
    current = work / "current"
    process = work / "process"
    signal = work / "signal"
    log = work / "adb.log"
    previous = work / "previous-ready"
    with log.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(args) + "\n")
    if args[:2] == ["shell", "getprop"]:
        print("0" if behavior == "not-avd" else "1")
    elif args[:4] == ["shell", "ip", "-o", "address"]:
        if process.exists() and process.read_text() == "setup":
            print("tun0 inet 192.0.2.1/32")
    elif args[0] == "logcat":
        print(ready(OLD_RUN, pid="1010"))
        if previous.exists():
            print(previous.read_text(), end="")
        if current.exists() and behavior not in {"no-ready", "early-exit"}:
            record = ready(current.read_text(), session="invalid" if behavior == "invalid-session" else SESSION)
            print(record)
            if behavior == "duplicate":
                print(record)
    elif args[:3] == ["shell", "am", "instrument"]:
        method = args[args.index("class") + 1]
        token = args[args.index("recoveryRunId") + 1] if "recoveryRunId" in args else ""
        if method.endswith("#armsVpnForExternalProcessDeath"):
            assert str(uuid.UUID(token)) == token
            signal.unlink(missing_ok=True)
            process.write_text("setup")
            current.write_text(token)
            if behavior == "early-exit":
                return 4
            if behavior == "terminal-early":
                print("INSTRUMENTATION_CODE: 0", flush=True)
            while process.exists() and not signal.exists():
                time.sleep(0.02)
            return 1  # Прерванная инструментализация ожидаема после SIGKILL.
        assert method == f"{SUITE}#{RECOVERY_TEST}"
        assert token == current.read_text()
        assert args[args.index("recoveredVpnSession") + 1] == SESSION
        assert args[args.index("oldVpnPid") + 1] == PID
        assert signal.exists()
        process.write_text("recovery")
        if behavior == "after-kill":
            print("INSTRUMENTATION_ABORTED: Process crashed.")
        elif behavior == "recovery-timeout":
            return 124
        else:
            print(protocol(SUITE, {RECOVERY_TEST}), end="")
    elif args[:3] == ["shell", "am", "force-stop"]:
        if behavior == "cleanup-error":
            return 4
        if current.exists():
            with previous.open("a", encoding="utf-8") as stream:
                stream.write(ready(current.read_text()) + "\n")
        process.unlink(missing_ok=True)
        current.unlink(missing_ok=True)
    elif args[0] == "shell" and args[1].startswith("pidof "):
        if process.exists():
            print("9876" if behavior == "wrong-pid" else PID)
    elif args[:2] == ["shell", "run-as"] and args[3] == "cat":
        if behavior == "marker-io-error":
            return 4
        print("2\t" + (OLD_RUN if behavior == "wrong-marker" else SESSION) + "\t...")
    elif args[:2] == ["shell", "run-as"] and args[3:] == ["kill", "-KILL", PID]:
        signal.touch()
        process.unlink(missing_ok=True)
    else:
        raise AssertionError(args)
    return 0


class RecoveryHostTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.env = {**os.environ, "ANDROID_HOME": self.directory.as_posix(),
                    "RECOVERY_TEST_STATE": self.directory.as_posix(),
                    "RECOVERY_TEST_HARNESS": Path(__file__).resolve().as_posix(),
                    "RECOVERY_TEST_PYTHON": Path(sys.executable).as_posix(),
                    "PYTHONDONTWRITEBYTECODE": "1"}
        bin_dir = self.directory / "bin"
        adb_dir = self.directory / "platform-tools"
        bin_dir.mkdir()
        adb_dir.mkdir()
        self.env["PATH"] = str(bin_dir) + os.pathsep + os.environ["PATH"]
        self.write_executable(bin_dir / "python3", 'exec "$RECOVERY_TEST_PYTHON" "$@"')
        self.write_executable(bin_dir / "sleep", 'exec "$RECOVERY_TEST_PYTHON" -c "import time; time.sleep(0.03)"')
        self.write_executable(adb_dir / "adb", 'exec python3 "$RECOVERY_TEST_HARNESS" --adb "$@"')

    def write_executable(self, file, body):
        file.write_text("#!/usr/bin/env bash\n" + body + "\n", encoding="utf-8", newline="\n")
        file.chmod(0o755)

    def run_experiment(self, behavior):
        bash = os.environ.get("RECOVERY_TEST_BASH") or shutil.which("bash") or "bash"
        return subprocess.run(
            [bash, ".github/scripts/test-vpn-process-recovery.sh", "emulator-5554", (self.directory / "logs").as_posix()],
            env={**self.env, "RECOVERY_TEST_BEHAVIOR": behavior}, cwd=ROOT,
            capture_output=True, text=True, encoding="utf-8", timeout=50, check=False,
        )

    def assert_failure(self, behavior, message, killed=False, clean=True):
        process = self.run_experiment(behavior)
        self.assertNotEqual(0, process.returncode, process.stdout + process.stderr)
        self.assertIn(message, process.stderr)
        self.assertFalse((self.directory / "logs" / "result.json").exists())
        self.assertEqual(killed, (self.directory / "signal").exists())
        if clean:
            self.assertFalse((self.directory / "process").exists())
            self.assertTrue((self.directory / "logs" / "final-addresses.txt").is_file())

    def test_first_and_repeated_success_ignore_stale_ready_and_leave_no_route(self):
        tokens = set()
        for _ in range(2):
            process = self.run_experiment("success")
            self.assertEqual(0, process.returncode, process.stdout + process.stderr)
            report = json.loads((self.directory / "logs" / "result.json").read_text())
            self.assertTrue(report["passed"])
            self.assertEqual(SESSION, report["sessionId"])
            self.assertEqual(int(PID), report["pid"])
            tokens.add(report["runId"])
            self.assertFalse((self.directory / "process").exists())
            self.assertEqual("", (self.directory / "logs" / "final-addresses.txt").read_text().strip())
        self.assertEqual(2, len(tokens))

    def test_stale_ready_alone_times_out_without_killing(self):
        self.assert_failure("no-ready", "Истёк срок")

    def test_duplicate_matching_ready_is_rejected(self):
        self.assert_failure("duplicate", "Повтор READY")

    def test_malformed_session_is_rejected(self):
        self.assert_failure("invalid-session", "Готовность не подтверждена")

    def test_pid_mismatch_is_rejected_and_cleanup_runs(self):
        self.assert_failure("wrong-pid", "PID из READY")

    def test_active_marker_must_match_the_session(self):
        self.assert_failure("wrong-marker", "не соответствует сеансу")

    def test_premature_setup_exit_is_rejected(self):
        self.assert_failure("early-exit", "завершился до SIGKILL")

    def test_terminal_runner_is_rejected_even_if_adb_is_still_running(self):
        self.assert_failure("terminal-early", "завершился до SIGKILL")

    def test_adb_error_after_vpn_start_still_cleans_up(self):
        self.assert_failure("marker-io-error", "Не удалось прочитать")

    def test_runner_failure_after_sigkill_still_cleans_up(self):
        self.assert_failure("after-kill", "Android-прогон не подтверждён", killed=True)

    def test_timeout_after_sigkill_still_cleans_up(self):
        self.assert_failure("recovery-timeout", "", killed=True)

    def test_cleanup_failure_prevents_publishing_success(self):
        self.assert_failure("cleanup-error", "Не подтверждена очистка", killed=True, clean=False)

    def test_non_avd_is_rejected_before_start(self):
        self.assert_failure("not-avd", "", clean=False)
        self.assertFalse((self.directory / "process").exists())
        commands = (self.directory / "adb.log").read_text().splitlines()
        self.assertEqual([["shell", "getprop", "ro.kernel.qemu"]], [json.loads(line) for line in commands])


class ResultValidatorTest(unittest.TestCase):
    def test_invalid_experiment_id_and_log_pid_are_rejected(self):
        with self.assertRaises(ValueError):
            results.read_ready(ready(OLD_RUN), "invalid-id")
        with self.assertRaises(ValueError):
            results.read_ready(ready(OLD_RUN, envelope="123"), OLD_RUN)

    def test_absent_current_record_is_distinct_from_a_match(self):
        run = str(uuid.uuid4())
        self.assertIsNone(results.read_ready(ready(OLD_RUN), run))
        self.assertEqual((PID, SESSION), results.read_ready(ready(OLD_RUN) + "\n" + ready(run), run))

    def test_preflight_requires_named_successful_tests_and_complete_runner(self):
        stream = protocol(results.SAF_SUITE, results.SAF_TESTS)
        statuses, _ = results.read_protocol(stream, results.SAF_SUITE, results.SAF_TESTS)
        self.assertEqual({test: 0 for test in results.SAF_TESTS}, statuses)
        for bad in (stream.removesuffix("INSTRUMENTATION_CODE: -1\n"),
                    stream.replace("OK (4 tests)", "OK (1 test)"),
                    "INSTRUMENTATION_ABORTED: Process crashed.\n",
                    stream.replace("requestedOpenAndWriteFailuresAreObservableWithoutFixtureError", "wrongTest")):
            with self.assertRaises(ValueError):
                results.read_protocol(bad, results.SAF_SUITE, results.SAF_TESTS)

    def test_baseline_requires_new_saf_regressions_and_system_consent(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            consent = path / "consent.log"
            consent.write_text(protocol(baseline.CONSENT_CLASS, {baseline.CONSENT_METHOD}), encoding="utf-8")
            suites = ET.Element("testsuites")
            for klass, methods in {**baseline.CLASSES, baseline.DIAGNOSTICS_CLASS: baseline.DIAGNOSTICS_METHODS}.items():
                suite = ET.SubElement(suites, "testsuite")
                for method in methods:
                    ET.SubElement(suite, "testcase", classname=klass, name=method)
            report = path / "TEST-main.xml"
            ET.ElementTree(suites).write(report, encoding="utf-8")
            self.assertEqual(37, baseline.verify(path, consent)["executed"])
            case = suite.find("./testcase[@name='lateSafResultCannotWriteOrCancelANewAttempt']")
            suite.remove(case)
            ET.ElementTree(suites).write(report, encoding="utf-8")
            with self.assertRaises(ValueError):
                baseline.verify(path, consent)
            consent.write_text("INSTRUMENTATION_ABORTED: Process crashed.\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "системного согласия"):
                baseline.verify(path, consent)


if __name__ == "__main__":
    if sys.argv[1:2] == ["--adb"]:
        sys.exit(fake_adb(sys.argv[2:]))
    unittest.main()
