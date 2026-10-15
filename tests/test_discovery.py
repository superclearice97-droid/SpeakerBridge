"""Tests for the Linux discovery engine. Run: python -m unittest discover -s tests"""
import os
import socket
import struct
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "linux"))
os.environ["XDG_CONFIG_HOME"] = tempfile.mkdtemp()

from speakerbridge import compat, mdns, scanner, ssdp, store  # noqa: E402
from speakerbridge.model import Device, ProtocolHit  # noqa: E402


# ---------------------------------------------------------------- helpers

def rr(name: bytes, rtype: int, rdata: bytes, ttl=120) -> bytes:
    return name + struct.pack("!HHIH", rtype, 0x8001, ttl, len(rdata)) + rdata


def txt(**kv) -> bytes:
    out = b""
    for k, v in kv.items():
        item = f"{k}={v}".encode()
        out += bytes([len(item)]) + item
    return out


def cast_response(instance: str, fn: str, md: str, cid: str, ip: str, port=8009) -> bytes:
    """A realistic Cast answer: PTR + SRV + TXT + A, using name compression."""
    header = struct.pack("!HHHHHH", 0, 0x8400, 0, 1, 0, 3)
    ptr_name = mdns.encode_name("_googlecast._tcp.local")
    ptr_off = 12
    inst_label = instance.encode()
    # PTR rdata = <instance label> + pointer to "_googlecast._tcp.local"
    ptr_rdata = bytes([len(inst_label)]) + inst_label + struct.pack("!H", 0xC000 | ptr_off)
    answer = rr(ptr_name, mdns.T_PTR, ptr_rdata)
    inst_off = 12 + len(ptr_name) + 10       # where the PTR rdata (instance name) starts
    inst_ptr = struct.pack("!H", 0xC000 | inst_off)
    host = mdns.encode_name(f"{cid}.local")
    srv = rr(inst_ptr, mdns.T_SRV, struct.pack("!HHH", 0, 0, port) + host)
    t = rr(inst_ptr, mdns.T_TXT, txt(id=cid, md=md, fn=fn, ca="199172"))
    a = rr(host, mdns.T_A, socket.inet_aton(ip))
    return header + answer + srv + t + a


class FakeMdns(threading.Thread):
    """Answers any query on a loopback port, like a one-shot unicast responder."""
    def __init__(self, packets):
        super().__init__(daemon=True)
        self.packets = packets
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.settimeout(0.2)
        self.port = self.sock.getsockname()[1]
        self.stop = False

    def run(self):
        while not self.stop:
            try:
                _, addr = self.sock.recvfrom(4096)
            except socket.timeout:
                continue
            for p in self.packets:
                self.sock.sendto(p, addr)


# ---------------------------------------------------------------- tests

class MdnsWireTests(unittest.TestCase):
    def test_query_roundtrip_header(self):
        q = mdns.build_query(["_googlecast._tcp.local"], qid=7)
        self.assertEqual(struct.unpack("!HHHHHH", q[:12]), (7, 0, 1, 0, 0, 0))
        name, off = mdns._read_name(q, 12)
        self.assertEqual(name, "_googlecast._tcp.local")
        self.assertEqual(struct.unpack("!HH", q[off:off + 4]), (mdns.T_PTR, 1))

    def test_parse_cast_answer_with_compression(self):
        pkt = cast_response("Kitchen-abc", "Kitchen speaker", "Google Nest Mini", "abc123", "192.168.1.40")
        recs = mdns.parse_packet(pkt)
        kinds = {r.rtype for r in recs}
        self.assertEqual(kinds, {mdns.T_PTR, mdns.T_SRV, mdns.T_TXT, mdns.T_A})
        ptr = next(r for r in recs if r.rtype == mdns.T_PTR)
        self.assertEqual(ptr.value, "Kitchen-abc._googlecast._tcp.local")
        t = next(r for r in recs if r.rtype == mdns.T_TXT)
        self.assertEqual(t.value["md"], "Google Nest Mini")

    def test_compression_loop_is_rejected(self):
        bad = struct.pack("!HHHHHH", 0, 0x8400, 0, 1, 0, 0) + b"\xc0\x0c"
        with self.assertRaises(ValueError):
            mdns.parse_packet(bad)

    def test_queries_are_ignored(self):
        self.assertEqual(mdns.parse_packet(mdns.build_query(["x.local"])), [])


class BrowseLoopbackTest(unittest.TestCase):
    def test_browse_against_fake_responder(self):
        srv = FakeMdns([
            cast_response("Nest-1", "Living Room", "Google Nest Audio", "n1", "192.168.1.20"),
            cast_response("Bar-2", "TV Soundbar", "HT-A3000", "s2", "192.168.1.21"),
            cast_response("Group-3", "Downstairs", "Google Cast Group", "g3", "192.168.1.20", 32187),
        ])
        srv.start()
        try:
            inst = mdns.browse(["_googlecast._tcp"], timeout=1.2, target=("127.0.0.1", srv.port))
        finally:
            srv.stop = True
        self.assertEqual(len(inst), 3)
        devs = scanner.merge(scanner.devices_from_mdns(inst), [])
        by_name = {d.name: d for d in devs}
        self.assertEqual(by_name["TV Soundbar"].manufacturer, "Sony")
        self.assertEqual(by_name["Living Room"].manufacturer, "Google")
        self.assertTrue(by_name["Downstairs"].is_cast_group)
        self.assertEqual(by_name["Downstairs"].ip, by_name["Living Room"].ip)  # group hosted on a member
        self.assertEqual(by_name["TV Soundbar"].key, "googlecast:s2")


class SsdpTests(unittest.TestCase):
    DESC = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:av="urn:schemas-sony-com:av">
 <device>
  <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
  <friendlyName>Sony Soundbar</friendlyName>
  <manufacturer>Sony Corporation</manufacturer>
  <modelName>HT-A7000</modelName>
  <UDN>uuid:1234</UDN>
  <av:X_ScalarWebAPI_DeviceInfo><av:X_ScalarWebAPI_Version>1.0</av:X_ScalarWebAPI_Version></av:X_ScalarWebAPI_DeviceInfo>
  <serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType></service></serviceList>
 </device>
</root>"""

    def test_parse_response(self):
        h = ssdp.parse_response(b"HTTP/1.1 200 OK\r\nLOCATION: http://1.2.3.4/d.xml\r\nST: ssdp:all\r\n\r\n")
        self.assertEqual(h["location"], "http://1.2.3.4/d.xml")
        self.assertIsNone(ssdp.parse_response(b"garbage"))

    def test_description_to_device(self):
        u = ssdp.UpnpDevice(location="http://192.168.1.30/d.xml", ip="192.168.1.30")
        ssdp.parse_description(self.DESC, u)
        devs = scanner.merge([], [u])
        d = devs[0]
        self.assertEqual(d.manufacturer, "Sony")
        self.assertIn("dlna_renderer", d.protocols)
        self.assertIn("sony_scalar", d.protocols)
        compat.classify(d, set())
        self.assertEqual(d.status, "ready_to_test")

    def test_discover_end_to_end_over_loopback(self):
        desc = self.DESC.encode()

        class H(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.end_headers()
                self.wfile.write(desc)

            def log_message(self, *a):
                pass

        http = HTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=http.serve_forever, daemon=True).start()
        loc = f"http://127.0.0.1:{http.server_port}/d.xml"
        resp = f"HTTP/1.1 200 OK\r\nLOCATION: {loc}\r\nST: ssdp:all\r\nUSN: uuid:1234\r\n\r\n".encode()
        udp = FakeMdns([resp])
        udp.start()
        try:
            found = ssdp.discover(timeout=1.0, target=("127.0.0.1", udp.port))
        finally:
            udp.stop = True
            http.shutdown()
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].model_name, "HT-A7000")
        self.assertEqual(found[0].fetch_error, "")

    def test_refuses_off_lan_description(self):
        u = ssdp.UpnpDevice(location="http://8.8.8.8/d.xml", ip="192.168.1.9")
        ssdp._fetch(u, 0.5)
        self.assertIn("LAN", u.fetch_error)


class ClassificationTests(unittest.TestCase):
    def _dev(self, *protos, **kw):
        d = Device(ip="10.0.0.5", **kw)
        for p in protos:
            d.add(ProtocolHit(p, "mdns"))
        return d

    def test_spotify_only_is_control_only(self):
        d = compat.classify(self._dev("spotify_connect", manufacturer="Vizio"), set())
        self.assertEqual(d.status, "control_only")
        self.assertIn("Chromecast built-in", d.notes[0])

    def test_router_is_not_speaker(self):
        d = self._dev()
        d.looks_like_non_speaker = True
        self.assertEqual(compat.classify(d, set()).status, "not_speaker")

    def test_third_party_cast_gets_group_tip(self):
        d = compat.classify(self._dev("googlecast", manufacturer="Vizio"), set())
        self.assertEqual(d.status, "ready_to_test")
        self.assertIn("Google Home speaker group", d.notes[0])

    def test_verified_persists(self):
        d = self._dev("googlecast", manufacturer="Google", device_id="googlecast:x1")
        store.mark_verified(d.key, "Nest", "googlecast")
        self.assertEqual(compat.classify(d, store.verified_keys()).status, "verified")
        store.unmark_verified(d.key)
        self.assertEqual(compat.classify(d, store.verified_keys()).status, "ready_to_test")

    def test_airplay2_feature_bit(self):
        self.assertTrue(scanner._airplay2("0x4A7FDFD5,0xBC157FDE"))   # high word bit 6 = bit 38
        self.assertFalse(scanner._airplay2("0x5A7FFFF7,0x1E"))
        self.assertFalse(scanner._airplay2("0x77,0x0"))
        self.assertFalse(scanner._airplay2("nonsense"))


if __name__ == "__main__":
    unittest.main()
