"""Runs mDNS + SSDP discovery and merges results into one Device per speaker."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor

from . import compat, mdns, ssdp, store
from .model import Device, ProtocolHit


def _airplay2(features: str) -> bool:
    """AirPlay feature bit 38 = buffered audio, which only AirPlay 2 receivers set."""
    try:
        parts = [int(p, 16) for p in features.split(",")]
    except ValueError:
        return False
    value = parts[0] | ((parts[1] << 32) if len(parts) > 1 else 0)
    return bool(value & (1 << 38))


def devices_from_mdns(instances: list[mdns.ServiceInstance]) -> list[Device]:
    type_map = compat.mdns_types()
    out: dict[str, Device] = {}
    for inst in instances:
        proto = type_map.get(inst.service_type)
        ip = inst.ipv4
        if not proto or not ip:
            continue
        txt = inst.txt
        name = inst.label
        if proto == "raop" and "@" in name:          # "AABBCCDDEEFF@Kitchen"
            name = name.split("@", 1)[1]
        is_group = proto == "googlecast" and txt.get("md", "").lower() == "google cast group"
        key = f"{ip}#group:{inst.instance}" if is_group else ip

        dev = out.setdefault(key, Device(ip=ip))
        dev.is_cast_group = dev.is_cast_group or is_group
        dev.raw_types.add(inst.service_type)

        details = dict(txt)
        if proto == "googlecast":
            dev.name = txt.get("fn") or dev.name or name
            dev.model = txt.get("md") or dev.model
            details["cast_id"] = txt.get("id", "")
        elif proto == "airplay":
            details["airplay2"] = _airplay2(txt.get("features", ""))
            dev.model = dev.model or txt.get("model", "")
            dev.manufacturer = dev.manufacturer or txt.get("manufacturer", "")
        dev.name = dev.name or name
        dev.add(ProtocolHit(protocol=proto, source="mdns", service_name=inst.instance,
                            port=inst.port, details=details))
    return list(out.values())


def _apply_upnp(dev: Device, u: ssdp.UpnpDevice) -> None:
    blob = " ".join(u.device_types + u.service_types + list(u.targets))
    dev.raw_types.update(u.device_types or u.targets)
    dev.name = dev.name or u.friendly_name
    dev.manufacturer = dev.manufacturer or u.manufacturer
    dev.model = dev.model or " ".join(x for x in (u.model_name, u.model_number) if x)
    details = {"location": u.location, "udn": u.udn, "server": u.server,
               "manufacturer": u.manufacturer, "model": u.model_name}
    if "MediaRenderer" in blob or "AVTransport" in blob:
        dev.add(ProtocolHit("dlna_renderer", "ssdp", u.usn, 0, details))
    if "ScalarWebAPI" in u.raw_xml:
        dev.add(ProtocolHit("sony_scalar", "ssdp", u.usn, 0, {"location": u.location}))
    if compat.looks_like_non_speaker(blob, u.friendly_name, u.manufacturer):
        dev.looks_like_non_speaker = True


def _finish(dev: Device) -> None:
    for proto, field_name in (("googlecast", "cast_id"), ("airplay", "deviceid"),
                              ("raop", "deviceid"), ("dlna_renderer", "udn")):
        hit = dev.protocols.get(proto)
        if hit and hit.details.get(field_name):
            dev.device_id = f"{proto}:{hit.details[field_name]}"
            break
    if dev.is_cast_group:
        dev.device_id = dev.device_id or f"group:{dev.name}"
    guessed = compat.guess_brand(dev.manufacturer, dev.model, dev.name)
    if guessed and (not dev.manufacturer or guessed.lower() in dev.manufacturer.lower()):
        dev.manufacturer = guessed
    elif not dev.manufacturer:
        dev.manufacturer = guessed


def merge(mdns_devices: list[Device], upnp_devices: list[ssdp.UpnpDevice]) -> list[Device]:
    by_ip: dict[str, Device] = {}
    groups: list[Device] = []
    for d in mdns_devices:
        if d.is_cast_group:
            groups.append(d)
        else:
            by_ip.setdefault(d.ip, d)
    for u in upnp_devices:
        _apply_upnp(by_ip.setdefault(u.ip, Device(ip=u.ip)), u)
    devices = list(by_ip.values()) + groups
    for d in devices:
        _finish(d)
    return devices


def scan(timeout: float = 4.0, interface_ip: str | None = None) -> list[Device]:
    types = list(compat.mdns_types())
    with ThreadPoolExecutor(max_workers=2) as pool:
        f_mdns = pool.submit(mdns.browse, types, timeout, interface_ip)
        f_ssdp = pool.submit(ssdp.discover, timeout, interface_ip)
        devices = merge(devices_from_mdns(f_mdns.result()), f_ssdp.result())
    verified = store.verified_keys()
    for d in devices:
        compat.classify(d, verified)
    order = {"verified": 0, "group": 1, "ready_to_test": 2, "control_only": 3, "not_speaker": 4}
    devices.sort(key=lambda d: (order.get(d.status, 9), d.name.lower()))
    return devices
