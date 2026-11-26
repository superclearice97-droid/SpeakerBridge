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

from speakerbridge import cast, cli, dlna, drift, mic, store, stream  # noqa: E402
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


def numbered():
    """Chunk i is filled with the 32-bit number i (from 1), so a reader can tell which
    chunks arrived, which were skipped and where silence (0) was inserted."""
    i = 1
    while True:
        yield struct.pack("<I", i) * (stream.CHUNK // 4)
        i += 1
        time.sleep(0.02)


def frames(r, n_bytes):
    data = r.read(n_bytes)
    return [struct.unpack_from("<I", data, k)[0] for k in range(0, len(data) - 3, 4)]


def chunk_ids(values):
    """The chunk numbers that arrived (whole or mostly), ignoring the few faded frames."""
    from collections import Counter
    return sorted(v for v, c in Counter(values).items() if v and c >= 100)


def runs(values):
    """[(value, count), ...] of consecutive equal values."""
    out = []
    for v in values:
        if out and out[-1][0] == v:
            out[-1][1] += 1
        else:
            out.append([v, 1])
    return [tuple(x) for x in out]


OPEN = urllib.request.build_opener(urllib.request.ProxyHandler({})).open


class StreamFormTests(unittest.TestCase):
    def test_l16_is_raw_big_endian_audio_with_the_dlna_profile(self):
        srv = stream.LiveServer(numbered(), "127.0.0.1")
        try:
            with OPEN(srv.url_for(0, stream.L16), timeout=5) as r:
                self.assertEqual(r.headers["Content-Type"], "audio/L16;rate=48000;channels=2")
                self.assertIn("DLNA.ORG_PN=LPCM", r.headers["contentFeatures.dlna.org"])
                self.assertEqual(r.headers["transferMode.dlna.org"], "Streaming")
                self.assertIsNone(r.headers["Content-Length"])
                data = r.read(stream.CHUNK)
            back = stream.swapped(data)   # swapping each sample's bytes again gives the original audio
            ids = {struct.unpack_from("<I", back, k)[0] for k in range(0, len(back), 4)}
            self.assertEqual(len(ids), 1)    # no WAV header in front: the first bytes are already audio
            self.assertNotIn(0, ids)
            self.assertNotEqual(data, back)  # and it really was byte-swapped
        finally:
            srv.close()

    def test_sized_forms_declare_a_length(self):
        srv = stream.LiveServer(silence(), "127.0.0.1")
        try:
            for form in (stream.WAV_SIZED, stream.L16_SIZED):
                req = urllib.request.Request(srv.url_for(2, form), method="HEAD")
                with OPEN(req, timeout=5) as r:
                    self.assertEqual(r.headers["Content-Length"], str(stream.SIZED_LENGTH), form)
                    self.assertEqual(r.headers["Content-Type"], form.content_type)
        finally:
            srv.close()

    def test_stats_show_whether_a_speaker_is_taking_audio(self):
        srv = stream.LiveServer(silence(), "127.0.0.1")
        try:
            self.assertFalse(srv.stat(1).flowing())
            with OPEN(srv.url_for(1, stream.L16), timeout=5) as r:
                r.read(stream.CHUNK * 5)
                self.assertTrue(srv.stat(1).flowing())
                self.assertEqual(srv.stat(1).connections, 1)
            time.sleep(0.5)
            self.assertFalse(srv.stat(0).flowing())   # and another speaker's stats are its own
        finally:
            srv.close()


class LiveServerTest(unittest.TestCase):
    def test_listener_gets_header_then_audio(self):
        srv = stream.LiveServer(silence(), "127.0.0.1")
        try:
            with OPEN(srv.url, timeout=5) as r:
                self.assertEqual(r.headers["Content-Type"], "audio/wav")
                data = r.read(44 + stream.CHUNK * 3)
            self.assertEqual(data[:4], b"RIFF")
            self.assertEqual(len(data), 44 + stream.CHUNK * 3)
        finally:
            srv.close()

    def test_unknown_paths_are_refused(self):
        srv = stream.LiveServer(silence(), "127.0.0.1")
        try:
            for bad in ("/x/speakerbridge.wav", "/speakerbridge.mp3", "/0/../etc/passwd"):
                with self.assertRaises(urllib.error.HTTPError, msg=bad):
                    OPEN(srv.url.replace("/speakerbridge.wav", bad), timeout=5)
        finally:
            srv.close()

    def test_unknown_speaker_numbers_still_get_the_stream(self):
        targets = [stream.Target("Only", "googlecast", "127.0.0.1", delay_ms=50)]
        srv = stream.LiveServer(silence(), "127.0.0.1", delay_ms=lambda n: targets[n].delay_ms)
        try:
            with OPEN(srv.url_for(99), timeout=5) as r:
                self.assertEqual(len(r.read(44 + stream.CHUNK)), 44 + stream.CHUNK)
        finally:
            srv.close()

    def test_closing_ends_open_streams_and_stops_an_endless_source(self):
        pulled = []

        def endless():
            while True:
                pulled.append(1)
                yield bytes(stream.CHUNK)
                time.sleep(0.02)
        srv = stream.LiveServer(endless(), "127.0.0.1")
        r = OPEN(srv.url_for(0), timeout=5)
        r.read(44 + stream.CHUNK)
        srv.close()
        t0 = time.monotonic()
        while r.read(stream.CHUNK):   # a speaker that ignored Stop still gets cut off
            self.assertLess(time.monotonic() - t0, 2)
        n = len(pulled)
        time.sleep(0.2)
        self.assertLessEqual(len(pulled) - n, 1)   # and nothing keeps pulling the clicks

    def test_delay_holds_audio_back_from_the_start(self):
        delays = {0: 0, 1: 300}
        srv = stream.LiveServer(silence(), "127.0.0.1", delay_ms=delays.get)
        try:
            for n, low, high in ((0, 0.0, 0.2), (1, 0.3, 0.6)):
                with OPEN(srv.url_for(n), timeout=5) as r:
                    r.read(44)
                    t0 = time.monotonic()
                    r.read(stream.CHUNK)
                    self.assertTrue(low <= time.monotonic() - t0 < high, (n, time.monotonic() - t0))
        finally:
            srv.close()

    def test_raising_the_delay_mid_stream_inserts_silence(self):
        delays = {0: 0}
        srv = stream.LiveServer(numbered(), "127.0.0.1", delay_ms=delays.get)
        try:
            with OPEN(srv.url_for(0), timeout=5) as r:
                r.read(44)
                before = frames(r, stream.CHUNK * 5)
                delays[0] = 100
                after = frames(r, stream.CHUNK * 10)
        finally:
            srv.close()
        self.assertNotIn(0, before)
        silences = [c for v, c in runs(after) if v == 0]
        self.assertEqual(len(silences), 1)
        # exactly 100 ms, give or take the 2 ms fade either side rounding to zero
        self.assertLessEqual(abs(silences[0] - 100 * stream.BYTES_PER_MS // 4), 2 * stream.FADE_FRAMES)
        ids = chunk_ids(before + after)
        self.assertEqual(ids, list(range(ids[0], ids[-1] + 1)))   # nothing skipped

    def test_lowering_the_delay_mid_stream_skips_audio(self):
        delays = {0: 200}
        srv = stream.LiveServer(numbered(), "127.0.0.1", delay_ms=delays.get)
        try:
            with OPEN(srv.url_for(0), timeout=5) as r:
                r.read(44)
                before = frames(r, stream.CHUNK * 3)
                delays[0] = 100
                after = frames(r, stream.CHUNK * 10)
        finally:
            srv.close()
        silences = [c for v, c in runs(before + after) if v == 0]
        self.assertTrue(all(c <= 2 * stream.FADE_FRAMES for c in silences))   # no silence added
        seen = chunk_ids(before + after)
        missing = [i for i in range(seen[0], seen[-1]) if i not in seen]
        self.assertEqual(len(missing), 100 // 20)   # 100 ms = 5 chunks skipped

    def test_click_track(self):
        import itertools
        first, second = itertools.islice(stream.clicks(), 2)
        self.assertEqual((len(first), len(second)), (stream.CHUNK, stream.CHUNK))
        self.assertTrue(any(first))
        self.assertFalse(any(second))


# ------------------------------------------------------------ fake speakers
BPS = stream.BYTES_PER_MS * 1000


class FakePlayer:
    """Plays a stream the way a real speaker does: starts `buffer_s` after the first audio
    arrives, then runs on its own clock, `ppm` fast (+) or slow (-). Logs when each click
    (sound after 100 ms of silence) would be heard."""

    def __init__(self, buffer_s=0.3, ppm=0.0):
        self.buffer_s, self.rate = buffer_s, 1 + ppm * 1e-6
        self.fetched, self.heard, self.t_start = b"", [], None
        self.content_type = self.content_length = ""
        self.ready = threading.Event()
        self._response = None

    def start(self, url):
        threading.Thread(target=self._play, args=(url,), daemon=True).start()
        self.ready.wait(5)   # a real speaker reports PLAYING once it has data

    def _play(self, url):
        try:
            with OPEN(url, timeout=5) as r:
                self._response = r
                self.content_type = r.headers.get("Content-Type", "")
                self.content_length = r.headers.get("Content-Length", "")
                head = r.read(44) if self.content_type.startswith("audio/wav") else b""   # raw L16 has no header
                pos, quiet = 0, 10 ** 9
                while True:
                    b = r.read(stream.CHUNK)
                    if not b:
                        return
                    if self.t_start is None:
                        self.t_start = time.monotonic() + self.buffer_s
                        self.fetched = head + b
                        self.ready.set()
                    if any(b):
                        i = next(k for k, x in enumerate(b) if x) & ~3
                        if quiet + i >= 100 * stream.BYTES_PER_MS:
                            self.heard.append(self.t_start + (pos + i) / (BPS * self.rate))
                        quiet = len(b) - len(b.rstrip(b"\0"))
                    else:
                        quiet += len(b)
                    pos += len(b)
        except OSError:
            pass

    def stop(self):
        """Hangs up, as a speaker does when told to stop."""
        try:
            if self._response is not None:
                self._response.fp.raw._sock.shutdown(socket.SHUT_RDWR)
        except (AttributeError, OSError):
            pass

    def position(self):
        """Seconds of the stream played so far, by the speaker's own clock."""
        return max(0.0, time.monotonic() - self.t_start) * self.rate if self.t_start else 0.0


class FakeCast(threading.Thread):
    """Accepts one sender, behaves like the Default Media Receiver, records what happened."""

    def __init__(self, certdir, player=None):
        super().__init__(daemon=True)
        self.player = player or FakePlayer()
        self.ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.ctx.load_cert_chain(f"{certdir}/c.pem", f"{certdir}/k.pem")
        self.srv = socket.socket()
        self.srv.bind(("127.0.0.1", 0))
        self.srv.listen(1)
        self.port = self.srv.getsockname()[1]
        self.events = []

    @property
    def fetched(self):
        return self.player.fetched

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
                self.player.start(p["media"]["contentId"])
                send("web-1", cast.NS_MEDIA, {"type": "MEDIA_STATUS",
                                              "status": [{"playerState": "PLAYING", "mediaSessionId": 1}]})
            elif p.get("type") == "GET_STATUS":
                send("web-1", cast.NS_MEDIA, {"type": "MEDIA_STATUS", "status": [{
                    "playerState": "PLAYING", "mediaSessionId": 1, "currentTime": self.player.position()}]})


# ------------------------------------------------------------ fake DLNA renderer
DESCRIPTION = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <URLBase>http://127.0.0.1:{port}/</URLBase>
  <device><friendlyName>Fake Denon</friendlyName>
    <serviceList>
      <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <controlURL>/rc</controlURL></service>
      <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <controlURL>upnp/av</controlURL></service>{cm}
    </serviceList></device>
</root>"""
CM = """
      <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <controlURL>/upnp/cm</controlURL></service>"""


class FakeDlna:
    """Serves a description and an AVTransport control URL; on Play it fetches the stream.
    By default it plays anything. `plays(mime, sized)` makes it picky, like a TV that takes the
    command and opens its player but only really plays some forms of the stream; `rejects(mime)`
    makes it refuse a type outright; `sinks` is what it says it plays (and adds a
    ConnectionManager to its description)."""

    def __init__(self, reject: str = "", player=None, whole_seconds=True, sinks=None,
                 plays=lambda mime, sized: True, rejects=lambda mime: False):
        self.actions, self.reject = [], reject
        self.player, self.whole_seconds = player or FakePlayer(), whole_seconds
        self.sinks, self.plays, self.rejects = sinks, plays, rejects
        self.offered, self.state, self.uri, self.mime = [], "NO_MEDIA_PRESENT", "", ""
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                body = DESCRIPTION.format(port=fake.port, cm=CM if fake.sinks is not None else "").encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                body = self.rfile.read(int(self.headers["Content-Length"])).decode()
                action = self.headers["SOAPACTION"].strip('"').split("#")[1]
                fake.actions.append(action)
                if self.path == "/upnp/cm" and action == "GetProtocolInfo":
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(f"<s:Envelope><s:Body><Sink>{fake.sinks}</Sink></s:Body></s:Envelope>".encode())
                    return
                refuse = action == "SetAVTransportURI" and fake.rejects(
                    body.split("http-get:*:")[1].split(":")[0] if "http-get:*:" in body else "")
                if self.path != "/upnp/av" or action == fake.reject or refuse:
                    if action == "SetAVTransportURI":
                        fake.offered.append(body.split("http-get:*:")[1].split(":DLNA")[0].split(":*")[0])
                    self.send_response(500)
                    self.end_headers()
                    self.wfile.write(b"<errorDescription>Not supported</errorDescription>")
                    return
                if action == "SetAVTransportURI":
                    fake.uri = body.split("<CurrentURI>")[1].split("</CurrentURI>")[0]
                    fake.mime = body.split("http-get:*:")[1].split(":DLNA")[0].split(":*")[0]
                    fake.offered.append(fake.mime + ("|sized" if "/sized/" in fake.uri else "|live"))
                    fake.state = "STOPPED"
                if action == "Play":
                    if fake.plays(fake.mime, "/sized/" in fake.uri):
                        fake.state = "PLAYING"
                        threading.Thread(target=fake.player.start, args=(fake.uri,), daemon=True).start()
                    else:   # opens its player, looks at the stream, can't play it, gives up
                        fake.state = "TRANSITIONING"

                        def give_up():
                            try:
                                with OPEN(fake.uri, timeout=3) as r:
                                    r.read(2000)
                            except OSError:
                                pass
                            time.sleep(0.3)
                            fake.state = "STOPPED"
                        threading.Thread(target=give_up, daemon=True).start()
                if action == "Stop":
                    fake.player.stop()
                    if fake.state != "NO_MEDIA_PRESENT":
                        fake.state = "STOPPED"
                reply = b""
                if action == "GetTransportInfo":
                    reply = (f"<s:Envelope><s:Body><CurrentTransportState>{fake.state}</CurrentTransportState>"
                             "<CurrentTransportStatus>OK</CurrentTransportStatus></s:Body></s:Envelope>").encode()
                if action == "GetPositionInfo":
                    pos = fake.player.position()
                    whole = int(pos)
                    rel = f"{whole // 3600}:{whole // 60 % 60:02d}:{whole % 60:02d}"
                    if not fake.whole_seconds:
                        rel += f"{pos - whole:.3f}"[1:]
                    reply = f"<s:Envelope><s:Body><RelTime>{rel}</RelTime></s:Body></s:Envelope>".encode()
                self.send_response(200)
                self.end_headers()
                self.wfile.write(reply)

            def log_message(self, *a):
                pass

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.httpd.server_address[1]
        self.location = f"http://127.0.0.1:{self.port}/desc.xml"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    @property
    def fetched(self):
        return self.player.fetched

    def target(self, name="Fake Denon"):
        return stream.Target(name, "dlna_renderer", "127.0.0.1", location=self.location)

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


class DlnaTests(unittest.TestCase):
    def test_finds_av_transport_and_resolves_against_urlbase(self):
        xml = DESCRIPTION.format(port=1234, cm=CM)
        self.assertEqual(dlna.av_transport(xml, "http://127.0.0.1:1234/desc.xml"),
                         ("urn:schemas-upnp-org:service:AVTransport:1", "http://127.0.0.1:1234/upnp/av"))
        self.assertEqual(dlna.service(xml, "http://127.0.0.1:1234/desc.xml", "ConnectionManager"),
                         ("urn:schemas-upnp-org:service:ConnectionManager:1", "http://127.0.0.1:1234/upnp/cm"))
        self.assertIsNone(dlna.service(DESCRIPTION.format(port=1, cm=""), "http://127.0.0.1:1/", "ConnectionManager"))

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


class CalibrationTests(unittest.TestCase):
    def test_commands_change_one_speakers_delay_within_limits(self):
        ts = [stream.Target("Nest", "googlecast", "h"), stream.Target("HEOS", "dlna_renderer", "h")]
        cli.apply_command("2 +50", ts)
        cli.apply_command("2+25ms", ts)
        self.assertEqual([t.delay_ms for t in ts], [0, 75])
        cli.apply_command("2 -500", ts)
        cli.apply_command("1 =99999", ts)
        self.assertEqual([t.delay_ms for t in ts], [stream.MAX_DELAY_MS, 0])
        self.assertIn("speaker number", cli.apply_command("3 +50", ts))
        self.assertIn("speaker number", cli.apply_command("louder", ts))
        self.assertIn("HEOS", cli.apply_command("", ts))

    def test_delays_are_saved_per_speaker(self):
        old = os.environ.get("XDG_CONFIG_HOME")
        os.environ["XDG_CONFIG_HOME"] = tempfile.mkdtemp()
        try:
            store.save_delay("googlecast:abc", 250)
            store.save_delay("dlna_renderer:uuid:1", 0)
            self.assertEqual(store.delays(), {"googlecast:abc": 250})
            store.save_delay("googlecast:abc", 0)
            self.assertEqual(store.delays(), {})
            self.assertEqual(store.verified_keys(), set())   # separate from verified records
        finally:
            if old is None:
                del os.environ["XDG_CONFIG_HOME"]
            else:
                os.environ["XDG_CONFIG_HOME"] = old


class SimSpeaker:
    """Simulated time: what LiveServer does for one listener (send each 20 ms chunk when
    it's due, apply nudges) plus a speaker whose clock is `ppm` off. Fast enough to run an
    hour of playback in about a second."""

    def __init__(self, ppm, buffer_s=1.5, whole_seconds=False, monitor=True):
        self.tl, self.rate = stream.Timeline(), 1 + ppm * 1e-6
        self.buffer_s, self.whole_seconds = buffer_s, whole_seconds
        self.said = []
        self.mon = drift.DriftMonitor(lambda: self.tl, self.said.append, BPS) if monitor else None

    def latency_at(self, t, t_start):
        """True capture-to-ear latency at time t (None while inserted silence plays)."""
        c = self.tl.capture_time(int((t - t_start) * self.rate * BPS))
        return None if c is None else t - c

    def run(self, seconds, poll_s):
        import random as rnd
        rng = rnd.Random(7)
        t, c_next, skip_s, t_start = 0.0, 0.0, 0.0, None
        next_poll, latencies = 1.0, []
        while t < seconds:
            t += 0.02
            while c_next + 0.02 <= t:          # what LiveServer sends (no planned delay here)
                c_next += skip_s
                skip_s = 0.0
                self.tl.add(stream.CHUNK, c_next)
                c_next += 0.02
                if t_start is None:
                    t_start = t + self.buffer_s
                n = self.tl.take_nudge()
                if n > 0:
                    self.tl.add(n * stream.BYTES_PER_MS, None)
                elif n < 0:
                    skip_s = -n / 1000
                if n:
                    self.tl.changed(n)
            if t_start is None or t < t_start:
                continue
            played = (t - t_start) * self.rate
            if self.mon and t >= next_poll:
                rtt = rng.uniform(0.002, 0.02)
                report = int(played) if self.whole_seconds else played
                self.mon.sample(t + rtt / 2, report, whole_seconds=self.whole_seconds, half_rtt=rtt / 2)
                next_poll = t + (rng.uniform(0.5, 1.5) if self.whole_seconds else 2.0)
            if round(t * 50) % 50 == 0:          # once a second
                lat = self.latency_at(t, t_start)
                if lat is not None:
                    latencies.append((t, lat))
        return latencies


class DriftTests(unittest.TestCase):
    def _spread_ms(self, latencies, after=60):
        late = [lat for t, lat in latencies if t > after]
        return (max(late) - min(late)) * 1000

    def test_without_monitoring_an_hour_drifts_apart(self):
        lat = SimSpeaker(ppm=50, monitor=False).run(3600, 2.0)
        self.assertGreater(self._spread_ms(lat), 170)   # 50 ppm = 180 ms an hour

    def test_cast_style_reports_keep_a_fast_or_slow_speaker_in_line_for_an_hour(self):
        for ppm in (50, -50, 200, -200):
            sim = SimSpeaker(ppm=ppm)
            lat = sim.run(3600, 2.0)
            self.assertLess(self._spread_ms(lat), 2 * drift.PRECISE_THRESHOLD_MS + 5, ppm)
            self.assertTrue(sim.mon.enabled, sim.said)
            word = "fast" if ppm > 0 else "slow"
            self.assertTrue(sim.said and all(word in m for m in sim.said), (ppm, sim.said[:3]))

    def test_whole_second_dlna_reports_still_keep_it_in_line(self):
        for ppm in (50, -200):
            sim = SimSpeaker(ppm=ppm, whole_seconds=True)
            lat = sim.run(3600, 1.0)
            # whole seconds only pin the latency down to a few tens of ms; uncorrected this
            # would spread 180 ms (50 ppm) or 720 ms (200 ppm) in the hour
            self.assertLess(self._spread_ms(lat), 80, ppm)
            self.assertTrue(sim.mon.enabled, sim.said)

    def test_a_perfect_clock_is_left_alone(self):
        sim = SimSpeaker(ppm=0, whole_seconds=True)
        sim.run(1800, 1.0)
        self.assertEqual(sim.said, [])

    def test_a_speaker_that_does_not_advance_is_not_corrected(self):
        tl, said = stream.Timeline(), []
        mon = drift.DriftMonitor(lambda: tl, said.append, BPS)
        for k in range(1000):
            tl.add(stream.CHUNK, k * 0.02)
        for i in range(20):
            mon.sample(1.0 + i, 0.0)
        self.assertFalse(mon.enabled)
        self.assertEqual(tl.take_nudge(), 0)
        self.assertIn("doesn't report", said[-1])

    def test_jumpy_precise_reports_are_never_acted_on(self):
        import random as rnd
        rng = rnd.Random(3)
        tl, said = stream.Timeline(), []
        mon = drift.DriftMonitor(lambda: tl, said.append, BPS)
        for k in range(1500):
            tl.add(stream.CHUNK, k * 0.02)
        t = 2.0
        for i in range(60):   # a receiver whose currentTime only updates now and then
            mon.sample(t, t - 1.0 - rng.uniform(0, 0.8))
            t += 0.3
        self.assertEqual(tl.take_nudge(), 0)
        self.assertFalse(mon.enabled)
        self.assertIn("too jumpy", said[-1])

    def test_an_implausible_jump_stops_monitoring(self):
        tl, said = stream.Timeline(), []
        mon = drift.DriftMonitor(lambda: tl, said.append, BPS)
        for k in range(1500):
            tl.add(stream.CHUNK, k * 0.02)
        t = 2.0
        for i in range(12):                    # steady 1.0 s latency: becomes the baseline
            mon.sample(t, t - 1.0)
            t += 1
        for i in range(5):                     # then it claims to be 5 s behind
            mon.sample(t, t - 6.0)
            t += 1
        self.assertFalse(mon.enabled)
        self.assertEqual(tl.take_nudge(), 0)
        self.assertIn("jumped", said[-1])


# ------------------------------------------------------------ microphone calibration
def mono_click():
    from array import array
    a = array("h", stream.click_chunk())
    return a[::2]   # left channel


def room_recording(seconds, clicks, rate=mic.MIC_RATE, seed=5):
    """Hiss plus each (time, gain) click, with a quieter echo 37 ms later."""
    import random as rnd
    from array import array
    rng = rnd.Random(seed)
    out = array("h", (rng.randint(-60, 60) for _ in range(int(seconds * rate))))
    click = mono_click()
    for t, gain in clicks:
        for when, g in ((t, gain), (t + 0.037, gain * 0.4)):
            i = int(when * rate)
            for k, x in enumerate(click):
                if 0 <= i + k < len(out):
                    out[i + k] = max(-32768, min(32767, out[i + k] + int(x * g)))
    return out


class FakeMic:
    """Stands in for MicRecorder: 'hears' the FakePlayers' clicks (loud and quiet ones), plus
    hiss and echoes, through a microphone that adds its own fixed lag."""
    LAG = 0.023

    def __init__(self, players_and_gains):
        self.players = players_and_gains
        self.cleared = 0

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        pass

    def between(self, t_from, t_to):
        events = [(h - t_from + self.LAG, g) for pl, g in self.players for h in pl.heard if t_from <= h <= t_to]
        return room_recording(t_to - t_from, events), t_from

    def clear(self):
        self.cleared += 1


class MicTests(unittest.TestCase):
    def test_hears_loud_and_quiet_clicks_to_a_millisecond_despite_echoes(self):
        truth = [0.5, 1.37, 2.03, 3.11, 4.4]
        rec = room_recording(5, [(t, 1.0 if i % 2 else 0.05) for i, t in enumerate(truth)])
        found = mic.onsets(rec, mic.MIC_RATE, 100.0)
        self.assertEqual(len(found), len(truth), found)
        for f, t in zip(found, truth):
            self.assertAlmostEqual(f - 100.0, t, delta=0.0015)

    def test_hiss_alone_is_not_a_click(self):
        self.assertEqual(mic.onsets(room_recording(3, []), mic.MIC_RATE, 0.0), [])

    def _sent(self, n=16):
        t, out = 10.0, []
        for k in range(n):
            out.append(t)
            t += mic.GAPS_S[k % len(mic.GAPS_S)]
        return out

    def test_pattern_match_survives_missed_and_stray_clicks(self):
        import random as rnd
        rng = rnd.Random(2)
        sent = self._sent()
        heard = [s + 1.234 + rng.uniform(-0.002, 0.002) for i, s in enumerate(sent) if i % 3]   # a third missed
        heard += [sent[0] + rng.uniform(0, 12) for _ in range(6)]                               # door slams
        self.assertAlmostEqual(mic.match(sent, sorted(heard)), 1.234, delta=0.003)

    def test_no_match_without_the_pattern(self):
        sent = self._sent()
        heard = [sent[0] + 0.5 + k * 0.7 for k in range(12)]   # evenly spaced: not our pattern
        self.assertIsNone(mic.match(sent, heard))

    def test_needs_a_yes_before_using_the_microphone(self):
        ts = [stream.Target("A", "googlecast", "127.0.0.1"), stream.Target("B", "googlecast", "127.0.0.1")]
        used = []

        class Spy(FakeMic):
            def __enter__(self):
                used.append(1)
                return self
        for reply in ("", "n", "no thanks"):
            self.assertEqual(cli.auto_calibrate(ts, answer=lambda _: reply, recorder=Spy([])), 1)

        def eof(_):
            raise EOFError
        self.assertEqual(cli.auto_calibrate(ts, answer=eof, recorder=Spy([])), 1)
        self.assertEqual(used, [])


@unittest.skipUnless(shutil.which("openssl"), "needs openssl to make a test certificate")
class StreamEndToEnd(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=fake-cast", "-keyout", f"{cls.tmp}/k.pem", "-out", f"{cls.tmp}/c.pem"],
                       check=True, capture_output=True)

    def _run(self, targets, source, seconds=1.5, on_route=lambda key, form: None):
        stop = threading.Event()
        threading.Timer(seconds, stop.set).start()
        statuses = []
        failures = stream.run(targets, stop, source=source, on_status=statuses.append, on_route=on_route)
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
        fake, dlna_fake = FakeCast(self.tmp), FakeDlna()
        try:
            fake.start()
            statuses, _ = self._run([self._cast_target(fake), dlna_fake.target()], None, seconds=4)
        finally:
            os.environ["PATH"] = old
            dlna_fake.close()
        self.assertTrue(any("buffer differently" in s for s in statuses))
        self.assertTrue(any("Fake Denon: Streaming over DLNA. Anything this computer plays" in s for s in statuses))
        calls = log.read_text().splitlines()
        self.assertIn("set-default-sink speakerbridge", calls)
        self.assertIn("move-sink-input 7 speakerbridge", calls)
        self.assertIn("set-default-sink alsa_output.hdmi", calls)
        self.assertEqual(calls[-1], "unload-module 42")
        self.assertEqual(fake.fetched[:4], b"RIFF")

    def test_streams_to_a_dlna_speaker_and_stops_it(self):
        fake = FakeDlna()
        try:
            statuses, failures = self._run([fake.target()], silence(), seconds=4)
        finally:
            fake.close()
        self.assertEqual([a for a in fake.actions if a in ("Stop", "SetAVTransportURI", "Play")],
                         ["Stop", "SetAVTransportURI", "Play", "Stop"])
        self.assertTrue(fake.player.content_type.startswith("audio/L16"))   # raw L16 first: what TVs take
        self.assertEqual(len(fake.fetched), stream.CHUNK)                    # no file header
        self.assertEqual(failures, [])
        self.assertTrue(any("Streaming over DLNA" in s for s in statuses))

    def test_mixed_brands_play_the_same_stream_at_once(self):
        cast_fake, dlna_fake = FakeCast(self.tmp), FakeDlna()
        cast_fake.start()
        try:
            statuses, failures = self._run([self._cast_target(cast_fake), dlna_fake.target()], silence(), seconds=4)
        finally:
            dlna_fake.close()
        self.assertEqual(failures, [])
        self.assertEqual(cast_fake.fetched[:4], b"RIFF")
        self.assertEqual(len(dlna_fake.fetched), stream.CHUNK)
        cast_url, dlna_url = cast_fake.load["media"]["contentId"], dlna_fake.uri
        self.assertEqual(cast_url.split("/0/")[0], dlna_url.split("/1/")[0])   # one server, a URL (and delay) each
        self.assertIn("STOP", cast_fake.events)
        self.assertEqual(dlna_fake.actions[-1], "Stop")
        self.assertTrue(any(s.startswith("Fake Nest: Streaming") for s in statuses))
        self.assertTrue(any(s.startswith("Fake Denon: Streaming") for s in statuses))
        self.assertFalse(any("buffer differently" in s for s in statuses))   # only for computer audio

    def test_saved_delay_reaches_the_right_speaker(self):
        cast_fake, dlna_fake = FakeCast(self.tmp), FakeDlna()
        cast_fake.start()
        nest, heos = self._cast_target(cast_fake), dlna_fake.target()
        heos.delay_ms = 400
        try:
            statuses, _ = self._run([nest, heos], silence(), seconds=3)
        finally:
            dlna_fake.close()
        self.assertIn("/0/", cast_fake.load["media"]["contentId"])
        self.assertIn("/1/", dlna_fake.uri)

    def _drift_run(self, targets, players, seconds):
        """Streams clicks with drift timings sped up (a 2% clock error instead of 50 ppm, so
        readings a second or two apart really do differ by tens of ms)."""
        from unittest import mock
        fast = dict(CAST_POLL_S=0.1, DLNA_POLL_S=(0.03, 0.08), WARMUP_S=1.0, MAX_PPM=50000,
                    PRECISE_MAX_SPREAD_S=0.1)
        with mock.patch.multiple(drift, **fast):
            statuses, failures = self._run(targets, stream.clicks(), seconds=seconds)
        self.assertEqual(failures, [])
        intervals = []
        for pl in players:
            # After the starting point is taken. A correction that skips audio can land on a click
            # and drop it, so clicks are counted by elapsed time rather than by how many were heard.
            heard = [h for h in pl.heard if h - pl.t_start > 3]
            span = heard[-1] - heard[0]
            intervals.append(span / round(span))
        return statuses, intervals

    def test_drift_is_corrected_on_a_live_cast_speaker(self):
        fast_nest = FakePlayer(ppm=20000)   # plays 2% fast: 20 ms a second
        fake = FakeCast(self.tmp, fast_nest)
        fake.start()
        statuses, (interval,) = self._drift_run([self._cast_target(fake)], [fast_nest], 9)
        self.assertIn("GET_STATUS", fake.events)
        self.assertAlmostEqual(interval, 1.0, delta=0.006)   # uncorrected it would be 0.980
        self.assertTrue(any("Nudged +" in s and "fast" in s for s in statuses), statuses)

    def test_drift_is_corrected_on_a_live_dlna_speaker(self):
        slow = FakePlayer(ppm=-20000)
        fake = FakeDlna(player=slow, whole_seconds=False)
        try:
            statuses, (interval,) = self._drift_run([fake.target()], [slow], 9)
        finally:
            fake.close()
        self.assertAlmostEqual(interval, 1.0, delta=0.006)   # uncorrected it would be 1.020
        self.assertTrue(any("Nudged -" in s and "slow" in s for s in statuses), statuses)

    def test_a_dlna_speaker_that_wont_say_where_it_is_is_left_alone(self):
        fake = FakeDlna(reject="GetPositionInfo")
        try:
            from unittest import mock
            with mock.patch.multiple(drift, DLNA_POLL_S=(0.05, 0.1), WARMUP_S=0.5):
                statuses, failures = self._run([fake.target()], silence(), seconds=4.5)
        finally:
            fake.close()
        self.assertEqual(failures, [])
        self.assertTrue(any("doesn't report how far" in s for s in statuses), statuses)
        polls = fake.actions.count("GetPositionInfo")
        self.assertLess(polls, 20)   # it stops asking

    def test_microphone_calibration_lines_mixed_speakers_up(self):
        from unittest import mock
        old = os.environ.get("XDG_CONFIG_HOME")
        os.environ["XDG_CONFIG_HOME"] = tempfile.mkdtemp()
        quick = dict(GAPS_S=(0.30, 0.50, 0.40, 0.62, 0.36, 0.54), MAX_LATENCY_S=1.5, LISTEN_S=4.5)
        nest_pl, heos_pl = FakePlayer(buffer_s=0.3), FakePlayer(buffer_s=1.0)
        try:
            nest_fake, heos_fake = FakeCast(self.tmp, nest_pl), FakeDlna(player=heos_pl)
            nest_fake.start()
            nest = stream.Target("Kitchen Nest", "googlecast", "127.0.0.1", port=nest_fake.port, key="cast:n")
            heos = heos_fake.target()
            heos.key = "dlna:h"
            room = FakeMic([(nest_pl, 0.8), (heos_pl, 0.1)])   # one loud and near, one quiet and far
            with mock.patch.multiple(mic, **quick):
                rc = cli.auto_calibrate([nest, heos], answer=lambda _: "y", recorder=room)
            self.assertEqual(rc, 0)
            saved = store.delays()
            heos_fake.close()
        finally:
            if old is None:
                del os.environ["XDG_CONFIG_HOME"]
            else:
                os.environ["XDG_CONFIG_HOME"] = old
        self.assertNotIn("dlna:h", saved)                      # the slowest stays at 0
        self.assertAlmostEqual(saved["cast:n"], 700, delta=25)  # 1.0 s vs 0.3 s of buffering
        self.assertEqual(room.cleared, 2)                      # recording cleared after each speaker

        # And with those delays, the two really do play together.
        p1, p2 = FakePlayer(buffer_s=0.3), FakePlayer(buffer_s=1.0)
        f1, f2 = FakeCast(self.tmp, p1), FakeDlna(player=p2)
        f1.start()
        t1 = stream.Target("Kitchen Nest", "googlecast", "127.0.0.1", port=f1.port, delay_ms=saved["cast:n"])
        try:
            self._run([t1, f2.target()], stream.clicks(), seconds=4)
        finally:
            f2.close()
        pairs = [(a, b) for a in p1.heard for b in p2.heard if abs(a - b) < 0.5]
        self.assertGreaterEqual(len(pairs), 2)
        for a, b in pairs:
            self.assertLess(abs(a - b), 0.03)   # within 30 ms (uncalibrated: 700 ms apart)

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

    # ------------------------------------------------------------ picky DLNA speakers
    def _quick_confirm(self):
        from unittest import mock
        return mock.patch.multiple(stream, CONFIRM_TIMEOUT_S=5.0, IDLE_GRACE_S=0.8)

    def test_a_tv_that_only_plays_raw_l16_gets_it_first_time(self):
        # What LG webOS TVs are reported to need: uncompressed audio only as audio/L16.
        tv, worked = FakeDlna(plays=lambda mime, sized: mime.startswith("audio/L16")), {}
        t = tv.target("Lounge TV")
        t.key = "dlna:tv"
        try:
            with self._quick_confirm():
                statuses, failures = self._run([t], stream.clicks(), seconds=4, on_route=worked.__setitem__)
        finally:
            tv.close()
        self.assertEqual(failures, [])
        self.assertEqual(tv.offered, ["audio/L16;rate=48000;channels=2|live"])
        self.assertTrue(tv.player.heard)                                    # it really played the clicks
        self.assertTrue(any("Streaming over DLNA" in s for s in statuses), statuses)
        self.assertEqual(worked, {"dlna:tv": "L16"})

    def test_a_speaker_that_only_plays_wav_is_found_by_trying_and_tried_first_next_time(self):
        worked = {}
        first = FakeDlna(plays=lambda mime, sized: mime == "audio/wav")
        t = first.target("Den")
        t.key = "dlna:den"
        try:
            with self._quick_confirm():
                statuses, failures = self._run([t], stream.clicks(), seconds=6, on_route=worked.__setitem__)
        finally:
            first.close()
        self.assertEqual(failures, [])
        self.assertEqual(first.offered, ["audio/L16;rate=48000;channels=2|live", "audio/wav|live"])
        self.assertTrue(first.player.heard)
        self.assertEqual(worked, {"dlna:den": "WAV"})

        again = FakeDlna(plays=lambda mime, sized: mime == "audio/wav")
        t2 = again.target("Den")
        t2.key, t2.route = "dlna:den", worked["dlna:den"]
        try:
            with self._quick_confirm():
                self._run([t2], silence(), seconds=3.5)
        finally:
            again.close()
        self.assertEqual(again.offered, ["audio/wav|live"])                 # straight to what worked

    def test_a_speaker_that_needs_a_length_gets_one(self):
        picky = FakeDlna(plays=lambda mime, sized: sized)
        try:
            with self._quick_confirm():
                statuses, failures = self._run([picky.target()], silence(), seconds=8)
        finally:
            picky.close()
        self.assertEqual(failures, [])
        self.assertEqual(picky.offered[-1], "audio/L16;rate=48000;channels=2|sized")
        self.assertEqual(picky.player.content_length, str(stream.SIZED_LENGTH))

    def test_a_type_refused_outright_moves_straight_to_the_next(self):
        strict = FakeDlna(rejects=lambda mime: mime.startswith("audio/L16"))
        t0 = time.monotonic()
        try:
            with self._quick_confirm():
                statuses, failures = self._run([strict.target()], silence(), seconds=3.5)
        finally:
            strict.close()
        self.assertEqual(failures, [])
        self.assertEqual(strict.offered[-1], "audio/wav|live")
        self.assertTrue(any("Streaming over DLNA" in s for s in statuses), statuses)
        self.assertLess(time.monotonic() - t0, 6)

    def test_what_the_speaker_says_it_plays_decides_the_order(self):
        wav_only = "http-get:*:audio/mpeg:*,http-get:*:audio/x-wav:*,http-get:*:video/mp4:*"
        self.assertEqual(dlna.order(wav_only.split(","))[0], "WAV")
        self.assertEqual(dlna.order(["http-get:*:audio/L16;rate=44100;channels=2:DLNA.ORG_PN=LPCM"])[0], "L16")
        self.assertEqual(dlna.order([])[0], "L16")
        hifi = FakeDlna(sinks=wav_only)
        try:
            with self._quick_confirm():
                self._run([hifi.target()], silence(), seconds=3.5)
        finally:
            hifi.close()
        self.assertIn("GetProtocolInfo", hifi.actions)
        self.assertEqual(hifi.offered[0], "audio/wav|live")

    def test_a_speaker_that_plays_nothing_is_reported_honestly(self):
        dud = FakeDlna(plays=lambda mime, sized: False)
        try:
            with self._quick_confirm(), self.assertRaises(stream.StreamError) as caught:
                self._run([dud.target("Old Receiver")], silence(), seconds=10)
        finally:
            dud.close()
        msg = str(caught.exception)
        self.assertIn("it took the stream but didn't play it", msg)
        self.assertIn("tried L16; WAV; L16 with a length; WAV with a length", msg)
        self.assertEqual(len(dud.offered), 4)
        self.assertEqual(dud.actions[-1], "Stop")   # and its player isn't left sitting open

    def test_a_speaker_stopped_from_its_own_remote_is_noticed(self):
        tv = FakeDlna()
        stop, statuses = threading.Event(), []

        def press_stop():
            while not any("Streaming over DLNA" in s for s in statuses):
                time.sleep(0.05)
            time.sleep(1)
            tv.player.stop()
            tv.state = "STOPPED"
        threading.Thread(target=press_stop, daemon=True).start()
        threading.Timer(15, stop.set).start()
        try:
            with self._quick_confirm(), self.assertRaisesRegex(stream.StreamError, "stopped the stream"):
                stream.run([tv.target()], stop, source=silence(), on_status=statuses.append)
        finally:
            stop.set()
            tv.close()


if __name__ == "__main__":
    unittest.main()
