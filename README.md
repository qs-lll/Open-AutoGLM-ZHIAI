# ZhiAI - AI驱动的Android手机自动化控制

<div align="center">

![ZhiAI Logo](resources/logo.png)

**基于视觉-语言模型的智能 Android 自动化框架**

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-blue.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-7.0%2B-green.svg)](https://www.android.com)
[![License](https://img.shields.io/badge/License-CC%20BY--NC--SA%204.0-red.svg)](LICENSE)

[快速开始](#-快速开始) • [功能特性](#-功能特性) • [使用教程](#-使用教程) • [常见问题](#-常见问题)

</div>

---

## 📖 项目简介

**ZhiAI** 是一个创新性的 Android 自动化控制应用，通过集成视觉-语言模型（VLM），实现了真正意义上的"所见即所得"智能操作。

与传统的脚本自动化工具不同，ZhiAI 能够：
- 📸 **截图分析** - 实时捕获屏幕内容
- 🧠 **AI理解** - 视觉模型理解界面元素
- 🎯 **智能操作** - 自动识别并执行点击、滑动、输入等操作
- 🔄 **循环执行** - 持续监控直到任务完成

### 工作原理

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  截图捕获   │ -> │  AI分析界面  │ -> │  解析动作   │ -> │  执行操作   │
│  ADB Screen │    │  VLM Model  │    │  Action JSON │    │  ADB Input  │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
       ^                                                                    │
       └────────────────────────────────────────────────────────────────────┘
                              持续循环直到任务完成
```

---

## 🌟 功能特性

### 核心功能

| 功能 | 描述 |
|------|------|
| 🤖 **AI智能控制** | 基于视觉-语言模型的自动化操作，无需编写脚本 |
| 📱 **无需Root** | 采用LADB技术，通过系统级ADB连接实现控制 |
| 🖥️ **友好界面** | Material Design风格的现代化UI |
| 🔄 **实时反馈** | 完整的执行过程可视化，支持日志查看 |
| 🎨 **浮窗服务** | 无障碍辅助功能的悬浮窗口 |

### 设备支持

| 平台 | 支持状态 | 要求 |
|------|----------|------|
| ✅ Android 7.0+ | 完全支持 | USB调试或无线调试 |
| ✅ Android 10+ | 推荐 | 支持更多输入方式 |
| ✅ Android 11+ | 最佳体验 | 原生无线ADB配对 |

### 智能功能

| 功能 | 说明 |
|------|------|
| 🎯 **60+应用支持** | 内置微信、淘宝、抖音等主流应用包名映射 |
| 🧠 **视觉理解** | AI模型直接分析屏幕截图，理解UI布局 |
| ⚡ **协程架构** | Kotlin Coroutines + Flow 实现高效异步 |
| 🔌 **自动连接** | DNS服务发现自动检测ADB端口 |
| 🌐 **多模型支持** | 兼容OpenAI格式的各种API服务 |

---

## 🚀 快速开始

### 示例 1：淘宝搜索商品

```
用户输入: 打开淘宝搜索无线耳机

执行流程:
1. 自动启动淘宝应用
2. 定位搜索框
3. 输入"无线耳机"
4. 点击搜索按钮
```

### 示例 2：微信发送消息

```
用户输入: 打开微信发消息给张三说你好

执行流程:
1. 启动微信应用
2. 搜索联系人"张三"
3. 进入聊天界面
4. 输入"你好"并发送
```

### 示例 3：美团点外卖

```
用户输入: 打开美团点一份麦当劳

执行流程:
1. 启动美团应用
2. 搜索"麦当劳"
3. 选择商品并下单
```

---

## 📱 安装部署

### 方式一：编译安装（推荐开发者）

#### 1. 环境准备

确保你的开发环境满足以下要求：

```bash
# 检查 JDK 版本（需要 JDK 11+）
java -version

# 检查 Android SDK
echo $ANDROID_HOME

# 检查 ADB 工具
adb version
```

#### 2. 克隆项目

```bash
git clone https://github.com/your-repo/ZhiAI.git
cd ZhiAI
```

#### 3. 编译安装

```bash
# 编译 Debug 版本
./gradlew assembleDebug

# 安装到连接的设备
./gradlew installDebug

# 或者直接安装 APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 方式二：直接安装 APK

从 [Releases](https://github.com/your-repo/ZhiAI/releases) 页面下载最新的 APK 文件：

```bash
# 安装 APK
adb install ZhiAI-v1.0.0-debug.apk
```

---

## ⚙️ 使用教程

### 第一步：启用开发者选项

#### Android 7.0 - 10

```bash
1. 进入「设置」→「关于手机」
2. 连续点击「版本号」7次，直到提示"您已处于开发者模式"
3. 返回设置，进入「开发者选项」
4. 开启「USB调试」
5. 开启「USB调试(安全设置)」- 重要！
```

#### Android 11+

```bash
1. 进入「设置」→「系统」→「开发者选项」
2. 开启「USB调试」
3. 开启「无线调试」
4. 记录显示的端口号码
```

### 第二步：连接设备

#### USB 连接（通用方法）

```bash
# 1. 使用 USB 线连接手机和电脑
# 2. 手机上确认允许 USB 调试
adb devices

# 输出示例：
# List of devices attached
# XXXXXXXX    device
```

#### 无线连接（Android 11+）

**方法 A：使用 ZhiAI 应用内配对**

```
1. 打开 ZhiAI 应用
2. 点击「无线配对」按钮
3. 在无线调试设置中点击「使用配对码配对设备」
4. 输入应用显示的配对码和端口
5. 等待配对成功
```



### 第三步：配置 AI 模型

打开 ZhiAI 应用，在设置界面配置以下参数：

| 参数 | 说明 | 示例值 |
|------|------|--------|
| **Base URL** | 模型API地址 | `http://localhost:8000/v1` |
| **API Key** | API密钥 | `sk-xxxxx` (部分服务需要) |
| **Model Name** | 模型名称 | `autoglm-phone-9b` |

#### 推荐的模型服务

**智谱 BigModel（官方）**

```json
{
  "base_url": "https://open.bigmodel.cn/api/paas/v4",
  "model": "autoglm-phone",
  "api_key": "你的API密钥"
}
```

**ModelScope**

```json
{
  "base_url": "https://api-inference.modelscope.cn/v1",
  "model": "ZhipuAI/AutoGLM-Phone-9B",
  "api_key": "你的API密钥"
}
```

**本地模型（Ollama）**

```json
{
  "base_url": "http://localhost:11434/v1",
  "model": "autoglm-phone",
  "api_key": "ollama"
}
```

### 第四步：开始使用

#### 方式 A：应用内操作

```
1. 打开 ZhiAI 应用
2. 确认顶部状态显示「已连接」
3. 在输入框中输入任务描述
4. 点击「执行」按钮
5. 观察执行过程和结果
```


---

## 📋 支持的操作

ZhiAI 支持以下自动化操作，由 AI 模型根据任务自动选择：

| 操作 | JSON格式 | 说明 |
|------|----------|------|
| **Launch** | `{"action": "Launch", "app": "微信"}` | 启动指定应用 |
| **Tap** | `{"action": "Tap", "element": [500, 100]}` | 点击屏幕坐标 |
| **Type** | `{"action": "Type", "text": "你好"}` | 输入文本 |
| **Swipe** | `{"action": "Swipe", "from": [500,1000], "to": [500,500]}` | 滑动屏幕 |
| **Back** | `{"action": "Back"}` | 返回上一级 |
| **Home** | `{"action": "Home"}` | 返回桌面 |
| **Long Press** | `{"action": "Long Press", "element": [500, 100]}` | 长按坐标 |
| **Double Tap** | `{"action": "Double Tap", "element": [500, 100]}` | 双击坐标 |
| **Wait** | `{"action": "Wait", "duration": 2}` | 等待指定秒数 |
| **Take_over** | `{"action": "Take_over", "reason": "需要登录"}` | 请求人工接管 |

---

## 🎨 应用支持列表

### 社交通讯

| 应用 | 包名 |
|------|------|
| 微信 | `com.tencent.mm` |
| QQ | `com.tencent.mobileqq` |
| 微博 | `com.sina.weibo` |
| Telegram | `org.telegram.messenger` |
| WhatsApp | `com.whatsapp` |

### 电商购物

| 应用 | 包名 |
|------|------|
| 淘宝 | `com.taobao.taobao` |
| 京东 | `com.jingdong.app.mall` |
| 拼多多 | `com.xunmeng.pinduoduo` |

### 生活服务

| 应用 | 包名 |
|------|------|
| 小红书 | `com.xingin.xhs` |
| 知乎 | `com.zhihu.android` |
| 美团 | `com.sankuai.meituan` |
| 饿了么 | `me.ele` |
| 大众点评 | `com.dianping.v1` |

### 视频娱乐

| 应用 | 包名 |
|------|------|
| bilibili | `tv.danmaku.bili` |
| 抖音 | `com.ss.android.ugc.aweme` |
| 快手 | `com.smile.gifmaker` |
| YouTube | `com.google.android.youtube` |

> 更多应用支持请查看 [`AppPackages.kt`](app/src/main/java/com/qs/phone/config/AppPackages.kt)

---

## ⚠️ 注意事项

### 安全提示

1. **API Key 安全**
   - 请勿在公开场合分享您的 API Key
   - 建议使用环境变量或配置文件存储密钥
   - 定期轮换 API Key

2. **数据隐私**
   - 截图数据会发送给 AI 模型服务
   - 敏感页面（支付、银行）会自动请求人工接管
   - 请使用可信赖的模型服务

3. **权限说明**

   | 权限 | 用途 |
   |------|------|
   | 存储权限 | 保存截图文件 |
   | 无障碍服务 | 浮窗功能 |
   | 前台服务 | 保持后台运行 |

### 使用限制

| 限制项 | 说明 |
|--------|------|
| 最大步数 | 默认 100 步，可在设置中调整 |
| 超时时间 | 单次操作超时 30 秒 |
| 文本输入 | 需要安装 ADB Keyboard（可选） |

---

## 🔧 常见问题

### 连接问题

| 问题 | 解决方案 |
|------|----------|
| 设备未找到 | `adb kill-server && adb start-server && adb devices` |
| DNS连接失败 | 确认设备和电脑在同一局域网 |
| 配对失败 | 检查无线调试是否开启，重启应用 |

### 功能问题

| 问题 | 解决方案 |
|------|----------|
| 无法打开应用 | 检查应用是否已安装 |
| 点击无响应 | 开启「USB调试(安全设置)」 |
| 文本输入失败 | 安装 ADB Keyboard 输入法 |

### 截图问题

| 问题 | 解决方案 |
|------|----------|
| 截图黑屏 | 可能是敏感页面，会自动请求人工接管 |
| 截图超时 | 检查存储权限是否授予 |
| 读取失败 | 等待文件写入完成 |

### 调试技巧

```bash
# 查看应用日志
adb logcat | grep -E "(ZhiAI|ShellExecutor|DeviceController|PhoneAgent)"

# 测试截图功能
adb shell screencap -p /sdcard/test.png

# 检查输入法
adb shell ime list -a

# 测试点击操作
adb shell input tap 500 1000
```

---

## 🛠️ 开发指南

### 项目结构

```
app/src/main/java/com/qs/phone/
├── PhoneAgent.kt              # 主 Agent，协调执行流程
├── MainActivity.kt            # 主界面，配置管理
├── shell/
│   └── ShellExecutor.kt       # LADB Shell 执行器
├── controller/
│   ├── DeviceController.kt    # 设备控制（截图、点击、滑动）
│   └── AppDetector.kt         # 应用检测
├── model/
│   └── ModelClient.kt         # OpenAI 兼容 API 客户端
├── action/
│   └── ActionHandler.kt       # 动作解析与执行
├── config/
│   ├── AppPackages.kt         # 应用包名映射
│   └── Prompts.kt             # 系统提示词
├── service/
│   ├── FloatingWindowService.kt  # 浮窗服务
│   └── WirelessAdbPairingService.kt  # 无线配对服务
└── util/
    ├── NativeLibraryLoader.kt # 本地库加载
    └── PermissionManager.kt   # 权限管理
```

### 构建命令

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建
./gradlew assembleRelease

# 运行测试
./gradlew test

# 清理构建
./gradlew clean
```

### 代码规范

```bash
# 代码格式化
./gradlew ktlintFormat

# 代码检查
./gradlew ktlintCheck
```

---

## 📄 开源协议

```
MIT License

Copyright (c) 2024 ZhiAI Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 🙏 致谢

- [LADB](https://github.com/tytydraco/LADB) - 本地 ADB 实现的灵感来源
- [AutoGLM](https://github.com/OpenAutoGLM/Open-AutoGLM) - 视觉-语言模型支持
- 所有贡献者和用户的支持

---

<div align="center">

**Made with ❤️ by ZhiAI Team**

[Star ⭐](https://github.com/your-repo/ZhiAI) • [Fork 🔱](https://github.com/your-repo/ZhiAI/fork) • [Issue 🐛](https://github.com/your-repo/ZhiAI/issues)

</div>
