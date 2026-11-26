"""Run the kernel-ops fixture in a fresh network namespace and verify its raw PCAP."""
import argparse
import json
import os
import platform
import re
import select
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

from kernel_ops_capture import (
    HEADER, MAX_PCAP, RECORD, RST, SNAPLEN, SYN, URG, digest, ports, read_pcap, seed_for, validate_flows,
)

FIXTURE_VERSION = 2
PACKET_OUTGOING = 4
ROOT = Path(__file__).resolve().parent
CAPTURE_SECONDS = 60
GENERATOR = "kernel-ops-host.c"
CAPTURE_CLOCK = "userspace UNIX microseconds"
LIMITS = {"seconds": CAPTURE_SECONDS, "bytes": MAX_PCAP}
OFFLOAD_KEYS = ("rx-checksumming", "tx-checksumming", "generic-segmentation-offload",
                "generic-receive-offload", "tcp-segmentation-offload")
CAPTURE_FILES = ("synthetic.pcap", "report.txt", "capture.json")


def flag(segment, mask):
    return bool(segment["flags"] & mask)


def analyse(lines, packets):
    rows, failures = [], []

    def from_client(port):
        return [p for p in packets if p["src"] == port]

    def to_client(port):
        return [p for p in packets if p["dst"] == port]

    def check(condition, text):
        if not condition:
            failures.append(text)

    for line in lines:
        name, port, *pairs = line.split()
        if not port.isdigit():
            continue
        values = dict(pair.split("=", 1) for pair in pairs)
        port = int(port)
        client = from_client(port)
        # A byte-identical retransmission does not add a new write boundary.
        data = [len(content) for _, content in dict.fromkeys(
            (p["seq"], p["data"]) for p in client if p["data"]
        )]
        syn = next((p for p in client if flag(p, SYN)), None)
        if name == "tcpinfo":
            rows.append(f"TCP_INFO\tрезультат {values['result']}, notsent {values['notsent']}, rtt {values['rtt']} мкс")
            check(values["result"] == "0", "TCP_INFO не прочитан")
        elif name == "split":
            rows.append(f"гарантированный разрез\tсегменты данных клиента {data}, ожидание {values['waited_us']} мкс")
            check(data[:2] == [100, 200], f"разрез не дал сегменты 100 и 200: {data}")
        elif name == "mss":
            server_sizes = sorted({p["payload"] for p in to_client(port) if p["payload"] > 0})
            rows.append(f"TCP_MAXSEG 300 до connect\tMSS в SYN {syn and syn['mss']}; размеры пакетов сервера на lo {server_sizes} (не показательны)")
            check(syn is not None and syn["mss"] == 300, "MSS в SYN не равен 300")
        elif name == "rcvbuf":
            rows.append(f"SO_RCVBUF 2048 до connect\tокно в SYN {syn and syn['window']}")
        elif name == "tfo":
            rows.append(f"TCP_FASTOPEN_CONNECT\tданных в SYN {syn and syn['payload']} байт")
        elif name == "ttl":
            ttls = sorted({p["ttl"] for p in client if p["payload"] > 0})
            rows.append(f"IP_TTL 5\tTTL сегментов данных {ttls}, было {values['previous']}")
            check(5 in ttls, "сегмент с TTL 5 не найден")
        elif name == "oob":
            urgent = [p["payload"] for p in client if flag(p, URG)]
            rows.append(f"MSG_OOB\tсегментов с URG {len(urgent)}, данные {urgent}")
            check(len(urgent) >= 1, "нет сегмента с флагом URG")
        elif name in ("rst_control", "rst_filter"):
            resets = sum(1 for p in to_client(port) if flag(p, RST))
            title = "RST без фильтра (контроль)" if name == "rst_control" else "BPF-фильтр RST"
            rows.append(f"{title}\tRST в пакетах {resets}, клиент: {values['outcome']}, установка {values['attach']}, снятие {values['detach']}")
            check(resets >= 1, f"{title}: RST не отправлен")
            check(values["outcome"] == ("reset" if name == "rst_control" else "silence"), f"{title}: исход {values['outcome']}")
        elif name == "splice":
            rows.append(f"vmsplice + splice\tотправлено {values['sent']}, сервер получил {values['received']}, сегменты {data}")
            check(values["received"] == "S", "splice не доставил байт")
        elif name == "md5":
            rows.append(f"TCP_MD5SIG\tустановка {values['enable']}, снятие {values['disable']}")
    return rows, failures


def load(path):
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("expected capture metadata object")
    return value


def save(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def json_command(*command):
    return json.loads(subprocess.check_output(command, timeout=5))


def ns_id():
    info = Path("/proc/self/ns/net").stat()
    return f"{info.st_dev}:{info.st_ino}"


def isolation(parent_namespace):
    if ns_id() == parent_namespace or {name for _, name in socket.if_nameindex()} != {"lo"}:
        raise ValueError("network namespace isolation failed")
    links = json_command("ip", "-j", "addr", "show", "dev", "lo")
    ipv4_routes = json_command("ip", "-j", "-4", "route", "show", "table", "main")
    ipv6_routes = json_command("ip", "-j", "-6", "route", "show", "table", "main")
    addresses = {f"{entry['local']}/{entry['prefixlen']}" for entry in links[0]["addr_info"]}
    if "UP" not in links[0]["flags"] or "LOOPBACK" not in links[0]["flags"] or ipv4_routes or ipv6_routes:
        raise ValueError("loopback down or nonlocal routes in namespace")
    if "127.0.0.1/8" not in addresses or addresses - {"127.0.0.1/8", "::1/128"}:
        raise ValueError("unexpected namespace addresses")
    return {"freshNamespace": True, "interfaces": ["lo"], "mainRoutes": [], "addresses": sorted(addresses),
            "mtu": links[0]["mtu"]}


def capture(binary, seed, path):
    # ETH_P_ALL exposes unexpected protocols to the validator. Keep one copy of each frame on lo.
    started_us = time.time_ns() // 1000
    with path.open("xb") as stream, socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(3)) as reader:
        reader.bind(("lo", 0))
        stream.write(HEADER)
        with subprocess.Popen([str(binary), f"{seed:x}"], stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
            deadline = time.monotonic() + CAPTURE_SECONDS
            try:
                while time.monotonic() < deadline:
                    ready, _, _ = select.select([reader], [], [], 0.2)
                    if not ready:
                        if process.poll() is not None:
                            break
                        continue
                    frame, address = reader.recvfrom(SNAPLEN + 1)
                    if address[0] != "lo" or len(frame) > SNAPLEN:
                        raise ValueError("invalid capture interface or frame size")
                    if address[2] == PACKET_OUTGOING:
                        continue
                    if stream.tell() + RECORD.size + len(frame) > MAX_PCAP:
                        raise ValueError("capture exceeded size budget")
                    seconds, micros = divmod(time.time_ns() // 1000, 1_000_000)
                    stream.write(RECORD.pack(seconds, micros, len(frame), len(frame)))
                    stream.write(frame)
                else:
                    raise ValueError("capture timed out")
            finally:
                if process.poll() is None:
                    process.kill()
                stdout, _ = process.communicate()
            if process.returncode:
                raise ValueError(f"fixture process exited with code {process.returncode}")
        # Linux SOL_PACKET / PACKET_STATISTICS returns received and dropped frames.
        _, dropped = struct.unpack("=II", reader.getsockopt(263, 6, 8))
    return stdout.decode("ascii"), started_us, time.time_ns() // 1000, dropped


def build_report(metadata, packets):
    lines = metadata["fixtureOutput"]
    expected_ports = ports(metadata["seed"])
    seen = {}
    for line in lines:
        if not re.fullmatch(r"[a-z0-9_]+ (?:[0-9]+ )?(?:[a-z0-9_]+=[a-zA-Z0-9_-]+ ?)+", line):
            raise ValueError("invalid native fixture output")
        name, number, *_ = line.split()
        if name in seen:
            raise ValueError("duplicate native fixture result")
        seen[name] = number
    if set(seen) != set(expected_ports) | {"md5", "mss_set", "rcvbuf_set", "tfo_set"}:
        raise ValueError("incomplete or unexpected native fixture output")
    if any(seen[name] != str(client) for name, (client, _) in expected_ports.items()) or seen["md5"] != "0":
        raise ValueError("native fixture ports do not match capture")
    rows, failures = analyse(lines, packets)
    if failures:
        raise ValueError("kernel operation checks failed: " + "; ".join(failures))
    return "\n".join([f"Ядро хоста {metadata['kernel']}, loopback"] + rows) + "\n"


def verify_metadata(metadata, identity):
    required = {"identity", "schemaVersion", "fixtureVersion", "generator", "captureClock", "limits",
                "seed", "kernel", "network", "dropped", "fixtureExitCode", "startedUs", "endedUs",
                "fixtureOutput"}
    if missing := required - metadata.keys():
        raise ValueError(f"missing capture metadata: {', '.join(sorted(missing))}")
    if (metadata["identity"] != identity or type(metadata["schemaVersion"]) is not int
            or metadata["schemaVersion"] != 1 or type(metadata["fixtureVersion"]) is not int
            or metadata["fixtureVersion"] != FIXTURE_VERSION or type(metadata["seed"]) is not int
            or metadata["seed"] != seed_for(identity["experimentId"])):
        raise ValueError("capture metadata belongs to another experiment or fixture")
    limits = metadata["limits"]
    if (metadata["generator"] != GENERATOR or metadata["captureClock"] != CAPTURE_CLOCK
            or not isinstance(limits, dict) or limits != LIMITS
            or any(type(value) is not int for value in limits.values())):
        raise ValueError("capture generator, clock or limits disagree with fixture")
    kernel = metadata["kernel"]
    if not isinstance(kernel, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+~-]{0,127}", kernel):
        raise ValueError("invalid kernel release label")
    network = metadata["network"]
    if (not isinstance(network, dict) or network.get("freshNamespace") is not True
            or network.get("interfaces") != ["lo"] or network.get("mainRoutes") != []
            or type(network.get("mtu")) is not int or not 0 < network["mtu"] <= 65536
            or network.get("addresses") not in (["127.0.0.1/8"], ["127.0.0.1/8", "::1/128"])):
        raise ValueError("invalid isolation metadata")
    offload = network.get("offload")
    if (not isinstance(offload, dict) or set(offload) != set(OFFLOAD_KEYS)
            or not all(value in ("on", "off") for value in offload.values())):
        raise ValueError("missing offload metadata")
    if any(type(metadata[key]) is not int or metadata[key] != 0 for key in ("dropped", "fixtureExitCode")):
        raise ValueError("capture drops or fixture process failure")
    start, end = metadata["startedUs"], metadata["endedUs"]
    if type(start) is not int or type(end) is not int or not 0 < start < end:
        raise ValueError("invalid capture timestamps")
    if end - start > (CAPTURE_SECONDS + 1) * 1_000_000:
        raise ValueError("invalid time budget")
    output = metadata["fixtureOutput"]
    if not isinstance(output, list) or not all(isinstance(line, str) for line in output):
        raise ValueError("invalid fixture output metadata")


def verify_capture(directory, identity, metadata):
    verify_metadata(metadata, identity)
    packets = read_pcap(directory / "synthetic.pcap")
    flows = validate_flows(packets, identity["experimentId"], metadata["startedUs"], metadata["endedUs"])
    report = build_report(metadata, packets)
    return flows, report


def verify_publication(directory, identity):
    if load(directory / "status.json") != {"identity": identity, "outcome": "passed"}:
        raise ValueError("capture status is not successful or belongs to another experiment")
    metadata = load(directory / "capture.json")
    flows, report = verify_capture(directory, identity, metadata)
    if (metadata["flows"] != flows or metadata["pcapSha256"] != digest((directory / "synthetic.pcap").read_bytes())
            or report.encode("utf-8") != (directory / "report.txt").read_bytes()):
        raise ValueError("PCAP, metadata and text report disagree")


def worker(directory, identity, parent_namespace):
    if platform.system() != "Linux" or ns_id() == parent_namespace:
        raise ValueError("capture requires a fresh Linux network namespace")
    subprocess.run(["ip", "link", "set", "dev", "lo", "up"], check=True, capture_output=True, timeout=5)
    network = isolation(parent_namespace)
    offloads = subprocess.check_output(["ethtool", "-k", "lo"], text=True, timeout=5)
    network["offload"] = dict(re.findall(r"^(" + "|".join(OFFLOAD_KEYS) + r"): (on|off)\b", offloads, re.M))
    binary = directory / "kernel-ops-host"
    subprocess.run(["cc", "-Wall", "-Wextra", "-Wno-unused-parameter", "-Werror", "-O1", "-o", str(binary),
                    str(ROOT / "kernel-ops-host.c")], check=True, capture_output=True, timeout=15)
    seed = seed_for(identity["experimentId"])
    output, start, end, dropped = capture(binary, seed, directory / "synthetic.pcap")
    if isolation(parent_namespace) != {key: value for key, value in network.items() if key != "offload"}:
        raise ValueError("network isolation changed during capture")
    metadata = {
        "schemaVersion": 1, "identity": identity, "fixtureVersion": FIXTURE_VERSION, "seed": seed,
        "kernel": platform.release(), "network": network, "startedUs": start, "endedUs": end,
        "fixtureOutput": output.splitlines(), "fixtureExitCode": 0, "dropped": dropped,
        "generator": GENERATOR, "captureClock": CAPTURE_CLOCK, "limits": LIMITS,
    }
    flows, report = verify_capture(directory, identity, metadata)
    metadata["flows"] = flows
    metadata["pcapSha256"] = digest((directory / "synthetic.pcap").read_bytes())
    (directory / "report.txt").write_bytes(report.encode("utf-8"))
    save(directory / "capture.json", metadata)
    # sudo's umask may leave root-owned output unreadable to the caller. The
    # caller owns the enclosing mode-0700 staging directory.
    for name in CAPTURE_FILES:
        (directory / name).chmod(0o644)


def run(output, identity):
    output.mkdir()
    try:
        if platform.system() != "Linux":
            raise ValueError("Linux network namespace unavailable")
        # The privileged worker can only hand off a capture from this private staging directory.
        with tempfile.TemporaryDirectory(dir=output) as temporary:
            private = Path(temporary)
            command = ([] if os.geteuid() == 0 else ["sudo", "-n"]) + [
                "unshare", "--net", "--", "python3", "-B", str(Path(__file__).resolve()),
                "--worker", str(private), "--parent-namespace", ns_id(), "--identity", json.dumps(identity),
            ]
            result = subprocess.run(command, capture_output=True, text=True, timeout=100)
            if result.returncode:
                message = result.stdout.strip()
                reason = message if message.startswith("capture rejected: ") and len(message) < 1500 else (
                    f"could not start isolated capture worker (exit {result.returncode})"
                )
                raise ValueError(reason)
            save(private / "status.json", {"identity": identity, "outcome": "passed"})
            verify_publication(private, identity)
            for name in CAPTURE_FILES:
                shutil.copyfile(private / name, output / name)
        save(output / "status.json", {"identity": identity, "outcome": "passed"})
        print((output / "report.txt").read_text(encoding="utf-8"), end="")
        return 0
    except (ValueError, OSError, subprocess.SubprocessError, KeyError, TypeError, IndexError) as error:
        return failure(output, identity, error)


def failure(output, identity, error):
    reason = str(error) if type(error) is ValueError else type(error).__name__
    save(output / "status.json", {"identity": identity, "outcome": "failed", "reason": reason})
    print(f"capture rejected: {reason}")
    return 1


def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--output", type=Path)
    mode.add_argument("--worker", type=Path)
    mode.add_argument("--verify", type=Path)
    parser.add_argument("--report", type=Path, help="Куда записать таблицу")
    parser.add_argument("--parent-namespace")
    parser.add_argument("--identity", type=json.loads)
    args = parser.parse_args()
    identity = args.identity or {"experimentId": uuid.uuid4().hex}
    if not re.fullmatch(r"[a-f0-9]{32}", identity["experimentId"]):
        parser.error("invalid experiment ID")
    if args.worker or args.verify:
        try:
            if args.worker:
                if not args.parent_namespace:
                    raise ValueError("expected host namespace identity")
                worker(args.worker, identity, args.parent_namespace)
            else:
                verify_publication(args.verify, identity)
            return 0
        except (ValueError, OSError, subprocess.SubprocessError, KeyError, TypeError, IndexError) as error:
            reason = str(error) if type(error) is ValueError else type(error).__name__
            print(f"capture rejected: {reason}")
            return 1
    if args.output:
        return run(args.output.resolve(), identity)
    with tempfile.TemporaryDirectory() as temporary:
        path = Path(temporary) / "kernel-ops"
        result = run(path, identity)
        if result == 0 and args.report:
            shutil.copyfile(path / "report.txt", args.report)
        return result


if __name__ == "__main__":
    sys.exit(main())
