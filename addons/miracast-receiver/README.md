# Miracast Receiver

Lets a Windows 10/11 PC cast its screen (video and sound) to the TV with **Win+K**. The TV shows up in the
Win+K list under its receiver name (default **KhalifaTV**); pick it and the desktop appears full screen on the TV.
Press **Back** on the remote to disconnect.

Fork-only add-on, tested on a TCL Q6 Google TV (Android 14). It needs [Shizuku](https://github.com/thedjchi/Shizuku)
because Android lets only system apps or the shell user advertise a Wi-Fi Display receiver.

## How it works

Two parts in one APK:

- **Helper** (runs as the shell user inside Shizuku). Turns on Wi-Fi Display "primary sink" on the Wi-Fi Direct
  interface, sets the Wi-Fi Direct name, and adds the Microsoft "Miracast over Infrastructure" (MS-MICE) element
  with the TV's host name and Wi-Fi address. By default it only keeps the TV discoverable (listen and discover
  again every 30 s, paused during a session): Android adds the MS-MICE element to discovery frames only, not to
  a group owner's beacons. **Wi-Fi Direct group mode** (Settings, off by default) instead keeps a Wi-Fi Direct
  group up with the TV as owner and accepts every connection request without a prompt, for PCs that can run the
  session over Wi-Fi Direct. It logs to logcat with the tag `MiracastHelper`.
- **App** (normal app). A foreground service answers mDNS for `<name>.local` and `_display._tcp`, listens on TCP
  7250 for MS-MICE, runs the Wi-Fi Display RTSP session, receives RTP/MPEG-TS on UDP 19000, and decodes H.264 video
  and LPCM or AAC audio with the TV's hardware decoders. It logs with the tag `Miracast`.

Windows tries MS-MICE first: the session then runs over the home Wi-Fi, so a PC whose firewall blocks the Wi-Fi
Direct link still works. In group mode, or when MS-MICE fails, Windows joins the TV's Wi-Fi Direct group and the
helper opens the receiver for the PC's address.

## One-time setup

1. Install and start Shizuku on the TV (pair it with **Wireless debugging**; turn on **Start on boot**).
2. Install this add-on: `adb install -r app-release.apk`.
3. Open **Miracast Receiver** from the TV's app list once. Press **Grant Shizuku access** and allow it.
4. The screen shows "Shizuku access granted" and the helper status (`wfd=true`, `mode=listen`, `vendor=OK ...`).

After a TV restart Shizuku starts itself, passes its binder to the app, and the helper and the listener come back
on their own.

## Settings (on the TV)

- **Receiver on/off.** Off removes the Wi-Fi Direct group and stops the listeners.
- **Wi-Fi Direct group mode** (off by default): see [How it works](#how-it-works).
- **Name.** Shown in Win+K. Letters, digits and `-` of it also form the mDNS host name (`KhalifaTV.local`).
- **Grant Shizuku access / Restart helper.**
- **MS-MICE capability byte** (`0x05` by default) for testing other bit orders.
- **Recent log**: the last session steps.

## Testing without Win+K

- Helper from adb, without Shizuku (stop it with Ctrl+C):
  `adb shell 'CLASSPATH=$(pm path io.github.mkhalifa.miracast | cut -d: -f2) app_process / io.github.mkhalifa.miracast.helper.HelperMain --name KhalifaTV --mice KhalifaTV [--group true]'`
- Session against any RTSP Wi-Fi Display source on the LAN:
  `adb shell am start -n io.github.mkhalifa.miracast/.ReceiverActivity --es source <ip> --ei port <port>`
- From the PC: `Resolve-DnsName KhalifaTV.local` and `Test-NetConnection <tv-ip> -Port 7250`.

## Limits

- No HDCP: protected video (streaming apps in a browser) stays black. The desktop and normal windows work.
- No UIBC: the TV remote does not control the PC.
- Video up to 1920x1080 at 60 Hz (H.264 constrained baseline/high, level 4.2); audio LPCM 44.1/48 kHz stereo or AAC.
- Frames are shown as soon as they are decoded; audio is kept within about 100 ms and dropped beyond that.
- In group mode, while the TV's Wi-Fi Direct group is up, the TV cannot join another Wi-Fi Direct device.
