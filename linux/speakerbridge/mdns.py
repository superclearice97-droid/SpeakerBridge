"""Minimal, dependency-free mDNS / DNS-SD browser.

Sends "one-shot" queries (RFC 6762 section 5.1) from an ephemeral UDP port.
Responders answer such queries by unicast straight back to that port, so we
never need to bind port 5353 and never fight Avahi for it.
"""
from __future__ import annotations

import ipaddress
import random
import socket
import struct
import time
from dataclasses import dataclass, field

MDNS_ADDR = "224.0.0.251"
MDNS_PORT = 5353

T_A, T_PTR, T_TXT, T_AAAA, T_SRV, T_ANY = 1, 12, 16, 28, 33, 255


# --------------------------------------------------------------------------
# Wire format
# --------------------------------------------------------------------------

def encode_name(name: str) -> bytes:
    out = b""
    for label in name.rstrip(".").split("."):
        raw = label.encode("utf-8")
        if len(raw) > 63:
            raise ValueError(f"label too long: {label!r}")
        out += bytes([len(raw)]) + raw
    return out + b"\x00"


def build_query(names: list[str], qtype: int = T_PTR, qid: int | None = None) -> bytes:
    qid = random.randint(1, 0xFFFF) if qid is None else qid
    header = struct.pack("!HHHHHH", qid, 0, len(names), 0, 0, 0)
    body = b"".join(encode_name(n) + struct.pack("!HH", qtype, 1) for n in names)
    return header + body


def _read_name(data: bytes, offset: int) -> tuple[str, int]:
    """Decode a possibly-compressed name. Returns (name, offset after name)."""
    labels: list[str] = []
    end = None
    hops = 0
    while True:
        if offset >= len(data):
            raise ValueError("name runs past end of packet")
        length = data[offset]
        if length & 0xC0 == 0xC0:
            if offset + 1 >= len(data):
                raise ValueError("truncated pointer")
            pointer = ((length & 0x3F) << 8) | data[offset + 1]
            if end is None:
                end = offset + 2
            offset = pointer
            hops += 1
            if hops > 64:
                raise ValueError("compression loop")
            continue
        if length == 0:
            offset += 1
            break
        offset += 1
        labels.append(data[offset:offset + length].decode("utf-8", "replace"))
        offset += length
    return ".".join(labels), (end if end is not None else offset)


@dataclass
class Record:
    name: str
    rtype: int
    ttl: int
    value: object   # str for PTR, dict for TXT, (prio, weight, port, target) for SRV, str ip for A/AAAA


def parse_txt(rdata: bytes) -> dict[str, str]:
    out: dict[str, str] = {}
    i = 0
    while i < len(rdata):
        n = rdata[i]
        i += 1
        item = rdata[i:i + n].decode("utf-8", "replace")
        i += n
        if not item:
            continue
        k, _, v = item.partition("=")
        out.setdefault(k.lower(), v)
    return out


def parse_packet(data: bytes) -> list[Record]:
    """Parse every resource record (answer, authority, additional)."""
    if len(data) < 12:
        return []
    _id, flags, qd, an, ns, ar = struct.unpack("!HHHHHH", data[:12])
    if not flags & 0x8000:      # a query, not a response
        return []
    off = 12
    for _ in range(qd):
        _, off = _read_name(data, off)
        off += 4
    records: list[Record] = []
    for _ in range(an + ns + ar):
        name, off = _read_name(data, off)
        if off + 10 > len(data):
            break
        rtype, _rclass, ttl, rdlen = struct.unpack("!HHIH", data[off:off + 10])
        off += 10
        rdata_off = off
        rdata = data[off:off + rdlen]
        off += rdlen
        value: object = None
        if rtype == T_PTR:
            value, _ = _read_name(data, rdata_off)
        elif rtype == T_SRV and rdlen >= 7:
            prio, weight, port = struct.unpack("!HHH", rdata[:6])
            target, _ = _read_name(data, rdata_off + 6)
            value = (prio, weight, port, target)
        elif rtype == T_TXT:
            value = parse_txt(rdata)
        elif rtype == T_A and rdlen == 4:
            value = str(ipaddress.IPv4Address(rdata))
        elif rtype == T_AAAA and rdlen == 16:
            value = str(ipaddress.IPv6Address(rdata))
        else:
            continue
        records.append(Record(name=name, rtype=rtype, ttl=ttl, value=value))
    return records


# --------------------------------------------------------------------------
# Browsing
# --------------------------------------------------------------------------

@dataclass
class ServiceInstance:
    instance: str               # e.g. "Living Room speaker._googlecast._tcp.local"
    service_type: str           # e.g. "_googlecast._tcp"
    host: str = ""
    port: int = 0
    txt: dict = field(default_factory=dict)
    addresses: set[str] = field(default_factory=set)
    sender_ip: str = ""         # address the answer came from (fallback)

    @property
    def label(self) -> str:
        suffix = "." + self.service_type + ".local"
        return self.instance[:-len(suffix)] if self.instance.endswith(suffix) else self.instance

    @property
    def ipv4(self) -> str:
        v4 = sorted(a for a in self.addresses if ":" not in a)
        return v4[0] if v4 else self.sender_ip


class _Collector:
    def __init__(self, service_types: list[str]):
        self.types = {t.lower(): t for t in service_types}
        self.instances: dict[str, ServiceInstance] = {}
        self.hosts: dict[str, set[str]] = {}
        self.pending_srv: dict[str, tuple] = {}
        self.pending_txt: dict[str, dict] = {}

    def feed(self, records: list[Record], sender_ip: str) -> None:
        for r in records:
            lname = r.name.lower()
            if r.rtype == T_PTR:
                stype = lname[:-len(".local")] if lname.endswith(".local") else lname
                if stype in self.types and isinstance(r.value, str):
                    key = r.value.lower()
                    inst = self.instances.get(key)
                    if inst is None:
                        inst = ServiceInstance(instance=r.value, service_type=self.types[stype])
                        self.instances[key] = inst
                    inst.sender_ip = inst.sender_ip or sender_ip
            elif r.rtype == T_SRV:
                self.pending_srv[lname] = r.value
            elif r.rtype == T_TXT:
                self.pending_txt.setdefault(lname, {}).update(r.value)
            elif r.rtype in (T_A, T_AAAA):
                self.hosts.setdefault(lname, set()).add(r.value)

    def resolve(self) -> list[ServiceInstance]:
        for key, inst in self.instances.items():
            srv = self.pending_srv.get(key)
            if srv:
                inst.port = srv[2]
                inst.host = srv[3]
                inst.addresses |= self.hosts.get(srv[3].lower(), set())
            inst.txt.update(self.pending_txt.get(key, {}))
        return list(self.instances.values())

    def unresolved(self) -> list[str]:
        out = []
        for key, inst in self.instances.items():
            if key not in self.pending_srv or key not in self.pending_txt:
                out.append(inst.instance)
        return out


def _open_socket(interface_ip: str | None) -> socket.socket:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)
    if interface_ip:
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(interface_ip))
    sock.bind(("0.0.0.0", 0))
    sock.settimeout(0.25)
    return sock


def browse(service_types: list[str], timeout: float = 3.0,
           interface_ip: str | None = None,
           target: tuple[str, int] = (MDNS_ADDR, MDNS_PORT)) -> list[ServiceInstance]:
    """Browse for DNS-SD service types (e.g. "_googlecast._tcp").

    `target` is overridable so tests can point at a fake responder.
    """
    col = _Collector(service_types)
    sock = _open_socket(interface_ip)
    query = build_query([t + ".local" for t in service_types], T_PTR)
    try:
        deadline = time.monotonic() + timeout
        # Repeat the query: mDNS is lossy on Wi-Fi.
        send_at = [0.0, 0.6, 1.5]
        followed_up = False
        start = time.monotonic()
        while time.monotonic() < deadline:
            elapsed = time.monotonic() - start
            while send_at and elapsed >= send_at[0]:
                send_at.pop(0)
                sock.sendto(query, target)
            # Halfway through, ask directly for anything missing SRV/TXT.
            if not followed_up and elapsed > timeout / 2:
                followed_up = True
                missing = col.unresolved()
                for i in range(0, len(missing), 8):
                    sock.sendto(build_query(missing[i:i + 8], T_ANY), target)
            try:
                data, addr = sock.recvfrom(9000)
            except socket.timeout:
                continue
            except OSError:
                continue
            try:
                col.feed(parse_packet(data), addr[0])
            except (ValueError, struct.error, IndexError):
                continue   # ignore malformed packets
    finally:
        sock.close()
    return col.resolve()
