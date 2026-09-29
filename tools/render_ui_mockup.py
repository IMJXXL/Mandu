# -*- coding: utf-8 -*-
"""UI 升级方案 mockup：首页 v2 / 阅读器滤镜面板 v2 / 设置页 v2（蓝色主题）"""
import os
from PIL import Image, ImageDraw, ImageFont

OUT = os.path.dirname(os.path.abspath(__file__))
def F(size, bold=False):
    return ImageFont.truetype("C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc", size)

T = dict(bg="#F5F5F5", surf="#FFFFFF", onSurf="#212121", outline="#E0E0E0", dim="#616161",
         prim="#1976D2", primCont="#BBDEFB", onPrimCont="#0D47A1", chip="#EEEEEE", err="#D32F2F")

def rrect(d, box, r, **kw):
    d.rounded_rectangle(box, radius=r, **kw)

# ================= 首页 v2 =================
W, H = 1500, 2700
im = Image.new("RGB", (W, H), T["bg"])
d = ImageDraw.Draw(im)
# 顶栏：搜索框化 + 精简图标
rrect(d, [40, 40, 1020, 130], 45, fill=T["surf"], outline=T["outline"], width=2)
d.text((80, 62), "⌕ 搜索书名、标签…", font=F(40), fill=T["dim"])
for i, ic in enumerate(["序", "筛", "史", "设"]):
    cx = 1120 + i * 110
    d.ellipse([cx-40, 45, cx+40, 125], fill=T["surf"])
    d.text((cx-20, 62), ic, font=F(40), fill=T["onSurf"])
# 书架行
d.text((50, 170), "书架", font=F(30), fill=T["dim"])
x = 50
for s, sel in [("全部", True), ("追番中", False), ("已买未读", False), ("日漫", False)]:
    fs = F(34); w = int(d.textlength(s, font=fs) + 56)
    rrect(d, [x, 215, x+w, 290], 38, fill=T["primCont"] if sel else T["surf"], outline=None if sel else T["outline"], width=2)
    d.text((x+28, 226), s, font=fs, fill=T["onPrimCont"] if sel else T["dim"])
    x += w + 16
rrect(d, [x, 215, x+150, 290], 38, outline=T["outline"], width=2)
d.text((x+30, 226), "更多 >", font=F(34), fill=T["prim"])
# 标签行（升级点：入口+已选+最近，色点）
d.text((50, 330), "标签筛选（新增）", font=F(30), fill=T["dim"])
x = 50
rrect(d, [x, 375, x+220, 450], 38, outline=T["prim"], width=3)
d.text((x+24, 386), "标签 · 2", font=F(34), fill=T["prim"])
x += 236
for name, sel in [("奇幻", True), ("作者:青山刚昌", True), ("热血", False)]:
    fs = F(34); tw = d.textlength(name, font=fs)
    w = int(tw + 120)
    rrect(d, [x, 375, x+w, 450], 38, fill=T["primCont"] if sel else T["surf"], outline=None if sel else T["outline"], width=2)
    d.ellipse([x+22, 396, x+50, 424], fill="#AD1457" if name != "热血" else "#2E7D32")
    d.text((x+60, 386), name, font=fs, fill=T["onPrimCont"] if sel else T["dim"])
    if sel:
        d.text((x+w-56, 388), "×", font=F(38), fill=T["onPrimCont"])
    x += w + 16
rrect(d, [x, 375, x+140, 450], 38, outline=T["outline"], width=2)
d.text((x+28, 386), "清除", font=F(34), fill=T["dim"])
# 分区标题 + 排序快捷
d.text((50, 505), "最近更新", font=F(38, True), fill=T["onSurf"])
d.text((1050, 512), "最近阅读 ▾   列数 ▦", font=F(30), fill=T["prim"])
# 书卡网格：封面+进度条+系列角标（升级点）
covers = [("#7E57C2", "#4527A0", "海贼王", "82", True), ("#EF6C00", "#BF360C", "进击的巨人", "45", False),
          ("#2E7D32", "#1B5E20", "静夜思", "100", False), ("#1565C0", "#0D47A1", "宇宙兄弟", "12", True),
          ("#AD1457", "#880E4F", "辉夜大小姐", "66", False), ("#546E7A", "#263238", "漂流少年", "3", True)]
cw, ch, gap = 440, 600, 36
for i, (c1, c2, title, prog, isSeries) in enumerate(covers):
    cx = 50 + (i % 3) * (cw + gap)
    cy = 580 + (i // 3) * (ch + 170)
    for k in range(ch):
        kk = k / ch
        rgb = tuple(int(int(c1[j:j+2], 16) * (1-kk) + int(c2[j:j+2], 16) * kk) for j in (1, 3, 5))
        d.line([cx, cy+k, cx+cw, cy+k], fill=rgb)
    rrect(d, [cx, cy, cx+cw, cy+ch], 20, outline=T["outline"], width=2)
    if isSeries:  # 系列角标（升级点）
        rrect(d, [cx+cw-150, cy+20, cx+cw-24, cy+78], 20, fill="#00000088")
        d.text((cx+cw-136, cy+30), "全 24 卷", font=F(28), fill="#FFFFFF")
    rrect(d, [cx, cy+ch-64, cx+cw, cy+ch], 0, fill="#00000099")
    d.text((cx+16, cy+ch-56), f"第 {int(prog)} 话", font=F(28), fill="#FFFFFF")
    # 进度条（升级点：封面底部细进度条）
    rrect(d, [cx, cy+ch+16, cx+cw, cy+ch+26], 5, fill=T["outline"])
    rrect(d, [cx, cy+ch+16, cx+int(cw*int(prog)/100), cy+ch+26], 5, fill=T["prim"])
    d.text((cx+4, cy+ch+40), title, font=F(36, True), fill=T["onSurf"])
# FAB
d.ellipse([W-220, H-260, W-80, H-120], fill=T["prim"])
d.text((W-172, H-215), "▶", font=F(52), fill="#FFFFFF")
d.line([0, 560, W, 560], fill=T["outline"], width=1)
im.save(os.path.join(OUT, "mockup_home_v2.png"))

# ================= 阅读器滤镜面板 v2 =================
W2, H2 = 1500, 1600
im2 = Image.new("RGB", (W2, H2), "#888888")
d2 = ImageDraw.Draw(im2)
d2.text((40, 20), "阅读器底栏「滤镜」面板 v2（Segmented 分区 + 滑条带数值 + 纹理缩略图）", font=F(36, True), fill="#FFFFFF")
sy = 90
rrect(d2, [0, sy, W2, H2], 40, fill=T["surf"])
rrect(d2, [W2//2-60, sy+22, W2//2+60, sy+34], 6, fill=T["outline"])
# 分区 tab（升级点：Segmented）
tabs = ["色彩", "纸张纹理", "翻页", "显示"]
tx = 60
for i, tname in enumerate(tabs):
    w = 320
    sel = i == 1
    rrect(d2, [tx, sy+70, tx+w, sy+150], 0, fill=T["primCont"] if sel else T["surf"], outline=T["outline"], width=2)
    d2.text((tx+90, sy+86), tname, font=F(34), fill=T["onPrimCont"] if sel else T["dim"])
    tx += w
# 纹理缩略图行（升级点：直接看图选纹理）
d2.text((60, sy+190), "纸型（点缩略图即选）", font=F(28), fill=T["dim"])
texs = ["书卷", "宣纸", "道林", "罗纹", "牛皮", "棉纸"]
for i, tname in enumerate(texs):
    cx = 60 + i * 236
    sel = i == 0
    rrect(d2, [cx, sy+240, cx+200, sy+390], 16, fill="#F3EDE2" if i < 4 else "#E8D9B8", outline=T["prim"] if sel else T["outline"], width=4 if sel else 2)
    d2.text((cx+56, sy+400), tname, font=F(30), fill=T["onSurf"])
# 滑条带数值（升级点：统一滑条样式）
for label, val, y in [("透明度", "15%", sy+480), ("浓度", "80%", sy+620)]:
    d2.text((60, y), label, font=F(32), fill=T["onSurf"])
    d2.text((1230, y), val, font=F(32, True), fill=T["prim"])
    rrect(d2, [60, y+56, W2-60, y+70], 7, fill=T["chip"])
    rrect(d2, [60, y+56, 500, y+70], 7, fill=T["prim"])
    d2.ellipse([480, y+42, 524, y+84], fill=T["prim"], outline="#FFFFFF", width=3)
# 混合模式选择（升级点：正片叠底）
d2.text((60, sy+790), "合成方式", font=F(32), fill=T["onSurf"])
for i, (m, sel) in enumerate([("正片叠底（像真书）", True), ("普通叠加", False)]):
    w = 640
    mx = 60 + i * 680
    rrect(d2, [mx, sy+840, mx+w, sy+916], 20, fill=T["primCont"] if sel else T["surf"], outline=T["outline"], width=2)
    d2.text((mx+30, sy+852), m, font=F(32), fill=T["onPrimCont"] if sel else T["dim"])
# AGSL 支持提示
d2.text((60, sy+950), "本机 Android 13+：启用 AGSL 程序化纸纹（分辨率无关、无接缝）", font=F(26), fill=T["dim"])
im2.save(os.path.join(OUT, "mockup_reader_panel_v2.png"))

# ================= 设置页 v2 =================
im3 = Image.new("RGB", (W, 2500), T["bg"])
d3 = ImageDraw.Draw(im3)
d3.text((50, 40), "设置页 v2（Card 分区统一 + 顶部搜索直达）", font=F(40, True), fill=T["onSurf"])
# 搜索直达（升级点）
rrect(d3, [40, 130, W-40, 220], 45, fill=T["surf"], outline=T["outline"], width=2)
d3.text((80, 152), "⌕ 搜索设置项…（如「伽马」「SMB」直达）", font=F(36), fill=T["dim"])
cards = [
    ("阅读", "翻页模式 · 滤镜 · 纸张纹理 · 墨水屏 · 超分", Icons := "📖"),
    ("书架与标签", "自动打标签 · 标签管理 · 书架收纳 · 隐藏", "🏷"),
    ("下载与远程", "SMB 服务器 · WebDAV · Komga · OPDS", "🌐"),
    ("备份与恢复", "WebDAV 远程备份 · 本地导出", "💾"),
    ("关于", "版本 1.35 · 检查更新 · 赞赏", "ℹ"),
]
y = 270
for title, sub, ic in cards:
    rrect(d3, [40, y, W-40, y+210], 28, fill=T["surf"], outline=T["outline"], width=2)
    d3.ellipse([80, y+55, 160, y+135], fill=T["primCont"])
    d3.text((100, y+68), ic, font=F(40))
    d3.text((200, y+55), title, font=F(40, True), fill=T["onSurf"])
    d3.text((200, y+120), sub, font=F(28), fill=T["dim"])
    d3.text((W-120, y+80), "›", font=F(56), fill=T["dim"])
    y += 240
d3.text((50, y+20), "升级点：全部设置收敛为 Card 分区 + 搜索直达 + 子页返回保留滚动位置", font=F(30), fill=T["dim"])
im3.save(os.path.join(OUT, "mockup_settings_v2.png"))
print("3 mockups ->", OUT)
