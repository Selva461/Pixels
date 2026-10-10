# Bundled models

| File | What | Source | Licence | SHA-256 |
|---|---|---|---|---|
| `deeplab_v3.tflite` | DeepLab v3 (MobileNet v2), PASCAL VOC 21 classes, 257×257 float input | https://storage.googleapis.com/mediapipe-models/image_segmenter/deeplab_v3/float32/1/deeplab_v3.tflite | Apache 2.0 | `ff36e24d40547fe9e645e2f4e8745d1876d6e38b332d39a82f0bf0f5d1d561b3` |

Used only to find people for Smart edit (`TfLitePeopleSegmenter`). It runs on the phone with
TensorFlow Lite; nothing is downloaded and no pixels are generated. `scripts/security_gate.py`
checks the checksum, so a changed model fails CI until this table is updated.
