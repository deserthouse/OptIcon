"""在界面树里按上下文找节点，输出中心坐标。find.py 的补充：目标文字本身不唯一时用。

用法：
  python pick.py <ui.xml> below <上方文字> <目标文字>      → 上方文字之下、最靠上的那个目标
  python pick.py <ui.xml> rowbutton <行标题> <按钮文字>    → 与行标题同一行的那个按钮
  python pick.py <ui.xml> has <文字片段>                  → 文字里含有该片段的第一个节点
  python pick.py <ui.xml> field <第几个输入框（从0起）>      → 输入框（EditText）的中心
找到输出 "x y"；找不到以 1 退出。
"""
import io
import re
import sys

ROW_TOLERANCE_PX = 90   # 同一行的判定：中心纵坐标相差不超过这么多像素


def nodes(xml):
    out = []
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ""])[1]
        b = [int(x) for x in re.findall(r"\d+", g("bounds"))]
        if len(b) == 4:
            out.append(((b[0] + b[2]) // 2, (b[1] + b[3]) // 2, g("text"), g("content-desc")))
    return out


def main():
    xml = io.open(sys.argv[1], encoding="utf-8", errors="replace").read()
    mode = sys.argv[2]
    ns = nodes(xml)
    if mode == "below":
        a, b = sys.argv[3], sys.argv[4]
        tops = [y for x, y, t, d in ns if t == a or d == a]
        if not tops:
            sys.exit(1)
        top = min(tops)
        for x, y, t, d in sorted((n for n in ns if n[2] == b or n[3] == b), key=lambda n: n[1]):
            if y > top:
                print(x, y)
                return
        sys.exit(1)
    if mode == "rowbutton":
        a, b = sys.argv[3], sys.argv[4]
        best = None
        for tx, ty, t, d in ns:
            if not (t == a or d == a):
                continue
            for x, y, t2, d2 in ns:
                if (t2 == b or d2 == b) and abs(y - ty) <= ROW_TOLERANCE_PX:
                    if best is None or abs(y - ty) < best[0]:
                        best = (abs(y - ty), x, y)
        if best is None:
            sys.exit(1)
        print(best[1], best[2])
        return
    if mode == "field":
        want = int(sys.argv[3]); found = []
        for m in re.finditer(r"<node [^>]*>", xml):
            n = m.group(0)
            g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ""])[1]
            if g("class").endswith("EditText"):
                b = [int(x) for x in re.findall(r"\d+", g("bounds"))]
                if len(b) == 4:
                    found.append(((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
        found.sort(key=lambda p: p[1])
        if want >= len(found):
            sys.exit(1)
        print(found[want][0], found[want][1])
        return
    if mode == "has":
        frag = sys.argv[3]
        for x, y, t, d in ns:
            if frag in t or frag in d:
                print(x, y)
                return
        sys.exit(1)
    sys.exit(2)


if __name__ == "__main__":
    main()
