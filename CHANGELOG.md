# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the version is `0.x`,
anything may change between minor releases.

## [Unreleased]

## [0.1.0] — 2026-11-08

First public release.

### Added
- Android app (Kotlin + Jetpack Compose) that controls VIZIO Bluetooth LE soundbars directly —
  no account, no cloud. Tested on the Elevate SE (SL512X).
- Local authentication handshake, live state on connect, and live updates when the bar is changed
  with its remote.
- Sound, Levels and System tabs: volume, mute, sound mode, surround, height speakers, effects,
  per-sound-mode levels, input, A/V delay, lighting, power and assistant settings, device info.
- Connection progress (scanning → connecting → authenticating → syncing); controls stay locked
  until the bar's real values are read.
- Phone volume keys control the bar while the app is open.
- Notification controls (volume, next EQ, disconnect) while connected; they close on disconnect or
  when the app is closed.
- Names for bars: the bar's broadcast name, overridden by the phone's Bluetooth nickname, overridden
  by a nickname set in Unbarred.
- Settings that persist between launches; diagnostic logging (off by default) with share/export.
- On-demand update check against GitHub Releases.
- Python command-line client (`python/`) and protocol documentation (`PROTOCOL.md`).

### Known issues
- Speaker test is disabled: on tested bars it leaves the bar unresponsive until it is
  power-cycled.

[Unreleased]: https://github.com/TrevorTherrien/unbarred-vizio/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/TrevorTherrien/unbarred-vizio/releases/tag/v0.1.0
