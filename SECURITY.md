# Security notes

SpeakerBridge talks to devices on your home network. These are its current limits; please read them before
using it on a network you do not control.

## Reporting a vulnerability

Please do not open a public issue. Use GitHub's private reporting:
**Security → Report a vulnerability** on this repository.

## What leaves your computer or phone

- **Linux streaming** serves everything the computer plays as an HTTP audio stream on the LAN address that faces the
  speaker. Google Cast speakers can only fetch it over plain HTTP, so **the audio is not encrypted**. The address
  contains a random 128-bit token, so devices that were not given it cannot just connect, but anyone who can
  capture traffic on the network could hear it. Do not stream on shared, public or untrusted Wi-Fi, and stop the
  stream when you are done (stopping restores your normal audio output).
- **Android test sound** serves a short generated chime from the phone on the Wi-Fi address while a test runs.
  It contains no personal audio.
- **Discovery** sends mDNS and SSDP queries on the local network only and refuses device descriptions from
  addresses outside it.
- The Android app allows cleartext HTTP because speakers publish their UPnP descriptions that way on the LAN.

## Known gaps

- Cast groups and stereo pairs are fetched by a speaker the app does not control, so the stream cannot be limited
  to one source address.
- The code contains no calls to internet services; everything it connects to is on the local network.
