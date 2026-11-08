#!/usr/bin/env python3
"""
connect_test.py — connect to the soundbar BY ADDRESS, with NO scanning.

Why: BleakScanner (WinRT advertisement watcher) is dead on your RZ616, but
connecting is a different radio operation and your phone proves the link works.
On Windows, BleakClient(address).connect() calls FromBluetoothAddressAsync(),
which does NOT need a prior scan. So we skip discovery entirely.

Get the bar's MAC from nRF Connect:
  - In the device list, the address (AA:BB:CC:DD:EE:FF) is shown under the name.
  - Or tap the device, then the three-dot menu / info — the address is listed.

Run:
  python connect_test.py AA:BB:CC:DD:EE:FF
"""

import asyncio
import sys
from bleak import BleakClient

VIZIO_PREFIX = "37619c1d"   # our custom service/characteristic family

async def main(addr):
    print(f"Direct connect to {addr}  (no scan)...")
    try:
        async with BleakClient(addr, timeout=25.0) as c:
            print(f"\n*** CONNECTED: {c.is_connected} ***\n")
            found_vizio = False
            for s in c.services:
                print(f"service {s.uuid}")
                for ch in s.characteristics:
                    tag = "  <-- VIZIO" if VIZIO_PREFIX in ch.uuid.lower() else ""
                    if VIZIO_PREFIX in ch.uuid.lower():
                        found_vizio = True
                    print(f"   char {ch.uuid}  {','.join(ch.properties)}{tag}")
            print()
            if found_vizio:
                print("SUCCESS: this is the soundbar and its control profile is reachable.")
                print("You can now use vizio_soundbar.py with:  -a " + addr)
            else:
                print("Connected, but no 37619c1d characteristics — is this the right address?")
    except Exception as e:
        print(f"\nCONNECT FAILED: {e!r}")
        print("If this is a timeout/'device not found', the radio can't do BLE at all")
        print("(connect path is dead too) -> a $10 RTL8761B USB BLE dongle is the fix.")
        print("If it's a pairing/auth error, tell me the exact text — that's progress,")
        print("it means we reached the device and just need to pair/bond.")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: python connect_test.py <MAC e.g. AA:BB:CC:DD:EE:FF>")
        sys.exit(2)
    asyncio.run(main(sys.argv[1].strip()))
