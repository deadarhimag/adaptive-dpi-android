"""Локальный TCP/UDP echo и фиксированный DNS A-ответ для Android-стенда."""

import argparse
import http.server
import socketserver
import ssl
import struct
import threading
import time


QUERY = bytes.fromhex("123401000001000000000000076578616d706c6504746573740000010001")
ANSWER = QUERY[:2] + bytes.fromhex("81800001000100000000") + QUERY[12:]
ANSWER += bytes.fromhex("c00c000100010000003c0004c000022d")


class TcpEcho(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(10)
        try:
            while chunk := self.request.recv(4096):
                self.request.sendall(chunk)
                print(f"TCP echo: {len(chunk)} bytes", flush=True)
        except OSError:
            pass


class UdpEcho(socketserver.BaseRequestHandler):
    def handle(self):
        data, sock = self.request
        if data == b"adaptive-udp-large":
            data = bytes(index % 256 for index in range(1400))
        sock.sendto(data, self.client_address)
        print(f"UDP echo: {len(data)} bytes", flush=True)


class DnsFixture(socketserver.BaseRequestHandler):
    def handle(self):
        data, sock = self.request
        if data == QUERY:
            sock.sendto(ANSWER, self.client_address)
            print(f"DNS/UDP: {len(data)}/{len(ANSWER)} bytes", flush=True)


class HttpsFixture(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/probe":
            self.send_error(404)
            return
        body = b"adaptive-tun-https\n"
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        print("HTTPS fixture: expected response sent", flush=True)

    def log_message(self, *_args):
        pass


class LongTcpServer(socketserver.ThreadingTCPServer):
    daemon_threads = True

    def __init__(self, address):
        self.accepted = 0
        self.counter_lock = threading.Lock()
        super().__init__(address, LongTcpFixture)

    def verify_request(self, request, client_address):
        with self.counter_lock:
            self.accepted += 1
        return True


class LongTcpFixture(socketserver.StreamRequestHandler):
    def handle(self):
        self.request.settimeout(85)
        try:
            mode = self.rfile.read(1)
            if mode == b"D":
                tick = time.monotonic()
                for sample in range(281):
                    time.sleep(max(0, tick - time.monotonic()))
                    self.wfile.write(struct.pack("!I", sample))
                    tick += .25
            elif mode == b"U":
                expected = b"".join(struct.pack("!I", sample) for sample in range(281))
                data = self.rfile.read(len(expected))
                self.wfile.write(b"OK" if data == expected else b"NO")
            elif mode == b"I":
                self.wfile.write(b"I")
                self.rfile.read(1)
        except OSError:
            pass


class FixtureCounter(socketserver.BaseRequestHandler):
    def handle(self):
        source = self.server.long_tcp
        with source.counter_lock:
            accepted = source.accepted
        self.request.sendall(struct.pack("!I", accepted))


def start_https(address, cert, key):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(cert, key)
    server = http.server.ThreadingHTTPServer((address, 39054), HttpsFixture)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    return server


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--https-certificate")
    parser.add_argument("--https-key")
    parser.add_argument("--extended", action="store_true")
    args = parser.parse_args()
    if bool(args.https_certificate) != bool(args.https_key):
        parser.error("Для HTTPS укажите сертификат и ключ.")
    address = args.bind
    with (
        socketserver.ThreadingTCPServer((address, 39052), TcpEcho) as tcp,
        socketserver.UDPServer((address, 39052), UdpEcho) as udp,
        socketserver.UDPServer((address, 39053), DnsFixture) as dns,
    ):
        servers = [tcp, udp]
        if args.https_certificate:
            servers.append(start_https(address, args.https_certificate, args.https_key))
        if args.extended:
            servers.append(LongTcpServer((address, 39055)))
            counter = socketserver.ThreadingTCPServer((address, 39057), FixtureCounter)
            counter.long_tcp = servers[-1]
            servers.append(counter)
        try:
            for server in servers:
                if server is not dns:
                    threading.Thread(target=server.serve_forever, daemon=True).start()
            print("ready", flush=True)
            dns.serve_forever()
        finally:
            for server in servers[3:]:
                server.server_close()


if __name__ == "__main__":
    main()
