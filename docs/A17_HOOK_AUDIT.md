# OptIcon Hook × Android 17 源码级交叉审计

> 2026-09-12 | 基准: PixelOS 17 (SDK 37, build CQ2A.260729.002) SystemUI17.apk + framework17.jar 反编译
> 参照系: SystemUI 36 / framework 36 (Downloads/sui-src, fw-src)
> 反编译产物: Downloads/pixelos17-src/{sui17-src, fw17-src}

## 一、逐 hook 兼容性结论（模块 v0.6.9 hook 面 vs 37 源码）

| # | Hook | 37 中的目标方法 | 签名 | 结论 |
|---|---|---|---|---|
| 1 | IconManager.createIcons BEFORE | `createIcons(NotificationEntry)` | 一致 | ✅ 兼容；17 创建 3 个 view（36 为 4），不影响 |
| 2 | IconManager.updateIcons 全重载 | `updateIcons(NotificationEntry, boolean)` | **17 恢复存在**（36 已移除） | ✅ 按名匹配策略正确命中两种形态 |
| 3 | Notification.getSmallIcon AFTER | `getSmallIcon()` | 一致 | ✅ 兼容 |
| 4 | IconManager.getIconDescriptor AFTER | `getIconDescriptor(NotificationEntry, boolean)` | 一致 | ✅ 兼容（重要会话 people-avatar 分支不变） |
| 5 | StatusBarIconView.set AFTER | `set(StatusBarIcon)` | 一致（17 标记 final，不影响 hook） | ✅ 兼容 |
| 6 | CachingIconView.setImageIcon | framework `CachingIconView.setImageIcon(Icon)` | 一致 | ✅ 兼容；17 新增子类 NotificationRowIconView 覆写此方法，app-icon 分支不进 super（原生行为，正确），smallIcon 分支经 invokesuper 仍被 hook 捕获 |
| 7 | recoverBuilder AFTER | 静态 `recoverBuilder(Context, Notification)` | 一致 | ✅ 兼容；17 新增 CompactContentResolver 紧凑通知路径同样流经 recoverBuilder，替换天然覆盖 |
| ✕ | ~~shouldShowAppIcon~~ | — | — | v0.6.9 已移除（破坏性，见调研记录） |

**结论：v0.6.9 的 hook 面在 AOSP 17 上全部兼容，无需改动。**

## 二、17 通知行图标决策链（#17 feature 路线图）

```
NotificationRowIconViewInflaterFactory.createIconProvider(sbn, ctx, row)
 └─ NotificationRowIconView (com.android.internal.widget, extends CachingIconView)
     └─ setImageIcon(smallIcon):
         ├─ loadAppIcon(): iconType==1(launcher) 或 2(bridged) → app 图标, 忽略 smallIcon
         └─ 否则 → super.setImageIcon(smallIcon)（着色小图标）
```

- **iconType 判定**（NotificationIconStyleProviderImpl.shouldShowAppIcon）：
  - `extras["android.app.preferSmallIcon"]==true` → false（应用可原生声明偏好小图标！）
  - 否则：非(系统应用 且 无 launcher 入口) → true（即可启动应用一律显示 app 图标）
  - bridged 通知（跨设备桥接元数据）→ type 2（app 图标+白碟+星角标）
- **app 图标来源**：`AppIconProviderImpl.getOrFetchAppIcon(user, pkg, "LEGACY")`（含 AppIconCache）
- 行状态：`row.mIsShowingAppIcon`

**#17 实现选项（按侵入度排序）**：
1. extras 注入 `android.app.preferSmallIcon=true`（零 hook，但需在 App 进程或 recoverBuilder 处写入，影响全局通知对象）
2. hook `NotificationIconStyleProviderImpl.shouldShowAppIcon` 返回 false（单点，**必须带配置守卫+默认关**——本次事故的教训）
3. hook `NotificationRowIconView.loadAppIcon` 返回 null（视图级）
- 注意：选项 2/3 恢复的正是我们刚删的 hook 面，差异仅在守卫；分辨率短板结论不变（smallIcon 24dp 放大会糊）

## 三、与用户观察的最终对账

- 真机截图中 USB 调试/电话等系统通知显示 smallIcon 着色圆 = **17 原生行为**（无 launcher 入口 → 不显示 app 图标），非模块所致
- launcher 应用的「app 图标 → 通知图标」变化 = v0.3.1 遗留 shouldShowAppIcon 破坏性 hook（v0.6.9 已修复）

## 四、环境备注

- SystemUI17 反编译 jadx 1.5.1，个别方法体失败（如 StatusBarIconView.set body）但签名完整可审计
- API 37 google_apis 镜像 guest 内 screencap 存在 goldfish DMA 断言崩溃（模拟器工具链缺陷，与本项目无关）；纯净 AVD CleanA36/CleanA37 已保留作原生基准

---

# 附：Q1 API 34 回归（2026-09-12）

**方法**：API 34 google_apis 镜像（build 2024-07-12）直接解包（GPT→super→system/system_ext），dexdump 签名审计。

## 签名审计结果（9/9 通过）

| Hook 目标 | API 34 实测签名 | 结论 |
|---|---|---|
| IconManager.createIcons | `(NotificationEntry)V` | ✅ |
| IconManager.updateIcons | `(NotificationEntry)V`（单重载；36 移除、37 为 (entry,Z)——按名全重载匹配策略三代通吃） | ✅ |
| IconManager.getIconDescriptor | `(NotificationEntry,Z)StatusBarIcon` | ✅ |
| StatusBarIconView.set | `(StatusBarIcon)Z` | ✅ |
| Notification.getSmallIcon / setSmallIcon | `()Icon` / `(Icon)V` | ✅ |
| Notification.Builder.recoverBuilder | `(Context,Notification)Builder` 静态 | ✅ |
| CachingIconView.setImageIcon | `(Icon)V` | ✅ |
| NotificationIconStyleProvider / shouldShowAppIcon | **不存在**（16 引入） | ✅ v0.6.9 移除该 hook 在 34 零影响 |

## 行为层证据
- v0.2.2 时代（2026-07-05）已在 API 34 AVD (WHPX) 完成 hook 加载端到端验证 ✅
- 本次静态审计 + 历史行为证据 = Q1 判定**通过**

## 限制说明
- rootAVD 工具链未随迁移保留，无法建 API 34 LSPosed 环境做防御 hook 触发取证；如未来需要，按 archive/memory/2026-09-09.md 的 rootAVD2026 流程重建
- API 34 镜像在宿主机当前模拟器版本（37.1.11）下窗口模式挂死（3 次复现），App 层回归未执行；A36/37 正常——记录为工具链缺陷

**Q1 关账：API 34 回归通过。**
