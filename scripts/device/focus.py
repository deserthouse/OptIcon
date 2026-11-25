# 设备现在能不能接收点击：有窗口持有焦点（mCurrentFocus），且锁屏没有挂着
# （KeyguardServiceDelegate 的 showing）。标准输入是 dumpsys window 的输出。
# 输出 ok / nofocus / keyguard / unknown —— 给 lib.sh 的 device_ready 用。
#
# 为什么需要它：系统界面进程被杀之后，框架立刻把锁屏置为显示中，等新进程起来才撤掉。虚拟机被宿主机
# 挤到的时候新进程起不来，锁屏就一直挂着——没有窗口持有焦点，之后每一次点击都被输入分发器丢掉
# （"Dropping event because there is no touchable window"），界面上却什么都看不出来。
import re
import sys

s = sys.stdin.read()
m = re.search(r"mCurrentFocus=(\S+)", s)
if not m:
    print("unknown")
elif m.group(1) == "null":
    print("nofocus")
else:
    k = re.search(r"KeyguardServiceDelegate[ \t]*\n[ \t]*showing=(\w+)", s)
    print("keyguard" if k and k.group(1) == "true" else "ok")
