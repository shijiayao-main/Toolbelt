# 日志系统

文件：`core/log/`

---

## JLog

```kotlin
object JLog {
    fun init(log: Logger)                                    // 替换日志实现（默认为 DefaultLogImpl）
    fun d(tag: String, msg: () -> String)
    fun i(tag: String, msg: () -> String)
    fun w(tag: String, msg: () -> String)
    fun e(tag: String, msg: () -> String)
    fun e(tag: String, e: Throwable?, msg: () -> String)     // 带异常堆栈，ERROR 级输出
}
```

`msg` 为 `() -> String` 惰性求值：仅当日志被真正输出时 lambda 才执行，关闭对应级别或未消费时可避免字符串拼接开销。

### 带异常的调用

```kotlin
// 常规日志
JLog.d(TAG) { "device state: $state" }

// 异常日志：堆栈以 ERROR 级完整输出（KotlinLoggingLogImpl 委托 logger.error(throwable)）
// e 为 null 时退化为普通 e(tag, msg)，调用方无需分支判断
runCatching { doSomething() }
    .onFailure { e -> JLog.e(TAG, e) { "doSomething failed" } }
```

默认实现 `DefaultLogImpl` 输出到 stdout。可在应用启动时调用 `JLog.init(...)` 替换为自定义实现（实现 `Logger` 接口）；实际运行时由 `JLogInitializer` 初始化为 `KotlinLoggingLogImpl`（kotlin-logging + Logback，root 级别 DEBUG）。

项目内所有日志调用统一使用 `JLog.d(TAG) { ... }` 形式；未捕获异常兜底（线程与协程）由 `GlobalUncaughtExceptionHandler` 以 `JLog.e(TAG, throwable)` 记录。
