# OptIcon UI 审查结论汇总（2026-10-03 · 静态+AVD 双篇合一入口）

> 本文是**结论入口**：全部问题、修复路线、样板与共性一页读完；证据与细节按下表取用。
> | 篇章 | 文档 | 内容 |
> |---|---|---|
> | 静态代码审计（同日） | 本目录 `ui-audit-2026-10-03.md` | O-1~O-11，file:line 证据 |
> | AVD 真机走查（同日） | 本目录 `ui-audit-avd-2026-10-03.md` | A-1~A-5，截图证据（`Projects\android-ui-review-2026-10-03\opticon\`，14 张） |
> | 三项目横评总报告 | `C:\Users\deser\Projects\android-ui-review-2026-10-03.md` / `...-avd.md` | 交叉对比与共性 |
> 走查版本：v0.9.4-beta（versionCode 57 debug 构建）；走查环境：A15_clean（API 35，无 LSPosed）——恰好覆盖"模块未激活用户"场景

## 一、总体结论

**结构健康、完成度高，零 crash**：AVD 走查冷启/导航/搜索/筛选/详情/设置/深色模式全通过，开关行整行热区实测生效（历史死区事故修复未回归）；静态审计确认工具链基线零偏差、已知 Compose 陷阱组全部规避。主要欠账集中在三块：**一处安全规范红线（签名口令入仓）、两处运行时状态反馈缺陷（Checking… 无超时、确认框按钮语义）、一批确认交互与 i18n 短板**。合计静态 11 项 + 走查 5 项。

## 二、全部问题清单（两篇合并，按修复优先级排序）

| 序 | 来源 | 严重度 | 问题 | 细节 |
|---|---|---|---|---|
| 1 | O-1 | **中高·安全** | release 签名口令默认值写在构建脚本里随仓分发（字面量此处不复写，见 build.gradle.kts 原行；因已随历史入仓，若曾公开分发需轮换口令） | 静态篇（build.gradle.kts:44-46） |
| 2 | O-11 | 中·规范 | `packageAndBakeApk` 仍向桌面拷贝 APK（2026-09-19 改令禁止） | 静态篇 |
| 3 | A-1 | **P2·功能** | 无 LSPosed 环境首页状态卡永久 "Checking…"：无超时无终态，与设置页（同秒判 Inactive）状态源不同步 | AVD 篇 |
| 4 | A-2 | **P2·UX** | Restart SystemUI 确认框按钮 "OK"/"Confirm Restart" 无 Cancel；实测 OK=取消，与通用预期相反 | AVD 篇 |
| 5 | O-2 | 中 | 下拉刷新开头清空列表 → 刷新期间整列表闪空（应只置 isScanning，照抄 Prunoid） | 静态篇 |
| 6 | O-4 | 中 | 删除自定义订阅源无确认无撤销（同屏关总开关/重启 SystemUI 都有确认，防护不一致） | 静态篇 |
| 7 | O-6 | 中·i18n | PICP 按钮 "PICP icon loaded/Pull PICP icon" 等硬编码英文，中文用户看英文 | 静态篇 |
| 8 | O-5 | 中 | XML 窗口背景仅 Light、无 values-night → 深色冷启闪白（照抄 SDGun sdg_window_bg） | 静态篇（V-1 待录屏验证强度） |
| 9 | O-3 | 中·性能 | 列表过滤+分组在组合内无 remember，高频重组全量重算 | 静态篇（V-2 待帧率验证） |
| 10 | B 交叉→O-10 | 低中 | 订阅同步失败靠英文子串判定，文案一改判定失效（应结构化 ok/severity） | 静态篇 |
| 11 | O-7 | 中低 | Hero 状态卡无限循环形变动画，页面可见期间不停产帧 | 静态篇 |
| 12 | O-8 | 低中·无障碍 | 订阅源行编辑/删除 IconButton contentDescription=null | 静态篇 |
| 13 | A-3 | P3 | 图标语义：Master Switch 用虫子图标（与 Verbose Logging 撞图）；拼图图标两行复用 | AVD 篇 |
| 14 | A-4 | P3 | 长括号 ID 换行后右括号独占一行 | AVD 篇 |
| 15 | A-5 | P3 | 图例 "Icon (#FFF)" 暴露 hex 色值 | AVD 篇 |
| 16 | O-9 | 低 | 死代码：StatusChip/ParameterPanel 无调用方；MATERIAL_ICONS "Palette" 误映射心形 | 静态篇 |

## 三、修复路线图（建议施工顺序与量级）

1. **当天可清**：#1 签名口令出仓（删 getOrElse 默认值，改 Prunoid 的"缺失回退 debug"或 SDGun 的 fail-fast；若仓库曾公开分发→轮换口令）；#2 桌面拷贝任务删除。
2. **一个迭代内（用户可感 P2）**：#3 Checking… 加超时+统一状态源；#4 对话框改 "Cancel/Restart"。两者都是小改动、直击走查暴露的用户困惑。
3. **半天批量**：#5 刷新闪空（一处改动）、#6 删源确认（复用现有确认框）、#7 i18n 四处补资源。
4. **中期**：#8 深色窗口背景（抄 SDGun）、#9 过滤 remember（抄 Prunoid ListScreen）、#10 同步失败结构化、#11 动画可见性驱动、#12 无障碍 CD。
5. **顺路打磨**：#13~#16。
6. 修完后跑一遍 AVD 篇"验证通过项"回归基线 + 项目自带 `scripts/check_doc_sync.sh`。

## 四、本项目是样板的部分（别的项目要抄这里的，别丢）

- 触控几何纪律：48dp 底线、波纹满幅"裁剪先行、缩进内移"、四问审计法（已写进项目 AGENTS.md）；
- 未保存修改返回拦截（save/discard）、ON_RESUME 自动刷新、图标解码 60ms 防抖、LazyVerticalGrid 复合 key 防重、写失败回滚+提示、首装宽限期防误报、MIUI 可见性受限专用空态；
- OptShapes/OptSpacing/MotionTokens 令牌文件——静态篇战略建议"抽共享组件模板"的种子。

## 五、跨项目共性中与本项目相关的

- **静默无反馈路径**（三项目共性病灶）：本项目的份额就是 #3/#4；
- **共享组件模板**（静态篇战略 #10）：SettingsCard/SettingItem/SectionTitle/CreditEntry/版本徽章已与 Prunoid 形成两份带注释互认的复制，**第三个同类项目动工前必须抽模板**，本项目是种子仓候选；
- **动态取色口径**（战略 #11）：本项目强制开，建议统一为"默认开+设置可关"（SDGun 模式）；
- 未覆盖项：TalkBack/大字体矩阵审查（双篇均未做，排期时补）。
