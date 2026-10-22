"""speakerbridge-scan: find Wi-Fi speakers from the terminal."""
from __future__ import annotations

import argparse
import json
import re
import sys

from . import __version__, compat, scanner, store

STATUS_SHORT = {
    "verified": "VERIFIED",
    "group": "CAST GROUP",
    "ready_to_test": "READY TO TEST",
    "control_only": "CONTROL ONLY",
    "not_speaker": "NOT A SPEAKER",
}


def _proto_labels(dev) -> str:
    out = []
    for k, hit in dev.protocols.items():
        label = compat.protocol_info(k)["label"]
        if k == "airplay" and hit.details.get("airplay2"):
            label = "AirPlay 2"
        out.append(label)
    return ", ".join(out) or "-"


def print_table(devices, show_all: bool) -> None:
    shown = [d for d in devices if show_all or d.status != "not_speaker"]
    hidden = len(devices) - len(shown)
    if not shown:
        print("No speakers found.")
        print("Run scripts/diagnose-audio.sh to check multicast, firewall and Wi-Fi isolation.")
    for d in shown:
        print(f"\n{d.name or '(unnamed)'}  [{STATUS_SHORT.get(d.status, d.status)}]")
        print(f"  Brand/model : {d.manufacturer or '?'} {d.model}".rstrip())
        print(f"  Address     : {d.ip}")
        print(f"  Protocols   : {_proto_labels(d)}")
        print(f"  Device key  : {d.key}")
        for n in d.notes[:2]:
            print(f"  Note        : {n}")
    if hidden:
        print(f"\n({hidden} non-speaker device(s) hidden; use --all to show)")
    print("\nDiscovery shows what a device advertises. 'Verified' only appears after you "
          "confirm playback with: speakerbridge-scan --verify KEY")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="speakerbridge-scan",
                                 description="Discover Wi-Fi speakers on your network.")
    ap.add_argument("--timeout", type=float, default=4.0, help="seconds to listen (default 4)")
    ap.add_argument("--interface", metavar="IP", help="local IP of the network interface to scan from")
    ap.add_argument("--json", action="store_true", help="print machine-readable JSON")
    ap.add_argument("--all", action="store_true", help="include routers, TVs' media servers, etc.")
    ap.add_argument("--verify", metavar="KEY", help="record that playback works on this device")
    ap.add_argument("--unverify", metavar="KEY", help="remove a verified record")
    ap.add_argument("--version", action="version", version=f"SpeakerBridge {__version__}")
    args = ap.parse_args(argv)

    if args.unverify:
        store.unmark_verified(args.unverify)
        print(f"Removed {args.unverify}")
        return 0

    devices = scanner.scan(args.timeout, args.interface)

    if args.verify:
        match = next((d for d in devices if d.key == args.verify), None)
        if not match:
            print(f"No device with key {args.verify} found in this scan.", file=sys.stderr)
            return 1
        store.mark_verified(match.key, match.name, ",".join(match.protocols))
        print(f"Marked {match.name} as verified.")
        return 0

    if args.json:
        json.dump({"version": __version__, "devices": [d.to_json() for d in devices]},
                  sys.stdout, indent=2, default=str)
        print()
    else:
        print_table(devices, args.all)
    return 0


def find_target(devices, wanted: str):
    """Picks a speaker SpeakerBridge can stream to (Cast or DLNA) by key, exact name or a
    unique part of its name. Returns (device, None) or (None, candidates to suggest)."""
    from .stream import target_for
    streamable = [d for d in devices if target_for(d)]
    w = wanted.strip().lower()
    for test in (lambda d: d.key.lower() == w, lambda d: d.name.lower() == w, lambda d: w in d.name.lower()):
        hits = [d for d in streamable if test(d)]
        if len(hits) == 1:
            return hits[0], None
        if len(hits) > 1:
            return None, hits
    return None, streamable


def stream_main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        prog="speakerbridge-stream",
        description="Send everything this computer plays to one or more speakers of any brand "
                    "(Google Cast speakers, groups and stereo pairs, and DLNA / UPnP speakers), "
                    "all at once. Press Ctrl+C to stop and switch audio back.")
    ap.add_argument("speakers", nargs="*", metavar="speaker",
                    help="speaker name (or part of it) or device key; name several to play on all of them")
    ap.add_argument("--format", choices=["wav", "mp3"], default="wav",
                    help="wav (default, lossless) or mp3 (needs ffmpeg; try it if wav won't play)")
    ap.add_argument("--timeout", type=float, default=4.0, help="seconds to scan (default 4)")
    lineup = ap.add_mutually_exclusive_group()
    lineup.add_argument("--calibrate", action="store_true",
                        help="play clicks on the speakers and line them up by ear; the delays are saved "
                             "and used every time you stream to them")
    lineup.add_argument("--auto-calibrate", action="store_true",
                        help="line the speakers up automatically using this computer's microphone "
                             "(asks first; the recording is never saved or sent anywhere)")
    args = ap.parse_args(argv)

    import threading
    from . import stream

    devices = scanner.scan(args.timeout)
    chosen = []
    for wanted in args.speakers:
        target, options = find_target(devices, wanted)
        if target is None:
            if options and any(wanted.lower() in d.name.lower() for d in options):
                print(f"More than one speaker matches '{wanted}'. Be more specific:", file=sys.stderr)
            else:
                print(f"No speaker SpeakerBridge can stream to matches '{wanted}'.", file=sys.stderr)
            _print_streamable(options or [], sys.stderr)
            return 1
        if target not in chosen:
            chosen.append(target)
    if not chosen:
        _print_streamable([d for d in devices if stream.target_for(d)], sys.stderr)
        return 1

    targets = [stream.target_for(d) for d in chosen]
    saved = store.delays()
    for t in targets:
        t.delay_ms = saved.get(t.key, 0)
    if args.calibrate:
        return calibrate(targets)
    if args.auto_calibrate:
        return auto_calibrate(targets)

    names = ", ".join(f"{t.name} ({t.host}{f', sync delay {t.delay_ms} ms' if t.delay_ms else ''})"
                      for t in targets)
    print(f"Streaming to {names}. Press Ctrl+C to stop.")
    print("Note: speakers buffer a second or two, so this suits music, not lip-synced video yet.")
    stop = threading.Event()
    try:
        failures = stream.run(targets, stop, fmt=args.format)
    except KeyboardInterrupt:
        print("\nStopping… audio is switching back to this computer.")
        return 0
    except stream.StreamError as exc:
        print(f"Couldn't stream: {exc}", file=sys.stderr)
        return 1
    return 1 if failures else 0


CALIBRATE_HELP = """\
Playing a click once a second on every speaker (each one takes a few seconds to start).
Stand where you will listen. Two clicks close together mean the speakers are out of line:
add delay to the speaker that clicks FIRST until you hear one click. Not sure which is
first? Add 100 ms to one; if the gap gets wider, take it off again and add it to the other.

  "2 +50"  adds 50 ms to speaker 2     "2 -50"  takes 50 ms off     "2 =400"  sets it
  Enter shows the delays.  "q" saves and stops.  Ctrl+C stops without saving."""


def delays_text(targets) -> str:
    return "\n".join(f"  {n}  {t.name:<28} {t.delay_ms:>5} ms" for n, t in enumerate(targets, 1))


def apply_command(line: str, targets) -> str:
    """Handles one calibration command ("2 +50", "2 -50", "2 =400"); returns what to print."""
    from .stream import MAX_DELAY_MS
    if not line.strip():
        return delays_text(targets)
    m = re.fullmatch(r"\s*(\d+)\s*([-+=])\s*(\d+)\s*(?:ms)?\s*", line)
    if not m or not 1 <= int(m[1]) <= len(targets):
        return f'Type a speaker number (1-{len(targets)}) and a change, e.g. "2 +50", "2 -50" or "2 =400".'
    t, v = targets[int(m[1]) - 1], int(m[3])
    new = v if m[2] == "=" else t.delay_ms + (v if m[2] == "+" else -v)
    t.delay_ms = max(0, min(MAX_DELAY_MS, new))
    return f"  {t.name}: {t.delay_ms} ms"


def calibrate(targets) -> int:
    import threading
    from . import stream
    if len(targets) < 2:
        print("Calibration lines speakers up with each other: name at least two.", file=sys.stderr)
        return 1
    stop, result = threading.Event(), {}

    def play():
        try:
            result["failures"] = stream.run(targets, stop, source=stream.clicks(),
                                            on_status=lambda m: print(f"  ({m})"))
        except stream.StreamError as exc:
            result["error"] = exc

    th = threading.Thread(target=play, daemon=True)
    th.start()
    print(CALIBRATE_HELP)
    print(delays_text(targets))
    try:
        while th.is_alive():
            line = input("> ")
            if line.strip().lower() in ("q", "quit"):
                break
            print(apply_command(line, targets))
    except (EOFError, KeyboardInterrupt):
        stop.set()
        th.join()
        print("\nStopped. Delays not saved.")
        return 1
    stop.set()
    th.join()
    if "error" in result:
        print(f"Couldn't play the clicks: {result['error']}", file=sys.stderr)
        return 1
    for t in targets:
        store.save_delay(t.key, t.delay_ms)
    print("Saved. These delays are used every time you stream to these speakers:")
    print(delays_text(targets))
    return 0


def auto_calibrate(targets, answer=input, recorder=None) -> int:
    """Lines the speakers up with the microphone, after asking."""
    from . import mic
    if len(targets) < 2:
        print("Calibration lines speakers up with each other: name at least two.", file=sys.stderr)
        return 1
    print(mic.CONSENT)
    try:
        ok = answer("\nUse the microphone now? [y/N] ").strip().lower() in ("y", "yes")
    except EOFError:
        ok = False
    if not ok:
        print("Not using the microphone. To line them up by ear instead: speakerbridge-stream --calibrate …")
        return 1
    try:
        delays, problems = mic.calibrate(targets, on_status=lambda m: print(f"  {m}"), recorder=recorder)
    except mic.MicError as exc:
        print(f"Couldn't use the microphone: {exc}.", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nStopped. Delays not changed.")
        return 1
    if len(delays) < 2:
        print("Couldn't measure enough speakers to line them up. Delays not changed.", file=sys.stderr)
        for line in problems:
            print(f"  {line}", file=sys.stderr)
        return 1
    measured = [t for t in targets if t.key in delays]
    for t in measured:
        t.delay_ms = delays[t.key]
        store.save_delay(t.key, t.delay_ms)
    print("Saved. These delays are used every time you stream to these speakers:")
    print(delays_text(measured))
    if problems:
        print("Not measured (their delays are unchanged):")
        for line in problems:
            print(f"  {line}")
    return 0


def _print_streamable(devices, out) -> None:
    from .stream import target_for
    print("Speakers you can stream to:" if devices else "No speakers SpeakerBridge can stream to were found.",
          file=out)
    for d in devices:
        kind = "Cast group" if d.is_cast_group else (
            "Google Cast" if target_for(d).protocol == "googlecast" else "DLNA")
        print(f"  {d.name}  ({kind})   key: {d.key}", file=out)


if __name__ == "__main__":
    sys.exit(main())
