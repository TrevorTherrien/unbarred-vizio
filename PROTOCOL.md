# VIZIO soundbar BLE protocol

Reverse engineered for interoperability by a single developer with AI assistance — from
analysis of the vendor app and Bluetooth LE captures of the author's own soundbar (see
[DISCLAIMER.md](DISCLAIMER.md#how-the-protocol-was-derived)). Confirmed on the
**VIZIO Elevate SE (SL512X)**; believed common across this generation of VIZIO
Bluetooth-LE soundbars. See [DISCLAIMER.md](DISCLAIMER.md) for legal context.

## 1. Overview

- The soundbar is controlled entirely over **Bluetooth LE GATT**. There is no Wi-Fi
  or classic-Bluetooth control path. (Classic Bluetooth on these bars is only A2DP
  music streaming — a separate function.)
- The vendor account/login is for cloud features and firmware delivery. It is **not**
  a cryptographic gate on local control — the on-device handshake below uses no
  server and no account.
- Each setting is a **single byte** written to a dedicated characteristic.

All custom UUIDs share the base:

```
ea1979cf-5313-4152-b056-37619c1dXXXX     (service groups A, B, C, D, F)
ea1979cf-5313-4152-b056-37619c1eXXXX     (OTA and karaoke groups)
```

The bar advertises its service UUIDs (e.g. `…37619c1da100`) with a connectable,
LE-only (`BR/EDR Not Supported`) advertisement, and includes manufacturer data with
VIZIO's company id (`0x0058`). Scan-filter on `…a100` to find it, or connect by
address.

## 2. GATT map

### Service A — Info (`…dA100`)
| UUID | Name | Dir |
|---|---|---|
| A101 | Model name | read (string) |
| A102 | Serial number | read (string) |
| A103 | Firmware version | read (string) |
| A104 | Current input | read (byte) |
| A105 | Audio out (current bitstream format) | read (byte) |
| A106 | Friendly name | read/write (string) |
| A107 | Security generation code | read (byte) |

### Service B — Main menu (`…dB100`)
| UUID | Name | Values |
|---|---|---|
| B102 | Auto voice-assistant | 0/1 |
| B103 | Eco power | byte |
| B106 | Volume | byte |
| B107 | Mute | 0/1 |
| B108 | Power-LED brightness | byte |
| B109 | Announcements (spoken prompts) | 0/1 |
| B10A | A/V delay (lip-sync) | byte, 0..8 on SL512X |
| B10B | **Factory reset** ⚠️ | All=0, Audio=1 |
| B10C | Speaker test ⚠️ (see §7) | 0/1 |
| B10D | EQ preset | Movie=0, Music=1, Direct=3 |
| B10E | Select input | USB=0, Bluetooth=1, HDMI=2 |
| B10F | Elevate LED color | see §4 |
| B111 | Auto-HDMI | 0/1 |

### Service C — Levels (`…dC100`) — all signed single-byte trims (see §3)
| UUID | Name |
|---|---|
| C101 | Bass |
| C102 | Treble |
| C103 | Center |
| C104 | Subwoofer level |
| C105 | Surround level |
| C106 | Dialogue (ClearDialog) — unsigned, 0..10 on SL512X |
| C107 | Surround balance |
| C108 | Height |
| C109 | Wireless-speaker status (read; bitmask: bit0 L, 1 R, 2 L-rear, 3 R-rear, 4 sub) |
| C10A | Wide-surround level |
| C10B | Wide-surround balance |

### Service D — Effects (`…dD100`)
| UUID | Name | Values |
|---|---|---|
| D101 | Surround speakers (5.1) | Off=0, On=1, Dual=2, Front=3 |
| D102 | TruVolume | 0/1 |
| D103 | Virtual:X | 0/1 |
| D104 | Night mode | 0/1 |
| D105 | Elevate speakers (height aim) | Up=0, Wide=1, Auto=2 |

### Service F — Security (`…dF100`), optional
| UUID | Name | Use |
|---|---|---|
| F101 | Response | read |
| F102 | Error | read |
| F103 | Status | read |
| F104 | Handshake | write device id / read nonce |
| F105 | Preshared | write handshake response / read pairing token |

### OTA / Karaoke (base `…1e`) ⚠️
`SERVICE_OTA …1e01xx` (firmware push — **do not touch**) and
`SERVICE_KARAOKE …1e11xx` (MicMe mic: battery, voice effect, music key, echo, mic
LED color). Documented in the source; omitted from the clients for safety/scope.

## 3. Value encoding

A control value is written as one byte: `[ (byte) value ]`. Prefer **Write-Without-
Response** for controls (see §8 — a write-request draws a bogus `status 14` ack);
reserve write-with-response for the handshake. Strings (friendly name) are UTF-8.

**Levels are signed, with no scaling or offset.** The byte *is* the level number in
two's complement: `+6 → 0x06`, `-6 → 0xFA`, `0 → 0x00`. To read a level back, treat
the byte as signed. The min/max/step are **not** hard-coded in the control path —
the vendor app pulls them from a per-model/firmware config, so exact ranges vary by
unit. The firmware clamps out-of-range writes, so probing ±1 at a time is safe.

## 4. Enum values

```
EQ  (B10D):             MOVIE=0   MUSIC=1   DIRECT=3
Elevate LED (B10F):     OFF=0  RAINBOW=1  LIGHT_BLUE=2  YELLOW=3
                        PURPLE=4  WHITE=5  CYAN=6  MAGENTA=7
Surround (D101):        OFF=0   ON=1   DUAL=2   FRONT=3
Elevate speakers (D105):UP=0    WIDE=1  AUTO=2
Input (B10E / A104):    USB=0   BLUETOOTH=1   HDMI=2
```

## 5. Local authentication handshake (only if service F is present)

If the bar exposes `SERVICE_SECURITY (F100)`, each control write is prefixed with an
ASCII `"<pairingToken>:"`; otherwise the raw value byte is written. The security is
**not encryption** — it is a plaintext token prepended to the payload. The token is
obtained by a handshake that is entirely local to the phone/PC and the bar:

Order (the client runs this immediately on connect, because the bar drops
unauthenticated clients quickly):

1. **Send device id** → write `F104`: `b"1:" + <14-char ASCII device id>`.
2. Read `F104` → a short ASCII challenge.
3. **Send response** → write `F105`: `(challenge + ":").bytes + <14-byte response>`.
4. Read `F105` → `pairingToken` (ASCII). Prefix every later control write with
   `"<pairingToken>:"`.

`A107` (security generation code) changes on factory reset and invalidates stored
tokens — if writes start failing, re-run the handshake.

### The step-3 response

The 14-byte response depends **only on the device id** sent in step 1 — not on the
bar's challenge. For a fixed device id it is therefore a constant, and a client can
simply replay it. Both clients in this repo use:

```
device id : "cafe0babe0dead"
response  : d0 90 28 c0 88 28 00 a8 b0 28 18 88 b0 a8
```

Using a different device id requires its matching response. Constants:
`python/vizio_soundbar.py` (`DEVICE_ID`, `HANDSHAKE_RESPONSE`) and
`app/.../Protocol.kt` (`Handshake`).

## 6. Connection notes

- The bar accepts **one controller at a time**; a device already connected to it
  will block others. Advertising stops while connected.
- It advertises for ~1 hour after power-up; after that, hold the bar's Bluetooth
  button to re-enter advertising.
- Scanning and connecting are different radio operations — on a flaky host you can
  often connect by a known address even when scanning returns nothing.

## 7. Don't-touch list ⚠️

`B10B` (factory reset), the entire `…1e01xx` OTA/firmware group, and `F105` outside
the handshake. A wrong write there can wipe settings or half-flash the unit.

`B10C` (speaker test): on the SL512X, starting the test leaves the bar ignoring every
later command until it is power-cycled. Both clients disable it until the correct
start/stop sequence is understood.

## 8. Live state, notifications, and per-mode EQ

Three behaviours that aren't obvious from the GATT map but matter for a correct client:

**Every control characteristic supports NOTIFY.** The bar pushes a notification with
the current value whenever a setting changes — *including changes made with the IR
remote or physical buttons*. The vendor app never subscribes (it reads on demand), but
a client that enables notifications (write CCCD `0x2902` = `0x0100` on each control)
reflects the bar's live state, external changes included. Each notification carries the
applied value as the same signed byte used for writes. This per-characteristic echo is
also the most reliable write **ack** — far more so than the GATT-layer status.

**Channel levels are stored per EQ mode, on the bar.** The §2 Service-C level trims
(bass, treble, center, dialogue, subwoofer, height, surround level/balance) are **not
global** — the firmware keeps an independent set for each EQ preset (`B10D`:
Movie/Music/Direct). Switching the EQ mode activates that mode's stored levels, and the
bar then **notifies the affected level characteristics** with the new values; a write to
a level is filed under whichever mode is currently active. The vendor app does not cache
these sets on the phone (its only local database stores paired-device records) — they
live entirely in the bar. So a client that subscribes to the level characteristics shows
the correct per-mode values automatically on a mode change; a write-only client should
re-read the levels after writing `B10D`.

**Write type / status 14.** The firmware returns a non-compliant GATT ack (`status 14`
/ `0x0E`, "unlikely") to write-*requests* that arrive rapidly — e.g. dragging a slider —
even though it applies the value. The control characteristics advertise
`WRITE_NO_RESPONSE`; using Write-Without-Response avoids the bogus ack entirely, and the
NOTIFY echo above confirms the value landed. Coalescing rapid writes (latest-wins per
characteristic) with light spacing keeps the link uncongested.
