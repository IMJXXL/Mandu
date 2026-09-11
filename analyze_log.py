import re, sys, os
from collections import defaultdict

log_path = r"P:\Program Files\Netease\GameViewer\Download\log_export_0911_092249.txt"
if not os.path.exists(log_path):
    print(f"Log file not found: {log_path}")
    sys.exit(1)

categories = {
    "OOM": re.compile(r"OutOfMemory|Failed to allocate|Forcing collection", re.I),
    "ANR": re.compile(r"AnrWatchdog|主线程卡顿|ANR|anr", re.I),
    "SlowBinder23": re.compile(r"code=23"),
    "SlowBinder1": re.compile(r"code=1\s"),
    "SELinuxDir": re.compile(r"denied.*tclass=dir"),
    "SELinuxFile": re.compile(r"denied.*tclass=file"),
    "FrameSeqDecoder": re.compile(r"FrameSeqDecoder|FrameAnimationDrawable"),
    "ScrollCallback": re.compile(r"scroll callback", re.I),
    "FATAL": re.compile(r"FATAL|AndroidRuntime.*crash", re.I),
    "GCClamp": re.compile(r"Clamp target GC|Forcing collection"),
    "DeadObject": re.compile(r"DeadObject|transaction failed|binder.*dead", re.I),
    "CoverFetch": re.compile(r"CoverFetch", re.I),
    "ScanDirFast": re.compile(r"scanDirFast|resolveRealDir", re.I),
    "ExceptionE": re.compile(r"^.*E/\S+\s*\("),
    "WarningW": re.compile(r"^.*W/\S+\s*\("),
    "CoilError": re.compile(r"[Ee]rror.*[Cc]oil|[Cc]oil.*[Ee]rror", re.I),
    "TrimMemory": re.compile(r"onTrimMemory|trimMemory", re.I),
    "LowMemory": re.compile(r"lowMemory|low.*memory|isLowRam", re.I),
    "mmap": re.compile(r"mmap|FileChannel.*map|MapMode", re.I),
    "LRU": re.compile(r"LRU|removeEldest|pageAnimDrawables", re.I),
}

counts = defaultdict(int)
samples = defaultdict(list)
max_samples = 5

# Known harmless tags to filter from E/ and W/
harmless_tags = {"QXPerf", "MiuiMultiWindowUtils", "MiuiMultiWindowUtilsUtils"}

with open(log_path, "r", encoding="utf-8", errors="replace") as f:
    for line in f:
        for cat, pattern in categories.items():
            if pattern.search(line):
                counts[cat] += 1
                if len(samples[cat]) < max_samples:
                    samples[cat].append(line.strip()[:300])
                break  # one category per line for sampling

print("=" * 60)
print("日志全面分析报告")
print(f"文件: {log_path}")
print(f"大小: {os.path.getsize(log_path) / 1024 / 1024:.1f} MB")
print("=" * 60)

severity_map = {
    "OOM": ("严重", "内存分配失败，可能导致闪退"),
    "ANR": ("严重", "应用无响应"),
    "SlowBinder23": ("中等", "SAF openInputStream 慢 Binder"),
    "SlowBinder1": ("中等", "SAF query 慢 Binder"),
    "SELinuxDir": ("无害", "目录 mmap 被 SELinux 拒绝（回退 getdents 正常）"),
    "SELinuxFile": ("严重", "文件 mmap 被 SELinux 拒绝（影响 mmap 优化）"),
    "FrameSeqDecoder": ("中等", "动图解码器实例"),
    "ScrollCallback": ("中等", "RecyclerView scroll callback 警告"),
    "FATAL": ("严重", "崩溃"),
    "GCClamp": ("中等", "GC 频繁回收"),
    "DeadObject": ("严重", "Binder 对象死亡"),
    "CoverFetch": ("信息", "封面加载日志"),
    "ScanDirFast": ("信息", "真实路径快速扫描"),
    "ExceptionE": ("需检查", "E/ 级别错误"),
    "WarningW": ("需检查", "W/ 级别警告"),
    "CoilError": ("严重", "Coil 图片加载错误"),
    "TrimMemory": ("信息", "内存回收回调"),
    "LowMemory": ("信息", "低内存判断"),
    "mmap": ("信息", "mmap 相关"),
    "LRU": ("信息", "LRU 淘汰"),
}

for cat in categories:
    c = counts[cat]
    if c == 0:
        continue
    sev, desc = severity_map.get(cat, ("未知", ""))
    print(f"\n{'='*40}")
    print(f"[{sev}] {cat}: {c} 次 — {desc}")
    print(f"{'='*40}")
    for s in samples[cat]:
        print(f"  {s}")

print(f"\n{'='*60}")
print("总结")
print(f"{'='*60}")
severe = [c for c in ["OOM", "ANR", "FATAL", "DeadObject", "SELinuxFile", "CoilError"] if counts[c] > 0]
medium = [c for c in ["SlowBinder23", "SlowBinder1", "FrameSeqDecoder", "ScrollCallback", "GCClamp"] if counts[c] > 0]
harmless = [c for c in ["SELinuxDir"] if counts[c] > 0]
info = [c for c in ["CoverFetch", "ScanDirFast", "TrimMemory", "LowMemory", "mmap", "LRU"] if counts[c] > 0]

print(f"严重问题: {severe if severe else '无'}")
print(f"中等问题: {medium if medium else '无'}")
print(f"无害: {harmless if harmless else '无'}")
print(f"信息: {info if info else '无'}")