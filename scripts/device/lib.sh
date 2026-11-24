#!/usr/bin/env bash
# 设备验证套件的公共函数。由 run_suite.sh source，不单独执行。
#
# 纪律：
#  - 只操作核身过的实例（AVD 名 + 机型都对了才动手），别的项目的实例不碰；
#  - 每个结论用 check 记录，报告里逐条可查；「看不到」不算「不存在」——判断不了的情况记失败；
#  - 视觉项只采集截图与几何数据，判读由具备视觉能力的模型或人来做；
#  - 设备路径一律经过 as()（带 MSYS_NO_PATHCONV，防止 Git Bash 把 /data/... 改写成本地路径）；
#    本地路径因此必须是相对路径或 C:/ 形式。

AVD_NAME="${AVD_NAME:-OptIcon_A16_root}"
AVD_MODEL="${AVD_MODEL:-sdk_gphone64_x86_64}"
PKG=io.github.deserthouse.opticon
P=/data/data/$PKG
SHARED=/sdcard/Download/OptIcon
# D5 用的两个包：规则库里有、上游没有（同步后都会被判为撤销）。一个没启用自定义，一个启用了。
D5_PLAIN=com.android.shell          # adb 能以它的身份发通知
D5_CUSTOM=io.suite.customized       # 不存在的包，只为造出「撤销 + 启用了自定义」的组合
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
command -v cygpath >/dev/null 2>&1 && DIR="$(cygpath -m "$DIR")"
OUT="${OUT:?run_suite.sh 负责设置 OUT（本次运行的输出目录）}"
S="${S:-}"
RESULTS="$OUT/results.tsv"
: > "$RESULTS"

log() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*" >&2; }

# ── 实例核身 ──
find_serial() {
  local s n
  for s in $(adb devices 2>/dev/null | awk '/emulator-/{print $1}'); do
    n=$(adb -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')
    [ "$n" = "$AVD_NAME" ] && { echo "$s"; return 0; }
  done
  return 1
}

require_avd() {
  [ -n "$S" ] || { S=$(find_serial) || { echo "找不到 $AVD_NAME（在跑的实例里没有它）" >&2; exit 2; }; }
  local name model
  name=$(adb -s "$S" emu avd name 2>/dev/null | head -1 | tr -d '\r')
  model=$(adb -s "$S" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  [ "$name" = "$AVD_NAME" ] && [ "$model" = "$AVD_MODEL" ] || { echo "拒绝操作：$S 是 '$name / $model'，不是 $AVD_NAME" >&2; exit 2; }
}

a()  { adb -s "$S" "$@"; }
as() { MSYS_NO_PATHCONV=1 adb -s "$S" shell "$@"; }
# root：参数拼成一条命令交给 su -c。参数里不能有单引号。
su_() { MSYS_NO_PATHCONV=1 adb -s "$S" shell "su -c '$*'"; }
push() { MSYS_NO_PATHCONV=1 adb -s "$S" push "$1" "$2" >/dev/null 2>&1; }   # 以退出码判断成败，不往控制台刷进度
# 设备上某个文件或目录的安全标签（经 root：应用数据目录 shell 身份读不到）。
# root 建出来的文件不带应用数据目录的类别标记，应用读不到——造文件和还原之后都要按原目录的标签 chcon。
label_of() { su_ "ls -Zd $1" | tr -d '\r' | awk '{ for (i = 1; i <= NF; i++) if ($i ~ /_r:/) { print $i; exit } }'; }

# ── 检查点 ──
# check "描述" 条件命令...   条件命令退出码 0 = PASS；其余 = FAIL
# 条件命令的输出不进报告，诊断信息进 debug.log。check 自己在失败时也返回非零。
check() {
  local desc="$1"; shift
  if "$@" >/dev/null 2>>"$OUT/debug.log"; then
    printf 'PASS\t%s\n' "$desc" >> "$RESULTS"; log "PASS  $desc"
  else
    printf 'FAIL\t%s\n' "$desc" >> "$RESULTS"; log "FAIL  $desc"; return 1
  fi
}
check_eq() {  # check_eq "描述" 实际 期望
  if [ "$2" = "$3" ]; then printf 'PASS\t%s\n' "$1" >> "$RESULTS"; log "PASS  $1"
  else printf 'FAIL\t%s\t实际=%s\t期望=%s\n' "$1" "$2" "$3" >> "$RESULTS"; log "FAIL  $1  实际=$2 期望=$3"; return 1; fi
}
fails() { grep -c '^FAIL' "$RESULTS"; }
# note 标签 值：把原始证据（哈希、目录清单、环境信息）记进报告，结论之外留下可复核的东西
note() { printf 'NOTE\t%s\t%s\n' "$1" "$2" >> "$RESULTS"; }
not() { "$@"; [ $? -ne 0 ]; }
no_node() { not node_xy "$1"; }

# ── 界面 ──
dump() {
  as uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  as cat /sdcard/ui.xml > "$OUT/_ui.xml" 2>/dev/null
}
# node_xy 文本或content-desc [第几个] → "x y"；找不到时非零
node_xy() { python "$DIR/find.py" "$OUT/_ui.xml" "$1" "${2:-0}" 2>/dev/null; }
# xy_ 需要上下文的查找（见 pick.py）
xy_below()      { python "$DIR/pick.py" "$OUT/_ui.xml" below "$1" "$2" 2>/dev/null; }
xy_row_button() { python "$DIR/pick.py" "$OUT/_ui.xml" rowbutton "$1" "$2" 2>/dev/null; }
xy_has()        { python "$DIR/pick.py" "$OUT/_ui.xml" has "$1" 2>/dev/null; }
xy_field()      { python "$DIR/pick.py" "$OUT/_ui.xml" field "$1" 2>/dev/null; }
tap_node() { local xy; dump; xy=$(node_xy "$@") || { log "找不到界面节点：$1"; return 1; }; as input tap $xy; }
tap_below() { local xy; dump; xy=$(xy_below "$1" "$2") || { log "找不到「$2」（应在「$1」下方）"; return 1; }; as input tap $xy; }
tap_row_button() { local xy; dump; xy=$(xy_row_button "$1" "$2") || { log "找不到「$1」这一行的「$2」"; return 1; }; as input tap $xy; }
tap_field() { local xy; dump; xy=$(xy_field "$1") || { log "找不到第 $1 个输入框"; return 1; }; as input tap $xy; }
# wait_node 文本 [第几个] [超时秒] → 找到时输出坐标；超时非零
wait_node() {
  local left=$(( ${3:-20} )) xy=""
  while [ "$left" -gt 0 ]; do dump; xy=$(node_xy "$1" "${2:-0}") && { echo "$xy"; return 0; }; sleep 2; left=$((left-2)); done
  return 1
}
# shot 名称：截图（校验不是坏文件）+ 界面树
shot() {
  adb -s "$S" exec-out screencap -p > "$OUT/$1.png"
  local size; size=$(wc -c < "$OUT/$1.png")
  [ "$size" -ge 20000 ] || { log "截图损坏（$size 字节）：$1 —— 是采集侧的问题，不是界面问题"; check "截图 $1 采集完好" false; return 3; }
  dump && cp "$OUT/_ui.xml" "$OUT/$1.xml"   # 界面树在截图之后现取，两者对应同一画面
}
# status_under 标题y → 标题下方 260px 内的状态行文字
status_under() {
  python - "$OUT/_ui.xml" "$1" <<'PY'
import io, re, sys
s = io.open(sys.argv[1], encoding="utf-8", errors="replace").read()
ty = int(sys.argv[2]); out = []
for m in re.finditer(r"<node [^>]*>", s):
    n = m.group(0)
    b = [int(x) for x in re.findall(r"\d+", (re.search(r'bounds="([^"]*)"', n) or [0, ""])[1])]
    t = (re.search(r'text="([^"]*)"', n) or [None, ""])[1]
    if t and len(b) == 4 and ty < (b[1] + b[3]) // 2 < ty + 260:
        out.append(t)
print(" | ".join(out))
PY
}
# section_sync_xy 分区标题 → 该分区同步按钮的 "x y" 与标题 y："x y ty"
section_sync_xy() {
  python - "$OUT/_ui.xml" "$1" <<'PY'
import io, re, sys
s = io.open(sys.argv[1], encoding="utf-8", errors="replace").read()
title = sys.argv[2]; ty = None
for m in re.finditer(r"<node [^>]*>", s):
    n = m.group(0)
    b = [int(x) for x in re.findall(r"\d+", (re.search(r'bounds="([^"]*)"', n) or [0, ""])[1])]
    if ('text="%s"' % title) in n:
        ty = (b[1] + b[3]) // 2
    elif 'content-desc="Sync subscription source"' in n and ty is not None and abs((b[1] + b[3]) // 2 - ty) < 60:
        print((b[0] + b[2]) // 2, (b[1] + b[3]) // 2, ty); break
PY
}
wait_sync_end() {  # 等「取消同步」按钮消失（上限约 60 秒）；规则库同步没有该按钮，用 wait_status
  local i; for i in $(seq 1 30); do dump; grep -q 'content-desc="Cancel sync"' "$OUT/_ui.xml" || return 0; sleep 2; done; return 1
}
wait_status() {   # wait_status 标题y "词1|词2|…" [超时秒] → 状态行出现任一词时输出状态行；超时非零
  local left=$(( ${3:-60} )) st="" w hit
  local -a words; IFS='|' read -r -a words <<< "$2"   # 只按竖线切：词条本身可以带空格
  while [ "$left" -gt 0 ]; do
    dump; st=$(status_under "$1"); hit=0
    for w in "${words[@]}"; do case "$st" in *"$w"*) hit=1;; esac; done
    [ "$hit" = 1 ] && { echo "$st"; return 0; }
    sleep 3; left=$((left-3))
  done
  echo "$st"; return 1
}
ime_shown() { as dumpsys input_method | tr -d '\r' | grep -q "mInputShown=true"; }

# ── 应用与偏好 ──
app_stop()      { as am force-stop "$PKG"; }
app_launch() {  # 冷启动并回到首页
  app_stop; as monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  wait_node "Settings" 0 30 >/dev/null || { log "应用首页没有就绪"; return 1; }
}
open_settings() {  # 设置页顶部（未滚动）
  app_launch || return 1
  tap_node "Settings" || return 1; sleep 2
  wait_node "Notification Icon Sources" 0 15 >/dev/null
}
prefs_cat()     { su_ "cat $P/shared_prefs/optcon_prefs.xml" 2>/dev/null | tr -d '\r'; }
prefs_pull()    { prefs_cat > "$1"; }
# prefs_inject 本地xml：停应用后整体替换偏好文件（保留属主与标签）
prefs_inject() {
  app_stop; push "$1" /data/local/tmp/opticon-prefs.xml || return 1
  su_ "cat /data/local/tmp/opticon-prefs.xml > $P/shared_prefs/optcon_prefs.xml"
}
# prefs_get 键 → 值（字符串或布尔/整数）；没有则输出空。用法：prefs_cat | prefs_get 键
# 管道里的数据先读进变量再作为参数交给 Python：`python - <<'PY'` 已经把标准输入给了脚本本身
prefs_get() {
  local data; data=$(cat)
  python - "$1" "$data" <<'PY'
import html, re, sys
s, k = sys.argv[2], re.escape(sys.argv[1])
m = re.search(r'<string name="%s"[^>]*>(.*?)</string>' % k, s, re.S) or \
    re.search(r'<(?:boolean|int|long|float) name="%s" value="([^"]*)"' % k, s)
print(html.unescape(m.group(1)) if m else "")
PY
}
# json_field 字段名 [第几个对象，默认0] → 标准输入是 JSON 数组，输出该对象的字段值（字符串）
json_field() {
  local data; data=$(cat)
  python - "$1" "${2:-0}" "$data" <<'PY'
import json, sys
try:
    arr = json.loads(sys.argv[3])
    print(str(arr[int(sys.argv[2])].get(sys.argv[1], "")))
except Exception:
    print("")
PY
}
# placed 分区(notif|picp) 源名字 → 该源显示在这个分区里时退出码 0（判定方法见 place.py）
placed() { python "$DIR/place.py" "$OUT/_ui.xml" "$2" "$1"; }
# 「不在某个分区里」：分区的锚点不在屏幕上时无法判断，记失败而不是通过
not_placed() {
  python "$DIR/place.py" "$OUT/_ui.xml" "$2" "$1"
  local rc=$?
  [ "$rc" -ne 2 ] || { echo "分区的锚点不在屏幕上，无法判断 $2 是否在 $1" >&2; return 1; }
  [ "$rc" -eq 1 ]
}
shared_flag()   { as cat "$SHARED/$1" 2>/dev/null | tr -d '\r'; }
picp_hash()     { su_ "cd $P/files/picp/icons 2>/dev/null && sha256sum * | sha256sum" | tr -d '\r' | cut -c1-64; }
picp_ls()       { su_ "ls -la $P/files/picp/" | tr -d '\r'; }
picp_residue()  { picp_ls | grep -q "staging\|\.zip"; }
hook_logs()     { adb -s "$S" logcat -d -s OptIcon/NotifHook:V OptIcon/HookLibSync:V OptIcon/SettingsVM:V OptIcon/AnipSync:V OptIcon/PicpEngine:V OptIcon/IconLibEngine:V OptIcon/AppListVM:V OptIcon/E2E:V 2>/dev/null | tr -d '\r'; }
hook_has()      { hook_logs | grep -q -- "$1"; }
wait_hook() {   # wait_hook 词 [超时秒]：等日志出现某词
  local left=$(( ${2:-90} )); while [ "$left" -gt 0 ]; do hook_has "$1" && return 0; sleep 3; left=$((left-3)); done; return 1
}
save_logs()     { hook_logs > "$OUT/$1.log"; }          # hook / 应用日志落盘
ls_shared()     { as ls -la "$SHARED" | tr -d '\r'; }    # 共享目录清单
pin_cleared()   { as locksettings get-disabled 2>/dev/null | tr -d '\r' | grep -q true; }

# ── 崩溃总闸 ──
# 概率性的崩溃靠单个场景的断言抓不住：清日志之前先把崩溃缓冲区留底，报告末尾统一查一次
clear_logs() {
  adb -s "$S" logcat -d -b crash 2>/dev/null | tr -d '\r' >> "$OUT/crashes.log"
  adb -s "$S" logcat -c -b all
}
keep_crashes()  { adb -s "$S" logcat -d -b crash 2>/dev/null | tr -d '\r' >> "$OUT/crashes.log"; }
crash_of() {     # crash_of 包名：运行期间这个进程崩过
  { adb -s "$S" logcat -d -b crash 2>/dev/null | tr -d '\r'; cat "$OUT/crashes.log" 2>/dev/null; } | grep -q "Process: $1,"
}

# ── hook 本地规则缓存（系统界面进程自己的 cacheDir；系统界面以 system uid 运行） ──
HOOK_CACHE_PARENT=/data/user/0/com.android.systemui/cache
HOOK_CACHE=$HOOK_CACHE_PARENT/opticon_hook_icons
hook_cache_has() { su_ "test -f $HOOK_CACHE/$1.png"; }
# 往 hook 本地缓存里放图标文件，属主与安全标签同该目录（模拟「旧版本自同步留下的快照」）
hook_cache_put() {
  local src=/data/local/tmp/suite-src.png ct own pkg
  ct=$(label_of "$HOOK_CACHE_PARENT")
  own=$(su_ "stat -c %u:%g $HOOK_CACHE_PARENT" | tr -d '\r')
  [ -n "$ct" ] && [ -n "$own" ] || { log "读不到系统界面缓存目录的安全标签或属主"; return 1; }
  su_ "mkdir -p $HOOK_CACHE && chown $own $HOOK_CACHE && chcon $ct $HOOK_CACHE" || return 1
  for pkg in "$@"; do
    su_ "cp $src $HOOK_CACHE/$pkg.png && chown $own $HOOK_CACHE/$pkg.png && chcon $ct $HOOK_CACHE/$pkg.png" || return 1
  done
}
