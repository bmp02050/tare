# Fixtures

`athena_capture_20250824.bin`: 120 BLE notifications (~7 s) captured from a real
Muse S Athena (preset p1034) in Amused's MUSB format. Copied unmodified from
[Amused-EEG/amused-py](https://github.com/Amused-EEG/amused-py) at commit
`bce20f98`, `tests/test_data/muse_20250824_172756.bin`. MIT License, Copyright (c) 2025 nexon33.

Note: TP10 is railed (0 to 1450 uV) for most of this capture, so it's useful
for testing the quality gate too.
