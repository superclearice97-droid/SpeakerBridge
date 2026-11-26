#!/usr/bin/env bash
# Makes the permanent key that signs SpeakerBridge's Android builds, and gives it to GitHub
# as two repository secrets. Run it once, on your own computer, from inside the repository.
#
# Why: Android only installs an update over an app signed with the same key. Without a
# permanent key every build from GitHub is signed with a throwaway one, so each update has
# to be uninstalled first (which also forgets your saved delays).
#
# The key is yours and must stay private: this script never prints it or commits it. Keep
# the file it leaves in ~/.config/speakerbridge/ safe (a backup is wise): if it is lost,
# the next build needs one last uninstall.
set -euo pipefail

dir="${XDG_CONFIG_HOME:-$HOME/.config}/speakerbridge"
key="$dir/android-signing.p12"
pass_file="$dir/android-signing.password"

command -v keytool >/dev/null || { echo "keytool is missing. On CachyOS: sudo pacman -S jdk-openjdk"; exit 1; }
command -v gh >/dev/null || { echo "The GitHub CLI is missing. On CachyOS: sudo pacman -S github-cli   then: gh auth login"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "Sign in to GitHub first: gh auth login"; exit 1; }

mkdir -p "$dir"
chmod 700 "$dir"
if [[ -f "$key" ]]; then
  echo "Using the key you already have: $key"
else
  umask 077
  head -c 24 /dev/urandom | base64 | tr -d '/+=' > "$pass_file"
  keytool -genkeypair -alias speakerbridge -keyalg RSA -keysize 3072 -validity 20000 \
    -dname "CN=SpeakerBridge" -storetype PKCS12 -keystore "$key" \
    -storepass:file "$pass_file" -keypass:file "$pass_file" >/dev/null
  echo "Made a new signing key: $key"
fi

base64 -w0 "$key" | gh secret set SIGNING_KEYSTORE_B64
gh secret set SIGNING_KEYSTORE_PASSWORD < "$pass_file"
echo "Done. Builds from GitHub are now signed with your key. The next one needs one last"
echo "uninstall of the old app; after that, updates install straight over it."
