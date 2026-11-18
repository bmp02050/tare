# Tare Notice (Android)

Native version of the notice log: one-tap buttons, random check-ins (choose Off/3/6/8/10 per day,
09:00-21:00, default 6,
spaced >= span/(2n) apart), append-only CSV with the same schema as `sense/selfreport/server.py`, so
`python -m sense.selfreport.summary <exported csv>` works on it. No dependencies beyond the
Android platform. minSdk 30 (Android 11).

Buttons come from `app/src/main/assets/labels.json`, a copy of `sense/selfreport/labels.json`
(`tests/test_selfreport.py` fails if they drift).

## Feelings wheel and reflection (protocol v2, from 2026-11-16)
Check-ins and **Name a feeling** open a feelings wheel (7 core > 41 secondary > 82 specific words,
from `assets/feelings.json`, a copy of `sense/selfreport/feelings.json`). The wheel is drawn in code,
not shipped as an image, so taps map straight to a word. A tap picks the nearest cell; the chips
under it refine to the exact word. Check-ins then ask "had you noticed it yourself before this
check-in?" (the baseline metric, unchanged), then show open questions. "What happened right before
this?" always appears, plus two others chosen at random; the rest are behind "More questions".
Every answer is optional and can be spoken (system speech recognizer) or typed.

Deliberately qualitative: no strength ratings anywhere (removed 2026-11-16, `meta` row
`no_strength`; older rows keep their values), no scores, no streaks, and the app never shows
numbers back. Analysis happens off the phone (`summary.py`), on purpose. Quick-tap states come in
two groups, Hard and Good (`"group": "good"` in labels.json); the Good ones mirror the hard ones,
e.g. Looping / Let it go, Reactive / Paused.

CSV additions (same columns): `label` = `feel:<core>/<secondary>/<specific>`; check-in labels
`unsure` (couldn't name it) and `none`; `reflect` rows (ref = entry, label = question id or `mind`,
note = answer, latest per question wins); one `meta` row `protocol_v2` marking the switch, so
`summary.py` reports the awareness rate before and after it. Tap a feeling in Recent to reread
or add to your answers.

## Build and install (Windows)
```powershell
$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```
Phone: Settings → About phone → Software information → tap **Build number** 7 times →
Developer options → **USB debugging** on → plug in → accept the prompt.

## Release build
```powershell
.\gradlew.bat :app:assembleRelease    # app\build\outputs\apk\release\app-release.apk
```
Signing reads `~/.tare/signing.properties` (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`; override the path with `TARE_SIGNING`). Neither the key nor the passwords ever go
in the repo. Without that file, the release APK is unsigned. Updates must be signed with the same key,
or Android refuses them: back the key up. Debug and release builds have different signatures, so
switching an installed copy between them needs an uninstall, which deletes its data (export first).

## Data
The CSV lives in app-private storage. Use **Export CSV** in the app to save a copy
(Drive, Downloads, ...). Uninstalling the app deletes its data: export first.

## Watch (wear/): body-triggered check-ins
Galaxy Watch4+ / any Wear OS 3+ watch. Health Services delivers heart rate and steps in the
background. The watch keeps a 7-day "resting" level (20th percentile of readings taken after
10+ minutes without steps, outside exercise and sleep). When a still reading is 20+ bpm above it,
the watch messages the phone app (Wearable Data Layer, path `/tare/body`). The phone logs a
`prompt` row with label `body` and posts its normal, neutral check-in. Check-ins opened from any
notification carry `ref` = that prompt's id, so `summary.py` compares random vs body prompts.
Limits: 09:00-21:00, 90 min cooldown, at most 4/day, and no prompts until ~60 still readings exist.
The watch shows no numbers; its own debug log is `files/body_events.csv` (`adb run-as`).

Install: on the watch, Settings > About watch > Software > tap Software version 5x; Developer
options > ADB debugging + Wireless debugging > Pair new device. Then:
```powershell
adb pair <ip>:<pair-port> <code>; adb connect <ip>:<port>
.\gradlew.bat :wear:assembleDebug
adb -s <ip>:<port> install -r wear\build\outputs\apk\debug\wear-debug.apk
```
Open **Tare** on the watch once and allow heart rate (all the time) and physical activity.
Both apps must be signed with the same key (same machine's debug key) for messages to arrive.
