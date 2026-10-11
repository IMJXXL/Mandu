# -*- coding: utf-8 -*-
"""
3D 波浪重设计 mockup 渲染器（2026-10-11 重设计 P4 审美门）
渲染 3 个方案 × 3 个相位帧，输出对比大图供用户拍板，再写 Compose 实现。

方案：
  A 克制版——网格安静，波是"光"不是"动"：卡片不动或微动(≤6dp)，波峰带卡片
    提亮+微放大+阴影加深，其余卡片全不透明
  B 透视版——共享消失点：整面卡片墙绕水平轴微倾(≤6°)+按行 Z 深度缩放，
    波峰带卡片沿 Z 抬起（近大远小），位移收敛
  C 现状对照——当前 Wave3DMode：全部卡片大幅浮动(±90dp×7.5%)、半透明(alpha 0.45)、
    独立歪斜，波包扫过时放大+金色光晕

用法：python render_wave_mockup.py  输出到桌面
"""
from PIL import Image, ImageDraw, ImageFilter
from PIL import ImageFont
import math, os

W, H = 300, 620            # 单屏面板尺寸
COLS = 3                   # 每屏 3 列卡片
CARD_AR = 0.72
GAP = 10
try:
    FONT = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 20)
    FONT_S = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 13)
except Exception:
    FONT = ImageFont.load_default()
    FONT_S = FONT

def make_cover(i, w, h):
    """假封面：暖色系渐变+大字页码，模拟漫画页观感"""
    hues = [(214,106,80),(106,134,160),(160,140,106),(134,160,120),(160,106,134),(120,110,140)]
    c = hues[i % len(hues)]
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / w + y / h) / 2
            px[x, y] = (int(c[0]*(0.55+0.45*t)), int(c[1]*(0.55+0.45*t)), int(c[2]*(0.55+0.45*t)))
    d = ImageDraw.Draw(img)
    d.text((w//2-14, h//2-16), str(i), font=FONT, fill=(255,255,255))
    return img

def rounded(img, rad=14):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0,0,img.size[0]-1,img.size[1]-1], rad, fill=255)
    out = Image.new("RGBA", img.size, (0,0,0,0))
    out.paste(img, (0,0), mask)
    return out

def draw_panel(variant, phase, seed0=0):
    """variant: A/B/C, phase: 0~1 波包位置。返回 (Image, 描述)"""
    screen = Image.new("RGB", (W, H), (30, 26, 24))
    d = ImageDraw.Draw(screen, "RGBA")
    cw = (W - GAP*(COLS+1)) / COLS
    ch = cw / CARD_AR
    rows = 6
    top = 40
    wave_center = phase * (rows + 3) - 1.5   # 波包中心（行坐标，可越界）

    for r in range(rows):
        for c in range(COLS):
            i = seed0 + r*COLS + c
            cover = make_cover(i, int(cw), int(ch))
            card = rounded(cover).convert("RGBA")
            x = GAP + c*(cw+GAP)
            y_base = top + r*(ch+GAP)

            if variant == "A":
                # 克制版：卡片不动。波峰带=亮度+阴影+2%放大；其他=常态
                dist = abs(r - wave_center)
                env = math.exp(-0.5*(dist/1.4)**2)           # 波包
                lift = 0 if dist < 3 else 0
                y = y_base - lift
                s = 1 + env*0.02
                bright = env*0.18
                shadow_a = 60 + int(env*100)
            elif variant == "B":
                # 透视版：共享倾角+行深度缩放+波峰沿Z抬起
                tilt = math.sin(phase*2*math.pi) * 0.06       # 整面统一微倾
                row_depth = (r - wave_center)                 # 波峰最近
                persp = 1 + max(-0.06, min(0.06, -row_depth*0.012))  # 近大远小
                env = math.exp(-0.5*(abs(r - wave_center)/1.4)**2)
                y = y_base - env*8                            # 峰带仅抬 8px
                s = persp*(1 + env*0.02)
                bright = env*0.15
                shadow_a = 60 + int(env*90)
                if tilt != 0 and r != rows-1:
                    y += tilt * (r - rows/2) * 14
            else:
                # 现状 C：全部浮动+半透明+波包金光
                idle = 0.075*math.sin(2*math.pi*(0.16*r - 0.15*c) + phase*2*math.pi*4) \
                     + 0.027*math.sin(2*math.pi*(0.1*r + 0.1*c) + phase*2*math.pi*2.4)
                env = math.exp(-0.5*((r + (c-1)*0.4 - wave_center)/2.2)**2)
                y = y_base + idle*ch*1.2
                s = 1 + env*0.14
                bright = 0
                shadow_a = 40
                alpha = int(255*(0.45 + 0.55*min(1, env*1.6)))
                card.putalpha(alpha)
                if env > 0.25:
                    gold = Image.new("RGBA", card.size, (197,161,107,int(env*38)))
                    card = Image.alpha_composite(card, gold)

            w2, h2 = int(cw*s), int(ch*s)
            card = card.resize((max(1,w2), max(1,h2)), Image.LANCZOS)
            # 阴影
            sh = Image.new("RGBA", (w2+30, h2+30), (0,0,0,0))
            ImageDraw.Draw(sh).rounded_rectangle([15, 15+6, 15+w2, 15+h2+6], 14, fill=(0,0,0,shadow_a))
            sh = sh.filter(ImageFilter.GaussianBlur(7))
            screen.paste(sh, (int(x-15), int(y-15+6)), sh)
            screen.paste(card.convert("RGB"), (int(x), int(y)))

            if variant == "A" and env > 0.5 or variant == "B" and env > 0.5:
                d2 = ImageDraw.Draw(screen, "RGBA")
                d2.text((x+6, y+h2-20), f"{i}", font=FONT_S, fill=(255,240,220))

    title = {"A": "方案A 克制版：网格安静，波=光带扫过（提亮+微放大）",
             "B": "方案B 透视版：共享消失点+行深度，波峰沿Z抬起8px",
             "C": "方案C 现状对照：全屏浮动±幅度大+半透明+歪斜"}[variant]
    ImageDraw.Draw(screen).rectangle([0,0,W,32], fill=(20,17,15))
    ImageDraw.Draw(screen).text((8, 5), f"{variant}  {title[:14]}", font=FONT_S, fill=(230,220,205))
    return screen

def main():
    cols = []  # 3 方案 × 3 相位
    phases = [0.0, 0.35, 0.7]
    panels = []
    for v in ["A", "B", "C"]:
        for p in phases:
            panels.append(draw_panel(v, p))
    pad = 24
    label_h = 40
    grid = Image.new("RGB", (W*3 + pad*4, (H+label_h)*3 + pad*4), (16,14,13))
    dg = ImageDraw.Draw(grid)
    labels = ["相位 0%", "相位 35%", "相位 70%"]
    for idx, (v, p) in enumerate([(v,p) for v in ["A","B","C"] for p in phases]):
        r, c = divmod(idx, 3)
        x = pad + c*(W+pad)
        y = pad + r*(H+label_h+pad)
        grid.paste(panels[idx], (x, y+label_h))
        name = {"A":"方案A 克制版","B":"方案B 透视版","C":"方案C 现状对照"}[v]
        dg.text((x+4, y+8), f"{name}  ·  {labels[c]}", font=FONT, fill=(240,225,200))
    out = r"C:\Users\dhgj\Desktop\波浪重设计mockup.png"
    grid.save(out)
    print("OK", out, grid.size)

if __name__ == "__main__":
    main()
