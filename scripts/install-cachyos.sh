#!/usr/bin/env bash
# Install SpeakerBridge for the current user on CachyOS / Arch.
# Usage:  ./scripts/install-cachyos.sh            (install)
#         ./scripts/install-cachyos.sh --uninstall
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$HOME/.local/share/speakerbridge"
BIN="$HOME/.local/bin"
APPS="$HOME/.local/share/applications"

if [[ "${1:-}" == "--uninstall" ]]; then
  rm -rf "$DEST" "$BIN/speakerbridge" "$BIN/speakerbridge-scan" "$APPS/speakerbridge.desktop"
  echo "SpeakerBridge removed (your verified list in ~/.config/speakerbridge was kept)."
  exit 0
fi

echo "==> Installing system packages (python, pyside6, pipewire tools)"
pkgs=()
for p in python pyside6 pipewire pipewire-pulse; do
  pacman -Qi "$p" &>/dev/null || pkgs+=("$p")
done
if ((${#pkgs[@]})); then
  sudo pacman -S --needed "${pkgs[@]}"
else
  echo "    already installed"
fi

echo "==> Copying app to $DEST"
rm -rf "$DEST"
mkdir -p "$DEST" "$BIN" "$APPS"
cp -r "$REPO/linux/speakerbridge" "$DEST/"
mkdir -p "$DEST/speakerbridge/data"
cp "$REPO/shared/compatibility/compatibility.json" "$DEST/speakerbridge/data/"
find "$DEST" -name '__pycache__' -prune -exec rm -rf {} +

cat > "$BIN/speakerbridge" <<EOF
#!/usr/bin/env bash
PYTHONPATH="$DEST" exec python3 -m speakerbridge "\$@"
EOF
cat > "$BIN/speakerbridge-scan" <<EOF
#!/usr/bin/env bash
PYTHONPATH="$DEST" exec python3 -m speakerbridge.cli "\$@"
EOF
chmod +x "$BIN/speakerbridge" "$BIN/speakerbridge-scan"

cat > "$APPS/speakerbridge.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=SpeakerBridge
Comment=Find and group Wi-Fi speakers from any brand
Exec=$BIN/speakerbridge
Icon=audio-speakers
Terminal=false
Categories=AudioVideo;Audio;Network;
EOF

echo
echo "Done. Open 'SpeakerBridge' from your app menu, or run:"
echo "    speakerbridge          (desktop app)"
echo "    speakerbridge-scan     (terminal scanner)"
case ":$PATH:" in *":$BIN:"*) ;; *) echo "Note: add $BIN to your PATH to use the commands above.";; esac
