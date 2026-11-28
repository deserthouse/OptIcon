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

# ── adb 服务隔离 ──
# 多个会话共用默认端口（5037）上的 adb 服务：别的会话重启它，这边的设备操作就会落空
# （2026-11-25 整跑时 d1 就这样失败过——点「添加」的那一下正赶上服务重启）。套件固定用自己的
# 服务端口；启动实例时必须带同一个环境变量，实例才会登记到这个服务上：
#   ANDROID_ADB_SERVER_PORT=5038 emulator -avd OptIcon_A16_root -no-snapshot -no-boot-anim
export ANDROID_ADB_SERVER_PORT="${ANDROID_ADB_SERVER_PORT:-5038}"

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
  [ -n "$S" ] || { S=$(find_serial) || { echo "在 adb 服务（端口 $ANDROID_ADB_SERVER_PORT）上找不到 $AVD_NAME。实例若登记在默认端口 5037 的服务上，套件看不到它：停掉实例，带 ANDROID_ADB_SERVER_PORT=$ANDROID_ADB_SERVER_PORT 重新启动。" >&2; exit 2; }; }
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

# ── 宿主机负载 ──
# 2026-11-25：宿主机在跑游戏时（CPU 85%）跑套件，虚拟机里的系统界面进程重启后起不来，整轮的失败全是环境造成的。
# 宿主机持续忙就不跑；确要在这种时候跑，设 SUITE_ALLOW_LOADED_HOST=1。
# 只按 CPU 判定——可用内存说明不了问题：游戏占着 34GB 私有内存的那晚可用内存还有 4.6GB，反而比平时
# 一台虚拟机加构建守护进程跑着的时候（约 3.5GB）多（2026-11-27 实测，那次内存判据把自己的整跑拦下了）。
# 提交率与硬缺页只为留记录，没有在故障现场标定过，不参与判定。
host_load() {  # 输出 "CPU%,提交率%,硬缺页每秒"；读不到时输出空
  powershell -NoProfile -Command "\$m=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory; \$c=(Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average; '{0},{1},{2}' -f \$c, \$m.PercentCommittedBytesInUse, \$m.PagesPersec" 2>/dev/null | tr -d '\r'
}
host_loaded() {  # host_load 的输出 → CPU ≥ 60% 时退出码 0；读不到时不拦
  [[ "$1" =~ ^[0-9]+, ]] || return 1
  [ "${1%%,*}" -ge "${HOST_CPU_MAX:-60}" ]
}
host_busy() {  # 连续三次采样（间隔 10 秒）都过高才算——别的实例开机、杀毒扫描这类瞬时尖峰不算；退出码 0 = 宿主机忙
  local i
  for i in 1 2 3; do
    host_loaded "$(host_load)" || return 1
    [ "$i" = "3" ] || sleep 10
  done
  return 0
}

# ── 界面 ──
# dump：界面树落到 $OUT/_ui.xml；没采到时把本地文件清空并返回非零。
# 先删设备上的旧文件：采集失败时 uiautomator 不写新文件，留着旧的就会读到上一次的画面
# （2026-11-25 排查点击失效时被它骗过——设备没有焦点窗口，界面树却还是旧的设置页）。
# 采集也可能挂住（系统被饿着的时候），所以带超时。
dump() {
  MSYS_NO_PATHCONV=1 timeout "${DUMP_TIMEOUT:-60}" adb -s "$S" shell "rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml" >/dev/null 2>&1
  as cat /sdcard/ui.xml > "$OUT/_ui.xml" 2>/dev/null
  [ -s "$OUT/_ui.xml" ]
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
# scroll_to_text 文字：设置页往下滚，直到这个文字所在的行完整出现在屏幕上（最多 8 次小幅滚动）。
# 用于「对话框关闭之后页面位置可能变了」的场合——2026-11-25 整跑时 d8 遇到过一次：带键盘的
# 添加对话框关闭后设置页回到了顶部（低概率，八轮探针未能复现）。返回 0 = 在屏幕上或已滚到。
scroll_to_text() {
  local k
  for k in 1 2 3 4 5 6 7 8; do
    dump
    if python - "$OUT/_ui.xml" "$1" <<'ROWPY'; then return 0; fi
import io, re, sys
s = io.open(sys.argv[1], encoding="utf-8", errors="replace").read()
for m in re.finditer(r"<node [^>]*>", s):
    n = m.group(0)
    t = (re.search(r'text="([^"]*)"', n) or [None, ""])[1]
    if t == sys.argv[2]:
        b = [int(x) for x in re.findall(r"\d+", (re.search(r'bounds="([^"]*)"', n) or [0, ""])[1])]
        if 330 < b[1] and b[3] < 2200:
            sys.exit(0)
sys.exit(1)
ROWPY
    [ "$k" = "1" ] && note "D8 页面位置" "关闭添加对话框后「$1」这一行不在屏幕上——页面位置变了"
    as input swipe 540 2000 540 1100 500; sleep 1.5
  done
  return 1
}

# shot 名称：截图（校验不是坏文件）+ 界面树
shot() {
  adb -s "$S" exec-out screencap -p > "$OUT/$1.png"
  local size; size=$(wc -c < "$OUT/$1.png")
  [ "$size" -ge 20000 ] || { log "截图损坏（$size 字节）：$1 —— 是采集侧的问题，不是界面问题"; check "截图 $1 采集完好" false; return 3; }
  dump && cp "$OUT/_ui.xml" "$OUT/$1.xml"   # 界面树在截图之后现取，两者对应同一画面
}
# shot_dialog 名称：截对话框，并等到它的底板不透明为止（切换深色模式、旋转之后系统有过渡动画，
# 过早截图会截到底板透明的过渡帧）。最多 4 轮；仍不透明返回非零——由调用方决定要不要记成检查点。
# 退出码 0 = 截到的是稳定的画面。
shot_dialog() {
  local i size
  for i in 1 2 3 4; do
    adb -s "$S" exec-out screencap -p > "$OUT/$1.png"
    size=$(wc -c < "$OUT/$1.png")
    [ "$size" -ge 20000 ] || { log "截图损坏（$size 字节）：$1 —— 是采集侧的问题，不是界面问题"; return 3; }
    dump
    if python "$DIR/opaque.py" "$OUT/$1.png" "$OUT/_ui.xml" >> "$OUT/debug.log" 2>&1; then
      cp "$OUT/_ui.xml" "$OUT/$1.xml"
      [ "$i" -gt 1 ] && note "截图 $1" "第 $i 轮才稳定（对话框底板不透明）"
      return 0
    fi
    sleep 2
  done
  cp "$OUT/_ui.xml" "$OUT/$1.xml"
  note "截图 $1" "$(python "$DIR/opaque.py" "$OUT/$1.png" "$OUT/_ui.xml" 2>&1 | tail -1)"
  return 1
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
# 贴底软键盘的上沿（像素）。键盘没弹出、或者是悬浮键盘 / 侧边工具条（都不占屏幕底部）时输出空。
# 这台模拟器挂着实体键盘，输入法默认只显示侧边工具条，所以不能只看「输入法已显示」。
ime_top() {
  as dumpsys window 2>/dev/null | tr -d '\r' | python -c '
import re, sys
m = re.search(r"type=ime frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", sys.stdin.read())
if m:
    l, t, r, b = map(int, m.groups())
    if b - t >= 400:
        print(t)
'
}
# buttons_above_ime 键盘上沿：界面树里的 Add / Cancel / Save 都在它上方（键盘弹出时按钮还能点到）
buttons_above_ime() {
  python - "$OUT/_ui.xml" "$1" <<'PY'
import io, re, sys
s = io.open(sys.argv[1], encoding="utf-8", errors="replace").read(); top = int(sys.argv[2]); found = {}
for m in re.finditer(r"<node [^>]*>", s):
    n = m.group(0)
    g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ""])[1]
    if g("text") in ("Add", "Cancel", "Save"):
        b = [int(x) for x in re.findall(r"\d+", g("bounds"))]
        found[g("text")] = b[3]
print(", ".join("%s bottom=%d" % kv for kv in sorted(found.items())))
sys.exit(0 if found and all(v <= top for v in found.values()) else 1)
PY
}

# ── 设备状态 ──
# 2026-11-25 的教训：宿主机被挤占时重启系统界面进程，新进程起不来（启动超时被杀），框架就一直把锁屏
# 置为显示中——没有窗口持有焦点，之后每一次点击都被输入分发器丢掉（"no touchable window" /
# "no focused window"），整轮跑完只剩一屏看不懂的失败。所以导航之前先确认设备本身是好的；不好就取证并停。
focus_state() {  # ok = 有窗口持有焦点且锁屏没有挂着；否则给出原因（nofocus / keyguard / unknown），判定见 focus.py
  as dumpsys window | tr -d '\r' | python "$DIR/focus.py"   # 一份快照里同时有焦点和锁屏状态
}
device_ready() {  # device_ready [超时秒]：设备能接收点击（启动过渡的空档不算）时退出码 0
  local left=$(( ${1:-30} ))
  while [ "$left" -gt 0 ]; do
    [ "$(focus_state)" = "ok" ] && return 0
    sleep 3; left=$((left-3))
  done
  log "设备不接收点击：$(focus_state)"; return 1
}
device_diag() {  # device_diag 标签：设备状态不对时留现场，报告里记一条指向它的证据
  local d="$OUT/diag-$1" i=0
  while [ -e "$d" ]; do i=$((i+1)); d="$OUT/diag-$1-$i"; done
  mkdir -p "$d"
  as dumpsys input > "$d/input.txt" 2>&1 &
  as dumpsys window > "$d/window.txt" 2>&1 &
  as dumpsys power > "$d/power.txt" 2>&1 &
  as dumpsys activity activities > "$d/activities.txt" 2>&1 &
  as dumpsys dropbox > "$d/dropbox.txt" 2>&1 &
  adb -s "$S" logcat -d -b all -v time -t 4000 > "$d/logcat.txt" 2>&1 &
  ( adb -s "$S" exec-out screencap -p > "$d/screen.png" 2>/dev/null ) &
  wait
  note "设备现场（$1）" "$(basename "$d")/：$(grep -m1 'mCurrentFocus=' "$d/window.txt" | tr -d '\r' | tr -s ' ')；锁屏 $(grep -m1 -A1 'KeyguardServiceDelegate' "$d/window.txt" | tr -d '\r' | sed -n 2p | tr -d ' ')"
  log "设备现场已留在 $d"
}

# ── 应用与偏好 ──
app_stop()      { as am force-stop "$PKG"; }
DEVICE_DEAD=0   # 1 = 设备已经不接收点击（见 device_ready）；之后的场景不再跑
app_launch() {  # 冷启动并回到首页；顺带确认设备能接收点击
  [ "$DEVICE_DEAD" = "0" ] || return 1
  app_stop; as monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  if ! wait_node "Settings" 0 30 >/dev/null; then
    log "应用首页没有就绪"
    device_ready 5 && return 1   # 设备是好的，只是应用没起来：留给调用方记失败
    DEVICE_DEAD=1; device_diag "app-not-ready"; return 1
  fi
  device_ready 30 || { DEVICE_DEAD=1; device_diag "device-not-ready"; return 1; }
}
open_settings() {  # 设置页顶部（未滚动）。点了没进去就再点一次（最多两次）。
  local try
  for try in 1 2; do
    app_launch || return 1
    tap_node "Settings" || return 1
    wait_node "Notification Icon Sources" 0 20 >/dev/null && return 0
    [ "$try" = "1" ] && [ "$DEVICE_DEAD" = "0" ] || break
    log "点了「Settings」但设置页没有出现——再点一次"
  done
  device_diag "settings-not-opened"
  return 1
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

# 清掉测试期间发的通知。系统没有提供「取消通知」的命令行（cmd notification 没有 cancel），
# 只能走通知栏的「全部清除」。尽力而为，不计分——清不掉只影响整洁，不影响结论。
clear_notifications() {
  local xy
  as cmd statusbar expand-notifications; sleep 2; dump
  xy=$(node_xy "Clear all notifications.") && { as input tap $xy; sleep 1; }
  as cmd statusbar collapse
}

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

# ── hook 本地规则缓存（系统界面进程自己的 cacheDir） ──
# 注意是「设备加密存储」那一份（/data/user_de），不是 /data/user/0：hook 拿到的系统界面上下文
# 是设备保护存储的上下文，开机后、用户解锁前就能读。放到 /data/user/0 下那份里，hook 看不到
# （2026-11-25 首次复验时就在这里放错了，日志是「dropped 0」）。属主与标签照父目录抄。
HOOK_CACHE_PARENT=/data/user_de/0/com.android.systemui/cache
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

# ── 杂项 ──
grep_has() { printf '%s\n' "$1" | grep -q -- "$2"; }   # grep_has 文本 基本正则
# hook_synced：日志里有一次真正跑过的自同步（不论结果）。批 UP 之后「同步过、上游没变」的日志不带 ingested，
# 所以「没有同步过」的检查不能只看 ingested。
hook_synced() { hook_logs | grep -q -e "self-sync ok" -e "self-sync failed" -e "rule icons into hook-local cache" -e "nothing to sync"; }

# ── 本机测试源的请求计数 ──
# server.log 每个请求一行。server_mark 记下当前行数；server_count 标记 路径 → 两个标记之间该路径的请求数。
server_mark()  { wc -l < "$OUT/server.log" | tr -d ' '; }
server_count() { tail -n "+$(( $1 + 1 ))" "$OUT/server.log" | grep -c -- "GET $2 "; }

# ── 规则库（上游 ANIP 快照，应用侧） ──
RULES=$P/files/fankes_cache
pull_root()   { MSYS_NO_PATHCONV=1 adb -s "$S" exec-out "su -c 'cat $1'" > "$2" 2>/dev/null; [ -s "$2" ]; }
rule_source() { su_ "cat $RULES/source.json" 2>/dev/null | tr -d '\r'; }   # 快照的来历（channel / tag / timestamp / sha256 / icons）
rule_count()  { su_ "ls $RULES" 2>/dev/null | tr -d '\r' | grep -c '\.png$'; }
rule_hash()   { su_ "cd $RULES && sha256sum * | sha256sum" 2>/dev/null | tr -d '\r' | cut -c1-64; }
built_line()  { hook_logs | grep -m1 "ANIP snapshot built" | sed 's/^.*ANIP snapshot built: //'; }
anip_mirrored() { local p; for p in "$@"; do as test -f "$SHARED/$p.opticon" || return 1; done; }   # 这些包在共享目录里都有镜像
rule_residue()  { su_ "test -e $P/files/fankes_cache.zip.tmp -o -d $RULES.staging -o -d $RULES.prev"; }
# rule_stamp：规则版本戳文件的「修改时间 大小」。收尾重新发布时会重写它——拿前后两次的值判断有没有重新发布。
rule_stamp()  { as stat -c '%Y %s' "$SHARED/rules_stamp.opticon" 2>/dev/null | tr -d '\r'; }
# drop_mirror 包名：经媒体库删掉这个包在共享目录里的镜像（应用删镜像走的也是这条路）。删后文件应不存在。
drop_mirror() {
  as content delete --uri content://media/external/downloads --where "\"_display_name='$1.opticon'\"" >/dev/null 2>&1
  not as test -f "$SHARED/$1.opticon"
}

# rule_sync：设置页上点「通知图标源」的同步按钮。状态行出现的位置留在 RS_TY（之后用 rule_wait 取）。
rule_sync() {
  local sx sy
  open_settings || return 1
  read -r sx sy RS_TY <<< "$(section_sync_xy "Notification Icon Sources")"
  [ -n "${sx:-}" ] || { log "没找到通知图标源分区的同步按钮"; device_diag "notif-section-not-found"; return 1; }
  as input tap "$sx" "$sy"
}
# rule_wait [超时秒] → 状态行（出现结论词时）；超时时也输出当时的状态行，但退出码非零
# 结论词要跟得上产品文案：漏了一个，那种结局就要空等到超时才判失败（2026-11-28 盲审 BU-11f：漏了「存储空间不足」）
rule_wait() { wait_status "$RS_TY" "ANIP synced|already up to date|Already up to date|failed|unavailable|Invalid data|did not finish|Not enough storage space" "${1:-300}"; }

# plant_mirror 快照里的PNG：把它作为 com.android.shell 的共享镜像（经应用真实的写入链路，应用才删得掉）
plant_mirror() {
  local ct; ct=$(label_of "$P/files")
  su_ "cp $1 $P/files/baked/suite-plant.png && chown \$(stat -c %u:%g $P/files) $P/files/baked/suite-plant.png && chcon $ct $P/files/baked/suite-plant.png" || return 1
  as am broadcast -a "$PKG.E2E_BAKE" -n "$PKG/.util.E2ETestReceiver" --es pkg "$D5_PLAIN" --es method manual --es branch local --es path "$P/files/baked/suite-plant.png" >/dev/null
  sleep 4
  as test -f "$SHARED/$D5_PLAIN.opticon"
}
# unplant_mirror：删掉上面放的镜像（经媒体库，与应用自己走的是同一条路）
unplant_mirror() {
  note "删除测试镜像 $D5_PLAIN" "$(as content delete --uri content://media/external/downloads --where "\"_display_name='$D5_PLAIN.opticon'\"" 2>&1 | tr -d '\r' | tr '\n' ' ')"
  not as test -f "$SHARED/$D5_PLAIN.opticon"
}

# anip_shade 场景 标签 快照里的PNG 截图前缀 [tint]：把这张图当成 com.android.shell 的小图标发一条通知，
# 留下状态栏与通知栏的截图——审美判读交给具备视觉能力的模型或人。带 tint 时另做一条硬检查：
# 状态栏里新出现的像素都是无彩色的（彩色位图同样被按单色着色）。
anip_shade() {
  local t="suite$(date +%s)$2"
  clear_logs
  plant_mirror "$3" || { log "没能把 $3 放成 $D5_PLAIN 的共享镜像"; unplant_mirror; return 1; }
  shot "$4-1-before"
  as cmd notification post -S bigtext -t suite "$t" "rule icon $2" >/dev/null; sleep 3
  check "$1（$2）hook 用这张图替换了通知小图标" hook_has "createIcons: $D5_PLAIN -> setSmallIcon replaced"
  shot "$4-2-status-bar"
  [ "${5:-}" = "tint" ] && check "$1（$2）状态栏里的小图标是单色（彩色位图同样被着色）" python "$DIR/tint.py" "$OUT/$4-1-before.png" "$OUT/$4-2-status-bar.png"
  as cmd statusbar expand-notifications; sleep 2
  shot "$4-3-shade"
  as cmd statusbar collapse; sleep 1
  clear_notifications
  save_logs "$4-replaced"
  unplant_mirror
}
