# Tare

A small, free tool for noticing what's going on in your own head, in the moment.

Tare is about self-awareness and metacognitive practice. **It is not a medical device** and it
does not diagnose, treat or replace anything. If you're working with a therapist, it can give you
your own words to bring to a session.

The name comes from a scale's tare button, which subtracts the container's weight so the true
reading comes through.

## What the app does (Android, `android/`)
- **Random check-ins** (Off / 3 / 6 / 8 / 10 a day, 09:00-21:00): "How are you right now?"
- **Feelings wheel**: pick a word (tap, zoom into a family, or search), then say whether you'd
  already noticed it yourself.
- **Reflection questions**, all optional, typed or spoken: what happened right before this? where
  do you feel it? what do you need?
- **Quick taps** for states you catch yourself in, hard ones (looping, reactive, ...) and good
  ones (let it go, present, in flow, ...).
- **"If you're not safe right now"**: 988 call/text (US) and a plan you write yourself.

On purpose, it has no scores, no streaks and no ratings, and it never turns feelings into numbers.

## Your data stays yours
- The app has **no internet permission**. Everything stays on the phone, in the app's private
  storage. There are no accounts, no analytics, no ads and no server, and nobody else can see it.
- **Export CSV** saves a copy wherever you choose. From there you can send it to your therapist,
  or anyone else, through your own email, messages or files. It never passes through the
  developer.
- Uninstalling deletes the data. Export first if you want to keep it.

## Install
Download the APK from Releases and open it on the phone (Android 11+). Android will ask you to
allow installs from your browser or file manager. To update, install the newer APK over it, and
your data stays.

Build it yourself: see [`android/README.md`](android/README.md).

## Also in this repo
- `android/wear/`: an experimental Wear OS companion that suggests a check-in when your heart
  rate rises while you're sitting still. Not released yet.
- `sense/`: research code for streaming EEG from a Muse S Athena headset (Python), and the
  analysis script for an exported log (`python -m sense.selfreport.summary <csv>`).

## License
[PolyForm Noncommercial 1.0.0](LICENSE). You can use, study, change and share Tare for any
noncommercial purpose, free. You may not sell it, charge for it, put ads in it, or use it in a
commercial product or service. This makes Tare *source-available*, not "open source" in the OSI
sense.

## If you're in crisis
In the US, call or text **988**. Elsewhere: <https://findahelpline.com>.
