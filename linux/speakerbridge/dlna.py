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


def av_transport(xml_text: str, location: str) -> tuple[str, str]:
    """(serviceType, absolute controlURL) of the first AVTransport service in a description."""
    root = ET.fromstring(xml_text)
    base = next((el.text.strip() for el in root.iter() if _strip_ns(el.tag) == "URLBase" and el.text), "")
    for svc in (el for el in root.iter() if _strip_ns(el.tag) == "service"):
        fields = {_strip_ns(c.tag): (c.text or "").strip() for c in svc}
        if "AVTransport" in fields.get("serviceType", "") and fields.get("controlURL"):
            return fields["serviceType"], urljoin(base or location, fields["controlURL"])
    raise DlnaError("this speaker doesn't offer DLNA playback (no AVTransport service)")


def _esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


class DlnaRenderer:
    def __init__(self, location: str):
        _require_lan(location)
        with _OPENER.open(location, timeout=3) as r:
            self.service, self.control = av_transport(r.read(512 * 1024).decode("utf-8", "replace"),
                                                      location)
        _require_lan(self.control)

    def soap(self, action: str, args: str = "") -> str:
        body = ('<?xml version="1.0" encoding="utf-8"?>'
                '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
                's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
                f'<u:{action} xmlns:u="{self.service}"><InstanceID>0</InstanceID>{args}</u:{action}>'
                '</s:Body></s:Envelope>').encode()
        req = urllib.request.Request(self.control, data=body, method="POST", headers={
            "Content-Type": 'text/xml; charset="utf-8"', "SOAPACTION": f'"{self.service}#{action}"'})
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

    def play(self, url: str, content_type: str, title: str) -> None:
        didl = ('<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
                'xmlns:dc="http://purl.org/dc/elements/1.1/" '
                'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
                f'<item id="0" parentID="-1" restricted="1"><dc:title>{_esc(title)}</dc:title>'
                '<upnp:class>object.item.audioItem.musicTrack</upnp:class>'
                f'<res protocolInfo="http-get:*:{content_type}:*">{_esc(url)}</res></item></DIDL-Lite>')
        self.stop()   # some renderers only accept a new URL from an idle transport
        self.soap("SetAVTransportURI",
                  f"<CurrentURI>{_esc(url)}</CurrentURI><CurrentURIMetaData>{_esc(didl)}</CurrentURIMetaData>")
        self.soap("Play", "<Speed>1</Speed>")

    def stop(self) -> None:
        """Best effort."""
        try:
            self.soap("Stop")
        except DlnaError:
            pass
