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

from speakerbridge import cast, cli, dlna, drift, store, stream  # noqa: E402
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
        self.ready = threading.Event()

    def start(self, url):
        threading.Thread(target=self._play, args=(url,), daemon=True).start()
        self.ready.wait(5)   # a real speaker reports PLAYING once it has data

    def _play(self, url):
        try:
            with OPEN(url, timeout=5) as r:
                head, pos, quiet = r.read(44), 0, 10 ** 9
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
        <controlURL>upnp/av</controlURL></service>
    </serviceList></device>
</root>"""


class FakeDlna:
    """Serves a description and an AVTransport control URL; on Play it fetches the stream."""

    def __init__(self, reject: str = "", player=None, whole_seconds=True):
        self.actions, self.reject = [], reject
        self.player, self.whole_seconds = player or FakePlayer(), whole_seconds
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
                if action == "Play":
                    fake.player.start(fake.uri)
                reply = b""
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
        fake, dlna_fake = FakeCast(self.tmp), FakeDlna()
        try:
            fake.start()
            statuses, _ = self._run([self._cast_target(fake), dlna_fake.target()], None)
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
            statuses, failures = self._run([fake.target()], silence())
        finally:
            fake.close()
        self.assertEqual([a for a in fake.actions if a != "GetPositionInfo"],
                         ["Stop", "SetAVTransportURI", "Play", "Stop"])
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
        cast_url, dlna_url = cast_fake.load["media"]["contentId"], dlna_fake.uri
        self.assertEqual(cast_url.replace("/0/", "/1/"), dlna_url)   # one server, a URL (and delay) each
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
            statuses, _ = self._run([nest, heos], silence())
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
            heard = [h for h in pl.heard if h - pl.t_start > 3]   # after the starting point is taken
            intervals.append((heard[-1] - heard[0]) / (len(heard) - 1))
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
                statuses, failures = self._run([fake.target()], silence(), seconds=2)
        finally:
            fake.close()
        self.assertEqual(failures, [])
        self.assertTrue(any("doesn't report how far" in s for s in statuses), statuses)
        polls = fake.actions.count("GetPositionInfo")
        self.assertLess(polls, 20)   # it stops asking

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
