# Security

Please report vulnerabilities privately using GitHub's **Security → Report a vulnerability** on this repository, and don't post details publicly. Only the latest version receives fixes.

## How the audio reaches the speaker
Speakers fetch audio from your device over plain HTTP on your local network. That is how Google Cast and DLNA speakers work, so SpeakerBridge can't encrypt it.

- **Linux stream:** the live stream is served at an unguessable address that changes every run and is given to the speaker over Cast's TLS connection. Another device on your Wi-Fi that finds the open port gets "not found" without the address. This is access control, **not encryption**: someone who can capture your Wi-Fi traffic while you stream can hear it. Only stream on a network you trust, and don't stream calls over a shared network.
- **Cast connection:** the app talks to Cast speakers over TLS but does not check the speaker's certificate (Cast devices use Google's own device certificates). A device on your network that impersonates a speaker could therefore see the stream address. Same advice: use a network you trust.
- **Android test chime:** a short generated chime is served on your Wi-Fi only while a test is running. It contains no personal data.
- Android allows plain-HTTP traffic because speakers describe themselves over HTTP on the local network.

## Not done yet
There is no encryption of the audio and no check of the speaker's identity. These are limits of the speaker protocols, not choices; they are listed so you can decide where to use the app.
