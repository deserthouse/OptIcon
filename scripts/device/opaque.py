"""对话框的底板是不是不透明的。用法：python opaque.py <截图.png> <界面树.xml>

判据：对话框顶部内边距那一行（标题上方，左右躲开圆角）在真对话框上必然是**单一纯色**。
取样行按**标题**定位（标题上方是固定 24dp 的内边距），不按窗口外框：外框的上沿比可见的
底板还高出一段（阴影留白），落在那一行里量到的是遮罩。
切换深色模式、旋转之后系统有过渡动画，过早截图会截到「底板透明、底下页面透出来」的
过渡帧——那一行横穿底下的页面内容，颜色参差。

为什么需要它：肉眼看这种图分不清是截图时机还是真回归。有了这条断言，截图可以等到画面
稳定再定，读图的人拿到的也是确定的结论。取样点选在顶部是因为它不会被软键盘遮挡，
也压不到任何文字。

退出码：0 不透明；1 透明（过渡帧或真回归）；2 界面树里找不到对话框。
"""
import io
import re
import sys
from PIL import Image

MAX_SPREAD = 16    # 同一行各取样点之间的最大色差（RGB 三通道绝对差之和）：真对话框为 0 上下
MIN_CONTRAST = 60  # 底板与左侧遮罩的平均色差：实测正常深色约 90、浅色 400 以上、过渡帧 36（取正中）
PATCH = 3          # 采样半径（像素），取 7×7 的均值抗噪
Y_ROW = 30         # 取样行在标题上方的距离：落在 24dp 的内边距里，圆角之内
X_IN = 45          # 取样范围离对话框左右边缘的距离：在圆角之内
X_OUT = 45         # 外侧取样点在对话框左边缘之外多远（避开页面左上角的返回箭头）
SAMPLES = 14
TITLES = ("Add Source", "Edit Source", "Sync the full icon library?")


def main():
    img = Image.open(sys.argv[1]).convert("RGB")
    xml = io.open(sys.argv[2], encoding="utf-8", errors="replace").read()
    nodes = []
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        text = (re.search(r'text="([^"]*)"', n) or [None, ""])[1]
        b = [int(x) for x in re.findall(r"\d+", (re.search(r'bounds="([^"]*)"', n) or [0, ""])[1])]
        if len(b) == 4:
            nodes.append((b, text))
    title = next((b for b, text in nodes if text in TITLES), None)
    if title is None:
        print("no dialog title in the dump")
        sys.exit(2)
    tx, ty = (title[0] + title[2]) // 2, (title[1] + title[3]) // 2
    holders = [b for b, _ in nodes if b[0] <= tx <= b[2] and b[1] <= ty <= b[3]]
    left, right = min(b[0] for b in holders), max(b[2] for b in holders)
    px = img.load()

    def patch_mean(x, y):
        acc = [0, 0, 0]
        n = 0
        for dx in range(-PATCH, PATCH + 1):
            for dy in range(-PATCH, PATCH + 1):
                xx, yy = x + dx, y + dy
                if 0 <= xx < img.size[0] and 0 <= yy < img.size[1]:
                    p = px[xx, yy]
                    acc[0] += p[0]; acc[1] += p[1]; acc[2] += p[2]; n += 1
        return [a / float(n or 1) for a in acc]

    y = title[1] - Y_ROW
    row = [patch_mean(left + X_IN + (right - left - 2 * X_IN) * i // (SAMPLES - 1), y) for i in range(SAMPLES)]
    spread = max(sum(abs(p - q) for p, q in zip(a, b)) for a in row for b in row)
    surface = [sum(c[i] for c in row) / SAMPLES for i in range(3)]
    outside = patch_mean(max(PATCH, left - X_OUT), y)
    contrast = sum(abs(p - q) for p, q in zip(surface, outside))
    ok = spread <= MAX_SPREAD and contrast >= MIN_CONTRAST
    print("dialog x[%d..%d] row-y=%d surface=(%d,%d,%d) outside=(%d,%d,%d) row-spread=%.0f contrast=%.0f -> %s"
          % (left, right, y, surface[0], surface[1], surface[2], outside[0], outside[1], outside[2],
             spread, contrast, "opaque" if ok else "TRANSPARENT"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
