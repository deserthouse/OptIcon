#!/usr/bin/env bash
# CI 里跑 Gradle（见 .github/workflows/ci.yml）。
#
# 依赖仓库的解析会偶发失败：2026-11-27 从零开始的运行约一半在解析 Kotlin 插件标记时被判「找不到」
# （Maven Central、Plugin Portal），同一提交重跑即过；命中依赖缓存时不会发生。所以这里只在失败像是解析类
# 问题时重试（最多 3 次）；测试或 lint 自身失败不重试。每次都带 --stacktrace，失败时才输出，便于看到底层原因。
#
# 用法：bash scripts/ci_gradle.sh <gradle 任务与参数...>
set -u -o pipefail
log=$(mktemp)
for attempt in 1 2 3; do
  if ./gradlew "$@" --no-configuration-cache --stacktrace 2>&1 | tee "$log"; then
    rm -f "$log"; exit 0
  fi
  if ! grep -q -E "Could not (resolve|GET|HEAD|download|find|determine)|was not found in any of the following sources|Failed to resolve|Could not transfer" "$log"; then
    rm -f "$log"; exit 1     # 不是解析类失败：原样退出，不重试
  fi
  rm -f "$log"
  [ "$attempt" -lt 3 ] || exit 1
  echo "::warning::Gradle 第 $attempt 次尝试因依赖解析失败，${CI_GRADLE_RETRY_WAIT:-15} 秒后重试"
  sleep "${CI_GRADLE_RETRY_WAIT:-15}"
done
