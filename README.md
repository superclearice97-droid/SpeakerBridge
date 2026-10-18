# SpeakerBridge

Make Wi-Fi speakers from different brands (Sony, Vizio, Google Nest and others) play together, using only their built-in Wi-Fi. No adapters, no cloud, no subscription.

**Current version: 0.1 — discovery.** It finds the speakers on your network, shows which wireless audio protocols each one supports, and tells you honestly whether SpeakerBridge will be able to send audio to it. Streaming arrives in 0.2.

**Test sound (Android):** tap a speaker, then **Play Test Sound**. The phone plays a short chime on it over Google Cast or DLNA (AirPlay not yet). On a Google Home speaker group, every member plays the chime together, in sync. Once you hear it, tap **I Heard It Play!** to mark the speaker Verified. The chime is generated on the phone and served only on your Wi-Fi; nothing leaves your network.

---

## Quick start

### CachyOS / Linux

```bash
./scripts/install-cachyos.sh     # installs python + pyside6 if missing, adds an app-menu entry
speakerbridge                    # desktop app (scans automatically on launch)
speakerbridge-scan               # same scan in the terminal
speakerbridge-scan --json        # machine-readable output
```

Without installing, from the repo: `cd linux && python3 -m speakerbridge` (GUI) or `python3 -m speakerbridge scan`.

The scanner itself uses only the Python standard library. The GUI needs `pyside6` (`sudo pacman -S pyside6`).

If nothing is found, run `./scripts/diagnose-audio.sh`. It checks multicast, firewall (firewalld/ufw), VPNs, Avahi and PipeWire, and prints the router settings that most often block discovery.

### Android

**From your phone (no computer needed):** push this folder to a GitHub repository. The included workflow (`.github/workflows/android-apk.yml`) builds the APK in the cloud on every push and publishes it on the repo's **Releases** page. Open that page on your phone, tap the `.apk`, and allow installing from your browser when Android asks.

**With a computer:** open the `android/` folder in Android Studio and press **Run**, or **Build → Build APK(s)**.

Requirements: Android 8.0+ (minSdk 26), built against SDK 35. The compatibility database is bundled straight from `shared/compatibility/`, so both apps always agree.

**Motion (Android):** the camera pulls back from a close-up tile to reveal the floor on launch; the floor drifts, pans when you drag it sideways (with momentum) and slides slower than the page as you scroll; a red pulse runs across the empty cells while scanning; found speakers pop in one by one and a spotlight hops between them; tapping a tile dives the camera into it and the floor dims behind the details panel; headlines roll in, a green highlight sweeps along the intro paragraph, counters tick up, rows slide in and flash green when pressed. All of it switches off if Android's animation setting is off.

The Android design is dark and editorial: a hairline-grid header, tall condensed serif headlines, monospace captions, one red accent, and a perspective "floor" where every speaker found appears as a colour tile you can tap. Fonts (Inter, Lora, DejaVu Sans Mono) are bundled; see `android/FONTS.md`.

---

## What the statuses mean

| Status | Meaning |
|---|---|
| **Verified** | You confirmed SpeakerBridge audio plays on it (button in the app, or `speakerbridge-scan --verify KEY`). Only *you* can set this. |
| **Cast group** | A speaker group made in the Google Home app. Its members already play in perfect sync. |
| **Ready to test** | Advertises a protocol SpeakerBridge will stream to (Google Cast, AirPlay, DLNA). |
| **Control only** | Found, but only advertises control protocols (Spotify Connect, Sony/Vizio control APIs). Can't take SpeakerBridge audio as-is. |
| **Not a speaker** | Routers, media servers, etc. Hidden unless you tick "Show non-speakers". |

Finding a device is not the same as being able to play on it. That's why "Ready to test" and "Verified" are separate.

---

## The plan for mixed brands

Most recent Sony and Vizio soundbars include **Chromecast built-in**, the same protocol Nest speakers use. When they do, the strongest path is:

1. In the Google Home app, put the Sony, Vizio and Nest speakers into one **speaker group**.
2. SpeakerBridge sends your Linux or Android audio to that group as if it were one speaker.
3. Google's own sync engine keeps them aligned, with no echo.

The scanner flags this when it sees a non-Google speaker with Cast ("can join a Google Home speaker group"). If your soundbar shows up as *Control only*, check its settings for Chromecast built-in or AirPlay and turn it on.

### Music vs TV audio (you asked for both)

- **Whole-home music** works well over Cast groups and AirPlay 2. Both buffer a couple of seconds, which is invisible for music.
- **TV / movie audio** is harder. That same buffer causes lip-sync delay, and soundbars add their own processing delay. The realistic setup is: the TV's main soundbar stays wired (HDMI ARC/eARC), and the Wi-Fi speakers in other rooms get the audio via SpeakerBridge. Same-room lip-synced surround across brands over Wi-Fi isn't something these protocols allow; the app will say so per device rather than promise it.

---

## Roadmap

| Version | Goal | Status |
|---|---|---|
| 0.1 | Discovery on Linux + Android, shared compatibility database | **Done** |
| 0.2 | Linux → Google Cast (single speakers and Cast groups) via an HTTP stream of PipeWire audio; AirPlay (RAOP) via PipeWire's built-in module | Next |
| 0.3 | Multiple targets at once, saved groups, per-speaker volume | |
| 0.4 | Sync engine: delay calibration for DLNA / mixed targets, drift monitoring | |
| 0.5 | Android as an audio source (AudioPlaybackCapture, with your consent) | |
| 0.6 | Sony / Vizio control APIs for volume and input, more DLNA devices | |
| 1.0 | Stable release, APK + Linux package, docs | |

---

## Layout

```
android/                 Kotlin + Jetpack Compose app (discovery, compat, UI)
linux/speakerbridge/     Python package: mdns.py, ssdp.py, scanner.py, compat.py, cli.py, gui.py
shared/compatibility/    compatibility.json, the protocol and brand database both apps use
shared/device-model/     JSON schema for exported devices
scripts/                 install-cachyos.sh, diagnose-audio.sh
tests/                   Linux unit + loopback tests (python3 -m unittest discover -s tests)
```

## How discovery works

- **mDNS / DNS-SD:** sends one-shot queries for Cast, AirPlay, RAOP, Spotify Connect, Vizio and Sonos service types from a random port. Speakers reply directly, so SpeakerBridge never conflicts with Avahi or Android's own mDNS service.
- **SSDP / UPnP:** M-SEARCH for all devices and media renderers, then reads each device's description (LAN addresses only) to get brand, model and services, including Sony's ScalarWebAPI.
- Results are merged per IP address. Cast groups are kept separate even though one member hosts them.

## Testing

```bash
python3 -m unittest discover -s tests     # Linux: wire format, loopback mDNS/SSDP, classification
cd android && ./gradlew test               # Android: mDNS wire format + AirPlay 2 detection
```
