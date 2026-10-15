"""Compatibility database: turns raw adverts into an honest device status."""
from __future__ import annotations

import json
import os
from functools import lru_cache
from pathlib import Path

from .model import Device

_HERE = Path(__file__).resolve().parent
_CANDIDATES = [
    os.environ.get("SPEAKERBRIDGE_COMPAT", ""),
    str(_HERE / "data" / "compatibility.json"),                                  # installed copy
    str(_HERE.parent.parent / "shared" / "compatibility" / "compatibility.json"),  # repo checkout
]


@lru_cache(maxsize=1)
def load() -> dict:
    for p in _CANDIDATES:
        if p and Path(p).is_file():
            with open(p, encoding="utf-8") as f:
                return json.load(f)
    raise FileNotFoundError("compatibility.json not found; set SPEAKERBRIDGE_COMPAT")


def mdns_types() -> dict[str, str]:
    """{"_googlecast._tcp": "googlecast", ...}"""
    return {p["discovery"]["mdns"]: key
            for key, p in load()["protocols"].items() if "mdns" in p["discovery"]}


def protocol_info(key: str) -> dict:
    return load()["protocols"].get(key, {"label": key, "role": "control", "sync_tier": 3, "notes": ""})


def guess_brand(*texts: str) -> str:
    blob = " ".join(t for t in texts if t).lower() + " "
    for hint in load()["brand_hints"]:
        if any(k in blob for k in hint["keywords"]):
            return hint["brand"]
    return ""


def looks_like_non_speaker(*texts: str) -> bool:
    blob = " ".join(t for t in texts if t).lower()
    return any(h in blob for h in load()["not_speaker_hints"])


def classify(dev: Device, verified_keys: set[str]) -> Device:
    """Set dev.status, dev.sync_tier and dev.notes. Discovery never implies playback."""
    protos = load()["protocols"]
    streamers = [k for k in dev.protocols if protos.get(k, {}).get("role") == "stream"]
    dev.notes = []
    dev.sync_tier = min((protocol_info(k)["sync_tier"] for k in dev.protocols), default=3)

    if dev.key in verified_keys:
        dev.status = "verified"
    elif dev.is_cast_group:
        dev.status = "group"
        dev.notes.append("This group already plays in sync across its members. SpeakerBridge can "
                         "target it as one speaker once Cast streaming lands (v0.2).")
    elif streamers:
        dev.status = "ready_to_test"
    elif dev.looks_like_non_speaker and not dev.protocols:
        dev.status = "not_speaker"
    elif dev.protocols:
        dev.status = "control_only"
    else:
        dev.status = "not_speaker"

    for k in dev.protocols:
        note = protocol_info(k).get("notes")
        if note:
            dev.notes.append(f"{protocol_info(k)['label']}: {note}")

    if "googlecast" in dev.protocols and dev.manufacturer and dev.manufacturer != "Google":
        dev.notes.insert(0, f"{dev.manufacturer} device with Chromecast built-in: it can join a "
                            "Google Home speaker group with your Nest speakers for native sync.")
    if dev.status == "control_only" and dev.manufacturer in ("Sony", "Vizio"):
        dev.notes.insert(0, "No audio-streaming protocol was advertised. Check the soundbar's settings "
                            "for 'Chromecast built-in' or 'AirPlay' and make sure it is enabled.")
    return dev
