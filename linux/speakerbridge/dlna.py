"""Minimal DLNA / UPnP AVTransport sender (standard library only).

Tells a renderer to play one URL. Mirrors the Android DlnaPlayer, so speakers that
only offer DLNA (many Denon/HEOS, Yamaha, LG, Samsung and older Sony models) can
take SpeakerBridge audio too.
"""
from __future__ import annotations

import ipaddress
import re
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from urllib.parse import urljoin, urlparse

from .ssdp import _strip_ns

_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))   # LAN only, never via a proxy


class DlnaError(Exception):
    pass


def _require_lan(url: str) -> None:
    try:
        addr = ipaddress.ip_address(urlparse(url).hostname or "")
    except ValueError:
        raise DlnaError("the speaker's address is not on your local network") from None
    if not (addr.is_private or addr.is_link_local):
        raise DlnaError("the speaker's address is not on your local network")


def service(xml_text: str, location: str, kind: str) -> tuple[str, str] | None:
    """(serviceType, absolute controlURL) of the first `kind` service in a description, or None."""
    root = ET.fromstring(xml_text)
    base = next((el.text.strip() for el in root.iter() if _strip_ns(el.tag) == "URLBase" and el.text), "")
    for svc in (el for el in root.iter() if _strip_ns(el.tag) == "service"):
        fields = {_strip_ns(c.tag): (c.text or "").strip() for c in svc}
        if kind in fields.get("serviceType", "") and fields.get("controlURL"):
            return fields["serviceType"], urljoin(base or location, fields["controlURL"])
    return None


def av_transport(xml_text: str, location: str) -> tuple[str, str]:
    """(serviceType, absolute controlURL) of the first AVTransport service in a description."""
    found = service(xml_text, location, "AVTransport")
    if not found:
        raise DlnaError("this speaker doesn't offer DLNA playback (no AVTransport service)")
    return found


def order(sinks: list[str]) -> list[str]:
    """The order to try stream forms in (stream.Form names), given what the speaker says it plays.
    Raw L16 is the one form every DLNA audio player must accept, so it goes first unless the
    speaker lists WAV and not L16; the forms that declare a length come last."""
    mimes = [s.split(":")[2].lower() for s in sinks if s.count(":") >= 2]
    l16 = any(m.startswith("audio/l16") for m in mimes)
    wav = any(m.startswith(("audio/wav", "audio/x-wav", "audio/wave")) for m in mimes)
    if wav and not l16:
        return ["WAV", "L16", "WAV with a length", "L16 with a length"]
    return ["L16", "WAV", "L16 with a length", "WAV with a length"]


def _tag(xml: str, name: str) -> str | None:
    m = re.search(rf"<(?:\w+:)?{name}(?:\s[^>]*)?>(.*?)</(?:\w+:)?{name}>", xml, re.S)
    if not m:
        return None
    return (m.group(1).strip().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"')
            .replace("&apos;", "'").replace("&amp;", "&"))


def _esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


class DlnaRenderer:
    def __init__(self, location: str):
        _require_lan(location)
        with _OPENER.open(location, timeout=3) as r:
            xml = r.read(512 * 1024).decode("utf-8", "replace")
        self.service, self.control = av_transport(xml, location)
        self.connection_manager = service(xml, location, "ConnectionManager")
        _require_lan(self.control)
        if self.connection_manager:
            _require_lan(self.connection_manager[1])

    def soap(self, action: str, args: str = "", via: tuple[str, str] | None = None) -> str:
        kind, control = via or (self.service, self.control)
        instance = "" if via else "<InstanceID>0</InstanceID>"
        body = ('<?xml version="1.0" encoding="utf-8"?>'
                '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
                's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
                f'<u:{action} xmlns:u="{kind}">{instance}{args}</u:{action}>'
                '</s:Body></s:Envelope>').encode()
        req = urllib.request.Request(control, data=body, method="POST", headers={
            "Content-Type": 'text/xml; charset="utf-8"', "SOAPACTION": f'"{kind}#{action}"'})
        try:
            with _OPENER.open(req, timeout=5) as r:
                return r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as exc:
            err = exc.read().decode("utf-8", "replace")
            reason = re.search(r"<errorDescription>(.*?)</errorDescription>", err)
            raise DlnaError(f"the speaker rejected {action} "
                            f"({reason.group(1) if reason else f'HTTP {exc.code}'})") from None
        except OSError as exc:
            raise DlnaError(f"couldn't reach the speaker ({exc})") from None

    def sinks(self) -> list[str]:
        """What the speaker says it can play (protocolInfo strings), or [] if it won't say."""
        if not self.connection_manager:
            return []
        try:
            reply = self.soap("GetProtocolInfo", via=self.connection_manager)
        except DlnaError:
            return []
        return [x.strip() for x in (_tag(reply, "Sink") or "").split(",") if x.strip()]

    def transport(self) -> tuple[str, str] | None:
        """(CurrentTransportState, CurrentTransportStatus), e.g. ("PLAYING", "OK"); None if it won't say."""
        try:
            reply = self.soap("GetTransportInfo")
        except DlnaError:
            return None
        state = _tag(reply, "CurrentTransportState")
        return (state, _tag(reply, "CurrentTransportStatus") or "") if state else None

    def set_uri(self, url: str, content_type: str, features: str, title: str) -> None:
        """Hands the speaker a URL to play; raises DlnaError if it refuses (e.g. a type it can't play)."""
        didl = ('<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
                'xmlns:dc="http://purl.org/dc/elements/1.1/" '
                'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
                f'<item id="0" parentID="-1" restricted="1"><dc:title>{_esc(title)}</dc:title>'
                '<upnp:class>object.item.audioItem.musicTrack</upnp:class>'
                f'<res protocolInfo="http-get:*:{content_type}:{features}">{_esc(url)}</res></item></DIDL-Lite>')
        self.soap("SetAVTransportURI",
                  f"<CurrentURI>{_esc(url)}</CurrentURI><CurrentURIMetaData>{_esc(didl)}</CurrentURIMetaData>")

    def play(self, url: str, content_type: str, title: str) -> None:
        self.stop()   # some renderers only accept a new URL from an idle transport
        self.set_uri(url, content_type, "*", title)
        self.soap("Play", "<Speed>1</Speed>")

    def position(self) -> tuple[float, bool] | None:
        """(seconds played, whether it only reports whole seconds), or None if it won't say."""
        try:
            m = re.search(r"<RelTime>\s*(\d+):(\d\d):(\d\d)(\.\d+)?\s*</RelTime>", self.soap("GetPositionInfo"))
        except DlnaError:
            return None
        if not m:
            return None   # NOT_IMPLEMENTED or missing
        h, mi, sec, frac = m.groups()
        return int(h) * 3600 + int(mi) * 60 + int(sec) + float(frac or 0), frac is None

    def stop(self) -> None:
        """Best effort."""
        try:
            self.soap("Stop")
        except DlnaError:
            pass
