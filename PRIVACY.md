# Privacy

SpeakerBridge has no account, no server of its own, no analytics and no crash reporting. It talks only to devices on your own network. Nothing is sent to the people who make SpeakerBridge.

## What it does on your network
- **Finds speakers:** it sends discovery requests (mDNS and SSDP/UPnP) on your Wi-Fi and reads each device's description (brand, model, supported protocols). Those descriptions stay on your device.
- **Plays sound on a speaker you choose:** the Android app plays a short chime it generates itself; the Linux app plays this computer's audio. Both serve the sound from your own device, over your local network, to the speaker you picked.

## What it stores on your device
A small list of the speakers you marked as **Verified** (the ones you confirmed play audio): in the app's private storage on Android, and in `~/.config/speakerbridge` on Linux. On Android, app backup is switched on, so Android may include that list in your device backup.

## Android permissions
Internet and network/Wi-Fi state (to reach speakers on your network), and the multicast switch (to receive discovery answers). The app does not ask for your microphone, location, contacts or files.

## The Linux app and what it can hear
When you press **Stream this computer's audio here**, **everything this computer plays goes to the speaker** (music, videos, and the other side of a call). Stop the stream when you don't want that. See SECURITY.md for who else on your network could listen in.
