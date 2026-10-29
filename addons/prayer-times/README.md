# Prayer Times

Today's five prayer times and sunrise for one city, full screen on the TV, with the next prayer highlighted and a
countdown to it, plus today's Gregorian and Hijri dates. Each prayer has its Arabic and English name. For the
[Clean Your TV](../../README.md) launcher it declares a [module card](../../docs/modules.md), so once you answer
**Show card** its tile reads "Prayer Times" with a green glow; on any other Android TV home it is an ordinary app.

![The prayer times page](https://raw.githubusercontent.com/majnioui/cleanyourtv-contrib/pr-assets/addon-prayer-times/prayer-times.png)

- **Data** comes from [Aladhan](https://aladhan.com/), which needs no account or key.
- **Settings on the TV** (the Settings button on the page): the city, found by a search; the calculation method
  (ISNA, Muslim World League, Egyptian, Umm al-Qura, Karachi, Diyanet, Morocco, Moonsighting Committee); Asr
  standard or Hanafi; and each reminder below, **all off until switched on**. Only what you change is stored;
  `app/src/main/res/values/config.xml` holds the defaults for a build (leave its location empty and its reminders
  false for a build anyone may install).
- **Reminders switch** (`reminders` in `app/src/main/res/values/config.xml`, off by default): a small
  alert card at the top right when a prayer time arrives, with one notification chime; it closes after
  10 seconds or on any key. It is separate from the reminders below and needs no adb setup.
- It reads Aladhan when the page opens and again after midnight. The countdown updates every minute.
  Offline, the last times stay on screen.
- 24-hour times, localized prayer names and strings for English, Indonesian (`values-in`), and Arabic (`values-ar`).

## Alerts, Azan and recitations

Each is a switch in Settings, off by default. Volumes, lead minutes, the recitation's address and the early wake
are build defaults in `config.xml`.

- **Before each prayer** a countdown pill over any app (`alert_soon_minutes`) and a banner (`alert_lead_minutes`).
  Neither wakes a TV in standby.
- **At the prayer** the Azan (see [Azan sound](#azan-sound)): what is playing pauses and resumes after it, and a window with
  **Dismiss Azan** stays for its length. TV on: Fajr at `azan_volume_fajr`, the others at the volume as it is. TV
  in standby (`azan_wake_tv`): woken at `azan_volume_fajr` or `azan_volume`, back to standby when the Azan ends
  unless someone pressed a key.
- **Every morning** at `morning_quran_time` a surah (`morning_quran_url`, Al-Baqarah) at `morning_quran_volume`;
  **on Fridays** Al-Kahf `kahf_minutes_before_dhuhr` before Dhuhr at `kahf_volume`. They pause for an Azan, and a
  TV they woke goes back to standby at the end. A recitation that plays to its end turns the TV off after a
  one-minute warning even if the TV was already on; any key on the warning keeps it on. Stopping it, or another
  app taking the sound, keeps the TV on.
- **In Ramadan** (Umm al-Qura, with a −2 to +2 day adjustment in Settings) the banners, the Home card and the
  Maghrib Azan window read "Suhoor ends" and "Iftar".
- **Test the Azan** in Settings plays it at once.
- Alarms delivered more than a few minutes late (the TV slept through them) are skipped, never played late.

### Azan sound

The add-on ships no recording; you choose the one you like. Until there is one, the Azan stays silent and only
the banner shows. Either:

- In Settings, **Azan sound**: type the address of an MP3 and the TV downloads it once (20 MB at most). The old
  sound stays until the new one is known to play.
- Or copy a file over adb, then open Settings once so the alarms pick it up:
  `adb push my-azan.mp3 /sdcard/Android/data/com.majnioui.tv.prayer/files/azan.mp3`

One recording with an open licence is
[The Adhan by Aaqib Azeez](https://commons.wikimedia.org/wiki/File:The_Adhan_-_Muslim_Call_to_Prayer_-_Aaqib_Azeez.mp3)
on Wikimedia Commons (87 s, CC BY-SA 4.0, by Atcovi). Its direct address for the download box:
`https://upload.wikimedia.org/wikipedia/commons/7/7d/The_Adhan_-_Muslim_Call_to_Prayer_-_Aaqib_Azeez.mp3`

**Waking a TV in standby.** On a TCL tested here an alarm can start its screen up to about three minutes late while
the screen is off, but at once while it is on. So `prewake_minutes` before each Azan and recitation a TV in
standby is woken on a dark clock, and the event itself starts on time. TCL needs **Settings > System > Power &
Energy > TV Standby > Standby Mode: Network Standby**; in Doze the alarm cannot wake the TV at all.

**One-time setup over ADB**, after installing:

```bash
cmd appops set com.majnioui.tv.prayer APP_AUTO_START allow; cmd appops set com.majnioui.tv.prayer APP_ASSOC_START allow
appops set com.majnioui.tv.prayer SYSTEM_ALERT_WINDOW allow
pm grant com.majnioui.tv.prayer android.permission.WRITE_SECURE_SETTINGS
settings put secure enabled_accessibility_services "$(settings get secure enabled_accessibility_services):com.majnioui.tv.prayer/com.majnioui.tv.prayer.SleepService"
cmd notification allow_listener com.majnioui.tv.prayer/com.majnioui.tv.prayer.RevivalService
```

The first line lets TCL start the app from its alarms. `SleepService` (accessibility, no events, cannot read the
screen) only puts the TV back to standby; `WRITE_SECURE_SETTINGS` lets the app switch it back on after a force-stop
drops it. `RevivalService` (notification listener, reads no notification) re-arms the alarms after a force-stop,
which cancels them.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Aladhan: the day's times; the recitation streams. |
| `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED` | Alerts, Azan and recitations on time, re-armed after a restart. |
| `SYSTEM_ALERT_WINDOW` | The pill, banner and Azan window over other apps; also what lets an alarm open a screen at once. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | The pill and the Azan while another app is on screen. They run only while one is showing or playing. |
| `WRITE_SECURE_SETTINGS` (ADB only) | Switching `SleepService` back on, nothing else. |
| `WAKE_LOCK` | The `reminders` switch: briefly wakes the screen for its alert card. |

What is sent: the date, the chosen city's latitude and longitude, the method and the Asr school to Aladhan; the
name you type in the city search to [Open-Meteo's geocoding](https://open-meteo.com/en/docs/geocoding-api); and a
request for the surah's MP3 when a recitation plays. No account, no telemetry, no ads, no location permission. The
home-screen provider gives only the day's prayer times, whether it is Ramadan, the Hijri date and whether the
countdown is on: no coordinates or city name, though prayer times always hint at the general area.
Backups are off.

## Home card (Clean Your TV launcher)

The launcher's home card shows the next prayer only while this add-on is installed and has a city; without it the
card shows the weather alone. The Hijri date there is a switch in Settings.

## Build and install

JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher:

```bash
cd addons/prayer-times && ./gradlew assembleRelease
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py --push app/build/outputs/apk/release/app-release.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

If `reminders` is set to `true`, grant the overlay permission so the on-screen alert card can display over playing video and other apps:

```bash
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'appops set com.majnioui.tv.prayer SYSTEM_ALERT_WINDOW allow'
```

On Android 14+ (API 34), exact alarms are off by default. The add-on safely falls back to `setAndAllowWhileIdle`, or exact timing can be explicitly granted via:

```bash
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'appops set com.majnioui.tv.prayer SCHEDULE_EXACT_ALARM allow'
```

> **Note:** Force-stopping the app (`am force-stop com.majnioui.tv.prayer`) removes all pending alarms scheduled in Android's `AlarmManager`. When testing real alarms, do not force-stop the app.

Press Home: the launcher asks once whether to show the card. Change the answer later by holding OK on
Settings, then Module cards.

The release build is signed with the debug key so it installs over ADB like the launcher. Sign your own builds
with your own key if you hand them out; the launcher pins its Show answer to the signing key.

## Credits

Prayer icons: [Lucide](https://lucide.dev) (ISC) and [Feather](https://feathericons.com) (MIT). Font: Outfit
(SIL Open Font License, `FONT-LICENSE-OFL.txt`).

## Test alarms (adb)

`am start -n com.majnioui.tv.prayer/.TestHooksActivity --ei test_alert_seconds 60 [--ei test_prayer 2]` arms the
countdown, the banner and the Azan 0, 40 and 80 s after the delay; `--ei test_quran_seconds 180 [--ei
test_quran_surah 108]` a recitation (Al-Kawthar is a short one). The activity needs `DUMP`, which adb's shell holds
and no app can, so other apps cannot fire them. To see the early wake, let the delay exceed `prewake_minutes`.

## Tested on

- Xiaomi TV (MiTV-MOOR2), Google TV 11, RTM5.220609.003: the page, the countdown, the card prompt and the card shown in the launcher's dock.
- TCL 43A50 (Smart TV Pro), Android 11 (build RP1A.200622.001): background prayer alarms, top-right unobtrusive alert card over active apps and home screen, and auto-dismiss after 10 seconds.

TCL Google TV (Android 12) in Network Standby: the Azan and the recitations waking the TV through the early wake on
time, the volume set and restored, the recitation pausing for the Azan, back to standby at the end, and recovery
from a force-stop. In the TV's default standby mode ("Doze") alarms cannot wake it.

## Maintainer

[@majnioui](https://github.com/majnioui)
