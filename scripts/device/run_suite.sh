#!/usr/bin/env bash
# OptIcon 设备验证套件：一条命令跑完发版前应过的设备场景，输出逐项 PASS/FAIL 的报告。
#
# 用法（项目根目录；先启动 OptIcon_A16_root、装好被测的调试包；hook 不用管，开跑时会重新加载）：
#   bash scripts/device/run_suite.sh                  # 全部场景（约 25 分钟，含一次重启）
#   bash scripts/device/run_suite.sh --only d1,d4     # 只跑指定场景
#   bash scripts/device/run_suite.sh --skip d6b       # 跳过指定场景（d6b 最慢，d5 / d6 / d9 / d10 / d12 / d13 / d14 需要外网）
#
# 前提：调试包（带测试接收器与明文流量声明）、root（su）、宿主机不忙（CPU 持续低于 60%，否则不跑）。
# 三道闸：宿主机负载、设备状态（有窗口持有焦点、锁屏没有挂着）、场景完整（导航失败即停，不留凭空通过的检查）。
# 细节见同目录 README.md。
# 套件自带备份与还原（偏好文件、显示设置、通知权限、图标库；PIN 用完即清），还原失败会在报告里标出。
# 视觉项（d8）只采集截图与几何数据并做基础断言；审美判读由具备视觉能力的模型或人来做。
# 报告末尾的「原始证据」一节留有关键日志文件、哈希与目录清单，供复核结论。
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
command -v cygpath >/dev/null 2>&1 && ROOT="$(cygpath -m "$ROOT")"
PORT="${PORT:-28765}"
ONLY=""; SKIP=""
while [ $# -gt 0 ]; do
  case "$1" in
    --only) ONLY="$2"; shift 2;;
    --skip) SKIP="$2"; shift 2;;
    *) echo "未知参数：$1" >&2; exit 2;;
  esac
done

OUT="$ROOT/tests/device-reports/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT/backup"
source "$ROOT/scripts/device/lib.sh"

SERVER_PID=""; PIN_SET=0; PICP_HASH0=""; RESTORE_FAILED=0
SYSUI_PID0=""; FONT0=""; NIGHT0=""; ACCEL0=""; IME0=""; COOLDOWN0=""; NOTIF_GRANTED0=""; PROXY0=""; MASTER0=""
HAD_MIRROR_PLAIN=0; HAD_MIRROR_CUSTOM=0

# ── 导航 ──
goto_picp() {  # 设置页滚到「应用图标源」分区，输出 "同步按钮x 同步按钮y 标题y"
  open_settings || return 1
  local xy
  as input swipe 540 2100 540 1150 500; sleep 2; dump
  xy=$(section_sync_xy "App Icon Sources")
  if [ -z "$xy" ] && node_xy "Notification Icon Sources" >/dev/null 2>&1; then   # 页面还在顶部：那一下滑动没有生效
    log "滑动没有生效——再滑一次"
    as input swipe 540 2100 540 1150 500; sleep 2; dump; xy=$(section_sync_xy "App Icon Sources")
  fi
  [ -n "$xy" ] || { log "设置页上没找到「应用图标源」分区的同步按钮"; device_diag "picp-section-not-found"; return 1; }
  echo "$xy"
}
scroll_to_add() {  # 大字体下把「应用图标源」分区的添加按钮滚进屏幕，输出其坐标
  local k xy seen=0 sig="" prev=""
  for k in $(seq 1 14); do
    dump
    sig=$(md5sum "$OUT/_ui.xml" | cut -c1-32)
    [ "$sig" != "$prev" ] || { log "已经滚到底，没找到「应用图标源」分区的添加按钮"; return 1; }
    prev=$sig
    xy=$(python - "$OUT/_ui.xml" "$seen" <<'PY'
import io, re, sys
s = io.open(sys.argv[1], encoding="utf-8", errors="replace").read(); nodes = []
for m in re.finditer(r"<node [^>]*>", s):
    n = m.group(0)
    b = [int(x) for x in re.findall(r"\d+", (re.search(r'bounds="([^"]*)"', n) or [0, ""])[1])]
    nodes.append((b, (re.search(r'text="([^"]*)"', n) or [None, ""])[1]))
bottom = max(b[3] for b, t in nodes)
inside = sys.argv[2] == "1"      # 之前的某一帧里见过分区标题：标题现在可能已经滚出屏幕上沿
for b, t in sorted(nodes, key=lambda x: x[0][1]):   # 自上而下：标题之后的第一个「Add Custom」就是它的
    if t == "App Icon Sources":
        inside = True
    elif inside and t == "Add Custom" and b[1] > 330 and b[3] < bottom - 120:
        print((b[0] + b[2]) // 2, (b[1] + b[3]) // 2); break
PY
)
    if [ -n "$xy" ]; then echo "$xy"; return 0; fi
    node_xy "App Icon Sources" >/dev/null 2>&1 && seen=1
    as input swipe 540 2000 540 1100 500; sleep 1.5
  done
  return 1
}

# ═══════════════ 场景 ═══════════════

d1() {  # 没有自定义源 → 添加首个 PICP 源
  python "$DIR/mkprefs.py" "$OUT/backup/optcon_prefs.xml" "$OUT/p-d1.xml" \
    custom_sub_sources- active_picp_source- active_ania_source- custom_sources_formats_migrated- picp_size_confirmed- >/dev/null
  prefs_inject "$OUT/p-d1.xml"
  goto_picp >/dev/null || { check "D1 进入应用图标源分区" false; return; }
  tap_below "App Icon Sources" "Add Custom"; sleep 2
  tap_field 0; sleep 1; as input text "SuitePICP"; sleep 1            # 0 = 名称，1 = 地址
  tap_field 1; sleep 1; as input text "http://127.0.0.1:$PORT/good.zip"; sleep 1
  tap_node "PICP zipball"; sleep 1; shot_dialog d1-01-add-dialog || true
  tap_node "Add"; sleep 3
  goto_picp >/dev/null; shot d1-02-after-add    # 冷启动重进：顺带验证落盘
  check "D1 新源出现在应用图标源分区" placed picp "SuitePICP"
  check "D1 新源不在通知图标源分区" not_placed notif "SuitePICP"
  check_eq "D1 落盘格式未被迁移覆盖" "$(prefs_cat | prefs_get custom_sub_sources | json_field format)" PICP_ZIPBALL
  check "D1 迁移标记已写入" test -n "$(prefs_cat | prefs_get custom_sources_formats_migrated)"
  note "D1 落盘的自定义源" "$(prefs_cat | prefs_get custom_sub_sources)"
  note "D1 迁移标记" "$(prefs_cat | prefs_get custom_sources_formats_migrated)"
  tap_row_button "SuitePICP" "More options"; sleep 1; tap_node "Edit source"; sleep 2; shot_dialog d1-03-edit-dialog || true
  check "D1 编辑对话框回显 PICP 为选中" python "$DIR/radios.py" "$OUT/_ui.xml" "$DENSITY" _picp_checked
  check "D1 选项之间的空隙明显大于一项内部的空隙" python "$DIR/radios.py" "$OUT/_ui.xml" "$DENSITY" _grouped
  check "D1 每个选项的标题都在自己那一行之内" python "$DIR/radios.py" "$OUT/_ui.xml" "$DENSITY" _titles_inside
  SCENARIO_DONE=1
}

d2() {  # 升级前数据：不带格式字段、被选为当前应用图标源的自定义源
  python "$DIR/mkprefs.py" "$OUT/backup/optcon_prefs.xml" "$OUT/p-d2.xml" \
    'custom_sub_sources=[{"id":"custom_1","name":"OldZip","url":"https://example.com/old/pack.zip","description":""}]' \
    active_picp_source=custom_1 active_ania_source- custom_sources_formats_migrated- picp_size_confirmed- >/dev/null
  prefs_inject "$OUT/p-d2.xml"
  goto_picp >/dev/null || { check "D2 进入应用图标源分区" false; return; }
  shot d2-01-after-upgrade
  check "D2 旧的自定义应用图标源仍在该分区" placed picp "OldZip"
  check "D2 它不在通知图标源分区" not_placed notif "OldZip"
  check_eq "D2 迁移判为 PICP 整库包" "$(prefs_cat | prefs_get custom_sub_sources | json_field format)" PICP_ZIPBALL
  check_eq "D2 选中项未被重置" "$(prefs_cat | prefs_get active_picp_source)" custom_1
  SCENARIO_DONE=1
}

d3() {  # 无法判定的旧源
  python "$DIR/mkprefs.py" "$OUT/backup/optcon_prefs.xml" "$OUT/p-d3.xml" \
    'custom_sub_sources=[{"id":"custom_2","name":"Mystery","url":"https://example.com/mystery","description":""}]' \
    active_picp_source- active_ania_source- custom_sources_formats_migrated- picp_size_confirmed- >/dev/null
  prefs_inject "$OUT/p-d3.xml"
  local hint="Data format not chosen yet"
  goto_picp >/dev/null || { check "D3 进入应用图标源分区" false; return; }
  shot d3-01-both-lists
  check "D3 待确认的源出现在通知图标源分区" placed notif "Mystery"
  check "D3 待确认的源出现在应用图标源分区" placed picp "Mystery"
  check "D3 两个分区都带提示" test "$(grep -o "$hint" "$OUT/_ui.xml" | wc -l)" -ge 2
  check_eq "D3 落盘为待确认" "$(prefs_cat | prefs_get custom_sub_sources | json_field format)" UNSPECIFIED
  tap_below "App Icon Sources" "Mystery"; sleep 1
  read -r sx sy ty <<< "$(section_sync_xy "App Icon Sources")"
  [ -n "${sx:-}" ] || { check "D3 找到应用图标源的同步按钮" false; return; }
  as input tap "$sx" "$sy"; sleep 3; shot d3-02-sync-refused
  case "$(status_under "$ty")" in *"no data format chosen"*) check "D3 同步被拒并引导编辑" true;; *) check "D3 同步被拒并引导编辑" false;; esac
  check "D3 被拒时没有弹体积确认" no_node "Sync the full icon library?"
  tap_row_button "Mystery" "More options"; sleep 1; tap_node "Edit source"; sleep 2; shot_dialog d3-03-edit-unspecified || true
  check "D3 编辑对话框不预选任何格式" python "$DIR/radios.py" "$OUT/_ui.xml" "$DENSITY" _none_checked
  tap_node "PICP zipball"; sleep 1; tap_node "Save"; sleep 3
  check_eq "D3 选定格式后才能保存，且已保存" "$(prefs_cat | prefs_get custom_sub_sources | json_field format)" PICP_ZIPBALL
  goto_picp >/dev/null; shot d3-04-after-save
  check "D3 选定后提示消失" no_node "$hint"
  check "D3 选定后只留在应用图标源分区" not_placed notif "Mystery"
  check "D3 选定后仍在应用图标源分区" placed picp "Mystery"
  SCENARIO_DONE=1
}

d4_case() {  # 坏源文件名 期望的状态行片段
  python "$DIR/mkprefs.py" "$OUT/backup/optcon_prefs.xml" "$OUT/p-d4.xml" \
    "custom_sub_sources=[{\"id\":\"custom_bad\",\"name\":\"BadSource\",\"url\":\"http://127.0.0.1:$PORT/$1\",\"description\":\"\",\"format\":\"PICP_ZIPBALL\"}]" \
    active_picp_source=custom_bad picp_size_confirmed=true >/dev/null
  prefs_inject "$OUT/p-d4.xml"
  read -r sx sy ty <<< "$(goto_picp)"
  [ -n "${sx:-}" ] || { check "D4 $1 进入应用图标源分区" false; return; }
  as input tap "$sx" "$sy"
  wait_sync_end; sleep 1; dump; shot "d4-${1%.*}"
  local st; st=$(status_under "$ty")
  case "$st" in *"$2"*) check "D4 $1 如实失败：$2" true;; *) check "D4 $1 如实失败：$2" false;; esac
  note "D4 $1 状态行" "$st"
  local h; h=$(picp_hash)
  check_eq "D4 $1 图标库逐字节不变" "$h" "$PICP_HASH0"
  note "D4 $1 图标库哈希" "$h"
  note "D4 $1 图标库目录清单" "$(picp_ls | tr '\n' ';')"
  save_logs "d4-${1%.*}"
  check "D4 $1 无暂存与归档残留" not picp_residue
}
d4() {
  d4_case plain.txt "PICP archive invalid: truncated or not a zip archive"
  d4_case nomatch.zip "no usable icons"
  d4_case truncated.zip "PICP archive invalid: truncated or not a zip archive"
  d4_case truncated-mid.zip "PICP archive invalid: truncated or not a zip archive"
  SCENARIO_DONE=1
}

d5() {  # 上游撤销在通知读取的两条通道上生效（需要外网）
  python "$DIR/mkprefs.py" "$OUT/backup/optcon_prefs.xml" "$OUT/p-d5.xml" \
    'custom_sub_sources=[]' custom_sources_formats_migrated=1 picp_size_confirmed=true "method_enabled_$D5_CUSTOM=true" >/dev/null
  prefs_inject "$OUT/p-d5.xml"
  python - "$OUT/suite-src.png" <<'PY'
import sys
from PIL import Image
img = Image.new("RGBA", (48, 48), (40, 90, 200, 255))
for x in range(6, 42):
    img.putpixel((x, 24), (255, 255, 255, 255)); img.putpixel((24, x), (255, 255, 255, 255))
img.save(sys.argv[1])
PY
  push "$OUT/suite-src.png" /data/local/tmp/suite-src.png
  # 规则库里造两个上游没有的包：同步后都会被判为撤销
  local ct; ct=$(label_of "$P/files")
  su_ "cp /data/local/tmp/suite-src.png $P/files/baked/suite-src.png && chown \$(stat -c %u:%g $P/files) $P/files/baked/suite-src.png && chcon $ct $P/files/baked/suite-src.png && cp $P/files/baked/suite-src.png $P/files/fankes_cache/$D5_PLAIN.png && cp $P/files/baked/suite-src.png $P/files/fankes_cache/$D5_CUSTOM.png && chown \$(stat -c %u:%g $P/files) $P/files/fankes_cache/$D5_PLAIN.png $P/files/fankes_cache/$D5_CUSTOM.png && chcon $ct $P/files/fankes_cache/$D5_PLAIN.png $P/files/fankes_cache/$D5_CUSTOM.png" \
    || check "D5 造出规则库里的多余包" false
  local pkg
  for pkg in $D5_PLAIN $D5_CUSTOM; do   # 共享目录的镜像走应用真实的写入链路（测试接收器）
    as am broadcast -a "$PKG.E2E_BAKE" -n "$PKG/.util.E2ETestReceiver" --es pkg "$pkg" --es method manual --es branch local --es path "$P/files/baked/suite-src.png" >/dev/null; sleep 4
    check "D5 测试接收器写出 $pkg 的共享镜像" as test -f "$SHARED/$pkg.opticon"
  done
  save_logs d5-seeded
  # hook 本地缓存里也各放一份（模拟从旧版本升级上来的设备：0.19.6 之前 hook 自同步常开）
  hook_cache_put "$D5_PLAIN" "$D5_CUSTOM" || check "D5 往 hook 本地缓存里放测试图标" false
  check "D5 前提：hook 本地缓存里有将被撤销的包" hook_cache_has "$D5_PLAIN"
  check "D5 前提：hook 本地缓存里有将被撤销的自定义包" hook_cache_has "$D5_CUSTOM"
  SYSUI_PID0=$(as pidof com.android.systemui | tr -d '\r')
  # 每次用不同的标签：同一个标签再发走的是「更新」路径，hook 的日志与行为都不一样
  local t1="suite$(date +%s)a" t2="suite$(date +%s)b"
  clear_logs
  as cmd notification post -S bigtext -t suite "$t1" "before sync" >/dev/null; sleep 3
  check "D5 同步前 hook 用镜像替换了该包的通知图标" hook_has "createIcons: $D5_PLAIN -> setSmallIcon replaced"
  save_logs d5-before-sync
  open_settings || { check "D5 打开设置页" false; return; }
  read -r sx sy ty <<< "$(section_sync_xy "Notification Icon Sources")"
  [ -n "${sx:-}" ] || { check "D5 找到通知图标源的同步按钮" false; return; }
  as input tap "$sx" "$sy"
  local st; st=$(wait_status "$ty" "ANIP synced|failed|unavailable|Invalid data|did not finish" 300)
  note "D5 规则库同步的状态行" "$st"
  case "$st" in *"ANIP synced"*) check "D5 规则库同步成功" true;; *) check "D5 规则库同步成功" false; save_logs d5-sync-failed; return;; esac
  check "D5 同步收尾完成（镜像 + 撤销清理）" wait_hook "rule sync follow-up" 150
  check "D5 撤销统计：2 个撤销、1 个清理、1 个因自定义保留" hook_has "revoked=2, mirrors removed=1, kept (customized)=1"
  check "D5 被撤销且未自定义的包：共享镜像已删除" not as test -f "$SHARED/$D5_PLAIN.opticon"
  check "D5 被撤销但启用了自定义的包：共享镜像保留" as test -f "$SHARED/$D5_CUSTOM.opticon"
  check "D5 规则库里已没有这两个包" not su_ "test -e $P/files/fankes_cache/$D5_PLAIN.png -o -e $P/files/fankes_cache/$D5_CUSTOM.png"
  check "D5 规则版本戳已写入" as test -s "$SHARED/rules_stamp.opticon"
  check "D5 撤销清单文件列出这两个包" revokes_both
  check "D5 hook 收到版本戳并作废图标缓存" hook_has "Rules stamp changed"
  save_logs d5-after-sync
  note "D5 共享目录清单" "$(ls_shared | tr '\n' ';')"
  note "D5 撤销清单文件内容" "$(shared_flag rules_revoked.opticon | tr '\n' ' ')"
  local i; for i in $(seq 1 10); do hook_cache_has "$D5_PLAIN" || break; sleep 3; done   # hook 侧的清理在自己的线程上
  check "D5 hook 本地缓存里被撤销的包已删除" not hook_cache_has "$D5_PLAIN"
  check "D5 hook 本地缓存里被撤销的自定义包也已删除" not hook_cache_has "$D5_CUSTOM"
  check "D5 hook 日志记下了本地缓存的清理" hook_has "dropped 2 revoked packages from hook-local cache"
  check_eq "D5 全程没有重启系统界面" "$(as pidof com.android.systemui | tr -d '\r')" "$SYSUI_PID0"
  clear_logs
  as cmd notification post -S bigtext -t suite "$t2" "after sync" >/dev/null; sleep 3
  check "D5 同步后的新通知不再被替换成旧图标" not hook_has "setSmallIcon replaced"
  save_logs d5-after-notification
  shot d5-01-after
  SCENARIO_DONE=1
}
revokes_both() {  # 撤销清单文件（每行一个包名）里两个测试包都在
  local list; list=$(shared_flag rules_revoked.opticon)
  printf '%s\n' "$list" | grep -qx "$D5_PLAIN" && printf '%s\n' "$list" | grep -qx "$D5_CUSTOM"
}

d6() {  # 自动更新开关（需要外网）。前提：此前 5 分钟内 hook 没有自同步过（事件触发的两次同步之间有最小间隔）
  python "$DIR/mkprefs.py" "$OUT/backup/optcon_prefs.xml" "$OUT/p-d6.xml" 'custom_sub_sources=[]' custom_sources_formats_migrated=1 picp_size_confirmed=true auto_lib_update=false >/dev/null
  prefs_inject "$OUT/p-d6.xml"
  goto_picp >/dev/null || { check "D6 进入设置页" false; return; }
  SYSUI_PID0=$(as pidof com.android.systemui | tr -d '\r')
  clear_logs
  tap_node "Auto lib update (background)"      # 点行的标题：整行可点是触摸区审计的既定契约
  check "D6 打开后 hook 读到开" wait_hook "auto lib update is now on" 30
  check "D6 不重启系统界面即开始自同步" wait_hook "self-sync ok" 120
  check_eq "D6 偏好记为开" "$(prefs_cat | prefs_get auto_lib_update)" true
  check_eq "D6 共享文件为开" "$(shared_flag auto_lib_update.opticon)" true
  save_logs d6-after-on
  clear_logs
  tap_node "Auto lib update (background)"
  check "D6 关闭后 hook 读到关" wait_hook "auto lib update is now off" 30
  sleep 65
  save_logs d6-after-off
  check "D6 关闭后一个巡检周期内不再同步" not hook_synced
  note "D6 关闭后共享文件" "$(shared_flag auto_lib_update.opticon)"
  check_eq "D6 全程没有重启系统界面" "$(as pidof com.android.systemui | tr -d '\r')" "$SYSUI_PID0"
  # D6c：偏好被重置为关、共享文件仍是开 → 应用启动后自动对齐
  tap_node "Auto lib update (background)"; sleep 3
  check "D6c 造出不一致的前提（共享文件为开）" test "$(shared_flag auto_lib_update.opticon)" = true
  prefs_cat > "$OUT/p-d6c.xml"
  python "$DIR/mkprefs.py" "$OUT/p-d6c.xml" "$OUT/p-d6c2.xml" auto_lib_update=false >/dev/null
  prefs_inject "$OUT/p-d6c2.xml"
  app_launch; sleep 4
  check_eq "D6c 应用启动后共享文件被对齐为关" "$(shared_flag auto_lib_update.opticon)" false
  check "D6c hook 读到关" wait_hook "auto lib update is now off" 30
  SCENARIO_DONE=1
}

d6b() {  # 锁屏密码变体：重启后解锁前不工作、解锁后自行恢复
  python "$DIR/mkprefs.py" "$OUT/backup/optcon_prefs.xml" "$OUT/p-d6b.xml" 'custom_sub_sources=[]' custom_sources_formats_migrated=1 picp_size_confirmed=true auto_lib_update=false >/dev/null
  prefs_inject "$OUT/p-d6b.xml"
  goto_picp >/dev/null || { check "D6b 进入设置页" false; return; }
  tap_node "Auto lib update (background)"
  check "D6b 前提：hook 读到开关已打开" wait_hook "auto lib update is now on" 30
  check_eq "D6b 前提：共享文件为开" "$(shared_flag auto_lib_update.opticon)" true
  keep_crashes   # 重启会清掉日志缓冲区，先留底
  as locksettings set-pin 1234 >/dev/null && PIN_SET=1
  check "D6b 前提：锁屏密码已设置" test "$(as locksettings get-disabled 2>/dev/null | tr -d '\r' | grep -c false)" = 1
  as reboot; S=""; sleep 25
  local i; for i in $(seq 1 40); do S=$(find_serial) || { sleep 5; continue; }; [ "$(as getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 5; done
  require_avd
  sleep 100   # 锁屏状态：超过开机宽限期与一个巡检周期
  check "D6b 解锁前没有同步" not hook_synced
  check "D6b 解锁前 hook 没能建立共享目录观察者" not hook_has "FileObserver established"
  save_logs d6b-locked
  as input keyevent KEYCODE_WAKEUP; sleep 2   # 熄屏时截屏是黑图：先点亮屏幕（仍然锁着）
  shot d6b-01-locked
  as "input keyevent KEYCODE_WAKEUP; sleep 1; input swipe 540 1900 540 700 300; sleep 1.5; input text 1234; sleep 0.5; input keyevent KEYCODE_ENTER"
  check "D6b 解锁后观察者被补建" wait_hook "FileObserver established" 90
  check "D6b 解锁后 hook 读到开" wait_hook "auto lib update is now on" 90
  check "D6b 解锁后自行完成一次同步" wait_hook "self-sync ok" 120
  save_logs d6b-after-unlock
  shot d6b-02-after-unlock
  # 重启之后共享目录要等首次解锁才读得到：解锁前观察者在不在，取决于 hook 有没有机会建立它
  SCENARIO_DONE=1
}

d7() {  # 首次同步的体积确认；同步中取消
  python "$DIR/mkprefs.py" "$OUT/backup/optcon_prefs.xml" "$OUT/p-d7.xml" \
    "custom_sub_sources=[{\"id\":\"custom_slow\",\"name\":\"SlowSource\",\"url\":\"http://127.0.0.1:$PORT/slow.zip\",\"description\":\"\",\"format\":\"PICP_ZIPBALL\"}]" \
    active_picp_source=custom_slow picp_size_confirmed- >/dev/null
  prefs_inject "$OUT/p-d7.xml"
  # 导航不成功就停：往下每一步都会落空，而「没有开始同步」「图标库不变」这类检查会凭空通过
  goto_picp >/dev/null || { check "D7 进入应用图标源分区" false; return; }
  read -r sx sy ty <<< "$(section_sync_xy "App Icon Sources")"
  as input tap "$sx" "$sy"
  check "D7 首次同步弹出体积确认" wait_node "Sync the full icon library?" 0 8
  shot_dialog d7-01-size-dialog || true
  tap_node "Cancel"; sleep 2; dump
  check "D7 取消确认后没有开始同步" no_node "Cancel sync"
  check "D7 取消确认不算已确认" test -z "$(prefs_cat | prefs_get picp_size_confirmed)"
  as input tap "$sx" "$sy"
  check "D7 尚未确认时再次点击仍会弹" wait_node "Sync the full icon library?" 0 8
  [ -n "$(node_xy "Start sync")" ] || { check "D7 找到「Start sync」按钮" false; return; }
  tap_node "Start sync"; sleep 6; dump; shot d7-02-syncing
  check "D7 确认后同步开始并显示进度" grep -q "Downloading" "$OUT/_ui.xml"
  check_eq "D7 确认后偏好才记为已确认" "$(prefs_cat | prefs_get picp_size_confirmed)" true
  [ -n "$(node_xy "Cancel sync")" ] || { check "D7 找到「Cancel sync」按钮" false; return; }
  tap_node "Cancel sync"; wait_sync_end; sleep 1; dump; shot d7-03-cancelled
  local st; st=$(status_under "$ty")
  case "$st" in *"cancelled"*) check "D7 取消后状态行说明已取消并保留旧库" true;; *) check "D7 取消后状态行说明已取消并保留旧库" false;; esac
  note "D7 取消后的状态行" "$st"
  local cy; cy=$(xy_has "cancelled" | cut -d' ' -f2)
  [ -n "$cy" ] && check "D7 取消后的状态行不是错误色" python "$DIR/ink.py" "$OUT/d7-03-cancelled.png" "$cy" neutral
  as input tap "$sx" "$sy"; sleep 3; dump
  check "D7 确认过之后不再弹" no_node "Sync the full icon library?"
  tap_node "Cancel sync"; wait_sync_end   # 收尾：不让慢速源把图标库换掉
  check_eq "D7 图标库逐字节不变" "$(picp_hash)" "$PICP_HASH0"
  SCENARIO_DONE=1
}

d8() {  # 视觉项的采集与几何断言（2.0 倍字体、贴底软键盘、旋转、深色）
  python "$DIR/mkprefs.py" "$OUT/backup/optcon_prefs.xml" "$OUT/p-d8.xml" \
    "custom_sub_sources=[{\"id\":\"custom_long\",\"name\":\"LongDesc\",\"url\":\"https://example.com/long\",\"description\":\"A deliberately long description used to make the edit dialog taller than the screen at the largest font scale, so that scrolling inside the dialog can be verified end to end.\",\"format\":\"PICP_ZIPBALL\"}]" \
    active_picp_source=custom_long picp_size_confirmed=true >/dev/null
  prefs_inject "$OUT/p-d8.xml"
  as settings put system font_scale 2.0; sleep 1
  # 这台模拟器挂着实体键盘，输入法默认只显示侧边工具条。打开「有实体键盘时也显示软键盘」之后，
  # 输入法在实例重启后**头一次**弹出时是贴底键盘；被收起（BACK）之后改为工具条或悬浮键盘。
  # 整跑时 d8 排在会重启的 d6b 之后；单独跑 d8 之前先重启实例。
  as settings put secure show_ime_with_hard_keyboard 1
  # 这里不用 goto_picp：2.0 倍字体下一下滑动未必把分区标题滑进屏幕，添加按钮交给下面的 scroll_to_add 自己找
  open_settings || { check "D8 进入设置页" false; return; }
  as input swipe 540 2100 540 1150 500; sleep 2
  read -r ax ay <<< "$(scroll_to_add)"
  [ -n "${ax:-}" ] || { check "D8 找到「应用图标源」分区的添加按钮" false; return; }
  as input tap "$ax" "$ay"; sleep 2; shot_dialog d8-01-add-2x || true
  check "D8 2.0 倍字体下三个格式选项都可见" test "$(grep -o 'text="PICP zipball"\|text="Legacy JSON"\|text="ANIP bundle"' "$OUT/_ui.xml" | wc -l)" -eq 3
  check "D8 单选行高都不低于 48dp" python "$DIR/radios.py" "$OUT/_ui.xml" "$DENSITY" _min_48
  # 软键盘弹出：对话框要收在键盘上方，按钮还能点到（内容区自己为键盘让位，不是靠系统平移窗口）
  tap_field 0; sleep 3; shot_dialog d8-02-add-2x-keyboard || true
  local top; top=$(ime_top)
  check "D8 前提：软键盘贴底弹出（不是悬浮键盘或侧边工具条）" test -n "$top" -a "$top" -lt 2000
  note "D8 添加对话框：键盘弹出时的键盘上沿" "${top:-（没有贴底键盘）}"
  check "D8 添加对话框：键盘弹出时「取消」「添加」都在键盘上方" buttons_above_ime "${top:-0}"
  check "D8 2.0 倍字体下每个选项的标题都在自己那一行之内" python "$DIR/radios.py" "$OUT/_ui.xml" "$DENSITY" _titles_inside
  as input swipe 540 1200 540 500 500; sleep 1.5; shot_dialog d8-02b-add-2x-scrolled || true   # 在键盘上方的可滚动区域里滚
  check "D8 2.0 倍字体滚动后三个格式选项仍都可见" test "$(grep -o 'text="PICP zipball"\|text="Legacy JSON"\|text="ANIP bundle"' "$OUT/_ui.xml" | wc -l)" -eq 3
  check "D8 2.0 倍字体下滚动后的行高都不低于 48dp" python "$DIR/radios.py" "$OUT/_ui.xml" "$DENSITY" _min_48
  check "D8 2.0 倍字体下选项之间的空隙明显大于一项内部的空隙" python "$DIR/radios.py" "$OUT/_ui.xml" "$DENSITY" _grouped
  check "D8 键盘弹出、内容滚动之后按钮仍在键盘上方" buttons_above_ime "${top:-0}"
  tap_node "Cancel"; sleep 2     # 关对话框即可，不用 BACK 收键盘（那会让输入法改显示工具条）
  # 对话框关闭后页面位置偶尔会变（见 scroll_to_text 的说明）：先把目标行滚进屏幕，并留证据
  scroll_to_text "LongDesc"
  tap_row_button "LongDesc" "More options"; sleep 1; tap_node "Edit source"; sleep 2
  tap_field 1; sleep 3; shot_dialog d8-03-edit-2x-keyboard || true
  local top2; top2=$(ime_top)
  check "D8 前提：编辑对话框里软键盘贴底弹出" test -n "$top2" -a "$top2" -lt 2000
  check "D8 编辑对话框：键盘弹出时「取消」「保存」都在键盘上方" buttons_above_ime "${top2:-0}"
  as input keyevent KEYCODE_BACK; sleep 1        # 收起键盘（后面不再需要键盘）
  check "D8 收起键盘后对话框还在" node_xy "Edit Source"
  shot_dialog d8-03-edit-2x-top || true
  as input swipe 780 1750 780 1050 600; sleep 1.5; shot_dialog d8-04-edit-2x-scrolled || true
  as settings put system accelerometer_rotation 0; as settings put system user_rotation 1; sleep 2
  check "D8 横屏下对话框底板不透明（不是过渡帧）" shot_dialog d8-05-landscape
  check "D8 旋转（界面重建）后对话框与输入仍在" grep -q 'text="https://example.com/long"' "$OUT/_ui.xml"
  as settings put system user_rotation 0; sleep 2
  as cmd uimode night yes; sleep 2
  check "D8 深色模式下对话框底板不透明（不是过渡帧）" shot_dialog d8-06-dark
  check "D8 深色模式下对话框仍在" node_xy "Edit Source"
  SCENARIO_DONE=1
}

d9() {  # 共享目录被整体移走再放回：观察者随之作废，巡检发现目录回来后重新建立
  check "D9 前提：共享目录在" as test -d "$SHARED"
  clear_logs
  su_ "mv $SHARED $SHARED.removed" || { check "D9 移走共享目录" false; return; }
  check "D9 移走共享目录" not as test -d "$SHARED"
  check "D9 hook 发现监听的目录没了、放掉观察者" wait_hook "shared dir removed" 30
  su_ "mv $SHARED.removed $SHARED" || { check "D9 放回共享目录" false; return; }
  check "D9 放回共享目录" as test -d "$SHARED"
  # 放掉监听时要让它停止监听；否则旧监听成了孤儿，目录放回时会再回一声——同一目录上挂两个活着的监听
  check "D9 移走目录时只放掉一次监听（没有孤儿监听）" test "$(hook_logs | grep -c 'observer dropped')" = 1
  check "D9 目录回来后一个巡检周期内观察者重新建立" wait_hook "FileObserver established" 100
  save_logs d9
  SCENARIO_DONE=1
}

# ═══════════════ 规则库：上游对齐（批 UP） ═══════════════
# 共同的前提：偏好从备份出发，只有内置规则源、自动更新关、不带清单地址的设备验证入口（偏好键
# e2e_anip_manifest_url，只有调试包读它）。本机清单把「官方发布清单」指到测试源，测试源提供的包就是
# 上游 2026-11-26 那一份（924 条），只是换了标签 aaaaaaa——所以下面的已知答案对真实上游和本机清单都成立。

anip_prefs() {  # anip_prefs 标签 [mkprefs 的额外参数…]
  local tag="$1"; shift
  python "$DIR/mkprefs.py" "$OUT/backup/optcon_prefs.xml" "$OUT/p-$tag.xml" \
    'custom_sub_sources=[]' active_ania_source- e2e_anip_manifest_url- custom_sources_formats_migrated=1 auto_lib_update=false "$@" >/dev/null
  prefs_inject "$OUT/p-$tag.xml"
}

# anip_restore 场景名：回到上游官方地址同步一次，把规则库放回真实上游（用过本机清单的场景收尾用；需要外网）
anip_restore() {
  anip_prefs "$1-restore"
  clear_logs
  rule_sync || { check "$1 还原：打开设置页并找到同步按钮" false; return; }
  local st; st=$(rule_wait)
  note "$1 还原后的状态行" "$st"
  case "$st" in *"ANIP synced"*|*"already up to date"*|*"Already up to date"*) check "$1 还原到真实上游" true;; *) check "$1 还原到真实上游" false; save_logs "$1-restore-failed"; return;; esac
  check "$1 还原的收尾完成" wait_hook "rule sync follow-up" 150
  check "$1 还原后快照的版本记录来自官方发布通道" grep_has "$(rule_source)" '"channel":"release"'
  check "$1 还原后标签不是本机清单的" not grep_has "$(rule_source)" '"tag":"aaaaaaa"'
  save_logs "$1-restore"
}

d10() {  # 真实上游（需要外网）：官方发布通道；别名与 SVG 两类包
  anip_prefs d10
  # 这一次一定要真的下载：抹掉快照的版本记录（0.19.7 及之前的快照本来就没有这个文件）
  su_ "rm -f $RULES/source.json"
  clear_logs
  rule_sync || { check "D10 打开设置页并找到规则库的同步按钮" false; return; }
  local st; st=$(rule_wait)
  note "D10 规则库同步的状态行" "$st"
  shot d10-01-after-sync
  check "D10 状态行带上游版本（日期与标签）" grep_has "$st" "upstream [0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\} ([0-9a-f]\{7\})"
  case "$st" in *"ANIP synced"*) check "D10 规则库同步成功" true;; *) check "D10 规则库同步成功" false; save_logs d10-sync-failed; return;; esac
  check "D10 同步收尾完成（镜像 + 撤销清理）" wait_hook "rule sync follow-up" 150
  check "D10 走的是官方发布通道，包通过了大小与哈希核对" hook_has "sha256 verified)"
  local tag; tag=$(hook_logs | grep -m1 -o "ANIP source: release [0-9a-f]*" | awk '{print $NF}')
  note "D10 上游版本" "${tag:-（日志里没有）}"
  note "D10 快照构成" "$(built_line)"
  save_logs d10-sync
  if [ "$tag" = "e98c906" ]; then
    check "D10 已知答案：924 个规则图标" grep_has "$st" "ANIP synced 924 rule icons"
    check "D10 已知答案：814 个位图 + 10 个 SVG 渲染 + 100 个别名，缺失与未解析都是 0" \
      hook_has "ANIP snapshot built: 924 icons (declared 924, written 814, rendered 10, aliased 100, carried over 0, missing 0, unresolved 0)"
  else
    note "D10 上游版本已不是已知答案那一份（e98c906）" "条数与构成以上面两行为准"
  fi
  check "D10 快照的版本记录：官方发布通道" grep_has "$(rule_source)" '"channel":"release"'
  check "D10 快照的版本记录：标签与日志一致" grep_has "$(rule_source)" "\"tag\":\"${tag:-none}\""
  check_eq "D10 快照里的 PNG 数 = 状态行里的条数" "$(rule_count)" "$(printf '%s' "$st" | grep -o 'ANIP synced [0-9]*' | awk '{print $3}')"
  # 别名与 SVG 两类包
  check "D10 别名包在快照里（抖音极速版）" su_ "test -f $RULES/com.ss.android.ugc.aweme.lite.png"
  check "D10 别名包的图与目标包逐字节相同" su_ "cmp -s $RULES/com.ss.android.ugc.aweme.lite.png $RULES/com.ss.android.ugc.aweme.png"
  check "D10 SVG 包渲染进了快照（理想汽车）" su_ "test -s $RULES/com.chehejia.oc.m01.png"
  pull_root "$RULES/com.chehejia.oc.m01.png" "$OUT/d10-svg-rendered.png"
  pull_root "$RULES/com.ss.android.ugc.aweme.png" "$OUT/d10-upstream-bitmap.png"
  check "D10 渲染出的 PNG：完整、能解码、不超过 150px、有可见像素" python "$DIR/pngstat.py" "$OUT/d10-svg-rendered.png" 150
  check "D10 取证：上游位图 PNG 完好" python "$DIR/pngstat.py" "$OUT/d10-upstream-bitmap.png"
  note "D10 渲染出的 PNG" "$(python "$DIR/pngstat.py" "$OUT/d10-svg-rendered.png")"
  check "D10 两类包都有共享目录的镜像" anip_mirrored com.ss.android.ugc.aweme.lite com.chehejia.oc.m01
  SYSUI_PID0=$(as pidof com.android.systemui | tr -d '\r')
  anip_shade D10 svg "$RULES/com.chehejia.oc.m01.png" d10-02
  anip_shade D10 bitmap "$RULES/com.ss.android.ugc.aweme.png" d10-03
  check_eq "D10 全程没有重启系统界面" "$(as pidof com.android.systemui | tr -d '\r')" "$SYSUI_PID0"
  note "D10 视觉判读（需要原生视觉模型）" "对照 d10-02-3-shade.png（SVG 渲染出的图标）与 d10-03-3-shade.png（上游位图）：两者在通知栏里的观感是否一致；d10-svg-rendered.png / d10-upstream-bitmap.png 是原图"
  SCENARIO_DONE=1
}

d10b() {  # 彩色 SVG（本机测试源的自定义规则源）：渲染保留原色，通知栏里按单色着色
  python "$DIR/mkprefs.py" "$OUT/backup/optcon_prefs.xml" "$OUT/p-d10b.xml" \
    "custom_sub_sources=[{\"id\":\"custom_vector\",\"name\":\"SuiteVector\",\"url\":\"http://127.0.0.1:$PORT/anip-extra.zip\",\"description\":\"\",\"format\":\"ANIP_BUNDLE\"}]" \
    active_ania_source=custom_vector e2e_anip_manifest_url- custom_sources_formats_migrated=1 auto_lib_update=false >/dev/null
  prefs_inject "$OUT/p-d10b.xml"
  clear_logs
  rule_sync || { check "D10b 打开设置页并找到规则库的同步按钮" false; return; }
  local st; st=$(rule_wait)
  note "D10b 规则库同步的状态行" "$st"
  case "$st" in *"ANIP synced"*) check "D10b 自定义规则源同步成功" true;; *) check "D10b 自定义规则源同步成功" false; save_logs d10b-sync-failed; return;; esac
  check "D10b 自定义源直接下载归档，状态行不带上游版本" not grep_has "$st" "upstream"
  check "D10b 同步收尾完成（镜像 + 撤销清理）" wait_hook "rule sync follow-up" 150
  note "D10b 快照构成" "$(built_line)"
  save_logs d10b-sync
  check "D10b 已知答案：926 = 924 + 彩色 SVG + 别名" \
    hook_has "ANIP snapshot built: 926 icons (declared 926, written 814, rendered 11, aliased 101, carried over 0, missing 0, unresolved 0)"
  check_eq "D10b 快照里的 PNG 数" "$(rule_count)" 926
  check "D10b 彩色 SVG 渲染进了快照" su_ "test -s $RULES/com.local.vector.png"
  pull_root "$RULES/com.local.vector.png" "$OUT/d10b-vector-rendered.png"
  check "D10b 渲染保留了 SVG 原本的红色（没有重新着色）" python "$DIR/pngstat.py" "$OUT/d10b-vector-rendered.png" 150 --red
  check "D10b 别名包的图与目标包逐字节相同" su_ "cmp -s $RULES/com.local.alias.png $RULES/com.ss.android.ugc.aweme.png"
  check "D10b 两类包都有共享目录的镜像" anip_mirrored com.local.vector com.local.alias
  SYSUI_PID0=$(as pidof com.android.systemui | tr -d '\r')
  anip_shade D10b vector "$RULES/com.local.vector.png" d10b-01 tint
  check_eq "D10b 全程没有重启系统界面" "$(as pidof com.android.systemui | tr -d '\r')" "$SYSUI_PID0"
  note "D10b 视觉判读（需要原生视觉模型）" "d10b-01-3-shade.png：红色圆形图标在通知栏里的观感；d10b-vector-rendered.png 是原图"
  # 还原：回到内置源。多出来的两个包随这次同步被撤销
  anip_prefs d10b-back
  clear_logs
  rule_sync || { check "D10b 还原：打开设置页并找到同步按钮" false; return; }
  st=$(rule_wait)
  note "D10b 还原后的状态行" "$st"
  case "$st" in *"ANIP synced"*|*"already up to date"*|*"Already up to date"*) check "D10b 还原到内置源（上游）" true;; *) check "D10b 还原到内置源（上游）" false; save_logs d10b-restore-failed; return;; esac
  check "D10b 还原的收尾完成" wait_hook "rule sync follow-up" 150
  check "D10b 还原后两个测试包已撤销" not su_ "test -e $RULES/com.local.vector.png -o -e $RULES/com.local.alias.png"
  check "D10b 还原后两个测试包的镜像已删" not anip_mirrored com.local.vector com.local.alias
  save_logs d10b-restore
  SCENARIO_DONE=1
}

d11() {  # 上游没有新版本：第二次同步只取清单，不下载、不重建（本机清单）
  anip_prefs d11 "e2e_anip_manifest_url=http://127.0.0.1:$PORT/anip-release.json"
  local mark; mark=$(server_mark)
  clear_logs
  rule_sync || { check "D11 打开设置页并找到规则库的同步按钮" false; return; }
  local st; st=$(rule_wait)
  note "D11 第一次同步的状态行" "$st"
  case "$st" in *"ANIP synced"*) check "D11 第一次同步成功（本机清单指向的包）" true;; *) check "D11 第一次同步成功（本机清单指向的包）" false; save_logs d11-first-failed; return;; esac
  check "D11 第一次同步的收尾完成" wait_hook "rule sync follow-up" 150
  save_logs d11-first
  check "D11 状态行带本机清单的日期与标签" grep_has "$st" "upstream 2026-11-26 (aaaaaaa)"
  check_eq "D11 第一次同步：清单 1 次、包 1 次" "$(server_count "$mark" /anip-release.json)/$(server_count "$mark" /anip-bundle-aaaaaaa.zip)" 1/1
  check "D11 快照的版本记录：官方发布通道 + 标签 aaaaaaa" grep_has "$(rule_source)" '"channel":"release","tag":"aaaaaaa"'
  local h0; h0=$(rule_hash)
  shot d11-01-first
  # 第二次：同一个版本。不冷启动（冷启动会清掉状态行），在同一个页面上再点一次
  mark=$(server_mark)
  clear_logs
  sleep 2
  read -r sx sy RS_TY <<< "$(section_sync_xy "Notification Icon Sources")"
  [ -n "${sx:-}" ] || { check "D11 第二次同步：找到同步按钮" false; return; }
  as input tap "$sx" "$sy"; sleep 2
  st=$(rule_wait)
  note "D11 第二次同步的状态行" "$st"
  shot d11-02-second
  case "$st" in *"already up to date"*|*"Already up to date"*) check "D11 第二次同步：已是最新" true;; *) check "D11 第二次同步：已是最新" false; save_logs d11-second-failed; return;; esac
  check "D11 「已是最新」带条数与上游版本" grep_has "$st" "[Aa]lready up to date · 924 icons · upstream 2026-11-26 (aaaaaaa)"
  check_eq "D11 第二次同步：清单 1 次、包 0 次" "$(server_count "$mark" /anip-release.json)/$(server_count "$mark" /anip-bundle-aaaaaaa.zip)" 1/0
  check "D11 日志：这个版本已经在本机，没有下载" hook_has "ANIP source: release aaaaaaa is already in place (924 icons) — nothing to download"
  check "D11 快照一个字节都没动" test "$(rule_hash)" = "$h0"
  check "D11 没有重建快照" not hook_has "ANIP snapshot built"
  check "D11 没有做收尾（镜像与撤销清理）" not hook_has "rule sync follow-up"
  check "D11 无暂存与归档残留" not rule_residue
  save_logs d11-second
  anip_restore "D11"
  SCENARIO_DONE=1
}

d12_body() {  # 包核对不上：退回整库归档，状态行如实（不带上游版本）。整库归档来自真实上游，需要外网
  echo badsha > "$DIR/payloads/mode.txt"
  anip_prefs d12 "e2e_anip_manifest_url=http://127.0.0.1:$PORT/anip-release.json"
  local mark; mark=$(server_mark)
  clear_logs
  rule_sync || { check "D12 打开设置页并找到规则库的同步按钮" false; return; }
  local st; st=$(rule_wait)
  note "D12 规则库同步的状态行" "$st"
  case "$st" in *"ANIP synced"*) check "D12 核对失败后同步仍然成功（退回整库）" true;; *) check "D12 核对失败后同步仍然成功（退回整库）" false; save_logs d12-failed; return;; esac
  check "D12 日志：官方包核对失败、退回整库" hook_has "failed its size / sha256 verification — falling back to the branch archive"
  check_eq "D12 本机清单取了 1 次、包下载了 1 次（下到了才核对）" "$(server_count "$mark" /anip-release.json)/$(server_count "$mark" /anip-bundle-aaaaaaa.zip)" 1/1
  check "D12 状态行如实：整库归档没有版本可报" not grep_has "$st" "upstream"
  check "D12 状态行如实：条数是整库的" grep_has "$st" "ANIP synced 924 rule icons"
  check "D12 快照的版本记录：整库归档" grep_has "$(rule_source)" '"channel":"archive"'
  check_eq "D12 快照完整（924 个 PNG）" "$(rule_count)" 924
  check "D12 同步收尾完成" wait_hook "rule sync follow-up" 150
  check "D12 核对不过的包没有留下来" not rule_residue
  shot d12-01-after-sync
  save_logs d12
  anip_restore "D12"
  SCENARIO_DONE=1
}
d12() {  # d12_body 跑完（含中途返回）都把测试源改回正常的哈希
  d12_body
  echo good > "$DIR/payloads/mode.txt"
}

d13_probe() { as curl -s -m 12 -o /dev/null -w '%{http_code}\n' "$1/robots.txt" 2>/dev/null | tr -d '\r' | head -1; }
d13() {  # 没有代理的网络（U1-c）：同步要么成功，要么如实报「下载失败」——批 UP 去掉了前置探测
  anip_prefs d13
  PROXY0=$(as settings get global http_proxy | tr -d '\r')
  check "D13 前提：模拟器带着全局代理" test -n "$PROXY0" -a "$PROXY0" != "null" -a "$PROXY0" != ":0"
  note "D13 全局代理（开始前）" "$PROXY0"
  as settings put global http_proxy :0; sleep 3
  check_eq "D13 已去掉全局代理" "$(as settings get global http_proxy | tr -d '\r')" ":0"
  note "D13 直连连通性（设备上的 curl 取 robots.txt 的状态码；设备上没有 curl 时为空）" \
    "github.com=$(d13_probe https://github.com) raw.githubusercontent.com=$(d13_probe https://raw.githubusercontent.com)"
  clear_logs
  rule_sync || { check "D13 打开设置页并找到规则库的同步按钮" false; return; }
  local st; st=$(rule_wait)
  note "D13 没有代理时同步的状态行" "$st"
  case "$st" in
    *"ANIP synced"*|*"already up to date"*|*"Already up to date"*) check "D13 没有代理时同步成功（GitHub 直连可达）" true;;
    *"Download failed"*) check "D13 没有代理时如实报「下载失败」（不是前置探测的误拦）" true;;
    *) check "D13 没有代理时同步成功或如实报下载失败" false;;
  esac
  check "D13 状态行没有出现前置探测时期的用语（网络不可用）" not grep_has "$st" "unavailable"
  note "D13 通道日志" "$(hook_logs | grep -e "release channel unavailable" -e "could not be downloaded" -e "ANIP source" | sed 's/^ *[0-9-]* [0-9:.]* *[0-9]* *[0-9]* . //' | head -4 | tr '\n' ';')"
  shot d13-01-after
  save_logs d13
  as settings put global http_proxy "$PROXY0"; sleep 3
  check_eq "D13 全局代理已恢复" "$(as settings get global http_proxy | tr -d '\r')" "$PROXY0"
  SCENARIO_DONE=1
}

d14() {  # 总开关关 + 自动更新开（批 MT M5）：总开关关着时自同步不跑；重新打开后恢复（需要外网）
  anip_prefs d14
  check "D14 前提：总开关开着" test "$(shared_flag master_switch.opticon)" = true
  open_settings || { check "D14 打开设置页" false; return; }
  clear_logs
  tap_node "Master Switch"; sleep 2
  check "D14 关总开关先弹确认" wait_node "Turn off master switch?" 0 10
  tap_node "Turn off"; sleep 3
  check_eq "D14 总开关已关（共享文件）" "$(shared_flag master_switch.opticon)" false
  # 用开关的切换把循环叫醒几轮（事件触发）：总开关关着，这些轮都不该同步
  tap_node "Auto lib update (background)"
  check "D14 hook 读到自动更新为开" wait_hook "auto lib update is now on" 30
  tap_node "Auto lib update (background)"
  check "D14 hook 读到自动更新为关" wait_hook "auto lib update is now off" 30
  tap_node "Auto lib update (background)"
  check "D14 hook 再次读到开（循环活着，被事件叫醒）" wait_hook "auto lib update is now on" 30
  sleep 90
  save_logs d14-master-off
  check "D14 总开关关着时 hook 不自同步（被事件叫醒之后 90 秒内）" not hook_synced
  check_eq "D14 自动更新的共享文件为开（开关本身是生效的）" "$(shared_flag auto_lib_update.opticon)" true
  # 总开关重新打开：再给一个事件，自同步照常进行
  clear_logs
  tap_node "Master Switch"; sleep 3
  check_eq "D14 总开关重新打开" "$(shared_flag master_switch.opticon)" true
  tap_node "Auto lib update (background)"
  check "D14 hook 读到关" wait_hook "auto lib update is now off" 30
  tap_node "Auto lib update (background)"
  check "D14 hook 读到开" wait_hook "auto lib update is now on" 30
  check "D14 总开关打开后自同步恢复" wait_hook "self-sync ok" 150
  note "D14 恢复后的同步结果" "$(hook_logs | grep -m1 "self-sync" | sed 's/^.*self-sync //')"
  save_logs d14-master-on
  shot d14-01-after
  tap_node "Auto lib update (background)"; sleep 3
  check_eq "D14 收尾：自动更新为关" "$(shared_flag auto_lib_update.opticon)" false
  SCENARIO_DONE=1
}

# ═══════════════ 备份 / 还原 / 报告 ═══════════════

setup() {
  require_avd
  adb -s "$S" logcat -c -b all
  # 宿主负载过高就不跑（见 lib.sh 的「宿主机负载」）：这时跑出来的失败和被测包无关
  local h r; h=$(host_load); r="${h#*,}"
  note "环境：宿主机负载（开始时）" "CPU ${h%%,*}%，提交率 ${r%%,*}%，硬缺页 ${r#*,} 每秒"
  if [ "${SUITE_ALLOW_LOADED_HOST:-0}" != "1" ]; then
    check "宿主机负载不高（CPU 持续低于 60%；连续三次采样）" not host_busy \
      || { log "宿主机负载过高，不跑。等宿主空闲再试；确要跑设 SUITE_ALLOW_LOADED_HOST=1"; return 1; }
  fi
  check "被测包已安装" as pm path "$PKG"
  # hook 在不在，不能靠日志里翻「安装」那一行——系统界面进程启动几分钟后它就被刷掉了。
  # 每次都重启系统界面进程，让 hook 随**当前安装的包**重新加载（也保证了跑的是新装的那一版）。
  log "重启系统界面进程，让 hook 随当前安装的包重新加载"
  su_ "kill \$(pidof com.android.systemui)"
  local i; for i in $(seq 1 40); do hook_has "self-sync loop starting" && break; sleep 2; done
  check "hook 已注入系统界面" hook_has "self-sync loop starting"
  # 系统界面进程不在的这段时间里，框架把锁屏置为显示中，新进程起来之后才撤掉。宿主机忙的时候新进程
  # 起不来，锁屏就一直挂着、点击全被丢——确认它撤掉了再往下跑（见 lib.sh 的「设备状态」）
  check "重启系统界面之后设备恢复可交互（有窗口持有焦点、锁屏没有挂着）" device_ready 90 \
    || { device_diag "after-systemui-restart"; log "重启系统界面之后设备没有恢复可交互，终止"; return 1; }
  check "root 可用" test "$(su_ "id -u" | tr -d '\r')" = 0
  app_stop
  prefs_pull "$OUT/backup/optcon_prefs.xml"
  check "已备份偏好文件" test -s "$OUT/backup/optcon_prefs.xml"
  DENSITY="${DENSITY:-$(python -c "print(round($(as wm density | tr -d '\r' | awk '{print $NF}') / 160.0, 4))")}"
  FONT0=$(as settings get system font_scale | tr -d '\r'); NIGHT0=$(as cmd uimode night | tr -d '\r' | awk '{print $NF}')
  ACCEL0=$(as settings get system accelerometer_rotation | tr -d '\r'); IME0=$(as settings get secure show_ime_with_hard_keyboard | tr -d '\r')
  COOLDOWN0=$(as settings get global notification_cooldown_enabled | tr -d '\r')
  NOTIF_GRANTED0=$(as dumpsys package "$PKG" | tr -d '\r' | grep -c "POST_NOTIFICATIONS: granted=true")
  as test -f "$SHARED/$D5_PLAIN.opticon" && HAD_MIRROR_PLAIN=1
  as test -f "$SHARED/$D5_CUSTOM.opticon" && HAD_MIRROR_CUSTOM=1
  MASTER0=$(shared_flag master_switch.opticon)
  PICP_HASH0=$(picp_hash)
  adb -s "$S" logcat -c -b all   # 从干净的状态开始记（崩溃总闸只看本次运行期间）
  note "环境：被测包" "$(as dumpsys package "$PKG" | tr -d '\r' | grep -m1 -E 'versionName' | tr -s ' ' | sed 's/^ //') / $(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null)"
  note "环境：设备" "$AVD_NAME（$S）Android $(as getprop ro.build.version.release | tr -d '\r') / API $(as getprop ro.build.version.sdk | tr -d '\r')，密度 $DENSITY px/dp"
  note "环境：测试源" "http://127.0.0.1:$PORT/（经模拟器的全局代理落到宿主）"
  if [ -n "$PICP_HASH0" ]; then
    su_ "cd $P/files && tar -cf /data/local/tmp/opticon-suite-picp.tar picp"
    check "已备份图标库（内容哈希 $PICP_HASH0）" as test -s /data/local/tmp/opticon-suite-picp.tar
    note "图标库目录清单（开始前）" "$(picp_ls | tr '\n' ';')"
  fi
  as settings put global notification_cooldown_enabled false
  as pm grant "$PKG" android.permission.POST_NOTIFICATIONS
  python "$DIR/serve.py" "$PORT" 2>"$OUT/server.log" & SERVER_PID=$!
  # 起服务后轮询到它能应答为止（要先离线生成测试样本，几秒钟）；探不到就停，不让后面的场景白跑
  local i up=1
  for i in $(seq 1 40); do
    sleep 1
    [ "$(curl --noproxy '*' -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/plain.txt" 2>/dev/null)" = "200" ] && { up=0; break; }
  done
  check "本机测试源已启动（$i 秒后能应答）" test "$up" = 0
  echo good > "$DIR/payloads/mode.txt"   # 规则库清单的哈希模式：每轮从「正确」开始（d12 会临时改成 badsha）
  [ "$(fails)" -eq 0 ]
}

restore() {
  log "开始还原设备…"
  app_stop
  if [ -s "$OUT/backup/optcon_prefs.xml" ]; then
    prefs_inject "$OUT/backup/optcon_prefs.xml" || { check "还原：偏好文件" false; RESTORE_FAILED=1; }
    check_eq "还原：偏好文件与备份逐字节一致" "$(prefs_cat | sha256sum | cut -c1-64)" "$(tr -d '\r' < "$OUT/backup/optcon_prefs.xml" | sha256sum | cut -c1-64)" || RESTORE_FAILED=1
  fi
  [ -n "$FONT0" ] && as settings put system font_scale "$FONT0"
  [ -n "$NIGHT0" ] && as cmd uimode night "$NIGHT0"
  [ -n "$ACCEL0" ] && as settings put system accelerometer_rotation "$ACCEL0"
  [ -n "$IME0" ] && { [ "$IME0" = "null" ] && as settings delete secure show_ime_with_hard_keyboard || as settings put secure show_ime_with_hard_keyboard "$IME0"; }
  [ -n "$COOLDOWN0" ] && as settings put global notification_cooldown_enabled "$COOLDOWN0"
  [ "$NOTIF_GRANTED0" = "0" ] && as pm revoke "$PKG" android.permission.POST_NOTIFICATIONS
  # d13 临时去掉过模拟器的全局代理：没改回来就改回来
  if [ -n "${PROXY0:-}" ]; then
    [ "$(as settings get global http_proxy | tr -d '\r')" = "$PROXY0" ] || as settings put global http_proxy "$PROXY0"
    check_eq "还原：模拟器的全局代理" "$(as settings get global http_proxy | tr -d '\r')" "$PROXY0" || RESTORE_FAILED=1
  fi
  echo good > "$DIR/payloads/mode.txt"
  clear_notifications
  if as test -d "$SHARED.removed"; then    # d9 中途失败时的兜底：目录被移走了就放回去
    su_ "mv $SHARED.removed $SHARED" && check "还原：共享目录已放回" as test -d "$SHARED"
  fi
  if [ "$PIN_SET" = "1" ]; then
    as locksettings clear --old 1234 >/dev/null
    check "还原：锁屏密码已清除" pin_cleared
  fi
  if [ -n "$PICP_HASH0" ] && [ "$(picp_hash)" != "$PICP_HASH0" ]; then
    local ct; ct=$(label_of "$P/files")
    su_ "cd $P/files && rm -rf picp && tar -xf /data/local/tmp/opticon-suite-picp.tar && chcon -R $ct picp"
    check_eq "还原：图标库内容哈希与备份一致" "$(picp_hash)" "$PICP_HASH0" || RESTORE_FAILED=1
  fi
  # 测试造出来的共享目录镜像：经媒体库删除（应用自己走的就是这条路），只删套件创建的
  local pkg n
  for pkg in "$D5_PLAIN" "$D5_CUSTOM"; do
    if [ "$pkg" = "$D5_PLAIN" ] && [ "$HAD_MIRROR_PLAIN" = "1" ]; then continue; fi
    if [ "$pkg" = "$D5_CUSTOM" ] && [ "$HAD_MIRROR_CUSTOM" = "1" ]; then continue; fi
    if as test -f "$SHARED/$pkg.opticon"; then
      n=$(as content delete --uri content://media/external/downloads --where "\"_display_name='$pkg.opticon'\"" 2>&1 | tr -d '\r' | tr '\n' ' ')
      note "还原：删除测试镜像 $pkg" "$n"
      check "还原：测试镜像 $pkg 已清除" not as test -f "$SHARED/$pkg.opticon"
    fi
  done
  su_ "rm -f $HOOK_CACHE/$D5_PLAIN.png $HOOK_CACHE/$D5_CUSTOM.png"   # 以防场景中途失败
  su_ "rmdir /data/user/0/com.android.systemui/cache/opticon_hook_icons 2>/dev/null"   # 早期版本误建在普通存储下的空目录
  su_ "rm -f $P/files/fankes_cache/$D5_PLAIN.png $P/files/fankes_cache/$D5_CUSTOM.png /data/local/tmp/opticon-prefs.xml /data/local/tmp/suite-src.png /data/local/tmp/opticon-suite-picp.tar $P/files/baked/suite-src.png $P/files/baked/suite-plant.png $P/files/baked/$D5_PLAIN.png $P/files/baked/$D5_CUSTOM.png"
  check "还原：临时文件已清除" not su_ "test -e /data/local/tmp/opticon-prefs.xml -o -e /data/local/tmp/suite-src.png -o -e /data/local/tmp/opticon-suite-picp.tar -o -e $P/files/baked/suite-plant.png"
  # d14 动过总开关：它不在偏好文件里，备份还原不了，只核对
  if [ -n "${MASTER0:-}" ]; then
    check_eq "还原：总开关与开始前一致" "$(shared_flag master_switch.opticon)" "$MASTER0" || RESTORE_FAILED=1
  fi
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null
  report
  CLEANED=1
}
# 正常结束走 finish（还原并定退出码）；准备阶段过后才装 EXIT 兜底（准备阶段还没动过设备）
on_exit() { [ "${CLEANED:-0}" = "1" ] || restore; }
finish() { restore; [ "$(fails)" -eq 0 ] && [ "$RESTORE_FAILED" = "0" ]; }

report() {
  # 崩溃总闸：任何场景里被测应用或系统界面崩过，整份报告都不算通过
  check "全程被测应用没有崩溃" not crash_of "$PKG"
  check "全程系统界面没有崩溃" not crash_of com.android.systemui
  local f="$OUT/REPORT.md" pass fail
  pass=$(grep -c '^PASS' "$RESULTS"); fail=$(grep -c '^FAIL' "$RESULTS")
  {
    echo "# 设备验证报告 $(date +%F\ %T)"
    echo
    echo "通过 $pass 项，失败 $fail 项。"
    echo
    echo "| 结果 | 检查点 |"; echo "|---|---|"
    awk -F'\t' '$1!="NOTE" { printf "| %s | %s %s |\n", ($1=="PASS"?"✅":"❌"), $2, ($3!=""?$3:"") }' "$RESULTS"
    echo
    echo "## 原始证据"
    echo
    awk -F'\t' '$1=="NOTE" { printf "- %s：`%s`\n", $2, $3 }' "$RESULTS"
    echo
    echo "截图、界面树与各阶段日志（\`*.log\`）在本目录；审美判读见 d8-*，逐项结论以本表为准——没列出的场景没有在设备上验证。"
  } > "$f"
  log "报告：$f（通过 $pass，失败 $fail）"
  [ "$fail" -eq 0 ] && [ "$RESTORE_FAILED" = "0" ]
}

want() {  # 场景是否要跑
  local s="$1"
  case ",$SKIP," in *",$s,"*) return 1;; esac
  [ -z "$ONLY" ] || case ",$ONLY," in *",$s,"*) return 0;; *) return 1;; esac
}

CLEANED=0
if ! setup; then log "准备阶段失败，终止"; trap on_exit EXIT; restore; exit 1; fi
trap on_exit EXIT
for s in d1 d2 d3 d4 d5 d6 d6b d7 d8 d9 d10 d10b d11 d12 d13 d14; do
  want "$s" || continue
  if [ "$DEVICE_DEAD" = "1" ]; then log "设备已经不接收点击，$s 不跑了"; continue; fi
  h=$(host_load); r="${h#*,}"
  if [ "${SUITE_ALLOW_LOADED_HOST:-0}" != "1" ] && host_busy; then   # 跑到一半宿主机忙起来也得停
    note "宿主机负载（$s 之前）" "CPU ${h%%,*}%，提交率 ${r%%,*}%，硬缺页 ${r#*,} 每秒"
    check "宿主机负载不高（$s 之前）" false
    log "宿主机负载过高，终止。等宿主空闲再跑"
    break
  fi
  # 设备已经不接收点击时后面的场景跑了也白跑（场景里有的导航跑在子 shell 里，DEVICE_DEAD 传不回来，所以在这里再看一眼）
  if ! device_ready 15; then
    device_diag "before-$s"
    check "设备可交互（$s 之前：有窗口持有焦点、锁屏没有挂着）" false
    log "设备不接收点击，终止。看报告目录 diag-before-$s/ 里的现场"
    break
  fi
  log "──── $s ────"
  require_avd
  SCENARIO_DONE=0
  "$s"
  # 场景中途返回时，它后面的检查项会凭空消失——这里明确记一笔
  check "场景 $s 跑到了最后（没有中途返回）" test "$SCENARIO_DONE" = 1
done
finish
exit $?
