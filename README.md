# Uperf

Uperf调度模块的图形化管理器。黑白扁平设计，Material 3 风格，完全离线运行。

## 特性

- **全局调度**：动态 / 省电 / 均衡 / 性能 / 极速，一键切换
- **分应用调度**：为每个应用单独指定性能模式，已配置应用自动置顶
- **系统应用管理**：用户应用与系统应用分页展示，均支持单独配置
- **息屏 / 默认调度**：动态模式下独立设置息屏与未配置应用的调度策略
- **日志查看**：调度运行日志实时预览，支持 INFO / DEBUG / ERROR 级别切换
- **模块管理**：模块信息、运行状态、重启调度服务、卸载模块、重启手机
- **主题**：浅色 / 深色 / 跟随系统
- **隐私**：零网络权限、零数据收集，所有配置仅保存在本机
- <img width="400" height="900" alt="Screenshot_2026-08-13-21-57-29-852_app uperf manager" src="https://github.com/user-attachments/assets/8852a3d4-73f6-4055-b846-f059ca003a55" />


## 使用要求

- 已 Root（Magisk / KernelSU / APatch）
- 已刷入 Uperf Game Turbo 模块（或任何使用 `/sdcard/Android/yc/uperf` 配置结构的 uperf 系模块）

## 下载

前往 [Releases](../../releases) 页面下载最新 APK。

## 工作原理

应用通过 Root 权限读写模块配置文件：

| 配置 | 路径 |
|---|---|
| 全局模式 | `/sdcard/Android/yc/uperf/cur_powermode.txt` |
| 分应用规则 | `/sdcard/Android/yc/uperf/perapp_powermode.txt` |
| 日志级别 | `/sdcard/Android/yc/uperf/uperf.json` 的 `log.level` |

perapp 规则中 `-` 为息屏调度、`*` 为默认调度。

## 自行构建

```bash
gradle assembleDebug
```

需要 JDK 17 与 Android SDK 34。也可以用 Android Studio 打开 `app` 目录直接构建。

## 开源许可

[Apache License 2.0](LICENSE)

## 致谢

- [uperf](https://github.com/yc9559/uperf) by Matt Yang（调度核心，Apache 2.0）
- [libsu](https://github.com/topjohnwu/libsu)（Root Shell，Apache 2.0）
- Jetpack Compose（UI 框架，Apache 2.0）
