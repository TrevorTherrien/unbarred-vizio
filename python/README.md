# Python CLI

A cross-platform command-line controller for VIZIO BLE soundbars, built on
[`bleak`](https://github.com/hbldh/bleak). Runs on Linux, macOS, Windows, and
Raspberry Pi.

```bash
pip install bleak
```

## Usage

```bash
# find the bar
python vizio_soundbar.py scan

# connect by address (does the local handshake automatically) and dump the GATT table
python vizio_soundbar.py -a AA:BB:CC:DD:EE:FF services

# read current state
python vizio_soundbar.py -a AA:BB:CC:DD:EE:FF info

# control examples
python vizio_soundbar.py -a AA:BB:CC:DD:EE:FF eq music          # Movie/Music/Direct
python vizio_soundbar.py -a AA:BB:CC:DD:EE:FF surround on       # Off/On/Dual/Front
python vizio_soundbar.py -a AA:BB:CC:DD:EE:FF elevate auto      # Up/Wide/Auto
python vizio_soundbar.py -a AA:BB:CC:DD:EE:FF led cyan
python vizio_soundbar.py -a AA:BB:CC:DD:EE:FF bass 4            # signed; -2 is allowed
python vizio_soundbar.py -a AA:BB:CC:DD:EE:FF dialog 3          # ClearDialog
python vizio_soundbar.py -a AA:BB:CC:DD:EE:FF --help            # full command list
```

If the handshake writes are rejected, retry with `--pair` to attempt an OS-level
BLE bond first.

## Helper scripts

- **`ble_diag.py`** — prints every BLE advertisement your adapter hears, to confirm
  the radio works and the bar is advertising. (A phone in pairing mode is *not* a
  valid test — that's classic Bluetooth; `bleak` only sees BLE.)
- **`connect_test.py <ADDRESS>`** — minimal connect-by-address test with no scanning,
  useful when a host radio won't scan but can still connect.

## Troubleshooting

- **`scan` finds nothing but a phone sees the bar:** your host's Bluetooth radio
  likely can't reliably scan BLE (common with some laptop Wi-Fi/BT combo chips on
  Windows). Options: run on a phone or Raspberry Pi, or use a known-good USB BLE 5.0
  dongle (e.g. an RTL8761B-based adapter). On Windows, BLE scanning also requires
  Location Services enabled.
- **Connects, then "handshake failed / disconnected":** the bar drops clients that
  don't authenticate fast. The CLI runs the handshake immediately; if it still drops,
  try `--pair`, and make sure no other device holds the bar's single connection slot.
- **Nothing happens on a write but no error:** confirm the characteristic exists on
  your model (`services`) and that the value is in the firmware's supported range.
