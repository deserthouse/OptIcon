# -*- coding: utf-8 -*-
"""release.py 里「构建记录顶替谁」的判定与远端 tag 查询（批 MT M1）。

跑法（项目根目录）：python -m unittest discover -s scripts/tests -v
不发网络请求：远端用一个本地裸仓库充当。
"""
import contextlib
import io
import json
import os
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


if __name__ == "__main__":
    unittest.main()
