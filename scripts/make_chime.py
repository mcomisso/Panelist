#!/usr/bin/env python3
"""Synthesise the two-note doorbell chime bundled as assets/chime.wav.

E5 then C5 (classic descending ding-dong) with bell-ish harmonics and an
exponential decay. Run from the repo root: python3 scripts/make_chime.py
"""
import math
import struct
import wave
from pathlib import Path

RATE = 22050


def bell(freq, dur, amp, decay):
    n = int(RATE * dur)
    out = []
    for i in range(n):
        t = i / RATE
        env = math.exp(-decay * t)
        if t < 0.004:
            env *= t / 0.004
        v = (math.sin(2 * math.pi * freq * t) * 0.62
             + math.sin(2 * math.pi * freq * 2 * t) * 0.24
             + math.sin(2 * math.pi * freq * 3 * t) * 0.10
             + math.sin(2 * math.pi * freq * 4.2 * t) * 0.05)
        out.append(v * env * amp)
    return out


total = 1.5
buf = [0.0] * int(RATE * total)


def mix(offset, samples):
    o = int(RATE * offset)
    for i, s in enumerate(samples):
        if o + i < len(buf):
            buf[o + i] += s


mix(0.00, bell(659.25, 0.70, 0.55, 5.5))   # E5 "ding"
mix(0.38, bell(523.25, 1.05, 0.60, 3.2))   # C5 "dong"

peak = max(1e-9, max(abs(v) for v in buf))
scale = 0.86 / peak
fade = int(RATE * 0.05)
for i in range(len(buf) - fade, len(buf)):
    buf[i] *= (len(buf) - 1 - i) / fade

data = b"".join(struct.pack("<h", int(v * scale * 32767)) for v in buf)
out = Path(__file__).resolve().parent.parent / "app/src/main/assets/chime.wav"
with wave.open(str(out), "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(RATE)
    w.writeframes(data)
print("wrote", out, out.stat().st_size, "bytes")
