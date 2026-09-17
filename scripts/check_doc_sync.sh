#!/usr/bin/env bash
# 文档同步自检：版本号三处一致性（build.gradle / project_status / MIGRATION_NOTES）
# 用法: bash scripts/check_doc_sync.sh  — 出包打 tag 前必须全绿
cd "$(dirname "$0")/.."
V=$(grep -oP 'MODULE_VERSION_NAME = "\K[^"]+' app/build.gradle.kts)
C=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts | head -1)
fail=0
check() { # $1=file $2=pattern $3=label
  if grep -q "$2" "$1"; then echo "  OK  $3"; else echo "  FAIL $3 ($1 缺 $V/$C)"; fail=1; fi
}
echo "build.gradle.kts: v$V / code $C"
check project_status.md            "versionName: $V | versionCode: $C" "project_status 头部"
check MIGRATION_NOTES.md           "当前版本 v$V / versionCode $C"     "MIGRATION_NOTES 待办区标题"
if grep -q "待真机 S1 复验\|待宿主 GPU 复位\|等用户批）" MIGRATION_NOTES.md; then
  echo "  WARN  MIGRATION_NOTES 存在疑似过期表述，请人工复核"; fi
[ $fail -eq 0 ] && echo "SYNC PASS" || { echo "SYNC FAIL — 修正后再打 tag"; exit 1; }
