import os
from PIL import Image

src = r"C:\Android\ico.png"
out_dir = r"C:\Android\MangaReader\app\src\main\res"

img = Image.open(src)
print(f"Original size: {img.size}")

# Create mipmap directories
sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

for folder, size in sizes.items():
    dir_path = os.path.join(out_dir, folder)
    os.makedirs(dir_path, exist_ok=True)
    resized = img.resize((size, size), Image.LANCZOS)
    out_path = os.path.join(dir_path, "ic_launcher.png")
    resized.save(out_path, "PNG")
    print(f"Created {out_path} ({size}x{size})")

print("Done!")
