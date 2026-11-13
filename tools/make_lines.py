"""Record extra lines (one per line of a text file) as j_<slug>.mp3 with a Piper voice.
Usage: python3 -I make_lines.py <voice.onnx> <lines.txt> <out_dir>
"""
import os, re, subprocess, sys, tempfile, unicodedata, wave
from piper import PiperVoice

def slug(text):
    t = unicodedata.normalize("NFD", text)
    t = "".join(c for c in t if unicodedata.category(c) != "Mn").lower()
    return re.sub(r"[^a-z0-9]+", "_", t).strip("_")

voice_path, lines_file, out_dir = sys.argv[1:4]
os.makedirs(out_dir, exist_ok=True)
voice = PiperVoice.load(voice_path)
trim = ("silenceremove=start_periods=1:start_threshold=-50dB,areverse,"
        "silenceremove=start_periods=1:start_threshold=-50dB,areverse")
n = 0
with open(lines_file, encoding="utf-8") as f, tempfile.TemporaryDirectory() as tmp:
    for line in f:
        line = line.strip()
        if not line:
            continue
        key = "j_" + slug(line)
        wav = os.path.join(tmp, key + ".wav")
        with wave.open(wav, "wb") as w:
            voice.synthesize_wav(line, w)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav, "-af", trim, "-ac", "1",
                        "-c:a", "libmp3lame", "-b:a", "48k", os.path.join(out_dir, key + ".mp3")], check=True)
        n += 1
print("clips:", n)
