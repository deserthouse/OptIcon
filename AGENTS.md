# OptIcon 项目规范

> Android 通知图标自适应 Xposed 模块（LSPosed / libxposed API 102，仅 hook SystemUI，Material 3 Expressive）。
> 融合 ANIA 规则库（fankes 书面授权）+ PICP 按需下载 + 图标包提取 + 兼容重绘。
> 全局规范见 `~/.zcode/AGENTS.md`；事故过程档见项目记忆（按需召回）。

## 事实源与文档

- **待办唯一事实源 = `MIGRATION_NOTES.md` 第六节**；`project_status.md` 只留版本规则/变更史（避免双源失真）。**两文件为维护者本地私有工作文件，不入公开仓**：公开 clone 中缺失属预期，`scripts/check_doc_sync.sh` 对缺失文件 SKIP；公开贡献者以 Releases 与 git 历史为准。
- **`CHANGELOG.md`（公开仓）= 发布说明唯一事实源**：版本 bump 即建对应条目（KEEP 格式，Unreleased 留给发布后的新变化）；Release 正文从同条目提取，不另行现写（上游§9.7）。`check_doc_sync.sh` 校验其含当前版本号。
- 出包前必跑 `scripts/check_doc_sync.sh`（版本号四处一致性，维护者本机），不绿不 tag。
- 状态变更当场重写待办节；汇报以 git log / build.gradle.kts / 桌面产物为准。

## 流程对齐（2026-10-21 用户指令：对齐上游 `Projects/AGENTS.md`，后续严格遵循）

- **审查/修复/发布流程以上游 `C:/Users/deser/Projects/AGENTS.md`（经全局规范第十一节每会话注入）为准**；本文件只写项目专属补充。优先级：用户最新指令 > 项目既有契约（keystore/命名/真机禁令等）> 上游通用条款；发现冲突记录具体冲突项再议，不擅自改写项目契约。
- **验收条件纪律**：WORKPLAN 每项的验收条件 = 关闭条件。未执行的验收必须列入当轮报告的「未验证项」（含原因与补验条件），「代码完成」不得表述为「验收通过」，不许把局部通过相加成完整验收（上游§六/§七）。
- **版本出包五步（先门禁后提交，先提交后构建）**：① 改版本号（build.gradle.kts 唯一事实源）→ ② check_doc_sync PASS + 测试/lint 绿 → ③ **提交版本 bump** → ④ 从该提交 SHA `assembleRelease`（`--no-configuration-cache`）→ ⑤ 将来 tag 指向该 SHA。构建后任何代码变化 = 新构建：发布前重建直接顶替并记录哈希更替；**发布后**禁止同名覆盖，一律新版本/build。tag/push/Release 由 AI 自主执行（**2026-10-24 用户授权：版本由 AI 管理，发版不需逐次批准**）；发布后逐阶段独立核验（tag SHA/资产 digest/CHANGELOG 同条目）仍为必做。
- **预发布一致性**：alpha/beta/rc 的应用内徽章配色、tag、Release.prerelease、文案四者一致——rc 与 beta 同用预发布配色（secondary），不得用正式色（上游§9.6）。
- **视觉审查模型门槛（2026-10-21 用户指令，上游§五硬性前提）**：视觉审查/读图/截图判读/视觉验收/视觉复审必须由具备**原生视觉能力**的模型执行；纯文本模型不得以 UI dump、像素采样数值或转述充当视觉判断——不具备原生视觉能力的轮次，视觉项一律列未验证。出图纸与派发任务（含子代理）时必须按此分派：代码班不做视觉裁决，视觉段交原生视觉模型。
- **真机走查**：已按用户指令移出发版工作流（2026-10-22）——不作为 rc→正式门禁，真机表现转自然观察（用户自发反馈+朋友机），不设排期、不写"待真机验收"类门禁表述。

## 放行标准（2026-11-24 订立；用户授权 AI 主导开发方向）

- **beta**：① 四项门禁全绿（单测 `--rerun-tasks` / lint 对基线无新增错误 / 文档同步 / 中英字符串齐平）；② 设备套件 `bash scripts/device/run_suite.sh` 全过——**触碰同步或 hook 的改动必跑**，纯文案改动可免但须在验证范围里写明；③ 发布前经一轮不接触施工记录的独立审查：P1/P2 清零，P3 有处置结论；④ Release 正文与 CHANGELOG 按三栏写明验证范围。
- **正式版**：某个 beta 在用户真机上跑满两周、期间没有 P1/P2，方可转正；转正只改版本号与更新日志，不带新功能。真机走查仍按上节（自然观察，不设排期）。
- **验证范围三栏**（设备上验证过 / 仅 JVM 单测 / 未验证）是报告与 Release 正文的硬格式：没列出的就是没验证，「代码完成」不等于「验收通过」。
- **发版五步走 `python scripts/release.py`**（`preflight → build → tag → publish → verify`；`notes` 起草 Release 正文与三栏验证范围）。工具里写死了硬约束：工作区干净、版本与签名核对、验证范围文件必须存在才允许发布、令牌只经 `git credential fill` 进内存、发布后重新下载比对摘要且不改旧条目；`verify` 不带令牌也能跑。不手工拼发版命令。
- **CI**（`.github/workflows/ci.yml`，检查步骤在 `.github/actions/checks`）跑单测、lint、中英字符串齐平与文档同步；CI 红了不发版。**看结果用 `python scripts/ci_status.py main`**（逐个 job 给结论；`--wait` 等结果、`--logs` 取日志、`--rerun` 重跑失败的任务）：门禁是「首个 job 失败则自动换一台运行器重试一次，两次都失败才判红」，整次运行的 conclusion 只反映重试之后的结果。`.github/workflows/diagnose.yml` 是只手动触发的依赖解析诊断。
- **先等 CI 绿，再构建**（2026-11-27 实证）：并回 main 后先推 main、等 CI 绿，再 preflight → build → tag → publish → verify。CI 的修复算新提交，构建记录随提交作废；顺序反了就得重建（当天为此重建过一次，被顶替的构建从未发布）。

## 版本与构建

- 版本语义 `MAJOR.MINOR.PATCH-stage`（alpha→beta→rc→正式）；版本号唯一事实源 = build.gradle.kts 的 `MODULE_VERSION_NAME`，任何地方不再手写。
- versionCode 逐版 +1 永不复用；APK 命名 `OptIcon-v$VER-YYYYMMDD-HHMMSS.apk`。
- release keystore `~/.android/opticon-release.jks`（SHA-256 指纹记录在 MIGRATION_NOTES）；真机交付一律 assembleRelease（签名混装=INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
- gradle wrapper 可用（`sh ./gradlew`；2026-11-24 核实，此前「wrapper jar 损坏」的记录已过期）；main 常绿 + feat/xxx 分支模型，已合并分支及时清理；产物/apk/archive/密钥不入库。
- **测试门禁固定 `--rerun-tasks`**（UP-TO-DATE≠重跑，外审§2 教训）；**停止条件（路线 B 刹车，2026-10-22 外审§9）**：来源选择/取消/保存失败/重建恢复的任务契约成立且实屏证明有效后，停止为更 Expressive 追加装饰、动画或框架——新方案不能在同任务下改善理解/步骤/维护成本时，保留现有布局。

## 项目专属教训

- **视觉承诺=交互兑现（触摸区审计原则，2026-09-24 定档；2026-09-25 R9 波纹审计后升四问）**：每个"看起来能点"的视觉元素（M3 设置行/卡片/chip/带值标签或箭头的行），必须有覆盖其全部视觉边界的点击行为；反之亦然。**审计方法**：不能只 grep modifier 顺序（padding 包不包 clickable）——那只能抓"热区缩水"类，抓不住"整行根本没接 onClick"类（Switch 行事故：四个开关行只有 ~52dp 开关本体响应，行其余 90% 是死区，modifier 顺序完全正确）。正确做法是逐行四问：①这个视觉的样子承诺了什么交互？②代码兑现了吗？③兑现的边界=视觉的边界吗？（③的"视觉"明确指**最外层视觉容器**——卡片/chip 的完整圆角轮廓，不是行内文字块）④触摸目标与波纹 ≥48dp 且覆盖容器全部边界吗？（尾随交互组件禁显式 `size(<48dp)` 覆盖，IconButton/TextButton 默认即 48dp，视觉大小由内部 Icon 控制；卡片内容层"裁剪先行、缩进内移"——Column(clip) 无 padding，行 clickable 先行 + padding(16dp) 内置，波纹才能满幅扫过卡片轮廓）。**验收方法**：打"死区坐标"实测（行最左端/行中央避开尾随控件/卡片边缘），不是看代码绿了就算；**交付门禁加"按压瞬间波纹边界对照"**——静态截图抓不住动画边界的错，需在按压瞬间抓帧对照波纹边界与容器轮廓（SettingsCard padding→clip 顺序事故的教训：代码结构正确≠波纹满幅）。相关：全局规范七.5 的 clickable/padding/clip 顺序语义条目是"怎么把热区做对"，本条是"怎么审计热区做没做"。
- **ViewModel 构造期的协程必须在构造完成之后启动（2026-11-24 设备套件实测）**：`viewModelScope` 用的是 `Main.immediate`，init 块里 `launch` 的协程体会**就地执行**；其中的 `withContext(Dispatchers.IO)` 在后台那段完成得足够快时**不挂起**、就地继续往下跑，此时声明在 init 之后的属性还是 null → 空指针崩溃（`SettingsViewModel` 打开设置页崩溃，栈里 `<init>` 直接压着 `invokeSuspend`）。这是线程调度决定的概率事件，宿主或设备负载高时才撞得上，单次手测抓不到。init 块里启动协程一律用 `launchAfterInit`（不带 immediate 的 `Dispatchers.Main`，先投递再执行）；设备套件报告末尾有「全程被测应用 / 系统界面没有崩溃」总闸。
- **Compose 对话框默认不为软键盘让位（2026-11-25 设备套件实证）**：`Dialog` / `AlertDialog` 默认不指定软键盘模式，系统选的是 `adjustPan`（窗口管理器里看得到 `sim={adjust=pan}`）——窗口不缩小，只在焦点输入框会被键盘盖住时整块上移，焦点在上面的输入框时底部按钮就被键盘盖住。含输入框的对话框要显式 `DialogProperties(decorFitsSystemWindows = false)` + `Modifier.safeDrawingPadding()`（Material 3 1.5.0-alpha22 的 AlertDialog 自己不处理窗口占位）。验证口径：读窗口管理器里输入法的 `type=ime frame=` 上沿，断言按钮都在它上方；「输入法已显示」不够——模拟器挂着实体键盘时输入法可能只是侧边工具条或悬浮键盘，都不占屏幕底部。
- **设备验证套件的三道闸（2026-11-25 实证）**：①**宿主机忙就不跑**——宿主在跑游戏时（CPU 85%、空闲内存 4.6G）整跑，虚拟机被饿到，系统界面进程重启后起不来（启动超时被杀），整轮的失败与被测包无关；套件开跑前和每个场景前各看一次宿主负载（CPU 连续三次采样都 ≥ 60% 就不跑；**可用内存不能当判据**——游戏占满的那晚它反而比平时高）。②**杀系统界面进程 = 框架立即把锁屏置为显示中**（`wm_set_keyguard_shown`），新进程起来才撤；新进程起不来就「没有窗口持有焦点」，之后每一次点击都被输入分发器丢掉（`Dropping event because there is no touchable window`），截图与界面树却可能看着正常。点击集体失效时先查 `dumpsys window` 的 `mCurrentFocus` 与 `KeyguardServiceDelegate.showing`，不要去猜应用卡顿；导航前断言「有窗口持有焦点 + 锁屏没挂着」，不满足就留现场（报告目录 `diag-*/`）并停。③**界面树采集先删旧文件、带超时**——`uiautomator dump` 失败不写新文件，旧文件会让「点击生效」这类判断凭空为真；场景的导航失败即停，否则「没有开始同步」「图标库不变」这类检查会凭空通过。
- **GitHub Actions 的「失败后重试」要用步骤级 continue-on-error（2026-11-27 踩过）**：任务级 `continue-on-error` 会把失败报成成功，`needs.<job>.result` 永远是 success，按它写的重试条件永远不成立——那次整次运行显示绿，实际首个 job 失败、重试被跳过。正确做法：组合动作所在的步骤 `continue-on-error`，后一步把 `steps.<id>.outcome` 写进 job 输出，重试 job 按输出决定是否运行。本地组合动作（`./.github/actions/…`）要先 `actions/checkout` 才找得到；标量里带冒号的 `run:` 要加引号。改完这类接线要在一次性分支上演练「首败 → 重试成功」与「两边都败 → 判红」两条路，并逐个 job 核对结论。另：第三方 `android-actions/setup-android` 替人接受 SDK 许可时只答固定条数，许可一多就失败——直接 `yes | sdkmanager --licenses`；依赖仓库（Maven Central / Plugin Portal，均在 Cloudflare 后）的解析会间歇失败，同一提交重跑即过，命中依赖缓存时不会发生。
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
