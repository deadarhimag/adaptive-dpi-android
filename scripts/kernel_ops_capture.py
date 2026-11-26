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
SEQ_MASK = 0xFFFFFFFF
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
        offset = (packet["seq"] - syn["seq"] - 1) & SEQ_MASK
        end = offset + packet["payload"]
        if end > len(expected) or packet["data"] != expected[offset:end]:
            raise ValueError("foreign or invalid synthetic payload")
        data[offset:end] = packet["data"]
        covered[offset:end] = b"\x01" * packet["payload"]
    if not all(covered):
        raise ValueError("incomplete synthetic payload")
    return digest(data)


def validate_session(packets, pair, client_bytes, server_bytes, reset_expected):
    """Check a single handshake, byte ranges and close for this controlled fixture."""
    client, server = pair
    opening = SYN | ACK | FIN | RST
    initial = {}
    acknowledged = False
    progress = {client: 0, server: 0}
    lengths = {client: client_bytes, server: server_bytes}
    finishes = set()
    reset = False
    for packet in packets:
        source, peer = packet["src"], packet["dst"]
        seq, ack, flags, size = (packet[key] for key in ("seq", "ack", "flags", "payload"))
        role = flags & opening
        if flags & SYN:
            if size:
                raise ValueError("SYN carries data outside the fixture contract")
            client_open = source == client and role == SYN
            server_reply = (source == server and role == (SYN | ACK) and client in initial
                            and ack == (initial[client]["seq"] + 1) & SEQ_MASK)
            if not (client_open or server_reply):
                raise ValueError("invalid TCP SYN/SYN-ACK")
            if source in initial and seq != initial[source]["seq"]:
                raise ValueError("second TCP handshake on fixture ports")
            if finishes or reset:
                raise ValueError("SYN after TCP close")
            initial.setdefault(source, packet)
            continue
        if len(initial) != 2:
            raise ValueError("missing TCP SYN/SYN-ACK")
        offset = (seq - initial[source]["seq"] - 1) & SEQ_MASK
        ack_offset = (ack - initial[peer]["seq"] - 1) & SEQ_MASK
        if not acknowledged:
            if flags & opening != ACK or source != client or offset != 0 or ack_offset != 0:
                raise ValueError("missing TCP handshake ACK")
            acknowledged = True
        if offset > progress[source] or (flags & ACK and ack_offset > progress[peer]):
            raise ValueError("TCP sequence or ACK belongs to another session")
        if flags & RST:
            # After the client's filter is removed, Linux can send a bare reset
            # in reply to a segment from the remaining half of the same pair.
            if not reset_expected or source != server or size or role not in (RST, RST | ACK):
                raise ValueError("unexpected TCP reset")
            if not reset and role != (RST | ACK):
                raise ValueError("expected established connection reset")
            reset = True
            continue
        if not flags & ACK:
            raise ValueError("unexpected TCP segment without ACK")
        end = offset + size
        if size and end > lengths[source]:
            raise ValueError("foreign or invalid synthetic payload")
        if flags & FIN:
            if end != lengths[source]:
                raise ValueError("FIN does not close the fixture stream")
            finishes.add(source)
            end += 1
        progress[source] = max(progress[source], end)
    if len(initial) != 2:
        raise ValueError("missing TCP SYN/SYN-ACK")
    if not acknowledged:
        raise ValueError("missing TCP handshake ACK")
    if not (reset if reset_expected else len(finishes) == 2):
        raise ValueError("missing TCP close")
    return initial


def validate_flows(packets, experiment, started_us, ended_us):
    seed = seed_for(experiment)
    routes = ports(seed)
    allowed = {direction: name for name, (client, server) in routes.items()
               for direction in ((client, server), (server, client))}
    flows = defaultdict(list)
    for packet in packets:
        pair = packet["src"], packet["dst"]
        if pair not in allowed:
            raise ValueError("foreign TCP flow or port")
        if not started_us <= packet["timestampUs"] <= ended_us:
            raise ValueError("packet outside capture time window")
        flows[allowed[pair]].append(packet)
    summary = {}
    for name, (client, server) in routes.items():
        flow = flows[name]
        client_writes, server_writes = SCENARIOS[name]
        client_payload = payload(seed, client_writes, name)
        server_payload = payload(seed, server_writes, name)
        try:
            initial = validate_session(flow, (client, server), len(client_payload), len(server_payload),
                                       name in ("rst_control", "rst_filter"))
        except ValueError as error:
            raise ValueError(f"{name}: {error}") from error
        summary[name] = {
            "clientPort": client, "serverPort": server, "packets": len(flow),
            "clientPayloadSha256": stream_digest((p for p in flow if p["src"] == client), initial[client], client_payload),
            "serverPayloadSha256": stream_digest((p for p in flow if p["src"] == server), initial[server], server_payload),
        }
    return summary
