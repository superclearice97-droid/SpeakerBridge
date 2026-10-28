# SpeakerBridge

Make Wi-Fi speakers from different brands (Sony, Vizio, Google Nest and others) play together, using only their built-in Wi-Fi. No adapters, no cloud, no subscription.

**Current version: 0.1 — discovery.** It finds the speakers on your network, shows which wireless audio protocols each one supports, and tells you honestly whether SpeakerBridge will be able to send audio to it. Streaming arrives in 0.2.

**Test sound (Android):** tap a speaker, then **Play Test Sound**. The phone plays a short chime on it over Google Cast or DLNA (AirPlay not yet). On a Google Home speaker group, every member plays the chime together, in sync. Once you hear it, tap **I Heard It Play!** to mark the speaker Verified. To hear several speakers at once, tick them under **Play Together** on the main screen: SpeakerBridge loads the chime on each one, then starts them all at the same moment. Different brands can still land a fraction of a second apart (each buffers differently); a Google Home group remains the tightest sync for Cast speakers. The chime is generated on the phone and served only on your Wi-Fi; nothing leaves your network.

**Stream your phone's audio (Android 10+, new):** tick one or more speakers under **Play Together**, then tap **Stream phone audio**. Android asks for the audio recording permission and then to share your screen; that's how Android lets an app capture other apps' sound. SpeakerBridge takes only the sound (never the picture, never the microphone) and serves it as a live stream on your Wi-Fi to each speaker, over Google Cast or DLNA. Then play music in any app. A "Streaming" notification shows while it runs; stop it there, with **Stop streaming**, or with Android's own stop-sharing control. Good to know:

- Apps can opt out of capture, and Android enforces that: many video apps (protected content) stream silence. SpeakerBridge doesn't work around it. While streaming, the app says whether it's sending sound or only silence.
- Android copies the sound rather than moving it, so the phone would keep playing too, a second or two ahead of the speakers. SpeakerBridge mutes the phone's media volume once music starts and unmutes it when you stop. On a phone where muting also silences what's shared, it notices within two seconds, unmutes, and says so.
- A speaker only shows "Streaming." once it's actually playing. Some TVs load the stream paused, so SpeakerBridge presses play for you; one that still won't play is reported (it may not support this kind of audio stream).
- Speakers buffer a second or two, so it suits music rather than video. Separate speakers, especially Cast and DLNA ones mixed, can be noticeably apart; the sync delay and drift correction from the Linux app aren't on Android yet. Speakers in one Google Home group stay in sync.
- It's for your own listening at home. Music and video services have their own terms about where their content may be played.

---

## Quick start

### CachyOS / Linux

```bash
./scripts/install-cachyos.sh     # installs python + pyside6 if missing, adds an app-menu entry
speakerbridge                    # desktop app (scans automatically on launch)
speakerbridge-scan               # same scan in the terminal
speakerbridge-scan --json        # machine-readable output
```

**Stream this computer's audio to speakers of different brands at once (new):**

```bash
speakerbridge-stream "Living Room"                    # name, part of a name, or device key
speakerbridge-stream "Kitchen Nest" "Sony" "Den HEOS"  # several speakers, any mix of brands
speakerbridge-stream "Living Room" --format mp3        # if a speaker won't play the default WAV stream (needs ffmpeg)
```

Or select speakers in the desktop app (Ctrl+click to pick several) and press **Stream this computer's audio here**. SpeakerBridge adds a virtual output called "SpeakerBridge", makes it the default, and moves what's already playing onto it, so everything the computer plays goes to the speakers. Stop (Ctrl+C or the button) and audio switches back to your usual output.

Each speaker gets the same stream over whichever protocol it speaks: **Google Cast** (Nest, Chromecast built-in Sony / Vizio / JBL and others, Google Home speaker groups and stereo pairs) or **DLNA / UPnP** (many Denon/HEOS, Yamaha MusicCast, LG, Samsung and older Sony speakers). If a speaker offers both, Cast is used. If one speaker can't play, the others keep going. The stream stays on your local network.

Sync, honestly: every speaker buffers a second or two, so this suits music rather than lip-synced video. Speakers inside one Google Home group stay tightly in sync. Separate speakers, especially Cast and DLNA ones mixed, start out up to a second or two apart. That's fine for different rooms; in one room you'll hear an echo until you line them up.

**Lining speakers up in one room (sync delay):**

Automatically, with this computer's microphone (it asks first, every time):

```bash
speakerbridge-stream --auto-calibrate "Kitchen Nest" "Den HEOS"
```

Put the computer roughly where you listen, set the speakers to a normal volume and keep the room quiet. Each speaker in turn plays clicks for about 15 seconds while the microphone listens; SpeakerBridge works out how late each one is and saves the delays that line them up. In the desktop app: select the speakers and press **Line up with microphone…**. They end up in line where the microphone is (sound moves about 3 ms per metre). If a speaker can't be heard, turn it up or bring the computer closer and try again.

Or by ear:

```bash
speakerbridge-stream --calibrate "Kitchen Nest" "Den HEOS"
```

Every speaker plays a click once a second. If you hear two clicks close together, add delay to the speaker that clicks first (`1 +50`, `1 -25`, `1 =400`) until you hear one click, then type `q` to save. In the desktop app, select the speakers, tick **Play sync clicks**, press Stream, and adjust each speaker's **Sync delay** box. Changes apply while it plays.

Either way, delays (0–3000 ms) are saved per speaker in `~/.config/speakerbridge/delays.json` and used every time you stream to that speaker.

Good to know: a speaker can only be held back, not sped up, so the slowest speaker stays at 0. With `--format mp3`, a delay changed mid-stream takes effect on the next stream.

**Staying in sync (drift correction):** each speaker's clock runs very slightly fast or slow, enough to slide a couple of hundred ms apart over an hour. While streaming, SpeakerBridge asks each speaker how far it has played and, when one has slid from where it started, inserts or skips a few ms of audio for that speaker (with a 2 ms fade, so it doesn't click). It's automatic. You'll see the occasional "Nudged +15 ms to stay in sync" message. How tight it holds depends on what the speaker reports:

| Speaker reports | Kept within |
|---|---|
| Google Cast (to the millisecond) | about ±15 ms |
| DLNA with fractions of a second | about ±15 ms |
| DLNA in whole seconds (most) | about ±30 ms |

A speaker that doesn't report its position, or whose reports don't add up, isn't touched: SpeakerBridge says so and leaves it alone rather than guess. Drift correction needs the default WAV stream (not `--format mp3`).

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

## Privacy

SpeakerBridge has no account, no cloud service and no analytics. Everything it does stays on your local network:

- **Discovery** asks devices on your Wi-Fi what they are; it only fetches descriptions from local addresses.
- **Streaming** serves your audio only to the speakers you chose, on your local network.
- **Saved on this computer**, in `~/.config/speakerbridge/`: `verified.json` (speakers you marked as working) and `delays.json` (sync delays). Delete them any time.
- **Android streaming** captures only the sound other apps play (Android's audio playback capture, which asks for your consent each time), keeps it in memory just long enough to send it to the speakers you picked on your Wi-Fi, and never saves or uploads it. Android requires the audio recording permission for this, but the microphone isn't used.
- **Microphone (Linux):** used only when you start microphone calibration, and only after you agree each time. The recording is kept in memory just long enough to time the clicks, cleared after each speaker, and never saved or sent anywhere.

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
| 0.2 | Linux → Google Cast (single speakers and Cast groups) via an HTTP stream of PipeWire audio; AirPlay (RAOP) via PipeWire's built-in module | Cast streaming done; AirPlay next |
| 0.3 | Multiple targets at once, saved groups, per-speaker volume | Linux: Cast + DLNA speakers at once done; saved groups and volume next |
| 0.4 | Sync engine: delay calibration for DLNA / mixed targets, drift monitoring | Linux: done (sync delay by ear or microphone, drift correction) |
| 0.5 | Android as an audio source (AudioPlaybackCapture, with your consent) | Streaming to Cast + DLNA speakers done; sync delay and drift correction next |
| 0.6 | Sony / Vizio control APIs for volume and input, more DLNA devices | |
| 1.0 | Stable release, APK + Linux package, docs | |

---

## Layout

```
android/                 Kotlin + Jetpack Compose app (discovery, compat, UI)
linux/speakerbridge/     Python package: mdns.py, ssdp.py, scanner.py, compat.py, cli.py, gui.py, cast.py, dlna.py, stream.py, drift.py, mic.py
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
python3 -m unittest discover -s tests     # Linux: wire format, loopback mDNS/SSDP, classification, Cast + DLNA streaming, sync
cd android && ./gradlew test               # Android: mDNS wire format + AirPlay 2 detection
```
