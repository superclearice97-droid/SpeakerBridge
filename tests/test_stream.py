"""Tests for Linux -> Cast / DLNA streaming. Run: python -m unittest discover -s tests

Uses a fake Cast speaker (TLS on loopback), a fake DLNA renderer (HTTP on loopback) and
fake pactl/parec scripts, so no real speaker or PipeWire is needed.
"""
import json
import os
import shutil
import socket
import ssl
import stat
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "linux"))

from speakerbridge import cast, cli, dlna, stream  # noqa: E402
from speakerbridge.model import Device, ProtocolHit  # noqa: E402


class WireTests(unittest.TestCase):
    def test_layout(self):
        b = cast.encode("sender-0", "receiver-0", "ns", "{}")
        self.assertEqual(b, b"\x08\x00\x12\x08sender-0\x1a\x0areceiver-0\x22\x02ns\x28\x00\x32\x02{}")

    def test_roundtrip_long_payload(self):
        p = json.dumps({"x": "a" * 400})
        m = cast.decode(cast.encode("a", "b", cast.NS_MEDIA, p))
        self.assertEqual(m, {"source": "a", "dest": "b", "namespace": cast.NS_MEDIA, "payload": p})

    def test_wav_header(self):
        h = stream.wav_header()
        self.assertEqual(len(h), 44)
        self.assertEqual((h[:4], h[8:16], h[36:40]), (b"RIFF", b"WAVEfmt ", b"data"))
        fmt, ch, rate = struct.unpack("<HHI", h[20:28])
        self.assertEqual((fmt, ch, rate), (1, 2, 48000))


def silence():
    while True:
        yield b"\x00" * stream.CHUNK
        time.sleep(0.02)


class LiveServerTest(unittest.TestCase):
    def test_listener_gets_header_then_audio(self):
        srv = stream.LiveServer(silence(), "127.0.0.1")
        try:
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(srv.url, timeout=5) as r:
                self.assertEqual(r.headers["Content-Type"], "audio/wav")
                data = r.read(44 + stream.CHUNK * 3)
            self.assertEqual(data[:4], b"RIFF")
            self.assertEqual(len(data), 44 + stream.CHUNK * 3)
        finally:
            srv.close()


# ------------------------------------------------------------ fake Cast speaker
class FakeCast(threading.Thread):
    """Accepts one sender, behaves like the Default Media Receiver, records what happened."""

    def __init__(self, certdir):
        super().__init__(daemon=True)
        self.ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.ctx.load_cert_chain(f"{certdir}/c.pem", f"{certdir}/k.pem")
        self.srv = socket.socket()
        self.srv.bind(("127.0.0.1", 0))
        self.srv.listen(1)
        self.port = self.srv.getsockname()[1]
        self.events, self.fetched = [], b""

    def run(self):
        c = self.ctx.wrap_socket(self.srv.accept()[0], server_side=True)

        def send(src, ns, obj):
            b = cast.encode(src, "sender-0", ns, json.dumps(obj))
            c.sendall(struct.pack(">I", len(b)) + b)

        def recv():
            h = b""
            while len(h) < 4:
                x = c.recv(4 - len(h))
                if not x:
                    return None
                h += x
            n, b = struct.unpack(">I", h)[0], b""
            while len(b) < n:
                b += c.recv(n - len(b))
            return cast.decode(b)

        while True:
            try:
                m = recv()
            except OSError:
                return
            if m is None:
                return
            p = json.loads(m["payload"])
            self.events.append(p.get("type"))
            if p.get("type") == "LAUNCH":
                send("receiver-0", cast.NS_RECEIVER, {"type": "RECEIVER_STATUS", "status": {
                    "applications": [{"appId": cast.APP_ID, "sessionId": "S1", "transportId": "web-1"}]}})
            elif p.get("type") == "LOAD":
                self.load = p
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                with opener.open(p["media"]["contentId"], timeout=5) as r:
                    self.fetched = r.read(44 + stream.CHUNK)
                send("web-1", cast.NS_MEDIA, {"type": "MEDIA_STATUS",
                                              "status": [{"playerState": "PLAYING", "mediaSessionId": 1}]})


# ------------------------------------------------------------ fake DLNA renderer
DESCRIPTION = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <URLBase>http://127.0.0.1:{port}/</URLBase>
  <device><friendlyName>Fake Denon</friendlyName>
    <serviceList>
      <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <controlURL>/rc</controlURL></service>
      <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <controlURL>upnp/av</controlURL></service>
    </serviceList></device>
</root>"""


class FakeDlna:
    """Serves a description and an AVTransport control URL; on Play it fetches the stream."""

    def __init__(self, reject: str = ""):
        self.actions, self.fetched, self.reject = [], b"", reject
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                body = DESCRIPTION.format(port=fake.port).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                body = self.rfile.read(int(self.headers["Content-Length"])).decode()
                action = self.headers["SOAPACTION"].strip('"').split("#")[1]
                fake.actions.append(action)
                if self.path != "/upnp/av" or action == fake.reject:
                    self.send_response(500)
                    self.end_headers()
                    self.wfile.write(b"<errorDescription>Not supported</errorDescription>")
                    return
                if action == "SetAVTransportURI":
                    fake.uri = body.split("<CurrentURI>")[1].split("</CurrentURI>")[0]
                self.send_response(200)
                self.end_headers()
                if action == "Play":
                    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                    with opener.open(fake.uri, timeout=5) as r:
                        fake.fetched = r.read(44 + stream.CHUNK)

            def log_message(self, *a):
                pass

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.httpd.server_address[1]
        self.location = f"http://127.0.0.1:{self.port}/desc.xml"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def target(self, name="Fake Denon"):
        return stream.Target(name, "dlna_renderer", "127.0.0.1", location=self.location)

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


class DlnaTests(unittest.TestCase):
    def test_finds_av_transport_and_resolves_against_urlbase(self):
        xml = DESCRIPTION.format(port=1234)
        self.assertEqual(dlna.av_transport(xml, "http://127.0.0.1:1234/desc.xml"),
                         ("urn:schemas-upnp-org:service:AVTransport:1", "http://127.0.0.1:1234/upnp/av"))

    def test_no_av_transport_is_a_clear_error(self):
        with self.assertRaisesRegex(dlna.DlnaError, "no AVTransport"):
            dlna.av_transport("<root><device/></root>", "http://127.0.0.1/")

    def test_refuses_addresses_off_the_lan(self):
        with self.assertRaisesRegex(dlna.DlnaError, "local network"):
            dlna.DlnaRenderer("http://8.8.8.8/desc.xml")


class TargetTests(unittest.TestCase):
    def _dev(self, name, *protos):
        d = Device(ip="192.168.1.20", name=name)
        for p in protos:
            d.add(ProtocolHit(p, "test", port=8009 if p == "googlecast" else 0,
                              details={"location": "http://192.168.1.20/d.xml"} if p == "dlna_renderer" else {}))
        return d

    def test_prefers_cast_then_dlna(self):
        self.assertEqual(stream.target_for(self._dev("Sony", "googlecast", "dlna_renderer")).protocol, "googlecast")
        t = stream.target_for(self._dev("Denon", "dlna_renderer"))
        self.assertEqual((t.protocol, t.location), ("dlna_renderer", "http://192.168.1.20/d.xml"))
        self.assertIsNone(stream.target_for(self._dev("Spotify only", "spotify_connect")))

    def test_cli_finds_dlna_speakers_by_name(self):
        devs = [self._dev("Kitchen Nest", "googlecast"), self._dev("Den HEOS", "dlna_renderer"),
                self._dev("Den Spotify", "spotify_connect")]
        self.assertEqual(cli.find_target(devs, "heos")[0].name, "Den HEOS")
        self.assertEqual(cli.find_target(devs, "den")[0].name, "Den HEOS")   # Spotify-only isn't a candidate
        self.assertIsNone(cli.find_target(devs, "sonos")[0])


@unittest.skipUnless(shutil.which("openssl"), "needs openssl to make a test certificate")
class StreamEndToEnd(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=fake-cast", "-keyout", f"{cls.tmp}/k.pem", "-out", f"{cls.tmp}/c.pem"],
                       check=True, capture_output=True)

    def _run(self, targets, source, seconds=1.5):
        stop = threading.Event()
        threading.Timer(seconds, stop.set).start()
        statuses = []
        failures = stream.run(targets, stop, source=source, on_status=statuses.append)
        time.sleep(0.3)
        return statuses, failures

    @staticmethod
    def _cast_target(fake, name="Fake Nest"):
        return stream.Target(name, "googlecast", "127.0.0.1", port=fake.port)

    def test_streams_live_wav_and_stops_cleanly(self):
        fake = FakeCast(self.tmp)
        fake.start()
        statuses, _ = self._run([self._cast_target(fake)], silence())
        self.assertEqual(fake.load["media"]["streamType"], "LIVE")
        self.assertEqual(fake.load["media"]["contentType"], "audio/wav")
        self.assertEqual(fake.fetched[:4], b"RIFF")
        self.assertEqual(len(fake.fetched), 44 + stream.CHUNK)
        self.assertIn("STOP", fake.events)
        self.assertTrue(any("Streaming" in s for s in statuses))

    def test_routes_system_audio_and_puts_it_back(self):
        """Runs the real PipeWire capture against fake pactl/parec and checks the routing."""
        bindir = Path(self.tmp) / "bin"
        bindir.mkdir(exist_ok=True)
        log = Path(self.tmp) / "pactl.log"
        log.write_text("")
        (bindir / "pactl").write_text(f"""#!/bin/sh
echo "$@" >> {log}
case "$*" in
  "get-default-sink") echo alsa_output.hdmi ;;
  "load-module"*) echo 42 ;;
  "list short sinks") printf '1\\talsa_output.hdmi\\n2\\tspeakerbridge\\n' ;;
  "list short sink-inputs") printf '7\\t1\\tclient\\n' ;;
  "list short modules") printf '' ;;
esac
""")
        (bindir / "parec").write_text("#!/bin/sh\nexec cat /dev/zero\n")
        for f in ("pactl", "parec"):
            (bindir / f).chmod((bindir / f).stat().st_mode | stat.S_IEXEC)
        old = os.environ["PATH"]
        os.environ["PATH"] = f"{bindir}:{old}"
        try:
            fake = FakeCast(self.tmp)
            fake.start()
            self._run([self._cast_target(fake)], None)
        finally:
            os.environ["PATH"] = old
        calls = log.read_text().splitlines()
        self.assertIn("set-default-sink speakerbridge", calls)
        self.assertIn("move-sink-input 7 speakerbridge", calls)
        self.assertIn("set-default-sink alsa_output.hdmi", calls)
        self.assertEqual(calls[-1], "unload-module 42")
        self.assertEqual(fake.fetched[:4], b"RIFF")

    def test_streams_to_a_dlna_speaker_and_stops_it(self):
        fake = FakeDlna()
        try:
            statuses, failures = self._run([fake.target()], silence())
        finally:
            fake.close()
        self.assertEqual(fake.actions, ["Stop", "SetAVTransportURI", "Play", "Stop"])
        self.assertEqual(fake.fetched[:4], b"RIFF")
        self.assertEqual(len(fake.fetched), 44 + stream.CHUNK)
        self.assertEqual(failures, [])
        self.assertTrue(any("Streaming over DLNA" in s for s in statuses))

    def test_mixed_brands_play_the_same_stream_at_once(self):
        cast_fake, dlna_fake = FakeCast(self.tmp), FakeDlna()
        cast_fake.start()
        try:
            statuses, failures = self._run([self._cast_target(cast_fake), dlna_fake.target()], silence())
        finally:
            dlna_fake.close()
        self.assertEqual(failures, [])
        self.assertEqual(cast_fake.fetched[:4], b"RIFF")
        self.assertEqual(dlna_fake.fetched[:4], b"RIFF")
        self.assertEqual(cast_fake.load["media"]["contentId"], dlna_fake.uri)   # one shared stream
        self.assertIn("STOP", cast_fake.events)
        self.assertEqual(dlna_fake.actions[-1], "Stop")
        self.assertTrue(any(s.startswith("Fake Nest: Streaming") for s in statuses))
        self.assertTrue(any(s.startswith("Fake Denon: Streaming") for s in statuses))
        self.assertTrue(any("buffer differently" in s for s in statuses))

    def test_one_speaker_failing_does_not_stop_the_others(self):
        cast_fake, bad = FakeCast(self.tmp), FakeDlna(reject="SetAVTransportURI")
        cast_fake.start()
        try:
            statuses, failures = self._run([self._cast_target(cast_fake), bad.target("Old Receiver")], silence())
        finally:
            bad.close()
        self.assertEqual(len(failures), 1)
        self.assertIn("Old Receiver", failures[0])
        self.assertIn("Not supported", failures[0])
        self.assertEqual(cast_fake.fetched[:4], b"RIFF")
        self.assertIn("STOP", cast_fake.events)

    def test_raises_when_no_speaker_can_play(self):
        bad = FakeDlna(reject="Play")
        try:
            with self.assertRaisesRegex(stream.StreamError, "rejected Play"):
                self._run([bad.target()], silence())
        finally:
            bad.close()


if __name__ == "__main__":
    unittest.main()
