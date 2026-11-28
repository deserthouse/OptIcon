"""状态栏里新出现的小图标是不是单色：比较发通知前后的两张截图，新增出来的像素必须都是无彩色。

用途：规则库里的 SVG 渲染出来是带透明通道的彩色位图，和上游的单色位图格式不同——这条检查证明
它在状态栏里同样被按单色着色（拿一个红色的图标试：没被着色就会出现彩色像素）。

只看状态栏左侧（通知图标所在的一侧，屏幕宽度的前 60%）：右侧的时钟、电量本来就会变，与被测的图标无关。
新增像素的包围盒还得像一个小图标（宽、高都在 8px 到 160px 之间）——不然说明混进了别处的变化
（应用顶部的内容变了之类），这时「全部无彩色」不能说明图标被着色了（2026-11-28 盲审 BU-11d）。

用法：python scripts/device/tint.py before.png after.png [只看顶部多少像素，默认 200]
退出码 0 = 找到了新增像素、范围像一个小图标、且全部无彩色；1 = 新增像素里有彩色的；
       2 = 左侧没有新增像素（判断不了，不算通过）；3 = 图读不了；
       4 = 新增像素的范围不像一个小图标（判断不了，不算通过）。
无彩色：R、G、B 两两相差都不超过 24（抗锯齿的灰色边缘算无彩色）。
诊断信息写到标准错误（run_suite.sh 的 check 把它落进 debug.log）：新增像素的数量与包围盒、其中彩色像素的数量。
"""
import sys

from PIL import Image

LEFT_SPAN = 0.6      # 只看屏幕左侧这一比例的宽度（通知图标所在的一侧）
MIN_ICON_PX = 8      # 包围盒的最小边长
MAX_ICON_PX = 160    # 包围盒的最大边长：状态栏小图标只有几十像素


def load(name):
    img = Image.open(name)
    img.load()
    return img.convert("RGB")


def main():
    try:
        before, after = load(sys.argv[1]), load(sys.argv[2])
    except Exception as e:  # noqa: BLE001
        print("unreadable: %s" % e, file=sys.stderr)
        return 3
    if before.size != after.size:
        print("sizes differ: %s vs %s" % (before.size, after.size), file=sys.stderr)
        return 3
    strip = int(sys.argv[3]) if len(sys.argv) > 3 else 200
    pa, pb = before.load(), after.load()
    width, height = before.size
    span = int(width * LEFT_SPAN)
    xs, ys, colored, changed = [], [], 0, 0
    for y in range(min(strip, height)):
        for x in range(span):
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
        print("no change in the left %d%% of the top %dpx" % (LEFT_SPAN * 100, strip), file=sys.stderr)
        return 2
    box = (min(xs), min(ys), max(xs), max(ys))
    w, h = box[2] - box[0] + 1, box[3] - box[1] + 1
    print("changed=%d box=(%d,%d)-(%d,%d) colored=%d" % (changed, box[0], box[1], box[2], box[3], colored),
          file=sys.stderr)
    if w < MIN_ICON_PX or h < MIN_ICON_PX or w > MAX_ICON_PX or h > MAX_ICON_PX:
        print("box %dx%d does not look like a status bar icon (screen %dx%d)" % (w, h, width, height),
              file=sys.stderr)
        return 4
    return 1 if colored else 0


if __name__ == "__main__":
    sys.exit(main())
