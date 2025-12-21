# 不使用 ADB 的应用检测实现

## 概述

本项目实现了一个不依赖 ADB 的应用检测系统，通过多种 Android API 来检测当前运行的应用。

## 实现方案

### 1. AppDetector 类

位置：`app/src/main/java/com/qs/phone/controller/AppDetector.kt`

这是核心的应用检测类，使用以下方法按优先级检测当前应用：

#### 检测方法优先级：

1. **无障碍服务 (Accessibility Service)** - 最准确
   - 通过 `FloatingWindowService` 获取当前焦点窗口
   - 使用 `rootInActiveWindow` 获取根节点的包名
   - 从最后保存的无障碍事件中获取包名

2. **UsageStatsManager** - 需要权限
   - 查询最近10秒的应用使用记录
   - 找到 `lastTimeUsed` 最近的应用
   - 需要用户授权 `PACKAGE_USAGE_STATS` 权限

3. **ActivityManager** - 有限制
   - 使用 `getRunningTasks()` 获取运行的任务
   - 在新版 Android 中权限受限
   - 作为备用方案

### 2. FloatingWindowService 增强

位置：`app/src/main/java/com/qs/phone/service/FloatingWindowService.kt`

- 添加了 `lastAccessibilityEvent` 字段保存最后一个无障碍事件
- 在 `onAccessibilityEvent` 中保存应用程序事件
- 提供 `getLastAccessibilityEvent()` 方法供检测器使用

### 3. DeviceController 更新

位置：`app/src/main/java/com/qs/phone/controller/DeviceController.kt`

- 集成 `AppDetector` 实例
- `getCurrentApp()` 方法现在优先使用非 ADB 检测
- 保留原有的 ADB 方法作为最后的备用方案
- 添加 `getAppDetectionInfo()` 用于调试

### 4. PhoneAgent 简化

位置：`app/src/main/java/com/qs/phone/PhoneAgent.kt`

- 简化了当前应用的获取逻辑
- 直接使用 `deviceController.getCurrentApp()`
- 移除了复杂的无障碍服务检测代码

## 特性

### 优势

1. **不依赖 ADB**：主要检测方法不需要 ADB 连接
2. **多级备用**：如果主要方法失败，自动使用备用方法
3. **性能优化**：优先使用最轻量级的检测方法
4. **兼容性**：支持不同 Android 版本的 API
5. **权限友好**：最小化所需的系统权限

### 权限要求

- **必需权限**：无障碍服务权限（用于应用功能）
- **可选权限**：`PACKAGE_USAGE_STATS`（提高检测准确性）

## 使用方法

```kotlin
// 创建应用检测器
val appDetector = AppDetector(context)

// 获取当前应用
val currentApp = appDetector.getCurrentApp()

// 获取检测方法状态信息
val info = appDetector.getDetectionMethodsInfo()
Log.d(TAG, info)
```

## 测试

在 MainActivity 的诊断按钮中集成了测试功能，点击诊断按钮会：

1. 运行应用检测测试
2. 显示检测到的应用
3. 输出各种检测方法的状态

## 系统应用识别

自动识别常见系统应用：

- `com.android.*` → "System"
- `*.launcher*` → "Home"
- `com.google.android.*` → 对应的 Google 应用
- 其他返回包名或应用名称

## 日志输出

检测过程会输出详细的日志，便于调试：

```
D/AppDetector: Getting current app without ADB
D/AppDetector: Accessibility service detected package: com.tencent.mobileqq
D/AppDetector: Detected app: 微信
```

## 性能考虑

- 无障碍服务检测：几乎无延迟，最推荐
- UsageStatsManager：查询历史记录，轻微延迟
- ActivityManager：在新版 Android 中受限
- ADB 方法：作为最后的备用方案，可能有延迟

## 总结

这个实现提供了一个健壮的、不依赖 ADB 的应用检测系统，通过多重备用机制确保在各种设备和 Android 版本上都能正常工作。