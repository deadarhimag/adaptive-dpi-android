"""Positive and negative controls for evidence collection and publication."""

import copy
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

import stage1b_junit as junit


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "scripts"))
import kernel_ops_capture as wire


def load_script(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


pcap = load_script("pcap_fixture", HERE.parents[1] / "scripts/kernel-ops-pcap.py")
evidence = load_script("evidence_runner", HERE / "stage1b-evidence.py")
IDENTITY = {"commit": "a" * 40, "tree": "b" * 40, "runId": "12", "attempt": "1",
            "experimentId": "4bacde09012345670fedcba098765432"}


def xml(cases, tests=None):
    suite = ET.Element("testsuite", name="Fixture", tests=str(len(cases) if tests is None else tests),
                       failures=str(sum(state == "failure" for _, state in cases)),
                       errors=str(sum(state == "error" for _, state in cases)),
                       skipped=str(sum(state == "skipped" for _, state in cases)))
    for name, state in cases:
        case = ET.SubElement(suite, "testcase", classname="Fixture", name=name)
        if state:
            ET.SubElement(case, state)
    return ET.tostring(suite)


class JvmEvidence(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.file = self.directory / "TEST-Fixture.xml"
        self.expected = {("Fixture", "one"), ("Fixture", "two[0: arg]"), ("Fixture", "two[1: arg]")}
        self.cases = [(name, None) for _, name in sorted(self.expected)]
        self.file.write_bytes(xml(self.cases))
        self.task = junit.TASKS["engine"]
        event = {"identity": IDENTITY, "task": self.task, "gradle": "8.13"}
        self.events = {"start": event.copy(), "finish": {**event, "outcome": "passed"}}

    def verify(self, **overrides):
        collection = {"identity": IDENTITY, "task": self.task, "files": junit.inventory(self.directory), **overrides}
        return junit.validate("engine", self.expected, self.directory, IDENTITY, collection, self.events)

    def test_success_and_parameterized_identities(self):
        self.assertTrue(self.verify()["success"])
        self.assertEqual(3, self.verify()["counts"]["passed"])

    def test_absent_xml(self):
        self.file.unlink()
        result = self.verify()
        self.assertEqual("missing", result["results"])
        self.assertEqual(3, result["counts"]["missing"])

    def test_empty_xml(self):
        self.file.write_bytes(b"")
        self.assertFalse(self.verify()["success"])

    def test_malformed_xml(self):
        self.file.write_bytes(b"<testsuite><testcase></testsuite>")
        self.assertTrue(self.verify()["malformed"])

    def test_truncated_xml(self):
        self.file.write_bytes(self.file.read_bytes()[:-5])
        self.assertTrue(self.verify()["malformed"])

    def test_duplicate_result(self):
        (self.directory / "TEST-Fixture-copy.xml").write_bytes(self.file.read_bytes())
        result = self.verify()
        self.assertEqual(3, result["counts"]["duplicate"])
        self.assertEqual(["Fixture"], result["duplicateSuites"])
        self.assertFalse(result["success"])

    def test_missing_method(self):
        self.file.write_bytes(xml(self.cases[:-1]))
        self.assertEqual(1, self.verify()["counts"]["missing"])

    def test_substitution_preserves_count_but_fails(self):
        self.file.write_bytes(xml(self.cases[:-1] + [("unrelated", None)]))
        result = self.verify()
        self.assertEqual((3, 1, 1), tuple(result["counts"][key] for key in ("passed", "missing", "unexpected")))
        self.assertFalse(result["success"])

    def test_failed_error_and_skipped_are_separate(self):
        for marker, count in [("failure", "failures"), ("error", "errors"), ("skipped", "skipped")]:
            with self.subTest(marker=marker):
                self.file.write_bytes(xml(self.cases[:-1] + [(self.cases[-1][0], marker)]))
                result = self.verify()
                self.assertEqual("complete", result["results"])
                self.assertEqual(1, result["counts"][count])
                self.assertFalse(result["success"])

    def test_failed_gradle_with_complete_xml(self):
        self.events["finish"]["outcome"] = "failed"
        self.assertEqual("failed", self.verify()["execution"])
        self.assertFalse(self.verify()["success"])

    def test_testsuite_testcase_count_mismatch(self):
        self.file.write_bytes(xml(self.cases, tests=10))
        self.assertTrue(self.verify()["malformed"])

    def test_sha_mismatch(self):
        self.events["finish"]["identity"] = {**IDENTITY, "commit": "c" * 40}
        self.assertIn("different run", self.verify()["executionDetail"])
        self.assertFalse(self.verify()["success"])

    def test_adjacent_attempt_or_experiment(self):
        for field, value in (("attempt", "2"), ("runId", "13"), ("experimentId", "e" * 32)):
            with self.subTest(field=field):
                self.assertFalse(self.verify(identity={**IDENTITY, field: value})["success"])

    def test_inventory_change(self):
        self.assertTrue(self.verify(files={})["metadataErrors"])

    def test_not_started_and_interrupted(self):
        self.events.pop("finish")
        self.assertEqual("interrupted", self.verify()["execution"])
        self.events.pop("start")
        self.assertEqual("not_started", self.verify()["execution"])
        self.assertFalse(self.verify()["success"])

    def test_gradle_cache_up_to_date_and_no_source(self):
        for reason in ("FROM-CACHE", "UP-TO-DATE", "NO-SOURCE", "SKIPPED"):
            with self.subTest(reason=reason):
                self.events["finish"].update(outcome="skipped", skipReason=reason)
                self.assertEqual(reason, self.verify()["executionDetail"])
                self.assertFalse(self.verify()["success"])

    def test_absent_process_does_not_collect_preexisting_xml(self):
        private = self.directory / "run"
        public = self.directory / "public"
        with mock.patch.object(junit, "RESULTS", {"engine": self.directory, "vpn": self.directory}):
            result = evidence.collect_jvm(private, public, IDENTITY)
        self.assertFalse(result["success"])
        self.assertEqual([], list(public.rglob("TEST-*.xml")))


def frame(src, dst, seq, ack, flags, payload=b"", mss=False, address=wire.LOOPBACK):
    options = b"\x02\x04\x01\x2c" if mss else b""
    tcp = struct.pack("!HHIIBBHHH", src, dst, seq, ack, (5 + bool(options)) << 4, flags, 2048, 0, 0)
    ip = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(tcp + options + payload), 0, 0, 5, 6, 0,
                     wire.LOOPBACK, address)
    return b"\0" * 12 + b"\x08\x00" + ip + tcp + options + payload


def test_flows(experiment):
    seed = wire.seed_for(experiment)
    for name, (client, server) in wire.ports(seed).items():
        yield name, frame(client, server, 100, 0, wire.SYN, mss=True)
        yield name, frame(server, client, 200, 101, wire.SYN | wire.ACK)
        yield name, frame(client, server, 101, 201, wire.ACK)
        client_seq, server_seq = 101, 201
        for src, dst, seq, ack, writes in (
            (client, server, client_seq, server_seq, wire.SCENARIOS[name][0]),
            (server, client, server_seq, client_seq, wire.SCENARIOS[name][1]),
        ):
            for size, value in writes:
                yield name, frame(src, dst, seq, ack, wire.ACK | (wire.URG if value == "U" else 0),
                                  wire.payload(seed, ((size, value),), name))
                seq += size
            if src == client:
                client_seq = seq
            else:
                server_seq = seq
        if name.startswith("rst_"):
            yield name, frame(server, client, server_seq, client_seq, wire.RST | wire.ACK)
        else:
            yield name, frame(client, server, client_seq, server_seq, wire.FIN | wire.ACK)
            yield name, frame(server, client, server_seq, client_seq + 1, wire.FIN | wire.ACK)


class PcapEvidence(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.directory = self.root / "pcap"
        self.directory.mkdir()
        seed = wire.seed_for(IDENTITY["experimentId"])
        values = {"tcpinfo": "result=0 notsent=0 rtt=12", "split": "waited_us=5",
                  "mss": "requested=300", "rcvbuf": "requested=2048", "tfo": "bytes=50",
                  "ttl": "previous=64", "oob": "sent=1", "rst_control": "attach=0 outcome=reset detach=0",
                  "rst_filter": "attach=0 outcome=silence detach=0", "splice": "sent=1 received=S"}
        self.metadata = {
            "schemaVersion": 1, "fixtureVersion": pcap.FIXTURE_VERSION, "identity": IDENTITY,
            "seed": seed, "kernel": "synthetic", "startedUs": 1_000_000, "endedUs": 2_000_000,
            "fixtureExitCode": 0, "dropped": 0, "network": {
                "freshNamespace": True, "interfaces": ["lo"], "mainRoutes": [], "addresses": ["127.0.0.1/8"],
                "mtu": 65536, "offload": dict.fromkeys(("rx-checksumming", "tx-checksumming", "generic-segmentation-offload",
                                                      "generic-receive-offload", "tcp-segmentation-offload"), "off"),
            },
            "fixtureOutput": [f"{name} {client} {values[name]}" for name, (client, _) in wire.ports(seed).items()]
            + ["md5 0 enable=0 disable=0", "mss_set result=0", "rcvbuf_set result=0", "tfo_set result=0"],
        }
        self.frames = list(test_flows(IDENTITY["experimentId"]))
        self.path = self.directory / "synthetic.pcap"
        self.write(self.frames)

    def write(self, frames):
        self.path.write_bytes(wire.HEADER + b"".join(
            wire.RECORD.pack(1, index, len(packet), len(packet)) + packet
            for index, (_, packet) in enumerate(frames)
        ))

    def verify(self):
        return pcap.verify_capture(self.directory, IDENTITY, self.metadata)

    def mark_passed(self):
        flows, report = self.verify()
        self.metadata.update(flows=flows, pcapSha256=wire.digest(self.path.read_bytes()))
        pcap.save(self.directory / "capture.json", self.metadata)
        pcap.save(self.directory / "status.json", {"identity": IDENTITY, "outcome": "passed"})
        (self.directory / "report.txt").write_bytes(report.encode())

    def test_success_and_report_matches_capture(self):
        self.mark_passed()
        pcap.verify_publication(self.directory, IDENTITY)
        self.assertEqual(10, len(self.metadata["flows"]))

    def test_missing_capture(self):
        self.path.unlink()
        with self.assertRaisesRegex(ValueError, "capture missing"):
            self.verify()

    def test_corrupt_and_truncated_capture(self):
        data = self.path.read_bytes()
        self.path.write_bytes(b"0000" + data[4:])
        with self.assertRaisesRegex(ValueError, "global header"):
            self.verify()
        self.path.write_bytes(data[:-1])
        with self.assertRaisesRegex(ValueError, "truncated"):
            self.verify()

    def test_missing_expected_flow_or_close(self):
        self.write([(name, packet) for name, packet in self.frames if name != "split"])
        with self.assertRaisesRegex(ValueError, "split: missing TCP"):
            self.verify()
        self.write(self.frames[:-1])
        with self.assertRaisesRegex(ValueError, "splice: missing TCP close"):
            self.verify()

    def test_foreign_flow(self):
        self.write(self.frames + [("foreign", frame(21000, 22000, 0, 0, wire.SYN))])
        with self.assertRaisesRegex(ValueError, "foreign TCP"):
            self.verify()

    def test_disallowed_address(self):
        self.write(self.frames + [("foreign", frame(21000, 22000, 0, 0, wire.SYN, address=b"\x7f\0\0\x02"))])
        with self.assertRaisesRegex(ValueError, "disallowed IPv4"):
            self.verify()

    def test_other_run_metadata_and_same_ports_different_payload(self):
        original = copy.deepcopy(self.metadata)
        self.metadata["identity"] = {**IDENTITY, "attempt": "2"}
        with self.assertRaisesRegex(ValueError, "another experiment"):
            self.verify()
        self.metadata = original
        new_seed = self.metadata["seed"] + 1000
        different = f"{new_seed:016x}" + IDENTITY["experimentId"][16:]
        self.write(list(test_flows(different)))
        with self.assertRaisesRegex(ValueError, "invalid synthetic payload"):
            self.verify()

    def test_isolation_failed(self):
        self.metadata["network"]["freshNamespace"] = False
        with self.assertRaisesRegex(ValueError, "isolation metadata"):
            self.verify()
        with mock.patch.object(pcap.platform, "system", return_value="Linux"), mock.patch.object(
            pcap, "ns_id", return_value="same"
        ), mock.patch.object(pcap, "capture") as capture:
            with self.assertRaisesRegex(ValueError, "fresh Linux network namespace"):
                pcap.worker(self.directory, IDENTITY, "same")
            capture.assert_not_called()

    def test_capture_size_limit(self):
        self.path.write_bytes(b"\0" * (wire.MAX_PCAP + 1))
        with self.assertRaisesRegex(ValueError, "size budget"):
            self.verify()

    def test_failed_process_or_packet_drops(self):
        for key in ("fixtureExitCode", "dropped"):
            with self.subTest(key=key):
                original = self.metadata[key]
                self.metadata[key] = 1
                with self.assertRaisesRegex(ValueError, "drops or fixture process failure"):
                    self.verify()
                self.metadata[key] = original

    def test_report_corruption_blocks_publication(self):
        self.mark_passed()
        (self.directory / "report.txt").write_text("tampered", encoding="ascii")
        with self.assertRaisesRegex(ValueError, "disagree"):
            pcap.verify_publication(self.directory, IDENTITY)
        upload = self.root / "publish"
        upload.mkdir()
        outcome = evidence.collect_pcap(self.root, upload, IDENTITY)
        self.assertEqual("failed", outcome["outcome"])
        self.assertEqual(["status.json"], sorted(path.name for path in (upload / "pcap").iterdir()))

    def test_missing_namespace_produces_safe_failure_report(self):
        output = self.root / "refusal"
        with mock.patch.object(pcap.platform, "system", return_value="Windows"), mock.patch.object(
            pcap.subprocess, "run"
        ) as launch:
            self.assertEqual(1, pcap.run(output, IDENTITY))
            launch.assert_not_called()
        self.assertEqual(["status.json"], sorted(path.name for path in output.iterdir()))
        self.assertEqual("failed", json.loads((output / "status.json").read_text())["outcome"])


if __name__ == "__main__":
    unittest.main()
