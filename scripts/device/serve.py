"""设备验证套件用的本机 HTTP 源（run_suite.sh 启动，用完即停）。模拟器经 http://10.0.2.2:<port>/ 访问。

端点：
  /plain.txt        纯文本（代理返回错误页的形态）
  /nomatch.zip      合法 ZIP，但没有任何 themes/…/icons/{pkg}/1.png
  /truncated.zip    合法 PICP 归档，在最后一个条目的数据末尾被截断（条目边界切点）
  /truncated-mid.zip 同一份归档，从数据中间截断
  /slow.zip         合法 PICP 归档，但发送得很慢（每 200ms 一小块）——测同步中途取消
  /good.zip         合法的小 PICP 归档（几个包），正常速度
  /anip-release.json       规则库官方发布通道的清单（批 UP），指向下面那个包；标签固定 aaaaaaa
  /anip-bundle-aaaaaaa.zip 上游 2026-11-26 那份官方资源包原样（tests/upstream-2026-11-27/bundle/，不入库）
  /anip-extra.zip          同一份包再加两个清单条目：一个彩色 SVG（红色实心圆）和一个别名——自定义规则源用

payloads/mode.txt 的内容是 badsha 时，清单里的 sha256 故意对不上（验证核对失败后退回整库）；其余时候正确。
每个请求都写一行到标准错误（run_suite.sh 把它落到 server.log，用来数请求）。
用法：python scripts/device/serve.py [port]（默认 28765；8765 在本机不可绑定）
"""
import hashlib
import io
import json
import os
import struct
import sys
import time
import zipfile
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
PAYLOADS = os.path.join(HERE, "payloads")

# ── 规则库（批 UP） ──
# 已知答案那一份官方资源包：单测的已知答案检查与设备套件共用，真实包不入库。
REAL_BUNDLE = os.path.join(ROOT, "tests", "upstream-2026-11-27", "bundle", "anip-bundle-e98c906.zip")
LOCAL_TAG = "aaaaaaa"
RELEASED_AT = 1795697528000          # 上游这份清单里的时间戳：2026-11-26
VECTOR_PKG, ALIAS_PKG = "com.local.vector", "com.local.alias"
ALIAS_TARGET = "com.ss.android.ugc.aweme"   # 官方包里 app 类别下已有的包
VECTOR_SVG = ('<svg xmlns="http://www.w3.org/2000/svg" width="72" height="72" viewBox="0 0 72 72">'
              '<circle cx="36" cy="36" r="27" fill="#e53935"/></svg>')


def png_bytes(tag):
    """真实可解码的小 PNG（48×48，按包名取色）；没有 PIL 时退化为「签名 + 任意字节」——
    解压器只认签名与 CRC/长度自洽，不要求能解码，同步测试照样成立。"""
    try:
        from PIL import Image
        img = Image.new("RGBA", (48, 48), (hash(tag) & 0xFF, 0x80, 0xC0, 0xFF))
        for x in range(8, 40):
            for y in range(20, 28):
                img.putpixel((x, y), (255, 255, 255, 255))
        out = io.BytesIO()
        img.save(out, format="PNG")
        return out.getvalue()
    except Exception:
        return b"\x89PNG\r\n\x1a\n" + zlib.compress(b"png-of-" + tag.encode()) + b" " * 64


def build_picp(path, pkgs):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("repo-main/README.md", "fixture")
        for p in pkgs:
            z.writestr("repo-main/themes/dark/icons/%s/1.png" % p, png_bytes(p))
    data = out.getvalue()
    if path.endswith(".zip"):
        with io.open(path, "wb") as f:
            f.write(data)
    return data


def read_real_bundle():
    """上游 2026-11-26 的官方资源包。拿不到就停：规则库的场景（d10b / d11 / d12）没有它跑不成。"""
    if not os.path.isfile(REAL_BUNDLE):
        sys.exit("need the upstream bundle at %s (see tests/upstream-2026-11-27/README.md)" % REAL_BUNDLE)
    with io.open(REAL_BUNDLE, "rb") as f:
        return f.read()


def build_anip_extra(path, real):
    """官方资源包 + 两个清单条目：彩色 SVG（自己有图，渲染时保留原色）和一个指向已有包的别名。"""
    with zipfile.ZipFile(io.BytesIO(real)) as src, zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as dst:
        for item in src.infolist():
            data = src.read(item.filename)
            if item.filename == "app/manifest.json":
                manifest = json.loads(data.decode("utf-8"))
                assert ALIAS_TARGET in manifest, "alias target is not in the app manifest any more"
                manifest[VECTOR_PKG] = {"label": {"en": "Suite vector"}, "format": "svg",
                                        "color": "#FFFFFF", "overlay": False, "contributors": "suite"}
                manifest[ALIAS_PKG] = {"target": ALIAS_TARGET, "label": {"en": "Suite alias"}}
                data = json.dumps(manifest).encode("utf-8")
            dst.writestr(item.filename, data)
        dst.writestr("app/res/%s.svg" % VECTOR_PKG, VECTOR_SVG)


def release_manifest():
    """官方发布通道的清单。badsha 模式下哈希故意对不上。"""
    name = "anip-bundle-%s.zip" % LOCAL_TAG
    data = FILES[name]
    digest = "0" * 64 if read_mode() == "badsha" else hashlib.sha256(data).hexdigest()
    return json.dumps({
        "schemaVersion": 1, "tag": LOCAL_TAG, "timestamp": RELEASED_AT,
        "assetName": name, "downloadUrl": name, "size": len(data), "sha256": digest,
    }).encode("utf-8")


def read_mode():
    try:
        with io.open(os.path.join(PAYLOADS, "mode.txt"), encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return "good"


def cut_at_entry_boundary(data):
    """切在最后一个条目的数据末尾 = 中央目录的起始偏移（EOCD 固定在末尾 22 字节）。"""
    eocd = len(data) - 22
    assert data[eocd:eocd + 4] == b"PK\x05\x06", "no EOCD at tail"
    off = struct.unpack("<I", data[eocd + 16:eocd + 20])[0]
    assert 0 < off < len(data)
    return data[:off]


def make_payloads():
    os.makedirs(PAYLOADS, exist_ok=True)
    with io.open(os.path.join(PAYLOADS, "plain.txt"), "wb") as f:
        f.write(b"<html><body>Service Unavailable</body></html>")
    build_picp(os.path.join(PAYLOADS, "nomatch.zip"), [])
    full = build_picp(os.path.join(PAYLOADS, "good.zip"), ["com.a", "com.b", "com.c"])
    with io.open(os.path.join(PAYLOADS, "truncated.zip"), "wb") as f:
        f.write(cut_at_entry_boundary(full))
    with io.open(os.path.join(PAYLOADS, "truncated-mid.zip"), "wb") as f:
        f.write(full[:len(full) // 2])
    # 慢速源要够大：每 200ms 发 2KB，4000 个条目约 0.4MB → 40 秒左右，够在中途点取消
    build_picp(os.path.join(PAYLOADS, "slow.zip"), ["com.slow%04d" % i for i in range(4000)])
    real = read_real_bundle()
    with io.open(os.path.join(PAYLOADS, "anip-bundle-%s.zip" % LOCAL_TAG), "wb") as f:
        f.write(real)
    build_anip_extra(os.path.join(PAYLOADS, "anip-extra.zip"), real)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        name = self.path.lstrip("/")
        ctype = "application/octet-stream"
        if name == "anip-release.json":
            data = release_manifest()
            ctype = "application/json"
        elif name in FILES:
            data = FILES[name]
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            if name == "slow.zip":
                # 每 200ms 发 2KB：整体要几十秒，足够在下载中途点取消
                for i in range(0, len(data), 2048):
                    self.wfile.write(data[i:i + 2048])
                    self.wfile.flush()
                    time.sleep(0.2)
            else:
                self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            sys.stderr.write("client disconnected: %s\n" % name)
        self.wfile.flush()

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (time.strftime("%H:%M:%S"), fmt % args))
        sys.stderr.flush()


FILES = {}


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 28765
    # 先绑定端口再生成样本：端口被占立刻报错，调用方也可以从这一刻起轮询
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    make_payloads()
    for n in sorted(os.listdir(PAYLOADS)):
        if not n.endswith(".zip") and n != "plain.txt":
            continue
        with io.open(os.path.join(PAYLOADS, n), "rb") as f:
            FILES[n] = f.read()
    full = FILES["good.zip"]
    assert FILES["truncated.zip"] == cut_at_entry_boundary(full)
    assert len(FILES["truncated-mid.zip"]) == len(full) // 2
    sys.stderr.write("payloads: %s\n" % {k: len(v) for k, v in sorted(FILES.items())})
    sys.stderr.write("serving on http://127.0.0.1:%d/ (emulator: http://10.0.2.2:%d/)\n" % (port, port))
    sys.stderr.flush()
    srv.serve_forever()


if __name__ == "__main__":
    main()
