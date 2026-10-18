"""Tests for Linux -> Cast streaming. Run: python -m unittest discover -s tests

Uses a fake Cast speaker (TLS on loopback) and fake pactl/parec scripts, so no real
speaker or PipeWire is needed.
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
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "linux"))

from speakerbridge import cast, stream  # noqa: E402


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


@unittest.skipUnless(shutil.which("openssl"), "needs openssl to make a test certificate")
class StreamEndToEnd(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=fake-cast", "-keyout", f"{cls.tmp}/k.pem", "-out", f"{cls.tmp}/c.pem"],
                       check=True, capture_output=True)

    def _run(self, fake, source, seconds=1.5):
        stop = threading.Event()
        threading.Timer(seconds, stop.set).start()
        statuses = []
        stream.run("127.0.0.1", fake.port, stop, source=source, on_status=statuses.append)
        time.sleep(0.3)
        return statuses

    def test_streams_live_wav_and_stops_cleanly(self):
        fake = FakeCast(self.tmp)
        fake.start()
        statuses = self._run(fake, silence())
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
            self._run(fake, None)
        finally:
            os.environ["PATH"] = old
        calls = log.read_text().splitlines()
        self.assertIn("set-default-sink speakerbridge", calls)
        self.assertIn("move-sink-input 7 speakerbridge", calls)
        self.assertIn("set-default-sink alsa_output.hdmi", calls)
        self.assertEqual(calls[-1], "unload-module 42")
        self.assertEqual(fake.fetched[:4], b"RIFF")


if __name__ == "__main__":
    unittest.main()
