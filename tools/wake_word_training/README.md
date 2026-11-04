# Wake word training

These files train an openWakeWord model for the app, from synthetic speech only.

| File | What it is |
|---|---|
| `train_wake_word.sh` | The whole pipeline in one script. Runs on Colab or on any Linux machine with an NVIDIA GPU. |
| `train_wake_word.ipynb` | A Colab notebook: a form, the script, a progress cell and a download cell. |
| `build_notebook.js` | Rebuilds the notebook from the script (`node build_notebook.js`). Run it after every change to the script. |

## Running it

**On Colab**
1. Upload the notebook (*File → Upload notebook*).
2. In *Runtime → Change runtime type*, choose **T4 GPU** and runtime version **2026.04** (Python 3.12).
3. Fill in the form, then *Run all*.
4. The run takes about 2–3 hours, and the last cell downloads `<MODEL_NAME>.tflite`.

**On your own GPU machine (Linux or WSL, Python 3.12, CUDA)**
```bash
PHRASES='["hey bah eet","hey baayit","hey baayeet"]' MODEL_NAME=hey_bayit \
PRONUNCIATIONS='{"eet":["IY1 T"],"baayit":["B AA1 Y IH0 T"],"baayeet":["B AA1 Y IY0 T"]}' \
bash train_wake_word.sh
```
The model lands in `oww-work/my_custom_model/`. A rerun reuses everything already downloaded.

Import the `.tflite` in the app: *Settings → Wake word → Import*.

## How it works

The script uses openWakeWord's own `train.py` and its `examples/custom_model.yml`. It adds no training logic of its own:

1. **Generate**: Piper (`en_US-libritts_r-medium`, 900+ speakers) speaks each spelling in `PHRASES` thousands of times, plus confusable negative phrases.
2. **Augment**: it mixes in room echo (MIT impulse responses) and background noise (AudioSet).
3. **Train**: a small classifier learns on top of openWakeWord's fixed melspectrogram and embedding models. ~2,000 hours of precomputed speech features (ACAV100M) serve as negatives.
4. **Convert**: the result goes from ONNX to TFLite, the format the app loads.

## What the official notebook gets wrong today (and how the script works around it)

| Problem | Workaround |
|---|---|
| Colab's newest runtime has Python 3.13; `piper-phonemize-cross` has no wheel for it. | Use runtime version 2026.04 (Python 3.12). The script checks for it. |
| Words missing from the English dictionary fall back to DeepPhonemizer, whose model download is gone. | `PRONUNCIATIONS` patches the lookup in `openwakeword/data.py` with your own ARPAbet. The run stops early if a word has none. |
| The notebook's background-noise source is dead. | Download AudioSet's balanced set as parquet from Hugging Face (`agkphysics/AudioSet`) and decode it to 16 kHz WAV. |
| `train.py` saves the ONNX model, then crashes in its own TFLite export, because `onnx_tf` no longer installs. | Judge success by the ONNX file, and convert with `onnx2tf -kat onnx____Flatten_0`. |
| All 17 GB of negative features can exhaust Colab's RAM, and the process is killed silently. | Retry automatically with every third row (`ACAV_FRACTION=3` forces it). |
| Pinned versions drift. | Pinned: `piper-sample-generator` at `213d4d5`; torch 2.5.0 (cu121); onnx 1.19.1; onnxruntime 1.22.1; speechbrain 0.5.14; audiomentations 0.33.0; torch-audiomentations 0.11.0. |

## Results so far ("היי בית", 2026-11-04)

| Run | Spellings | Accuracy | Recall | False positives/hour |
|---|---|---|---|---|
| `hey_bah_eet` | 1 | — | — | — |
| `hey_bayit` (1/3 of the negatives) | `hey bah eet`, `hey baayit`, `hey baayeet` | 0.687 | 0.375 | 0.177 |

The numbers come from synthetic validation clips. They are much lower than real use: on the phone, `hey_bayit` detects the user's natural "היי בית" very well and had no false triggers over a whole day. Judge a model on the device, using the `utterance peak score` debug log and `tools/wake_report.sh`.

## Next: real recordings

Synthetic English voices only approximate Hebrew. Recordings of real speakers saying the phrase should help most with other voices in the home. Keep recordings outside git (for example `recordings/`, which is git-ignored). openWakeWord can mix real clips into the positive set alongside the generated ones.
