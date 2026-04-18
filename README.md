# lib-exception

Android 崩溃捕获工具库

## 引用

Step 1. Add the JitPack repository to your build file

```groovy
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

Step 2. Add the dependency

```groovy
dependencies {
    implementation 'com.github.baiqingsong:lib-exception:Tag'
}
```

## 特性

- 捕获未处理异常，自动写入本地崩溃日志文件
- 支持回调接口，方便上传崩溃信息到自有服务器
- 可选集成腾讯 Bugly 崩溃上报（参数可配置）
- 崩溃日志按天生成，自动清理超过 7 天的文件
- 崩溃报告包含完整设备信息和异常堆栈（含 Caused by / Suppressed）

## 初始化

在 `Application.onCreate()` 中初始化：

```java
// 基础初始化 + 设置崩溃回调
LCrashHandlerUtil.getInstance()
    .init(this)
    .setListener((context, crashReport) -> {
        // crashReport 是完整的崩溃报告字符串，上传到你的服务器
    });

// 自定义崩溃日志路径
LCrashHandlerUtil.getInstance()
    .init(this, "/sdcard/MyApp/crash/")
    .setListener((context, crashReport) -> { });
```

## Bugly 集成（可选）

```java
// 基础初始化
LCrashHandlerUtil.getInstance().initBugly(this, "your_app_id", BuildConfig.DEBUG);

// 带渠道标识
LCrashHandlerUtil.getInstance().initBugly(this, "your_app_id", "your_channel", BuildConfig.DEBUG);
```

## 类说明

`com.dawn.exception.LCrashHandlerUtil` 崩溃捕获工具类

| 方法 | 说明 |
|------|------|
| `getInstance()` | 获取单例实例 |
| `init(Context)` | 初始化崩溃捕获（使用默认路径） |
| `init(Context, String)` | 初始化崩溃捕获（指定自定义路径） |
| `setListener(OnCrashListener)` | 设置崩溃回调（用于上传服务器） |
| `initBugly(Context, String, boolean)` | 初始化 Bugly 上报 |
| `initBugly(Context, String, String, boolean)` | 初始化 Bugly 上报（带渠道） |
| `getCrashPath()` | 获取崩溃日志存储目录 |
| `getCrashFiles()` | 获取所有崩溃日志文件列表（按时间排序） |
| `cleanExpiredCrashLogs()` | 手动清理超过 7 天的崩溃日志 |
| `clearAllCrashLogs()` | 清除所有崩溃日志 |

### 回调接口

```java
public interface OnCrashListener {
    /**
     * @param context     上下文
     * @param crashReport 崩溃报告完整内容（包含设备信息和堆栈）
     */
    void onCrash(Context context, String crashReport);
}
```
