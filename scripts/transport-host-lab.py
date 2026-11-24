"""Контроль целостности TCP, UDP и HTTPS на localhost с JSON-отчётом."""

import argparse
import hashlib
import http.client
import importlib.util
import json
from pathlib import Path
import socket
import socketserver
import ssl
import threading
import time
from concurrent.futures import Future, ThreadPoolExecutor
from contextlib import ExitStack, contextmanager, redirect_stdout
import io
import platform


def load_fixture():
    location = Path(__file__).with_name("transport-fixture.py")
    spec = importlib.util.spec_from_file_location("adaptive_transport_fixture", location)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


FIXTURE = load_fixture()
LOOPBACK = "127.0.0.1"
PAYLOAD = bytes(range(256)) * 32
EXPECTED_HTTPS = b"adaptive-tun-https\n"
TIMEOUT = 4


class LabTcpHandler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(TIMEOUT)
        while chunk := self.request.recv(4096):
            self.request.sendall(chunk)


class LabTcpServer(socketserver.ThreadingTCPServer):
    request_queue_size = 32


class LabUdpHandler(socketserver.BaseRequestHandler):
    def handle(self):
        data, server = self.request
        server.sendto(data, self.client_address)


class RefusesRequest(FIXTURE.HttpsFixture):
    def do_GET(self):
        self.send_error(451, "Synthetic policy fixture")


@contextmanager
def run_server(server):
    with server:
        done = Future()

        def serve():
            try:
                server.serve_forever(poll_interval=0.05)
            except Exception as error:
                done.set_exception(error)
            else:
                done.set_result(None)

        worker = threading.Thread(target=serve, daemon=True)
        worker.start()
        try:
            yield server.server_address[1]
        finally:
            server.shutdown()
            done.result(timeout=TIMEOUT)
            worker.join(TIMEOUT)


class LabHttpsServer(FIXTURE.http.server.ThreadingHTTPServer):
    daemon_threads = False

    def __init__(self, handler, certificate, key):
        self.context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.context.load_cert_chain(certificate, key)
        super().__init__((LOOPBACK, 0), handler)

    def finish_request(self, request, client_address):
        request.settimeout(TIMEOUT)
        with self.context.wrap_socket(request, server_side=True) as wire:
            super().finish_request(wire, client_address)


def https_exchange(port, context, expected_code):
    client = http.client.HTTPSConnection("localhost", port, timeout=TIMEOUT, context=context)
    try:
        client.request("GET", "/probe")
        response = client.getresponse()
        body = response.read(4096)
        if response.status != expected_code or expected_code == 200 and body != EXPECTED_HTTPS:
            raise RuntimeError("Ответ HTTPS отличается от контрольного")
        return {"status": response.status, "body_sha256": hashlib.sha256(body).hexdigest()}
    finally:
        client.close()


def tcp_exchange(port, barrier):
    with socket.create_connection((LOOPBACK, port), timeout=TIMEOUT) as client:
        barrier.wait(TIMEOUT)
        start = time.perf_counter()
        client.sendall(PAYLOAD)
        client.shutdown(socket.SHUT_WR)
        received = bytearray()
        while chunk := client.recv(len(PAYLOAD) + 1 - len(received)):
            received.extend(chunk)
            if len(received) > len(PAYLOAD):
                raise RuntimeError("TCP вернул лишние байты")
        if received != PAYLOAD:
            raise RuntimeError("TCP не вернул контрольное содержимое")
        return time.perf_counter() - start


def run(certificate, key):
    trusted = ssl.create_default_context(cafile=str(certificate))
    report = {
        "platform": platform.system(),
        "data_scope": "synthetic_loopback",
        "capture": {"status": "not_measured", "pcap": None},
        "tcp": [],
    }
    with ExitStack() as resources:
        tcp_port = resources.enter_context(run_server(LabTcpServer((LOOPBACK, 0), LabTcpHandler)))
        for count in (8, 16, 32):
            barrier = threading.Barrier(count)
            start = time.perf_counter()
            with ThreadPoolExecutor(max_workers=count) as pool:
                jobs = [pool.submit(tcp_exchange, tcp_port, barrier) for _ in range(count)]
                latency = [job.result(timeout=TIMEOUT * 3) for job in jobs]
            report["tcp"].append({
                "connections": count,
                "exact_echoes": len(latency),
                "bytes_each_direction": count * len(PAYLOAD),
                "total_ms": round((time.perf_counter() - start) * 1000, 3),
                "max_exchange_ms": round(max(latency) * 1000, 3),
            })

        udp_port = resources.enter_context(run_server(socketserver.UDPServer((LOOPBACK, 0), LabUdpHandler)))
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
            client.settimeout(TIMEOUT)
            client.connect((LOOPBACK, udp_port))
            for index in range(32):
                payload = bytes([index]) + bytes(range(256))
                client.send(payload)
                if client.recv(4096) != payload:
                    raise RuntimeError("UDP не сохранил контрольную датаграмму")
        report["udp"] = {"exact_echoes": 32, "bytes_each_direction": 32 * 257}

        normal = resources.enter_context(run_server(LabHttpsServer(FIXTURE.HttpsFixture, certificate, key)))
        restricted = resources.enter_context(run_server(LabHttpsServer(RefusesRequest, certificate, key)))
        # Фикстура печатает только служебный текст. JSON остаётся единственным выводом CLI.
        with redirect_stdout(io.StringIO()):
            report["https"] = https_exchange(normal, trusted, 200)
            report["https_policy_fixture"] = https_exchange(restricted, trusted, 451)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--certificate", required=True, type=Path)
    parser.add_argument("--key", required=True, type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report = run(args.certificate, args.key)
    output = json.dumps(report, indent=2, ensure_ascii=False)
    if args.report:
        with args.report.open("x", encoding="utf-8") as destination:
            destination.write(output + "\n")
    print(output)


if __name__ == "__main__":
    main()
