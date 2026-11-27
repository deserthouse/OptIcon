"""状态栏里新出现的小图标是不是单色：比较发通知前后的两张截图，新增出来的像素必须都是无彩色。

用途：规则库里的 SVG 渲染出来是带透明通道的彩色位图，和上游的单色位图格式不同——这条检查证明
它在状态栏里同样被按单色着色（拿一个红色的图标试：没被着色就会出现彩色像素）。

用法：python scripts/device/tint.py before.png after.png [只看顶部多少像素，默认 200]
退出码 0 = 找到了新增像素且全部无彩色；1 = 新增像素里有彩色的；
       2 = 顶部没有新增像素（判断不了，不算通过）；3 = 图读不了。
无彩色：R、G、B 两两相差都不超过 24（抗锯齿的灰色边缘算无彩色）。
输出一行：新增像素的数量与包围盒、其中彩色像素的数量。
"""
import sys

from PIL import Image


def load(name):
    img = Image.open(name)
    img.load()
    return img.convert("RGB")


def main():
    try:
        before, after = load(sys.argv[1]), load(sys.argv[2])
    except Exception as e:  # noqa: BLE001
        print("unreadable: %s" % e)
        return 3
    if before.size != after.size:
        print("sizes differ: %s vs %s" % (before.size, after.size))
        return 3
    strip = int(sys.argv[3]) if len(sys.argv) > 3 else 200
    pa, pb = before.load(), after.load()
    width, height = before.size
    xs, ys, colored, changed = [], [], 0, 0
    for y in range(min(strip, height)):
        for x in range(width):
            r1, g1, b1 = pa[x, y]
            r2, g2, b2 = pb[x, y]
            if abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2) <= 24:
                continue
            changed += 1
            xs.append(x)
            ys.append(y)
            if max(abs(r2 - g2), abs(g2 - b2), abs(r2 - b2)) > 24:
                colored += 1
    if changed == 0:
        print("no change in the top %dpx" % strip)
        return 2
    print("changed=%d box=(%d,%d)-(%d,%d) colored=%d" % (changed, min(xs), min(ys), max(xs), max(ys), colored))
    return 1 if colored else 0


if __name__ == "__main__":
    sys.exit(main())
