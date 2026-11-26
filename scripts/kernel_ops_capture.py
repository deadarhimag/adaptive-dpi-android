"""PCAP format and packet validation for the isolated kernel-ops fixture."""

from collections import defaultdict
import hashlib
import struct


MAX_PCAP = 1024 * 1024
SNAPLEN = 65550
HEADER = struct.pack("<IHHIIII", 0xA1B2C3D4, 2, 4, 0, 0, SNAPLEN, 1)
RECORD = struct.Struct("<IIII")
LOOPBACK = bytes((127, 0, 0, 1))
SYN, RST, FIN, ACK, URG = 0x02, 0x04, 0x01, 0x10, 0x20
SCENARIOS = {
    "tcpinfo": ((), ()),
    "split": (((100, "a"), (200, "b")), ()),
    "mss": ((), ((4000, "m"),)),
    "rcvbuf": ((), ((20000, "r"),)),
    "tfo": (((50, "t"),), ()),
    "ttl": (((10, "l"),), ()),
    "oob": (((10, "o"), (1, "U")), ()),
    "rst_control": ((), ()),
    "rst_filter": ((), ()),
    "splice": (((1, "S"),), ()),
}


def seed_for(experiment):
    return int(experiment[:16], 16)


def ports(seed):
    base = 40000 + seed % 1000 * 20
    return {name: (base + index * 2 + 1, base + index * 2) for index, name in enumerate(SCENARIOS)}


def payload(seed, writes, scenario):
    key = seed.to_bytes(8, "little")
    return b"".join(bytes(
        ord(value) ^ (0 if scenario == "splice" or value == "U" else key[index % 8])
        for index in range(size)
    ) for size, value in writes)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def parse_frame(frame):
    if not 54 <= len(frame) <= SNAPLEN or frame[12:14] != b"\x08\x00":
        raise ValueError("unexpected Ethernet frame")
    ip = frame[14:]
    header = (ip[0] & 15) * 4
    total, fragment = struct.unpack_from("!HH", ip, 2)[0], struct.unpack_from("!H", ip, 6)[0]
    if (ip[0] >> 4 != 4 or ip[9] != 6 or header < 20 or header + 20 > total
            or total != len(ip) or fragment & 0xBFFF):
        raise ValueError("invalid IPv4/TCP header or fragmentation")
    if ip[12:16] != LOOPBACK or ip[16:20] != LOOPBACK:
        raise ValueError("disallowed IPv4 endpoint")
    tcp = ip[header:]
    source, destination, seq, ack = struct.unpack_from("!HHII", tcp)
    offset = (tcp[12] >> 4) * 4
    if not 20 <= offset <= len(tcp) or not source or not destination:
        raise ValueError("invalid TCP header")
    options = tcp[20:offset]
    index, mss = 0, None
    while index < len(options):
        kind = options[index]
        if kind == 0:
            break
        if kind == 1:
            index += 1
            continue
        if index + 2 > len(options) or not 2 <= options[index + 1] <= len(options) - index:
            raise ValueError("invalid TCP option")
        length = options[index + 1]
        if kind == 2:
            if length != 4 or mss is not None:
                raise ValueError("invalid TCP MSS option")
            mss = struct.unpack_from("!H", options, index + 2)[0]
        index += length
    return {"src": source, "dst": destination, "seq": seq, "ack": ack, "ttl": ip[8], "flags": tcp[13],
            "window": struct.unpack_from("!H", tcp, 14)[0], "mss": mss,
            "payload": len(tcp) - offset, "data": tcp[offset:]}


def read_pcap(path):
    if not path.is_file():
        raise ValueError("capture missing")
    if not len(HEADER) < path.stat().st_size <= MAX_PCAP:
        raise ValueError("capture empty or over size budget")
    data = path.read_bytes()
    if not data.startswith(HEADER):
        raise ValueError("invalid PCAP global header")
    offset = len(HEADER)
    packets = []
    while offset < len(data):
        if len(data) - offset < RECORD.size:
            raise ValueError("truncated PCAP record header")
        seconds, micros, included, original = RECORD.unpack_from(data, offset)
        offset += RECORD.size
        if (micros >= 1_000_000 or not 54 <= included == original <= SNAPLEN
                or included > len(data) - offset):
            raise ValueError("invalid or truncated PCAP record")
        packet = parse_frame(data[offset:offset + included])
        packet["timestampUs"] = seconds * 1_000_000 + micros
        packets.append(packet)
        offset += included
    return packets


def stream_digest(packets, syn, expected):
    data = bytearray(len(expected))
    covered = bytearray(len(expected))
    for packet in packets:
        if not packet["data"]:
            continue
        seq = (packet["seq"] + bool(packet["flags"] & SYN)) & 0xFFFFFFFF
        offset = (seq - syn["seq"] - 1) & 0xFFFFFFFF
        end = offset + packet["payload"]
        if end > len(expected) or packet["data"] != expected[offset:end]:
            raise ValueError("foreign or invalid synthetic payload")
        data[offset:end] = packet["data"]
        covered[offset:end] = b"\x01" * packet["payload"]
    if not all(covered):
        raise ValueError("incomplete synthetic payload")
    return digest(data)


def validate_flows(packets, experiment, started_us, ended_us):
    seed = seed_for(experiment)
    routes = ports(seed)
    allowed = {pair for client, server in routes.values() for pair in ((client, server), (server, client))}
    flows = defaultdict(list)
    for packet in packets:
        pair = packet["src"], packet["dst"]
        if pair not in allowed:
            raise ValueError("foreign TCP flow or port")
        if not started_us <= packet["timestampUs"] <= ended_us:
            raise ValueError("packet outside capture time window")
        flows[pair].append(packet)
    summary = {}
    for name, (client, server) in routes.items():
        outgoing = flows[client, server]
        incoming = flows[server, client]
        syn = next((p for p in outgoing if p["flags"] & (SYN | ACK) == SYN), None)
        reply = next((p for p in incoming if p["flags"] & (SYN | ACK) == (SYN | ACK)), None)
        if not syn or not reply or reply["ack"] != (syn["seq"] + 1) & 0xFFFFFFFF:
            raise ValueError(f"{name}: missing TCP SYN/SYN-ACK")
        if not any(p["flags"] & (SYN | ACK) == ACK and p["ack"] == (reply["seq"] + 1) & 0xFFFFFFFF
                   for p in outgoing):
            raise ValueError(f"{name}: missing TCP handshake ACK")
        if name in ("rst_control", "rst_filter"):
            closed = any(p["flags"] & RST for p in incoming)
        else:
            closed = all(any(p["flags"] & FIN for p in endpoint) for endpoint in (outgoing, incoming))
        if not closed:
            raise ValueError(f"{name}: missing TCP close")
        client_writes, server_writes = SCENARIOS[name]
        summary[name] = {
            "clientPort": client, "serverPort": server, "packets": len(outgoing) + len(incoming),
            "clientPayloadSha256": stream_digest(outgoing, syn, payload(seed, client_writes, name)),
            "serverPayloadSha256": stream_digest(incoming, reply, payload(seed, server_writes, name)),
        }
    return summary
