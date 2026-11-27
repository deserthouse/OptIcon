"""看一张 PNG 的事实（设备验证套件用）：文件完整、能解码、尺寸、可见像素；可选「可见像素是不是红色」。

用法：python scripts/device/pngstat.py <png> [最大边长像素] [--red]
退出码 0 = 全部满足；1 = 有一条不满足；2 = 文件读不了或不是 PNG。
输出一行（run_suite.sh 把它记进 debug.log）。
"""
import sys

from PIL import Image


def main():
    path = sys.argv[1]
    max_dim = int(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2].isdigit() else None
    want_red = "--red" in sys.argv[2:]
    try:
        with open(path, "rb") as f:
            head = f.read(8)
        assert head == b"\x89PNG\r\n\x1a\n", "not a PNG file"
        img = Image.open(path)
        img.load()
    except Exception as e:  # noqa: BLE001 - 任何读不出都算 2
        print("unreadable: %s" % e)
        return 2
    w, h = img.size
    px = img.convert("RGBA").load()
    visible = red = 0
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a > 32:
                visible += 1
                if r > 150 and g < 110 and b < 110:
                    red += 1
    share = (red / visible) if visible else 0.0
    problems = []
    if w < 1 or h < 1:
        problems.append("empty")
    if max_dim and (w > max_dim or h > max_dim):
        problems.append("a side is over %dpx" % max_dim)
    if visible == 0:
        problems.append("nothing visible")
    if want_red and visible and share < 0.9:
        problems.append("only %.0f%% of the visible pixels are red" % (share * 100))
    print("%dx%d visible=%d red=%.0f%%%s" % (
        w, h, visible, share * 100, ("; " + "; ".join(problems)) if problems else ""))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
