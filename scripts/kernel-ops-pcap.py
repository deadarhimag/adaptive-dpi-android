"""Задача 1.6, шаг 5: влияние операций adaptive_ops.c на сегменты, Linux-хост, loopback.

Собирает scripts/kernel-ops-host.c компилятором cc, захватывает пакеты на lo (AF_PACKET, нужен
CAP_NET_RAW: запуск через sudo) и печатает таблицу «сценарий - что видно в пакетах».
Проверяются только свойства, которые на loopback не зависят от GSO: границы двух записей при
гарантированном разрезе, MSS в SYN, TTL, флаг URG, RST при контроле и тишина с фильтром.
Размеры пакетов сервера на lo не показательны: на loopback ядро передаёт и укрупняет сегменты (GSO/GRO),
поэтому влияние MSS и окна на дробление ответа проверяется только на стенде с настоящим интерфейсом.
"""
import argparse
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

PACKET_OUTGOING = 4
ROOT = Path(__file__).resolve().parent


class Capture(threading.Thread):
    def __init__(self):
        super().__init__(daemon=True)
        self.packets = []
        self.stopped = threading.Event()
        self.socket = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(0x0800))
        self.socket.bind(("lo", 0))
        self.socket.settimeout(0.2)

    def run(self):
        while not self.stopped.is_set():
            try:
                frame, address = self.socket.recvfrom(262144)
            except socket.timeout:
                continue
            # На lo каждый пакет виден дважды: исходящая копия отбрасывается.
            if address[2] != PACKET_OUTGOING:
                segment = parse(frame)
                if segment:
                    self.packets.append(segment)

    def stop(self):
        time.sleep(0.3)
        self.stopped.set()
        self.join()
        self.socket.close()


def parse(frame):
    ip = frame[14:]
    if len(ip) < 20 or ip[0] >> 4 != 4 or ip[9] != 6:
        return None
    header = (ip[0] & 15) * 4
    total = struct.unpack("!H", ip[2:4])[0]
    tcp = ip[header:]
    source, destination = struct.unpack("!HH", tcp[0:4])
    offset = (tcp[12] >> 4) * 4
    flags = tcp[13]
    window = struct.unpack("!H", tcp[14:16])[0]
    mss = None
    options = tcp[20:offset]
    index = 0
    while index < len(options):
        kind = options[index]
        if kind == 0:
            break
        if kind == 1:
            index += 1
            continue
        if index + 1 >= len(options):
            break
        length = options[index + 1]
        if kind == 2 and length == 4:
            mss = struct.unpack("!H", options[index + 2:index + 4])[0]
        index += max(length, 2)
    return {"src": source, "dst": destination, "ttl": ip[8], "flags": flags, "window": window, "mss": mss,
            "payload": total - header - offset}


def flag(segment, mask):
    return bool(segment["flags"] & mask)


SYN, RST, URG = 0x02, 0x04, 0x20


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
        data = [p["payload"] for p in client if p["payload"] > 0]
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, help="Куда записать таблицу")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as temporary:
        binary = Path(temporary) / "kernel-ops-host"
        subprocess.run(["cc", "-Wall", "-Wextra", "-Wno-unused-parameter", "-Werror", "-O1", "-o", str(binary),
                        str(ROOT / "kernel-ops-host.c")], check=True)
        capture = Capture()
        capture.start()
        output = subprocess.run([str(binary)], check=True, capture_output=True, text=True, timeout=60).stdout
        capture.stop()
    rows, failures = analyse(output.splitlines(), capture.packets)
    kernel = subprocess.run(["uname", "-r"], capture_output=True, text=True).stdout.strip()
    report = "\n".join([f"Ядро хоста {kernel}, loopback"] + rows) + "\n"
    print(report, end="")
    if args.report:
        args.report.write_text(report, encoding="utf-8")
    if failures:
        print("Проверка сегментов не пройдена: " + "; ".join(failures), file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
