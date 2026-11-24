"""生成注入设备用的偏好文件（XML）。

用法：python scripts/device/mkprefs.py <base.xml> <out.xml> [key=value ...] [key- ...]
  key=value  设置字符串键（已有的替换，没有的追加在 </map> 之前）
  key-       删除该键
布尔写成 key=true / key=false（自动用 <boolean>）。
"""
import io
import sys
from xml.sax.saxutils import escape


def main():
    base, out, ops = sys.argv[1], sys.argv[2], sys.argv[3:]
    s = io.open(base, encoding="utf-8").read()
    for op in ops:
        if op.endswith("-"):
            key = op[:-1]
            new, n = remove(s, key)
            assert n >= 0, "nothing to remove for %s (malformed base?)" % key
            s = new
            continue
        key, value = op.split("=", 1)
        s, n = remove(s, key)
        if value in ("true", "false"):
            line = '    <boolean name="%s" value="%s" />\n' % (key, value)
        else:
            line = '    <string name="%s">%s</string>\n' % (key, escape(value))
        at = s.rindex("</map>")
        s = s[:at] + line + s[at:]
    io.open(out, "w", encoding="utf-8", newline="\n").write(s)
    print("wrote %s (%d ops)" % (out, len(ops)))


def remove(s, key):
    import re
    pat = re.compile(r'[ \t]*<(string|boolean|set)[^>]*name="%s"[^>]*?(/>|>.*?</\1>)[ \t]*\n?' % re.escape(key), re.S)
    new, n = pat.subn("", s, count=1)
    return new, n


if __name__ == "__main__":
    main()
