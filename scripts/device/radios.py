"""读界面树里的单选行：行高、选中状态、行内文字的位置、选项之间的间距。

用法：
  python radios.py <ui.xml> [密度 px/dp]              打印每一行
  python radios.py <ui.xml> <密度> _picp_checked      标题为 PICP zipball 的行被选中 → 退出码 0
  python radios.py <ui.xml> <密度> _none_checked      没有任何一行被选中 → 退出码 0
  python radios.py <ui.xml> <密度> _min_48            所有行高都不低于 48dp → 退出码 0
  python radios.py <ui.xml> <密度> _grouped           选项之间的空隙明显大于一项内部「标题 → 描述」的空隙 → 退出码 0
  python radios.py <ui.xml> <密度> _titles_inside      每个选项的标题都落在自己那一行的边界之内 → 退出码 0

单选行 = checkable 的 selectable 节点；选中态以行节点为准（圆圈本体不单独暴露节点）。
注意：界面树的 enabled 字段不可信（按钮禁用时文本节点仍报 true），本脚本不读它。
"""
import io
import re
import sys

MIN_DP = 48.0
GROUP_EXTRA_PX = 16   # 选项之间的空隙至少比一项内部的空隙多这么多（约 6dp）


def rows_of(xml):
    nodes = []
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ""])[1]
        b = [int(x) for x in re.findall(r"\d+", g("bounds"))]
        if len(b) == 4:
            nodes.append((b, g("text"), g("checkable") == "true", g("checked") == "true"))
    out = []
    for rb, _, _, checked in [x for x in nodes if x[2]]:
        # 行里的文字节点：(上沿, 下沿, 文字)，自上而下
        texts = sorted((b[1], b[3], t) for b, t, _, _ in nodes if t and b[1] >= rb[1] and b[3] <= rb[3])
        out.append((rb, checked, texts))
    out.sort(key=lambda r: r[0][1])
    return out


def main():
    xml, density = sys.argv[1], (float(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2] != "" else 2.625)
    mode = sys.argv[3] if len(sys.argv) > 3 else ""
    rows = rows_of(io.open(xml, encoding="utf-8", errors="replace").read())
    if mode == "_picp_checked":
        sys.exit(0 if any(c and any(t == "PICP zipball" for _, _, t in ts) for _, c, ts in rows) else 1)
    if mode == "_none_checked":
        sys.exit(0 if rows and not any(c for _, c, _ in rows) else 1)
    if mode == "_min_48":
        sys.exit(0 if rows and all((rb[3] - rb[1]) / density >= MIN_DP - 0.2 for rb, _, _ in rows) else 1)
    if mode == "_titles_inside":
        # 字体放大后标题比圆圈高：标题不能越出本行的上边界（越出的部分会落进上一行的点击区）
        xml_text = io.open(xml, encoding="utf-8", errors="replace").read()
        names = ["ANIP bundle", "Legacy JSON", "PICP zipball"]
        ok = len(names) > 0
        for name in names:
            m = re.search(r'<node [^>]*text="%s"[^>]*>' % re.escape(name), xml_text)
            if not m:
                continue   # 滚出屏幕的选项不判
            b = [int(x) for x in re.findall(r"\d+", (re.search(r'bounds="([^"]*)"', m.group(0)) or [0, ""])[1])]
            inside = any(rb[1] <= b[1] and b[3] <= rb[3] for rb, _, _ in rows)
            print("title %r y=%d..%d inside its row: %s" % (name, b[1], b[3], inside))
            ok = ok and inside
        sys.exit(0 if ok and rows else 1)
    if mode == "_grouped":
        full = [ts for _, _, ts in rows if len(ts) >= 2]
        internal = min(ts[1][0] - ts[0][1] for ts in full) if full else 0
        ok = len(rows) >= 2
        for a, b in zip(rows, rows[1:]):
            if not a[2] or not b[2]:
                continue
            gap = b[2][0][0] - a[2][-1][1]
            print("between rows: %dpx (inside a row: %dpx)" % (gap, internal))
            ok = ok and gap >= internal + GROUP_EXTRA_PX
        sys.exit(0 if ok else 1)
    for rb, checked, texts in rows:
        print("row y=%d..%d  h=%dpx (%.1fdp)  checked=%s" % (rb[1], rb[3], rb[3] - rb[1], (rb[3] - rb[1]) / density, checked))
        for top, bottom, t in texts:
            print("     text y=%d..%d (top offset %dpx)  %r" % (top, bottom, top - rb[1], t))


if __name__ == "__main__":
    main()
