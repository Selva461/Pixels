"""Subject (person) matte from ONNX salient-object / human segmentation models.

Supports rembg-style ONNX exports of BiRefNet (1024x1024) and U2Net (320x320).
Input: sRGB-encoded float image (H,W,3) in 0..1. Output: float matte (H,W) in 0..1.
"""
import numpy as np, cv2
import onnxruntime as ort

MEAN = np.array([0.485, 0.456, 0.406]); STD = np.array([0.229, 0.224, 0.225])


def run_model(model_path, rgb, size):
    H, W = rgb.shape[:2]
    x = cv2.resize(np.clip(rgb, 0, 1).astype(np.float32), (size, size), interpolation=cv2.INTER_AREA)
    x = (x - MEAN) / STD
    x = x.transpose(2, 0, 1)[None].astype(np.float32)
    so = ort.SessionOptions(); so.intra_op_num_threads = 2
    sess = ort.InferenceSession(model_path, so, providers=['CPUExecutionProvider'])
    out = sess.run(None, {sess.get_inputs()[0].name: x})[0][0, 0]
    if out.min() < 0 or out.max() > 1:  # logits -> probability
        out = 1 / (1 + np.exp(-out))
    out = (out - out.min()) / max(out.max() - out.min(), 1e-6)
    return cv2.resize(out.astype(np.float32), (W, H), interpolation=cv2.INTER_CUBIC).clip(0, 1)
