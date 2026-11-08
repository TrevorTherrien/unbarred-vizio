#!/usr/bin/env python3
"""
vizio_soundbar.py — login-free local controller for VIZIO BLE soundbars
(Elevate SE SL512X / Elevate / M-series Elevate and relatives).

This talks the SAME Bluetooth-LE GATT protocol the VIZIO Mobile app uses,
reverse engineered with AI assistance (no account, no cloud, no
modified APK). It runs on any machine with a BLE adapter:

    pip install bleak
    python3 vizio_soundbar.py scan
    python3 vizio_soundbar.py --address AA:BB:CC:DD:EE:FF info
    python3 vizio_soundbar.py --address AA:BB:CC:DD:EE:FF eq music
    python3 vizio_soundbar.py --address AA:BB:CC:DD:EE:FF led cyan
    python3 vizio_soundbar.py --address AA:BB:CC:DD:EE:FF surround on
    python3 vizio_soundbar.py --address AA:BB:CC:DD:EE:FF height 6
    python3 vizio_soundbar.py --address AA:BB:CC:DD:EE:FF elevate auto

Protocol summary
----------------
* Custom service/char base UUID:  ea1979cf-5313-4152-b056-37619c1dXXXX
  (the A/B/C/D/F groups) and ...37619c1eXXXX (OTA / karaoke groups).
* Most settings are a SINGLE BYTE written to a dedicated characteristic.
* Optional "security" is NOT encryption: if the bar exposes the security
  service, every control write is prefixed with  b"<pairingToken>:" .
  The pairing token is obtained by a purely LOCAL handshake that replays a
  fixed response for a fixed device ID — no login, no server.
* If the bar does NOT expose the security service, writes are sent raw.

This module auto-detects whether the handshake is needed and performs it.
"""

import argparse
import asyncio
import sys

from bleak import BleakClient, BleakScanner

# --------------------------------------------------------------------------
# UUIDs  (see PROTOCOL.md §2)
# --------------------------------------------------------------------------
def _u(suffix4: str) -> str:
    # Groups A/B/C/D/F use ...1d
    return f"ea1979cf-5313-4152-b056-37619c1d{suffix4}"

def _u1e(suffix4: str) -> str:
    # OTA (0100) and Karaoke/Mic (1100) groups use ...1e
    return f"ea1979cf-5313-4152-b056-37619c1e{suffix4}"

SERVICE_INFO      = _u("A100")
CHAR_MODEL_NAME   = _u("A101")
CHAR_SERIAL       = _u("A102")
CHAR_FW_VERSION   = _u("A103")
CHAR_CURRENT_INPUT= _u("A104")
CHAR_AUDIO_OUT    = _u("A105")
CHAR_FRIENDLY_NAME= _u("A106")
CHAR_SECURITY_GEN = _u("A107")

SERVICE_MAIN_MENU = _u("B100")
CHAR_SB_INFO      = _u("B101")
CHAR_AUTO_VA      = _u("B102")
CHAR_ECO_POWER    = _u("B103")
CHAR_GET_INPUT    = _u("B104")
CHAR_DISC_INPUTS  = _u("B105")
CHAR_VOLUME       = _u("B106")
CHAR_MUTE         = _u("B107")
CHAR_POWER_LED    = _u("B108")
CHAR_AV_DELAY     = _u("B10A")
CHAR_FACTORY_RESET= _u("B10B")
CHAR_SPEAKER_TEST = _u("B10C")
CHAR_VZ_EQ        = _u("B10D")
CHAR_SEL_INPUT    = _u("B10E")
CHAR_ELEVATE_LED  = _u("B10F")
CHAR_AUTO_HDMI    = _u("B111")

CHAR_ANNOUNCEMENT = _u("B109")

SERVICE_LEVELS    = _u("C100")
CHAR_BASS         = _u("C101")
CHAR_TREBLE       = _u("C102")
CHAR_CENTER       = _u("C103")
CHAR_SUBWOOFER    = _u("C104")
CHAR_SURR_LEVEL   = _u("C105")
CHAR_DIALOG       = _u("C106")
CHAR_SURR_BALANCE = _u("C107")
CHAR_HEIGHT       = _u("C108")
CHAR_WLESS_SPK_ST = _u("C109")
CHAR_WIDE_SURR_LVL= _u("C10A")
CHAR_WIDE_SURR_BAL= _u("C10B")

SERVICE_EFFECTS   = _u("D100")
CHAR_SURR_SPEAKERS= _u("D101")
CHAR_TRU_VOLUME   = _u("D102")
CHAR_VIRTUAL_X    = _u("D103")
CHAR_NIGHT_MODE   = _u("D104")
CHAR_ELEVATE_SPK  = _u("D105")

SERVICE_SECURITY  = _u("F100")
CHAR_SEC_RESPONSE = _u("F101")
CHAR_SEC_ERROR    = _u("F102")
CHAR_SEC_STATUS   = _u("F103")
CHAR_SEC_HANDSHAKE= _u("F104")
CHAR_SEC_PRESHARED= _u("F105")

# OTA group (…1e01xx) — firmware flashing. DO NOT TOUCH unless you mean it.
SERVICE_OTA       = _u1e("0100")
CHAR_OTA_PKG_BUF  = _u1e("0101")
CHAR_OTA_STATUS   = _u1e("0102")
CHAR_OTA_REQUEST  = _u1e("0103")
CHAR_OTA_CRC      = _u1e("0104")

# Karaoke / microphone group (…1e11xx) — only on MicMe-capable bars
SERVICE_KARAOKE   = _u1e("1100")
CHAR_MIC_PAIR_REQ = _u1e("1101")
CHAR_MIC_BATTERY  = _u1e("1102")
CHAR_MIC_CONN_ST  = _u1e("1103")
CHAR_MIC_KARAOKE  = _u1e("1104")   # voice effect
CHAR_MIC_MUSIC_KEY= _u1e("1105")   # pitch: lower/original/higher
CHAR_MIC_VOLUME   = _u1e("1106")
CHAR_MIC_ECHO     = _u1e("1107")
CHAR_MIC_LED      = _u1e("1108")   # mic color
CHAR_MIC_GLOBAL   = _u1e("1109")

# --------------------------------------------------------------------------
# Value maps (see PROTOCOL.md §4)
# --------------------------------------------------------------------------
EQ         = {"movie": 0, "music": 1, "direct": 3}
LED        = {"off": 0, "rainbow": 1, "light_blue": 2, "yellow": 3,
              "purple": 4, "white": 5, "cyan": 6, "magenta": 7}
SURROUND   = {"off": 0, "on": 1, "dual": 2, "front": 3}
ELEVATE    = {"up": 0, "wide": 1, "auto": 2}              # height-speaker aim
BOOL       = {"off": 0, "on": 1, "false": 0, "true": 1, "0": 0, "1": 1}
INPUT      = {"usb": 0, "bluetooth": 1, "hdmi": 2}
RESET      = {"all": 0, "audio": 1}                       # factory-reset scope
MUSIC_KEY  = {"lower": 0, "original": 1, "higher": 2}     # karaoke pitch
VOICE_FX   = {"none": 1, "duoslap_hi": 2, "album_rock": 3, "semi_pro_trio_country": 4,
              "semi_pro_trio_2": 5, "duo_2": 6, "silky_trio_2": 7}
MIC_COLOR  = {"none": 0, "rgb": 1, "orange": 2, "blossom": 3, "seafoam": 4, "ocean": 5,
              "electric": 6, "red": 7, "violet": 8, "teal": 9, "white": 10, "gray": 11}
# Read-only decode for CHAR_AUDIO_OUT (A105) — current bitstream format
AUDIO_OUT  = {0:"Dolby Digital",1:"Dolby Digital+",2:"Dolby TrueHD",3:"Atmos (DD+)",
              4:"Atmos (TrueHD)",5:"Atmos (MAT)",6:"DTS",7:"LPCM",8:"PCM",9:"Stereo",
              10:"WAV",11:"MP3",12:"DTS-HD MA",13:"DTS:X",14:"Dolby MAT",255:"None"}
# Read-only decode for CHAR_CLASSIC_STATUS (B112)
BT_STATUS  = {0:"link none",1:"wait connect",2:"pair mode",3:"connected",4:"disconnected",
              5:"TWS paired",6:"TWS unpaired",7:"TWS pair search"}

# --------------------------------------------------------------------------
# Fixed handshake values (see PROTOCOL.md §5)
# --------------------------------------------------------------------------
# The bar's expected 14-byte response depends only on the device ID, never on
# its challenge, so for one fixed ID it is a constant that we simply replay.
# Changing DEVICE_ID requires the matching HANDSHAKE_RESPONSE.
DEVICE_ID = "cafe0babe0dead"                                   # exactly 14 chars
HANDSHAKE_RESPONSE = bytes.fromhex("d09028c0882800a8b0281888b0a8")  # for DEVICE_ID
assert len(DEVICE_ID) == 14 and len(HANDSHAKE_RESPONSE) == 14


class VizioSoundbar:
    def __init__(self, address: str, verbose: bool = True):
        self.address = address
        self.verbose = verbose
        self.client: BleakClient | None = None
        self.pairing_token: str | None = None
        self.secured = False
        self.do_pair = False   # set True to attempt OS-level BLE bonding on connect

    def log(self, *a):
        if self.verbose:
            print(*a, file=sys.stderr)

    async def __aenter__(self):
        self.client = BleakClient(self.address, disconnected_callback=self._on_disconnect)
        await self.client.connect()
        self.log(f"[+] connected to {self.address}")
        # Optional OS-level bond first (some firmware requires an encrypted link
        # before the security service accepts writes). Harmless if not needed.
        if self.do_pair:
            try:
                ok = await self.client.pair()
                self.log(f"[*] OS pair() -> {ok}")
            except Exception as e:
                self.log(f"[*] pair() not available/failed: {e!r}")
        services = self.client.services
        self.secured = services.get_service(SERVICE_SECURITY) is not None
        if self.secured:
            self.log("[*] security service present -> handshake NOW (bar boots unauth clients fast)")
            # mimic the app: read the gen code (A107) first, then handshake
            try:
                gen = await self.client.read_gatt_char(CHAR_SECURITY_GEN)
                self.log(f"[*] security generation code (A107) = {list(gen)}")
            except Exception as e:
                self.log(f"[*] gen-code read skipped: {e!r}")
            await self._handshake()
        else:
            self.log("[*] no security service -> raw writes")
        return self

    def _on_disconnect(self, client):
        self.log("[!] DISCONNECTED by peer (bar dropped the link)")

    async def __aexit__(self, *exc):
        if self.client:
            await self.client.disconnect()
            self.log("[-] disconnected")

    # ---- security handshake (all local, no account) ----------------------
    async def _handshake(self):
        import time
        dev = DEVICE_ID.encode("ascii")                  # 14 bytes
        t0 = time.time()
        def el(): return f"{(time.time()-t0)*1000:.0f}ms"
        try:
            # 1) send mobile device id:  "1:" + id   -> HANDSHAKE char (F104)
            await self.client.write_gatt_char(CHAR_SEC_HANDSHAKE, b"1:" + dev, response=True)
            self.log(f"    [{el()}] step1 sent mobile id")
            await asyncio.sleep(0.15)
            # 2) read request nonce from HANDSHAKE char
            nonce_raw = await self.client.read_gatt_char(CHAR_SEC_HANDSHAKE)
            nonce = nonce_raw.split(b"\x00")[0].decode("ascii", "replace").strip()
            self.log(f"    [{el()}] step2 nonce raw={nonce_raw!r} parsed={nonce!r}")
            # 3) send response:  "<nonce>:" + HANDSHAKE_RESPONSE  -> PRESHARED char (F105)
            await self.client.write_gatt_char(
                CHAR_SEC_PRESHARED, (nonce + ":").encode("ascii") + HANDSHAKE_RESPONSE, response=True)
            self.log(f"    [{el()}] step3 sent response")
            await asyncio.sleep(0.15)
            # 4) read pairing token from PRESHARED char
            tok_raw = await self.client.read_gatt_char(CHAR_SEC_PRESHARED)
            self.pairing_token = tok_raw.split(b"\x00")[0].decode("ascii", "replace").strip()
            self.log(f"    [{el()}] step4 pairingToken raw={tok_raw!r} parsed={self.pairing_token!r}")
            self.log(f"[+] HANDSHAKE OK in {el()}")
        except Exception as e:
            self.log(f"[!] handshake FAILED at {el()}: {e!r}")
            self.log("    If this is a disconnect: bar booted us mid-handshake (radio too slow,")
            self.log("    or needs OS bonding -> retry with  --pair ). If it's an auth/write")
            self.log("    error on F105: the link likely needs bonding first (--pair).")
            raise

    # ---- core write/read -------------------------------------------------
    async def write_value(self, char_uuid: str, value: int):
        """Write a single-byte control value, prefixed with the token if secured."""
        payload = bytes([value & 0xFF])
        if self.secured and self.pairing_token:
            payload = (self.pairing_token + ":").encode("ascii") + payload
        await self.client.write_gatt_char(char_uuid, payload, response=True)
        self.log(f"    wrote {char_uuid[-4:]} = {value}  ({payload!r})")

    async def read_text(self, char_uuid: str) -> str:
        raw = await self.client.read_gatt_char(char_uuid)
        return raw.split(b"\x00")[0].decode("utf-8", "replace").strip()

    async def read_byte(self, char_uuid: str) -> int:
        raw = await self.client.read_gatt_char(char_uuid)
        return raw[0] if raw else -1

    # ---- high-level controls --------------------------------------------
    async def set_eq(self, name):        await self.write_value(CHAR_VZ_EQ, EQ[name])
    async def set_led(self, name):       await self.write_value(CHAR_ELEVATE_LED, LED[name])
    async def set_surround(self, name):  await self.write_value(CHAR_SURR_SPEAKERS, SURROUND[name])
    async def set_elevate(self, name):   await self.write_value(CHAR_ELEVATE_SPK, ELEVATE[name])
    async def set_height(self, lvl):     await self.write_value(CHAR_HEIGHT, int(lvl))
    async def set_bass(self, lvl):       await self.write_value(CHAR_BASS, int(lvl))
    async def set_treble(self, lvl):     await self.write_value(CHAR_TREBLE, int(lvl))
    async def set_subwoofer(self, lvl):  await self.write_value(CHAR_SUBWOOFER, int(lvl))
    async def set_dialog(self, lvl):     await self.write_value(CHAR_DIALOG, int(lvl))
    async def set_volume(self, lvl):     await self.write_value(CHAR_VOLUME, int(lvl))
    async def set_mute(self, on):        await self.write_value(CHAR_MUTE, 1 if on else 0)
    async def set_virtual_x(self, on):   await self.write_value(CHAR_VIRTUAL_X, 1 if on else 0)
    async def set_night_mode(self, on):  await self.write_value(CHAR_NIGHT_MODE, 1 if on else 0)
    async def set_tru_volume(self, on):  await self.write_value(CHAR_TRU_VOLUME, 1 if on else 0)
    # extra audio levels / balance
    async def set_center(self, lvl):     await self.write_value(CHAR_CENTER, int(lvl))
    async def set_surr_level(self, lvl): await self.write_value(CHAR_SURR_LEVEL, int(lvl))
    async def set_surr_balance(self, v): await self.write_value(CHAR_SURR_BALANCE, int(v))
    async def set_wide_surr_level(self,v):await self.write_value(CHAR_WIDE_SURR_LVL, int(v))
    async def set_wide_surr_balance(self,v):await self.write_value(CHAR_WIDE_SURR_BAL, int(v))
    async def set_av_delay(self, v):     await self.write_value(CHAR_AV_DELAY, int(v))
    # system toggles / selectors
    async def set_input(self, name):     await self.write_value(CHAR_SEL_INPUT, INPUT[name])
    async def set_power_led(self, v):    await self.write_value(CHAR_POWER_LED, int(v))
    async def set_eco_power(self, v):    await self.write_value(CHAR_ECO_POWER, int(v))
    async def set_auto_hdmi(self, on):   await self.write_value(CHAR_AUTO_HDMI, 1 if on else 0)
    async def set_auto_va(self, on):     await self.write_value(CHAR_AUTO_VA, 1 if on else 0)
    async def set_announcement(self, on):await self.write_value(CHAR_ANNOUNCEMENT, 1 if on else 0)
    async def speaker_test(self, on):    await self.write_value(CHAR_SPEAKER_TEST, 1 if on else 0)
    async def set_name(self, text):      # friendly name (UTF-8, optional token prefix)
        payload = text.encode("utf-8")
        if self.secured and self.pairing_token:
            payload = (self.pairing_token + ":").encode("ascii") + payload
        await self.client.write_gatt_char(CHAR_FRIENDLY_NAME, payload, response=True)
    async def factory_reset(self, scope="audio"):   # 'audio' or 'all' — destructive
        await self.write_value(CHAR_FACTORY_RESET, RESET[scope])
    # karaoke / mic (only on MicMe-capable bars)
    async def set_mic_volume(self, v):   await self.write_value(CHAR_MIC_VOLUME, int(v))
    async def set_mic_echo(self, v):     await self.write_value(CHAR_MIC_ECHO, int(v))
    async def set_mic_color(self, name): await self.write_value(CHAR_MIC_LED, MIC_COLOR[name])
    async def set_music_key(self, name): await self.write_value(CHAR_MIC_MUSIC_KEY, MUSIC_KEY[name])
    async def set_voice_fx(self, name):  await self.write_value(CHAR_MIC_KARAOKE, VOICE_FX[name])

    async def dump_services(self):
        """Print the full discovered GATT table — run this FIRST on a new bar."""
        names = {v: k for k, v in globals().items()
                 if isinstance(v, str) and v.startswith("ea1979cf")}
        for svc in self.client.services:
            print(f"service {svc.uuid}  {names.get(svc.uuid,'')}")
            for ch in svc.characteristics:
                props = ",".join(ch.properties)
                print(f"   char {ch.uuid[-4:]}  {names.get(ch.uuid,''):22s} [{props}]")
        print(f"\nsecurity service present: {self.secured}")

    async def info(self):
        out = {}
        for label, uuid in [("model", CHAR_MODEL_NAME), ("serial", CHAR_SERIAL),
                            ("firmware", CHAR_FW_VERSION), ("friendly_name", CHAR_FRIENDLY_NAME)]:
            try:    out[label] = await self.read_text(uuid)
            except Exception as e: out[label] = f"<err: {e}>"
        for label, uuid in [("current_input", CHAR_CURRENT_INPUT), ("volume", CHAR_VOLUME),
                            ("mute", CHAR_MUTE), ("eq", CHAR_VZ_EQ),
                            ("surround", CHAR_SURR_SPEAKERS), ("elevate_aim", CHAR_ELEVATE_SPK),
                            ("height", CHAR_HEIGHT), ("bass", CHAR_BASS), ("treble", CHAR_TREBLE),
                            ("subwoofer", CHAR_SUBWOOFER), ("center", CHAR_CENTER),
                            ("dialog", CHAR_DIALOG), ("surr_level", CHAR_SURR_LEVEL),
                            ("night", CHAR_NIGHT_MODE), ("virtual_x", CHAR_VIRTUAL_X),
                            ("tru_volume", CHAR_TRU_VOLUME), ("led", CHAR_ELEVATE_LED),
                            ("eco_power", CHAR_ECO_POWER), ("auto_hdmi", CHAR_AUTO_HDMI)]:
            try:    out[label] = await self.read_byte(uuid)
            except Exception as e: out[label] = f"<err: {e}>"
        # decoded read-only status
        try:    out["audio_out"] = AUDIO_OUT.get(await self.read_byte(CHAR_AUDIO_OUT), "?")
        except Exception: pass
        try:
            spk = await self.read_byte(CHAR_WLESS_SPK_ST)
            names = ["L","R","L-rear","R-rear","sub"]
            out["wireless_speakers"] = [n for b,n in enumerate(names) if spk>=0 and (spk>>b)&1]
        except Exception: pass
        return out


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------
async def do_scan():
    print("Scanning 8s for BLE devices (look for VIZIO / your soundbar)...")
    devices = await BleakScanner.discover(timeout=8.0)
    for d in devices:
        name = d.name or "?"
        mark = "  <-- VIZIO?" if ("vizio" in name.lower() or "sound" in name.lower()) else ""
        print(f"  {d.address}   {name}{mark}")

async def run(args):
    if args.command == "scan":
        await do_scan(); return
    if not args.address:
        print("error: --address required (run 'scan' first)", file=sys.stderr); sys.exit(2)

    sb_ctx = VizioSoundbar(args.address, verbose=not args.quiet)
    sb_ctx.do_pair = args.pair
    async with sb_ctx as sb:
        c = args.command
        if   c == "services": await sb.dump_services()
        elif c == "info":     print(await sb.info())
        elif c == "eq":       await sb.set_eq(args.arg)
        elif c == "led":      await sb.set_led(args.arg)
        elif c == "surround": await sb.set_surround(args.arg)
        elif c == "elevate":  await sb.set_elevate(args.arg)
        elif c == "height":   await sb.set_height(args.arg)
        elif c == "bass":     await sb.set_bass(args.arg)
        elif c == "treble":   await sb.set_treble(args.arg)
        elif c == "sub":      await sb.set_subwoofer(args.arg)
        elif c == "dialog":   await sb.set_dialog(args.arg)
        elif c == "volume":   await sb.set_volume(args.arg)
        elif c == "mute":     await sb.set_mute(BOOL[args.arg])
        elif c == "virtualx": await sb.set_virtual_x(BOOL[args.arg])
        elif c == "night":    await sb.set_night_mode(BOOL[args.arg])
        elif c == "truvolume":await sb.set_tru_volume(BOOL[args.arg])
        elif c == "center":   await sb.set_center(args.arg)
        elif c == "surrlevel":await sb.set_surr_level(args.arg)
        elif c == "surrbal":  await sb.set_surr_balance(args.arg)
        elif c == "widesurrlevel":  await sb.set_wide_surr_level(args.arg)
        elif c == "widesurrbal":    await sb.set_wide_surr_balance(args.arg)
        elif c == "avdelay":  await sb.set_av_delay(args.arg)
        elif c == "input":    await sb.set_input(args.arg)          # usb|bluetooth|hdmi
        elif c == "powerled": await sb.set_power_led(args.arg)
        elif c == "ecopower": await sb.set_eco_power(args.arg)
        elif c == "autohdmi": await sb.set_auto_hdmi(BOOL[args.arg])
        elif c == "autova":   await sb.set_auto_va(BOOL[args.arg])
        elif c == "announce": await sb.set_announcement(BOOL[args.arg])
        elif c == "spktest":  # disabled: leaves the SL512X unresponsive until power-cycled
            print("spktest is disabled: on tested bars it stops the bar responding to any "
                  "command until it is power-cycled. See PROTOCOL.md section 7.")
        elif c == "name":     await sb.set_name(args.arg)
        elif c == "reset":    await sb.factory_reset(args.arg or "audio")  # audio|all (destructive)
        elif c == "micvol":   await sb.set_mic_volume(args.arg)
        elif c == "micecho":  await sb.set_mic_echo(args.arg)
        elif c == "miccolor": await sb.set_mic_color(args.arg)
        elif c == "musickey": await sb.set_music_key(args.arg)
        elif c == "voicefx":  await sb.set_voice_fx(args.arg)
        elif c == "raw":      # raw <char-suffix4> <int>   e.g. raw B10D 1
            await sb.write_value(_u(args.arg), int(args.arg2))
        else:
            print(f"unknown command {c}", file=sys.stderr); sys.exit(2)
        if c not in ("info", "services"):
            print("ok")

def main():
    p = argparse.ArgumentParser(description="Login-free VIZIO BLE soundbar controller")
    p.add_argument("--address", "-a", help="BLE MAC address of the soundbar")
    p.add_argument("--quiet", "-q", action="store_true")
    p.add_argument("--pair", action="store_true",
                   help="attempt OS-level BLE bonding on connect (try if handshake writes are rejected)")
    p.add_argument("command", help="scan|info | eq led surround elevate height bass treble sub "
                                    "center dialog surrlevel surrbal widesurrlevel widesurrbal "
                                    "volume mute avdelay | virtualx night truvolume | input "
                                    "powerled ecopower autohdmi autova announce spktest name reset | "
                                    "micvol micecho miccolor musickey voicefx | raw")
    p.add_argument("arg", nargs="?", help="value (e.g. music, cyan, on, 6, hdmi)")
    p.add_argument("arg2", nargs="?", help="second value for 'raw'")
    args = p.parse_args()
    asyncio.run(run(args))

if __name__ == "__main__":
    main()
