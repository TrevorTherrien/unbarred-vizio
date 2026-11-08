#!/usr/bin/env python3
"""
ble_diag.py — diagnose why BleakScanner finds nothing on Windows.

Run:  python ble_diag.py
It uses the detection-CALLBACK API (not discover()) so it prints EVERY BLE
advertisement the radio hears, live, with RSSI. If other BLE gadgets show up
but the VIZIO bar doesn't, the problem is the bar (not advertising). If NOTHING
shows up at all, the problem is the PC side (permissions / Location / the radio
is classic-only).

IMPORTANT: a phone in Bluetooth "pairing/discoverable" mode is NOT a valid test.
Phones advertise over *Classic* Bluetooth for pairing; BleakScanner only sees
*BLE* advertisements, so your phone legitimately won't appear here. Test with a
real BLE device (a fitness band, a BLE beacon, wireless earbuds while in their
BLE pairing blink, or the soundbar itself).
"""

import asyncio
import platform
import sys

# On Windows, bleak needs the Proactor loop (the default on 3.8+). This is just
# a guard in case something set the Selector policy.
if sys.platform == "win32":
    try:
        asyncio.set_event_loop_policy(asyncio.WindowsProactorEventLoopPolicy())
    except Exception:
        pass

try:
    import bleak
    from bleak import BleakScanner
except ImportError:
    print("bleak is not installed in THIS interpreter.")
    print("In PyCharm make sure the project .venv is selected, then:  pip install bleak")
    sys.exit(1)

seen = {}

def on_detection(device, adv):
    key = device.address
    if key not in seen:
        seen[key] = True
        name = device.name or adv.local_name or "(no name)"
        svc = ",".join(adv.service_uuids) if adv.service_uuids else ""
        hit = ""
        if ("vizio" in (name or "").lower()
                or any("37619c1d" in u.lower() or "37619c1e" in u.lower()
                       for u in (adv.service_uuids or []))):
            hit = "   <<< THIS LOOKS LIKE THE SOUNDBAR"
        print(f"  {device.address}  rssi={adv.rssi:>4}  {name}{hit}")
        if svc:
            print(f"        services: {svc}")

async def main():
    print(f"Python  : {platform.python_version()}  ({sys.executable})")
    print(f"Platform: {platform.system()} {platform.release()}")
    print(f"bleak   : {getattr(bleak, '__version__', '?')}")
    print("-" * 60)

    # 1) adapter sanity: can we even construct a scanner?
    try:
        scanner = BleakScanner(detection_callback=on_detection)
    except Exception as e:
        print(f"FAILED to create scanner -> BLE stack/adapter problem: {e!r}")
        print("Likely: the PC's Bluetooth radio is not BLE-capable (pre-4.0),")
        print("or the Bluetooth service/driver is disabled in Device Manager.")
        return

    print("Scanning 20s with live callback — every BLE advertiser prints once:\n")
    try:
        await scanner.start()
        await asyncio.sleep(20.0)
        await scanner.stop()
    except Exception as e:
        print(f"\nSCAN ERROR: {e!r}")
        print("On Windows this is usually a permission problem — see notes below.")
        return

    print("-" * 60)
    if not seen:
        print("ZERO BLE devices heard. That points at the PC, not the soundbar:")
        print("  1. Settings > Privacy & security > Location: turn Location ON")
        print("     (Windows gates BLE scanning behind Location services).")
        print("  2. Settings > Privacy & security > Bluetooth devices: allow access.")
        print("  3. Confirm the radio is Bluetooth 4.0+ (LE). In PowerShell:")
        print("       Get-PnpDevice -Class Bluetooth | ft -Auto")
        print("     A pre-4.0 radio / classic-only USB dongle sees NOTHING in bleak.")
        print("  4. Try toggling Bluetooth off/on, or a different BLE adapter.")
    else:
        print(f"Heard {len(seen)} BLE device(s). The radio & permissions are FINE.")
        print("If the VIZIO line is absent, the bar isn't advertising:")
        print("  - Power-cycle it, then hold the soundbar's Bluetooth button to")
        print("    re-enter BLE advertising (it stops advertising ~1h after boot).")
        print("  - Make sure no phone/other device currently holds its link.")

if __name__ == "__main__":
    asyncio.run(main())
