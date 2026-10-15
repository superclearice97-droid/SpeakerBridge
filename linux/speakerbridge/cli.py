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


if __name__ == "__main__":
    sys.exit(main())
