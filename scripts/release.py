#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""OptIcon 发版工具（维护者本机使用）。

把「版本出包五步」做成逐段可核验的命令，避免手敲漏步：

  preflight  工作区干净 + 版本号/更新日志条目/文档同步/文案对齐 + 单测重跑 + lint（可与基线逐项对照）
  build      从当前提交构建 release 包；核对版本号、签名证书、不含调试专有内容；记录 SHA-256
  tag        在该提交上打 tag（本地）
  publish    推送提交与 tag → 建 Release（正文 = CHANGELOG 条目 + 手写的验证范围）→ 上传附件
  verify     不依赖前面步骤的状态，从远端重新读取并逐项核对
  notes      把某版本的 CHANGELOG 条目按 Release 正文的形态打印出来

用法：
  python scripts/release.py <preflight|build|tag|publish|verify|notes> [选项]
    --proxy http://127.0.0.1:10808   访问 GitHub 用的本地代理（默认）；--no-proxy 直连
    --lint-base <git-ref>            preflight 时与该提交的 lint 结果逐项对照
    --title "— 短标题"               publish 时附加在 Release 标题后
    --version <x.y.z>                仅 notes 子命令需要

硬约束（写在代码里，不靠人记）：
  - 工作区不干净就不 preflight / 不构建（先提交后构建）；
  - 构建出的包必须：版本号与版本代码与源码一致、签名证书是正式 keystore、不含调试专有内容；
  - 没有手写的 archive/release/<版本>/verification-scope.md 就不发布；
  - verify 只信从远端重新读回的数据。
令牌只从 `git credential fill` 取，只存在于进程内存：不落盘、不打印、不进命令行参数。
"""
import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime

# 项目根 = scripts/ 的上一级；OPTICON_ROOT 只用于从暂存副本里自测本脚本
ROOT = os.environ.get("OPTICON_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELEASE_CERT_SHA256 = "c478361f7b2c68c126d952a1d24c2fbe90758646aed39ac54b44b6bcdd4916f5"
API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"
GRADLE_ARGS = ["--no-configuration-cache"]

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")


# ───────────────────────── 基础设施 ─────────────────────────

class Fail(Exception):
    pass


def fail(msg):
    raise Fail(msg)


def sh(args, cwd=ROOT, check=True, capture=True, log=None, stdin=None):
    """运行命令。check=True 时非零退出即失败；log 给定时把输出写进日志、只在失败时回显结尾。"""
    out = None
    fh = None
    if log:
        fh = io.open(log, "wb")
        out = fh
    elif capture:
        out = subprocess.PIPE
    try:
        p = subprocess.run(args, cwd=cwd, stdout=out, stderr=subprocess.STDOUT if log else subprocess.PIPE,
                           input=stdin)
    except OSError as e:
        fail("无法运行 %s：%s" % (args[0], e))
    finally:
        if fh:
            fh.close()
    text = (p.stdout or b"").decode("utf-8", "replace") if capture else ""
    if check and p.returncode != 0:
        tail = text.strip().split("\n")[-12:] if not log else io.open(log, encoding="utf-8", errors="replace").read().strip().split("\n")[-12:]
        fail("命令失败（退出码 %d）：%s\n%s" % (p.returncode, " ".join(args), "\n".join(tail)))
    return p.returncode, text


def gradle(tasks, log, cwd=ROOT):
    """项目的 gradlew（wrapper 可用；CI 与本机同一条路径）。"""
    sh(["sh", "gradlew"] + tasks + GRADLE_ARGS, cwd=cwd, log=log)


def git(*args, **kw):
    return sh(["git"] + list(args), **kw)[1].strip()


def read_version():
    src = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    m = re.search(r'MODULE_VERSION_NAME\s*=\s*"([^"]+)"', src)
    c = re.search(r"versionCode\s*=\s*(\d+)", src)
    if not m or not c:
        fail("build.gradle.kts 里找不到 MODULE_VERSION_NAME / versionCode")
    return m.group(1), int(c.group(1))


def is_prerelease(version):
    return bool(re.search(r"-(alpha|beta|rc)", version))


def extract_notes(version, heading="#"):
    """CHANGELOG 里该版本条目的正文（标题行之后到下一个 '## [' 之前），小节标题统一成 '##'。"""
    lines = io.open(os.path.join(ROOT, "CHANGELOG.md"), encoding="utf-8").read().split("\n")
    pat = re.compile(r"^## \[%s\](\s+-\s*\S+)?\s*$" % re.escape(version))
    starts = [i for i, line in enumerate(lines) if pat.match(line)]
    if len(starts) != 1:
        fail("CHANGELOG 里 %s 的条目应当恰好一个，找到 %d 个" % (version, len(starts)))
    start = starts[0] + 1
    end = next((i for i in range(start, len(lines)) if re.match(r"^## \[", lines[i])), len(lines))
    body = lines[start:end]
    while body and not body[0].strip():
        body.pop(0)
    while body and not body[-1].strip():
        body.pop()
    if not body:
        fail("CHANGELOG 里 %s 的条目是空的" % version)
    return "\n".join(("%s %s" % (heading, m.group(1))) if (m := re.match(r"^#{1,5} (.*)$", line)) else line
                     for line in body) + "\n"


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def work_dir(version):
    d = os.path.join(ROOT, "archive", "release", version)
    os.makedirs(d, exist_ok=True)
    return d


def load(wd, name):
    p = os.path.join(wd, name + ".json")
    if not os.path.exists(p):
        fail("缺少 %s——先运行 %s" % (os.path.relpath(p, ROOT), {"preflight": "preflight", "build": "build"}.get(name, name)))
    return json.load(io.open(p, encoding="utf-8"))


def save(wd, name, data):
    with io.open(os.path.join(wd, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def require_clean():
    status = git("status", "--porcelain")
    if status:
        fail("工作区不干净——先把改动提交或收走（先提交后构建）：\n" + "\n".join(status.split("\n")[:10]))


def require_unchanged(record, what):
    head = git("rev-parse", "HEAD")
    if record.get("head") != head:
        fail("%s 记录的提交是 %s，当前 HEAD 是 %s——提交变了就要重新 preflight / build" % (what, record.get("head", "?")[:12], head[:12]))
    return head


def sdk_dir():
    for line in io.open(os.path.join(ROOT, "local.properties"), encoding="utf-8"):
        if line.startswith("sdk.dir="):
            return line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
    fail("local.properties 里没有 sdk.dir")


def build_tool(name):
    """SDK build-tools 里最高版本下的某个工具。"""
    base = os.path.join(sdk_dir(), "build-tools")
    versions = sorted((v for v in os.listdir(base) if re.match(r"^\d+\.\d+\.\d+$", v)),
                      key=lambda v: [int(x) for x in v.split(".")])
    for v in reversed(versions):
        for candidate in (name + ".exe", name + ".bat", name):
            p = os.path.join(base, v, candidate)
            if os.path.exists(p):
                return p
    fail("SDK build-tools 里找不到 %s" % name)


# ───────────────────────── GitHub ─────────────────────────

def repo_slug():
    url = git("remote", "get-url", "origin")
    m = re.search(r"github\.com[:/]([^/]+/[^/]+?)(\.git)?$", url)
    if not m:
        fail("origin 不是 GitHub 仓库：%s" % url)
    return m.group(1)


def credential_token():
    """令牌只在本进程内存里。"""
    p = subprocess.run(["git", "credential", "fill"], cwd=ROOT, input="protocol=https\nhost=github.com\n\n",
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    out = p.stdout.decode("utf-8", "replace")
    for line in out.splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    fail("从 git credential fill 取不到 GitHub 令牌")


def api(method, path, token=None, json_body=None, raw=None, base=API, timeout=90, allow_404=False):
    """GitHub API。json_body 是请求体的 JSON 对象；raw 是二进制请求体（上传附件）。
    allow_404=True 时 404 返回 (404, {}) 而不是失败。"""
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "opticon-release",
               "X-GitHub-Api-Version": "2022-11-28"}
    data = None
    if token:
        headers["Authorization"] = "Bearer " + token
    if json_body is not None:
        data = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    elif raw is not None:
        data = raw
        headers["Content-Type"] = "application/vnd.android.package-archive"
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler(OPENER_PROXIES))
    try:
        with opener.open(req, timeout=timeout) as r:
            text = r.read().decode("utf-8", "replace")
            return r.status, (json.loads(text) if text.strip() else {})
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", "replace")
        if e.code == 404 and allow_404:
            return 404, {}
        fail("GitHub %s %s → HTTP %d：%s" % (method, path, e.code, text[:300]))
    except urllib.error.URLError as e:
        fail("连不上 GitHub（%s）：%s" % (base, e.reason))


OPENER_PROXIES = {}


def api_download(url, dest, timeout=600):
    """公开仓库的附件直链不需要令牌——绝不把令牌带给跳转后的下载域名。"""
    req = urllib.request.Request(url, headers={"User-Agent": "opticon-release"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler(OPENER_PROXIES))
    try:
        with opener.open(req, timeout=timeout) as r, open(dest, "wb") as f:
            shutil.copyfileobj(r, f)
    except urllib.error.URLError as e:
        fail("下载附件失败：%s" % e.reason)
    return sha256_of(dest)


# ───────────────────────── preflight ─────────────────────────

def parse_test_results():
    total = failed = skipped = 0
    d = os.path.join(ROOT, "app", "build", "test-results", "testDebugUnitTest")
    files = [f for f in os.listdir(d) if f.startswith("TEST-") and f.endswith(".xml")] if os.path.isdir(d) else []
    if not files:
        fail("没有测试结果（%s）——测试任务没跑？" % d)
    for f in files:
        suite = ET.parse(os.path.join(d, f)).getroot()
        total += int(suite.get("tests", 0))
        failed += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
        skipped += int(suite.get("skipped", 0))
    return {"total": total, "failed": failed, "skipped": skipped}


def parse_lint(path):
    counts = {"Fatal": 0, "Error": 0, "Warning": 0, "Hint": 0, "Information": 0}
    if not os.path.exists(path):
        fail("没有 lint 报告（%s）" % path)
    for issue in ET.parse(path).getroot().iter("issue"):
        sev = issue.get("severity")
        counts[sev] = counts.get(sev, 0) + 1
    return counts


def lint_vs_base(ref):
    """把 ref 导出到临时目录跑一次 lint，与当前结果逐项对照（只报新增/消失的条目）。

    用 git archive 导出而不是 worktree：不改动本仓库的元数据，导出目录用完即删。"""
    if not re.match(r"^[0-9A-Za-z._/^-]+$", ref):
        fail("lint 基线不是一个合法的 git 引用：%r" % ref)
    sys.path.insert(0, os.path.join(ROOT, "scripts"))
    import lint_diff
    tmp = os.path.join(ROOT, "archive", "release", "_lintbase")
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp)
    # ref 先解析成完整提交号再拼进 shell 命令
    resolved = git("rev-parse", "--verify", ref + "^{commit}")
    sh(["bash", "-c", "git archive %s | tar -x -C '%s'" % (resolved, tmp.replace("\\", "/"))])
    shutil.copy(os.path.join(ROOT, "local.properties"), tmp)
    gradle([":app:lintDebug"], os.path.join(tmp, "lint.log"), cwd=tmp)
    base = lint_diff.load(os.path.join(tmp, "app", "build", "reports", "lint-results-debug.xml"))
    cur = lint_diff.load(os.path.join(ROOT, "app", "build", "reports", "lint-results-debug.xml"))
    shutil.rmtree(tmp, ignore_errors=True)
    return {"new": [["%s %s %s" % k, n] for k, n in sorted((cur - base).items())],
            "gone": [["%s %s %s" % k, n] for k, n in sorted((base - cur).items())]}


def cmd_preflight(args):
    require_clean()
    head, branch = git("rev-parse", "HEAD"), git("rev-parse", "--abbrev-ref", "HEAD")
    version, code = read_version()
    wd = work_dir(version)
    print("版本 v%s / versionCode %d  ·  %s@%s" % (version, code, branch, head[:12]))
    notes = extract_notes(version)
    _, sync = sh(["bash", "scripts/check_doc_sync.sh"])
    if "SYNC PASS" not in sync:
        fail("文档同步检查没有 PASS：\n" + sync)
    sh([sys.executable, "scripts/check_strings.py"])
    gradle([":app:testDebugUnitTest", "--rerun-tasks"], os.path.join(wd, "preflight-test.log"))
    tests = parse_test_results()
    if tests["failed"] or tests["skipped"]:
        fail("测试未全绿：%s" % tests)
    gradle([":app:lintDebug"], os.path.join(wd, "preflight-lint.log"))
    lint = parse_lint(os.path.join(ROOT, "app", "build", "reports", "lint-results-debug.xml"))
    if lint["Fatal"] or lint["Error"]:
        fail("lint 有 Error：%s" % lint)
    diff = lint_vs_base(args.lint_base) if args.lint_base else None
    save(wd, "preflight", {
        "version": version, "versionCode": code, "head": head, "branch": branch,
        "prerelease": is_prerelease(version), "tests": tests, "lint": lint, "lint_diff": diff,
        "doc_sync": True, "strings": True, "notes_first_line": notes.split("\n")[0],
        "at": datetime.now().isoformat(timespec="seconds"),
    })
    print("单测 %d 项（重跑）0 失败 0 跳过  ·  lint 0 Error / %d Warning / %d Hint  ·  文档同步 PASS  ·  文案对齐 PASS"
          % (tests["total"], lint["Warning"], lint["Hint"]))
    if diff:
        print("lint 对基线 %s：新增 %d 项，消失 %d 项" % (args.lint_base, len(diff["new"]), len(diff["gone"])))
        for item, n in diff["new"]:
            print("  +%d %s" % (n, item))
    print("preflight 通过。")


# ───────────────────────── build / tag ─────────────────────────

def cmd_build(args):
    pf = load(work_dir(read_version()[0]), "preflight")
    require_clean()
    head = require_unchanged(pf, "preflight")
    version, code = pf["version"], pf["versionCode"]
    wd = work_dir(version)
    # 先 clean：输出目录里只留这一次的产物（APK 名带构建时间，重复构建会越积越多，没法认出哪个是新的）
    gradle([":app:clean", ":app:assembleRelease"], os.path.join(wd, "build.log"))
    out_dir = os.path.join(ROOT, "app", "build", "outputs", "apk", "release")
    apks = [f for f in os.listdir(out_dir) if f.endswith(".apk")] if os.path.isdir(out_dir) else []
    if len(apks) != 1 or ("v%s-" % version) not in apks[0]:
        fail("release 输出目录应当恰好一个带本版本号的 APK，实际：%s" % apks)
    src = os.path.join(out_dir, apks[0])
    dest = os.path.join(wd, apks[0])
    sha = sha256_of(src)
    history = []
    old = os.path.join(wd, "build.json")
    if os.path.exists(old):
        prev = json.load(io.open(old, encoding="utf-8"))
        if prev.get("head") != head:
            fail("archive 里已有另一个提交的构建记录（%s）——同一版本号换了提交，先确认版本号是否该递增"
                 % prev.get("head", "?")[:12])
        history = prev.get("history", []) + [{"apk": prev["apk"], "sha256": prev["sha256"], "at": prev["built_at"]}]
    shutil.copy2(src, dest)
    if sha256_of(dest) != sha:
        fail("复制产物后哈希不一致")
    badging, _ = sh([build_tool("aapt2"), "dump", "badging", dest])
    if ("versionName='%s'" % version) not in badging or ("versionCode='%d'" % code) not in badging:
        fail("APK 里的版本号与源码不一致：\n" + "\n".join(l for l in badging.split("\n") if "version" in l))
    manifest, _ = sh([build_tool("aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", dest])
    if "E2ETestReceiver" in manifest:
        fail("release 包里带有调试专有的测试接收器")
    for line in manifest.split("\n"):
        if "usesCleartextTraffic" in line and "0xffffffff" in line:
            fail("release 包允许明文流量（应只在调试包里）：\n" + line.strip())
    certs, _ = sh([build_tool("apksigner"), "verify", "--print-certs", dest])
    m = re.search(r"certificate SHA-256 digest:\s*([0-9a-f]+)", certs)
    if not m or m.group(1).lower() != RELEASE_CERT_SHA256:
        fail("签名证书不是正式 keystore（可能回退成了 debug 签名）：\n" + certs.strip()[-300:])
    save(wd, "build", {"version": version, "versionCode": code, "head": head, "apk": apks[0], "sha256": sha,
                       "size": os.path.getsize(dest), "cert": m.group(1).lower(), "built_at":
                       datetime.now().isoformat(timespec="seconds"), "history": history})
    print("已构建 %s\nSHA-256 %s\n大小 %d 字节  ·  版本号/版本代码一致  ·  正式签名  ·  不含调试专有内容"
          % (apks[0], sha, os.path.getsize(dest)))
    for h in history:
        print("（顶替了同提交的早先构建：%s %s）" % (h["apk"], h["sha256"][:16]))


def cmd_tag(args):
    b = load(work_dir(read_version()[0]), "build")
    require_clean()
    head = require_unchanged(b, "build")
    tag = "v" + b["version"]
    rc, existing = sh(["git", "rev-parse", "--verify", "-q", "refs/tags/" + tag], check=False)
    if rc == 0:
        if existing.strip() != head:
            fail("tag %s 已存在且指向别的提交（%s）——不自动移动 tag" % (tag, existing.strip()[:12]))
        print("tag %s 已在 %s" % (tag, head[:12]))
        return
    git("tag", tag, head)
    print("已打 tag %s → %s" % (tag, head[:12]))


# ───────────────────────── publish / verify ─────────────────────────

def push_with_proxy_fallback(args, refs):
    for extra in ([["-c", "http.proxy=" + args.proxy]] if args.proxy else []) + [[]]:
        rc, out = sh(["git"] + extra + ["push", "origin"] + refs, check=False, capture=False)
        if rc == 0:
            return
        sys.stderr.write("推送失败（%s），%s\n" % ("经代理" if extra else "直连", "换直连重试" if extra else "放弃"))
    fail("推送失败")


def cmd_publish(args):
    b = load(work_dir(read_version()[0]), "build")
    pf = load(work_dir(read_version()[0]), "preflight")
    require_clean()
    require_unchanged(b, "build")
    tag = "v" + b["version"]
    scope_path = os.path.join(work_dir(b["version"]), "verification-scope.md")
    if not os.path.exists(scope_path) or not io.open(scope_path, encoding="utf-8").read().strip():
        fail("缺少手写的验证范围 %s——发布前必须如实写明哪些在设备上验证过、哪些只有单元测试、哪些没验证"
             % os.path.relpath(scope_path, ROOT))
    scope = io.open(scope_path, encoding="utf-8").read().strip()
    body = extract_notes(b["version"], "##") + "\n## 验证范围\n\n" + scope + "\n\n## 附件与校验\n\n- %s\n- SHA-256: %s\n" % (
        b["apk"], b["sha256"])
    name = "v%s (versionCode %d)%s" % (b["version"], b["versionCode"], (" " + args.title) if args.title else "")
    plan = "Release「%s」 tag=%s prerelease=%s 正文 %d 行 附件 %s（%s）" % (
        name, tag, pf["prerelease"], len(body.split("\n")), b["apk"], b["sha256"][:16])
    if args.dry_run:
        print("[dry-run] 将推送 %s 与 %s，并创建：%s" % (b.get("branch") or pf.get("branch"), tag, plan))
        return
    OPENER_PROXIES.update({"http": args.proxy, "https": args.proxy} if args.proxy else {})
    slug = repo_slug()
    push_with_proxy_fallback(args, [pf["branch"], tag])
    token = credential_token()
    wanted = {"name": name, "body": body, "draft": False, "prerelease": pf["prerelease"]}
    status, rel = api("GET", "/repos/%s/releases/tags/%s" % (slug, tag), token, allow_404=True)
    differs = bool(rel) and any(rel.get(k) != wanted[k] for k in wanted)
    if status == 404:
        # tag 已在远端：不传 target_commitish（短 SHA 会被判 422），让 API 复用既有 tag
        _, rel = api("POST", "/repos/%s/releases" % slug, token, dict(wanted, tag_name=tag))
    elif rel.get("assets"):
        # 已经完整发布过：不自动改写已发布的页面
        if differs:
            fail("该版本的 Release 已发布且内容与现在不同——已发布的页面不自动改写；要更正请在页面上追加勘误")
        print("Release 已存在且内容一致，跳过建页")
    else:
        # 上次发布在建页之后、传附件之前中断：续跑，把页面修成预期内容
        if differs:
            _, rel = api("PATCH", "/repos/%s/releases/%d" % (slug, rel["id"]), token, wanted)
        print("接续上次中断的发布：页面已在，继续上传附件")
    same = [a for a in rel.get("assets", []) if a["name"] == b["apk"]]
    if same:
        if (same[0].get("digest") or "") != "sha256:" + b["sha256"]:
            fail("Release 上已有同名附件但哈希不同——发布后不覆盖同名产物；请递增版本号或构建标识后重发")
        asset = same[0]
        print("附件已在 Release 上（哈希一致），跳过上传")
    else:
        if rel.get("assets"):
            fail("Release 上已经有别的附件：%s——附件契约是仅一个 APK" % ", ".join(a["name"] for a in rel["assets"]))
        _, asset = api("POST", "/repos/%s/releases/%d/assets?name=%s" % (slug, rel["id"], b["apk"]), token,
                       raw=open(os.path.join(work_dir(b["version"]), b["apk"]), "rb").read(), base=UPLOADS, timeout=600)
    save(work_dir(b["version"]), "publish", {"release_id": rel["id"], "html_url": rel.get("html_url"),
                                            "asset_id": asset.get("id"), "at": datetime.now().isoformat(timespec="seconds")})
    print("已发布：%s" % rel.get("html_url"))


def cmd_verify(args):
    # --version 可指向别的版本：拿已发布的旧版本当已知答案，检验核验逻辑本身
    wd = work_dir(args.version or read_version()[0])
    b = load(wd, "build")
    pf = load(wd, "preflight")
    tag, apk = "v" + b["version"], b["apk"]
    local = os.path.join(wd, apk)
    OPENER_PROXIES.update({"http": args.proxy, "https": args.proxy} if args.proxy else {})
    slug = repo_slug()
    rows = []

    def check(label, ok, detail=""):
        rows.append((label, bool(ok), detail))

    extra = ["-c", "http.proxy=" + args.proxy] if args.proxy else []
    rc, out = sh(["git"] + extra + ["ls-remote", "origin", "refs/tags/" + tag], check=False)
    if rc != 0 and args.proxy:
        rc, out = sh(["git", "ls-remote", "origin", "refs/tags/" + tag], check=False)
    remote_sha = out.split()[0] if rc == 0 and out.split() else ""
    check("远端 tag 指向构建提交", remote_sha == b["head"] and len(remote_sha) == 40,
          "%s vs %s" % (remote_sha or "（取不到）", b["head"]))

    # 核验只读公开数据，不带令牌——换个环境也能跑
    _, rel = api("GET", "/repos/%s/releases/tags/%s" % (slug, tag))
    check("Release 不是草稿", not rel.get("draft"))
    check("预发布标记与版本后缀一致", rel.get("prerelease") == pf["prerelease"], "prerelease=%s" % rel.get("prerelease"))
    check("标题含版本号与版本代码", (rel.get("name") or "").startswith("v%s (versionCode %d)" % (b["version"], b["versionCode"])),
          rel.get("name"))
    body = rel.get("body") or ""
    check("正文含附件哈希与验证范围", b["sha256"] in body and "## 验证范围" in body)
    check("正文与 CHANGELOG 条目一致", extract_notes(b["version"], "##").strip() in body)
    assets = rel.get("assets", [])
    check("附件恰好一个且文件名一致", len(assets) == 1 and assets[0]["name"] == apk,
          ", ".join("%s(%d)" % (a["name"], a.get("size", 0)) for a in assets))
    if assets:
        a = assets[0]
        check("附件大小与本地一致", a.get("size") == b["size"], "%s vs %d" % (a.get("size"), b["size"]))
        check("附件摘要与本地哈希一致", (a.get("digest") or "") == "sha256:" + b["sha256"], a.get("digest"))
        tmp = os.path.join(wd, "_verify-download.apk")
        try:
            check("重新下载附件逐字节比对", api_download(a["browser_download_url"], tmp) == b["sha256"])
        finally:
            if os.path.exists(tmp):
                os.remove(tmp)
    _, others = api("GET", "/repos/%s/releases?per_page=40" % slug)
    broken = [r["tag_name"] for r in others if r["tag_name"] != tag and not r.get("draft") and not r.get("assets")]
    check("旧版本的下载入口完整", not broken, "缺附件：" + ", ".join(broken) if broken else "%d 个旧版本均有附件" % (len(others) - 1))
    stable = [r for r in others if not r.get("draft") and not r.get("prerelease")]
    latest = max(stable, key=lambda r: r["id"])["tag_name"] if stable else "（无）"
    check("Latest 仍是非预发布的最新正式版", (not pf["prerelease"]) or latest != tag, "Latest=%s" % latest)
    width = max(len(label) for label, _, _ in rows)
    for label, ok, detail in rows:
        print("%s  %s%s" % ("PASS" if ok else "FAIL", label, ("  · " + detail) if detail else ""))
    bad = [r for r in rows if not r[1]]
    save(wd, "verify", {"at": datetime.now().isoformat(timespec="seconds"), "rows": rows})
    if bad:
        fail("核验有 %d 项失败：%s" % (len(bad), "；".join(r[0] for r in bad)))
    print("核验 %d 项全部通过。" % len(rows))


def main():
    ap = argparse.ArgumentParser(description="OptIcon 发版工具")
    ap.add_argument("command", choices=["preflight", "build", "tag", "publish", "verify", "notes"])
    ap.add_argument("--proxy", default="http://127.0.0.1:10808", help="访问 GitHub 的本地代理")
    ap.add_argument("--no-proxy", action="store_true", help="直连")
    ap.add_argument("--lint-base", help="preflight 时与该 git 引用的 lint 结果逐项对照")
    ap.add_argument("--title", help="publish 时附加在 Release 标题后")
    ap.add_argument("--version", help="notes / verify 子命令：要处理的版本号（默认取 build.gradle.kts 里的当前版本）")
    ap.add_argument("--dry-run", action="store_true", help="publish：只打印计划")
    args = ap.parse_args()
    if args.no_proxy:
        args.proxy = None
    if args.command == "notes":
        sys.stdout.write(extract_notes(args.version or read_version()[0], "##"))
        return
    {"preflight": cmd_preflight, "build": cmd_build, "tag": cmd_tag,
     "publish": cmd_publish, "verify": cmd_verify}[args.command](args)


if __name__ == "__main__":
    try:
        main()
    except Fail as e:
        sys.stderr.write("失败：%s\n" % e)
        sys.exit(1)
