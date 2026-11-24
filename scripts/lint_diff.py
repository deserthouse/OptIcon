"""lint 逐项对照：按（严重度, 问题类型, 文件）做多重集合比对，不带行号。

用法（项目根目录）：python scripts/lint_diff.py baseline.xml current.xml
行号会因编辑而漂移，不进键；同一文件里同一类型的多个问题按出现次数比较。
"""
import collections
import io
import re
import sys

ISSUE = re.compile(r"<issue\s+([^>]*)>(.*?)</issue>", re.S)
ATTR = lambda name, attrs: (re.search(name + r'="([^"]*)"', attrs) or [None, ""])[1]


def load(path):
    text = io.open(path, encoding="utf-8").read()
    out = collections.Counter()
    for attrs, body in ISSUE.findall(text):
        loc = re.search(r'<location\s+([^>]*)/>', body)
        file = ATTR("file", loc.group(1)) if loc else "?"
        file = re.sub(r"^.*?[/\\]app[/\\](?=src|build\.gradle)", "app/", file)  # 两个检出根不同：一律归一到模块相对路径
        file = file.replace("\\", "/")
        out[(ATTR("severity", attrs), ATTR("id", attrs), file)] += 1
    return out


def main():
    base, cur = load(sys.argv[1]), load(sys.argv[2])
    by_sev = lambda c: collections.Counter(sev for (sev, _, _), n in c.items() for _ in range(n))
    print("baseline:", dict(by_sev(base)))
    print("current :", dict(by_sev(cur)))
    print("\n== NEW in current ==")
    for key, n in sorted((cur - base).items()):
        print("  +%d  %s  %s  %s" % (n, key[0], key[1], key[2]))
    print("\n== GONE from baseline ==")
    for key, n in sorted((base - cur).items()):
        print("  -%d  %s  %s  %s" % (n, key[0], key[1], key[2]))
    errors = sum(n for (sev, _, _), n in cur.items() if sev in ("Error", "Fatal"))
    print("\nErrors in current:", errors)
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
