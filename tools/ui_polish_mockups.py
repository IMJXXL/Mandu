# -*- coding: utf-8 -*-
"""漫读 UI 精修方案对比图（8 张，纯 mockup 渲染，非设备截图）v2"""
import os
from PIL import Image, ImageDraw, ImageFont

OUT = r"C:\Users\dhgj\Desktop\漫读UI精修方案\对比图"
os.makedirs(OUT, exist_ok=True)
S = 2

def F(size, bold=False):
    return ImageFont.truetype("C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc", max(8, int(size * S)))

T = dict(bg="#F3F4F6", surf="#FFFFFF", onSurf="#1B1B1F", outline="#E2E2E6", dim="#6B6B74",
         prim="#1976D2", primCont="#BBDEFB", onPrimCont="#0D47A1", chip="#F0F0F2",
         err="#D32F2F", ok="#1B8A3F", grp="#8A8A93")
PANEL_W, PAD, GAP = 800, 22, 40

def new_canvas(title):
    H = 2400
    im = Image.new("RGB", ((PANEL_W * 2 + GAP * 3) * S, H * S), "#E9EAEE")
    d = ImageDraw.Draw(im)
    d.text((GAP * S, 14 * S), title, font=F(30, True), fill="#33333A")
    return im, d

def panel(d, x0, tag, tagcolor):
    top = 70
    d.text((x0 * S, top * S), tag, font=F(24, True), fill=tagcolor)
    d.rounded_rectangle([x0 * S, (top + 42) * S, (x0 + PANEL_W) * S, 2380 * S], 24 * S, fill=T["bg"])
    return top + 62

def rrect(d, box, r, **kw):
    d.rounded_rectangle([v * S for v in box], r * S, **kw)

def txt(d, xy, s, size, fill=None, bold=False, anchor="la"):
    d.text((xy[0] * S, xy[1] * S), s, font=F(size, bold), fill=fill or T["onSurf"], anchor=anchor)

def tw(d, s, size, bold=False):
    return d.textlength(s, font=F(size, bold)) / S

def icon_badge(d, x, y, ch, size=34):
    rrect(d, [x, y, x + size, y + size], 10, fill="#E8F0FB")
    txt(d, (x + size / 2, y + size / 2 + 1), ch, 14, T["prim"], True, "mm")

def card(d, x, y, w, title, sub=None, icon=None, trailing=None, mark=None):
    h = 64 if sub else 54
    rrect(d, [x, y, x + w, y + h], 12, fill=T["surf"], outline=T["outline"], width=1)
    cx = x + 16
    if icon:
        icon_badge(d, cx, y + (h - 34) / 2, icon)
        cx += 44
    if sub:
        txt(d, (cx, y + 16), title, 14.5, T["onSurf"], True, "lm")
        txt(d, (cx, y + h - 15), sub, 10.5, T["dim"], anchor="lm")
    else:
        txt(d, (cx, y + h / 2), title, 14.5, T["onSurf"], True, "lm")
    if trailing == "chev":
        txt(d, (x + w - 18, y + h / 2), "›", 17, T["dim"], "rm")
    if mark:
        callout(d, x + w + 14, y + h / 2, mark)
    return y + h + 8

def group_label(d, x, y, s):
    txt(d, (x + 4, y), s, 11.5, T["grp"], True)
    return y + 26

def callout(d, x, y, n):
    d.ellipse([(x - 12) * S, (y - 12) * S, (x + 12) * S, (y + 12) * S], fill=T["err"])
    txt(d, (x, y), str(n), 12.5, "#FFFFFF", True, "mm")

def note_block(d, x, y, items, w=PANEL_W - PAD * 2):
    rrect(d, [x, y, x + w, y + 26 * len(items) + 20], 10, fill="#FDF3F2", outline="#F0C9C6", width=1)
    for i, (n, s) in enumerate(items):
        callout(d, x + 24, y + 24 + 26 * i, n)
        txt(d, (x + 44, y + 24 + 26 * i), s, 11.5, "#8C2B24", anchor="lm")
    return y + 26 * len(items) + 34

def dialog(d, x, y, w, title, lines, buttons, danger_idx=None, mark_btn=None):  # mark_btn=(按钮下标, 编号)
    h = 92 + 27 * len(lines) + 54
    rrect(d, [x, y, x + w, y + h], 16, fill=T["surf"])
    d.rounded_rectangle([x * S, y * S, (x + w) * S, (y + h) * S], 16 * S, outline="#D8D8DC", width=1 * S)
    txt(d, (x + 24, y + 18), title, 16, T["onSurf"], True)
    for i, l in enumerate(lines):
        fill = T["dim"] if i > 0 else "#4A4A52"
        txt(d, (x + 24, y + 52 + 26 * i), l, 12.5, fill)
    bx = x + w - 20
    for bi in range(len(buttons) - 1, -1, -1):
        label = buttons[bi][0]
        danger = (bi == danger_idx)
        bw = tw(d, label, 13, True) + 36
        bx -= bw
        col = T["err"] if danger else T["prim"]
        rrect(d, [bx, y + h - 48, bx + bw, y + h - 12], 18, fill="#FCEBEA" if danger else "#E8F0FB")
        txt(d, (bx + bw / 2, y + h - 30), label, 13, col, True, "mm")
        if mark_btn and mark_btn[0] == bi:
            callout(d, bx + bw / 2, y + h - 64, mark_btn[1])
        bx -= 10
    return y + h

def save(im, name, content_y):
    im = im.resize((im.width // S, im.height // S), Image.LANCZOS)
    im.crop((0, 0, im.width, min(2390, content_y + 30))).save(os.path.join(OUT, name))
    print("saved", name)

# ---------- 01 设置首页 ----------
im, d = new_canvas("01 · 设置首页 —— 分组重排 + 卡片「当前值」摘要")
x1 = GAP; x2 = GAP + PANEL_W + GAP; W = PANEL_W
y = panel(d, x1, "BEFORE · 现状", T["err"])
y = card(d, x1 + PAD, y, W - PAD * 2 - 30, "本地漫画管理", "添加文件夹、扫描刷新、显示隐藏", "文", "chev")
y = card(d, x1 + PAD, y, W - PAD * 2 - 30, "书架管理", "新建、重命名、拖动排序书架", "架", "chev")
y += 12; y = group_label(d, x1 + PAD, y, "显示")
y = card(d, x1 + PAD, y, W - PAD * 2 - 30, "全局隐藏", "开启后首页不显示任何漫画", "隐")
y = card(d, x1 + PAD, y, W - PAD * 2 - 30, "请作者喝杯咖啡", "随心就好，不看也没关系", "赏", "chev")
y += 12; y = group_label(d, x1 + PAD, y, "外观")
y = card(d, x1 + PAD, y, W - PAD * 2 - 30, "外观设置", "主题颜色、暗色模式、自定义主色", "色", "chev")
y += 12; y = group_label(d, x1 + PAD, y, "阅读")
y = card(d, x1 + PAD, y, W - PAD * 2 - 30, "阅读设置", "背景颜色、墨水屏、PDF质量、自动翻页等", "读", "chev")
y = card(d, x1 + PAD, y, W - PAD * 2 - 30, "增强队列", "批量离线增强进度、已完成产物与缓存管理", "强", "chev")
y = card(d, x1 + PAD, y, W - PAD * 2 - 30, "SMB 服务器管理", "配置局域网共享并扫描阅读漫画", "S", "chev")
y = card(d, x1 + PAD, y, W - PAD * 2 - 30, "WebDAV 服务器管理", "配置 WebDAV 网盘并在线阅读远端漫画", "W", "chev")
y = card(d, x1 + PAD, y, W - PAD * 2 - 30, "Komga 服务器管理", "配置 Komga 自建漫画服务器并在线阅读", "K", "chev", mark=1)
y = card(d, x1 + PAD, y, W - PAD * 2 - 30, "OPDS 服务器管理 Beta", "配置 OPDS 漫画服务器（Kavita/LANraragi/Calibre 等）", "O", "chev")
y += 14
y = note_block(d, x1 + PAD, y, [(1, "四张服务器卡混在「阅读」组——它们是书源，不是阅读行为")])
yb = y

y = panel(d, x2, "AFTER · 分组归位 + 卡片即状态", T["ok"])
y = card(d, x2 + PAD, y, W - PAD * 2, "本地漫画管理", "添加文件夹、扫描刷新、显示隐藏", "文", "chev")
y = card(d, x2 + PAD, y, W - PAD * 2, "书架管理", "新建、重命名、拖动排序书架", "架", "chev")
y += 12; y = group_label(d, x2 + PAD, y, "显示")
y = card(d, x2 + PAD, y, W - PAD * 2, "全局隐藏", "开启后首页不显示任何漫画", "隐")
y = card(d, x2 + PAD, y, W - PAD * 2, "请作者喝杯咖啡", "随心就好，不看也没关系", "赏", "chev")
y += 12; y = group_label(d, x2 + PAD, y, "外观")
y = card(d, x2 + PAD, y, W - PAD * 2, "外观设置", "当前：青苹果 · 暗色", "色", "chev", mark=3)
y += 12; y = group_label(d, x2 + PAD, y, "阅读")
y = card(d, x2 + PAD, y, W - PAD * 2, "阅读设置", "当前：右开翻页 · 墨水屏优化开", "读", "chev", mark=3)
y = card(d, x2 + PAD, y, W - PAD * 2, "增强队列", "批量离线增强进度、已完成产物与缓存管理", "强", "chev")
y += 12; y = group_label(d, x2 + PAD, y, "服务器与源")
y = card(d, x2 + PAD, y, W - PAD * 2, "SMB", "局域网共享 · 已配置 2 台", "S", "chev")
y = card(d, x2 + PAD, y, W - PAD * 2, "WebDAV", "网盘在线阅读 · 未配置", "W", "chev")
y = card(d, x2 + PAD, y, W - PAD * 2, "Komga", "自建漫画服务器 · 未配置", "K", "chev")
y = card(d, x2 + PAD, y, W - PAD * 2, "OPDS Beta", "Kavita / LANraragi / Calibre 等", "O", "chev")
y += 12
y = card(d, x2 + PAD, y, W - PAD * 2, "关于", "版本、开源许可、导出诊断日志", "i", "chev")
y += 10
txt(d, (x2 + PAD, y + 8), "③ 卡片副标题显示当前值 ｜ 捐赠卡位置按作者意图保持不动", 11.5, T["ok"], True)
save(im, "01_设置首页_分组与摘要.png", max(yb, y + 40))

# ---------- 02 危险对话框统一模板 ----------
im, d = new_canvas("02 · 危险确认对话框 —— 动词化按钮 + 三行式正文")
x1 = GAP; x2 = GAP + PANEL_W + GAP
y = panel(d, x1, "BEFORE · 现状", T["err"])
yy = dialog(d, x1 + PAD, y + 16, W - PAD * 2, "删除书架", ["删除书架「追番中」？书架内的漫画不受影响。"], [("取消", 0), ("确定", 0)])
yy = dialog(d, x1 + PAD, yy + 18, W - PAD * 2, "清空阅读历史", ["将清空所有阅读记录，此操作无法撤销。"], [("取消", 0), ("确定", 0)], mark_btn=(1, 4))
txt(d, (x1 + PAD, yy + 12), "同类还有：删除文件夹 / 删除服务器 / 清空队列 / 删除标签…", 11.5, T["dim"])
txt(d, (x1 + PAD, yy + 34), "全应用 13 处「确定」确认按钮（lint 实测）", 11.5, T["dim"])
yy += 70
yb = note_block(d, x1 + PAD, yy, [(4, "危险操作按钮写「确定」——零信息，且非红色不警觉")])

y = panel(d, x2, "AFTER · 三行式模板", T["ok"])
yy = dialog(d, x2 + PAD, y + 16, W - PAD * 2, "删除书架？", ["书架内的漫画不受影响，仅移除该分组。", "此操作不可撤销。"], [("取消", 0), ("删除", 1)], danger_idx=1, mark_btn=(1, 5))
yy = dialog(d, x2 + PAD, yy + 18, W - PAD * 2, "清空阅读历史？", ["共 128 条记录将被移除。", "此操作不可撤销。"], [("取消", 0), ("清空", 1)], danger_idx=1, mark_btn=(1, 6))
txt(d, (x2 + PAD, yy + 12), "模板：标题=动词+对象？ 正文=后果/数量 + 保留项或不可逆；", 11.5, T["dim"])
txt(d, (x2 + PAD, yy + 34), "确认按钮=动词本身 + 错误红底色", 11.5, T["dim"])
yy += 70
note_block(d, x2 + PAD, yy, [(5, "按钮动词化：删除 / 清空 / 移除，一眼知道按下发生什么"),
                             (6, "危险按钮错误红 + 浅红底，与普通「取消」拉开层级")])
save(im, "02_危险对话框_统一模板.png", max(yb, yy + 60))

# ---------- 03 空状态统一模板 ----------
im, d = new_canvas("03 · 空状态 —— 「还没有…」统一口吻 + 内嵌动作按钮")
x1 = GAP; x2 = GAP + PANEL_W + GAP
y = panel(d, x1, "BEFORE · 现状（两种口径、无动作）", T["err"])
yy = y + 12
for s in ["还没有标签。导入带 ComicInfo.xml 的书，或点右上角新建", "暂无服务器，点击右上角 + 添加",
          "还没有阅读记录", "暂无书签（阅读中点底部「书签」按钮添加当前页）", "暂无书库", "还没有漫画"]:
    yy = card(d, x1 + PAD, yy, W - PAD * 2 - 30, s, icon=None)
yy += 8
yb = note_block(d, x1 + PAD, yy, [(7, "「还没有」与「暂无」混用（lint 实测 10 处）"),
                             (7, "全部纯文字：没有图标层级，没有可点的下一步")])

y = panel(d, x2, "AFTER · 模板：图标 + 现状 + 指引 + 动作", T["ok"])
def empty_state(d, x, y, w, ch, main, guide, btn, mark=None):
    rrect(d, [x, y, x + w, y + 148], 14, fill=T["surf"], outline=T["outline"], width=1)
    d.ellipse([(x + w / 2 - 22) * S, (y + 16) * S, (x + w / 2 + 22) * S, (y + 60) * S], fill=T["chip"])
    txt(d, (x + w / 2, y + 38), ch, 18, T["dim"], False, "mm")
    txt(d, (x + w / 2, y + 74), main, 14, T["onSurf"], True, "mm")
    txt(d, (x + w / 2, y + 98), guide, 11, T["dim"], False, "mm")
    bw = tw(d, btn, 12.5, True) + 40
    rrect(d, [x + w / 2 - bw / 2, y + 114, x + w / 2 + bw / 2, y + 140], 13, fill=T["prim"])
    txt(d, (x + w / 2, y + 127), btn, 12.5, "#FFFFFF", True, "mm")
    if mark:
        callout(d, x + w + 14, y + 74, mark)
    return y + 160
yy = empty_state(d, x2 + PAD, y + 14, W - PAD * 2 - 30, "签", "还没有标签", "导入带 ComicInfo.xml 的书可自动获得", "新建标签", mark=8)
yy = empty_state(d, x2 + PAD, yy + 10, W - PAD * 2 - 30, "S", "还没有服务器", "支持 SMB / WebDAV / Komga / OPDS", "添加服务器")
yy += 8
txt(d, (x2 + PAD, yy + 6), "口吻统一「还没有…」；按钮直达该页面本来的主操作", 11.5, T["ok"], True)
save(im, "03_空状态_统一模板.png", max(yb, yy + 40))

# ---------- 04 标签管理头部 ----------
im, d = new_canvas("04 · 标签管理头部 —— 排序从「盲点循环」改为可见菜单")
x1 = GAP; x2 = GAP + PANEL_W + GAP
y = panel(d, x1, "BEFORE · 现状", T["err"])
rrect(d, [x1 + PAD, y + 10, x1 + PANEL_W - PAD - 96, y + 58], 12, fill=T["surf"], outline=T["outline"], width=1)
d.ellipse([(x1 + PAD + 16) * S, (y + 26) * S, (x1 + PAD + 34) * S, (y + 44) * S], outline=T["dim"], width=S)
txt(d, (x1 + PAD + 46, y + 34), "搜索标签...", 13, T["dim"], anchor="lm")
rrect(d, [x1 + PANEL_W - PAD - 88, y + 10, x1 + PANEL_W - PAD, y + 58], 12, fill=T["surf"], outline=T["outline"], width=1)
txt(d, (x1 + PANEL_W - PAD - 44, y + 34), "按数量", 12.5, T["prim"], True, "mm")
callout(d, x1 + PANEL_W - PAD - 100, y + 78, 9)
txt(d, (x1 + PAD, y + 92), "点一下循环到下一种排序——要点三次才知道共有哪些", 11.5, T["dim"])
txt(d, (x1 + PAD, y + 114), "且从按钮文字上分不清它是「当前态」还是「动作」", 11.5, T["dim"])

y = panel(d, x2, "AFTER · 「排序 ▾」入口 + 三态菜单带勾选", T["ok"])
rrect(d, [x2 + PAD, y + 10, x2 + PANEL_W - PAD - 96, y + 58], 12, fill=T["surf"], outline=T["outline"], width=1)
d.ellipse([(x2 + PAD + 16) * S, (y + 26) * S, (x2 + PAD + 34) * S, (y + 44) * S], outline=T["dim"], width=S)
txt(d, (x2 + PAD + 46, y + 34), "搜索标签…", 13, T["dim"], anchor="lm")
rrect(d, [x2 + PANEL_W - PAD - 88, y + 10, x2 + PANEL_W - PAD, y + 58], 12, fill=T["surf"], outline=T["outline"], width=1)
txt(d, (x2 + PANEL_W - PAD - 44, y + 34), "排序 ▾", 12.5, T["prim"], True, "mm")
mx, my = x2 + PANEL_W - PAD - 170, y + 64
rrect(d, [mx, my, mx + 160, my + 126], 10, fill=T["surf"])
d.rounded_rectangle([mx * S, my * S, (mx + 160) * S, (my + 126) * S], 10 * S, outline="#D8D8DC", width=1 * S)
for i, (label, sel) in enumerate([("按数量", True), ("按名称", False), ("按手动", False)]):
    txt(d, (mx + 16, my + 20 + 38 * i), label, 12.5, T["onSurf"] if sel else T["dim"], sel, "lm")
    if sel:
        txt(d, (mx + 138, my + 20 + 38 * i), "✓", 12.5, T["prim"], True, "mm")
callout(d, mx + 176, my + 28, 9)
txt(d, (x2 + PAD, my + 150), "当前排序有 ✓，三个选项全部可见；搜索框省略号统一「…」", 11.5, T["ok"], True)
save(im, "04_标签管理头部_排序菜单.png", my + 200)

# ---------- 05 书架搜索与历史 ----------
im, d = new_canvas("05 · 书架搜索 —— 历史 chips 化 + 单条删除")
x1 = GAP; x2 = GAP + PANEL_W + GAP
def search_field(d, x, y, label):
    rrect(d, [x + PAD, y, x + PANEL_W - PAD, y + 56], 12, fill=T["surf"], outline=T["outline"], width=1)
    d.ellipse([(x + PAD + 16) * S, (y + 20) * S, (x + PAD + 34) * S, (y + 38) * S], outline=T["dim"], width=S)
    txt(d, (x + PAD + 46, y + 28), label, 13, T["dim"], anchor="lm")
    return y + 72
y = panel(d, x1, "BEFORE · 现状", T["err"])
y = search_field(d, x1, y + 8, "搜索...")
callout(d, x1 + PANEL_W - PAD - 26, y - 44, 10)
txt(d, (x1 + PAD, y + 4), "搜索历史", 11, T["dim"])
txt(d, (x1 + PANEL_W - PAD, y + 4), "清除", 11, T["prim"], anchor="ra")
yy = y + 18
for h in ["进击的巨人", "北斗神拳", "浪客行", "火凤燎原"]:
    txt(d, (x1 + PAD + 8, yy + 10), "—", 11, T["dim"], anchor="lm")
    txt(d, (x1 + PAD + 34, yy + 10), h, 13, T["onSurf"], anchor="lm")
    yy += 34
yy += 8
yb = note_block(d, x1 + PAD, yy, [(10, "占位词「搜索...」信息量低（书名？作者？标签？）"),
                             (11, "历史只能整表清除，无法删掉单条手滑记录")])

y = panel(d, x2, "AFTER · 历史 chips（点=搜，×=删单条）", T["ok"])
y = search_field(d, x2, y + 8, "搜索书名、作者…")
callout(d, x2 + PAD + 150, y - 44, 10)
yy = y + 6
x, rowy = x2 + PAD, yy
for h in ["进击的巨人", "北斗神拳", "浪客行", "火凤燎原"]:
    w = tw(d, h, 12.5) + 52
    rrect(d, [x, rowy, x + w, rowy + 36], 18, fill=T["surf"], outline=T["outline"], width=1)
    txt(d, (x + 18, rowy + 18), h, 12.5, T["onSurf"], anchor="lm")
    txt(d, (x + w - 16, rowy + 18), "×", 13, T["dim"], anchor="mm")
    x += w + 10
    if x + w > x2 + PANEL_W - PAD - 20:
        x = x2 + PAD; rowy += 44
callout(d, x + 16, rowy + 18, 11)
txt(d, (x2 + PAD, rowy + 62), "右上角保留「清空」整表入口；占位词写明可搜范围", 11.5, T["ok"], True)
save(im, "05_书架搜索_历史chips.png", max(yb, rowy + 100))

# ---------- 06 服务器表单必填规范 ----------
im, d = new_canvas("06 · 服务器表单 —— 必填标记与示例提示统一")
x1 = GAP; x2 = GAP + PANEL_W + GAP
def form_field(d, x, y, label, value="", helper=None, mark=None):
    txt(d, (x + PAD, y), label, 11.5, T["dim"])
    rrect(d, [x + PAD, y + 22, x + PANEL_W - PAD - 20, y + 64], 10, fill=T["surf"], outline=T["outline"], width=1)
    if value:
        txt(d, (x + PAD + 16, y + 43), value, 13, T["onSurf"], anchor="lm")
    if helper:
        txt(d, (x + PAD + 2, y + 72), helper, 10.5, T["dim"])
    if mark:
        callout(d, x + PANEL_W - PAD, y + 10, mark)
    return y + (94 if helper else 78)
y = panel(d, x1, "BEFORE · 现状（三种写法并存）", T["err"])
yy = form_field(d, x1, y + 10, "服务器地址 *（https://dav.example.com/）", mark=12)
yy = form_field(d, x1, yy, "主机地址或 IP *", mark=12)
yy = form_field(d, x1, yy, "名称（可选）")
yy += 6
yb = note_block(d, x1 + PAD, yy, [(12, "示例 URL 塞进 label，星号可选写法三样并存"),
                             (12, "输入框上方一行塞下身份+示例，扫读困难")])

y = panel(d, x2, "AFTER · label 管身份、helper 管示例", T["ok"])
yy = form_field(d, x2, y + 10, "服务器地址 *", "https://", "示例：https://dav.example.com/", mark=12)
yy = form_field(d, x2, yy, "主机地址或 IP *", "", "SMB 填 IP 或主机名，如 192.168.1.10", mark=13)
yy = form_field(d, x2, yy, "名称（可选）", "", "留空时以地址作为名称")
yy += 4
txt(d, (x2 + PAD, yy + 6), "规范：必填 = label 尾加 *；示例/说明一律进 helper 行", 11.5, T["ok"], True)
save(im, "06_服务器表单_必填与提示.png", max(yb, yy + 40))

# ---------- 07 阅读设置 sheet 分组 ----------
im, d = new_canvas("07 · 阅读设置（常规页）—— 加分组锚点，零重排")
x1 = GAP; x2 = GAP + PANEL_W + GAP
def check_row(d, x, y, s, checked=False):
    rrect(d, [x + PAD, y + 5, x + PAD + 22, y + 27], 6,
          fill=T["prim"] if checked else T["surf"], outline=None if checked else "#C9C9CF", width=1)
    if checked:
        txt(d, (x + PAD + 11, y + 16), "✓", 10, "#FFFFFF", True, "mm")
    txt(d, (x + PAD + 34, y + 16), s, 12.5, T["onSurf"], anchor="lm")
    return y + 40
y = panel(d, x1, "BEFORE · 现状（连续滚动无锚点）", T["err"])
yy = y + 10
for s, c in [("显示页码", True), ("沉浸式模式（重进生效）", False), ("保持屏幕常亮", True),
             ("无缝续读下一卷（读到卷末自动进入同书下一卷）", False),
             ("翻页时闪烁（全局接管中：设置→阅读设置→墨水屏）", False), ("自动进入条漫模式", True)]:
    yy = check_row(d, x1, yy, s, c)
yy += 6
txt(d, (x1 + PAD + 4, yy), "氛围音", 11.5, T["grp"], True)
yy += 26
x = x1 + PAD
for lab, sel in [("关闭", True), ("下雨", False), ("深夜雨", False)]:
    w = tw(d, lab, 12.5) + 32
    rrect(d, [x, yy, x + w, yy + 32], 16, fill=T["primCont"] if sel else T["surf"], outline=None if sel else T["outline"], width=1)
    txt(d, (x + w / 2, yy + 16), lab, 12.5, T["onPrimCont"] if sel else T["dim"], sel, "mm")
    x += w + 10
yy += 44
yb = note_block(d, x1 + PAD, yy, [(14, "十几个开关连排，视觉分组只有空气")])

y = panel(d, x2, "AFTER · 同样的行，仅加分组锚点", T["ok"])
yy = y + 10
yy = group_label(d, x2 + PAD, yy, "阅读")
for s, c in [("显示页码", True), ("沉浸式模式（重进生效）", False), ("自动进入条漫模式", True), ("无缝续读下一卷", False)]:
    yy = check_row(d, x2, yy, s, c)
yy = group_label(d, x2 + PAD, yy + 2, "屏幕")
for s, c in [("保持屏幕常亮", True), ("翻页时闪烁  ⓘ", False)]:
    yy = check_row(d, x2, yy, s, c)
yy = group_label(d, x2 + PAD, yy + 2, "氛围音")
x = x2 + PAD
for lab, sel in [("关闭", True), ("下雨", False), ("深夜雨", False)]:
    w = tw(d, lab, 12.5) + 32
    rrect(d, [x, yy, x + w, yy + 32], 16, fill=T["primCont"] if sel else T["surf"], outline=None if sel else T["outline"], width=1)
    txt(d, (x + w / 2, yy + 16), lab, 12.5, T["onPrimCont"] if sel else T["dim"], sel, "mm")
    x += w + 10
callout(d, x2 + PANEL_W - PAD - 6, y + 40, 14)
yy += 46
txt(d, (x2 + PAD, yy), "「全局接管中：设置→阅读设置→墨水屏」长说明收进行尾 ⓘ，", 11, T["dim"])
txt(d, (x2 + PAD, yy + 20), "行文字保持短；无缝续读长括号说明同理收进 ⓘ", 11, T["dim"])
save(im, "07_阅读设置sheet_分组锚点.png", max(yb, yy + 60))

# ---------- 08 大文件下载确认 ----------
im, d = new_canvas("08 · 远端大文件下载确认 —— 数字分行 + 动词按钮")
x1 = GAP; x2 = GAP + PANEL_W + GAP
y = panel(d, x1, "BEFORE · 现状", T["err"])
yy = dialog(d, x1 + PAD, y + 16, W - PAD * 2, "大文件下载确认",
            ["共 36 本，最大约 156 MB（超过 128 MB）。继续下载到本地？"],
            [("取消", 0), ("继续", 0)])
yy += 10
yb = note_block(d, x1 + PAD, yy, [(15, "「继续」不指明动作；总量/单卷/阈值挤一行")])

y = panel(d, x2, "AFTER · 两行正文 + 动词按钮", T["ok"])
yy = dialog(d, x2 + PAD, y + 16, W - PAD * 2, "下载 36 本到本地？",
            ["共约 1.2 GB，单卷最大 156 MB。", "下载队列中可随时暂停或取消。"],
            [("取消", 0), ("开始下载", 1)], danger_idx=1, mark_btn=(1, 15))
yy += 10
txt(d, (x2 + PAD, yy), "安抚行「可随时取消」降低决策压力——符合实际行为", 11.5, T["ok"], True)
save(im, "08_大文件下载确认_三行式.png", max(yb, yy + 40))
print("ALL DONE")
