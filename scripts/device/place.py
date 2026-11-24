"""判断某个订阅源名字显示在哪个分区里。find.py 的补充：同一个源可能同时出现在两个分区。

用法：python place.py <ui.xml> <源名字> <notif|picp>
  退出码 0 = 在该分区内；1 = 判断得了、但不在；2 = 判断不了（分区锚点不在屏幕上）

分区在设置页上的样子（自上而下）：
  分区标题 + 同步按钮 → 内置源那一行 → 自定义源各一行 →（提示）→「Add Custom」
通知图标源分区从内置 ANIP 那一行算起（分区标题滚出屏幕后它仍然可辨认），到它下面的第一个
「Add Custom」为止；应用图标源分区从「App Icon Sources」标题起，到下面的第一个「Add Custom」
为止（那个按钮不在屏幕上时到屏幕底部）。
"""
import io
import re
import sys

ANIP_BUILTIN = "Android Notification Icon Project (ANIP, formerly AndroidNotifyIconAdapt)"
PICP_TITLE = "App Icon Sources"
ADD = "Add Custom"


def nodes(xml):
    out = []
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ""])[1]
        b = [int(x) for x in re.findall(r"\d+", g("bounds"))]
        if len(b) == 4:
            out.append(((b[1] + b[3]) // 2, g("text"), g("content-desc")))
    return out


def main():
    xml = io.open(sys.argv[1], encoding="utf-8", errors="replace").read()
    target, section = sys.argv[2], sys.argv[3]
    ns = nodes(xml)
    at = lambda text: sorted(y for y, t, d in ns if t == text or d == text)
    add = at(ADD)
    bottom = max(y for y, _, _ in ns)
    if section == "notif":
        anchor = at(ANIP_BUILTIN)
        if not anchor:
            sys.exit(2)
        start = min(anchor)
        ends = [y for y in add if y > start]
        if not ends:
            sys.exit(2)
        end = ends[0]
    elif section == "picp":
        anchor = at(PICP_TITLE)
        if not anchor:
            sys.exit(2)
        start = min(anchor)
        end = min((y for y in add if y > start), default=bottom + 1)
    else:
        sys.exit(2)
    sys.exit(0 if any(start < y < end for y in at(target)) else 1)


if __name__ == "__main__":
    main()
