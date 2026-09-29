# -*- coding: utf-8 -*-
"""漫读 UI 文案体检（方案附件，不改动应用代码）
扫描 app/src/main/java 下的用户可见字符串，按《文案规范 v1》报违规：
  R1 省略号     中文文案以 ... 结尾 → 应为单字符 …
  R2 加号       全角 ＋ 出现在 UI 文案 → 统一半角 +
  R3 确认按钮   Text("确定") → 确认按钮应写具体动词（保存/创建/应用/删除/移除/清空…）
  R4 中英空格   CJK 与数字/字母粘连（如 "3个文件夹"）→ 疑似缺空格，人工复核
  R5 尖括号     "更多>" 这类裸 > → 应为 ›
  R6 混用口径   空状态 "暂无" 与 "还没有" 混用 → 统一「还没有」
用法: python tools/ui_text_lint.py
"""
import os, re, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "java")
PROJECT = os.path.normpath(os.path.join(ROOT, "..", "..", ".."))
rules = {k: [] for k in ["R1", "R2", "R3", "R4", "R5", "R6"]}
CJK = r"\u4e00-\u9fff"
# 匹配字符串字面量（不含插值的普通段），足够覆盖 Text("...") 与 label/placeholder
STR = re.compile(r'"([^"\\\n]{1,80})"')
RE_ELL = re.compile(r'[^.]\\?\\.\\.$|[^.]$')  # placeholder, real check below

def check(lit, path, line):
    has_cjk = re.search(r"[" + CJK + r"]", lit)
    if not has_cjk:
        return
    if lit.endswith("..."):
        rules["R1"].append((path, line, lit))
    if "＋" in lit:
        rules["R2"].append((path, line, lit))
    if lit == "确定":
        rules["R3"].append((path, line, lit))
    if re.search(r"[0-9a-zA-Z][" + CJK + r"]|[" + CJK + r"][0-9]", lit) and "%" not in lit and "$" not in lit:
        rules["R4"].append((path, line, lit))
    if re.search(r"更多>[\u3000-\u9fff<]", lit) or lit.endswith("更多>"):
        rules["R5"].append((path, line, lit))
    if lit.startswith("暂无"):
        rules["R6"].append((path, line, lit))

for dirpath, _, files in os.walk(ROOT):
    for fn in files:
        if not fn.endswith(".kt"):
            continue
        p = os.path.join(dirpath, fn)
        rel = os.path.relpath(p, PROJECT).replace("\\", "/")
        with open(p, encoding="utf-8", errors="ignore") as f:
            for i, l in enumerate(f, 1):
                for m in STR.finditer(l):
                    check(m.group(1), rel, i)

total = 0
names = {"R1": "省略号 ... → …", "R2": "全角加号 ＋ → +", "R3": '确认按钮 "确定" → 具体动词',
         "R4": "中英数字缺空格(疑似)", "R5": "裸 > → ›", "R6": "空状态「暂无」→「还没有」"}
for k in ["R1", "R2", "R3", "R4", "R5", "R6"]:
    v = rules[k]
    total += len(v)
    print(f"[{k}] {names[k]}: {len(v)} 处")
    for path, line, lit in v[:99]:
        print(f"    {path}:{line}  \"{lit}\"")
    print(f"\n合计 {total} 处待修（R4 为疑似需人工复核）")
