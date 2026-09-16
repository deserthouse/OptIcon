# 标准③ 图标配送通道迁移 — 设计草案 v1

> 2026-09-12 · A2 预研产出 · **状态：待用户批准**
> 目标版本：v0.7.0-alpha 头号 · 硬约束：先于 #13/#14 实施

## 一、现状（四层回退）

```
App 烘焙 PNG
 └─[生产]→ Download/OptIcon/{pkg}.opticon (MediaStore.Downloads 写入)
              SystemUI hook 直读同路径 (File API)
     ↓ miss
    HookLibSync 自同步（SystemUI 进程内自建规则缓存）
     ↓ miss
    /data/local/tmp/opticon_baked (root/dev 通道)
     ↓ miss
    filesDir / IconContentProvider (dev 回退)
```

### 现有通道问题清单
| # | 问题 | 影响 | 严重度 |
|---|---|---|---|
| P1 | 用户可见污染：文件管理器「下载」里出现大量 `.opticon` 文件（部分 ROM 文件管理器无视 `.nomedia`/隐藏规则） | 每个用户都会撞见的观感问题 | 高 |
| P2 | 生命周期：卸载 App 不清理公共目录文件；孤儿图标累积 | 存储占用 + 卫生 | 中 |
| P3 | 信任边界：公共目录任何 app 可写，SystemUI 读取内容未被校验（理论上可被恶意 app 投毒位图；实际风险=位图渲染，低但存在） | 安全洁癖 | 低 |
| P4 | AOSP 对 system_app 读公共目录的策略持续收紧（未来版本风险） | 前瞻 | 中 |

## 二、候选方案对比

### 方案 A · root 通道：/data/local/tmp/opticon（推荐主方案）
- **写**：App 侧 `su -c` 复制烘焙 PNG 到 `/data/local/tmp/opticon/`，chmod 644 + dir 755
- **读**：SystemUI hook File 直读（现 WORLD_BAKED_DIR 路径已在代码中，v0.3.1 验证过）
- ✅ 用户不可见（无文件管理器入口）；无 MediaStore 开销；Iconify/ANIP 同类思路
- ❌ 依赖 root（但我们本就要求 root 环境，LSPosed 模块无 root 不成立——**非新增依赖**）
- ⚠️ 关键未知（→ S2 实验）：真机上 `shell_data_file`/`magisk_file` SELinux 域下 system_app 能否直接读；不能则需 `restorecon` 到可读域或 Magisk 模块化 contexts

### 方案 B · Magisk 模块化目录：/data/adb/opticon
- **写**：su -c 写入；**读**：SystemUI 直读
- ✅ 天然隐藏（/data/adb 需 root 才可见）、magisk_file 域（SystemUI 读 magisk_file 模拟器上已验证 v0.3.1）
- ❌ 依赖具体 root 实现（SukiSU/Magisk/KernelSU 目录语义一致，风险低）；Magisk 更新历史上有 domain 变更（30.x 改过 magisk_file 标签）
- 定位：方案 A 的备选，S2 一起测

### 方案 C · 保持 MediaStore 但深度隐藏（保守回退）
- 文件名加前缀 `.`、写 `.nomedia`、RELATIVE_PATH 深层目录
- ✅ 改动最小；❌ 不解决 P2/P3，部分 ROM 无效 —— 仅作为迁移期的回退通道保留

### 否决项
- `Android/data/<pkg>/`：SystemUI 读他应用私有目录 = app_data_file 域隔离，死路（v0.2.2 已证）
- ContentProvider 常驻通道：LSPosed v2 注入进程 contentResolver 被 framework 拒（v0.2.2 已证）

## 三、目标架构（开关式双通道）

```
Preference: delivery_channel = "root_tmp" (默认新) | "downloads" (旧,回退)
App 烘焙 → 按通道写入（root_tmp: su -c cp; downloads: MediaStore）
SystemUI 读序: /data/local/tmp/opticon → Download/OptIcon → HookLibSync → provider
FileObserver: 两路径都监听（读序即优先级）
迁移: 首启检测旧目录存量 → 镜像到新通道 → 用户清理提示
```

- su 失败（如 Magisk 授权被撤）→ 自动回落 downloads 通道 + 状态提示（复用 v0.5.0 的 su 探测）
- E2E receiver 加通道自检广播（debug 包），S2 实验与日后回归复用

## 四、S2 真机实验清单（需用户插机，~20 分钟，全程 AI 只读）

| # | 实验 | 命令（用户执行或授权 su） | 判定 |
|---|---|---|---|
| E1 | /data/local/tmp 可读性 | su -c 写测试文件 chmod 644 → `ls -Z` 记录 context → 模块侧读探针 | system_app 域读 shell_data_file 是否放行 |
| E2 | /data/adb/opticon 可读性 | 同上 | magisk_file 域读取 + SukiSU 兼容性 |
| E3 | su 写入延迟 | 计时 su -c cp 单文件 | 批量烘焙（460 app）是否需合并单次 su 会话 |
| E4 | 旧通道体量 | ls Download/OptIcon 存量 | 迁移策略（一次性镜像 vs 懒迁移） |

- 实现时给 debug 包加只读探针广播（E2E_PROBE_CHANNEL），返回「路径/存在/可读/SELinux 错误码」四元组

## 五、风险与未决

1. SELinux 是唯一硬风险——若 A/B 均不可读，需要 Magisk 模块预置 contexts 或回到方案 C（S2 定生死）
2. SukiSU 与 Magisk 的 su -c 路径差异（SettingsViewModel 已有三套 PATH，复用）
3. FileObserver 对 /data/local/tmp 的监听权限（inotify 在 system_app 域对该路径的允许性，S2 顺带验证）

## 六、实施拆分（批准后）

1. debug 探针广播 + S2 实验（真机会话）
2. 双通道读写层 + 偏好开关（feat 分支）
3. 存量迁移 + su 失败回落
4. 模拟器（旧通道）+ 真机（新通道）全量回归
5. 出 v0.7.0-alpha

---

## 七、批准决议（2026-09-17）

**用户授权 AI 拍板（「不与既定原则冲突且功能正常即可」），草案批准，附三项修正：**

| # | 修正 | 理由 |
|---|---|---|
| 1 | root 通道路径复用 hook 现有 `WORLD_BAKED_DIR`（`/data/local/tmp/opticon_baked`），不新设路径 | 代码连续性，避免双 legacy 路径 |
| 2 | 并入 FileObserver 后缀 bug 修复（核验发现：`onEvent`/`preloadIcons` 按 `.png` 过滤，生产通道 `.opticon` 文件事件全被丢弃——热生效实际失效，注释与实现矛盾） | 不修则迁移后新通道热生效同样失效 |
| 3 | S2 中 A/B 通道 SELinux 均不可读 → 保留 C（Downloads）为主通道，迁移搁置不强行 | 「保证功能正常」优先于通道洁癖 |

**原则合规核查**：无新增依赖（root 本为 LSPosed 前提）✓ / su 失败自动回落保证功能 ✓ / PICP-ANIA 红线不涉及（通道为设备本地文件摆渡，不分发）✓ / 真机会话 AI 只读规则不变 ✓ / 版本纪律：观察者修复 PATCH、迁移本体 MINOR(v0.8.0) ✓

**实施批次**（批准时挂起，后经用户指示解冻）：
- 批次 1 · v0.7.5-alpha（自主）：FileObserver/preload 双后缀修复 + hook 启动通道探针日志
- 批次 2 · S2 真机会话（~20min）：E1 /data/local/tmp 可读性 · E2 /data/adb 可读性 · E3 su 批量写延迟 · E4 旧通道存量。决策门：可读→批次 3；均不可读→保留 C 归档
- 批次 3 · v0.8.0-alpha：DeliveryChannel 偏好（auto/手动）→ RootDeliveryWriter（单文件+批量单会话脚本）→ 读侧优先级翻转 → 存量迁移 → 双端回归
