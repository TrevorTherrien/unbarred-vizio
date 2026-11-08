# Contributing

Thanks for helping keep these speakers usable! Contributions are welcome — bug
fixes, UI improvements, and especially **compatibility reports for other models**.

## Ground rules

- This is an interoperability project. Contribute only knowledge derived
  from **your own observation of devices you own** (GATT dumps, packet captures,
  testing). **Do not** paste or commit the vendor app's decompiled source, assets,
  or other proprietary code.
- Keep it respectful of the trademark guidance in [DISCLAIMER.md](DISCLAIMER.md):
  no vendor logos or brand artwork, descriptive naming only.
- Be safe: never add the factory-reset or OTA/firmware characteristics to the
  convenience UI unless proven and cross checked.

## Adding support for another model

1. Connect and run the CLI's GATT dump:
   ```
   python vizio_soundbar.py -a <ADDRESS> services
   ```
2. Open an issue with:
   - Model number and firmware version.
   - The full `services` output (which services/characteristics it exposes).
   - Whether the security service (`…F100`) is present, and whether the handshake
     succeeds.
3. If you've mapped a new characteristic or value, note how you confirmed it
   (what changed on the bar when you wrote which byte).

## Code

- **Android:** Kotlin + Jetpack Compose, raw `android.bluetooth` APIs (no third-party
  BLE lib). Keep GATT operations serialized.
- **Python:** `bleak`, standard library otherwise. Target 3.9+.
- Keep the protocol facts in [PROTOCOL.md](PROTOCOL.md) as the single source of
  truth; both clients should match it.

## Reporting bugs

Open an issue with the device model, the client (Android/Python) and version, what
you did, what you expected, and what happened — plus any log/status text the client
printed.
