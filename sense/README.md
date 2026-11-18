# sense/ — acquisition (tare-p1: Muse S Athena EEG)

## Setup

### Linux laptop (Ubuntu/Debian; BlueZ >= 5.55)
```bash
sudo apt update && sudo apt install -y bluez python3-venv python3-tk git
systemctl is-active bluetooth            # expect: active
git clone https://github.com/bmp02050/tare.git && cd tare
python3 -m venv .venv && source .venv/bin/activate
pip install -U pip && pip install -r requirements.txt
pytest -q                                # 9 passed, incl. decode of a real Athena capture
python -m sense.stream_eeg --simulate --protocol   # full pipeline, no hardware
```
Do **not** pair the Muse in the OS Bluetooth settings. Bleak connects directly, and an OS-level
pairing can grab the device. Close the Muse phone app (or turn off phone Bluetooth) before
connecting: the headband accepts one central at a time.

### Windows 10/11 (works with the same code; bleak uses WinRT)
```powershell
py -3.11 -m venv .venv; .venv\Scripts\Activate.ps1
pip install -U pip; pip install -r requirements.txt
pytest -q
```

### Android
Not supported at tare-p1. Termux's Python can't reach Android's BLE GATT API (no BlueZ,
no HCI access for unrooted apps, termux-api has no GATT). The real options are a
python-for-android/Buildozer APK using bleak's Android backend, or porting
`muse_athena.py` (~150 lines of protocol logic) to Kotlin alongside the Samsung/Meta
SDKs in tare-p2. The second is the recommended path.

## Run
```bash
python -m sense.stream_eeg                 # live plot + 1 line/s on stdout
python -m sense.stream_eeg --protocol      # 4-min guided eyes-open/closed test, auto-scored
python -m sense.stream_eeg --no-plot       # headless
python -m sense.analyze_session data/sessions/<file>_protocol.csv   # re-score a session
```
Outputs (gitignored, personal data): `data/sessions/*.csv` (per-second features),
`data/raw/*.bin` (raw BLE payloads, Amused MUSB format, re-decodable).

## Stdout line
```
t=   44s    EC2  fs= 256.8Hz  alpha= 14.90dB  theta=  7.05dB  a/t= 4.93  [TP9:ok AF7:ptp AF8:ok TP10:ok]  gaps=0
```
`fs` = effective EEG rate over the last 5 s (must be ~256). Per-channel gate: `ok`,
`rail` (electrode off skin), `ptp` (blink/jaw/movement), `flat` (dead).
`gaps` = notifications lost according to the device's packet counter.

## Notice log (self-report baseline) — `sense/selfreport/`

One-tap buttons for what you notice, plus 3 random check-ins a day. Standard library only,
append-only CSV. Runs on the phone itself in Termux, so it works anywhere and the data stays on the phone.

### Phone setup (Android, one time)
1. Install **F-Droid** (f-droid.org), then from F-Droid install **Termux** and **Termux:API**
   (both from F-Droid; Play Store builds can't talk to each other). Optional: **Termux:Boot**.
2. Android Settings → Apps → Termux → Battery → **Unrestricted** (Samsung: also add Termux to
   "Never sleeping apps"), and allow notifications for Termux:API.
3. In Termux:
   ```sh
   pkg update && pkg upgrade -y
   pkg install -y python git termux-api
   termux-setup-storage            # grant storage; creates ~/storage/shared
   git clone https://github.com/bmp02050/tare.git ~/tare
   termux-notification --title test --content "check-ins work"   # should pop a notification
   ```
4. Run it:
   ```sh
   termux-wake-lock
   cd ~/tare && python -m sense.selfreport.server --data ~/storage/shared/tare
   ```
5. In Chrome on the phone open `http://127.0.0.1:8765` → ⋮ → **Add to Home screen**.

Optional auto-start at boot (needs Termux:Boot, opened once):
```sh
mkdir -p ~/.termux/boot && cat > ~/.termux/boot/tare.sh <<'SH'
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock
cd ~/tare && python -m sense.selfreport.server --data ~/storage/shared/tare >> ~/tare-notice.log 2>&1
SH
chmod +x ~/.termux/boot/tare.sh
```

Data: `Internal storage/tare/notices.csv` (or the page's **Export CSV**). Baseline numbers:
`python -m sense.selfreport.summary ~/storage/shared/tare/notices.csv`.
Custom buttons: copy `sense/selfreport/labels.json` to `~/storage/shared/tare/labels.json` and edit.
Update the code: `cd ~/tare && git pull`, then restart the server.
