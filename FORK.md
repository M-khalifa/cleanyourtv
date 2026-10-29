# What this fork adds

This fork follows [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) and adds the pieces below. All
of them are off by default; tested on a TCL Q6 (Google TV), Android 12.

## Prayer and weather card on Home

One glass card under the launcher's top buttons with the next prayer from the Prayer Times add-on and the
weather now from the Weather add-on, alternating between the two. With only one add-on installed the card shows
that one; with neither it stays hidden. As the prayer gets close it stops alternating and turns orange
(`prayer_soon_minutes`, 60), then red (`prayer_imminent_minutes`, 15). A weather reading older than three hours is
not shown.

![The card with the weather now](docs/switches/home_widgets.jpg)

Turn it on in `launcher/app/src/main/res/values/config.xml` with `<bool name="home_widgets">true</bool>`, then
build and install as usual: `TV_HOST=<tv ip> launcher/build-and-install.sh`. The launcher reads the two add-ons'
content providers, declared in `<queries>`: no new permission, no network, no background service.

## Three small launcher keys

`bluetooth_button`, `tools_apps` and `icon_tiles`, described in [themes/README.md](themes/README.md) and
[docs/switches.md](docs/switches.md).

## Prayer Times add-on

A settings screen on the TV (city, method, Asr school), and opt-in reminders: a countdown and banner before each
prayer, the Azan at the prayer time, a morning recitation and Al-Kahf on Fridays, which can wake the TV from
standby and put it back afterwards. No Azan recording is included; you choose your own. See
[its README](addons/prayer-times/README.md).

## Weather add-on

`NowProvider`, the current conditions the Home card reads.

## Review

The Prayer Times add-on, the Home card and the merge with upstream were checked before release with
[cross-review](https://github.com/M-khalifa/SkillArray/tree/main/plugins/cross-review/skills/cross-review), part of
[SkillArray](https://github.com/M-khalifa/SkillArray): Claude and Codex review the same code and cross-examine each
other's findings. Three runs gave 33 findings: 31 confirmed and fixed, 1 refuted, and 1 left open at low stakes
(whether the exact prayer times could hint at the city).

The merge with upstream v3.1 and the recitation auto-off were checked the same way before they went in: two runs and
a second round on the fixes gave 16 findings: 15 fixed and 1 refuted. A last single-reviewer pass on
those fixes found four low-stakes edge cases, each of which leaves the TV on rather than turning it off.
