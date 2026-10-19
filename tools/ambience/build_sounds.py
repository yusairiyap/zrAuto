#!/usr/bin/env python3
"""Builds the Ambience addon's recorded loops from Freesound.

usage: build_sounds.py sounds.json out_dir      (needs FREESOUND_API_KEY, ffmpeg and numpy)

For every sound in sounds.json: reads each source's metadata from the Freesound API and stops
if its license is not CC0, downloads the HQ OGG preview (a token is enough, no OAuth), makes a
seamless loop of the wanted length (equal-power crossfade of the end into the start), matches the
loudness to the synthesized voices, layers the sources, and encodes Opus. Writes manifest.json
with the size, hash and credits of every file, which the app reads.
"""
import hashlib
import json
import os
import subprocess
import sys
import urllib.request

import numpy as np

API = "https://freesound.org/apiv2"
CC0 = "http://creativecommons.org/publicdomain/zero/1.0/"
SR = 48000          # Opus always decodes at 48 kHz
TARGET_RMS = 0.12   # what the synthesized voices sit at, so the two sources are about as loud


def http_get(url, token=None):
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", "Token " + token)
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def sound_info(sound_id, token):
    fields = "id,name,username,license,duration,previews,url"
    return json.loads(http_get("%s/sounds/%d/?fields=%s" % (API, sound_id, fields), token))


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
         "-c:a", "libopus", "-b:a", "64k", "-application", "audio", path],
        check=True, input=x.astype(np.float32).tobytes())


def main():
    cfg_path, out_dir = sys.argv[1], sys.argv[2]
    token = os.environ["FREESOUND_API_KEY"]
    cfg = json.load(open(cfg_path))
    length = int(cfg.get("loopSeconds", 60) * SR)
    xf = int(cfg.get("crossfadeSeconds", 3) * SR)
    os.makedirs(out_dir, exist_ok=True)
    manifest = {"version": 1, "sounds": {}}
    for key, sound in cfg["sounds"].items():
        mix = np.zeros((length, 2), dtype=np.float32)
        credits = []
        for src in sound["sources"]:
            info = sound_info(src["id"], token)
            if info["license"] != CC0:
                sys.exit("%s: sound %d (%s by %s) is not CC0 but %s" %
                         (key, info["id"], info["name"], info["username"], info["license"]))
            preview = info["previews"]["preview-hq-ogg"]
            tmp = os.path.join(out_dir, "_src_%d.ogg" % info["id"])
            open(tmp, "wb").write(http_get(preview))
            x = decode(tmp)
            os.remove(tmp)
            if len(x) < SR * 2:
                sys.exit("%s: sound %d is only %.1f s long" % (key, info["id"], len(x) / SR))
            x = loopable(x, length, xf)
            x = x / rms(x) * TARGET_RMS * float(src.get("gain", 1.0))
            mix += x
            credits.append({"id": info["id"], "title": info["name"], "author": info["username"],
                            "license": "CC0", "url": info["url"]})
            print("%s: %d %s by %s, %.0f s" % (key, info["id"], info["name"], info["username"],
                                               info["duration"]))
        peak = float(np.max(np.abs(mix)))
        if peak > 0.95:
            mix *= 0.95 / peak
        name = key + ".opus"
        path = os.path.join(out_dir, name)
        encode_opus(mix, path)
        data = open(path, "rb").read()
        manifest["sounds"][key] = {
            "file": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
            "rate": SR, "channels": 2, "seconds": length // SR,
            "title": sound.get("title", key), "credits": credits}
        print("%s: %s, %d KB" % (key, name, len(data) // 1024))
    json.dump(manifest, open(os.path.join(out_dir, "manifest.json"), "w"), indent=2)


if __name__ == "__main__":
    main()
