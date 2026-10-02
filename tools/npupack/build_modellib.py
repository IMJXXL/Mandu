#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QNN 模型库构建驱动：converter 产出的 .cpp/.bin → windows-x86_64 模型库 DLL。

工具链（全部免安装/免管理员）：
  - clang-cl：NDK 29 自带（D:/Android/Sdk/ndk/29/.../clang-cl.exe）
  - lld-link：rust-lld 复制改名（COFF 链接器）
  - MSVC 头/库：tools/msvc（从 VS 17.14 官方 CDN vsix 解包）+ 本机 Windows Kits 10.0.26100
  - object-generator：QAIRT SDK 自带

用法: python build_modellib.py --model X:/MangaReader-AI/dlc/xxx [--out-dir X:/MangaReader-AI/modellib]
"""
import argparse
import os
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

SDK = Path(r"X:/115/manduAAAAAAA/v2.49.0.260730/qairt/2.49.0.260730")
MSVC = Path(r"X:/MangaReader-AI/tools/msvc/Contents/VC/Tools/MSVC/14.44.35207")
WINKIT = Path(r"C:/Program Files (x86)/Windows Kits/10")
NDK_BIN = Path(r"D:/Android/Sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin")
LLD_LINK = Path(r"X:/MangaReader-AI/tools/lld-link.exe")
OBJGEN = SDK / "bin/x86_64-windows-msvc/object-generator.exe"

ENV = {
    **os.environ,
    "QAIRT_SDK_ROOT": str(SDK),
    "INCLUDE": ";".join(str(p) for p in [
        MSVC / "include",
        WINKIT / "Include/10.0.26100.0/ucrt",
        WINKIT / "Include/10.0.26100.0/um",
        WINKIT / "Include/10.0.26100.0/shared",
    ]),
}


def run(cmd, cwd=None):
    r = subprocess.run([str(c) for c in cmd], cwd=cwd, env=ENV,
                       capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout[-3000:])
        print(r.stderr[-3000:])
        sys.exit(f"FAILED: {' '.join(str(c) for c in cmd)}")
    return r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True, help="converter 产出的 .cpp 路径（同名 .bin 须在场）")
    ap.add_argument("--out-dir", default=r"X:/MangaReader-AI/modellib")
    args = ap.parse_args()

    cpp = Path(args.model)
    stem = cpp.stem
    binf = cpp.with_suffix(".bin")
    out_dir = Path(args.out_dir) / stem
    if out_dir.exists():
        shutil.rmtree(out_dir)
    (out_dir / "raw").mkdir(parents=True)
    (out_dir / "obj/windows-x86_64").mkdir(parents=True)

    share = SDK / "share/QNN/converter/jni"
    for f in ["QnnModel.cpp", "QnnModel.hpp", "QnnModelPal.hpp", "QnnTypeMacros.hpp",
              "QnnWrapperUtils.cpp", "QnnWrapperUtils.hpp", "windows/QnnModelPal.cpp"]:
        shutil.copy2(share / f, out_dir / Path(f).name)
    shutil.copy2(cpp, out_dir / "QnnNetworkModel.cpp")

    with tarfile.open(binf) as t:
        t.extractall(out_dir / "raw")
    objs = sorted((out_dir / "obj/windows-x86_64").glob("*.o*"))
    if not objs:
        run([OBJGEN, "raw", "windows-x86_64", "obj\windows-x86_64"], cwd=out_dir)
        objs = sorted((out_dir / "obj/windows-x86_64").glob("*.o*"))
    if not objs:
        sys.exit("object-generator produced nothing")
    cc = [NDK_BIN / "clang-cl.exe", "/nologo", "/c", "/MT", "/O2", "/EHsc",
          "/DQNN_API=__declspec(dllexport)",
          f"/I{SDK}/include/QNN", "/I.",
          "QnnModel.cpp", "QnnModelPal.cpp", "QnnWrapperUtils.cpp", "QnnNetworkModel.cpp"]
    run(cc, cwd=out_dir)

    link = [LLD_LINK, "/DLL", f"/OUT:{stem}.dll",
            f"/LIBPATH:{MSVC}/lib/x64",
            f"/LIBPATH:{WINKIT}/Lib/10.0.26100.0/ucrt/x64",
            f"/LIBPATH:{WINKIT}/Lib/10.0.26100.0/um/x64",
            "libcmt.lib", "QnnModel.obj", "QnnModelPal.obj",
            "QnnWrapperUtils.obj", "QnnNetworkModel.obj"] + [o.name for o in objs]
    run(link, cwd=out_dir)

    dll = out_dir / f"{stem}.dll"
    if not dll.exists():
        sys.exit("dll not produced")
    print(f"OK {dll} ({dll.stat().st_size/1e6:.1f} MB)")


if __name__ == "__main__":
    main()
