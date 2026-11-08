# Disclaimer & legal notes

*This document is provided for transparency. It is not legal advice. If you need
legal certainty for your situation, consult a qualified attorney in your
jurisdiction.*

## No affiliation

This project is **independent and unofficial**. It is **not** affiliated with,
authorized by, endorsed by, sponsored by, or connected to VIZIO, Inc., Walmart
Inc., or any of their subsidiaries or affiliates. All work here is that of
independent contributors.

## Trademarks

"VIZIO", "Elevate", and related names, logos, and product names are trademarks or
registered trademarks of their respective owners. They are used in this project
**only nominatively** — that is, to accurately describe the hardware this software
interoperates with (for example, "a controller for VIZIO soundbars"). Such
descriptive use does not imply any endorsement or affiliation.

- This project does **not** use any VIZIO logo, wordmark styling, app icon, or brand
  artwork.
- The software name ("Unbarred") is our own. Where a product name appears — including
  the descriptive "for VIZIO" in the app label and repository name — it is only to
  state compatibility, nothing more.
- If you redistribute or fork this project, keep it clearly identified as an
  independent work and do not present it as an official VIZIO product.

> If you prefer to avoid the brand name entirely, the app's display name and
> `applicationId` can be changed freely — nothing in the protocol depends on them.

## What this project is (right to repair / interoperability)

This is an interoperability project: it documents how a device communicates and
provides an independent, compatible client so owners can keep using hardware they
have already purchased. Reverse engineering carried out to achieve interoperability
between an independently created program and other programs is widely recognized —
for example, under the interoperability provisions of the U.S. DMCA
(17 U.S.C. § 1201(f)) and the EU Software Directive (2009/24/EC, Art. 6). The intent
here is restoration of owner control and compatibility, not circumvention for
infringement.

### About the handshake response

`PROTOCOL.md` documents a fixed 14-byte value that the sound bar accepts during its
local Bluetooth handshake for one fixed client id. It is included because it is
**necessary for interoperability** — without it, an independent client cannot speak
to the device. Note what it is and isn't:

- It is the **same on every bar and every connection** for that client id; it does not
  depend on the bar's challenge.
- It is **not** a per-user secret, a private signing key, a password, or anything
  that protects a user's personal data or account. It guards nothing but the local
  Bluetooth link to a speaker the user already physically controls.
- This project does **not** publish the vendor app's internal key or token algorithm;
  it only replays the fixed response.

## How the protocol was derived

To be transparent about how this project was made:

- It was built by **one developer** who owns the hardware and had previously used the
  vendor's app with it. This is **not** a clean-room implementation in the formal sense
  (separate analysis and implementation teams).
- Development was driven with **AI assistance**. AI tools were used to reverse engineer
  the BLE protocol and the local authentication handshake from the vendor app, and to
  help capture and interpret Bluetooth LE traffic between the app and the author's own
  soundbar.
- The goal was interoperability only: documenting the protocol and writing a new,
  independent client from it.

## No warranty; use at your own risk

This software is provided "AS IS", without warranty of any kind, as stated in the
[LICENSE](LICENSE). By using it you accept all risk, including but not limited to:

- Changing audio or device settings in ways you didn't intend.
- Potential effects on your device's manufacturer warranty or support.
- The possibility — however small — of putting the device in an unexpected state.

The authors and contributors are **not liable** for any damage to equipment, loss of
functionality, or other harm arising from use of this software. Avoid characteristics
you have not identified; never write to the factory-reset or firmware/OTA
characteristics unless you fully understand and accept the consequences.

## No circumvention of account/DRM, no redistribution of vendor code

This project does **not** contain, modify, repackage, or redistribute the vendor's
application or any of its proprietary code or assets. The clients are newly written
from the documented on-the-wire protocol; no vendor code is copied into them. It does not
attempt to defeat account security protecting a user's personal data, access paid
services without authorization, or enable any infringement.

## Your responsibility

Laws differ by country and change over time. You are responsible for ensuring your
use of this software complies with the laws and agreements that apply to you,
including any contract with the device manufacturer or retailer.
