# -*- coding: utf-8 -*-
"""
漫读纸张纹理生成器 v2（纯 numpy + Pillow，无 scipy 依赖）

对比桌面旧版 生成脚本.txt 的修复与增强：
1. 修 fbm 全通 bug：旧版 exp(-2π²σ²r²) 的 σ 传的是 0.004~0.03（像素单位下等于不滤波），
   所有 octave 都是白噪声，多尺度云纹从未出现。本版 octave 的 σ 按像素 1→16 递增，真多尺度。
2. 1/f^β 谱合成：纸张"成形云纹"（formation mottling）用幂律谱直接生成，比 octave 叠加更像纸。
3. 方向性纤维：真纸纤维沿造纸机方向（横向）取向，旧版是各向同性随机方向。
4. 新增罗纹（laid）帘纹：传统纸的细密竖帘线 + 稀疏横向挂链线。
5. 全部滤波用 FFT 周期卷积（mode wrap 语义），保证严格无缝平铺；无 scipy。

输出：app/src/main/assets/textures/paper_*.webp（512×512，与现有 6 张 CC0 图同尺寸）
预览：tools/preview/preview_grid.png（供人工检查，不入库）
"""
import os
import numpy as np
from PIL import Image

SIZE = 512
ASSET_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "textures")
PREVIEW_DIR = os.path.join(os.path.dirname(__file__), "preview")


# ---------- 基础：FFT 周期高斯模糊（严格无缝，等价 gaussian_filter(mode='wrap')） ----------
def fft_blur(img2d, sigma_px):
    if sigma_px <= 0:
        return img2d
    fy = np.fft.fftfreq(img2d.shape[0])[:, None]
    fx = np.fft.fftfreq(img2d.shape[1])[None, :]
    H = np.exp(-2.0 * (np.pi ** 2) * (sigma_px ** 2) * (fx ** 2 + fy ** 2))
    return np.real(np.fft.ifft2(np.fft.fft2(img2d) * H))


def fft_blur_aniso(img2d, sigma_x, sigma_y):
    fy = np.fft.fftfreq(img2d.shape[0])[:, None]
    fx = np.fft.fftfreq(img2d.shape[1])[None, :]
    H = np.exp(-2.0 * (np.pi ** 2) * ((sigma_x ** 2) * (fx ** 2) + (sigma_y ** 2) * (fy ** 2)))
    return np.real(np.fft.ifft2(np.fft.fft2(img2d) * H))


def norm01(a):
    lo, hi = a.min(), a.max()
    return (a - lo) / (hi - lo + 1e-8)


# ---------- 多尺度底噪：octave 真多尺度（σ 按像素翻倍） ----------
def fbm_noise(size, octaves=5, base_sigma_px=1.0, seed=0, aniso_y=1.0):
    """每 octave：白噪声经 σ=base*2^o 的周期高斯低通。返回 0..1。"""
    rng = np.random.default_rng(seed)
    result = np.zeros((size, size), dtype=np.float64)
    total = 0.0
    amp = 1.0
    for o in range(octaves):
        sigma = base_sigma_px * (2 ** o)
        white = rng.standard_normal((size, size))
        if aniso_y != 1.0:
            n = fft_blur_aniso(white, sigma, sigma * aniso_y)
        else:
            n = fft_blur(white, sigma)
        s = n.std() + 1e-8
        result += (n - n.mean()) / s * amp
        total += amp
        amp *= 0.55
    return norm01(result / total)


# ---------- 1/f^β 谱噪声：纸张成形云纹 ----------
def spectral_noise(size, beta=1.3, seed=0, f0=1.5, aniso_y=1.0):
    """功率谱 ∝ 1/f^β 的周期噪声。f0 防低频发散，aniso_y>1 让云纹沿横向拉长。"""
    rng = np.random.default_rng(seed)
    white = rng.standard_normal((size, size))
    fy = np.fft.fftfreq(size)[:, None] * size  # 周期/像素
    fx = np.fft.fftfreq(size)[None, :] * size
    r = np.sqrt(fx ** 2 + (fy * aniso_y) ** 2)
    r[0, 0] = np.inf  # 去直流
    spec = np.fft.fft2(white) / (r ** (beta / 2.0))
    spec[r <= f0] = 0
    spec[0, 0] = 0
    n = np.real(np.fft.ifft2(spec))
    return norm01(n)


# ---------- 纤维：方向性随机游走（横向为主） ----------
def fiber_layer(size, seed, n_fibers, length_range=(15, 90), dir_spread=0.45, thickness_blur=0.7):
    rng = np.random.default_rng(seed)
    fibers = np.zeros((size, size), dtype=np.float64)
    for _ in range(n_fibers):
        y0 = int(rng.integers(0, size))
        x0 = int(rng.integers(0, size))
        theta = rng.normal(0.0, dir_spread)  # 0 = 横向（造纸机方向）
        length = int(rng.integers(*length_range))
        vy, vx = np.sin(theta), np.cos(theta)
        cy, cx = 0.0, 0.0
        jy, jx = rng.normal(0, 0.08), rng.normal(0, 0.08)
        for _ in range(length):
            iy, ix = int(round(y0 + cy)) % size, int(round(x0 + cx)) % size
            fibers[iy, ix] += 1.0
            vy += jy * rng.standard_normal()
            vx += jx * rng.standard_normal()
            vlen = np.hypot(vy, vx) + 1e-8
            vy, vx = vy / vlen, vx / vlen
            cy += vy
            cx += vx
    fibers = fft_blur(fibers, thickness_blur)
    return norm01(fibers)


# ---------- 罗纹帘线：细密竖线 + 稀疏横链线 ----------
def laid_pattern(size, line_spacing=4, line_depth=0.5, chain_spacing=96, chain_depth=0.5):
    x = np.arange(size)[None, :]
    y = np.arange(size)[:, None]
    lines = 1.0 - line_depth * (0.5 + 0.5 * np.cos(2 * np.pi * x / line_spacing))
    chain = 1.0 - chain_depth * (0.5 + 0.5 * np.cos(2 * np.pi * y / chain_spacing))
    pat = lines * chain
    # 轻微扰动，避免过于规则
    pat = pat * (0.92 + 0.16 * fbm_noise(size, octaves=3, base_sigma_px=2.0, seed=77))
    return norm01(pat)


# ---------- 老化污渍 / 斑点（周期边界，与旧版同思路） ----------
def stains(size, seed, n_stains=12, r_range=(60, 200)):
    rng = np.random.default_rng(seed)
    out = np.zeros((size, size), dtype=np.float64)
    y, x = np.ogrid[:size, :size]
    for _ in range(n_stains):
        cy, cx = int(rng.integers(0, size)), int(rng.integers(0, size))
        r = int(rng.integers(*r_range))
        dy = np.minimum(np.abs(y - cy), size - np.abs(y - cy))
        dx = np.minimum(np.abs(x - cx), size - np.abs(x - cx))
        d = np.sqrt(dx ** 2 + dy ** 2)
        amp = rng.uniform(0.25, 0.8)
        # 两个不同宽度的核叠加，边缘不那么"高斯圆"
        out += amp * np.exp(-(d ** 2) / (2 * r ** 2))
        out += amp * 0.6 * np.exp(-(d ** 2) / (2 * (r * 0.45) ** 2))
    return norm01(out)


def spots(size, seed, density, blur):
    rng = np.random.default_rng(seed)
    s = (rng.random((size, size)) > (1 - density)).astype(np.float64)
    s = fft_blur(s, blur)
    return norm01(s) if s.max() > 0 else s


# ---------- 合成输出 ----------
def make_texture(size, base_rgb, tex01, strength):
    base = np.array(base_rgb, dtype=np.float64) / 255.0
    mod = (tex01 - 0.5) * 2.0 * strength
    rgb = np.clip(base[None, None, :] * (1.0 + mod[..., None]), 0, 1)
    return np.round(rgb * 255).astype(np.uint8)


# =====================================================
# 六种纸型配方
# =====================================================
def paper_xuan(size, seed=11):
    """宣纸：暖白，云纹明显 + 密软纤维，无斑点"""
    mottle = spectral_noise(size, beta=1.4, seed=seed, aniso_y=1.6)
    grain = fbm_noise(size, octaves=3, base_sigma_px=1.0, seed=seed + 1)
    fibers = fiber_layer(size, seed + 2, n_fibers=900, length_range=(20, 110), dir_spread=0.5)
    t = 0.52 * mottle + 0.18 * grain + 0.30 * fibers
    return norm01(t)


def paper_cream(size, seed=21):
    """米白纤维纸：细底噪 + 可见短纤维"""
    grain = fbm_noise(size, octaves=4, base_sigma_px=1.0, seed=seed)
    mottle = spectral_noise(size, beta=1.1, seed=seed + 1, f0=3.0)
    fibers = fiber_layer(size, seed + 2, n_fibers=700, length_range=(15, 70), dir_spread=0.6)
    t = 0.42 * grain + 0.23 * mottle + 0.35 * fibers
    return norm01(t)


def paper_woodfree(size, seed=31):
    """道林纸（胶版纸）：细腻均匀，极淡云纹，干净"""
    grain = fbm_noise(size, octaves=4, base_sigma_px=1.0, seed=seed)
    mottle = spectral_noise(size, beta=1.0, seed=seed + 1, f0=4.0, aniso_y=1.3)
    t = 0.62 * grain + 0.38 * mottle
    return norm01(t)


def paper_laid(size, seed=41):
    """罗纹纸：传统帘纹，细竖线 + 横链线 + 细底噪"""
    pat = laid_pattern(size, line_spacing=4, line_depth=0.55, chain_spacing=128, chain_depth=0.45)
    grain = fbm_noise(size, octaves=3, base_sigma_px=1.0, seed=seed)
    t = 0.68 * pat + 0.32 * grain
    return norm01(t)


def paper_newsprint(size, seed=51):
    """新闻纸：灰白，粗颗粒 + 明显云纹，廉价纸感"""
    grain = fbm_noise(size, octaves=5, base_sigma_px=1.2, seed=seed)
    mottle = spectral_noise(size, beta=1.6, seed=seed + 1, aniso_y=1.4)
    speck = spots(size, seed + 2, density=0.0015, blur=1.0)
    t = 0.44 * grain + 0.44 * mottle + 0.12 * speck
    return norm01(t)


def paper_oldpage(size, seed=61):
    """旧书页：暖黄，老化污渍 + 淡斑点 + 云纹"""
    mottle = spectral_noise(size, beta=1.2, seed=seed, aniso_y=1.3)
    grain = fbm_noise(size, octaves=4, base_sigma_px=1.0, seed=seed + 1)
    st = stains(size, seed + 2, n_stains=12)
    sp = spots(size, seed + 3, density=0.0022, blur=1.8)
    t = 0.34 * mottle + 0.24 * grain + 0.30 * st + 0.12 * sp
    return norm01(t)


# =====================================================
# 输出
# =====================================================
if __name__ == "__main__":
    configs = [
        # (文件名, 生成函数, 底色 RGB, 纹理强度, seed, App 标签)
        ("paper_xuan",     paper_xuan,     (250, 246, 236), 0.075, 11, "宣纸"),
        ("paper_cream",    paper_cream,    (250, 248, 242), 0.070, 21, "米白纤维"),
        ("paper_woodfree", paper_woodfree, (247, 246, 241), 0.060, 31, "道林纸"),
        ("paper_laid",     paper_laid,     (249, 246, 238), 0.065, 41, "罗纹纸"),
        ("paper_newsprint", paper_newsprint, (240, 238, 231), 0.095, 51, "新闻纸"),
        ("paper_oldpage",  paper_oldpage,  (243, 231, 201), 0.105, 61, "仿旧书页"),
    ]

    os.makedirs(ASSET_DIR, exist_ok=True)
    os.makedirs(PREVIEW_DIR, exist_ok=True)

    previews = []
    for name, func, base, strength, seed, label in configs:
        tex = func(SIZE, seed=seed)
        arr = make_texture(SIZE, base, tex, strength)
        img = Image.fromarray(arr, "RGB")
        out = os.path.join(ASSET_DIR, f"{name}.webp")
        img.save(out, "WEBP", quality=92, method=6)
        print(f"{name}.webp  {os.path.getsize(out) / 1024:.1f} KB  [{label}]")

        # 预览：左半原图，右半叠在模拟漫画上（中灰 50% alpha）方便看效果
        comic = np.full((SIZE, SIZE, 3), 200, dtype=np.uint8)
        comic[:, : SIZE // 4] = 255          # 白底分镜块
        comic[:, SIZE // 4: SIZE // 2] = 60  # 深色块
        overlay = Image.blend(Image.fromarray(comic), img, 0.30)
        row = np.concatenate([np.array(img), np.array(overlay)], axis=1)
        previews.append(row)

    grid = np.concatenate(previews, axis=0)
    Image.fromarray(grid).save(os.path.join(PREVIEW_DIR, "preview_grid.png"))
    print("预览：tools/preview/preview_grid.png")
    print("完成。")
