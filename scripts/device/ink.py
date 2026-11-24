"""取状态行文字的主色，判定是错误色还是普通说明色。

用法：python ink.py <截图.png> <文字行中心y> [半高=24] [neutral|error]
输出采样结果；给了第 4 个参数时，判定与之相符 → 退出码 0。

判据：错误色里 R 明显高于 G、B；普通说明文字是低饱和的灰紫。背景取同一行最右侧的像素。
"""
import sys
from PIL import Image

path, cy = sys.argv[1], int(sys.argv[2])
half = int(sys.argv[3]) if len(sys.argv) > 3 and sys.argv[3] not in ("neutral", "error") else 24
want = sys.argv[4] if len(sys.argv) > 4 else (sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] in ("neutral", "error") else "")
img = Image.open(path).convert("RGB")
w, h = img.size
band = [(x, y) for y in range(max(0, cy - half), min(h, cy + half)) for x in range(60, w - 60, 2)]
bg = img.getpixel((w - 30, cy))


def dist(c):
    return sum((a - b) ** 2 for a, b in zip(c, bg))


ink = max((img.getpixel(p) for p in band), key=dist)
r, g, b = ink
is_error = r - max(g, b) > 60
verdict = "error" if is_error else "neutral"
print("bg=%s ink=%s -> %s" % (bg, ink, verdict))
sys.exit(0 if not want or want == verdict else 1)
