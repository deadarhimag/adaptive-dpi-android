"""Run, collect and validate the evidence from one CI attempt."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import uuid

import stage1b_junit as junit


SCRIPTS = Path(__file__).resolve().parent
CATALOG = SCRIPTS / "stage1b-jvm-expected.json"
PCAP = SCRIPTS.parents[1] / "scripts/kernel-ops-pcap.py"
NDK = "28.2.13676358"


def now():
    return datetime.now(timezone.utc).isoformat()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def read(path):
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("expected JSON object")
    return value


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def checkout_identity():
    commit = git("rev-parse", "HEAD")
    if commit != os.environ["GITHUB_SHA"]:
        raise ValueError("checkout does not match expected SHA")
    identity = {"commit": commit, "tree": git("rev-parse", "HEAD^{tree}")}
    for name, key in (("GITHUB_RUN_ID", "runId"), ("GITHUB_RUN_ATTEMPT", "attempt")):
        value = os.environ[name]
        if not re.fullmatch("[1-9][0-9]*", value):
            raise ValueError(f"invalid {name}")
        identity[key] = value
    return identity


def load_conditions():
    if not hasattr(os, "getloadavg"):
        return None
    return list(os.getloadavg())


def memory_bytes():
    if platform.system() == "Linux":
        for line in Path("/proc/meminfo").read_text(encoding="ascii").splitlines():
            if line.startswith("MemTotal:"):
                return int(line.split()[1]) * 1024
    if platform.system() == "Windows":
        return int(subprocess.check_output([
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory",
        ], text=True).strip())
    return None


def prepare(run):
    identity = checkout_identity()
    identity["experimentId"] = uuid.uuid4().hex
    java = subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr
    jdk = next((line for line in java.splitlines() if re.match(r"^(?:openjdk|java) version ", line)), None)
    if jdk is None:
        raise ValueError("JDK version not found")
    wrapper = Path("gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    gradle = re.search(r"/gradle-([0-9.]+)-bin\.zip", wrapper).group(1)
    run.mkdir(parents=True, exist_ok=False)
    save(run / "start.json", {
        "schemaVersion": 1, "identity": identity, "fixtureVersion": 1, "startedAt": now(),
        "environment": {
            "os": platform.system(),
            "osVersion": platform.freedesktop_os_release()["PRETTY_NAME"] if platform.system() == "Linux"
            else platform.version(), "kernel": platform.release(),
            "abi": platform.machine(), "runnerImage": os.environ.get("ImageOS"),
            "runnerImageVersion": os.environ.get("ImageVersion"), "jdk": jdk, "gradleWrapper": gradle,
            "ndkExpected": NDK, "avdApi": None,
            "cpus": len(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else os.cpu_count(),
            "memoryBytes": memory_bytes(), "loadAverageAtStart": load_conditions(),
        },
    })


def run_identity(run):
    info = read(run / "start.json")
    context = checkout_identity()
    identity = info["identity"]
    if info["schemaVersion"] != 1 or any(identity.get(key) != value for key, value in context.items()):
        raise ValueError("evidence directory belongs to another revision or attempt")
    if not re.fullmatch("[0-9a-f]{32}", identity["experimentId"]):
        raise ValueError("invalid experiment ID")
    return identity


def run_jvm(run, identity, offline):
    events = run / "task-events"
    events.mkdir()
    for result in junit.RESULTS.values():
        if result.exists():
            # Same filesystem, outside the collection path, and no recursive deletion.
            result.rename(result.with_name(f"{result.name}.previous-{identity['experimentId']}"))
    save(run / "jvm-start.json", {"identity": identity, "startedAt": now()})
    environment = {
        **os.environ, "STAGE1B_TASK_EVENTS": str(events), "STAGE1B_IDENTITY": json.dumps(identity),
    }
    gradle = [
        str(Path("gradlew.bat").resolve()) if os.name == "nt" else "./gradlew",
        "--no-daemon", "--no-configuration-cache", "--no-build-cache", "--rerun-tasks", "--continue",
        "--console=plain", "--init-script", str(SCRIPTS / "stage1b-tasks.init.gradle"),
    ]
    if offline:
        gradle.extend(["--offline", "--max-workers=2", "-Pkotlin.compiler.execution.strategy=in-process"])
    gradle.extend(["testDebugUnitTest", ":engine:test"])
    log = run / "jvm-gradle.log"
    secrets = [os.environ[name].encode() for name in (
        "ANDROID_CI_DEBUG_STORE_PASSWORD", "ANDROID_CI_DEBUG_KEY_PASSWORD", "GITHUB_TOKEN",
    ) if os.environ.get(name)]
    with log.open("xb") as stream, subprocess.Popen(
        gradle, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=environment
    ) as process:
        for line in process.stdout:
            for value in secrets:
                line = line.replace(value, b"[REDACTED]")
            stream.write(line)
            stream.flush()
            sys.stdout.buffer.write(line)
            sys.stdout.buffer.flush()
        result = process.wait()
    save(run / "jvm-process.json", {"identity": identity, "exitCode": result, "finishedAt": now()})
    return result


def optional_record(path, errors):
    if path.exists():
        try:
            return read(path)
        except (ValueError, OSError):
            errors.append(f"unreadable {path.name}")
    return None


def collect_jvm(run, upload, identity):
    errors = []
    started = optional_record(run / "jvm-start.json", errors)
    process = optional_record(run / "jvm-process.json", errors)
    fresh = started is not None and started.get("identity") == identity
    catalog = read(CATALOG)
    summaries = {}
    versions = {}
    exit_code = process.get("exitCode") if process and process.get("identity") == identity else None
    for module, task in junit.TASKS.items():
        destination = upload / "jvm" / module
        destination.mkdir(parents=True)
        events = {}
        problems = []
        if fresh:
            for source in sorted(junit.RESULTS[module].glob("TEST-*.xml")):
                if not source.is_file() or source.is_symlink() or source.stat().st_size > junit.MAX_XML_BYTES:
                    problems.append(f"unsafe or oversized {source.name}")
                    continue
                shutil.copyfile(source, destination / source.name)
            for phase in ("start", "finish"):
                source = run / "task-events" / f"{module}-{phase}.json"
                value = optional_record(source, problems)
                if value is not None:
                    events[phase] = value
                    shutil.copyfile(source, destination / f"{phase}.json")
        if started is not None and not fresh:
            problems.append("invalid JVM start provenance")
        collection = {
            "identity": identity, "task": task, "files": junit.inventory(destination),
            "collectionError": "; ".join(problems) or None,
        }
        save(destination / "collection.json", collection)
        expected = junit.expected_cases(catalog, module)
        summaries[module] = junit.validate(module, expected, destination, identity, collection, events)
        if "finish" in events:
            versions[module] = events["finish"].get("gradle")
    if fresh and (run / "jvm-gradle.log").exists():
        shutil.copyfile(run / "jvm-gradle.log", upload / "jvm" / "gradle.log")
    for name, record in (("invocation.json", started), ("process.json", process)):
        if record is not None and record.get("identity") == identity:
            save(upload / "jvm" / name, record)
    shutil.copyfile(CATALOG, upload / "jvm" / "expected.json")
    summary = {"identity": identity, "gradleExitCode": exit_code, "gradleVersions": versions,
               "modules": summaries, "metadataErrors": errors}
    summary["success"] = exit_code == 0 and not errors and all(result["success"] for result in summaries.values())
    save(upload / "jvm" / "validation.json", summary)
    return summary


def capture_pcap(run, identity):
    return subprocess.call([sys.executable, str(PCAP), "--output", str(run / "pcap"),
                            "--identity", json.dumps(identity)])


def collect_pcap(run, upload, identity):
    target = upload / "pcap"
    target.mkdir()
    source = run / "pcap"
    if not source.exists():
        status = {"identity": identity, "outcome": "not_started"}
    else:
        errors = []
        status = optional_record(source / "status.json", errors)
        if not status or status.get("identity") != identity:
            status = {"identity": identity, "outcome": "interrupted", "reason": "missing or unbound capture status"}
        elif status.get("outcome") == "passed":
            result = subprocess.call([sys.executable, str(PCAP), "--verify", str(source), "--identity", json.dumps(identity)])
            if result == 0:
                for name in ("synthetic.pcap", "report.txt", "capture.json"):
                    shutil.copyfile(source / name, target / name)
            else:
                status = {"identity": identity, "outcome": "failed", "reason": "independent publication validation failed"}
    save(target / "status.json", status)
    return status


def finalize(run, identity, jvm_step, pcap_step, avd_api):
    upload = run / "publish"
    upload.mkdir()
    jvm = collect_jvm(run, upload, identity)
    pcap = collect_pcap(run, upload, identity)
    info = read(run / "start.json")
    ndk = Path(os.environ.get("ANDROID_HOME", ".")) / "ndk" / NDK / "source.properties"
    info["environment"]["ndk"] = next((line.split("=", 1)[1].strip() for line in
        ndk.read_text(encoding="utf-8").splitlines() if line.startswith("Pkg.Revision")), None) if ndk.is_file() else None
    info["environment"]["loadAverageAtFinish"] = load_conditions()
    info["environment"]["gradle"] = jvm["gradleVersions"]
    info["environment"]["avdApiConfigured"] = avd_api
    if pcap["outcome"] == "passed":
        capture = read(upload / "pcap" / "capture.json")
        info["capture"] = {key: capture[key] for key in ("fixtureVersion", "network", "seed", "limits")}
    info["endedAt"] = now()
    info["catalogSha256"] = hashlib.sha256(CATALOG.read_bytes()).hexdigest()
    info["outcomes"] = {"jvm": jvm_step or "not_started", "pcap": pcap_step or "not_started",
                        "jvmEvidencePassed": jvm["success"], "pcapEvidence": pcap["outcome"]}
    info["artifacts"] = [
        {"path": path.relative_to(upload).as_posix(), "size": path.stat().st_size,
         "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        for path in sorted(upload.rglob("*")) if path.is_file()
    ]
    save(upload / "manifest.json", info)
    for module, outcome in jvm["modules"].items():
        print(f"{module}: {outcome['execution']}, {outcome['results']}, {outcome['counts']}")
    print(f"PCAP: {pcap['outcome']}")
    return 0 if (jvm_step == pcap_step == "success" and jvm["success"]
                 and pcap["outcome"] == "passed") else 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("prepare", "jvm", "pcap", "finalize"))
    parser.add_argument("run", type=Path)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--jvm-step", default="not_started")
    parser.add_argument("--pcap-step", default="not_started")
    parser.add_argument("--avd-api", type=int)
    args = parser.parse_args()
    run = args.run.resolve()
    if args.command == "prepare":
        prepare(run)
        return 0
    identity = run_identity(run)
    if args.command == "jvm":
        return run_jvm(run, identity, args.offline)
    if args.command == "pcap":
        return capture_pcap(run, identity)
    return finalize(run, identity, args.jvm_step, args.pcap_step, args.avd_api)


if __name__ == "__main__":
    sys.exit(main())
