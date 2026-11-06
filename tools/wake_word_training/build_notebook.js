// Builds train_wake_word.ipynb from train_wake_word.sh, so the notebook never drifts from the script.
// Usage: node build_notebook.js
const fs = require("fs");
const path = require("path");

const script = fs.readFileSync(path.join(__dirname, "train_wake_word.sh"), "utf8").replace(/\r\n/g, "\n");
const lines = (text) => text.split("\n").map((l, i, all) => (i < all.length - 1 ? l + "\n" : l));
const md = (text) => ({ cell_type: "markdown", metadata: {}, source: lines(text) });
const code = (text, extra = {}) => ({ cell_type: "code", metadata: extra, execution_count: null, outputs: [], source: lines(text) });

const cells = [
  md(`# Train a wake word for Bayit Voice

Trains an [openWakeWord](https://github.com/dscripka/openWakeWord) model from synthetic speech and gives you a \`.tflite\` file to import in the app (Settings → Wake word → Import).

**Before you start**
1. *Runtime → Change runtime type*: **T4 GPU**, runtime version **2026.04** (Python 3.12). The newest runtime has Python 3.13, where the speech generator does not install.
2. Fill in the form below, then *Runtime → Run all*.
3. A run takes about 2–3 hours on a T4. Keep the tab open. The Train cell downloads the model as soon as it exists: Colab deletes the runtime, and everything in it, once the session ends or sits idle.

**Tips**
- Give several spellings of the same sound: they make the synthetic voices say it in different ways. For "היי בית": \`hey bah eet\`, \`hey baayit\`, \`hey baayeet\`.
- Words the English dictionary lacks need a pronunciation in ARPAbet (\`AA\` as in "father", \`IY\` as in "see", \`IH\` as in "sit"; 1 = stressed, 0 = unstressed). The run stops early and names any word that is missing one.
- Real recordings of the phrase help most with real voices. Cut them into one 16 kHz clip per utterance with \`tools/wake_word_training/split_recordings.js\`, zip the clips, and tick **USE_REAL_CLIPS**: the second cell asks for the zip. A fifth of them is kept out of training, and the run ends with the model's recall on those.`),
  code(`#@title Your wake word
PHRASES = '["hey jarvis"]'  #@param {type:"string"}
MODEL_NAME = "hey_jarvis"  #@param {type:"string"}
PRONUNCIATIONS = '{}'  #@param {type:"string"}
N_SAMPLES = 10000  #@param {type:"integer"}
STEPS = 15000  #@param {type:"integer"}
USE_REAL_CLIPS = False  #@param {type:"boolean"}
REAL_REPEAT = 15  #@param {type:"integer"}

import json, os, re
json.loads(PHRASES); json.loads(PRONUNCIATIONS)  # fail early on a typo
assert re.fullmatch(r"[A-Za-z0-9_]+", MODEL_NAME), "MODEL_NAME: letters, digits and underscores only"
os.environ.update(PHRASES=PHRASES, MODEL_NAME=MODEL_NAME, PRONUNCIATIONS=PRONUNCIATIONS,
                  N_SAMPLES=str(N_SAMPLES), STEPS=str(STEPS), REAL_REPEAT=str(REAL_REPEAT), WORKDIR="/content")
os.environ.pop("REAL_CLIPS", None)
print("ok")`, { cellView: "form" }),
  code(`#@title Upload real clips (only with USE_REAL_CLIPS)
import os, shutil, zipfile
if USE_REAL_CLIPS:
    from google.colab import files
    print("Choose a .zip of 16 kHz mono WAV clips, one utterance each")
    uploaded = files.upload()
    shutil.rmtree("/content/real_clips", ignore_errors=True)
    os.makedirs("/content/real_clips")
    for name in uploaded:
        with zipfile.ZipFile(name) as z:
            for member in z.namelist():
                if member.lower().endswith(".wav") and not member.startswith("__MACOSX"):
                    with z.open(member) as src, open(os.path.join("/content/real_clips", os.path.basename(member)), "wb") as dst:
                        shutil.copyfileobj(src, dst)
    n = len(os.listdir("/content/real_clips"))
    assert n > 0, "No .wav files in the zip"
    os.environ["REAL_CLIPS"] = "/content/real_clips"
    print(n, "real clips ready")
else:
    print("synthetic only")`, { cellView: "form" }),
  md("The training script (the same file as `tools/wake_word_training/train_wake_word.sh` in the app's repository)."),
  // %%writefile must be the first line of its cell.
  code(`%%writefile /content/train_wake_word.sh\n${script}`),
  code(`#@title Train (2–3 hours; progress below)
import subprocess, sys
proc = subprocess.Popen(["bash", "/content/train_wake_word.sh"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
result = None
for line in proc.stdout:
    if line.startswith(("===", "FINISHED", "FAILED")) or any(w in line.lower() for w in ("accuracy", "recall", "retrying", "real clips", "held out")):
        print(line, end="", flush=True)
    if line.startswith(("FINISHED", "FAILED")):
        result = line.strip()
proc.wait()
# Download right away: Colab deletes the runtime, and the model with it, once the session ends
# or idles out. A model is worth keeping even when a later step (the recall check) failed.
import os
model_path = f"/content/my_custom_model/{MODEL_NAME}.tflite"
if os.path.exists(model_path):
    from google.colab import files
    files.download(model_path)
if not (result or "").startswith("FINISHED"):
    raise SystemExit(f"Training did not finish: {result}. Logs: /content/*.log")`, { cellView: "form" }),
  code(`#@title Download the model again (the Train cell already downloads it)
from google.colab import files
files.download(f"/content/my_custom_model/{MODEL_NAME}.tflite")`, { cellView: "form" }),
];

const notebook = {
  nbformat: 4,
  nbformat_minor: 0,
  metadata: {
    accelerator: "GPU",
    colab: { provenance: [], gpuType: "T4" },
    kernelspec: { name: "python3", display_name: "Python 3" },
    language_info: { name: "python" },
  },
  cells,
};
fs.writeFileSync(path.join(__dirname, "train_wake_word.ipynb"), JSON.stringify(notebook, null, 1) + "\n");
console.log("wrote train_wake_word.ipynb,", cells.length, "cells");
