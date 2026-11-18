"""Decode real Athena packets and check the 0x53 fix restores the full 256 Hz."""

from pathlib import Path

import muse_athena_protocol as proto
import numpy as np
import pytest
from muse_raw_stream import MuseRawStream

from sense.bandpower import channel_quality
from sense.muse_athena import EEG_FULL_SCALE_UV, ParseStats, decode_eeg_payload, walk_payload

FIXTURE = Path(__file__).parent / "fixtures" / "athena_capture_20250824.bin"


@pytest.fixture(scope="module")
def packets():
    pk = list(MuseRawStream(str(FIXTURE)).read_packets())
    assert len(pk) == 120
    return pk


def _duration_s(pk):
    return (pk[-1].timestamp - pk[0].timestamp).total_seconds()


def test_full_sample_rate_with_0x53_handled(packets):
    stats = ParseStats()
    blocks = [b for p in packets if (b := decode_eeg_payload(p.data, stats)) is not None]
    eeg = np.vstack(blocks)
    rate = eeg.shape[0] / _duration_s(packets)
    assert 250 <= rate <= 262, rate
    assert eeg.shape[1] == 4
    assert not stats.unknown_tags, stats.unknown_tags
    assert stats.seq_gaps == 0
    assert stats.packets == 120


def test_upstream_parser_drops_samples(packets):
    """Documents the upstream bug so we notice if Amused fixes it (then this test fails, and we can drop our walker)."""
    ours = sum(decode_eeg_payload(p.data).shape[0] for p in packets if decode_eeg_payload(p.data) is not None)
    theirs = sum(sp["data"].shape[0] for p in packets for sp in proto.parse_payload(p.data)["EEG"])
    assert theirs < 0.9 * ours, (theirs, ours)


def test_every_packet_parses_to_the_end(packets):
    for p in packets:
        consumed = 14
        for i, (tag, data) in enumerate(walk_payload(p.data)):
            consumed += len(data) + (0 if i == 0 else 5)
        assert consumed == len(p.data), (p.packet_num, consumed, len(p.data))


def test_quality_gate_flags_railed_tp10(packets):
    eeg = np.vstack([b for p in packets if (b := decode_eeg_payload(p.data)) is not None])
    q = channel_quality(eeg[:512], EEG_FULL_SCALE_UV)
    assert q[3] == "rail"   # TP10 off-skin in this capture
    assert q[1] == ""       # AF7 clean
