# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

ZhiAI 是一个基于 AI 的 Android 手机自动化控制应用，通过视觉-语言模型分析屏幕界面并执行相应操作。它采用了类似 LADB 的技术，实现了无需 Root 权限的设备控制。

## 常用命令

### 构建和测试
```bash
# 编译 Debug 版本
./gradlew assembleDebug

# 编译 Release 版本
./gradlew assembleRelease

# 运行单元测试
./gradlew test

# 运行集成测试（需要连接设备）
./gradlew connectedAndroidTest

# 清理构建缓存
./gradlew clean

# 查看应用日志
adb logcat | grep -E "(ZhiAI|ShellExecutor|DeviceController|PhoneAgent)"

# 安装到设备
./gradlew installDebug
```

### ADB 调试
```bash
# 检查设备连接
adb devices

# 查看设备属性
adb shell getprop ro.product.locale

# 检查输入法
adb shell ime list -a

# 截图测试
adb shell screencap -p /sdcard/test.png
```

## 架构

### 核心组件

```
app/src/main/java/com/qs/phone/
├── PhoneAgent.kt              # 主 Agent，协调整个执行流程
├── MainActivity.kt            # 主界面，配置管理和权限获取
├── shell/
│   └── ShellExecutor.kt       # LADB Shell 执行器，使用 libadb.so 本地库
├── controller/
│   └── DeviceController.kt    # 设备控制：截图、点击、滑动、文本输入
├── model/
│   └── ModelClient.kt         # OpenAI 兼容 API 客户端
├── action/
│   └── ActionHandler.kt       # 解析并执行 AI 输出的动作
├── config/
│   ├── AppPackages.kt         # 应用包名映射（60+ 应用支持）
│   └── Prompts.kt             # 系统提示词
├── service/
│   └── FloatingWindowService.kt # 无障碍浮窗服务
└── util/
    ├── NativeLibraryLoader.kt # 本地库加载
    └── PermissionManager.kt   # 权限管理
```

### 执行流程

1. **初始化**: 加载 libadb.so → 检查调试状态 → 初始化 ShellExecutor → 连接 ADB
2. **任务循环**: 截图 → AI 分析界面 → 解析动作指令 → 执行操作 → 循环直到完成
3. **LADB 机制**: 通过内置的 libadb.so 实现本地 ADB 连接，支持无线调试（Android 11+）和 USB 调试

### 关键技术

- **LADB 集成**: 使用 libadb.so 本地库（支持 arm64-v8a, armeabi-v7a, x86, x86_64）
- **无 Root 控制**: 通过系统级 ADB 连接，无需设备 Root
- **AI 驱动**: 集成视觉-语言模型分析屏幕界面
- **协程架构**: 全面使用 Kotlin Coroutines + Flow
- **文件完整性**: 截图时检测文件写入完成状态，避免读取损坏图片

## 支持的操作

| 操作 | 格式 | 描述 |
|------|------|------|
| Launch | `{"action": "Launch", "app": "微信"}` | 启动应用 |
| Tap | `{"action": "Tap", "element": [x, y]}` | 点击坐标 |
| Type | `{"action": "Type", "text": "文本"}` | 输入文本 |
| Swipe | `{"action": "Swipe", "from": [x1, y1], "to": [x2, y2]}` | 滑动屏幕 |
| Back | `{"action": "Back"}` | 返回 |
| Home | `{"action": "Home"}` | 回到桌面 |
| Long Press | `{"action": "Long Press", "element": [x, y]}` | 长按 |
| Double Tap | `{"action": "Double Tap", "element": [x, y]}` | 双击 |
| Wait | `{"action": "Wait", "duration": 2}` | 等待 |
| Take_over | `{"action": "Take_over", "reason": "原因"}` | 人工接管 |

## 中文输入方案

设备控制器支持多种中文输入方法，按优先级自动尝试：

1. **ADB Keyboard** (需要安装 ADB Keyboard APK)
2. **ADB_INPUT_TEXT 广播**: `am broadcast -a ADB_INPUT_TEXT --es msg "中文文本"`
3. **Base64 编码**: `am broadcast -a ADB_INPUT_B64 --es msg "base64编码"`
4. **传统 ASCII 输入**: 适用于英文和数字

## 开发注意事项

### LADB 集成要点
- 本地库路径: `context.applicationInfo.nativeLibraryDir/libadb.so`
- 使用 `useLegacyPackaging = true` 确保正确提取
- DNS 服务发现自动检测 ADB 端口
- 支持多设备环境，优先选择 localhost 设备

### 权限要求
- 存储权限: 保存截图文件
- 无障碍服务: 浮窗和辅助功能
- WRITE_SECURE_SETTINGS: 自动启用调试（可选）
- 前台服务: 保持后台运行

### 截图机制
- 保存路径: `/sdcard/Android/data/${packageName}/files/screenshot_${timestamp}.png`
- 使用 `isFileWriting()` 检测文件写入完成
- Base64 编码后提供给 AI 模型分析
- 支持敏感页面的自动接管处理

### 错误处理
- 自动重试机制（截图、命令执行）
- 超时保护（withTimeout）
- 优雅降级（LADB 不可用时的提示）
- 详细日志记录便于调试

## 应用支持

内置 60+ 应用的包名映射，包括：
- **社交通讯**: 微信、QQ、微博、Telegram、Discord
- **电商购物**: 淘宝、京东、拼多多、闲鱼
- **生活服务**: 小红书、知乎、美团、饿了么、大众点评
- **视频娱乐**: bilibili、抖音、快手、YouTube、Netflix
- **系统应用**: 设置、浏览器、相机、文件管理器

在 `AppPackages.kt` 中添加新的应用支持。