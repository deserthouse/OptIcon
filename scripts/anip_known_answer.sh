#!/usr/bin/env bash
# 拿一份真实的上游官方资源包跑已知答案测试（AnipRealBundleKnownAnswerTest）。
# 用法：bash scripts/anip_known_answer.sh <anip-bundle-<提交>.zip>
# 包从上游的 release 附件取：https://github.com/BetterAndroid/android-notification-icon-project/releases
# 平时的门禁不跑这个测试（包不入库）；上游更新后跑一遍，核对差异再改测试里的期望值。
set -u
cd "$(dirname "$0")/.." || exit 2
if [ $# -ne 1 ] || [ ! -f "$1" ]; then
  echo "用法：bash scripts/anip_known_answer.sh <anip-bundle-<提交>.zip>" >&2
  exit 2
fi
dir="$(cd "$(dirname "$1")" && pwd)"
name="$(basename "$1")"
bundle="$(cygpath -m "$dir/$name" 2>/dev/null || echo "$dir/$name")"
echo "被测的包：$bundle"
echo "SHA-256：$(sha256sum "$dir/$name" | cut -c1-64)"
sh gradlew :app:testDebugUnitTest --tests "*AnipRealBundleKnownAnswerTest" --no-configuration-cache \
  "-Dopticon.anip.bundle=$bundle" --console=plain
