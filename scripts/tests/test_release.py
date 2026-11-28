# -*- coding: utf-8 -*-
"""release.py 的单测：build / tag 子命令的接线，「构建记录顶替谁」的判定，远端 tag 查询（批 MT M1）。

跑法（项目根目录）：python -m unittest discover -s scripts/tests -v
不发网络请求、不跑 Gradle：子命令里的 sh / gradle / build-tool 换成记录器；远端 tag 查询另用本地裸仓库充当。
"""
import argparse
import contextlib
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import release  # noqa: E402

HEAD_A, HEAD_B = "a" * 40, "b" * 40


def record(head, apk="OptIcon-v0.20.1-beta-20261128-010203.apk", history=None):
    return {"version": "0.20.1-beta", "versionCode": 86, "head": head, "apk": apk, "sha256": "f" * 64,
            "size": 1, "cert": "c", "built_at": "2026-11-28T01:02:03", "history": history or []}


class ReplacementHistoryTest(unittest.TestCase):

    def test_no_record_yields_empty_history(self):
        self.assertEqual([], release.replacement_history(None, HEAD_A, "0.20.1-beta", False))

    def test_an_earlier_build_of_the_same_commit_is_replaced_without_asking(self):
        older = {"apk": "old.apk", "sha256": "1" * 64, "at": "2026-11-27T00:00:00", "head": "c" * 40}
        prev = record(HEAD_A, history=[older])

        history = release.replacement_history(prev, HEAD_A, "0.20.1-beta", False, remote_has_tag=self.fail)

        self.assertEqual([older, {"apk": prev["apk"], "sha256": prev["sha256"], "at": prev["built_at"], "head": HEAD_A}], history)

    def test_another_commit_is_refused_by_default(self):
        with self.assertRaisesRegex(release.Fail, "另一个提交"):
            release.replacement_history(record(HEAD_B), HEAD_A, "0.20.1-beta", False, remote_has_tag=self.fail)

    def test_another_commit_is_replaced_only_when_the_remote_has_no_tag(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            history = release.replacement_history(record(HEAD_B), HEAD_A, "0.20.1-beta", True,
                                                  remote_has_tag=lambda tag, proxy=None: (self.assertEqual("v0.20.1-beta", tag) or False))
        self.assertEqual([{"apk": record(HEAD_B)["apk"], "sha256": "f" * 64, "at": "2026-11-28T01:02:03", "head": HEAD_B}], history)
        self.assertIn(record(HEAD_B)["apk"], out.getvalue())
        self.assertIn("b" * 12, out.getvalue())

    def test_a_version_that_is_already_tagged_on_the_remote_is_never_replaced(self):
        with self.assertRaisesRegex(release.Fail, "远端已有 v0.20.1-beta"):
            release.replacement_history(record(HEAD_B), HEAD_A, "0.20.1-beta", True, remote_has_tag=lambda tag, proxy=None: True)

    def test_an_unanswerable_remote_refuses_too(self):
        def lost(tag, proxy=None):
            raise release.Fail("查不到远端的 tag")
        with self.assertRaisesRegex(release.Fail, "查不到远端的 tag"):
            release.replacement_history(record(HEAD_B), HEAD_A, "0.20.1-beta", True, remote_has_tag=lost)


class RemoteTagTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.origin = os.path.join(self.tmp, "origin.git")
        self.clone = os.path.join(self.tmp, "clone")
        subprocess.check_call(["git", "init", "--bare", "-q", self.origin])
        subprocess.check_call(["git", "clone", "-q", self.origin, self.clone])
        with open(os.path.join(self.clone, "README.md"), "w", encoding="utf-8", newline="\n") as f:
            f.write("x\n")
        git = lambda *a: subprocess.check_call(["git", "-C", self.clone] + list(a))  # noqa: E731
        git("add", "README.md")
        git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "init")
        git("tag", "v0.19.7-beta")
        git("push", "-q", "origin", "v0.19.7-beta")
        self.saved_root = release.ROOT
        release.ROOT = self.clone

    def tearDown(self):
        release.ROOT = self.saved_root

    def test_the_remote_is_asked_for_exactly_that_tag(self):
        self.assertTrue(release.remote_has_tag("v0.19.7-beta"))
        self.assertFalse(release.remote_has_tag("v0.20.0"))

    def test_a_remote_that_cannot_be_reached_stops_the_build(self):
        subprocess.check_call(["git", "-C", self.clone, "remote", "set-url", "origin", self.tmp])
        with self.assertRaisesRegex(release.Fail, "查不到远端的 tag"):
            release.remote_has_tag("v0.20.0")


class BuildRecordShapeTest(unittest.TestCase):
    """build.json 是 verify / publish 的输入：顶替之后新记录的形态不变，history 旧在前。"""

    def test_history_entries_carry_the_commit_they_belong_to(self):
        prev = record(HEAD_B, history=[{"apk": "older.apk", "sha256": "2" * 64, "at": "t0", "head": "d" * 40}])
        with contextlib.redirect_stdout(io.StringIO()):
            history = release.replacement_history(prev, HEAD_A, "0.20.1-beta", True,
                                                  remote_has_tag=lambda tag, proxy=None: False)
        self.assertEqual(["d" * 40, HEAD_B], [h["head"] for h in history])
        json.dumps(history)  # 可落盘


NEW_APK = "OptIcon-v0.20.1-beta-20261128-281200.apk"


class BuildCommandStub:
    """把 build / tag 子命令依赖的外部世界换成记录器：ROOT 是临时目录，sh / gradle / build_tool 都不真的执行。"""

    PROXY = "http://127.0.0.1:1"

    def __init__(self, test):
        self.test = test
        self.tmp = tempfile.mkdtemp()
        self.wd = os.path.join(self.tmp, "archive", "release", "0.20.1-beta")
        os.makedirs(os.path.join(self.tmp, "app"))
        os.makedirs(self.wd)
        with open(os.path.join(self.tmp, "app", "build.gradle.kts"), "w", encoding="utf-8") as f:
            f.write('val MODULE_VERSION_NAME = "0.20.1-beta"\nandroid { defaultConfig { versionCode = 86 } }\n')
        self.calls, self.gradle_calls = [], []
        self.status, self.remote_rc, self.remote_out, self.local_tag = "", 0, "", ""
        self.saved = (release.ROOT, release.sh, release.gradle, release.build_tool)
        release.ROOT, release.sh, release.gradle = self.tmp, self.fake_sh, self.fake_gradle
        release.build_tool = lambda name: "/fake/" + name

    def restore(self):
        release.ROOT, release.sh, release.gradle, release.build_tool = self.saved

    def save_json(self, name, data):
        with open(os.path.join(self.wd, name + ".json"), "w", encoding="utf-8") as f:
            json.dump(data, f)

    def load_json(self, name):
        with open(os.path.join(self.wd, name + ".json"), encoding="utf-8") as f:
            return json.load(f)

    def fake_sh(self, args, cwd=None, check=True, capture=True, log=None, stdin=None):
        self.calls.append(list(args))
        if args[0] == "git":
            if args[1:] == ["rev-parse", "HEAD"]:
                return 0, HEAD_A + "\n"
            if args[1] == "status" and args[2] == "--porcelain":
                return 0, self.status
            if "ls-remote" in args:
                return self.remote_rc, self.remote_out
            if args[1:] == ["rev-parse", "--verify", "-q", "refs/tags/v0.20.1-beta"]:
                return (0, self.local_tag) if self.local_tag else (1, "")
            if args[1] == "tag":
                return 0, ""
        if args[0] == "/fake/aapt2" and "badging" in args:
            return 0, "package: name='io.github.deserthouse.opticon' versionCode='86' versionName='0.20.1-beta'\n"
        if args[0] == "/fake/aapt2" and "xmltree" in args:
            return 0, "N: android:usesCleartextTraffic=(type 0x12)0x0\n"
        if args[0] == "/fake/apksigner":
            return 0, "Signer #1 certificate SHA-256 digest: %s\n" % release.RELEASE_CERT_SHA256
        self.test.fail("没有料到的命令：%r" % (args,))

    def fake_gradle(self, tasks, log, cwd=None):
        self.gradle_calls.append(list(tasks))
        out = os.path.join(self.tmp, "app", "build", "outputs", "apk", "release")
        shutil.rmtree(out, ignore_errors=True)
        os.makedirs(out)
        with open(os.path.join(out, NEW_APK), "wb") as f:
            f.write(b"fake apk built for " + HEAD_A.encode())

    def run_cmd(self, cmd, **options):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            cmd(argparse.Namespace(proxy=self.PROXY, **options))
        return out.getvalue()

    def remote_queries(self):
        return [c for c in self.calls if c[0] == "git" and "ls-remote" in c]


class BuildCommandTest(unittest.TestCase):
    """驱动真实的 cmd_build，走完「读 preflight → 判断顶替谁 → 构建 → 核对 → 落盘」整条链。

    2026-11-28 盲审（BU-01）：调用点传了 replacement_history 签名里没有的 proxy，build 一跑就抛 TypeError，
    而函数层的 9 条单测全绿——接线本身也要有人测。
    """

    def setUp(self):
        self.stub = BuildCommandStub(self)
        self.stub.save_json("preflight", {"version": "0.20.1-beta", "versionCode": 86, "head": HEAD_A})

    def tearDown(self):
        self.stub.restore()

    def args(self, replace=False):
        return {"replace_unpublished": replace}

    def test_a_first_build_runs_end_to_end_and_is_recorded(self):
        out = self.stub.run_cmd(release.cmd_build, **self.args())

        # gradle() 包装函数会给任务表追加 GRADLE_ARGS 再交给本记录器；这里断言的是子命令选的任务
        self.assertEqual([[":app:clean", ":app:assembleRelease"]], self.stub.gradle_calls)
        built = self.stub.load_json("build")
        apk = os.path.join(self.stub.wd, NEW_APK)
        self.assertEqual(("0.20.1-beta", 86, HEAD_A, NEW_APK),
                         tuple(built[k] for k in ("version", "versionCode", "head", "apk")))
        with open(apk, "rb") as f:
            self.assertEqual(hashlib.sha256(f.read()).hexdigest(), built["sha256"])
        self.assertEqual(os.path.getsize(apk), built["size"])
        self.assertEqual(release.RELEASE_CERT_SHA256, built["cert"])
        self.assertEqual([], built["history"])
        self.assertIn("已构建 " + NEW_APK, out)
        self.assertEqual([], self.stub.remote_queries(), "没有旧构建记录时不该去问远端")

    def test_an_earlier_build_of_the_same_commit_is_replaced_and_kept_in_history(self):
        old = record(HEAD_A, apk="OptIcon-v0.20.1-beta-20261127-000000.apk")
        self.stub.save_json("build", old)
        old_apk = os.path.join(self.stub.wd, old["apk"])
        open(old_apk, "wb").close()

        self.stub.run_cmd(release.cmd_build, **self.args())

        built = self.stub.load_json("build")
        self.assertEqual([{"apk": old["apk"], "sha256": old["sha256"], "at": old["built_at"], "head": HEAD_A}],
                         built["history"])
        self.assertFalse(os.path.exists(old_apk), "版本目录的顶层只放这一次要发的包")
        self.assertTrue(os.path.isfile(os.path.join(self.stub.wd, "replaced", old["apk"])), "被顶替的包挪进 replaced/")
        self.assertEqual([NEW_APK], [f for f in os.listdir(self.stub.wd) if f.endswith(".apk")])
        self.assertEqual([], self.stub.remote_queries())

    def test_another_commit_is_refused_by_default_before_any_build(self):
        self.stub.save_json("build", record(HEAD_B, apk="OptIcon-v0.20.1-beta-20261127-000000.apk"))

        with self.assertRaisesRegex(release.Fail, "另一个提交"):
            self.stub.run_cmd(release.cmd_build, **self.args())
        self.assertEqual([], self.stub.gradle_calls)
        self.assertEqual([], self.stub.remote_queries())

    def test_another_commit_is_replaced_when_the_remote_has_no_tag_and_the_query_goes_through_the_proxy(self):
        self.stub.save_json("build", record(HEAD_B, apk="OptIcon-v0.20.1-beta-20261127-000000.apk"))

        out = self.stub.run_cmd(release.cmd_build, **self.args(replace=True))

        self.assertEqual([HEAD_B], [h["head"] for h in self.stub.load_json("build")["history"]])
        self.assertEqual(1, len(self.stub.gradle_calls))
        self.assertIn("顶替另一个提交（" + "b" * 12 + "）的未发布构建", out)
        queries = self.stub.remote_queries()
        self.assertEqual([["git", "-c", "http.proxy=" + BuildCommandStub.PROXY, "ls-remote", "--tags", "origin",
                           "refs/tags/v0.20.1-beta", "refs/tags/v0.20.1-beta^{}"]], queries)

    def test_a_released_version_is_never_rebuilt_over(self):
        self.stub.save_json("build", record(HEAD_B))
        self.stub.remote_out = "c" * 40 + "\trefs/tags/v0.20.1-beta\n"

        with self.assertRaisesRegex(release.Fail, "远端已有 v0.20.1-beta"):
            self.stub.run_cmd(release.cmd_build, **self.args(replace=True))
        self.assertEqual([], self.stub.gradle_calls)

    def test_an_unanswerable_remote_stops_before_building_after_trying_both_routes(self):
        self.stub.save_json("build", record(HEAD_B))
        self.stub.remote_rc = 128

        with self.assertRaisesRegex(release.Fail, "查不到远端的 tag"):
            self.stub.run_cmd(release.cmd_build, **self.args(replace=True))
        self.assertEqual([], self.stub.gradle_calls)
        self.assertEqual([["-c", "http.proxy=" + BuildCommandStub.PROXY], []],
                         [q[1:3] if q[1] == "-c" else [] for q in self.stub.remote_queries()])
        self.assertEqual(2, len(self.stub.remote_queries()))

    def test_a_dirty_worktree_or_a_stale_preflight_stops_before_building(self):
        self.stub.status = " M app/build.gradle.kts"
        with self.assertRaisesRegex(release.Fail, "工作区不干净"):
            self.stub.run_cmd(release.cmd_build, **self.args())
        self.stub.status = ""
        self.stub.save_json("preflight", {"version": "0.20.1-beta", "versionCode": 86, "head": HEAD_B})
        with self.assertRaisesRegex(release.Fail, "preflight 记录的提交"):
            self.stub.run_cmd(release.cmd_build, **self.args())
        self.assertEqual([], self.stub.gradle_calls)

    def test_a_package_that_lies_about_its_version_is_not_recorded(self):
        def lying_sh(args, **kw):
            rc, text = self.stub.fake_sh(args, **kw)
            return (0, text.replace("versionCode='86'", "versionCode='85'")) if "badging" in args else (rc, text)
        release.sh = lying_sh
        with self.assertRaisesRegex(release.Fail, "APK 里的版本号与源码不一致"):
            self.stub.run_cmd(release.cmd_build, **self.args())
        self.assertFalse(os.path.exists(os.path.join(self.stub.wd, "build.json")))

    def test_a_package_signed_with_another_certificate_is_not_recorded(self):
        def other_cert_sh(args, **kw):
            rc, text = self.stub.fake_sh(args, **kw)
            return (0, "Signer #1 certificate SHA-256 digest: %s\n" % ("0" * 64)) if args[0].endswith("apksigner") \
                else (rc, text)
        release.sh = other_cert_sh
        with self.assertRaisesRegex(release.Fail, "签名证书不是正式 keystore"):
            self.stub.run_cmd(release.cmd_build, **self.args())
        self.assertFalse(os.path.exists(os.path.join(self.stub.wd, "build.json")))

    def test_a_debug_build_with_the_test_receiver_or_cleartext_traffic_is_not_recorded(self):
        def debug_sh(args, **kw):
            rc, text = self.stub.fake_sh(args, **kw)
            return (0, "E: receiver(0x01010003)=… name=\"0x0101…\"\n  A: android:name=\"io.github.deserthouse.opticon.E2ETestReceiver\"") \
                if "xmltree" in args else (rc, text)
        release.sh = debug_sh
        with self.assertRaisesRegex(release.Fail, "调试专有的测试接收器"):
            self.stub.run_cmd(release.cmd_build, **self.args())

        def cleartext_sh(args, **kw):
            rc, text = self.stub.fake_sh(args, **kw)
            return (0, "N: android:usesCleartextTraffic=(type 0x12)0xffffffff\n") if "xmltree" in args else (rc, text)
        release.sh = cleartext_sh
        with self.assertRaisesRegex(release.Fail, "明文流量"):
            self.stub.run_cmd(release.cmd_build, **self.args())
        self.assertFalse(os.path.exists(os.path.join(self.stub.wd, "build.json")))


class TagCommandTest(unittest.TestCase):
    """tag 子命令：本地已有同名 tag 且指向别的提交时拒绝，并说清楚怎么办（BU-11c）。"""

    def setUp(self):
        self.stub = BuildCommandStub(self)
        self.stub.save_json("build", record(HEAD_A, apk=NEW_APK))

    def tearDown(self):
        self.stub.restore()

    def test_a_local_tag_left_by_a_replaced_build_is_refused_with_a_way_out(self):
        self.stub.local_tag = HEAD_B + "\n"

        with self.assertRaisesRegex(release.Fail, "git tag -d v0.20.1-beta"):
            self.stub.run_cmd(release.cmd_tag)
        self.assertNotIn(["git", "tag", "v0.20.1-beta", HEAD_A], self.stub.calls)

    def test_the_tag_is_created_on_the_built_commit_when_it_does_not_exist_yet(self):
        out = self.stub.run_cmd(release.cmd_tag)

        self.assertIn(["git", "tag", "v0.20.1-beta", HEAD_A], self.stub.calls)
        self.assertIn("已打 tag", out)

    def test_an_existing_tag_on_the_built_commit_is_left_alone(self):
        self.stub.local_tag = HEAD_A + "\n"

        out = self.stub.run_cmd(release.cmd_tag)

        self.assertNotIn(["git", "tag", "v0.20.1-beta", HEAD_A], self.stub.calls)
        self.assertIn("已在", out)


if __name__ == "__main__":
    unittest.main()
