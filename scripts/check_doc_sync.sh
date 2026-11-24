#!/usr/bin/env bash
# 文档同步自检：版本号四处一致性（build.gradle / project_status / MIGRATION_NOTES / CHANGELOG）
# 用法: bash scripts/check_doc_sync.sh  — 出包打 tag 前必须全绿
# project_status.md 与 MIGRATION_NOTES.md 为维护者本地私有文件（AGENTS.md 约定）；
# 公开 clone 缺失时相应检查 SKIP。CHANGELOG.md 在公开仓，缺失即 FAIL。
cd "$(dirname "$0")/.."
V=$(grep -oP 'MODULE_VERSION_NAME = "\K[^"]+' app/build.gradle.kts)
C=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts | head -1)
fail=0
check() { # $1=file $2=pattern $3=label
  # R3 教训：版本号含 []（如 0.18.0-rc.3）时 grep 正则字符类会误判——固定串匹配
  if grep -qF -- "$2" "$1"; then echo "  OK   $3"; else echo "  FAIL $3 ($1 缺 $V/$C)"; fail=1; fi
}
local_check() { # 本地私有文件：缺失 SKIP（公开 clone 属预期）
  if [ ! -f "$1" ]; then echo "  SKIP $3（$1 为本地私有文件，公开 checkout 缺失属预期）"; return; fi
  check "$@"
}
echo "build.gradle.kts: v$V / code $C"
local_check project_status.md  "versionName: $V | versionCode: $C"      "project_status 头部"
local_check MIGRATION_NOTES.md "当前版本 v$V / versionCode $C"          "MIGRATION_NOTES 待办区标题"
local_check MIGRATION_NOTES.md "当前: \`v$V\` / versionCode $C"          "MIGRATION_NOTES 当前版本行"
check CHANGELOG.md             "## [$V]"                                "CHANGELOG 当前版本条目"
if [ -f MIGRATION_NOTES.md ] && grep -q "待真机 S1 复验\|待宿主 GPU 复位\|等用户批）" MIGRATION_NOTES.md; then
  echo "  WARN  MIGRATION_NOTES 存在疑似过期表述，请人工复核"; fi
[ $fail -eq 0 ] && echo "SYNC PASS" || { echo "SYNC FAIL — 修正后再打 tag"; exit 1; }
