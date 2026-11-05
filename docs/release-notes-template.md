> **这是 [Modificator/E-Ink-Launcher](https://github.com/Modificator/E-Ink-Launcher) 0.1.8.6（Apache-2.0）的改造版，不是从零写的。**
> 原作者 Modificator，上游基线提交 `155738c`。改动明细见 README 第 1、2 节与 `NOTICE`。

## 本次改动

{{CHANGES}}

## 包含的功能

- **文件夹占 2×2 格，里面 3×3 预览最多 9 个图标**：点小图标直接启动，点缝隙/留白展开
- **自定义图标顺序**：设置 →「调整图标顺序」
- **横屏适配**：竖屏 4×6 → 横屏 6×4，顺序不变
- **文件夹名称开关**：默认不显示

## 测试设备

| 项 | 值 |
|---|---|
| 机型 | 海信 Hisense 墨水屏手机 **HITV205N** |
| 系统 / 屏幕 | Android 11 / 720×1440 / 320dpi |
| 桌面网格 | 竖屏 4 列 × 6 行（横屏自动对调） |
| 编译环境 | Windows 11 + JDK 17.0.21 + Android SDK 36 + Gradle 9.1 |

## 安装

```bash
adb install -r {{APK}}
adb shell cmd package set-home-activity cn.modificator.launcher/.Launcher
```

⚠️ **debug 签名**，包名与官方 E-Ink Launcher 相同（`cn.modificator.launcher`），安装前请先卸载官方版；卸载会清掉桌面配置。
