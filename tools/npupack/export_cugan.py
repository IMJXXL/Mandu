#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Real-CUGAN SE 2x（官方 UpCunet2x）→ 静态 256→512 ONNX 导出。
图内含 reflect-pad18 + 中心裁剪（与官方 tile_mode=0 完全一致），输出=输入×2，
是全卷积图，QNN 瓦片循环的 padding 由 prepare_onnx.py 实测。

用法: python export_cugan.py --weights X:/MangaReader-AI/weights/xxx.pth --out X:/MangaReader-AI/onnx/xxx.onnx
"""
import argparse
import sys

sys.path.insert(0, r"X:/MangaReader-AI/tools")

import torch
from cugan_model import UpCunet2x


class CuganTile256(torch.nn.Module):
    """固定 256 输入瓦片；forward(x, 0) 内部 reflect-pad18 → unet → 中心裁剪 → 512。"""

    def __init__(self, net: UpCunet2x):
        super().__init__()
        self.net = net

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.net.forward(x, 0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    net = UpCunet2x()
    sd = torch.load(args.weights, map_location="cpu")
    net.load_state_dict(sd, strict=True)
    net.eval()
    model = CuganTile256(net).eval()

    x = torch.rand(1, 3, 256, 256)
    with torch.no_grad():
        y_torch = model(x)

    torch.onnx.export(
        model, x, args.out,
        opset_version=13,
        do_constant_folding=True,
        input_names=["input"], output_names=["output"],
    )

    import onnx
    import onnxruntime as ort
    onnx.checker.check_model(onnx.load(args.out))
    s = ort.InferenceSession(args.out, providers=["CPUExecutionProvider"])
    y_onnx = s.run(None, {"input": x.numpy()})[0]
    diff = float(abs(y_torch.numpy() - y_onnx).max())
    print(f'{{"out": {list(y_onnx.shape)}, "torch_vs_onnx_maxdiff": {diff:.6f}}}')


if __name__ == "__main__":
    main()
