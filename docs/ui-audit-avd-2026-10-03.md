# OptIcon UI 真机走查审计与修改意见（AVD 篇）

> 审查日期：2026-10-03 · 方式：AVD 真机走查（A15_clean 实例，API 35，1080x2400；本仓 assembleDebug 构建 v0.9.4-beta-20261003-112724 → force-stop 后 `am start --activity-clear-task` 冷启 → 确定性坐标逐页导航 → 逐屏截图 → 读图评审；异常以 `logcat -b crash` 定性）
> 姊妹文档：同日**静态代码审计**见本目录 `ui-audit-2026-10-03.md`（代码层问题 O-1~O-11）；三项目横评总报告见 `C:\Users\deser\Projects\android-ui-review-2026-10-03-avd.md`
> 证据截图：`C:\Users\deser\Projects\android-ui-review-2026-10-03\opticon\`（下文以 `opticon/NN` 简称，共 14 张）
> 环境说明：走查在**无 LSPosed 的 clean 实例**上做——这正好覆盖"模块未激活"用户的首启体验，下列 P2×2 均是该场景暴露的
> 状态：全程零 crash；已与静态篇同批提交入库（2026-10-03）

## 一、问题清单（按严重度）

| # | 严重度 | 问题 | 证据 | 与静态篇关系 |
|---|---|---|---|---|
| A-1 | **P2** | **首页 hook 状态卡在无 LSPosed 环境下永久停留 "Checking… / Talking to the hook, one moment…"**：超过 1 分钟无变化、无超时、无错误反馈；同期设置页同秒即准确判出 "LSPosed Status: Inactive — Enable this module in LSPosed Manager…"。两处状态源不同步，首页等待无终态（全局规范"等待必须带超时+错误通道"的 UI 对应物） | opticon/01、02（间隔 14s 两帧一致）vs opticon/03 | 静态篇未覆盖（运行时行为） |
| A-2 | **P2** | **Restart SystemUI 确认框按钮语义混乱**：标题已是 "Confirm Restart"，按钮却是 "OK"（左、蓝）与 "Confirm Restart"（右、红），没有 Cancel。实测点 "OK" = 取消（对话框关闭、无重启发生）——与 "OK=确认执行" 的通用预期相反，红色按钮在右侧也偏离 M3 惯例（破坏性确认通常在起始侧） | opticon/08 → opticon/09 | 静态篇只记了"有确认"，未抓到按钮语义问题 |
| A-3 | P3 | 图标语义重复/不当：Module Control 区 **Master Switch 与 Verbose Logging 同用虫子图标**（主总开关用 bug 图标语义不当）；Notification area icons 行与 LSPosed Status 行复用拼图图标 | opticon/03、07 | — |
| A-4 | P3 | 括号内长 ID 换行破相：App Icon Sources 条目 "Perfect Icons Completion Project (Perfect-Icons-Completion-Project" 换行后右括号独占一行 | opticon/05、07 | — |
| A-5 | P3 | 状态栏预览图例 "Icon (#FFF)" 直接暴露 hex 色值 | opticon/12 | — |

## 二、修改意见（按建议施工顺序）

1. **A-1 状态卡加超时与终态**：hook 探测等待带超时（建议 3~5s），超时后转"Inactive / 无法联系模块"并给一行指引（对齐设置页现有文案）；首页与设置页**统一状态源**（同一个探测结果缓存），避免两页各判各的。
2. **A-2 对话框按钮重排**：改 "Cancel / Restart" 双钮（或至少左键显式叫 Cancel）；破坏性动作的确认钮放终止侧、用 error 色容器。改动是纯文案+顺序，收益直接。
3. **A-3~A-5 顺路打磨**：Master Switch 换 `Icons.Rounded.Tune`（或 AppSettingsAlt）类总控图标、Verbose Logging 保留 bug 图标即可独占语义；长 ID 用 `softWrap=false + overflow=Ellipsis` 或移出括号单独一行；图例 hex 换描述性文案（如 "Default white icon"）或加 tooltip。

## 三、真机验证通过项（回归基线，改动后别退化）

- **开关行整行热区**：按本项目"Switch 行死区"审计法实测，点 Verbose Logging 行最左端成功翻转开关（opticon/04）——历史事故的修复真实生效；
- 修改意见里的其他交互注意别破坏：搜索实时过滤+计数联动、跨主题/重建状态保持（opticon/11、13）；筛选空态有图标+指引（opticon/10）；详情页总开关关闭时子选项灰置并解释（opticon/12）；
- 设置页诊断准确性（Inactive / Root Unavailable + 操作指引，opticon/03）是 A-1 修复的对齐目标；
- 深色模式全量适配、Credits 五方署名齐全（opticon/06、13、14）；
- 冷启/导航/对话框全程零 crash（crash 缓冲区干净）。

## 四、修复验证建议

A-1/A-2 修完后在 **clean 非 root 实例**复验（本次走查脚本可直接复用：冷启 → 等 10s 截图比对状态卡终态；点 Restart → 检查按钮文案与两键各自行为）。A-2 属于"按压瞬间波纹边界"之外的对话框语义，静态截图即可验收。
