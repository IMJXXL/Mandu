# -*- coding: utf-8 -*-
"""
翻页纸声合成器 v2：三段结构模拟真实落页，替代 v1 的 90ms 单段"嗖"声。

真纸翻页的声学构成（对若干实翻录音的听感拆解）：
A. 滑擦（0~150ms）：纸面掠过空气/邻页的宽带摩擦，频带中心随加速下扫 5k→1.2k，
   带随机调幅（纤维束断续擦过）——"沙沙"主体
B. 碎裂（30~230ms）：十几个微瞬态，纤维粘合面剥离的"咔嚓"点，随机散布
C. 落纸（190~300ms）：纸拍落到书面的低频闷响 + 短促余擦——"啪嗒"落地感
输出：app/src/main/assets/sounds/page_flip.wav（44.1k 单声道 16bit，约 0.32s）
"""
import os
import wave
import numpy as np

SR = 44100
T = 0.32
N = int(SR * T)
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "sounds", "page_flip.wav")


def bandpass(x, lo, hi):
    """FFT 软边带通（误差函数边缘，无铃振）"""
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1.0 / SR)
    g = 1.0 / (1.0 + np.exp(-(f - lo) / (lo * 0.35 + 1.0)))
    g *= 1.0 / (1.0 + np.exp((f - hi) / (hi * 0.35 + 1.0)))
    return np.fft.irfft(X * g, len(x))


def main():
    rng = np.random.default_rng(7)
    t = np.arange(N) / SR
    out = np.zeros(N)

    # ---- A. 滑擦：两段带通交叉淡化实现频带下扫 ----
    noise = rng.standard_normal(N)
    hi_band = bandpass(noise, 2500, 9500)
    lo_band = bandpass(noise, 700, 3200)
    w = np.clip(t / 0.15, 0.0, 1.0) ** 1.2
    rustle = hi_band * (1.0 - w) + lo_band * w
    # 随机调幅：低频噪声整流成包络起伏（纤维束断续接触）
    am = np.abs(bandpass(rng.standard_normal(N), 25, 130))
    am = am / (am.mean() + 1e-9)
    env_a = np.exp(-t / 0.075) * (1.0 - np.exp(-t / 0.008)) * (0.55 + 0.7 * am)
    out += rustle * env_a * 0.85

    # ---- B. 碎裂微瞬态 ----
    for _ in range(16):
        start = rng.uniform(0.03, 0.23)
        i0 = int(start * SR)
        dur = int(SR * rng.uniform(0.004, 0.012))
        if i0 + dur >= N:
            continue
        tt = np.arange(dur) / SR
        burst = rng.standard_normal(dur) * np.exp(-tt / rng.uniform(0.002, 0.005))
        out[i0:i0 + dur] += burst * rng.uniform(0.12, 0.42)

    # ---- C. 落纸闷响 + 余擦 ----
    i0 = int(0.20 * SR)
    dur = int(0.11 * SR)
    tt = np.arange(dur) / SR
    thump = np.sin(2 * np.pi * 165 * tt) * np.exp(-tt / 0.024) * 0.55
    thump += bandpass(rng.standard_normal(dur), 90, 700) * np.exp(-tt / 0.020) * 0.45
    out[i0:i0 + dur] += thump

    # 尾部收音，防爆音
    fade = np.ones(N)
    f0 = int(0.26 * SR)
    fade[f0:] = np.linspace(1.0, 0.0, N - f0)
    out *= fade
    out /= np.abs(out).max() + 1e-9
    out *= 0.5

    pcm = (out * 32767).astype(np.int16)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with wave.open(OUT, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    print(f"{OUT}  {os.path.getsize(OUT) / 1024:.1f} KB  {T}s {SR}Hz")


if __name__ == "__main__":
    main()
