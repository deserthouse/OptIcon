"""在界面树里按文本或 content-desc 找节点，输出中心坐标。

用法：python scripts/device/find.py <ui.xml> <text 或 content-desc> [第几个，默认 0]
找到输出 "x y"；找不到输出空行并以 1 退出。
"""
import io
import re
import sys


def main():
    xml, needle = sys.argv[1], sys.argv[2]
    nth = int(sys.argv[3]) if len(sys.argv) > 3 else 0
    s = io.open(xml, encoding="utf-8", errors="replace").read()
    hits = []
    for m in re.finditer(r"<node [^>]*>", s):
        n = m.group(0)
        g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ""])[1]
        if g("text") == needle or g("content-desc") == needle:
            b = [int(x) for x in re.findall(r"\d+", g("bounds"))]
            if len(b) == 4:
                hits.append(((b[0] + b[2]) // 2, (b[1] + b[3]) // 2, g("enabled")))
    if len(hits) <= nth:
        print("NOT FOUND: %r (hits=%d)" % (needle, len(hits)))
        sys.exit(1)
    x, y, enabled = hits[nth]
    print("%d %d" % (x, y))
    sys.stderr.write("found %r #%d at (%d,%d) enabled=%s\n" % (needle, nth, x, y, enabled))


if __name__ == "__main__":
    main()
