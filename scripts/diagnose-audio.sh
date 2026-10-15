#!/usr/bin/env bash
# Checks the things that most often stop speakers from being discovered.
# Safe to run: it only reads system state.

ok()   { printf '  \e[32m✔\e[0m %s\n' "$*"; }
warn() { printf '  \e[33m!\e[0m %s\n' "$*"; }
bad()  { printf '  \e[31m✘\e[0m %s\n' "$*"; }

echo "Network"
route=$(ip -4 route show default 2>/dev/null | head -1)
if [[ -n "$route" ]]; then
  iface=$(awk '{for(i=1;i<NF;i++) if($i=="dev") print $(i+1)}' <<<"$route")
  addr=$(ip -4 -o addr show dev "$iface" | awk '{print $4}' | head -1)
  ok "Default interface: $iface ($addr)"
  [[ "$iface" == wl* ]] && ok "Connected over Wi-Fi" || warn "Not on Wi-Fi ($iface). That's fine if Ethernet is on the same network as the speakers."
  if ip link show "$iface" | grep -q MULTICAST; then ok "Multicast enabled on $iface"; else bad "Multicast is off on $iface (discovery needs it)"; fi
else
  bad "No default route; are you connected to a network?"
fi
if ip -4 route | grep -qE '^(10|172|192)\..* dev (tun|wg|tailscale)'; then
  warn "A VPN route is active. Some VPNs block local discovery; try disconnecting it."
fi

echo; echo "Firewall"
if systemctl is-active --quiet firewalld 2>/dev/null; then
  warn "firewalld is running. If scans find nothing, allow discovery with:"
  echo "      sudo firewall-cmd --permanent --add-service=mdns --add-service=ssdp && sudo firewall-cmd --reload"
elif systemctl is-active --quiet ufw 2>/dev/null; then
  warn "ufw is running. If scans find nothing, allow discovery with:"
  echo "      sudo ufw allow 5353/udp && sudo ufw allow 1900/udp"
else
  ok "No firewalld/ufw service active"
fi

echo; echo "Discovery services"
if systemctl is-active --quiet avahi-daemon 2>/dev/null; then
  ok "avahi-daemon running (not required, but its tools help debugging)"
  if command -v avahi-browse >/dev/null; then
    echo "    Cast devices Avahi can see:"
    timeout 4 avahi-browse -rpt _googlecast._tcp 2>/dev/null | awk -F';' '$1=="=" {print "      - "$4" ("$8")"}' | sort -u
  fi
else
  warn "avahi-daemon not running (optional). SpeakerBridge does its own discovery."
fi

echo; echo "Audio (needed from v0.2 for streaming)"
if command -v pw-cli >/dev/null && pw-cli info 0 >/dev/null 2>&1; then
  ok "PipeWire running"
else
  bad "PipeWire not reachable. Install with: sudo pacman -S pipewire pipewire-pulse wireplumber"
fi
if command -v pactl >/dev/null; then
  if pactl list short modules 2>/dev/null | grep -q raop-discover; then
    ok "PipeWire AirPlay (RAOP) discovery module loaded"
  else
    warn "AirPlay discovery module not loaded (optional). Try: pactl load-module module-raop-discover"
  fi
fi

echo; echo "Router tips if nothing shows up"
echo "  - Phone/PC and speakers must be on the same Wi-Fi (not a guest network)."
echo "  - Turn off 'AP isolation' / 'client isolation' in the router."
echo "  - Mesh systems sometimes block multicast between bands; enable 'IGMP snooping' or 'multicast enhancement' if offered."
