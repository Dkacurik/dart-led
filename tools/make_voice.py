"""Generate the Dart LED voice pack (Piper "Ryan" voice) as MP3 clips.

Usage: python3 -I make_voice.py <voice.onnx> <jokes.txt> <out_dir>
Keys must match VoicePack.slug() in the app.
"""
import os
import re
import subprocess
import sys
import tempfile
import unicodedata
import wave

from piper import PiperVoice

voice_path, jokes_file, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]


def slug(text):
    t = unicodedata.normalize("NFD", text)
    t = "".join(c for c in t if unicodedata.category(c) != "Mn").lower()
    return re.sub(r"[^a-z0-9]+", "_", t).strip("_")


clips = {}  # file name (without .mp3) -> text to speak
phrases = {
    "game_on": "Game on!",
    "to_throw_first": "to throw first.",
    "throws_first": "throws first.",
    "you_require": "you require",
    "one_eighty": "One hundred and eighty!",
    "what_a_finish": "What a finish!",
    "big_fish": "The big fish! One hundred and seventy!",
    "game_shot_leg": "Game shot, and the leg,",
    "game_shot_match": "Game shot, and the match,",
    "bust": "Bust!",
    "no_score": "No score.",
    "six_seven_intro": "Six... seven!",
}
for k, t in phrases.items():
    clips["p_" + k] = t
for n in range(1, 181):
    clips[f"n_{n}"] = f"{n}!"
names = ["Dávid", "Matej", "Samuel", "Peter", "Martin", "Tomáš", "Michal", "Jakub", "Lukáš",
         "Marek", "Juraj", "Filip", "Adam", "Patrik", "Ján", "Jozef", "Andrej", "Erik", "Roman",
         "Richard", "Daniel", "Pavol", "Milan", "Lucia", "Simona", "Katka", "Zuzana", "Monika"]
for nm in names:
    clips["name_" + slug(nm)] = nm
with open(jokes_file, encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if line:
            clips["j_" + slug(line)] = line

os.makedirs(out_dir, exist_ok=True)
voice = PiperVoice.load(voice_path)
trim = ("silenceremove=start_periods=1:start_threshold=-50dB,areverse,"
        "silenceremove=start_periods=1:start_threshold=-50dB,areverse")
with tempfile.TemporaryDirectory() as tmp:
    for key, text in clips.items():
        wav = os.path.join(tmp, key + ".wav")
        with wave.open(wav, "wb") as w:
            voice.synthesize_wav(text, w)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav, "-af", trim, "-ac", "1",
                        "-c:a", "libmp3lame", "-b:a", "48k", os.path.join(out_dir, key + ".mp3")], check=True)
print("clips:", len(clips))
