"""中英文案键对齐检查：键集合一致，带格式化占位符的条目两边占位符一致。

用法（项目根目录）：python scripts/check_strings.py
"""
import io
import re
import sys

EN = "app/src/main/res/values/strings.xml"
ZH = "app/src/main/res/values-zh-rCN/strings.xml"
ENTRY = re.compile(r'<string\s+name="([^"]+)"[^>]*>(.*?)</string>', re.S)
ARG = re.compile(r"%(\d+\$)?[sdf]")


def load(path):
    text = io.open(path, encoding="utf-8").read()
    out = {}
    for name, body in ENTRY.findall(text):
        if name in out:
            print("DUPLICATE key in %s: %s" % (path, name))
            sys.exit(2)
        out[name] = body
    return out


def main():
    en, zh = load(EN), load(ZH)
    ok = True
    for k in sorted(set(en) - set(zh)):
        print("only in en:", k); ok = False
    for k in sorted(set(zh) - set(en)):
        print("only in zh:", k); ok = False
    for k in sorted(set(en) & set(zh)):
        a, b = sorted(ARG.findall(en[k])), sorted(ARG.findall(zh[k]))
        if a != b or len(ARG.findall(en[k])) != len(ARG.findall(zh[k])):
            print("placeholder mismatch: %s (en=%r zh=%r)" % (k, en[k], zh[k])); ok = False
    print("en=%d zh=%d -> %s" % (len(en), len(zh), "PASS" if ok else "FAIL"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
