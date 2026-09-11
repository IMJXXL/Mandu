import re, os
from collections import defaultdict

log_path = r"P:\Program Files\Netease\GameViewer\Download\logcat_zip_test.txt"

categories = {
    "OOM": re.compile(r"OutOfMemory|Failed to allocate|Forcing collection", re.I),
    "ANR": re.compile(r"AnrWatchdog|主线程卡顿|ANR", re.I),
    "SlowBinder23": re.compile(r"Slow Binder.*code=23"),
    "SlowBinder1": re.compile(r"Slow Binder.*code=1\s"),
    "SELinuxDir": re.compile(r"denied.*tclass=dir"),
    "SELinuxFile": re.compile(r"denied.*tclass=file"),
    "FrameSeqDecoder": re.compile(r"FrameSeqDecoder|FrameAnimationDrawable"),
    "ScrollCallback": re.compile(r"scroll callback", re.I),
    "FATAL": re.compile(r"FATAL|AndroidRuntime.*crash", re.I),
    "GCClamp": re.compile(r"Clamp target GC|Forcing collection"),
    "DeadObject": re.compile(r"DeadObject|transaction failed|binder.*dead", re.I),
    "CoverFetch": re.compile(r"CoverFetch", re.I),
    "ScanDirFast": re.compile(r"scanDirFast|resolveRealDir", re.I),
    "TrimRegistry": re.compile(r"TrimRegistry|trimAll", re.I),
    "ListPages": re.compile(r"listPages|listPagesFor", re.I),
    "LoadPage": re.compile(r"loadPage|loadDirPageRaw|loadZipPage", re.I),
    "PeekAnim": re.compile(r"peekAnim|ensureAnimKind", re.I),
    "GetRealFile": re.compile(r"getRealFile|getRealFileForPage", re.I),
    "MakeAnim": re.compile(r"makeAnimDrawable|loadAnimPage|loadAndSetDrawable", re.I),
    "Mmap": re.compile(r"mmap|FileChannel.*map|MapMode", re.I),
    "ExceptionE": re.compile(r"^.*E/\S+\s*\("),
    "WarningW": re.compile(r"^.*W/\S+\s*\("),
    "Scanner": re.compile(r"Scanner|findFirstCover|rescan", re.I),
    "ArchiveEngine": re.compile(r"ArchiveEngine", re.I),
    "DedupBooks": re.compile(r"DedupBooks", re.I),
    "Coil": re.compile(r"[Cc]oil", re.I),
}

counts = defaultdict(int)
samples = defaultdict(list)

with open(log_path, "r", encoding="utf-8", errors="replace") as f:
    for line in f:
        for cat, pattern in categories.items():
            if pattern.search(line):
                counts[cat] += 1
                if len(samples[cat]) < 8:
                    samples[cat].append(line.strip()[:250])

print("=" * 70)
print(f"日志分析: {os.path.getsize(log_path)/1024:.1f} KB")
print("=" * 70)

for cat in categories:
    c = counts[cat]
    if c == 0:
        continue
    print(f"\n[{cat}]: {c} 次")
print("=" * 70)
print("总结")
print("=" * 70)
for cat in categories:
    if counts[cat] > 0:
        print(f"  {cat}: {counts[cat]}")