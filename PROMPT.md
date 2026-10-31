# The TVOS prompt

Paste everything below the line into Claude Code on a computer on the same network as the TV. It gathers
every file it needs from this repository, at this exact release.

**Before you start, on the TV:** Settings → Device Preferences → About → press OK on Build 7 times. Go back to
Developer options and turn on USB debugging, plus Network or Wireless debugging if your TV shows them.

---

I want to install TVOS on my Android TV. My TV model is: <MODEL NAME HERE>. Work on the real TV, step by step.

**1. Gather the files.** Clone the exact release, then work only from that folder:

```bash
git clone --depth 1 --branch v1.1.0 https://github.com/Randomstufteam/TVOS.git
cd TVOS
python3 -m venv tools/venv
tools/venv/bin/pip install -r tools/requirements.txt
```

On Windows the second tool path is `tools/venv/Scripts/pip`. You also need JDK 17 and the Android SDK (platform 34,
build-tools 34.0.0). If either is missing, tell me what to install, or use `tools/docker-build.sh`. Never download an
APK from anywhere: TVOS is always built here from this source.

**2. Read the rules first.** Read `docs/base-procedure.md` in full and follow it for connecting, backing up,
debloating, verifying and closing ADB. Its safety rules are not optional. They include:
- Back up the package list and settings before the first change.
- Disable, never uninstall, one package per shell call, in small batches. No root.
- Never disable what the TV needs: HDMI and tuner services, remote pairing, Play Store and Play Services, or
  Android's core packages. `tools/disable.sh` refuses those lists.
- Never open a factory or test app, not even to look.
- Ask me before touching anything my household uses: streaming apps, the voice assistant, Chromecast, firmware updates.
- If this is not an Android or Google TV (no ADB port, or Tizen, webOS, Roku OS, VIDAA or SmartCast), stop.
- If the TV refuses disables or sideloads, or its SDK is below 26, stop and say so. Do not retry in a loop.

Where the base procedure describes writing a launcher, skip that part: TVOS is that launcher. It is the source in
`launcher/`. `examples/hisense-hirano/debloat.txt` is the list from the one TV TVOS was built on. Treat it as a guide
and check every package on my TV before using it.

**3. Fit TVOS to my TV.** Edit only `launcher/app/src/main/res/values/config.xml`:
- `dock_apps`: ask me which apps I want and in what order. Up to 7 slots with the default `columns` of 7.
- `hidden_apps`: packages on my TV that should not show as tiles. The ones listed are from a Hisense TV.
- `settings_key_keycodes`: the value 83 is one Hisense remote's gear button. Keep it only if mine sends the same key.
- `stock_home_redirect`: `com.google.android.apps.tv.launcherx` on Google TV. Empty it on any other TV.
- `home_long_press_home`: true only if the Home button helper is used.

**4. Build and install.**

```bash
TV_HOST=<tv ip> scripts/install-tvos.sh
```

The script builds from source, installs TVOS, grants what it needs over ADB and turns on the Home button helper and
TV Keyboard. On Google TV it leaves the stock home enabled, because the Assistant shows its results there, so the
message saying Home still resolves to `launcherx` is expected. On a CVTE board (any `com.cvte.*` package), follow the
CVTE rule in `docs/base-procedure.md`: reboot once and confirm Home and ADB come back before anything else.

**5. Check it with me.**
- Take screenshots with `tools/tv.py --screenshot shots/<name>.png` and look at them.
- Ask me to press the real Home, Back and gear buttons on the remote. An injected key proves nothing for these.
- Never run `am force-stop com.gathin.tvhome`. It switches the Home button helper off, and Google TV's home takes over.
- Ask me to open Weather > Change city and type with the remote. Do not continue until I confirm the keyboard works.
  If it does not, switch back with `ime set` to the keyboard listed by `ime list -s` and tell me.
- Only after I confirm, ask whether I want the old keyboard disabled. Disable it only if I say yes, using the
  package `ime list -s` shows. Keeping it is a safe choice.

**6. Finish.** Give me a restore script for what you changed. Then close ADB as `docs/base-procedure.md` describes,
and confirm the result with `tools/adb-closed.py <tv ip>`.
