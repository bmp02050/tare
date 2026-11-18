"""Muse S Athena EEG acquisition over BLE.

What comes from Amused (``muse_athena_protocol``, pinned in requirements.txt):
GATT UUIDs, the command encoding, the Athena init sequence, and the 14-bit
EEG decoder.

What is ours, and why:

* ``walk_payload`` replaces ``proto.parse_payload``. Amused's parser stops at
  the first TAG it does not recognise. Athena firmware emits TAG 0x53
  mid-payload in roughly half of all notifications, so everything after it --
  including EEG -- is silently dropped. On Amused's own captured Athena data
  this yields ~215 Hz effective EEG instead of 256 Hz. Treating 0x53 as a
  24-byte opaque subpacket parses 60/60 affected packets cleanly end-to-end
  and restores 256 Hz (see tests/test_athena_parse.py).
* ``MuseAthenaStream`` replaces ``MuseStreamClient.connect_and_stream``, which
  swallows all exceptions, has no stop hook, no reconnect, and no stall
  detection.

Every notification is stamped with a host monotonic receive time and the
device's 16-bit packet sequence number (header bytes 1-2, inferred from
captures: increments by exactly 1 per notification), so drops are countable.
"""

from __future__ import annotations

import asyncio
import json
import logging
import threading
import time
from collections import Counter
from dataclasses import dataclass, field
from typing import Callable, Iterator, Optional

import numpy as np
import muse_athena_protocol as proto
from bleak import BleakClient, BleakScanner

log = logging.getLogger("tare.sense.muse")

EEG_FS = 256
EEG_CHANNELS = ["TP9", "AF7", "AF8", "TP10"]  # first 4 columns in both 4ch and 8ch modes
EEG_FULL_SCALE_UV = proto.EEG_SCALE * 16383  # ~1450 uV; samples at 0 or here are railed

TAG_UNDOCUMENTED_53 = 0x53
SUBPACKET_DATA_LEN = {tag: cfg[3] for tag, cfg in proto.SENSOR_CONFIG.items()}
SUBPACKET_DATA_LEN[TAG_UNDOCUMENTED_53] = 24  # inferred, see module docstring
EEG_TAGS = {proto.TAG_EEG_4CH: 4, proto.TAG_EEG_8CH: 8}

STALL_TIMEOUT_S = 3.0  # no sensor notification for this long while connected -> reconnect


# ---------------------------------------------------------------------------
# Payload parsing
# ---------------------------------------------------------------------------
@dataclass
class ParseStats:
    packets: int = 0
    eeg_samples: int = 0
    seq_gaps: int = 0  # notifications missing according to the device sequence counter
    unknown_tags: Counter = field(default_factory=Counter)
    short_packets: int = 0  # shorter than header: usually MTU not negotiated
    last_seq: Optional[int] = None

    def note_seq(self, seq: int) -> None:
        if self.last_seq is not None:
            gap = (seq - self.last_seq - 1) & 0xFFFF
            # A huge "gap" is a device-side counter reset (e.g. after reconnect), not loss.
            if gap < 1000:
                self.seq_gaps += gap
        self.last_seq = seq


def packet_seq(payload: bytes) -> int:
    return payload[1] | (payload[2] << 8)


def walk_payload(payload: bytes, stats: Optional[ParseStats] = None) -> Iterator[tuple[int, bytes]]:
    """Yield ``(tag, data)`` for each subpacket in one BLE notification.

    Layout: 14-byte header whose byte 9 is the first subpacket's TAG, then that
    subpacket's data; then repeated ``[TAG(1)][sub-header(4)][data(N)]``.
    """
    if len(payload) < proto.HEADER_SIZE + 1:
        if stats is not None:
            stats.short_packets += 1
        return
    tag = payload[9]
    if tag not in SUBPACKET_DATA_LEN:
        if stats is not None:
            stats.unknown_tags[tag] += 1
        return
    end = proto.HEADER_SIZE + SUBPACKET_DATA_LEN[tag]
    if end > len(payload):
        return
    yield tag, payload[proto.HEADER_SIZE:end]
    off = end
    while off + 5 < len(payload):
        tag = payload[off]
        n = SUBPACKET_DATA_LEN.get(tag)
        if n is None:
            if stats is not None:
                stats.unknown_tags[tag] += 1
            return
        start, end = off + 5, off + 5 + n
        if end > len(payload):
            return
        yield tag, payload[start:end]
        off = end


def decode_eeg_payload(payload: bytes, stats: Optional[ParseStats] = None) -> Optional[np.ndarray]:
    """Return EEG samples in this notification as float32 (n_samples, 4) in uV, or None.

    Values are unsigned (0..~1450 uV, DC ~725); downstream must detrend.
    """
    blocks = [
        proto.decode_eeg(data, EEG_TAGS[tag])[:, :4]
        for tag, data in walk_payload(payload, stats)
        if tag in EEG_TAGS
    ]
    if stats is not None:
        stats.packets += 1
        if len(payload) >= 3:
            stats.note_seq(packet_seq(payload))
    if not blocks:
        return None
    out = np.vstack(blocks)
    if stats is not None:
        stats.eeg_samples += out.shape[0]
    return out


# ---------------------------------------------------------------------------
# Thread-safe ring buffer
# ---------------------------------------------------------------------------
class EEGBuffer:
    """Fixed-capacity ring buffer of (n, 4) EEG samples, safe across threads."""

    def __init__(self, seconds: float = 30.0, fs: int = EEG_FS, n_channels: int = 4):
        self._cap = int(seconds * fs)
        self._data = np.zeros((self._cap, n_channels), dtype=np.float32)
        self._lock = threading.Lock()
        self.total = 0  # samples ever written; use deltas for effective sample rate
        self.last_packet_t: Optional[float] = None  # host monotonic time of last write

    def append(self, samples: np.ndarray, t_host: float) -> None:
        n = samples.shape[0]
        with self._lock:
            idx = (self.total + np.arange(n)) % self._cap
            self._data[idx] = samples
            self.total += n
            self.last_packet_t = t_host

    def latest(self, n: int) -> Optional[np.ndarray]:
        with self._lock:
            if self.total < n or n > self._cap:
                return None
            idx = (self.total - n + np.arange(n)) % self._cap
            return self._data[idx].copy()


# ---------------------------------------------------------------------------
# BLE streaming
# ---------------------------------------------------------------------------
class MuseAthenaStream:
    """Connects to a Muse S Athena and pushes decoded EEG into an ``EEGBuffer``.

    Runs its own asyncio loop on a daemon thread so the caller's main thread is
    free for matplotlib. Reconnects automatically on disconnect or stall.
    """

    def __init__(
        self,
        buffer: EEGBuffer,
        address: Optional[str] = None,
        name_prefix: str = "Muse",
        preset: str = "p1034",
        on_raw: Optional[Callable[[bytes, float], None]] = None,
    ):
        if preset not in proto.COMMANDS:
            raise ValueError(f"preset must be one of p21/p1034/p1035, got {preset!r}")
        self.buffer = buffer
        self.address = address
        self.name_prefix = name_prefix
        self.preset = preset
        self.on_raw = on_raw
        self.stats = ParseStats()
        self.device_info: dict = {}
        self.connected = False
        self.reconnects = 0
        self.mtu: Optional[int] = None
        self._ctrl_text = ""
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._stop: Optional[asyncio.Event] = None
        self._thread: Optional[threading.Thread] = None

    # -- public -------------------------------------------------------------
    def start(self) -> None:
        self._thread = threading.Thread(target=self._thread_main, name="muse-ble", daemon=True)
        self._thread.start()

    def stop(self, timeout: float = 5.0) -> None:
        if self._loop and self._stop:
            self._loop.call_soon_threadsafe(self._stop.set)
        if self._thread:
            self._thread.join(timeout)

    # -- internals ----------------------------------------------------------
    def _thread_main(self) -> None:
        asyncio.run(self._main())

    async def _main(self) -> None:
        self._loop = asyncio.get_running_loop()
        self._stop = asyncio.Event()
        backoff = 1.0
        while not self._stop.is_set():
            try:
                streamed = await self._session()
                backoff = 1.0 if streamed else min(backoff * 2, 15.0)
            except Exception as e:  # BLE stacks raise a zoo of exception types
                log.warning("BLE session error: %s: %s", type(e).__name__, e)
                backoff = min(backoff * 2, 15.0)
            finally:
                self.connected = False
            if not self._stop.is_set():
                self.reconnects += 1
                log.info("reconnecting in %.0fs", backoff)
                try:
                    await asyncio.wait_for(self._stop.wait(), timeout=backoff)
                except asyncio.TimeoutError:
                    pass

    async def _find_device(self):
        if self.address:
            dev = await BleakScanner.find_device_by_address(self.address, timeout=10.0)
        else:
            dev = await BleakScanner.find_device_by_filter(
                lambda d, adv: bool(d.name and d.name.startswith(self.name_prefix)), timeout=10.0
            )
        if dev is None:
            raise RuntimeError(
                f"no device found ({self.address or self.name_prefix + '*'}); "
                "is it charged, powered on, and NOT connected to the Muse phone app?"
            )
        log.info("found %s (%s)", dev.name, dev.address)
        return dev

    async def _session(self) -> bool:
        """One connect->stream->disconnect cycle. Returns True if data flowed."""
        dev = await self._find_device()
        disconnected = asyncio.Event()
        loop = asyncio.get_running_loop()

        def on_disconnect(_client):
            loop.call_soon_threadsafe(disconnected.set)

        async with BleakClient(dev, disconnected_callback=on_disconnect) as client:
            self.connected = True
            self.mtu = getattr(client, "mtu_size", None)
            log.info("connected; MTU=%s", self.mtu)
            if self.mtu is not None and self.mtu < 247:
                log.warning("MTU %s < 247: Athena notifications are ~200-240 B and may be truncated", self.mtu)
            self.stats.last_seq = None  # device counter may restart on reconnect
            await client.start_notify(proto.CONTROL_UUID, self._on_control)

            sensor_on = False
            for desc, cmd, delay in proto.get_init_sequence(self.preset):
                # Amused enables sensor notifications right before the first dc001.
                if not sensor_on and cmd == proto.COMMANDS["dc001"]:
                    await client.start_notify(proto.SENSOR_UUID, self._on_sensor)
                    sensor_on = True
                log.debug("init: %s", desc)
                await client.write_gatt_char(proto.CONTROL_UUID, cmd, response=False)
                await asyncio.sleep(delay)

            t_start = time.monotonic()
            got_data = False
            while not self._stop.is_set() and not disconnected.is_set():
                await asyncio.sleep(0.5)
                last = self.buffer.last_packet_t
                ref = last if last is not None and last >= t_start else t_start
                if time.monotonic() - ref > STALL_TIMEOUT_S:
                    log.warning("no EEG for %.0fs -> forcing reconnect", STALL_TIMEOUT_S)
                    break
                got_data = got_data or (last is not None and last >= t_start)

            if client.is_connected:
                try:
                    await client.write_gatt_char(proto.CONTROL_UUID, proto.COMMANDS["h"], response=False)
                except Exception:
                    pass
            return got_data

    def _on_sensor(self, _sender, data: bytearray) -> None:
        t = time.monotonic()
        payload = bytes(data)
        if self.on_raw is not None:
            self.on_raw(payload, t)
        eeg = decode_eeg_payload(payload, self.stats)
        if eeg is not None:
            self.buffer.append(eeg, t)

    def _on_control(self, _sender, data: bytearray) -> None:
        # Control responses are JSON split across notifications; accumulate and extract.
        self._ctrl_text += bytes(data).decode("utf-8", errors="ignore")
        while "{" in self._ctrl_text and "}" in self._ctrl_text:
            start = self._ctrl_text.index("{")
            end = self._ctrl_text.index("}", start) + 1
            chunk, self._ctrl_text = self._ctrl_text[start:end], self._ctrl_text[end:]
            try:
                info = json.loads(chunk)
            except ValueError:
                continue
            new = {k: v for k, v in info.items() if self.device_info.get(k) != v}
            self.device_info.update(info)
            if "fw" in new or "bp" in new:
                log.info("device: fw=%s battery=%s%%", self.device_info.get("fw"), self.device_info.get("bp"))
        self._ctrl_text = self._ctrl_text[-512:]


# ---------------------------------------------------------------------------
# Synthetic source (pipeline testing before hardware arrives)
# ---------------------------------------------------------------------------
class SimulatedStream:
    """Generates Athena-like EEG in real time: 1/f noise + 10 Hz alpha that grows
    when ``eyes_closed()`` returns True, DC ~725 uV, blinks on AF7/AF8 when eyes open.

    Same start/stop/stats surface as MuseAthenaStream.
    """

    def __init__(self, buffer: EEGBuffer, eyes_closed: Callable[[], bool], seed: int = 0):
        self.buffer = buffer
        self.eyes_closed = eyes_closed
        self.rng = np.random.default_rng(seed)
        self.stats = ParseStats()
        self.device_info = {"fw": "simulated", "bp": 100}
        self.connected = True
        self.reconnects = 0
        self.mtu = None
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._alpha_amp = 4.0

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, name="muse-sim", daemon=True)
        self._thread.start()

    def stop(self, timeout: float = 2.0) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout)

    def _pink(self, n: int) -> np.ndarray:
        white = self.rng.standard_normal((n, 4))
        f = np.fft.rfftfreq(n, 1 / EEG_FS)
        f[0] = f[1]
        spec = np.fft.rfft(white, axis=0) / np.sqrt(f)[:, None]
        x = np.fft.irfft(spec, n=n, axis=0)
        return 8.0 * x / x.std(axis=0)

    def _run(self) -> None:
        chunk, n_done, t0 = 12, 0, time.monotonic()  # 12 samples ~= 3 EEG subpackets
        noise, ni = self._pink(EEG_FS * 60), 0
        k = 1.0 - np.exp(-1.0 / (EEG_FS * 0.5))  # ~0.5 s time constant on alpha amplitude
        while not self._stop.is_set():
            target = 4.0 * (3.0 if self.eyes_closed() else 1.0)  # ~9x power EC vs EO
            t = (n_done + np.arange(chunk)) / EEG_FS
            amp = np.empty(chunk)
            for i in range(chunk):
                self._alpha_amp += (target - self._alpha_amp) * k
                amp[i] = self._alpha_amp
            alpha = amp[:, None] * np.sin(2 * np.pi * 10.0 * t)[:, None] * np.array([[1.0, 0.4, 0.4, 1.0]])
            if ni + chunk > noise.shape[0]:
                noise, ni = self._pink(EEG_FS * 60), 0
            x = 725.0 + noise[ni:ni + chunk] + alpha
            ni += chunk
            if not self.eyes_closed() and self.rng.random() < chunk / (EEG_FS * 4):  # ~1 blink / 4 s
                x[:, 1:3] += 120.0
            self.buffer.append(x.astype(np.float32), time.monotonic())
            self.stats.packets += 1
            self.stats.eeg_samples += chunk
            n_done += chunk
            sleep = t0 + n_done / EEG_FS - time.monotonic()
            if sleep > 0:
                time.sleep(sleep)
