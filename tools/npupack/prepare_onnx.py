#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
pnnx 产出的 ncnn 系 ONNX 后处理：
  1) BGR→RGB 手术：首层 Conv 的输入通道 0/2 对调（ncnn 约定 BGR，我们的 QNN 瓦片喂 RGBA=RGB）；
  2) onnxruntime 数值验证：手术图(RGB输入) ≈ 原图(BGR输入=RGB反转) —— 证明手术不改变网络语义；
  3) padding 实测：中心脉冲激励，量输出响应半径 r，padding = ceil(r/scale) + 安全余量；
  4) 几何校验：输出边长必须 = 输入边长 × scale。

用法:
  python prepare_onnx.py --onnx in.onnx --out out.onnx [--tile 256] [--margin 2]
打印 JSON: {"out_shape":[...], "scale":2, "padding":N, "verify_max_diff":x}
"""
import argparse
import math
import sys

import numpy as np
import onnx
import onnxruntime as ort


def find_first_conv_weight(model: onnx.ModelProto):
    graph_input = {i.name for i in model.graph.input}
    for node in model.graph.node:
        if node.op_type != "Conv":
            continue
        if not node.input or node.input[0] not in graph_input:
            continue
        w = node.input[1]
        for init in model.graph.initializer:
            if init.name == w:
                return init
        # 权重若是 Constant 节点产出
        for n2 in model.graph.node:
            if n2.op_type == "Constant" and n2.output[0] == w:
                for out in n2.attribute:
                    if out.name == "value":
                        return out.t
    return None


def swap_bgr(model: onnx.ModelProto, weight_init) -> None:
    arr = onnx.numpy_helper.to_array(weight_init)
    if arr.ndim != 4 or arr.shape[1] != 3:
        sys.exit(f"first conv weight unexpected shape {arr.shape}")
    arr = arr.copy()
    arr[:, [0, 2]] = arr[:, [2, 0]]
    new = onnx.numpy_helper.from_array(arr, weight_init.name)
    weight_init.CopyFrom(new)


def ort_session(model_bytes: bytes) -> "ort.InferenceSession":
    so = ort.SessionOptions()
    so.intra_op_num_threads = 4
    return ort.InferenceSession(model_bytes, so, providers=["CPUExecutionProvider"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--onnx", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--tile", type=int, default=256)
    ap.add_argument("--margin", type=int, default=2, help="padding 安全余量（px，输入域）")
    ap.add_argument("--no-surgery", action="store_true",
                    help="跳过 BGR→RGB 首层手术（官方 PyTorch 导出的图本身是 RGB）")
    args = ap.parse_args()

    model = onnx.load(args.onnx)
    weight = None if args.no_surgery else find_first_conv_weight(model)
    if not args.no_surgery and weight is None:
        sys.exit("first Conv (directly on graph input) not found — manual check needed")

    # 原图句柄：BGR 语义
    orig_bytes = model.SerializeToString()
    s_orig = ort_session(orig_bytes)
    in_name = s_orig.get_inputs()[0].name
    ishape = s_orig.get_inputs()[0].shape  # [1,3,256,256]
    oshape = s_orig.get_outputs()[0].shape
    c_in, tile = ishape[1], ishape[2]
    c_out, otile = oshape[1], oshape[2]
    if c_in != 3 or tile != args.tile:
        sys.exit(f"unexpected IO shape {ishape} -> {oshape}")
    if otile % tile != 0:
        sys.exit(f"output {otile} not multiple of input {tile} — context geometry mismatch")
    scale = otile // tile

    # 语义验证：swap 输入通道喂原图 vs 直接喂手术图
    rng = np.random.default_rng(7)
    rgb = rng.random((1, 3, tile, tile), dtype=np.float32)
    if args.no_surgery:
        # 无手术：图本身就是 RGB 语义，只需验证输出几何
        diff = 0.0
    else:
        bgr = rgb[:, [2, 1, 0], :, :].copy()
        y_bgr = s_orig.run(None, {in_name: bgr})[0]

    if not args.no_surgery:
        swap_bgr(model, weight)
    s_new = ort_session(model.SerializeToString())
    y_rgb = s_new.run(None, {in_name: rgb})[0]
    if not args.no_surgery:
        # 输出同样是 BGR 语义（网络端到端保通道语义），比较前把输出也反转回 RGB
        y_rgb_cmp = y_rgb[:, [2, 1, 0], :, :]
        diff = float(np.max(np.abs(y_rgb_cmp - y_bgr)))
    del s_orig

    # padding 实测：中心脉冲
    impulse = np.zeros((1, 3, tile, tile), dtype=np.float32)
    cy = cx = tile // 2
    impulse[0, :, cy, cx] = 1.0
    y = s_new.run(None, {in_name: impulse})[0]
    mag = np.abs(y[0]).sum(axis=0)  # [H_out, W_out]
    ys, xs = np.nonzero(mag > (mag.max() * 1e-3))
    oc_y, oc_x = cy * scale, cx * scale
    r = int(max(np.abs(ys - oc_y).max(), np.abs(xs - oc_x).max()))
    padding = min(48, math.ceil(r / scale) + args.margin)

    onnx.save(model, args.out)
    print(
        f'{{"out_shape": {list(oshape)}, "scale": {scale}, '
        f'"padding": {padding}, "impulse_radius_out": {r}, "verify_max_diff": {diff:.6f}}}'
    )


if __name__ == "__main__":
    main()
