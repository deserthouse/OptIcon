# OptIcon 项目规范

> Android 通知图标自适应 Xposed 模块（LSPosed / libxposed API 102，仅 hook SystemUI，Material 3 Expressive）。
> 融合 ANIA 规则库（fankes 书面授权）+ PICP 按需下载 + 图标包提取 + 兼容重绘。
> 全局规范见 `~/.zcode/AGENTS.md`；事故过程档见项目记忆（按需召回）。

## 事实源与文档

- **待办唯一事实源 = `MIGRATION_NOTES.md` 第六节**；`project_status.md` 只留版本规则/变更史（避免双源失真）。
- 出包前必跑 `scripts/check_doc_sync.sh`（版本号三处一致性），不绿不 tag。
- 状态变更当场重写待办节；汇报以 git log / build.gradle.kts / 桌面产物为准。

## 版本与构建

- 版本语义 `MAJOR.MINOR.PATCH-stage`（alpha→beta→rc→正式）；版本号唯一事实源 = build.gradle.kts 的 `MODULE_VERSION_NAME`，任何地方不再手写。
- versionCode 逐版 +1 永不复用；APK 命名 `OptIcon-v$VER-YYYYMMDD-HHMMSS.apk`。
- release keystore `~/.android/opticon-release.jks`（SHA-256 指纹记录在 MIGRATION_NOTES）；真机交付一律 assembleRelease（签名混装=INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
- gradle wrapper jar 损坏，用本地 dists 的 gradle-9.5.1 直编；main 常绿 + feat/xxx 分支模型，已合并分支及时清理；产物/apk/archive/密钥不入库。

## 项目专属教训

- **视觉承诺=交互兑现（触摸区审计原则，2026-09-24 定档）**：每个"看起来能点"的视觉元素（M3 设置行/卡片/chip/带值标签或箭头的行），必须有覆盖其全部视觉边界的点击行为；反之亦然。**审计方法**：不能只 grep modifier 顺序（padding 包不包 clickable）——那只能抓"热区缩水"类，抓不住"整行根本没接 onClick"类（Switch 行事故：四个开关行只有 ~52dp 开关本体响应，行其余 90% 是死区，modifier 顺序完全正确）。正确做法是逐行三问：①这个视觉的样子承诺了什么交互？②代码兑现了吗？③兑现的边界=视觉的边界吗？**验收方法**：打"死区坐标"实测（行最左端/行中央避开尾随控件/卡片边缘），不是看代码绿了就算。相关：全局规范七.5 的 clickable/padding/clip 顺序语义条目是"怎么把热区做对"，本条是"怎么审计热区做没做"。
- **防御性 hook 反转事故（v0.3.1→A17）**：给"空接口"挂的无条件 hook 在新版本填充真实实现后反转为全局破坏——一切 hook 必须带配置守卫；升级目标 API 后重审全部 hook 点（A17 审计报告在 `docs/A17_HOOK_AUDIT.md`）。
- **hook 失效排查顺序**：① LSPosed Manager 查作用域勾选（覆盖安装/重启会掉勾选，第一嫌疑）→ ② `/data/adb/lspd/log/modules_*.log` grep 包名（比 logcat 可靠）→ ③ 才轮到内核/SELinux/代码层。直接 SQL 改 modules_config.db 守护进程不感知，必须 Manager UI 操作。
- **FileObserver 后缀过滤 bug 藏了两个月**：声称生效的功能必须回到代码路径核对（全局规范四.5 的出处）。
- **#12 滚动闪退关账（v0.7.8）**：真因是图标包 appfilter.xml 重复 `<item>` 行 → LazyVerticalGrid key 冲突（IllegalArgumentException），非 OOM；`distinctBy { componentRaw to drawableName }` 二元组去重修复（7437→7141）。压测要超量级：Arcticons 48132 图标 235 次 fling 零崩溃。
- LSPosed 状态检测三要素：`ActivityThread.currentApplication()` 真身上下文（SystemContext 的 opPackageName="android" 会被 provider 拒）+ Provider uid 直比鉴权 + 心跳新鲜度窗口（5min 重报/10min 窗口）。
- `pkill -f` 子串误杀（A17 上连坐壁纸引擎）→ 用 `kill $(pidof)` 精确匹配。
- 通知测试前 adb unroot（uid=0 的 post 通知被 NameNotFoundException 静默拒绝）；App 内 kill 重启 SystemUI 壁纸不丢失，force-stop 会。
- 三方模块交互（用户真机装有 Global Icon Pack）需对照实验定界。

## 合规红线

- ANIA 规则库已获 fankes 书面授权（673+ 应用）；PICP 不预制不分发（GitHub 公开 ≠ 开源）；NotificationIconFix 仅定位为灵感参考。
- 致谢四区域保持一致（README 致谢区 / 工作原理 / CREDITS_DRAFT / App 内）。

## 协作

- 2026-09-11 起用户授权 AI 全权维护，用户只提需求与验收；汇报用状态表格。
- 用户是 Android 发烧友但不写代码，审美细节敏感（字号/间距/对齐/ripple 裁剪都会抠）；署名体系（澪狼 / Mio Ookami / Ling the Wolp）细节见项目记忆 deserthouse-identity-naming。
- 真机：PixelOS Android 17（PLK110，SukiSU root）；朋友机：一加 OOS / 小米 HyperOS（跨 ROM 测试矩阵）。真机只做白名单只读采集。
- 迁移/备份必带：`.git` 全史 + keystore + `archive/` + `memory/` 工作日志。
