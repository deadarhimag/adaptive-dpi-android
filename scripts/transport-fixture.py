"""Локальный TCP/UDP echo и фиксированный DNS A-ответ для Android-стенда."""

import argparse
import socketserver
import threading


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
        sock.sendto(data, self.client_address)
        print(f"UDP echo: {len(data)} bytes", flush=True)


class DnsFixture(socketserver.BaseRequestHandler):
    def handle(self):
        data, sock = self.request
        if data == QUERY:
            sock.sendto(ANSWER, self.client_address)
            print(f"DNS/UDP: {len(data)}/{len(ANSWER)} bytes", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", default="127.0.0.1")
    args = parser.parse_args()
    address = args.bind
    with (
        socketserver.ThreadingTCPServer((address, 39052), TcpEcho) as tcp,
        socketserver.UDPServer((address, 39052), UdpEcho) as udp,
        socketserver.UDPServer((address, 39053), DnsFixture) as dns,
    ):
        for server in (tcp, udp):
            threading.Thread(target=server.serve_forever, daemon=True).start()
        print("ready", flush=True)
        dns.serve_forever()


if __name__ == "__main__":
    main()
