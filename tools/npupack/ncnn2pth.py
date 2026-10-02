#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Real-CUGAN SE 2x：ncnn(param+bin) → PyTorch state_dict(.pth)。

我们 assets 里的 ncnn 权重（GPU 档在用）与 bilibili 官方 SE pth 同源，
本脚本把它们映射回 UpCunet2x 的 state_dict，供 NPU 导出链使用——
保证 NPU 档与 GPU 档权重逐字节同源。

bin 布局：全局 magic(19950407) 后为逐 blob 裸流，大部分 fp16、
少数（末段 bias/输出层）fp32，无 per-blob flag——用 DFS 找与文件
长度唯一自洽的分段方案。数值用 ncnn 官方 runtime 对拍验证。

用法: python ncnn2pth.py --param x.param --bin x.bin --out x.pth --verify
"""
import argparse
import struct
import sys

import numpy as np
import torch

BIN_MAGIC = 19950407


def parse_param(path):
    layers = []
    with open(path, "r", encoding="utf-8") as f:
        lines = f.read().strip().splitlines()
    for line in lines[2:]:
        toks = line.split()
        op, name = toks[0], toks[1]
        n_in, n_out = int(toks[2]), int(toks[3])
        params = {}
        for t in toks[4 + n_in + n_out :]:
            k, v = t.split("=", 1)
            params[int(k)] = v
        layers.append((op, name, params))
    return layers


def build_mapping(deconv_mode="direct"):
    """torch.state_dict 键 ← ncnn 层（顺序与 param 逐行对应）。"""
    M = []

    def conv(layer, key, out_, in_, k):
        M.append(("Convolution", layer, key + ".weight", (out_, in_, k, k), key + ".bias"))

    def deconv(layer, key, out_, in_, k):
        # torch ConvTranspose2d 权重恒为 (in, out, kh, kw)；
        # deconv_mode 决定 ncnn 原始数据的通道语义（direct=按 in 读再换轴 / swap=按 out 读再换轴）
        M.append(("Deconvolution", layer, key + ".weight", (in_, out_, k, k), key + ".bias", deconv_mode))

    def ip(layer, key, out_, in_):
        M.append(("InnerProduct", layer, key + ".weight", (out_, in_, 1, 1), key + ".bias"))

    conv("convrelu_0", "unet1.conv1.conv.0", 32, 3, 3)
    conv("convrelu_1", "unet1.conv1.conv.2", 64, 32, 3)
    conv("convrelu_2", "unet1.conv1_down", 64, 64, 2)
    conv("convrelu_3", "unet1.conv2.conv.0", 128, 64, 3)
    conv("convrelu_4", "unet1.conv2.conv.2", 64, 128, 3)
    ip("convrelu_5", "unet1.conv2.seblock.conv1", 8, 64)
    ip("convsigmoid_21", "unet1.conv2.seblock.conv2", 64, 8)
    deconv("deconvrelu_0", "unet1.conv2_up", 64, 64, 2)
    conv("convrelu_6", "unet1.conv3", 64, 64, 3)
    deconv("deconv_48", "unet1.conv_bottom", 3, 64, 4)
    conv("convrelu_7", "unet2.conv1.conv.0", 32, 3, 3)
    conv("convrelu_8", "unet2.conv1.conv.2", 64, 32, 3)
    conv("convrelu_9", "unet2.conv1_down", 64, 64, 2)
    conv("convrelu_10", "unet2.conv2.conv.0", 64, 64, 3)
    conv("convrelu_11", "unet2.conv2.conv.2", 128, 64, 3)
    ip("convrelu_12", "unet2.conv2.seblock.conv1", 16, 128)
    ip("convsigmoid_22", "unet2.conv2.seblock.conv2", 128, 16)
    conv("convrelu_13", "unet2.conv2_down", 128, 128, 2)
    conv("convrelu_14", "unet2.conv3.conv.0", 256, 128, 3)
    conv("convrelu_15", "unet2.conv3.conv.2", 128, 256, 3)
    ip("convrelu_16", "unet2.conv3.seblock.conv1", 16, 128)
    ip("convsigmoid_23", "unet2.conv3.seblock.conv2", 128, 16)
    deconv("deconvrelu_1", "unet2.conv3_up", 128, 128, 2)
    conv("convrelu_17", "unet2.conv4.conv.0", 64, 128, 3)
    conv("convrelu_18", "unet2.conv4.conv.2", 64, 64, 3)
    ip("convrelu_19", "unet2.conv4.seblock.conv1", 8, 64)
    ip("convsigmoid_24", "unet2.conv4.seblock.conv2", 64, 8)
    deconv("deconvrelu_2", "unet2.conv4_up", 64, 64, 2)
    conv("convrelu_20", "unet2.conv5", 64, 64, 3)
    conv("conv_46", "unet2.conv_bottom", 3, 64, 3)
    return M


def plan_layout(layers, data):
    """DFS 找与文件长度唯一自洽的逐 blob 存储形态（fp16/fp32 裸流）。"""
    seq = []
    for op, name, p in layers:
        wc = int(p[6]) if op in ("Convolution", "Deconvolution") else int(p[2]) if op == "InnerProduct" else 0
        if not wc:
            continue
        bc = int(p[0]) if int(p.get(5, p.get(1, 0))) else 0
        seq.append((name, "w", wc))
        if bc:
            seq.append((name, "b", bc))

    memo = {}

    def dfs(i, pos, path):
        if (i, pos) in memo:
            return memo[(i, pos)]
        if i == len(seq):
            return [path] if pos == len(data) else []
        res = []
        nm, label, cnt = seq[i]
        for npos, form in ((pos + cnt * 2, "fp16"), (pos + cnt * 4, "fp32")):
            if npos <= len(data):
                r = dfs(i + 1, npos, path + [(nm, label, form)])
                if r:
                    res.extend(r)
                    break
        memo[(i, pos)] = res
        return res

    sols = dfs(0, 4, [])
    if len(sols) != 1:
        sys.exit(f"bin layout ambiguous/unsolvable: {len(sols)} solutions")
    return sols[0]


def convert(param_path, bin_path, deconv_mode="direct"):
    layers = parse_param(param_path)
    data = open(bin_path, "rb").read()
    if struct.unpack_from("<i", data, 0)[0] != BIN_MAGIC:
        sys.exit("bin magic mismatch")
    plan = plan_layout(layers, data)
    by_name = {name: (op, p) for op, name, p in layers}
    pos = 4
    sd = {}
    for entry in build_mapping(deconv_mode):
        op, layer, key, wshape, bkey = entry[:5]
        deconv_mode_i = entry[5] if len(entry) > 5 else None
        lop, p = by_name[layer]
        if lop != op:
            sys.exit(f"op mismatch for {layer}")
        form = next(f for nm, lb, f in plan if nm == layer and lb == "w")
        cnt = int(np.prod(wshape))
        dt = "<f2" if form == "fp16" else "<f4"
        w = np.frombuffer(data, dtype=dt, count=cnt, offset=pos).astype("<f4")
        pos += cnt * (2 if form == "fp16" else 4)
        if lop == "Deconvolution" and len(wshape) == 4:
            in_c, out_c, kh, kw = wshape
            raw = w.reshape(out_c, in_c, kh, kw)
            if deconv_mode_i == "direct":
                w = np.ascontiguousarray(raw.transpose(1, 0, 2, 3))
            else:
                w = raw  # swap：ncnn 数据本来就是 (out,in) 语义，仅换轴读法
            sd[key] = torch.from_numpy(np.ascontiguousarray(w))
        else:
            sd[key] = torch.from_numpy(w.reshape(wshape).copy())
        if int(p.get(5, p.get(1, 0))):
            form = next(f for nm, lb, f in plan if nm == layer and lb == "b")
            cnt = int(p[0])
            dt = "<f2" if form == "fp16" else "<f4"
            b = np.frombuffer(data, dtype=dt, count=cnt, offset=pos).astype("<f4")
            pos += cnt * (2 if form == "fp16" else 4)
            sd[bkey] = torch.from_numpy(b.copy())
    if pos != len(data):
        sys.exit(f"bin not fully consumed: {pos}/{len(data)}")
    return sd


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--param", required=True)
    ap.add_argument("--bin", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--verify", action="store_true")
    args = ap.parse_args()

    sys.path.insert(0, r"X:/MangaReader-AI/tools")
    from cugan_model import UpCunet2x

    saved_mode = None
    for mode in ("direct", "swap"):
        sd = convert(args.param, args.bin, mode)
        net = UpCunet2x()
        net.load_state_dict(sd, strict=True)
        net.eval()
        rng = np.random.default_rng(11)
        rgb01 = rng.random((1, 3, 256, 256), dtype=np.float32)
        with torch.no_grad():
            y = net(torch.from_numpy(rgb01), 0).numpy()
        finite = bool(np.isfinite(y).all())
        print(f"mode={mode} range=({float(y.min()):.4f},{float(y.max()):.4f}) finite={finite}")
        if finite and saved_mode is None:
            saved_mode = mode
            torch.save(sd, args.out)
            print(f"saved {args.out} (mode={mode}, {len(sd)} tensors)")

    if args.verify and saved_mode:
        # ncnn 官方 runtime 对拍（BGR 0-1 + reflect-pad18 → graph → 512）
        import ncnn
        import torch.nn.functional as F
        n = ncnn.Net()
        n.opt.use_vulkan_compute = False
        n.opt.num_threads = 4
        n.load_param(args.param)
        n.load_model(args.bin)
        rng = np.random.default_rng(11)
        rgb01 = rng.random((1, 3, 256, 256), dtype=np.float32)
        padded = F.pad(torch.from_numpy(rgb01[:, [2, 1, 0], :, :]), (18, 18, 18, 18), "reflect").numpy()
        ex = n.create_extractor()
        ex.input("in0", ncnn.Mat(np.ascontiguousarray(padded[0])))
        ret, out = ex.extract("out0")
        y_ncnn = np.array(out)[None][:, [2, 1, 0], :, :]
        net = UpCunet2x()
        net.load_state_dict(torch.load(args.out, map_location="cpu"), strict=True)
        net.eval()
        with torch.no_grad():
            y_torch = net(torch.from_numpy(rgb01), 0).numpy()
        diff = float(np.abs(y_torch - y_ncnn).max())
        print(f"torch vs ncnn maxdiff: {diff:.6f}")
        if diff > 0.02:
            sys.exit("VERIFY FAILED")


if __name__ == "__main__":
    main()
