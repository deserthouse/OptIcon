# -*- coding: utf-8 -*-
"""看 CI 的结果。整次运行的 conclusion 不够用——发版门禁是「首个 job 失败则自动换运行器重试一次」，
要逐个 job 看结论（见项目 AGENTS.md「放行标准」）。

用法（项目根目录）：
  python scripts/ci_status.py [提交或分支或标签，默认 main] [--wait 分钟] [--all-runs]
      列出该提交的 CI 运行，逐个 job 给出结论；有失败的 job 列出失败步骤。
      --wait N     等到这些运行结束为止（最多 N 分钟）
      --all-runs   连 diagnose 等其它流程的运行一起列（默认只看发版门禁 ci.yml）
  python scripts/ci_status.py --logs <运行 id> <输出目录>
      把这次运行各 job 的日志存到目录里
  python scripts/ci_status.py --rerun <运行 id>
      重跑这次运行里失败的 job

退出码：0 = 列出的 job 全部成功或跳过；1 = 有失败；2 = 还没跑完或参数有问题。
令牌只从 `git credential fill` 取（同 scripts/release.py），只交给 api.github.com；
取日志时拿到跳转地址后不带令牌去下载。"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import release  # noqa: E402

GATE_WORKFLOW = "ci.yml"
PROXY = "http://127.0.0.1:10808"   # 访问 GitHub 走本地代理；--no-proxy 直连


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def token(no_proxy):
    if not no_proxy:
        release.OPENER_PROXIES.update({"http": PROXY, "https": PROXY})
    return release.credential_token()


def resolve_sha(ref):
    if re.fullmatch(r"[0-9a-f]{40}", ref):
        return ref
    p = subprocess.run(["git", "rev-parse", "--verify", "-q", ref + "^{commit}"], cwd=release.ROOT,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode != 0:
        release.fail("解析不了 %r（提交、分支或标签）" % ref)
    return p.stdout.decode().strip()


def list_runs(tok, sha, everything):
    path = "/repos/%s/actions/runs?head_sha=%s&per_page=30" % (release.repo_slug(), sha)
    _, data = release.api("GET", path, tok)
    runs = data.get("workflow_runs", [])
    return runs if everything else [r for r in runs if r.get("path", "").endswith("/" + GATE_WORKFLOW)]


def describe_run(tok, run):
    _, jobs = release.api("GET", "/repos/%s/actions/runs/%d/jobs" % (release.repo_slug(), run["id"]), tok)
    name = (run.get("path") or "").rsplit("/", 1)[-1]
    print("run %s  %s  event=%s ref=%s  %s / %s  attempt=%s  %s" % (
        run["id"], name, run["event"], run.get("head_branch"), run["status"], run["conclusion"],
        run.get("run_attempt"), run.get("created_at")))
    done, ok = run["status"] == "completed", True
    for job in jobs["jobs"]:
        failed = [s["name"] for s in job.get("steps") or [] if s.get("conclusion") == "failure"]
        good = job["conclusion"] in ("success", "skipped")
        ok = ok and good
        print("   job %-16s %s%s" % (job["name"], job["conclusion"],
                                     ("  ← 失败步骤：" + "；".join(failed)) if failed else ""))
    return done, ok


def download_logs(tok, run_id, out_dir):
    _, jobs = release.api("GET", "/repos/%s/actions/runs/%s/jobs" % (release.repo_slug(), run_id), tok)
    os.makedirs(out_dir, exist_ok=True)
    plain = urllib.request.build_opener(urllib.request.ProxyHandler(release.OPENER_PROXIES))
    for job in jobs["jobs"]:
        req = urllib.request.Request(release.API + "/repos/%s/actions/jobs/%d/logs" % (release.repo_slug(), job["id"]),
                                     headers={"Authorization": "Bearer " + tok, "Accept": "application/vnd.github+json",
                                              "User-Agent": "opticon-release"})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler(release.OPENER_PROXIES), NoRedirect())
        try:
            opener.open(req, timeout=90)
            print("job %s：日志没有跳转地址，跳过" % job["name"])
            continue
        except urllib.error.HTTPError as e:
            if e.code not in (301, 302, 303, 307) or not (e.headers.get("Location") or "").startswith("http"):
                print("job %s：取不到日志（HTTP %s）" % (job["name"], e.code))   # 被跳过的 job 没有日志
                continue
            loc = e.headers["Location"]
        dest = os.path.join(out_dir, "job-%s.log" % job["id"])
        try:
            with plain.open(urllib.request.Request(loc, headers={"User-Agent": "opticon-release"}), timeout=120) as resp, \
                    open(dest, "wb") as f:
                f.write(resp.read())
        except urllib.error.HTTPError as e:   # 被跳过的 job：有跳转地址但日志不存在
            if os.path.exists(dest) and os.path.getsize(dest) == 0:
                os.remove(dest)
            print("job %s（%s）：日志不可用（HTTP %s）" % (job["name"], job["conclusion"], e.code))
            continue
        print("job %s（%s）：%s" % (job["name"], job["conclusion"], dest))


def main():
    ap = argparse.ArgumentParser(description="看 CI 结果（逐个 job）")
    ap.add_argument("ref", nargs="?", default="main", help="提交、分支或标签")
    ap.add_argument("--wait", type=float, default=0, help="等到运行结束，最多这么多分钟")
    ap.add_argument("--all-runs", action="store_true", help="连其它流程的运行一起列")
    ap.add_argument("--logs", nargs=2, metavar=("RUN_ID", "DIR"), help="下载某次运行各 job 的日志")
    ap.add_argument("--rerun", metavar="RUN_ID", help="重跑某次运行里失败的 job")
    ap.add_argument("--no-proxy", action="store_true", help="直连 GitHub")
    args = ap.parse_args()
    tok = token(args.no_proxy)
    if args.logs:
        download_logs(tok, args.logs[0], args.logs[1])
        return 0
    if args.rerun:
        status, _ = release.api("POST", "/repos/%s/actions/runs/%s/rerun-failed-jobs" % (release.repo_slug(), args.rerun), tok)
        print("已提交重跑（HTTP %d）" % status)
        return 0
    sha = resolve_sha(args.ref)
    deadline = time.time() + args.wait * 60
    while True:
        runs = list_runs(tok, sha, args.all_runs)
        states = [describe_run(tok, run) for run in runs]
        if all(done for done, _ in states) or time.time() >= deadline:
            break
        print("…还没跑完，20 秒后再看")
        time.sleep(20)
    if not runs:
        print("这个提交还没有 CI 运行")
        return 2
    if not all(done for done, _ in states):
        return 2
    green = all(ok for _, ok in states)
    print("RESULT:", "ALL GREEN" if green else "有 job 失败")
    return 0 if green else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except release.Fail as e:
        sys.stderr.write("失败：%s\n" % e)
        sys.exit(2)
