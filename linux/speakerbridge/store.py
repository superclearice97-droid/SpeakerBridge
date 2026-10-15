"""Per-user records: which devices *you* have confirmed actually play audio."""
from __future__ import annotations

import json
import os
import time
from pathlib import Path


def config_dir() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config")
    p = Path(base) / "speakerbridge"
    p.mkdir(parents=True, exist_ok=True)
    return p


def _verified_path() -> Path:
    return config_dir() / "verified.json"


def load_verified() -> dict[str, dict]:
    try:
        with open(_verified_path(), encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def verified_keys() -> set[str]:
    return set(load_verified())


def _save(data: dict) -> None:
    path = _verified_path()
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
    tmp.replace(path)


def mark_verified(key: str, name: str, protocol: str, note: str = "") -> None:
    data = load_verified()
    data[key] = {"name": name, "protocol": protocol, "note": note,
                 "verified_at": time.strftime("%Y-%m-%dT%H:%M:%S")}
    _save(data)


def unmark_verified(key: str) -> None:
    data = load_verified()
    if data.pop(key, None) is not None:
        _save(data)
