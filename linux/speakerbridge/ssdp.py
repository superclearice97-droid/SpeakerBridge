"""Dependency-free SSDP (UPnP) discovery plus device-description parsing."""
from __future__ import annotations

import ipaddress
import socket
import time
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from urllib.parse import urlparse

SSDP_ADDR = "239.255.255.250"
SSDP_PORT = 1900

SEARCH_TARGETS = [
    "ssdp:all",
    "urn:schemas-upnp-org:device:MediaRenderer:1",
    "urn:dial-multiscreen-org:service:dial:1",
]


def build_msearch(st: str, mx: int = 2) -> bytes:
    return (
        "M-SEARCH * HTTP/1.1\r\n"
        f"HOST: {SSDP_ADDR}:{SSDP_PORT}\r\n"
        'MAN: "ssdp:discover"\r\n'
        f"MX: {mx}\r\n"
        f"ST: {st}\r\n"
        "USER-AGENT: Linux/1.0 UPnP/1.1 SpeakerBridge/0.1\r\n"
        "\r\n"
    ).encode()


def parse_response(data: bytes) -> dict[str, str] | None:
    try:
        text = data.decode("utf-8", "replace")
    except Exception:
        return None
    lines = text.split("\r\n") if "\r\n" in text else text.split("\n")
    if not lines or not (lines[0].upper().startswith("HTTP/") or lines[0].upper().startswith("NOTIFY")):
        return None
    headers: dict[str, str] = {}
    for line in lines[1:]:
        k, sep, v = line.partition(":")
        if sep:
            headers[k.strip().lower()] = v.strip()
    return headers if "location" in headers else None


@dataclass
class UpnpDevice:
    location: str
    ip: str
    usn: str = ""
    server: str = ""
    targets: set[str] = field(default_factory=set)
    friendly_name: str = ""
    manufacturer: str = ""
    model_name: str = ""
    model_number: str = ""
    udn: str = ""
    device_types: list[str] = field(default_factory=list)
    service_types: list[str] = field(default_factory=list)
    raw_xml: str = ""
    fetch_error: str = ""


def _strip_ns(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse_description(xml_text: str, dev: UpnpDevice) -> None:
    """Fill UpnpDevice fields from a UPnP device description document."""
    dev.raw_xml = xml_text
    root = ET.fromstring(xml_text)
    devices = [el for el in root.iter() if _strip_ns(el.tag) == "device"]
    for i, d in enumerate(devices):
        fields = {_strip_ns(c.tag): (c.text or "").strip() for c in d}
        if fields.get("deviceType"):
            dev.device_types.append(fields["deviceType"])
        if i == 0:  # root device carries the identity
            dev.friendly_name = fields.get("friendlyName", "")
            dev.manufacturer = fields.get("manufacturer", "")
            dev.model_name = fields.get("modelName", "")
            dev.model_number = fields.get("modelNumber", "")
            dev.udn = fields.get("UDN", "")
    for el in root.iter():
        if _strip_ns(el.tag) == "serviceType" and el.text:
            dev.service_types.append(el.text.strip())


def _fetch(dev: UpnpDevice, timeout: float) -> UpnpDevice:
    try:
        req = urllib.request.Request(dev.location, headers={"User-Agent": "SpeakerBridge/0.1"})
        # Local-network only: never follow a description URL off the LAN.
        host = urlparse(dev.location).hostname or ""
        try:
            addr = ipaddress.ip_address(host)
        except ValueError:
            raise ValueError("description URL is not a LAN address") from None
        if not (addr.is_private or addr.is_link_local):
            raise ValueError("description URL is not a LAN address")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read(512 * 1024).decode("utf-8", "replace")
        parse_description(body, dev)
    except Exception as exc:  # network gear is messy; record and move on
        dev.fetch_error = f"{type(exc).__name__}: {exc}"
    return dev


def discover(timeout: float = 3.0, interface_ip: str | None = None,
             fetch_descriptions: bool = True,
             target: tuple[str, int] = (SSDP_ADDR, SSDP_PORT)) -> list[UpnpDevice]:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    if interface_ip:
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(interface_ip))
    sock.bind(("0.0.0.0", 0))
    sock.settimeout(0.25)
    found: dict[str, UpnpDevice] = {}
    try:
        for _ in range(2):
            for st in SEARCH_TARGETS:
                sock.sendto(build_msearch(st), target)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                data, addr = sock.recvfrom(4096)
            except (socket.timeout, OSError):
                continue
            h = parse_response(data)
            if not h:
                continue
            loc = h["location"]
            dev = found.get(loc)
            if dev is None:
                dev = UpnpDevice(location=loc, ip=addr[0], usn=h.get("usn", ""), server=h.get("server", ""))
                found[loc] = dev
            if h.get("st"):
                dev.targets.add(h["st"])
    finally:
        sock.close()

    devices = list(found.values())
    if fetch_descriptions and devices:
        with ThreadPoolExecutor(max_workers=8) as pool:
            devices = list(pool.map(lambda d: _fetch(d, 2.5), devices))
    return devices
