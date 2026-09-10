<div align="center">

# OptIcon

**Android 通知图标规范化工具 —— 覆盖完整 Material You 世代**

让每一个通知图标都符合原生 Android 设计规范

[![License](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2012%2B-green.svg)]()
[![API](https://img.shields.io/badge/LSPosed-API%20102-orange.svg)]()

</div>

---

> **OptIcon** 是一个 [LSPosed](https://github.com/LSPosed/LSPosed) 模块，用于将不规范的彩色通知图标替换为符合原生 Android 设计规范的 Material You 图标。
>
> OptIcon is an [LSPosed](https://github.com/LSPosed/LSPosed) module that normalizes non-compliant colorful notification icons into native Android Material You style.

## ✨ 功能特性

### 三级图标供给策略

| 策略 | 说明 | 来源 |
|---|---|---|
| **通知图标适配** | 673+ 国内主流应用的适配规则库，开箱即用 | [AndroidNotifyIconAdapt](https://github.com/fankes/AndroidNotifyIconAdapt) (ANIA) |
| **资产导入提取** | 从第三方图标包或系统自适应图标分层提取 | 本地 + [完美图标补全计划](https://github.com/pzcn/Perfect-Icons-Completion-Project) (PICP) |
| **智能算法重绘** | 四角采样自过滤算法，无规则也能生成规范图标 | 内置引擎 |

### 运行时合规检测

应用发送通知时自动评估其原生图标是否合规，并在列表中以「符合规范 / 不符合规范」标签呈现——你可以清楚地知道哪些 App 正在破坏你的状态栏一致性。

### 其他特性

- 🔴 **Heads-up 全覆盖** —— 悬浮横幅、下拉列表、锁屏图标全路径替换
- 🎨 **Material You 动态取色** —— 深浅色双主题，跟随系统壁纸配色
- ⚡ **热生效** —— 修改图标无需重启，FileObserver 实时监听
- 🌐 **多语言就绪** —— 中文/英文，架构支持任意语言扩展
- 🔒 **零网络依赖（可选）** —— 规则库按需下载，无订阅、无遥测

## 📸 界面预览

<p float="left">
  <img src="docs/screenshots/list_current.png" width="270" alt="应用列表"/>
  <img src="docs/screenshots/detail.png" width="270" alt="应用详情"/>
  <img src="docs/screenshots/list_dark.png" width="270" alt="深色模式"/>
</p>

## 🚀 安装使用

### 环境要求

- Android 12+（API 31，Material You 起始版本）
- 已 root 并安装 [Magisk](https://github.com/topjohnwu/Magisk)（Zygisk）/ KernelSU / SukiSU
- [LSPosed](https://github.com/LSPosed/LSPosed) v2.0+（libxposed API 102）

### 步骤

1. 安装 OptIcon APK
2. 在 LSPosed Manager 中启用模块，作用域勾选**系统界面（SystemUI）**
3. 重启系统界面（或整机重启）
4. 打开 OptIcon，在设置中同步图标规则库
5. 在应用列表中选择应用 → 选择策略 → 保存

## 🛠️ 工作原理

```
┌────────────┐    规则/烘焙产物     ┌──────────────────┐
│  OptIcon   │ ──────────────────▶ │  Download/OptIcon │ (共享公共目录)
│  (App 进程) │    MediaStore 写入   │    (图标/开关/合规)  │
└────────────┘                     └────────┬─────────┘
                                            │ File API 直读
┌────────────┐   hook createIcons           ▼
│  SystemUI  │ ◀──── 替换 smallIcon ── IconManager 管线
└────────────┘   (含 heads-up/列表/锁屏全路径)
```

- **方案 A**：App 端 MediaStore 写入公共目录，SystemUI hook 端直读（[Iconify](https://github.com/MohamedRejworkshop/Iconify) 量产验证模式）
- **方案 B**：SystemUI 进程内自下载规则库（ANIP 架构，零跨进程）
- **合规检测**：寄生在已有的 createIcons hook 中，评估原始图标单色性，几乎零开销

## 🤝 致谢

本项目的诞生离不开以下项目与开发者：

- **[fankes / AndroidNotifyIconAdapt](https://github.com/fankes/AndroidNotifyIconAdapt)** —— 「Android 通知图标规范适配计划」，673+ 应用适配规则的基石
- **[pzcn / Perfect-Icons-Completion-Project](https://github.com/pzcn/Perfect-Icons-Completion-Project)** —— 「完美图标补全计划」，高质量分层图标资产
- **[Howard20181 / NotificationIconFix](https://github.com/Howard20181/NotificationIconFix)** —— 通知图标修复的先行者，本项目的重要灵感来源
- **[LSPosed Team](https://github.com/LSPosed)** —— 现代 Xposed 框架
- **[MohamedRejworkshop / Iconify](https://github.com/MohamedRejworkshop/Iconify)** —— 跨进程文件配送架构参考

## ⚖️ 开源协议

本项目采用 [AGPL-3.0](LICENSE) 协议开源。

- ANIA 规则库（AGPL-3.0）以运行时下载方式使用，不内置分发
- PICP 图标资产按需下载供用户个人使用，不重分发（详见其仓库说明）

## 📊 兼容性

| 项目 | 支持情况 |
|---|---|
| 最低版本 | Android 12 (API 31) |
| 目标版本 | Android 16 (API 36) |
| 已验证 | API 34 / API 36（AOSP + LSPosed v2.2.0） |
| 定制 ROM | 未针对 MIUI/ColorOS 等适配，理论可用但不保证 |

---

<div align="center">

如果这个项目对你有帮助，欢迎 ⭐ Star 支持

</div>
