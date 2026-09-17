<div align="center">

# OptIcon

[简体中文](README.md) · [English](README_EN.md)


**Android 通知图标规范化工具 —— 覆盖完整 Material You 世代**

让每一个通知图标都符合原生 Android 设计规范

[![License](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2012%2B-green.svg)](#-兼容性)
[![API](https://img.shields.io/badge/LSPosed-API%20102-orange.svg)](https://github.com/libxposed/api)

</div>

---

> **OptIcon** 是一个 [LSPosed](https://github.com/LSPosed/LSPosed) 模块，用于将不规范的彩色通知图标替换为符合原生 Android 设计规范的 Material You 图标。

## ✨ 功能特性

### 三级图标供给策略

| 策略 | 说明 | 来源 |
|---|---|---|
| **通知图标适配** | 673+ 国内主流应用的适配规则库，开箱即用 | [AndroidNotifyIconAdapt](https://github.com/fankes/AndroidNotifyIconAdapt) (ANIA) |
| **资产导入提取** | 从第三方图标包或系统自适应图标分层提取 | 本地 + [完美图标补全计划](https://github.com/pzcn/Perfect-Icons-Completion-Project) (PICP) |
| **智能算法重绘** 🚧 | 四角采样自过滤算法，无规则也能生成规范图标（**施工中**：引擎管线已就绪，入口与控制面板尚未开放） | 内置引擎 |

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
</p
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

本项目的诞生离不开以下项目与开发者（与应用内致谢同序同文）：

- **[fankes / AndroidNotifyIconAdapt](https://github.com/fankes/AndroidNotifyIconAdapt)** —— 特别感谢：fankes 慷慨地授权本项目使用其团队《Android 通知图标规范适配计划》的 673+ 应用适配规则库。
- **[pzcn / Perfect-Icons-Completion-Project](https://github.com/pzcn/Perfect-Icons-Completion-Project)** —— 感谢《完美图标补全计划》团队对丰富 Android 图标生态的无私贡献。
- **[Howard20181 / NotificationIconFix](https://github.com/Howard20181/NotificationIconFix)** —— 感谢 Howard20181，其技术路径为本项目提供了重要的灵感与参考。
- **[MohamedRejworkshop / Iconify](https://github.com/MohamedRejworkshop/Iconify)** —— 感谢 Iconify，其跨进程图标配送管线（共享目录模式）为本项目的生产配送方案提供了架构参考。
- **[LSPosed Team](https://github.com/LSPosed)** —— 一切的基石，谢谢你们。

## 🔐 权限说明（QUERY_ALL_PACKAGES 豁免声明 / Permission Disclosure）

OptIcon 申请 `QUERY_ALL_PACKAGES`（查询全部应用）权限。**该权限仅用于在应用列表中枚举设备上已安装、可能发送通知的应用**，以供用户逐个配置图标策略——通知并不只来自带桌面图标的应用（实测一台设备上 41% 的包没有 launcher 入口，其中包括 Play 服务等高频通知来源）。OptIcon 不收集、不上传任何数据，无遥测、无网络回传。


## ⚠️ 免责声明 / Disclaimer

> 本项目为**兴趣使然的个人作品**，按“现状”提供，不含任何明示或默示的担保。
>
> - **功能不保证**：作者不保证任何功能在您的设备、ROM 或系统版本上正常运行；定制 ROM（MIUI / ColorOS / HyperOS 等）的私有行为可能造成无法预料的差异。
> - **无开发承诺**：作者不对后续的开发计划、排期、功能路线或是否继续维护作任何承诺；项目可能随时放缓、暂停或归档。
> - **风险自担**：使用本应用产生的任何后果（包括但不限于系统界面异常、通知丢失、功耗变化）由使用者自行承担；启用 root / Xposed 模块本身存在风险，请自行评估。
> - **无关联声明**：本项目与 Google、Android、一加及文中提及的任何厂商/项目无隶属关系；各商标归其各自所有者所有。
> - **用途自决**：项目用途由使用者自行决定，开发者不对任何滥用行为负责。

## 🤖 AI 使用声明 / AI Disclosure

> 本项目由 AI（大语言模型）深度参与开发——包括架构设计、代码实现、测试与文档；人类（[@deserthouse](https://github.com/deserthouse)）提出需求、进行验收并拥有最终决策权。

## ⚖️ 开源协议

本项目采用 [AGPL-3.0](LICENSE) 协议开源。

- ANIA 规则库（AGPL-3.0）以运行时下载方式使用，不内置分发
- PICP 图标资产按需下载供用户个人使用，不重分发（详见其仓库说明）

## 📊 兼容性

| 项目 | 支持情况 |
|---|---|
| 最低版本 | Android 12 (API 31) |
| 目标版本 | Android 17 (API 37) |
| 已验证 | API 36 端到端（AOSP 模拟器 + LSPosed v2.2.0）；Android 17（OOS 真机）验收中 |
| 定制 ROM | 以 AOSP 为主线开发；OOS（一加 15）为第一真机环境，其余理论可用不保证 |

---

<div align="center">

如果这个项目对你有帮助，欢迎 ⭐ Star 支持

</div>
