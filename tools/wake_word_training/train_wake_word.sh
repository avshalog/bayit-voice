#!/bin/bash
# Trains an openWakeWord model end to end and writes <MODEL_NAME>.tflite for the app.
# Runs on Google Colab (T4 GPU, Python 3.12) or any Linux machine with an NVIDIA GPU.
#
# Configure with environment variables (examples are not defaults for any one home):
#   PHRASES          JSON list of spellings to synthesize, e.g. '["hey jarvis"]'          (required)
#   MODEL_NAME       file name of the model, letters/digits/underscores                    (required)
#   PRONUNCIATIONS   JSON object of ARPAbet pronunciations for words the English
#                    dictionary lacks, e.g. '{"bayit": ["B AA1 Y IH0 T"]}'                  (default {})
#   N_SAMPLES        synthetic positive clips (default 10000)
#   STEPS            training steps (default 15000)
#   WORKDIR          working directory (default /content on Colab, else ./oww-work)
#   ACAV_FRACTION    1 = all negative features; 3 = a third (default: retry with 3 if training dies)
#   REAL_CLIPS       folder of real recordings of the phrase: 16 kHz mono WAV, one utterance each
#                    (split_recordings.js makes them). Optional; without it training is synthetic only.
#   REAL_REPEAT      how many times each real clip joins the training set, each time with its own
#                    noise and room echo (default 15)
#   REAL_HOLDOUT     share of real clips kept out of training to measure recall on real voices (default 0.2)
#
# Each step prints "=== STEP <name>". The run ends with "FINISHED <path>" or "FAILED <step>".
set -o pipefail

: "${PHRASES:?Set PHRASES, e.g. PHRASES='[\"hey jarvis\"]'}"
: "${MODEL_NAME:?Set MODEL_NAME, e.g. MODEL_NAME=hey_jarvis}"
PRONUNCIATIONS="${PRONUNCIATIONS:-{\}}"
N_SAMPLES="${N_SAMPLES:-10000}"
STEPS="${STEPS:-15000}"
REAL_REPEAT="${REAL_REPEAT:-15}"
REAL_HOLDOUT="${REAL_HOLDOUT:-0.2}"
if [ -n "$REAL_CLIPS" ]; then
  REAL_CLIPS="$(cd "$REAL_CLIPS" 2>/dev/null && pwd)" || { echo "FAILED setup: REAL_CLIPS folder not found"; exit 1; }
fi
if [ -z "$WORKDIR" ]; then [ -d /content ] && WORKDIR=/content || WORKDIR="$PWD/oww-work"; fi
export PHRASES MODEL_NAME PRONUNCIATIONS N_SAMPLES STEPS REAL_CLIPS REAL_REPEAT REAL_HOLDOUT

mkdir -p "$WORKDIR" && cd "$WORKDIR" || exit 1
step() { echo "=== STEP $1 $(date +%H:%M:%S)"; }
fail() { echo "FAILED $1"; exit 1; }

step setup
python3 -c 'import sys; v = sys.version_info[:2]; print("python", v); sys.exit(0 if v == (3, 12) else 1)' \
  || fail "setup: needs Python 3.12 (on Colab: Runtime > Change runtime type > runtime version 2026.04)"
nvidia-smi -L || fail "setup: no NVIDIA GPU"
[ -d piper-sample-generator ] || git clone -q https://github.com/rhasspy/piper-sample-generator
(cd piper-sample-generator && git checkout -q 213d4d5)
[ -f piper-sample-generator/models/en_US-libritts_r-medium.pt ] || wget -q -O piper-sample-generator/models/en_US-libritts_r-medium.pt \
  https://github.com/rhasspy/piper-sample-generator/releases/download/v2.0.0/en_US-libritts_r-medium.pt
[ -d openwakeword ] || git clone -q https://github.com/dscripka/openwakeword

# The negative features are 17 GB: start downloading them now, in parallel with everything else.
FEAT=https://huggingface.co/datasets/davidscripka/openwakeword_features/resolve/main/
ACAV=openwakeword_features_ACAV100M_2000_hrs_16bit.npy
if [ ! -f features.ok ]; then
  nohup sh -c "wget -q -c ${FEAT}validation_set_features.npy && wget -q -c ${FEAT}${ACAV} && echo done > features.ok" > features.log 2>&1 &
fi

step pip
pip install -q piper-tts piper-phonemize-cross webrtcvad || fail pip
pip install -q torch==2.5.0 torchvision==0.20.0 torchaudio==2.5.0 --index-url https://download.pytorch.org/whl/cu121 || fail pip
pip install -q -e ./openwakeword --no-deps || fail pip
pip install -q mutagen==1.47.0 torchinfo==1.8.0 torchmetrics==1.2.0 speechbrain==0.5.14 audiomentations==0.33.0 \
  torch-audiomentations==0.11.0 acoustics==0.2.6 onnxruntime==1.22.1 ai_edge_litert==1.4.0 onnxsim onnx2tf \
  onnx_graphsurgeon sng4onnx pronouncing==0.2.0 datasets==2.14.6 deep-phonemizer==0.0.19 pyarrow soundfile || fail pip
pip install -q onnx==1.19.1 || fail pip
python3 -c "import torch, piper_phonemize, openwakeword, onnx2tf; print('cuda', torch.cuda.is_available())" || fail pip

step models
M=openwakeword/openwakeword/resources/models
mkdir -p $M
for f in embedding_model.onnx embedding_model.tflite melspectrogram.onnx melspectrogram.tflite; do
  [ -f $M/$f ] || wget -q https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/$f -O $M/$f || fail models
done

# openWakeWord looks words up in an English dictionary and falls back to DeepPhonemizer, whose
# model download is gone. Words missing from the dictionary get their pronunciation from PRONUNCIATIONS.
step pronunciations
python3 - <<'EOF' || fail pronunciations
import json, os, re
path = "openwakeword/openwakeword/data.py"
src = open(path).read()
custom = json.loads(os.environ["PRONUNCIATIONS"])
old = re.search(r"input_text_phones = \[pronouncing\.phones_for_word\(i\)[^\n]*", src)
if old is None:
    raise SystemExit("data.py changed: the phoneme lookup line was not found")
new = f"input_text_phones = [pronouncing.phones_for_word(i) or {custom!r}.get(i, []) for i in input_text.split()]"
open(path, "w").write(src.replace(old.group(0), new))
import pronouncing
for phrase in json.loads(os.environ["PHRASES"]):
    missing = [w for w in phrase.split() if not pronouncing.phones_for_word(w) and w not in custom]
    if missing:
        raise SystemExit(f"No pronunciation for {missing} in '{phrase}': add them to PRONUNCIATIONS")
print("pronunciations ok:", list(custom))
EOF

step rirs
if [ ! -d mit_rirs ]; then
  git lfs install -q
  git clone -q https://huggingface.co/datasets/davidscripka/MIT_environmental_impulse_responses || fail rirs
  mkdir -p mit_rirs && cp MIT_environmental_impulse_responses/16khz/*.wav mit_rirs/
fi

# Background noise: AudioSet's balanced set, decoded from parquet (the notebook's original source is gone).
step background
python3 - <<'EOF' || fail background
import io, os, numpy as np, scipy.io.wavfile, soundfile as sf, pyarrow.parquet as pq
from math import gcd
from scipy.signal import resample_poly
os.makedirs("audioset_16k", exist_ok=True)
if len(os.listdir("audioset_16k")) > 1000:
    raise SystemExit(0)
base = "https://huggingface.co/datasets/agkphysics/AudioSet/resolve/main/data/bal_train/"
for shard in ["00", "01", "02"]:
    os.system(f"wget -q -O as_{shard}.parquet {base}{shard}.parquet")
    for i, a in enumerate(pq.read_table(f"as_{shard}.parquet").column("audio").to_pylist()):
        if not a or not a.get("bytes"):
            continue
        d, sr = sf.read(io.BytesIO(a["bytes"]), dtype="float32")
        d = d.mean(axis=1) if d.ndim > 1 else d
        if sr != 16000:
            g = gcd(16000, sr)
            d = resample_poly(d, 16000 // g, sr // g)
        scipy.io.wavfile.write(f"audioset_16k/as{shard}_{i}.wav", 16000, (np.clip(d, -1, 1) * 32767).astype(np.int16))
    os.remove(f"as_{shard}.parquet")
print(len(os.listdir("audioset_16k")), "background clips")
EOF

step config
python3 - <<'EOF' || fail config
import json, os, yaml
c = yaml.safe_load(open("openwakeword/examples/custom_model.yml"))
c.update({
    "target_phrase": json.loads(os.environ["PHRASES"]),
    "model_name": os.environ["MODEL_NAME"],
    "n_samples": int(os.environ["N_SAMPLES"]),
    "n_samples_val": max(1000, int(os.environ["N_SAMPLES"]) // 10),
    "steps": int(os.environ["STEPS"]),
    "target_accuracy": 0.5, "target_recall": 0.25, "max_negative_weight": 1500,
    "output_dir": "./my_custom_model",
    "background_paths": ["./audioset_16k"],
    "rir_paths": ["./mit_rirs"],
    "false_positive_validation_data_path": "validation_set_features.npy",
    "feature_data_files": {"ACAV100M_sample": "openwakeword_features_ACAV100M_2000_hrs_16bit.npy"},
})
yaml.safe_dump(c, open("my_model.yaml", "w"), allow_unicode=True)
print(c["target_phrase"], c["n_samples"], c["steps"])
EOF

T="python3 openwakeword/openwakeword/train.py --training_config my_model.yaml"
POS="my_custom_model/${MODEL_NAME}/positive_train"
# Copies of real clips from an earlier run would count as generated clips; clear them first.
rm -f "$POS"/real_*.wav
step generate
$T --generate_clips > generate.log 2>&1 || { tail -20 generate.log; fail generate; }

# Real recordings join the synthetic positives before augmentation, so each copy gets its own
# background noise and room echo. A share of them, taken evenly from every source recording,
# stays out of training for the recall check at the end.
step real_clips
python3 - <<'EOF' || fail real_clips
import os, random, shutil, soundfile as sf
src = os.environ.get("REAL_CLIPS")
if not src:
    print("no REAL_CLIPS: synthetic only")
    raise SystemExit(0)
pos = os.path.join("my_custom_model", os.environ["MODEL_NAME"], "positive_train")
hold = "real_holdout"
shutil.rmtree(hold, ignore_errors=True)
os.makedirs(hold)
clips = sorted(f for f in os.listdir(src) if f.lower().endswith(".wav"))
info = {f: sf.info(os.path.join(src, f)) for f in clips}
bad = [f for f, i in info.items() if i.samplerate != 16000 or i.channels != 1]
if bad:
    raise SystemExit(f"Not 16 kHz mono: {bad[:5]}")
# Training windows are about 2 s and train.py truncates longer clips, which could cut the phrase.
too_long = [f for f in clips if info[f].duration > 2.0]
clips = [f for f in clips if info[f].duration <= 2.0]
print(f"{len(clips)} real clips; {len(too_long)} longer than 2 s left out")
groups = {}
for f in clips:
    groups.setdefault(f.rsplit("_", 1)[0], []).append(f)
rnd = random.Random(1)
repeat, share = int(os.environ["REAL_REPEAT"]), float(os.environ["REAL_HOLDOUT"])
n_train = n_hold = 0
for name, files in sorted(groups.items()):
    rnd.shuffle(files)
    k = round(len(files) * share)
    for f in files[:k]:
        shutil.copy(os.path.join(src, f), os.path.join(hold, f))
    for f in files[k:]:
        for r in range(repeat):
            shutil.copy(os.path.join(src, f), os.path.join(pos, f"real_{f[:-4]}_{r:02d}.wav"))
    n_hold += k
    n_train += len(files) - k
    print(f"{name}: {len(files) - k} for training, {k} held out")
print(f"real clips: {n_train} x {repeat} in training, {n_hold} held out")
EOF
step augment
$T --augment_clips > augment.log 2>&1 || { tail -20 augment.log; fail augment; }

step wait_features
while [ ! -f features.ok ]; do sleep 30; tail -c 200 features.log 2>/dev/null | tr '\r' '\n' | tail -1; done

# Keeps every n-th row of the negative features, for machines with too little RAM for all 17 GB.
use_fraction() {
  python3 - "$1" <<'EOF' || fail subset
import sys, numpy as np
n = int(sys.argv[1])
a = np.load("openwakeword_features_ACAV100M_2000_hrs_16bit.npy", mmap_mode="r")
rows = len(range(0, a.shape[0], n))
b = np.lib.format.open_memmap("acav_subset.npy", mode="w+", dtype=a.dtype, shape=(rows,) + a.shape[1:])
j = 0
for i in range(0, a.shape[0], 300000 * n):
    c = a[i:i + 300000 * n:n]
    b[j:j + len(c)] = c
    j += len(c)
b.flush()
print("negative features:", b.shape)
EOF
  sed -i 's/openwakeword_features_ACAV100M_2000_hrs_16bit.npy/acav_subset.npy/' my_model.yaml
}

step train
ONNX="my_custom_model/${MODEL_NAME}.onnx"
rm -f "$ONNX"
[ -n "$ACAV_FRACTION" ] && [ "$ACAV_FRACTION" != 1 ] && use_fraction "$ACAV_FRACTION"
# train.py exits with an error AFTER saving the ONNX (its own tflite export needs onnx_tf, which
# no longer installs), so success is judged by the ONNX file, never by the exit code.
$T --train_model > train.log 2>&1
if [ ! -f "$ONNX" ] && [ -z "$ACAV_FRACTION" ]; then
  echo "Training stopped without a model (usually out of RAM); retrying with a third of the negative features"
  use_fraction 3
  $T --train_model > train.log 2>&1
fi
[ -f "$ONNX" ] || { grep -aE "Killed|Traceback|Error" train.log | tail -5; fail train; }
grep -aiE "accuracy|recall|false positive" train.log | grep -v GetPrototype | tail -4

step convert
OUT="my_custom_model/${MODEL_NAME}.tflite"
onnx2tf -i "$ONNX" -o my_custom_model/ -kat onnx____Flatten_0 > convert.log 2>&1 || { tail -10 convert.log; fail convert; }
mv "my_custom_model/${MODEL_NAME}_float32.tflite" "$OUT" || fail convert
sha256sum "$OUT"

# Recall on the held-out real clips: each clip, with a second of silence around it, runs through
# the trained model the way the app streams audio (80 ms frames); a clip counts as detected when
# its peak score reaches 0.5, the app's default threshold.
step real_recall
python3 - "$ONNX" <<'EOF' || fail real_recall
import os, sys, numpy as np, soundfile as sf
if not os.path.isdir("real_holdout") or not os.listdir("real_holdout"):
    print("no held-out real clips")
    raise SystemExit(0)
from openwakeword.model import Model
m = Model(wakeword_models=[sys.argv[1]], inference_framework="onnx")
key = list(m.models.keys())[0]
by_source, peaks = {}, []
for f in sorted(os.listdir("real_holdout")):
    a, _ = sf.read(os.path.join("real_holdout", f), dtype="int16")
    a = np.concatenate([np.zeros(16000, np.int16), a, np.zeros(16000, np.int16)])
    m.reset()
    peak = max(m.predict(a[i:i + 1280])[key] for i in range(0, len(a) - 1279, 1280))
    peaks.append(peak)
    by_source.setdefault(f.rsplit("_", 1)[0], []).append(peak)
for s, p in sorted(by_source.items()):
    print(f"real recall {s}: {sum(x >= 0.5 for x in p)}/{len(p)}  (median peak {np.median(p):.2f})")
print(f"REAL RECALL: {sum(x >= 0.5 for x in peaks)}/{len(peaks)} = {np.mean([x >= 0.5 for x in peaks]):.2f}")
EOF
echo "FINISHED $WORKDIR/$OUT"
