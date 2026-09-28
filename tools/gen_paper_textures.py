# -*- coding: utf-8 -*-
"""
漫读纸张纹理生成器 v3（纯 numpy + Pillow，无 scipy 依赖）

v3 核心变化：资产语义从"装饰图"改为"反射率图"。
阅读端正片叠底合成（MULTIPLY）直接拿资产当反射率乘页面：out = dst × asset/255，
墨(0)乘任何纸纹仍是纯黑。因此资产必须：
1. 亮度均值归一到 247（整页只变暗 ~3%，无感）；
2. 纸张底色作为色度烘焙（牛皮纸=棕、米白纸=暖白），不再靠叠加 alpha；
3. 调制深度按真纸反射率量级烘焙（细颗粒/纤维/云纹峰值 ±7~12%，σ≈2~3.5%）。
CC0 实拍照片同样归一化：除掉打光晕影（高通），只留编织/纤维纹理，色度保留。

v2 保留：FFT 周期卷积严格无缝、1/f^β 谱合成、方向性纤维、罗纹帘线。
"""
import os
import numpy as np
from PIL import Image, ImageFilter

SIZE = 512
ASSET_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "textures")
PREVIEW_DIR = os.path.join(os.path.dirname(__file__), "preview")

LUM_W = np.array([0.299, 0.587, 0.114])
TARGET_MEAN = 247.0  # 反射率均值：整页变暗 ~3%，观感即"纸白"


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


def fft_grad(field):
    """周期场梯度（FFT 频域求导，严格无缝），返回 (dy, dx)"""
    fy = np.fft.fftfreq(field.shape[0])[:, None] * field.shape[0]
    fx = np.fft.fftfreq(field.shape[1])[None, :] * field.shape[1]
    F = np.fft.fft2(field)
    dy = np.real(np.fft.ifft2(F * (2j * np.pi * fy)))
    dx = np.real(np.fft.ifft2(F * (2j * np.pi * fx)))
    return dy, dx


# ---------- 多尺度底噪 ----------
def fbm_noise(size, octaves=5, base_sigma_px=1.0, seed=0, aniso_y=1.0):
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
    rng = np.random.default_rng(seed)
    white = rng.standard_normal((size, size))
    fy = np.fft.fftfreq(size)[:, None] * size
    fx = np.fft.fftfreq(size)[None, :] * size
    r = np.sqrt(fx ** 2 + (fy * aniso_y) ** 2)
    r[0, 0] = np.inf
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
        theta = rng.normal(0.0, dir_spread)
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


# ---------- 罗纹帘线 ----------
def laid_pattern(size, line_spacing=4, line_depth=0.5, chain_spacing=96, chain_depth=0.5):
    x = np.arange(size)[None, :]
    y = np.arange(size)[:, None]
    lines = 1.0 - line_depth * (0.5 + 0.5 * np.cos(2 * np.pi * x / line_spacing))
    chain = 1.0 - chain_depth * (0.5 + 0.5 * np.cos(2 * np.pi * y / chain_spacing))
    pat = lines * chain
    pat = pat * (0.92 + 0.16 * fbm_noise(size, octaves=3, base_sigma_px=2.0, seed=77))
    return norm01(pat)


# ---------- 老化污渍 / 斑点 ----------
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
        out += amp * np.exp(-(d ** 2) / (2 * r ** 2))
        out += amp * 0.6 * np.exp(-(d ** 2) / (2 * (r * 0.45) ** 2))
    return norm01(out)


def spots(size, seed, density, blur):
    rng = np.random.default_rng(seed)
    s = (rng.random((size, size)) > (1 - density)).astype(np.float64)
    s = fft_blur(s, blur)
    return norm01(s) if s.max() > 0 else s


# ---------- 反射率输出 ----------
def tint_of(base_rgb):
    """底色 → 亮度归一的色度向量（max 通道钳到 1，避免乘法输出大面积削顶）"""
    t = np.array(base_rgb, dtype=np.float64) / 255.0
    t = t / (t @ LUM_W)
    return t / t.max()


def save_reflectance(name, tex01, tint, strength, label):
    """tex01∈[0,1] 调制图 + 强度（峰值调制）→ 亮度均值 247 的反射率 RGB"""
    mod = (tex01 - 0.5) * 2.0 * strength
    refl = 1.0 + mod
    refl /= refl.mean()
    rgb = np.clip(refl[..., None] * tint[None, None, :] * TARGET_MEAN, 0, 255)
    img = Image.fromarray(np.round(rgb).astype(np.uint8), "RGB")
    out = os.path.join(ASSET_DIR, f"{name}.webp")
    img.save(out, "WEBP", quality=92, method=6)
    arr = np.asarray(img, dtype=np.float64)
    lum = arr @ LUM_W
    print(f"{name}.webp  {os.path.getsize(out) / 1024:.1f} KB  [{label}]  "
          f"均值{lum.mean():.0f} σ{lum.std():.1f} 峰峰{lum.max() - lum.min():.0f}")
    return img


def normalize_photo(name, label):
    """CC0 实拍照片 → 反射率图：除掉打光晕影（高通），只留编织/纤维纹理，色度保留"""
    p = os.path.join(ASSET_DIR, f"{name}.webp")
    img = Image.open(p).convert("RGB")
    if img.size != (SIZE, SIZE):
        img = img.resize((SIZE, SIZE), Image.LANCZOS)
    arr = np.asarray(img, dtype=np.float64)
    lum = arr @ LUM_W
    # PIL 高斯（边缘 clamp，不引入 FFT wrap 伪影）；半径 28 去掉 >~60px 的光照结构
    low = np.asarray(Image.fromarray(lum.astype(np.uint8)).filter(ImageFilter.GaussianBlur(28)), dtype=np.float64)
    detail = lum / (low + 1e-6)
    detail = np.clip(detail, 0.70, 1.18)
    detail /= (detail @ LUM_W).mean() if detail.ndim == 3 else detail.mean()
    tint = arr.reshape(-1, 3).mean(axis=0) / 255.0
    tint = tint / (tint @ LUM_W)
    tint = tint / tint.max()
    rgb = np.clip(detail[..., None] * tint[None, None, :] * TARGET_MEAN, 0, 255)
    out_img = Image.fromarray(np.round(rgb).astype(np.uint8), "RGB")
    out_img.save(p, "WEBP", quality=92, method=6)
    o = np.asarray(out_img, dtype=np.float64)
    olum = o @ LUM_W
    print(f"{name}.webp  {os.path.getsize(p) / 1024:.1f} KB  [{label}]  "
          f"均值{olum.mean():.0f} σ{olum.std():.1f}（实拍归一化）")
    return out_img


# =====================================================
# 纸型配方（返回 tex01∈[0,1]，强度统一在 save_reflectance 施加）
# =====================================================
def paper_xuan(size, seed=11):
    """宣纸：暖白，云纹明显 + 密软纤维"""
    mottle = spectral_noise(size, beta=1.4, seed=seed, aniso_y=1.6)
    grain = fbm_noise(size, octaves=3, base_sigma_px=1.0, seed=seed + 1)
    fibers = fiber_layer(size, seed + 2, n_fibers=900, length_range=(20, 110), dir_spread=0.5)
    t = 0.52 * mottle + 0.18 * grain + 0.30 * fibers
    return norm01(t)


def paper_cream(size, seed=21):
    """米白纤维纸：细底噪 + 可见短纤维"""
    grain = fbm_noise(size, octaves=4, base_sigma_px=1.0, seed=seed)
    mottle = spectral_noise(size, beta=1.1, seed=seed + 1, f0=3.0)
    fibers = fiber_layer(size, seed + 2, n_fibers=750, length_range=(15, 70), dir_spread=0.6)
    t = 0.40 * grain + 0.22 * mottle + 0.38 * fibers
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
    t = 0.66 * pat + 0.32 * grain
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


def paper_real(size, seed=71):
    """书卷纸（拟真旗舰）：五层结构还原真书纸的视觉signature——
    1) 成形云纹（β=1.7 幂律谱，主导结构）
    2) 纤维絮聚（纤维密度受云纹软版调制，floc效应）
    3) 微颗粒（软核模糊，填充料+纤维交叠）
    4) 表面起伏定向光照（低频高度场FFT梯度，给纸厚度感）
    5) 冷暖色斑（与云纹弱相关的黄/蓝偏移，模拟荧光增白不均）"""
    rng = np.random.default_rng(seed)
    formation = spectral_noise(size, beta=1.7, seed=seed, f0=2.0, aniso_y=1.35)
    formation_soft = fft_blur(formation, size / 256.0)
    fibers = fiber_layer(size, seed + 1, n_fibers=int(size * 3.2),
                         length_range=(size // 32, size // 13), dir_spread=0.35, thickness_blur=0.55)
    fibers = norm01(fibers * (0.45 + 1.1 * formation_soft))
    grain = norm01(fft_blur(rng.standard_normal((size, size)), 0.6))
    height = spectral_noise(size, beta=2.4, seed=seed + 2, f0=1.0, aniso_y=1.2)
    gy, gx = fft_grad(height)
    shade = -(gx * 0.7 + gy * 0.7)
    shade = shade / (shade.std() + 1e-8)  # 标准化，幅度在外部乘 shade_strength
    warm = spectral_noise(size, beta=1.3, seed=seed + 3, f0=2.5) - 0.5
    lum = 0.52 * formation + 0.17 * grain + 0.31 * fibers
    return norm01(lum), shade, warm


# =====================================================
# 输出
# =====================================================
if __name__ == "__main__":
    os.makedirs(ASSET_DIR, exist_ok=True)
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    previews = []

    # (文件名, 配方, 底色RGB, 峰值调制强度, seed, 标签)
    # 强度从 v2 的 0.06~0.105 上调到 0.08~0.13：乘法全强度下的"纸感"锚定真纸反射率量级
    configs = [
        ("paper_xuan",      paper_xuan,      (250, 246, 236), 0.090, 11, "宣纸"),
        ("paper_cream",     paper_cream,     (250, 248, 242), 0.095, 21, "米白纤维"),
        ("paper_woodfree",  paper_woodfree,  (247, 246, 241), 0.075, 31, "道林纸"),
        ("paper_laid",      paper_laid,      (249, 246, 238), 0.080, 41, "罗纹纸"),
        ("paper_newsprint", paper_newsprint, (240, 238, 231), 0.120, 51, "新闻纸"),
        ("paper_oldpage",   paper_oldpage,   (243, 231, 201), 0.130, 61, "仿旧书页"),
    ]

    def comic_overlay(img, size):
        comic = np.full((size, size, 3), 200, dtype=np.uint8)
        comic[:, : size // 4] = 255
        comic[:, size // 4: size // 2] = 60
        return Image.blend(Image.fromarray(comic), img, 0.30)

    for name, func, base, strength, seed, label in configs:
        tex = func(SIZE, seed=seed)
        img = save_reflectance(name, tex, tint_of(base), strength, label)
        previews.append(np.concatenate([np.array(img), np.array(comic_overlay(img, SIZE))], axis=1))

    # ---- 旗舰拟真书卷纸：1024 五层结构 ----
    FSIZE = 1024
    lum01, shade, warm = paper_real(FSIZE, seed=71)
    lum_strength, shade_strength, warm_strength = 0.100, 0.030, 0.050
    base = np.array([250, 247, 240], dtype=np.float64) / 255.0
    mod = (lum01 - 0.5) * 2.0 * lum_strength + shade * shade_strength
    refl = np.stack([
        1.0 + mod + warm * warm_strength * 0.7,
        1.0 + mod + warm * warm_strength * 0.1,
        1.0 + mod - warm * warm_strength * 0.7,
    ], axis=-1)
    pixel = base[None, None, :] * refl
    pixel *= TARGET_MEAN / ((pixel @ LUM_W).mean() * 255.0) * 255.0
    img = Image.fromarray(np.clip(np.round(pixel), 0, 255).astype(np.uint8), "RGB")
    out = os.path.join(ASSET_DIR, "paper_real.webp")
    img.save(out, "WEBP", quality=92, method=6)
    arr = np.asarray(img, dtype=np.float64)
    al = arr @ LUM_W
    print(f"paper_real.webp  {os.path.getsize(out) / 1024:.1f} KB  [书卷纸]  {FSIZE}px  "
          f"均值{al.mean():.0f} σ{al.std():.1f}")

    previews.append(np.concatenate([
        np.array(img.resize((512, 512), Image.LANCZOS)),
        np.array(comic_overlay(img, FSIZE).resize((512, 512), Image.LANCZOS))], axis=1))

    # ---- CC0 实拍 6 张 → 反射率归一化 ----
    photos = [
        ("paper_cotton", "棉纸（细纹）"), ("paper_cotton2", "棉纸（粗纹）"),
        ("paper_kraft", "牛皮纸"), ("paper_kraft2", "牛皮纸2"),
        ("paper_kraft_box", "牛皮纸盒"), ("paper_sketch", "素描纸"),
    ]
    for name, label in photos:
        pimg = normalize_photo(name, label)
        previews.append(np.concatenate([np.array(pimg), np.array(comic_overlay(pimg, SIZE))], axis=1))

    grid = np.concatenate(previews, axis=0)
    Image.fromarray(grid).save(os.path.join(PREVIEW_DIR, "preview_grid.png"))
    zoom = img.crop((FSIZE // 2 - 192, FSIZE // 2 - 192, FSIZE // 2 + 192, FSIZE // 2 + 192))
    zoom.resize((768, 768), Image.NEAREST).save(os.path.join(PREVIEW_DIR, "preview_real_zoom.png"))
    print("预览：tools/preview/preview_grid.png + preview_real_zoom.png")
    print("完成。")
