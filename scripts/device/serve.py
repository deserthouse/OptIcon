"""设备验证套件用的本机 HTTP 源（run_suite.sh 启动，用完即停）。模拟器经 http://10.0.2.2:<port>/ 访问。

端点：
  /plain.txt        纯文本（代理返回错误页的形态）
  /nomatch.zip      合法 ZIP，但没有任何 themes/…/icons/{pkg}/1.png
  /truncated.zip    合法 PICP 归档，在最后一个条目的数据末尾被截断（条目边界切点）
  /truncated-mid.zip 同一份归档，从数据中间截断
  /slow.zip         合法 PICP 归档，但发送得很慢（每 200ms 一小块）——测同步中途取消
  /good.zip         合法的小 PICP 归档（几个包），正常速度
用法：python scripts/device/serve.py [port]（默认 28765；8765 在本机不可绑定）
"""
import io
import os
import struct
import sys
import time
import zipfile
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
PAYLOADS = os.path.join(HERE, "payloads")


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


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        name = self.path.lstrip("/")
        if name not in FILES:
            self.send_error(404)
            return
        data = FILES[name]
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
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


FILES = {}


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 28765
    # 先绑定端口再生成样本：端口被占立刻报错，调用方也可以从这一刻起轮询
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    make_payloads()
    for n in sorted(os.listdir(PAYLOADS)):
        with io.open(os.path.join(PAYLOADS, n), "rb") as f:
            FILES[n] = f.read()
    full = FILES["good.zip"]
    assert FILES["truncated.zip"] == cut_at_entry_boundary(full)
    assert len(FILES["truncated-mid.zip"]) == len(full) // 2
    sys.stderr.write("payloads: %s\n" % {k: len(v) for k, v in sorted(FILES.items())})
    sys.stderr.write("serving on http://127.0.0.1:%d/ (emulator: http://10.0.2.2:%d/)\n" % (port, port))
    srv.serve_forever()


if __name__ == "__main__":
    main()
