"""speakerbridge-scan: find Wi-Fi speakers from the terminal."""
from __future__ import annotations

import argparse
import json
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


def find_cast_target(devices, wanted: str):
    """Picks a Cast device by key, exact name or a unique part of its name."""
    casts = [d for d in devices if "googlecast" in d.protocols]
    w = wanted.strip().lower()
    for test in (lambda d: d.key.lower() == w, lambda d: d.name.lower() == w, lambda d: w in d.name.lower()):
        hits = [d for d in casts if test(d)]
        if len(hits) == 1:
            return hits[0], casts
        if len(hits) > 1:
            return None, hits
    return None, casts


def stream_main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        prog="speakerbridge-stream",
        description="Send everything this computer plays to a Google Cast speaker, "
                    "speaker group or stereo pair. Press Ctrl+C to stop and switch audio back.")
    ap.add_argument("speaker", nargs="?", help="speaker name (or part of it) or device key")
    ap.add_argument("--format", choices=["wav", "mp3"], default="wav",
                    help="wav (default, lossless) or mp3 (needs ffmpeg; try it if wav won't play)")
    ap.add_argument("--timeout", type=float, default=4.0, help="seconds to scan (default 4)")
    args = ap.parse_args(argv)

    import threading
    from . import stream

    devices = scanner.scan(args.timeout)
    target, options = find_cast_target(devices, args.speaker or "") if args.speaker else (None, [
        d for d in devices if "googlecast" in d.protocols])
    if target is None:
        if args.speaker and options and any(args.speaker.lower() in d.name.lower() for d in options):
            print(f"More than one speaker matches '{args.speaker}'. Be more specific:", file=sys.stderr)
        elif args.speaker:
            print(f"No Cast speaker matches '{args.speaker}'.", file=sys.stderr)
        print("Cast speakers found:" if options else "No Cast speakers found.", file=sys.stderr)
        for d in options:
            print(f"  {d.name}{'  (group)' if d.is_cast_group else ''}   key: {d.key}", file=sys.stderr)
        return 1

    port = target.protocols["googlecast"].port or 8009
    print(f"Streaming to {target.name} ({target.ip}). Press Ctrl+C to stop.")
    print("Note: Cast speakers buffer a second or two, so this suits music, not lip-synced video yet.")
    stop = threading.Event()
    try:
        stream.run(target.ip, port, stop, fmt=args.format)
    except KeyboardInterrupt:
        print("\nStopping… audio is switching back to this computer.")
    except stream.StreamError as exc:
        print(f"Couldn't stream: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
