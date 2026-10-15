"""Device model shared by the scanner, CLI and GUI.

Mirrors shared/device-model/device.schema.json so Linux and Android export
the same JSON shape.
"""
from __future__ import annotations

from dataclasses import dataclass, field, asdict


@dataclass
class ProtocolHit:
    """One protocol a device advertised, plus whatever the advert told us."""
    protocol: str                 # key in compatibility.json "protocols"
    source: str                   # "mdns" or "ssdp"
    service_name: str = ""        # mDNS instance name or SSDP USN
    port: int = 0
    details: dict = field(default_factory=dict)  # TXT records / UPnP fields


@dataclass
class Device:
    ip: str
    name: str = ""
    manufacturer: str = ""
    model: str = ""
    device_id: str = ""           # stable id when the protocol gives one
    protocols: dict[str, ProtocolHit] = field(default_factory=dict)
    raw_types: set[str] = field(default_factory=set)  # every advertised type, for diagnostics
    is_cast_group: bool = False
    looks_like_non_speaker: bool = False
    # filled by compat.classify()
    status: str = ""
    sync_tier: int = 3
    notes: list[str] = field(default_factory=list)

    @property
    def key(self) -> str:
        """Identity used for 'verified' records. Prefer a protocol id over IP,
        because DHCP can change addresses."""
        return self.device_id or f"ip:{self.ip}"

    def add(self, hit: ProtocolHit) -> None:
        existing = self.protocols.get(hit.protocol)
        if existing is None:
            self.protocols[hit.protocol] = hit
        else:
            existing.details.update({k: v for k, v in hit.details.items() if v})
            existing.port = existing.port or hit.port

    def to_json(self) -> dict:
        d = asdict(self)
        d["raw_types"] = sorted(self.raw_types)
        d["key"] = self.key
        return d
