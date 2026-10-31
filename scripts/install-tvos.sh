#!/usr/bin/env bash
# Build TVOS from source and install it on the TV: TV_HOST=<tv ip> scripts/install-tvos.sh
# Google TV keeps its own home enabled, because the Assistant shows its results there. The Home button helper then
# keeps the remote on TVOS. Pass STOCK_LAUNCHER yourself to override that choice.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the TV IP, e.g. TV_HOST=192.168.1.50 scripts/install-tvos.sh}"
export TV_HOST

PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
tv() { "$PY" -I tools/tv.py "$1"; }

GOOGLE_TV=0
if tv 'pm list packages com.google.android.apps.tv.launcherx' | grep -q launcherx; then GOOGLE_TV=1; fi
if [ "$GOOGLE_TV" = 1 ] && [ -z "${STOCK_LAUNCHER:-}" ]; then export STOCK_LAUNCHER=none; fi

status=0
launcher/build-and-install.sh || status=$?
if ! tv 'pm path com.gathin.tvhome' | grep -q base.apk; then
  echo "ERROR: TVOS did not install; see the messages above." >&2
  exit 1
fi
# With the stock home kept on purpose, the Home check above always complains. Anywhere else it is a real failure.
if [ "$status" != 0 ] && [ "${STOCK_LAUNCHER:-}" != none ]; then exit "$status"; fi

# A reinstall stops the helper, and writing the same setting back is ignored, so take it off the list and put it back.
# Other accessibility services on the list stay.
ours=com.gathin.tvhome/.HomeKeyService
others=$(tv 'settings get secure enabled_accessibility_services' | tr -d '\r' | tail -n 1 | tr ':' '\n' \
  | grep -v -e "^$ours\$" -e '^null$' -e '^$' | paste -s -d ':' - || true)
tv "settings put secure enabled_accessibility_services '$others'"
sleep 1
tv "settings put secure enabled_accessibility_services '${others:+$others:}$ours'"
tv 'settings put secure accessibility_enabled 1'

# One shell call each: chained commands can stop silently after the first.
tv 'pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION'
tv 'pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS'
tv 'appops set com.gathin.tvhome GET_USAGE_STATS allow'
tv 'appops set com.gathin.tvhome WRITE_SETTINGS allow'
tv 'cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.WatchListener'
tv 'ime enable com.gathin.tvhome/.TvKeyboard'
tv 'ime set com.gathin.tvhome/.TvKeyboard'

sleep 5
tv "dumpsys accessibility | grep -E 'Bound services|Crashed'"
echo "TVOS is installed. The old keyboard is still enabled until you have tested typing."
