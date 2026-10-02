#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
漫读 NPU 模型包打包脚本：把一代的 context + models.json 组装进 :modelapk 模块并构建 APK。

用法（仓库根目录）:
  python tools/npupack/package_model_apk.py --arch 75 --contexts-dir X:/MangaReader-AI/contexts/v75

流程:
  1. 校验该代目录下的 context（<stem>.v<arch>.bin）都在清单 stem 集合内；
  2. 生成 models.json（protocolVersion=1，与 NpuModelPluginScanner 严格对应）；
  3. 复制到 modelapk/src/main/assets/；
  4. 调 gradlew :modelapk:assembleRelease -PmodelId=com.mangareader.model.v<arch> ...；
  5. 产物拷到 X:/MangaReader-AI/model-apk/漫读-NPU模型-v<arch>.apk。
"""
import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ASSETS = REPO / "modelapk" / "src" / "main" / "assets"
OUT_DIR = Path("X:/MangaReader-AI/model-apk")

# 与 NpuModelRegistry.stemFor 严格一致的档位清单（首发两个 NPU 档 + 降噪变体）。
# padding：CUGAN=官方 tile reflect-pad 18；动漫V3=Real-ESRGAN 官方瓦片边 10（Komiho 同款实测）。
# context 来源：mihon_img_upscale v1.3.2 release APK（同源权重 + QAIRT 离线编译，仅 FP16）；
# 后续自编译走 tools/npupack/build_modellib.py + Linux 端 context 生成（Windows 主机不支持）。
MODEL_STEMS = {
    "realcugan-se-2x-conservative": ("realcugan_2x_conservative", 2, 18, "CUGAN 2x 保守（NPU）"),
    "realcugan-se-2x-denoise1x": ("realcugan_2x_conservative", 2, 18, "CUGAN 2x 轻度降噪（NPU）"),
    "realcugan-se-2x-denoise3x": ("realcugan_2x_conservative", 2, 18, "CUGAN 2x 强力降噪（NPU）"),
    "animevideov3-2x": ("animevideov3_2x", 2, 10, "动漫V3 2x 极速（NPU）"),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--arch", type=int, required=True, help="HTP 代，如 75")
    ap.add_argument("--contexts-dir", required=True, help="context 目录（<stem>.v<arch>.bin）")
    ap.add_argument("--skip-build", action="store_true", help="只生成 assets，不调 gradle")
    args = ap.parse_args()

    arch = args.arch
    ctx_dir = Path(args.contexts_dir)
    bins = sorted(ctx_dir.glob(f"*.v{arch}.bin"))
    if not bins:
        sys.exit(f"no context *.v{arch}.bin in {ctx_dir}")

    entries = []
    for b in bins:
        stem = b.name[: b.name.rfind(f".v{arch}.bin")]
        if stem not in MODEL_STEMS:
            print(f"skip unknown stem: {stem}")
            continue
        _, scale, padding, label = MODEL_STEMS[stem]
        entries.append({
            "id": f"{stem}-v{arch}",
            "stem": stem,
            "scale": scale,
            "padding": padding,
            "arches": [str(arch)],
            "label": label,
            "assetDir": "qnn-contexts",
        })
    if not entries:
        sys.exit("all contexts unknown to registry — check MODEL_STEMS")

    manifest = {"protocolVersion": 1, "models": entries}

    ctx_assets = ASSETS / "qnn-contexts"
    if ctx_assets.exists():
        shutil.rmtree(ctx_assets)
    ctx_assets.mkdir(parents=True)
    for b in bins:
        shutil.copy2(b, ctx_assets / b.name)
    (ASSETS / "models.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"assets ready: {len(bins)} contexts, manifest entries={len(entries)}")

    if args.skip_build:
        return

    pkg_id = f"com.mangareader.model.v{arch}"
    label = f"漫读-NPU模型-v{arch}"
    subprocess.run([
        "cmd", "/c", "gradlew.bat", ":modelapk:assembleRelease",
        f"-PmodelId={pkg_id}", f"-PmodelLabel={label}", "-PmodelVersionCode=1",
        "--no-daemon", "--max-workers=1",
    ], check=True, cwd=REPO)

    apk_src = REPO / "modelapk" / "build" / "outputs" / "apk" / "release" / "modelapk-release.apk"

    # 后处理：AGP 9 托管 desugaring 会给无代码模块塞 ~2.3MB desugar 运行时 dex（hasCode=false
    # 下纯死重）。剥掉 dex 后必须重签（改动使原 v2 签名失效）——apksigner 重出 v1+v2 签名。
    stripped = apk_src.with_name("modelapk-release-stripped.apk")
    import zipfile
    with zipfile.ZipFile(apk_src) as zin, zipfile.ZipFile(stripped, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            if item.filename == "classes.dex":
                continue
            zout.writestr(item, zin.read(item.filename))
    apksigner = Path(r"D:/Android/Sdk/build-tools/36.0.0/apksigner.bat")
    ks = REPO / "manga-reader.jks"
    subprocess.run([
        str(apksigner), "sign",
        "--ks", str(ks), "--ks-key-alias", "manga-reader",
        "--ks-pass", "pass:M@ngaR3ader#2026", "--key-pass", "pass:M@ngaR3ader#2026",
        "--out", str(apk_src), str(stripped),
    ], check=True)
    stripped.unlink()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    apk_out = OUT_DIR / f"漫读-NPU模型-v{arch}.apk"
    shutil.copy2(apk_src, apk_out)
    print(f"APK -> {apk_out}")


if __name__ == "__main__":
    main()
