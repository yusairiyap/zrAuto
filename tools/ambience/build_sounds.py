#!/usr/bin/env python3
"""Turns hand-downloaded CC0 sounds into the Ambience addon's recorded loops.

usage: build_sounds.py DOWNLOAD_DIR OUT_DIR [sounds.json]      (needs ffmpeg with libopus, and numpy)

For every sound in sounds.json it finds each source in DOWNLOAD_DIR (the file whose name starts with
the Freesound id), makes a seamless loop of loopSeconds (an equal-power crossfade of the end into the
start; a source that is too short is first made to loop on itself), matches the loudness to the
synthesized voices, layers the sources, and encodes Opus in an Ogg file (.ogg, so Android keeps it
uncompressed in the APK). Writes manifest.json (what the app reads) next to the files.
"""
import json
import os
import subprocess
import sys

import numpy as np

SR = 48000          # Opus always decodes at 48 kHz
TARGET_RMS = 0.16   # about where the synthesized voices sit, so a recording is as loud as they are


def find_source(src_dir, sound_id):
    for name in sorted(os.listdir(src_dir)):
        stem = os.path.splitext(name)[0]
        if stem == str(sound_id) or stem.startswith(str(sound_id) + "__") or stem.startswith(str(sound_id) + "_"):
            return os.path.join(src_dir, name)
    return None


def decode(path):
    """Any audio file to float32 stereo at SR, shape (n, 2)."""
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ac", "2", "-ar", str(SR), "-"],
        check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).reshape(-1, 2).copy()


def make_loop(x, length, xf):
    """A loop of `length` samples: out[0:xf] fades the sound's tail (x[length:length+xf]) out while
    the head fades in, so the end of the loop runs straight into its start."""
    t = np.linspace(0, np.pi / 2, xf, endpoint=False)[:, None]
    head = x[:xf] * np.sin(t) + x[length:length + xf] * np.cos(t)
    return np.concatenate([head, x[xf:length]])


def loopable(x, length, xf):
    """x as a seamless loop of exactly `length` samples, whatever its own length."""
    if len(x) < length + xf:
        # Too short: make it loop on its own first, then repeat it up to the wanted length.
        xf1 = max(1, min(xf, len(x) // 4))
        base = make_loop(x, len(x) - xf1, xf1)
        reps = int(np.ceil((length + xf) / len(base)))
        x = np.tile(base, (reps, 1))
    return make_loop(x[:length + xf], length, xf)


def rms(x):
    return float(np.sqrt(np.mean(np.square(x)))) + 1e-9


def encode_opus(x, path):
    subprocess.run(
        ["ffmpeg", "-y", "-v", "error", "-f", "f32le", "-ar", str(SR), "-ac", "2", "-i", "-",
         "-c:a", "libopus", "-b:a", "64k", "-application", "audio", "-f", "ogg", path],
        check=True, input=x.astype(np.float32).tobytes())


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    src_dir, out_dir = sys.argv[1], sys.argv[2]
    cfg = json.load(open(sys.argv[3] if len(sys.argv) > 3 else os.path.join(os.path.dirname(__file__), "sounds.json")))
    length = int(cfg.get("loopSeconds", 60) * SR)
    xf = int(cfg.get("crossfadeSeconds", 3) * SR)
    os.makedirs(out_dir, exist_ok=True)
    manifest = {"version": 1, "sounds": {}}
    for key, sound in cfg["sounds"].items():
        found = [(s, find_source(src_dir, s["id"])) for s in sound["sources"]]
        missing = [s["id"] for s, p in found if p is None]
        if missing:
            print("%s: skipped, not downloaded yet: %s" % (key, ", ".join(map(str, missing))))
            continue
        decoded = []
        for src, path in found:
            x = decode(path)
            if len(x) < SR * 2:
                sys.exit("%s: %s is only %.1f s long" % (key, os.path.basename(path), len(x) / SR))
            decoded.append(x)
        # As long as the longest source allows (at most loopSeconds): a 10 s brook is a 10 s loop,
        # not the same ten seconds six times over in a bigger file.
        longest = max(len(x) for x in decoded)
        xf_s = min(xf, longest // 4)
        length_s = min(length, longest - xf_s)
        mix = np.zeros((length_s, 2), dtype=np.float32)
        credits = []
        for (src, path), x in zip(found, decoded):
            x = loopable(x, length_s, xf_s)
            x = x / rms(x) * TARGET_RMS * float(src.get("gain", 1.0))
            mix += x
            credits.append({"id": src["id"], "title": src.get("name", ""), "author": src.get("author", ""),
                            "license": "CC0", "url": src.get("url", "")})
            print("%s: %s" % (key, os.path.basename(path)))
        # A spiky sound (drops, crackling, cups) would need its whole level turned down to keep its
        # peaks under full scale, and end up far quieter than the rest: squash only the peaks instead.
        mix = np.tanh(mix) * 0.8   # headroom: lossy coding overshoots sharp peaks
        name = key + ".ogg"
        path = os.path.join(out_dir, name)
        encode_opus(mix, path)
        size = os.path.getsize(path)
        manifest["sounds"][key] = {"file": name, "seconds": length_s // SR, "title": sound.get("title", key),
                                   "credits": credits}
        print("%s: %s, %d KB" % (key, name, size // 1024))
    json.dump(manifest, open(os.path.join(out_dir, "manifest.json"), "w"), indent=2)


if __name__ == "__main__":
    main()
