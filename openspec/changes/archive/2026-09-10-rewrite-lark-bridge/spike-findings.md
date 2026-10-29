# M1 Spike 闸门实测报告（S1 / S2 / S3 / S5 / S7）

> 本报告是「重写飞书桥接」方案 M1 里程碑的**实测取证记录**，所有结论均来自实际命令输出与 SDK 源码原文，
> 不含推测。凡推测处均显式标注。
>
> - 仓库：本仓（路径此处略）
> - 起始 HEAD：`bd03d58 update`（分支 `main`），全程**未执行任何 git 写操作**，收尾时 HEAD 与工作区状态见文末「文件变更清单」。
> - Spike 性质：验证性任务，**未写任何业务代码**。
> - 未覆盖：S4（凭据长连接连通）与 S6（卡片流式限制）需要用户提供飞书凭据（不入库），属独立任务。

## 结论速览

| Spike | 判定 | 一句话结论 |
|---|---|---|
| S1 SDK Gradle 集成 | **PASS** | `:lark-gateway` 装配成功，`oapi-sdk:2.8.5` 解析成功，`compileKotlin` 绿；convention 插件已把 jvmTarget 固化为 JVM_11，模块内无需再声明。 |
| S2 jlink 打包 | **PASS** | `packageDistributionForCurrentOS` 成功产出 `.deb`，解包后启动可执行文件 30 秒存活并进入 Compose `main()`，无 `NoClassDefFoundError`；**无需新增 `modules(...)`，无需 `includeAllModules = true`**。 |
| S3 LarkChannel 真实 API 四问 | **PASS（四问全部有源码级答案）** | 四问全部拿到源码原文证据；同时发现 **7 项方案基线偏差**（A~G），其中 2 项会改变 M3 契约的实现细节，已单列「待 Leader 裁决」。 |
| S5 Gson 与 slf4j 共存 | **PASS** | `slf4j-api` 被冲突解析**升级**到 `2.0.17`（非降级），logback 1.5.18 provider 绑定成功；Gson 2.9.0 与 kotlinx-serialization-json 1.9.0 同 classpath 互不干扰；无 `NoSuchMethodError`。 |
| S7 shadowJar 的 slf4j ServiceLoader | **FAIL（隐患成立，只取证未修）** | `exclude("META-INF/")` 确实剥掉 `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`，实测复现 `SLF4J(W): No SLF4J providers were found.` + NOP logger。修复留给 M3-T2。 |

**M1 闸门裁决：S1 + S2 + S3 全部 PASS ⇒ 可以进入 M3 的网关契约定稿**，但必须先处理 S3 暴露的偏差 A/B/E（见「S3 · 待 Leader 裁决的架构决策点」）。

---

## S1 · SDK Gradle 集成

### 验证方法（实际命令）

```bash
# 1) 先确认 Maven Central 上 2.8.5 真实存在（方案 S1 的失败应对是「退回自研 WS」，需先排除版本不存在）
curl -s https://repo1.maven.org/maven2/com/larksuite/oapi/oapi-sdk/ | grep -oE '2\.[0-9]+\.[0-9]+' | sort -uV | tail -8
curl -s https://repo1.maven.org/maven2/com/larksuite/oapi/oapi-sdk/2.8.5/oapi-sdk-2.8.5.pom

# 2) 新增 catalog 条目 + include 模块 + 建空壳模块，然后编译
./gradlew :lark-gateway:compileKotlin --console=plain

# 3) 打印依赖树
./gradlew :lark-gateway:dependencies --configuration runtimeClasspath --console=plain
```

### 实测输出关键片段

版本目录（Maven Central）末尾：

```
2.7.1  2.7.2  2.7.3  2.8.1  2.8.2  2.8.3  2.8.4  2.8.5
```

⇒ `2.8.5` 存在且是**当前最新版**，方案的「退回自研 WebSocket 协议栈」失败应对**无需触发**。

编译结果：

```
> Task :lark-gateway:compileKotlin
BUILD SUCCESSFUL in 1m 23s
```

`:lark-gateway` 的 `runtimeClasspath` 完整依赖树（**这就是 SDK 引入的全部传递依赖，M7 文档要抄**）：

```
runtimeClasspath - Runtime classpath of 'main'.
+--- org.jetbrains.kotlin:kotlin-stdlib:2.4.0
|    \--- org.jetbrains:annotations:13.0 -> 23.0.0
+--- com.larksuite.oapi:oapi-sdk:2.8.5
|    +--- commons-codec:commons-codec:1.15
|    +--- com.google.code.gson:gson:2.9.0
|    +--- org.slf4j:slf4j-api:1.7.30
|    +--- org.apache.httpcomponents:httpclient:4.5.13
|    |    +--- org.apache.httpcomponents:httpcore:4.4.13
|    |    +--- commons-logging:commons-logging:1.2
|    |    \--- commons-codec:commons-codec:1.11 -> 1.15
|    +--- org.apache.httpcomponents:httpmime:4.5.13
|    +--- com.larksuite.oapi:larksuite-oapi-shaded-protobuf:2.4.6
|    \--- com.google.guava:guava:32.0.0-jre
|         +--- com.google.guava:failureaccess:1.0.1
|         +--- com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava
|         +--- com.google.code.findbugs:jsr305:3.0.2
|         +--- org.checkerframework:checker-qual:3.33.0
|         +--- com.google.errorprone:error_prone_annotations:2.18.0
|         \--- com.google.j2objc:j2objc-annotations:2.8
\--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0
     \--- org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0
          +--- org.jetbrains:annotations:23.0.0
          +--- org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.11.0
          \--- org.jetbrains.kotlin:kotlin-stdlib:2.2.20 -> 2.4.0
```

POM 里的 `conscrypt-openjdk-uber` / `android-all` / `jsr305` / `animal-sniffer-annotations` 均为 `provided` 或 `test` scope，**未进入 runtimeClasspath**（与上面的树一致）。`maven-compiler-plugin` 的 `source/target = 1.8` ⇒ SDK 字节码是 Java 8，与 `:lark-gateway` 的 jvmTarget 11 兼容（8 ≤ 11）。

### 关于 jvmTarget 的一个额外取证（方案未写明，实测补上）

`build-logic/src/main/kotlin/JvmCliConventionPlugin.kt` 已经在 convention 层把 Java/Kotlin target 统一钉死为 11：

```kotlin
extensions.configure(JavaPluginExtension::class.java) {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
extensions.configure(KotlinJvmProjectExtension::class.java) {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}
dependencies.add("testImplementation", "org.jetbrains.kotlin:kotlin-test")
```

⇒ **`:lark-gateway/build.gradle.kts` 不需要（也不应该）重复声明 jvmTarget 11**，`kotlin("test")` 也已由 convention 注入。
⇒ convention 已经 `pluginManager.apply("com.gradleup.shadow")`，所以 `:lark-gateway` 无需再 `alias(libs.plugins.gradleupShadow)`（`:log-tools` 里那行 alias 是冗余的，不是错误）。

### 判定：**PASS**

- `:lark-gateway:compileKotlin` 绿；
- 依赖解析成功，无版本冲突告警；
- SDK 依赖用 `implementation`（非 `api`）声明 ⇒ 编译期隔离生效（M3-T4 的 `LarkSdkIsolationTest` 是第二道，AGENTS.md 约定是第三道）。

### 对后续里程碑的影响与既定应对

1. **M3-T2**：`:lark-gateway` 的 jvmTarget 已由 convention 保证为 11，T2 不需要为它做任何 target 修正；T2 的工作量集中在 shadowJar 的 `exclude("META-INF/")`（见 S7）与 `LogbackHelper`。
2. **M3-T3**：契约代码可以直接写在 `lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/`，包名 `com.jiaoay.lark.gateway` 已占位。
3. **依赖体积**：SDK 主 jar 21 MB + guava 3 MB + shaded-protobuf 1.9 MB ≈ **+26 MB**，进 `.deb` 后实测包体可接受（见 S2）。
4. **`httpclient` / `httpmime` 是死依赖**（见 S3-G 与 S2 的 jdeps 段）：M7 文档应写明「SDK 传递引入 Apache HttpClient 4.5.13，但 SDK 实际走 shaded OkHttp，httpclient 路径不会被执行」，避免后人误以为要维护它。

---

## S2 · jlink 打包与启动

### 验证方法（实际命令）

```bash
# 1) 临时给 composeApp 加 implementation(project(":lark-gateway"))，跑打包
./gradlew :composeApp:packageDistributionForCurrentOS --console=plain > build/spike-tmp/s2-package.log 2>&1

# 2) 定位产物并解包（jlink runtime 与 launcher 只保留在 .deb 里，build/compose/main-release 不存在）
dpkg-deb -x composeApp/build/compose/binaries/main/deb/easydevtoolbox_1.0.0_amd64.deb build/spike-tmp/deb-x

# 3) 确认真的把 SDK 打进去了
dpkg-deb -c .../easydevtoolbox_1.0.0_amd64.deb | grep -iE "oapi|gson|slf4j|logback|guava|httpclient|protobuf|lark"

# 4) 取 jlink 实际模块清单
ls build/spike-tmp/deb-x/opt/easydevtoolbox/lib/runtime/legal/

# 5) 启动可执行文件（DISPLAY=:0 真实存在，非 headless）
cd build/spike-tmp/deb-x/opt/easydevtoolbox && timeout 30 ./bin/EasyDevToolbox

# 6) jdeps 实测各 jar 的模块需求
jdeps --print-module-deps --ignore-missing-deps <jar>
jdeps --multi-release 17 --print-module-deps --ignore-missing-deps <jar>   # gson/slf4j/logback-core 是多发行版 jar
```

### 实测输出关键片段

打包结果：

```
> Task :composeApp:packageDeb
> Task :composeApp:packageDmg SKIPPED
> Task :composeApp:packageMsi SKIPPED
> Task :composeApp:packageDistributionForCurrentOS
BUILD SUCCESSFUL in 1m 46s
78 actionable tasks: 56 executed, 22 up-to-date
EXIT=0
```

产物：`composeApp/build/compose/binaries/main/deb/easydevtoolbox_1.0.0_amd64.deb`

`.deb` 内 `lib/app/` 里的 SDK 相关 jar（**证明不是空转，:lark-gateway 与 SDK 全链路都进了包**）：

```
lark-gateway-f92c867b1ed2f6dfe17a58669f42b87.jar                     456 B   ← 空壳模块本身
oapi-sdk-2.8.5-74968bf68ad5d7b878e8eb7b871ecf8d.jar            21,096,038 B
larksuite-oapi-shaded-protobuf-2.4.6-f38881514367e3fb06aea76be21e4e6.jar 1,923,960 B
gson-2.9.0-53fa3e6753e9d931d62cb8958fde2f.jar                     249,277 B
guava-32.0.0-jre-677b5dfdedfbdbc13bc0478878de7aa.jar            3,034,489 B
httpclient-4.5.13-40d6b975fbd28fa10292a45a0db9457.jar             780,321 B
httpcore-4.4.13-e07a248f61c52776a2366c75dcd4963.jar
httpmime-4.5.13-3fc1ef2c9dc47b62b78192f54bc18.jar
commons-codec-1.15 / commons-logging-1.2
slf4j-api-2.0.17-b648d114a23683498ac3f746f959d2f.jar              69,908 B
logback-classic-1.5.18 / logback-core-1.5.18
failureaccess-1.0.1 / listenablefuture-9999.0 / checker-qual-3.33.0 /
error_prone_annotations-2.18.0 / j2objc-annotations-2.8 / jsr305-3.0.2
```

`EasyDevToolbox.cfg` 里 `lark-gateway-*.jar`、`oapi-sdk-2.8.5-*.jar`、`slf4j-api-2.0.17-*.jar` 均以 `app.classpath=$APPDIR/...` 形式登记 ⇒ 运行时 classpath 完整。

**jlink 实际模块清单**（`lib/runtime/legal/` 目录名即模块名，共 9 个 —— M7 文档要抄）：

```
java.base
java.datatransfer
java.desktop
java.logging
java.net.http          ← composeApp/build.gradle.kts 里显式 modules("java.net.http") 的结果
java.prefs
java.xml
jdk.crypto.ec
jdk.unsupported        ← composeApp/build.gradle.kts 里显式 modules("jdk.unsupported") 的结果
```

启动实测（`DISPLAY=:0`，真实 X 显示，**不是 headless**）：

```
$ timeout 30 ./bin/EasyDevToolbox ; echo EXIT=$?
EXIT=124                          ← 124 = timeout 到时进程仍存活 = 启动成功且未崩溃

d: AppInitializerManager: startInit:
d: LogbackInitializer: init: start
kotlin-logging: initializing... active logger factory: Slf4jLoggerFactory
22:29:51.737 [DefaultDispatcher-worker-1] INFO  c.j.e.core.log.JLogInitializer - LogbackInitializer: init: 日志初始化完成
22:29:52.232 [AWT-EventQueue-0] DEBUG c.j.e.ComposableSingletons$Main - main: after startInit
22:29:52.848 [DefaultDispatcher-worker-2] INFO  c.j.e.c.l.LarkBridgeManager - LarkBridgeManager: create LarkBridge: transport=openapi appId=cli_****1bc4 ...
```

⇒ 窗口进程 30 秒存活、日志系统初始化完成、`ComposableSingletons$Main.main` 已进入、旧 `LarkBridgeManager` 正常构造。
⇒ **全程无 `NoClassDefFoundError`、无 `java.lang.module.ResolutionException`、无 "No SLF4J providers"**。
⇒ 这次启动同时构成 S5 的**最高保真运行时证据**（真实 jlink runtime + 真实发布 classpath 下 slf4j 2.0.17 与 logback 1.5.18 绑定成功）。

**jdeps 实测输出**（方案要求记进报告，无论 PASS 与否）：

| jar | `jdeps --print-module-deps --ignore-missing-deps` | 是否已被 runtime 覆盖 |
|---|---|---|
| `oapi-sdk-2.8.5.jar` | `java.base,java.logging` | ✅ 全覆盖 |
| `larksuite-oapi-shaded-protobuf-2.4.6.jar` | `java.base,java.logging,jdk.unsupported` | ✅ 全覆盖 |
| `guava-32.0.0-jre.jar` | `java.base,java.logging,jdk.unsupported` | ✅ 全覆盖 |
| `httpcore-4.4.13.jar` | `java.base` | ✅ |
| `httpmime-4.5.13.jar` | `java.base` | ✅ |
| `commons-codec-1.15.jar` | `java.base` | ✅ |
| `commons-logging-1.2.jar` | `java.base,java.logging` | ✅ |
| `gson-2.9.0.jar` | `java.base`（需 `--multi-release`） | ✅ |
| `slf4j-api-2.0.17.jar` | `java.base`（需 `--multi-release`） | ✅ |
| `logback-core-1.5.18.jar` | `java.base,java.xml`（需 `--multi-release`） | ✅ |
| `logback-classic-1.5.18.jar` | jdeps 自身抛异常（`JdepsTask.run` 栈），未取得结果 | — 不阻塞：启动实测已证明 logback 在 jlink runtime 里正常工作 |
| **`httpclient-4.5.13.jar`** | **`java.base,java.naming,java.security.jgss`** | ⚠️ **`java.naming` 与 `java.security.jgss` 均不在 runtime 清单内** |
| `lark-gateway-*.jar`（空壳） | 空（无类） | — |

**唯一缺口 `httpclient → java.naming + java.security.jgss` 的裁决：不需要补，因为它是死代码路径。** 源码级证据：

```bash
$ grep -rn "org\.apache\.http" build/spike-tmp/src --include="*.java" | wc -l
9                      # 9 处命中全部集中在同一个文件
$ grep -rln "org\.apache\.http" build/spike-tmp/src --include="*.java"
com/lark/oapi/core/httpclient/ApacheHttpClientTransport.java      # 仅此一个文件
$ grep -rn "ApacheHttpClientTransport\|OkHttpTransport" build/spike-tmp/src --include="*.java" \
    | grep -v "core/httpclient/"
com/lark/oapi/core/Transport.java:21:import com.lark.oapi.core.httpclient.OkHttpTransport;
com/lark/oapi/core/Transport.java:325:                    httpTransport = new OkHttpTransport(OKHttps.defaultClient);
com/lark/oapi/Client.java:24:import com.lark.oapi.core.httpclient.OkHttpTransport;
com/lark/oapi/Client.java:578:              new OkHttpTransport(
com/lark/oapi/Client.java:581:          config.setHttpTransport(new OkHttpTransport(OKHttps.defaultClient));
```

`Transport.doSend()` 原文：

```java
IHttpTransport httpTransport = config.getHttpTransport();
if (httpTransport == null) {
    httpTransport = new OkHttpTransport(OKHttps.defaultClient);
}
RawResponse rawResponse = httpTransport.execute(request);
```

⇒ **`ApacheHttpClientTransport` 在整个 SDK 源码树里没有任何实例化点**，默认传输是 shaded OkHttp（`com.lark.oapi.okhttp`）。
上传路径也走 OkHttp：`OkHttpTransport.buildReqBody()` 用的是 `MultipartBody.Builder`，不是 `httpmime` 的 `MultipartEntityBuilder`。
WebSocket 路径同样是 shaded OkHttp（`ws/Client.java` 里 `httpClient = new OkHttpClient()`）。
唯一能让 httpclient 活起来的方式是我们主动调 `LarkChannelOptions.Builder.httpTransport(...)` 注入 —— 方案里没有这个动作，**M3-T3 也禁止注入**。

因此：`java.naming` 缺失只影响一条永不执行的代码路径。

### 判定：**PASS**

- 打包成功（`BUILD SUCCESSFUL`，`EXIT=0`，产出 `.deb`）；
- 可执行文件启动成功（30 秒存活，进入 Compose `main()`，日志系统就绪）；
- 无 `NoClassDefFoundError`；
- 失败原因分类不适用（本次没有失败，且环境有真实 `DISPLAY=:0`，不存在 headless 干扰）；
- **两次失败即改 `includeAllModules = true` 的应对路径未触发**，`modules(...)` 无需追加。

### 对后续里程碑的影响与既定应对

1. **`composeApp/build.gradle.kts` 的临时依赖已按约定移除**，收尾后 `git diff composeApp/build.gradle.kts` 为空。最终形态由 M6 经 `:feature-larkbridge → :lark-bridge → api(:lark-gateway)` 传递引入；由于 S2 已证明「直接在 composeApp classpath 上带 SDK 打包 + 启动」没有问题，M6 的传递引入路径不会引入新的 jlink 风险。
2. **不需要补 `modules(...)`，也不需要 `includeAllModules = true`**（省掉约 40 MB 包体）。这条结论要写进 M7 的 `docs/pages/lark-bridge.md`「SDK 边界实测」小节。
3. **⚠️ 遗留风险 R-S2-1（必须在 M5 真机验证时专门确认）**：本次启动验证中 `:lark-gateway` 是空壳，**没有任何代码真正加载 SDK 的类**。因此 S2 证明的是「新增依赖不破坏打包与启动」，**没有**证明「SDK 的网络/加密路径在裁剪后的 jlink runtime 里不缺模块」。SDK 走 shaded OkHttp + JDK 的 TLS，而 `java.base` 已含 `javax.net.ssl`、`jdk.crypto.ec` 已含 EC 算法，理论上足够；但这是理论推断。M5（T11 端到端真机验证）必须用**打好的 `.deb`/`.msi`/`.dmg`**（而不是 `:composeApp:run`，后者跑在完整 JDK 上）实际收发一次消息，确认不出现 `NoClassDefFoundError: javax/naming/...` 或 TLS 握手失败。若真出现，按方案既定应对补 `modules("java.naming")` 或退到 `includeAllModules = true`。
4. **⚠️ 遗留风险 R-S2-2**：jlink runtime 里没有 `java.management` 与 `java.naming`。logback 1.5.18 的 `JMXConfigurator` 需要 `java.management`，但只有在 `logback.xml` 里显式写 `<jmxConfigurator/>` 才会被激活。本次启动实测无异常 ⇒ 当前配置没有激活它。**M4/M7 若给桥接加 logback 配置，禁止引入 `<jmxConfigurator/>`**，否则需要补 `modules("java.management")`。

---

## S3 · LarkChannel 真实 API 四问（最重要）

### 验证方法（实际命令）

不用文档、不用 javadoc jar，直接下 **sources jar** 读源码 + `javap` 打公开签名，这是最强证据：

```bash
# sources jar 不在 Gradle 缓存里（Gradle 不自动下载），从 Central 取到 workspace 内的 gitignored 目录
curl -sL -o build/spike-tmp/oapi-sdk-2.8.5-sources.jar \
  https://repo1.maven.org/maven2/com/larksuite/oapi/oapi-sdk/2.8.5/oapi-sdk-2.8.5-sources.jar   # 22.6 MB
cd build/spike-tmp && unzip -q oapi-sdk-2.8.5-sources.jar -d src

# 包结构
unzip -l ~/.gradle/caches/.../oapi-sdk-2.8.5.jar | awk '{print $4}' | grep '\.class$' | xargs -n1 dirname | sort -u

# 公开签名
javap -cp ~/.gradle/caches/.../oapi-sdk-2.8.5.jar com.lark.oapi.channel.LarkChannel
javap -cp ... com.lark.oapi.channel.outbound.OutboundSender
javap -cp ... com.lark.oapi.ws.Client

# 全树取证 Q4（retry / 限流头）
grep -rniE "x-ogw|ratelimit|rate_limit|retry-after|retryafter" src --include="*.java"
```

### 四问答案表

| # | 问题 | 答案 | 判定 | 源码证据 |
|---|---|---|---|---|
| Q1 | `connect()` / `send()` / 卡片流式的返回类型与线程模型 | **全部 `CompletableFuture`**，且默认跑在 **`ForkJoinPool.commonPool()`**（`CompletableFuture.runAsync/supplyAsync` 无自定义 executor 重载）。**handler 在 shaded-okhttp 的 WS reader 线程上被同步调用**，返回后 SDK 才回 ACK 并上报 `X-Biz-Rt`。 | **PASS（可用，但必须改造）** | 见下 Q1 原文 |
| Q2 | `SafetyConfig` 的去重键是 `message_id` 还是 `event_id` | **`message_id`**。`NormalizeDedupKeys.message()` 拼 `"message:" + messageId`；`SafetyPipeline.eventId(msg)` 方法名叫 eventId 但**实际返回 `msg.getMessageId()`**。 | **PASS（与方案 D12 一致，无需改）** | 见下 Q2 原文 |
| Q3 | 卡片流式是否内部维护 `sequence` | **是，SDK 自己维护**。`MarkdownStreamControllerImpl` 有实例字段 `private int sequence;`，每次 `++sequence`。每个流一个 controller 实例 ⇒ 天然单调递增。 | **PASS，且比方案预期更好：R8 的 `card_id→sequence` LRU(256) 与 `cardSequenceMapSize` 指标可以直接删掉** | 见下 Q3 原文 |
| Q4 | `RetryConfig` 是否感知 `x-ogw-ratelimit-reset` | **完全不感知**。`RetryConfig` 只有 `maxAttempts` / `baseDelayMs` 两个字段；重试是 `sleep(baseDelayMs * 3^(attempt-1))` 的盲退避。全树 grep `x-ogw\|ratelimit\|rate_limit\|retry-after` 在 SDK 自有代码里**零命中**（只命中 shaded okhttp 内部的通用 `Retry-After` 与 `LarkChannelErrorCode.RATE_LIMITED` 枚举名）。 | **FAIL（SDK 不具备该能力）** ⇒ 限流退避必须由 `:lark-gateway` 自己做，或接受 SDK 的盲退避 | 见下 Q4 原文 |

#### Q1 原文证据

`channel/LarkChannel.java`：

```java
public synchronized CompletableFuture<BotIdentity> connect() {
    if (connectPromise != null) { return connectPromise; }
    CompletableFuture<BotIdentity> promise = new CompletableFuture<>();
    connectPromise = promise;
    CompletableFuture.runAsync(() -> { ... promise.complete(establishConnection()); ... });
    return promise;                                   // ← commonPool
}
private BotIdentity establishConnection() {
    BotIdentity identity = fetchBotIdentity();
    botIdentity = identity;
    safetyPipeline.setBotIdentity(identity);
    if (rawWsClient != null) {
        rawWsClient.start();
        awaitWebSocketReady(rawWsClient, 15000L);      // ← 15 秒握手超时（方案未写明这个数值）
    }
    connected = true;
    return identity;
}
public BotIdentity connectSync() { return connect().join(); }
public CompletableFuture<Void> disconnect() { return CompletableFuture.runAsync(() -> { ... }); }
public CompletableFuture<SendResult> send(String to, SendInput input, SendOptions sendOptions) {
    return CompletableFuture.supplyAsync(() -> outboundSender.send(to, input, sendOptions));   // ← commonPool
}
public CompletableFuture<SendResult> stream(String to, StreamInput input, SendOptions sendOptions) {
    return CompletableFuture.supplyAsync(() -> outboundSender.stream(to, input, sendOptions)); // ← commonPool
}
public CompletableFuture<byte[]> downloadResource(String fileKey, String type) { ... }
public CompletableFuture<String> addReaction(String messageId, String emojiType) { ... }
public CompletableFuture<Boolean> removeReactionByEmoji(String messageId, String emojiType) { ... }
private void awaitWebSocketReady(com.lark.oapi.ws.Client wsClient, long timeoutMs) {
    try { wsClient.awaitReady(timeoutMs); }
    catch (Exception e) { wsClient.close();
        throw new LarkChannelException(LarkChannelErrorCode.NOT_CONNECTED,
                "websocket handshake did not complete", null, e); }
}
```

`channel/ChannelEventBus.java`（handler 是**同步**调用，在调用方线程即 WS reader 线程上）：

```java
<T> void emit(String eventName, T event) {
    ChannelEventHandler<?> handler = handlers.get(eventName);
    if (handler == null) return;
    try { ((ChannelEventHandler<T>) handler).handle(event); }        // ← 同步、调用方线程
    catch (Throwable error) {
        if ("error".equals(eventName)) LOGGER.warn(...); else emitError(eventName, error, event);
    } }
<T> ChannelSubscription on(String eventName, ChannelEventHandler<T> handler) {
    if (handlers.containsKey(eventName)) LOGGER.warn("channel: handler for \"{}\" is being overwritten", eventName);
    handlers.put(eventName, handler);   // ← 单 handler per event name，后注册覆盖前者（不是多播！）
    return () -> handlers.remove(eventName, finalHandler); }
```

`ws/Client.java`（ACK 在 handler 返回之后才发，并且上报业务耗时）：

```java
private void handleDataFrame(Pbbp2.Frame frame) {
    ...
    Response response = new Response(200);
    long start = System.currentTimeMillis();
    try { switch (mt) { case EVENT: Object r = this.eventHandler.doWithoutValidation(pl);   // ← 同步
                        if (r != null) response.setData(Jsons.DEFAULT.toJson(r).getBytes(UTF_8)); break;
                        case CARD: return; } }
    catch (Throwable e) { ...; response = new Response(500); }                              // ← 抛异常 ⇒ ACK 500 ⇒ 飞书重推
    long end = System.currentTimeMillis();
    byte[] bs = frame.toBuilder().setPayload(ByteString.copyFrom(resp))
        .addHeaders(Pbbp2.Header.newBuilder().setKey(HEADER_BIZ_RT)
                     .setValue(String.valueOf(end - start)).build())                        // ← X-Biz-Rt = handler 耗时
        .toByteArray();
    this.conn.send(com.lark.oapi.okio.ByteString.of(bs)); }                                 // ← ACK 此时才发
```

⇒ **方案的「handler 3 秒铁律」被 SDK 的实现机制完全印证**：handler 阻塞会直接推迟 ACK，飞书侧超时即重推。所以「SDK 回调线程内只允许 `runCatching { inboundChannel.trySend(msg) }`」这条硬约束是结构性必需的，不是保守。

`SafetyPipeline.dispatchMessageBatch()` 还揭示了第二个后果：**去重标记在 handler 返回之后才写**：

```java
private void dispatchMessageBatch(BatchedDispatch batch) {
    try { onMessage.onMessage(batch.getMessage()); }
    finally { for (String id : batch.getSourceIds()) { if (id != null) { seenCache.mark(id); lock.release(id); } } } }
```

⇒ handler 阻塞期间 `seenCache` 尚未标记，飞书的重推**不会被去重拦住**，会并发进入处理链。这是「必须 O(1) 非阻塞」的第二个独立理由。

##### 协程化规则（与方案一致，可直接落地）

- `CompletableFuture` → `kotlinx.coroutines.future.await()`。已解包验证 `kotlinx/coroutines/future/FutureKt.class` 就在 `kotlinx-coroutines-core-jvm-1.11.0.jar` 内 ⇒ **不需要新增 `kotlinx-coroutines-jdk8`**（方案硬约束成立）。
- 一律包 `withContext(netDispatcher)`，避免 commonPool 与我们的 IO dispatcher 混用。
- 回调式（`on(eventName, handler)`）→ `callbackFlow` + `awaitClose { subscription.unsubscribe() }`。

#### Q2 原文证据

`channel/normalize/NormalizeDedupKeys.java`：

```java
public static String message(NormalizedMessage event) { return "message:" + safe(event.getMessageId()); }
public static String cardAction(CardActionEvent event) {
    return "card:" + safe(event.getMessageId()) + ":" + safe(event.getOperatorId()) + ":" + safe(event.getActionTag())
            + ":" + safe(event.getActionName()) + ":" + safe(event.getActionOption()) + ":" + truncate(safe(valueJson), 128); }
public static String reaction(ReactionEvent event) {
    return "rx:" + safe(event.getMessageId()) + ":" + safe(event.getOperatorId()) + ":" + safe(event.getEmojiType())
            + ":" + safe(event.getAction()) + ":" + event.getActionTime(); }
```

`channel/safety/SafetyPipeline.java`：

```java
public void pushMessage(final NormalizedMessage msg) {
    ...
    final String eventId = eventId(msg);
    if (isStale(msg)) return;
    if (seenCache.contains(eventId)) return;
    RejectReason rejectReason = policy.evaluate(msg);
    if (rejectReason != null) { if (onReject != null) onReject.onReject(new RejectEvent(...)); return; }
    if (!lock.tryAcquire(eventId)) return;
    ...
}
private String eventId(NormalizedMessage msg) { return msg.getMessageId() == null ? "" : msg.getMessageId(); }   // ← 名为 eventId，实为 messageId
private boolean isStale(NormalizedMessage m) {
    return m.getCreateTime() > 0L && System.currentTimeMillis() - m.getCreateTime() > staleWindow; }
```

⇒ **去重键 = `messageId`**，与方案「幂等键用 messageId，不要用 eventId」的硬约束一致。
⇒ 额外收益：卡片回传（`cardAction`）与表情回应（`reaction`）的去重键是**复合键**（含 operator/action/tag/option/value 或 emoji/action/actionTime），比 messageId 更细，天然支持「同一人对同一消息的不同表情分别计数」。M3-T3 的 `GatewayModel` 幂等设计可以直接依赖 SDK 这套键，不需要自己再造。

`channel/safety/SeenCache.java`（去重容量策略，方案未写明）：

```java
LinkedHashMap<String, Long> memory = new LinkedHashMap<>(16, 0.75f, true);   // ← access-order LRU
// sweepIfNeeded(): 按 dedupSweepMs 清过期
// trimIfNeededLocked(): 按 dedupMaxEntries 逐出最老
// namespaced(key) = dedupNamespace + ":" + key
```

默认 `dedupTtlMs = 12h`、`dedupMaxEntries = 5000`、`dedupSweepMs = 5min`、`staleMessageWindowMs = 30min`、`processingLockTtlMs = 5min`、`dedupNamespace = "channel:seen"`。
⇒ SDK 的去重是**纯内存 LRU**（可选注入 `ICache externalCache`）。进程重启即丢，所以方案里「同 appId 单实例互斥 `FileChannel.tryLock`」（D19）依然必要 —— SDK 不提供跨进程互斥。

#### Q3 原文证据

`channel/outbound/streaming/MarkdownStreamControllerImpl.java`：

```java
private static final String ELEMENT_ID = "stream_md";
private String content = "";  private String cardId = "";  private String messageId = "";
private int sequence;                     // ← SDK 内部维护 sequence（实例字段，每流一个）
private boolean started;

public void append(String chunk) {
    if (chunk == null || chunk.isEmpty()) return;
    ensureStarted();
    content = MergeStreamingText.merge(content, chunk);
    throttle.note(chunk.length()); }

private void ensureStarted() throws Exception {
    if (started) return;
    started = true;
    Map<String,Object> card = buildStreamingCard(sender.getStreamInitialText());
    cardId = sender.createCardInstance(card);                        // cardkit.v1.card.create
    messageId = sender.sendCardByReference(to, idType, cardId, options); }   // im.message.create + {"type":"card","data":{"card_id":...}}

private void pushContent() throws Exception {
    if (cardId == null || cardId.isEmpty()) return;
    final String snapshot = content == null || content.isEmpty() ? "..." : content;
    final int nextSequence = ++sequence;
    queue.enqueue(() -> sender.updateCardElementContent(cardId, ELEMENT_ID, snapshot, nextSequence)); }

private void completeTerminal() throws Exception {
    throttle.flushNow(); queue.drain();
    String finalContent = ...;
    if (content == null || content.isEmpty()) sender.updateCardElementContent(cardId, ELEMENT_ID, finalContent, ++sequence);
    sender.finishStreamingCard(cardId, buildStreamingCard(finalContent, false, truncateSummary(finalContent)), ++sequence); }

public SendResult run(MarkdownStreamProducer producer) {
    try { ensureStarted(); producer.produce(this); completeTerminal(); return new SendResult(messageId); }
    catch (Exception e) { failTerminal(); throw sender.wrapStreamingException("markdown stream failed", e); } }

private Map<String,Object> buildStreamingCard(String text, boolean streamingMode, String summary) {
    // markdown{tag, element_id="stream_md", content} → body{elements:[markdown]}
    // header{title:{tag:"plain_text", content:"Streaming response"}}     ← 标题硬编码英文
    // config{streaming_mode:bool, summary:{content:summary}}             // summary 默认 "[Generating...]"
    // card{schema:"2.0", header, config, body}                           ← 卡片 JSON 完全由 SDK 硬编码
}
```

`channel/outbound/streaming/Throttle.java` + `UpdateQueue.java`（**`append()` 可能同步阻塞在网络 IO 上**）：

```java
// Throttle
public void note(int deltaChars) throws Exception {
    pendingChars += deltaChars;
    long elapsed = System.currentTimeMillis() - lastFireAt;
    if (pendingChars >= chars || elapsed >= ms) flushNow(); }        // ← 达到阈值就同步 fire
public void flushNow() throws Exception { pendingChars = 0; lastFireAt = System.currentTimeMillis(); action.fire(); }
// UpdateQueue
public synchronized void enqueue(QueueTask task) throws Exception { task.run(); }   // ← 同步执行！不是队列
public void drain() { }                                                             // ← no-op
```

⇒ **`MarkdownStreamController.append()` 是同步阻塞调用**，节流阈值一到就在当前线程发 HTTP patch。
⇒ `:lark-gateway` 的 `CardStreamSink.tryAppend()` **不能**直接调 `append()`，否则「try」的语义（非阻塞、可失败）不成立。M3-T3 的实现必须是：`tryAppend` 只往内部 channel/队列塞，由一个专属协程在 `netDispatcher` 上串行消费并调 `append()`。这条必须写进契约文档。

`channel/outbound/streaming/CardStreamControllerImpl.java`（`Kind.CARD` 路径，**没有 sequence**，走的是 `im.v1.message.patch`）：

```java
public void update(Map<String,Object> next) { current = new LinkedHashMap<>(next); throttle.note(sender.toJson(current).length()); }
public SendResult run(CardStreamProducer producer) { sendInitial(); producer.produce(this); completeTerminal(); return new SendResult(messageId); }
private void sendInitial() throws Exception { messageId = sender.sendOneWithFallback(to, idType, "interactive", current, options); }
private void patch() throws Exception { final Map<String,Object> snapshot = new LinkedHashMap<>(current);
                                        queue.enqueue(() -> sender.patchCard(messageId, snapshot)); }   // im.v1.message.patch
```

⇒ **两种流式机制并存**：`MARKDOWN` 走 CardKit（`card_id` + `sequence`，原生流式），`CARD` 走消息 patch（无 sequence，本质是「消息编辑」）。
⇒ 方案 D1「弃用消息编辑改 CardKit」的正确落地方式是：**只用 `StreamInput.markdown(...)`，禁止使用 `StreamInput.card(...)`**。后者就是被否决的消息编辑路径。这条要写进 M3-T3 的实现约束与 M3-T4 的守护测试。

`channel/outbound/RawMessageSender.java` 的注释与实现（sequence 语义官方原文）：

```java
/**
 * Stream update: replace a card element's content. Sequence must be monotonically increasing.
 */
void updateCardElementContent(String cardId, String elementId, String content, int sequence) { ... }
```

#### Q4 原文证据

`channel/config/LarkChannelOptions.java`：

```java
public static class RetryConfig { private int maxAttempts = 3; private long baseDelayMs = 500L; }   // ← 只有两个旋钮，无 reset 头字段
```

`channel/outbound/OutboundRetry.java`：

```java
public static <T> T retry(RetryableOperation<T> operation, LarkChannelOptions.RetryConfig config) {
    int maxAttempts = config == null ? 3 : config.getMaxAttempts();
    long baseDelayMs = config == null ? 500L : config.getBaseDelayMs();
    LarkChannelException last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try { return operation.run(attempt); }
        catch (Exception error) {
            last = OutboundErrors.classifyError(error);
            if (attempt >= maxAttempts || !OutboundErrors.isRetryable(last)) throw last;
            sleep(baseDelayMs * (long) Math.pow(3, attempt - 1));    // ← 盲退避：500ms / 1500ms，不看任何响应头
        } }
    throw last == null ? new IllegalStateException("retry finished without result") : last; }
private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { ... } }   // ← Thread.sleep，占住 commonPool 线程
```

`channel/outbound/OutboundErrors.java`（**错误分类靠字符串猜**，不是靠结构化 code）：

```java
public static boolean isRetryable(LarkChannelException e) {
    return hasCode(e, LarkChannelErrorCode.RATE_LIMITED) || hasCode(e, LarkChannelErrorCode.UNKNOWN); }
private static LarkChannelErrorCode inferCode(String text, String lower) {
    if (text.contains("230020") || text.contains("230017") || text.contains("404")) return TARGET_REVOKED;
    if (text.contains("99991401") || text.contains("99991400") || text.contains("401") || text.contains("403")) return PERMISSION_DENIED;
    if (text.contains("230002") || text.contains("230001") || text.contains("400")) return FORMAT_ERROR;
    if (text.contains("429")) return RATE_LIMITED;
    if (lower.startsWith("ssrf_blocked") || lower.contains("ssrf")) return SSRF_BLOCKED;
    if (lower.contains("timeout") || text.contains("ETIMEDOUT") || text.contains("ECONNABORTED")) return SEND_TIMEOUT;
    return UNKNOWN; }
```

全树 grep（在解包源码上执行）：

```bash
$ grep -rniE "x-ogw|ratelimit|rate_limit|retry-after|retryafter" build/spike-tmp/src --include="*.java"
# SDK 自有代码：零命中
# 仅命中 shaded okhttp 内部的通用 Retry-After 处理，与 LarkChannel 的重试逻辑无关
# 以及 LarkChannelErrorCode.RATE_LIMITED 这个枚举名本身
```

⇒ **Q4 = FAIL：SDK 完全不感知 `x-ogw-ratelimit-reset`。**
额外风险：`inferCode` 是子串匹配，`UNKNOWN` 也被判定为 retryable ⇒ 一个包含 "400" 字样的普通错误文本可能被误分类；而真正的限流若响应体里不含 "429" 字样就会落到 `UNKNOWN` 分支（仍会重试，但退避时间错误）。
`Thread.sleep` 在 `ForkJoinPool.commonPool()` 上执行 ⇒ 限流重试期间会**占住 commonPool 线程**，与其他 SDK 异步调用互相饿死。这是「所有 SDK 调用必须包 `withContext(netDispatcher)`」的第三个独立理由（前两个见 Q1）。

**M3-T3 的既定应对（不改 SDK、不改契约）**：`:lark-gateway` 内部维护自己的限流退避层 —— 捕获 `LarkChannelException`，若 `getCode() == RATE_LIMITED` 则按自己记录的窗口退避后重试，SDK 的 `RetryConfig` 设 `maxAttempts = 1` 关掉它的盲退避，避免双层重试放大。此为**实现细节**，不改网关对外契约。

### S3 判定：**PASS**

四问全部取得源码级答案，其中 Q1/Q2/Q3 = PASS，Q4 = FAIL（SDK 能力缺失，但已给出在 `:lark-gateway` 内部消化的既定应对，不影响契约）。

### S3 附带发现的 7 项方案基线偏差（A~G）

> 这些是方案「已核验的事实基线」章节里**写错或漏写**的地方，全部有源码原文佐证。M3-T3 开工前必须吸收。

**A. 实际包名与方案基线不符（方案事实错误）**

方案写的是 `com.lark.oapi.config.*` 与 `com.lark.oapi.model.*`。**这两个包不存在。** `unzip -l` 实测包结构：

```
com/lark/oapi/channel/                       LarkChannel, ChannelEventBus, ChannelInboundProcessor,
                                             ChannelClientFactory, LarkChannelFactory, ChannelEventHandler,
                                             ChannelSubscription
com/lark/oapi/channel/config/                LarkChannelOptions（+ 所有静态嵌套 Config 类）
com/lark/oapi/channel/model/                 NormalizedMessage, SendInput, SendResult, SendOptions,
                                             StreamInput, BotIdentity, RejectEvent, MentionInfo, ResourceDescriptor
com/lark/oapi/channel/exception/             LarkChannelException, LarkChannelErrorCode
com/lark/oapi/channel/normalize/             NormalizeDedupKeys, NormalizeOptions, converters/
com/lark/oapi/channel/outbound/              OutboundSender, RawMessageSender, OutboundRetry, OutboundErrors,
                                             OutboundRouting, markdown/, media/, streaming/
com/lark/oapi/channel/safety/                SafetyPipeline, PolicyGate, SeenCache, ProcessingLock,
                                             ChatPipeline, ChatPipelineManager, BatchedDispatch, FlushHandler,
                                             OnMessageDispatch, OnReject, SafetyPipelineOptions
com/lark/oapi/ws/                            Client（+ enums/ exception/ model/ pb/）
com/lark/oapi/card/  com/lark/oapi/core/  com/lark/oapi/event/  com/lark/oapi/scene/  com/lark/oapi/service/
com/lark/oapi/okhttp/  com/lark/oapi/okio/  com/lark/oapi/google/protobuf/       ← shaded
```

⇒ 所有 `import` 语句要按 `channel.config` / `channel.model` 嵌套写。M3-T3 与 M3-T4（`LarkSdkIsolationTest` 的 import 黑名单）必须用真实包名，否则守护测试形同虚设。
⇒ 黑名单应包含：`com.lark.oapi`、`com.google.gson`、`org.apache.http`、`com.google.common`（guava）、`com.larksuite`。

**B. `openCardStream` 在 SDK 里没有直接等价物，但存在逃生舱**

契约里的 `openCardStream(...)` 对应 SDK 的 `stream(to, StreamInput.markdown(producer), options)` —— 但 SDK 的模型是**回调驱动**（`producer.produce(controller)` 在 SDK 的线程上同步执行，producer 返回即视为流结束），而契约的 `CardStreamSink` 是**推驱动**（调用方在任意时刻 `tryAppend` / `flushTerminal` / `close`）。两者语义不兼容。

好在 `OutboundSender` 是 **public 类，全部方法 public**（`javap` 实证），可以绕过 `LarkChannel.stream()` 自建推驱动的流：

```
public class com.lark.oapi.channel.outbound.OutboundSender {
  public final MediaUploader uploader;
  public OutboundSender(com.lark.oapi.Client, LarkChannelOptions);
  public SendResult send(String, SendInput, SendOptions);
  public SendResult stream(String, StreamInput, SendOptions);
  public String sendOneWithFallback(RawSendArgs);
  public String rawSendWithRetry(RawSendArgs);
  public String createCardInstance(Map<String,Object>);
  public String sendCardByReference(String, ReceiveIdType, String, SendOptions);
  public void updateCardElementContent(String, String, String, int);
  public void finishStreamingCard(String, Map<String,Object>, int);
  public void patchCard(String, Map<String,Object>);
  public String resolveCardId(String);
  public int getStreamThrottleMs();  public int getStreamThrottleChars();  public String getStreamInitialText();
  public String toJson(Object);  public RuntimeException wrapStreamingException(String, Exception);
  public void editMessage(String,String); public void updateCard(String,Map); public void recallMessage(String);
  public byte[] downloadResource(String,String); public String addReaction(String,String);
  public void removeReaction(String,String); public boolean removeReactionByEmoji(String,String); }
public final class OutboundRouting { public static ReceiveIdType detectReceiveIdType(String); }
```

但 `LarkChannel.outboundSender` 是 **`private final` 且无 getter**（`javap` 确认 `LarkChannel` 只暴露 `getRawClient()` / `getRawWsClient()`）。所以要自建 `OutboundSender`，只能 `new OutboundSender(larkChannel.getRawClient(), options)` —— 需要自己持有一份 `LarkChannelOptions`。

**这是需要 Leader 裁决的架构决策点（见下节 D-1）。**

**C. CardKit 流式的业务错误被 SDK 静默吞掉**

`RawMessageSender` 里只有 `rawSend()`（`im.message.create` / `reply`）与 `createCardInstance()` 调用了 `assertSuccessfulResponse()`；**`updateCardElementContent()` 与 `finishStreamingCard()` 把返回值完全丢弃**：

```java
private void assertSuccessfulResponse(BaseResponse<?> response, String action) {
    if (response == null) throw new LarkChannelException(UNKNOWN, action + " returned null response");
    if (!response.success()) throw new IllegalStateException(action + " failed: code=" + response.getCode() + ", msg=" + response.getMsg()); }
// ↑ 只有 rawSend / createCardInstance 调用了它

void updateCardElementContent(String cardId, String elementId, String content, int sequence) {
    try { ... client.cardkit().v1().cardElement().patch(PatchCardElementReq.newBuilder()...build());
          // ← 返回值被完全丢弃，无 success()/getCode() 检查
    } catch (Exception e) { throw OutboundErrors.classifyError(e, "updateCardElementContent failed"); } }

void finishStreamingCard(String cardId, Map<String,Object> card, int sequence) {
    try { ... client.cardkit().v1().card().settings(SettingsCardReq.newBuilder()...build());
          // ← 同样丢弃返回值
    } catch (Exception e) { throw OutboundErrors.classifyError(e, "finishStreamingCard failed"); } }
```

⇒ CardKit 的业务错误码（如 `300317` sequence 不递增、`200860`、`200810` 流式期间无法响应卡片回传）**只有 HTTP 层失败才会抛异常**，业务层失败是静默的：卡片会「看起来卡住不动」，日志里没有任何痕迹。
⇒ **既定应对（M3-T3）**：`CardStreamSink` 必须在自己的层面做「终端态确认」—— `flushTerminal()` 之后若 SDK 未抛异常，仍要假定可能失败，通过 `observe` 通道上报「卡片可能未终结」的观测事件，并在 UI 上保留可重试入口。M5 真机验证（T11）必须专门构造一次 sequence 冲突来验证这个静默行为。

**D. `updatePolicy` 只覆盖 5 个 `PolicyConfig` 字段，且没有 sender blocklist**

```java
public void updatePolicy(LarkChannelOptions.PolicyConfig partial) {
    if (partial == null) return;
    LarkChannelOptions.PolicyConfig policy = options.getPolicy();
    if (policy == null) return;
    policy.setGroupAllowlist(partial.getGroupAllowlist());
    policy.setDmMode(partial.getDmMode());
    policy.setDmAllowlist(partial.getDmAllowlist());
    policy.setRequireMention(partial.isRequireMention());
    policy.setRespondToMentionAll(partial.isRespondToMentionAll());
}
```

`PolicyConfig` 默认值：

```java
private List<String> groupAllowlist = Collections.emptyList();
private String dmMode = "open";                       // 只有 "open" / "disabled" / "allowlist"，没有 "blacklist"
private List<String> dmAllowlist = Collections.emptyList();
private boolean requireMention = true;
private boolean respondToMentionAll = false;
```

`PolicyGate.evaluate()` 原文：

```java
public RejectReason evaluate(NormalizedMessage message) {
    if ("group".equals(chatType) || "topic_group".equals(chatType)) {
        if (!policy.getGroupAllowlist().isEmpty() && !policy.getGroupAllowlist().contains(message.getChatId())) return GROUP_NOT_ALLOWED;
        if (message.isMentionAll()) return policy.isRespondToMentionAll() ? null : MENTION_ALL_BLOCKED;
        if (policy.isRequireMention() && !message.isMentionedBot()) return NO_MENTION;
        return null; }
    String dmMode = policy.getDmMode();
    if ("disabled".equals(dmMode)) return DM_DISABLED;
    if ("allowlist".equals(dmMode) && !policy.getDmAllowlist().contains(message.getSenderId())) return SENDER_NOT_ALLOWED;
    return null; }
enum RejectReason { GROUP_NOT_ALLOWED, SENDER_NOT_ALLOWED, NO_MENTION, DM_DISABLED, MENTION_ALL_BLOCKED }
```

⇒ **SDK 侧只有 allowlist，没有 blocklist**；而旧实现用的是 `accessMode=blacklist`（S2 启动日志实证：`accessMode=blacklist`）。
⇒ 若把访问控制交给 SDK，会丢掉黑名单能力（方案要求「现有能力一个不少」）。
⇒ **这是需要 Leader 裁决的架构决策点（见下节 D-2）。**
⇒ 另注：`updatePolicy` 传 `partial` 时是**全量覆盖这 5 个字段**（未设的字段会被写成 null/false），不是增量 merge。M3-T3 的 `updatePolicy` 实现必须先把当前完整 policy 复制一份再改目标字段，否则一次「只改 dmMode」的调用会把 groupAllowlist 清空。这是个容易踩的坑，要写进契约文档。

**E. `chatQueueEnabled` 默认 `true` + `delayMs = 600` 会合并消息，破坏我方管线语义 —— 必须显式设 `false`**

```java
public static class SafetyConfig {
    private long dedupTtlMs = 12L*60L*60L*1000L;          // 12h
    private int dedupMaxEntries = 5000;
    private long dedupSweepMs = 5L*60L*1000L;
    private long staleMessageWindowMs = 30L*60L*1000L;
    private boolean chatQueueEnabled = true;              // ← 默认开启！
    private long processingLockTtlMs = 5L*60L*1000L;
    private String dedupNamespace = "channel:seen";
    private BatchTextConfig batchText = new BatchTextConfig(); }
public static class BatchTextConfig {
    private long delayMs = 600L;  private int longThresholdChars = 1000;
    private long longDelayMs = 2000L;  private int maxMessages = 8;  private int maxChars = 4000; }
```

`SafetyPipeline.pushMessage()` 里的分叉：

```java
if (queueEnabled && msg.getChatId() != null && !msg.getChatId().isEmpty()) {
    manager.push(msg.getChatId(), msg, dispatchHandler);   // ← 走 ChatPipeline，会延迟并合并
    return; }
dispatchHandler.flush(new BatchedDispatch(msg, java.util.Collections.singletonList(eventId)));  // ← 直接派发
```

`ChatPipeline.mergeBatch()` 原文（**多条消息被合成一条，只保留最后一条的 messageId**）：

```java
private static NormalizedMessage mergeBatch(List<NormalizedMessage> batch) {
    if (batch.size() == 1) return batch.get(0);
    NormalizedMessage last = batch.get(batch.size() - 1);
    // content 用 "\n\n" 拼接；messageId / senderId / chatType / createTime / rootId / threadId 全取 last
    return new NormalizedMessage(last.getMessageId(), last.getChatId(), last.getChatType(), last.getSenderId(),
            last.getSenderName(), content.toString(), last.getRawContentType(), mergeResources(batch),
            mergeMentions(batch), mentionAll, mentionedBot, last.getRootId(), last.getThreadId(),
            last.getReplyToMessageId(), last.getCreateTime(), last.getRaw()); }
```

⇒ 如果保持默认 `true`：用户连发两条消息会被合成一条，且**第一条的 messageId 丢失** ⇒ 幂等键错乱、回复目标错乱、`observe` 上报的计数错乱。
⇒ **既定应对（M3-T3 强制）**：构造 `LarkChannelOptions` 时必须 `safety.chatQueueEnabled = false`。这条要写进 M3-T4 的守护测试（断言 options 构造结果）。
⇒ 副作用：关掉后 `ChatPipeline` 的 `lark-channel-chat-pipeline` 单线程 daemon scheduler 就不会被使用，减少一个线程。

**F. `ws.Client` 的 `FixedThreadPool` 永不 `shutdown()` ⇒ `StartStopLeakTest` 会 FAIL，并阻止 JVM 自然退出**

```java
protected final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2 + 1);
// 全文 grep "executor" 只有 2 处命中：第 49 行声明、第 168 行 this.executor.execute(this::pingLoop)
// ⇒ 全类无任何 executor.shutdown() / shutdownNow()
public void close() { this.userClosed = true; if (!readyFuture.isDone()) readyFuture.completeExceptionally(...); this.disconnect(); }
```

`ws.Client` 默认值（方案未写明）：`reconnectNonce = 30`、`reconnectCount = -1`（**无限重连**）、`reconnectInterval = 120s`、`pingInterval = 120s`；分片重组用 `CacheBuilder.newBuilder().expireAfterWrite(5, MINUTES)` 的 Guava Cache。

> 🔴 **真机实测更正（2026-09-08，T11 三轮安全子集）**：上行 `pingInterval = 120s` 是 **SDK 官方标称默认值**，但**真机实测 ping/pong 间隔恰为 90 秒**（三轮日志一致：`16:57:14 → 16:58:44 → 17:00:14`）。`lark-gateway/Tuning.kt`（115 行，§7.4 连接域）与 `:lark-bridge` **均未声明也未接线**任何 ping/心跳间隔常量 ⇒ 90s 是 SDK `ws.Client` 内部行为，**我方无可调点，不存在死的调优旋钮**。引用本行的 `pingInterval` 时请以 **90s** 为准。（`reconnectInterval = 120s` 与 `reconnectCount = -1` 未真机验，保持标称值。）

⇒ 该线程池是**非 daemon** 的 `FixedThreadPool`（`Executors.newFixedThreadPool` 默认线程工厂产生非 daemon 线程），`disconnect()`/`close()` 都不关它 ⇒ **`LarkChannel.disconnect()` 之后 JVM 不会自然退出**，且反复 connect/disconnect 会泄漏线程（每次 `new ws.Client` 泄漏 `2*CPU+1` 个线程）。
⇒ **既定应对（M3-T3 + M4-T9）**：
  - `SdkLarkGateway.close()` 不能只调 `disconnect()`，必须显式 `System.exit` 或在自己的生命周期管理里兜底；
  - 桥接进程（M4-T6 的进程原语）停止子进程时不能依赖「子进程自己退出」，必须有超时后强杀的路径；
  - `StartStopLeakTest`（若方案里有）需要把这条 SDK 泄漏登记为**已知豁免**，或改为断言「线程数增长有界」而非「回到基线」。
⇒ **这是需要 Leader 裁决的架构决策点（见下节 D-3）。**

**G. 默认 HTTP 传输是 shaded OkHttp，不是 Apache HttpClient（方案事实错误）**

已在 S2 的 jdeps 段用 grep 全树取证，此处只复述结论：`Transport.doSend()` 与 `Client` 的三处 `new` 全部是 `OkHttpTransport`；`ApacheHttpClientTransport` 无任何实例化点；上传走 OkHttp 的 `MultipartBody`。
⇒ 方案里「`lark-bridge` 的 `OpenApiClient`/`LarkWsClient` 依赖 JDK HttpClient/WebSocket」这条 `modules("java.net.http")` 的注释，对**新实现不再成立**（SDK 用 shaded OkHttp，不用 JDK HttpClient）。
⇒ 但 `modules("java.net.http") **不能删**：旧 `:lark-bridge` 在 M2 才拆，且删掉属 M3-T2 的范围外改动。**M7 文档应把该注释的理由改写为「历史遗留，新实现走 SDK 的 shaded OkHttp；保留以兼容 X」**，或经 Leader 同意后在 M3-T2 移除并重新验证打包。

### S3 · 待 Leader 裁决的架构决策点（我不自行改契约）

| # | 决策点 | 冲突描述 | 我的建议方案 | 影响 |
|---|---|---|---|---|
| **D-1** | `openCardStream` 的实现路径 | SDK 的 `stream()` 是**回调驱动**（producer 返回即结束），契约的 `CardStreamSink` 是**推驱动**（任意时刻 `tryAppend`）。语义不兼容。 | **自建 `OutboundSender`**：`new OutboundSender(larkChannel.getRawClient(), 我方持有的 LarkChannelOptions)`，然后自己驱动 `createCardInstance` → `sendCardByReference` → 循环 `updateCardElementContent(cardId, "stream_md", snapshot, ++seq)` → `finishStreamingCard`。sequence 由我们自己的每个 sink 实例维护（与 SDK 的 `MarkdownStreamControllerImpl` 同构，Q3 已证明这是正确做法）。代价：需要在 `:lark-gateway` 内持有 `LarkChannelOptions` 引用，并复制 SDK 的 `buildStreamingCard` JSON 结构（`schema 2.0` + `element_id "stream_md"` + `streaming_mode` + `summary`）。收益：完全掌控节流/终端态/错误上报，规避 C 点的静默失败。 | M3-T3 实现复杂度上升约 1 个类；但这是唯一能满足契约语义的路径 |
| **D-2** | 访问控制放在 SDK 还是我方 | SDK 的 `PolicyConfig` 只有 group allowlist + dm `open/disabled/allowlist`，**没有黑名单**；而旧实现是 `accessMode=blacklist`（S2 启动日志实证）。方案要求「现有能力一个不少」。 | **访问控制全部放在 `:lark-bridge` 的处理链里，SDK 侧 `PolicyConfig` 设为全放行**（`groupAllowlist = []`、`dmMode = "open"`、`requireMention = false`、`respondToMentionAll = true`）。理由：① 保住黑名单能力；② `PolicyGate` 的 reject 只发 `"reject"` 事件、不给我方处理链介入机会，无法做「拒绝但回一句提示」这类旧行为；③ 单一裁决点便于 M4-T7 的处理链测试。代价：SDK 的 `RejectEvent` 通道基本闲置。 | 影响 M4-T7 处理链与 M6 UI 的 accessMode 编辑器；不影响网关契约 |
| **D-3** | SDK 线程池泄漏的收口方式 | `ws.Client.executor` 是非 daemon `FixedThreadPool` 且永不 shutdown（F 点）⇒ `disconnect()` 后 JVM 不退出，反复连断泄漏线程。 | **不试图修复 SDK**（禁止改 SDK、禁止反射关它的 executor）。改为在**进程边界**上收口：桥接以独立子进程运行（M4-T6 的进程原语已经这么设计），停止 = 关 stdin/发停止信号 + 超时后 `destroyForcibly()`。同时把「disconnect 后线程数不回落」登记为 `StartStopLeakTest` 的已知豁免，断言改为「单次 connect/disconnect 循环内线程增长 ≤ 2*CPU+1 且第二次循环不再增长」。 | 影响 M4-T6/T9 与 M3-T4 的测试断言；不影响网关契约 |

### 其它 S3 附带事实（M3-T3 直接可用，无需裁决）

**`LarkChannel` 的完整公开面（`javap` 实证）**

```java
public synchronized CompletableFuture<BotIdentity> connect()
public BotIdentity connectSync()                       // = connect().join()
public CompletableFuture<Void> disconnect()
public <T> ChannelSubscription on(String eventName, ChannelEventHandler<T> handler)
public CompletableFuture<SendResult> send(String to, SendInput input, SendOptions opts)
public CompletableFuture<SendResult> stream(String to, StreamInput input, SendOptions opts)
public CompletableFuture<byte[]> downloadResource(String fileKey, String type)
public CompletableFuture<String> addReaction(String messageId, String emojiType)
public CompletableFuture<Boolean> removeReactionByEmoji(String messageId, String emojiType)
public void updatePolicy(LarkChannelOptions.PolicyConfig partial)
public Client getRawClient()
public com.lark.oapi.ws.Client getRawWsClient()
```

⇒ **`LarkChannel` 不是 `AutoCloseable`，没有 `close()`**。契约里的 `MessageGateway.close()` 只能映射到 `disconnect()`，并在其上补自己的资源收口（协程 scope cancel、channel close）。
⇒ 契约 12 成员里 `setReaction` 对应 SDK 的 `addReaction` / `removeReactionByEmoji` **两个**方法，需要在网关层合成一个（例如 `setReaction(messageId, emoji, add: Boolean)` 或按 emoji 为空表示移除）。这是**签名级不兼容**，需要 Leader 确认（并入 D-1 一并裁决，或按我建议的「网关层合成」处理）。
⇒ 事件名实测确认（来自 `ChannelClientFactory` 与 `ChannelInboundProcessor`）：`"message"` / `"reject"` / `"reconnecting"` / `"reconnected"` / `"cardAction"` / `"reaction"` / `"comment"` / `"botAdded"` / `"error"`。

```java
// ChannelClientFactory
createWebSocketClient → new ws.Client.Builder(appId, appSecret)
    .eventHandler(dispatcher).domain(...).source(...).clientAssertionProvider(...)
    .onReconnecting(() -> eventBus.emit("reconnecting", null))
    .onReconnected(() -> eventBus.emit("reconnected", null))
    .build()
```

⇒ 契约的 `connectionState` 可以直接由 `"reconnecting"` / `"reconnected"` + `connect()`/`disconnect()` 的 future 完成态推导，不需要额外轮询。

**`NormalizedMessage` 的 16 个 getter（全部 final 字段）**

```
messageId, chatId, chatType, senderId, senderName, content, rawContentType,
List<ResourceDescriptor> resources, List<MentionInfo> mentions,
boolean mentionAll, boolean mentionedBot, rootId, threadId, replyToMessageId,
long createTime, Object raw
```

⇒ 方案 D13 的会话 key `chatId + (threadId ?: rootId ?: "")` 所需字段**齐备**；`senderName` 已由 SDK 提供（`ChannelInboundProcessor` 用 `new NormalizeOptions(botIdentity, includeRaw, true /*stripBotMentions*/, subMessageFetcher, null /*senderNameResolver*/)` ⇒ **senderName 只从事件本身取，不额外发 HTTP**，符合「handler 3 秒铁律」）。`subMessageFetcher` 只在 `merge_forward` 合并转发时同步调 `client.im().message().get(...)` ⇒ 这是 SDK 内部**唯一**会在 handler 线程上发 HTTP 的路径，M5 真机验证要专门测一次合并转发消息。
⇒ `botIdentity` 为 null 时 `ChannelInboundProcessor` 抛 `NOT_CONNECTED` ⇒ 必须先 `connect()` 成功再注册 handler。
⇒ `stripBotMentions = true` 是硬编码 ⇒ SDK 已经把 `@机器人` 从 content 里剥掉了，我方处理链不要再剥一次（旧实现可能自己剥，M3-T1 的行为基线要标为「SDK 吸收」）。

**`SendInput` / `StreamInput` / `SendOptions` / `SendResult` 的完整形状**

```java
// SendInput.Kind
MARKDOWN, TEXT, POST, IMAGE, FILE, AUDIO, VIDEO, CARD, SHARE_CHAT, SHARE_USER, STICKER
// 静态工厂
SendInput.markdown(String) / text(String) / post(Object) / image(String|byte[]|Object)
         / file(source, fileName) / audio(source, duration) / video(source, duration, coverImageKey)
         / card(Map) / shareChat(...) / shareUser(...) / sticker(fileKey)

// StreamInput
static StreamInput markdown(MarkdownStreamProducer)
static StreamInput card(Map<String,Object> initial, CardStreamProducer)
enum Kind { MARKDOWN, CARD }
interface MarkdownStreamProducer { void produce(MarkdownStreamController) throws Exception; }
interface MarkdownStreamController { void append(String chunk); void setContent(String full); String getMessageId(); }
interface CardStreamProducer { void produce(CardStreamController) throws Exception; }
interface CardStreamController { void update(Map); Map getCurrent(); String getMessageId(); }

// SendOptions.Builder
replyTo(String) / replyInThread(Boolean) / mentions(List<String>) / mentionInfos(List<MentionInfo>)

// SendResult
String getMessageId();  List<String> getChunkIds();   // "Streaming chunk ids when the underlying cardkit API returns them"

// 其它
interface ChannelEventHandler<T> { void handle(T); }
interface ChannelSubscription { void unsubscribe(); }
class BotIdentity { BotIdentity(String,String); String getOpenId(); String getName(); }
class RejectEvent { RejectEvent(RejectReason,Object); RejectEvent(RejectReason,String,String,String,Object);
                    RejectReason getReason(); String getMessageId(); String getChatId(); String getSenderId(); Object getRaw(); }
final class LarkChannelErrorCode extends Enum {
    FORMAT_ERROR, TARGET_REVOKED, RATE_LIMITED, PERMISSION_DENIED, UPLOAD_FAILED,
    SSRF_BLOCKED, SEND_TIMEOUT, NOT_CONNECTED, UNKNOWN;  String getValue(); }
class LarkChannelException extends RuntimeException { String getCode(); String getContext(); }
class LarkChannelFactory { public static LarkChannel createLarkChannel(LarkChannelOptions options); }
final class ChannelLowLevelApi { /* 全部 package-private，不可用 */ }
```

**`OutboundConfig` 的完整默认值（含方案漏掉的旋钮）**

```java
public static class OutboundConfig {
    private int textChunkLimit = 3500;                    // ✓ 与方案一致
    private int streamThrottleMs = 100;                   // 方案要 250
    private int streamThrottleChars = 50;                 // ← 方案漏了这个旋钮（字符阈值也会触发 flush）
    private String streamInitialText = "Thinking...";
    private boolean ssrfGuardEnabled = true;
    private List<String> ssrfAllowlist = Collections.emptyList();
    private RetryConfig retry = new RetryConfig();
    private List<String> allowedFileDirs = Collections.emptyList(); }
```

⇒ `Throttle.note()` 的触发条件是 `pendingChars >= chars || elapsed >= ms`（**或**关系）⇒ 即使把 `streamThrottleMs` 设成 250，只要累计 50 字符就会 flush。**M3-T3 若要实现方案的「250ms 节流」，必须同时把 `streamThrottleChars` 调大**（例如 4000），否则节流形同虚设。

⇒ 另注（混合热更语义）：`OutboundSender` 构造器里 `this.chunkLimit = options.getOutbound().getTextChunkLimit()` 是**构造时快照，不可热更**；而 `getStreamThrottleMs()` / `getStreamThrottleChars()` / `getStreamInitialText()` 每次读 config ⇒ **可热更**。M3-T3 的 `updatePolicy` 若暴露这些旋钮，必须在文档里区分「立即生效」与「需重连生效」。这也是方案「已否决：把 SDK 调优旋钮暴露进用户配置文档」的一个额外支撑理由。

**`ssrfGuardEnabled = true` 默认开启**：`allowedFileDirs` 默认为空。M4-T8 的 `files` handler（下载资源落地）必须显式配置 `allowedFileDirs`，否则 SSRF 防护可能拦掉本地路径。这是方案未覆盖的点，M5 真机验证要测文件消息。

**`RegisterApp` 存在，但回调签名与方案不符**

```
com.lark.oapi.scene.registration.RegisterApp.register(RegisterAppOptions) → RegisterAppResult
RegisterAppResult: String getClientId(), String getClientSecret(), UserInfo
QRCodeInfo: String getUrl(), int getExpireIn()          ← 不是方案写的 (deviceCode, verifyUrl)
RegisterAppException: String getCode(), String getDescription()
ExpiredException
AppAddonsEncoder
// 参数校验原文
if (opts.getOnQRCode() == null) throw new RegisterAppException(ERROR_INVALID_ARGUMENT, "onQRCode is required");
```

⇒ 契约里的 `registerApp` 成员可用，但**回调模型是 `onQRCode(QRCodeInfo{url, expireIn})`**，M3-T3 与 M5 的 CLI 子命令（扫码建应用）要按这个签名写，不能按方案里的 `deviceCode/verifyUrl`。

**shaded 情况（决定「禁止 relocate」的必要性）**

`okhttp` / `okio` / `protobuf` 已被 shade 进 `com.lark.oapi.*` 命名空间（源码里 `import com.lark.oapi.google.protobuf.ByteString`、`com.lark.oapi.okio.ByteString`），另有独立 artifact `larksuite-oapi-shaded-protobuf:2.4.6`。
**Gson 没有被 shade**（`import com.google.gson.Gson` 原样）⇒ `Jsons.DEFAULT` 是 `public static final Gson`，SDK 大量用反射 + Gson TypeAdapter 处理 `com.lark.oapi.service.*.model.*` ⇒ **relocate Gson 会直接打断 SDK 的反射链**。方案「禁止 relocate Gson/protobuf」的硬约束**成立且必须遵守**。S5 的 `sdkInternalGsonSingletonWorks` 测试已实测 `Jsons.DEFAULT` 可用。

---

## S5 · Gson 与 slf4j 共存裁决

### 验证方法（实际命令）

```bash
# 1) Gradle 侧：查 composeApp 的最终解析结果（KMP jvm target 的 configuration 名是 jvmRuntimeClasspath）
./gradlew :composeApp:dependencies --configuration jvmRuntimeClasspath --console=plain
./gradlew :lark-gateway:dependencies --configuration testRuntimeClasspath --console=plain

# 2) 产物侧：查 .deb 里实际打进去了哪个版本
dpkg-deb -c .../easydevtoolbox_1.0.0_amd64.deb | grep -iE "slf4j|gson|logback|protobuf|guava|httpclient"

# 3) 运行时侧（最高保真）：直接在真实 jlink runtime 里启动打包产物
timeout 30 ./bin/EasyDevToolbox

# 4) 断言侧：临时在 :lark-gateway 写一个 S5CoexistenceSpikeTest（跑完即删）
./gradlew :lark-gateway:test --console=plain
```

临时测试的复现组合与 `composeApp` 完全一致（`slf4j-api 2.0.17` + `logback-classic 1.5.18` + `gson 2.9.0` + `kotlinx-serialization-json 1.9.0`），三个用例：
① Gson 与 kotlinx.serialization 各序列化/反序列化一次互不干扰；② SDK 内部 `Jsons.DEFAULT` 单例可用；③ `LoggerFactory.getLogger("x")` 绑定到 logback 而非 NOP。

### 实测输出关键片段

**Gradle 冲突解析（`:composeApp` 的 `jvmRuntimeClasspath`）—— S5 的核心答案：**

```
+--- com.larksuite.oapi:oapi-sdk:2.8.5
|    +--- commons-codec:commons-codec:1.15
|    +--- com.google.code.gson:gson:2.9.0
|    +--- org.slf4j:slf4j-api:1.7.30 -> 2.0.17          ← 升级，不是降级
|    +--- org.apache.httpcomponents:httpclient:4.5.13
|    |    +--- org.apache.httpcomponents:httpcore:4.4.13
|    |    +--- commons-logging:commons-logging:1.2
|    |    \--- commons-codec:commons-codec:1.11 -> 1.15
|    +--- org.apache.httpcomponents:httpmime:4.5.13
|    +--- com.larksuite.oapi:larksuite-oapi-shaded-protobuf:2.4.6
|    \--- com.google.guava:guava:32.0.0-jre
+--- ch.qos.logback:logback-classic:1.5.18
|    +--- ch.qos.logback:logback-core:1.5.18
|    \--- org.slf4j:slf4j-api:2.0.17                    ← 版本来源
+--- org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0
|    \--- org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.9.0
```

⇒ **`slf4j-api` 最终解析版本 = `2.0.17`**（logback-classic 1.5.18 带入），SDK 声明的 `1.7.30` 被 Gradle 默认「取最高版本」策略抬升。**这正是 logback 1.5.x 需要的 2.0.x**，R13 风险自然消解，**无需任何 `resolutionStrategy` / `force` / 排除**。

`.deb` 产物侧交叉验证：

```
slf4j-api-2.0.17-b648d114a23683498ac3f746f959d2f.jar       ← 只有一个 slf4j-api，版本 2.0.17
logback-classic-1.5.18 / logback-core-1.5.18
gson-2.9.0                                                 ← 只有一个 gson，无版本冲突
kotlinx-serialization-core-jvm-1.9.0 / kotlinx-serialization-json-jvm-1.9.0
larksuite-oapi-shaded-protobuf-2.4.6                        ← protobuf 已 shaded 到 com.lark.oapi.google.protobuf
datastore-preferences-external-protobuf-1.3.0-alpha10       ← 另一套 protobuf，同样已 shaded，与 SDK 不冲突
guava-32.0.0-jre / httpclient-4.5.13 / httpcore-4.4.13 / httpmime-4.5.13
```

⇒ **无重复 jar、无同名不同版本共存**。

运行时侧（真实 jlink runtime，来自 S2 的启动日志）：

```
kotlin-logging: initializing... active logger factory: Slf4jLoggerFactory
22:29:51.737 [DefaultDispatcher-worker-1] INFO  c.j.e.core.log.JLogInitializer - LogbackInitializer: init: 日志初始化完成
```

⇒ 无 "No SLF4J providers were found"，logback provider 通过 ServiceLoader 正常绑定。

断言侧（临时测试 `S5CoexistenceSpikeTest`，`./gradlew :lark-gateway:test`）：

```xml
<testsuite name="com.jiaoay.lark.gateway.S5CoexistenceSpikeTest" tests="3" skipped="0" failures="0" errors="0" time="0.147">
  <testcase name="gsonAndKotlinxSerializationCoexist" time="0.089"/>
  <testcase name="sdkInternalGsonSingletonWorks"      time="0.004"/>
  <testcase name="slf4jBindsRealProviderNotNop"       time="0.047"/>
  <system-out><![CDATA[
S5 SDK Gson 来源 jar: file:.../com.google.code.gson/gson/2.9.0/.../gson-2.9.0.jar
22:35:55.090 [Test worker] INFO s5-probe -- S5 slf4j ILoggerFactory = ch.qos.logback.classic.LoggerContext
S5 slf4j-api 来源 jar: file:.../org.slf4j/slf4j-api/2.0.17/.../slf4j-api-2.0.17.jar
S5 ILoggerFactory 来源 jar: file:.../ch.qos.logback/logback-classic/1.5.18/.../logback-classic-1.5.18.jar
]]></system-out>
BUILD SUCCESSFUL / EXIT=0
```

⇒ 三条断言全部成立：
- `Gson().toJson(...)` 与 `Json.encodeToString(String.serializer(), ...)` / `Json.encodeToString(ListSerializer(String.serializer()), ...)` 在同一 JVM 内各自正确，互不干扰；`Gson().fromJson(..., Map::class.java)` 反序列化也正常。
- `com.lark.oapi.core.utils.Jsons.DEFAULT`（`public static final Gson`）可用，加载自 `gson-2.9.0.jar` 原始坐标 ⇒ 印证「禁止 relocate Gson」。
- `LoggerFactory.getLogger("s5-probe").javaClass.name` 以 `ch.qos.logback` 开头，`getILoggerFactory()` = `ch.qos.logback.classic.LoggerContext`，加载自 `logback-classic-1.5.18.jar`；`slf4j-api` 加载自 `2.0.17`。**无 NOP 退化、无 `NoSuchMethodError`**。

**临时验证的处置**：测试文件 `lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/S5CoexistenceSpikeTest.kt` 与其依赖的 `testImplementation(libs.logback.classic)` / `testImplementation(libs.kotlinx.serializationJson)` **已按约定删除**（「跑完即删」）。`:lark-gateway` 最终形态不依赖 logback 与 kotlinx-serialization（日志与配置序列化属 `:lark-bridge` / `:core` 的职责，SDK 模块保持最小依赖面）。

### 判定：**PASS**

- `slf4j-api` 最终解析 **2.0.17**（≥ 2.0.x，满足 logback 1.5.18 要求），且是**升级**而非降级；
- 无版本冲突降级、无重复 jar；
- Gson 2.9.0 与 kotlinx-serialization-json 1.9.0 共存无干扰；
- **未出现 `NoSuchMethodError`** ⇒ 方案里「出现 NoSuchMethodError 就报告 Leader、不要自己钉版本」的应对路径**未触发**，无需钉任何版本。

### 对后续里程碑的影响与既定应对

1. **M3-T2 不需要为 slf4j 加任何 `resolutionStrategy`**。若未来 logback 降到 1.3.x（需 slf4j 2.0.x）或 SDK 升到声明 slf4j 2.x 的版本，同样不需要动作。
2. **M7 文档要写的三个数字**：`oapi-sdk 2.8.5` / `slf4j-api 2.0.17`（解析结果，非 SDK 声明的 1.7.30）/ jlink 模块清单 9 项（见 S2）。
3. **`:lark-gateway` 不要引入 logback**：SDK 模块只依赖 `slf4j-api`（传递而来），日志实现由宿主（`:composeApp`）与 CLI（新 `:lark-bridge` 的 shadowJar）各自提供。这是 S7 隐患的边界条件。
4. **⚠️ 遗留风险 R-S5-1**：`commons-logging 1.2` 随 httpclient 进入 classpath。它是死代码路径（见 S2/S3-G），但如果 M3-T2 之后有人给 SDK 注入 `ApacheHttpClientTransport`，commons-logging 会与 slf4j 形成两套日志门面。**应对**：M3-T4 的 `LarkSdkIsolationTest` 追加一条断言 —— 禁止在 `:lark-gateway` 之外出现 `org.apache.commons.logging` 与 `com.lark.oapi.core.httpclient.ApacheHttpClientTransport` 的引用。

---

## S7 · shadowJar 的 slf4j ServiceLoader（只取证，不修）

### 验证方法（实际命令）

方案原本要求用 `:log-tools` 取证。实测发现 **`:log-tools` 根本不依赖 slf4j/logback**（其 `build.gradle.kts` 只有 `bouncycastle` / `zstd-jni` / `clikt`），所以 `log-tools-all.jar` 里 0 个 slf4j 类，无法复现该症状。为取得**决定性证据**，改在 `:lark-gateway`（本次允许新建的模块，同样 apply 了 `easydevtoolbox.jvm.cli` convention，因此走的是同一段 `exclude("META-INF/")` 代码）上做一次性探针：临时加 `implementation(libs.logback.classic)` + 一个 `main()` 探针 + 临时 `shadowJar` Main-Class，跑完立即全部还原。

```bash
# 1) 方案原定路径（仍然执行，用于取基线）
./gradlew :log-tools:shadowJar --console=plain
unzip -l log-tools/build/libs/log-tools-all.jar | grep -i "META-INF/services"
java -jar log-tools/build/libs/log-tools-all.jar

# 2) 决定性探针（:lark-gateway 临时带 logback）
./gradlew :lark-gateway:shadowJar --console=plain
unzip -l lark-gateway/build/libs/lark-gateway-all.jar | grep -i "META-INF"
unzip -l lark-gateway/build/libs/lark-gateway-all.jar | grep -cE "ch/qos/logback/|org/slf4j/"
java -jar lark-gateway/build/libs/lark-gateway-all.jar

# 3) 反证：源 jar 里确实有那个 services 声明（证明是「被剥掉」而非「本来没有」）
unzip -l ~/.gradle/caches/.../logback-classic-1.5.18.jar | grep -i services
unzip -p ~/.gradle/caches/.../logback-classic-1.5.18.jar META-INF/services/org.slf4j.spi.SLF4JServiceProvider

# 4) 附带取证：还有哪些依赖的 services 声明被同一条 exclude 干掉
for j in mordant-jvm-{jna,ffm,graal-ffi}-jvm.jar bcprov-jdk18on-1.85.2.jar; do unzip -l "$j" | grep "META-INF/services"; done
```

### 实测输出关键片段

**convention 插件的原文（`JvmCliConventionPlugin.kt`，只读取证，未修改）：**

```kotlin
tasks.matching { it.name == "shadowJar" }.configureEach {
    (this as? com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar)?.exclude("META-INF/")
}
```

⇒ `exclude("META-INF/")` 排除的是**整个目录**，不是只排签名文件（`META-INF/*.SF` / `*.DSA` / `*.RSA`）。**R12 隐患在源码层成立。**

**方案原定路径 `:log-tools` 的基线（取证结论：该模块不受影响，因为它没有 slf4j）：**

```
$ unzip -l log-tools/build/libs/log-tools-all.jar | grep -i "META-INF"
       81  1980-02-01 00:00   META-INF/MANIFEST.MF
        0  1980-02-01 00:00   META-INF/
(services 命中数: 0)

$ unzip -l log-tools/build/libs/log-tools-all.jar | grep -cE "org/slf4j/|ch/qos/logback/"
0                                  ← log-tools 完全不依赖 slf4j/logback

$ unzip -p log-tools/build/libs/log-tools-all.jar META-INF/MANIFEST.MF
Manifest-Version: 1.0
Main-Class: com.jiaoay.log.MainKt
Multi-Release: true

$ java -jar log-tools/build/libs/log-tools-all.jar
Usage: log-utils [<options>]

Error: missing option --file
                                   ← 无 "no slf4j providers"，因为该 jar 里没有 slf4j
```

**决定性探针 `:lark-gateway`（临时带 logback，走同一段 convention 代码）：**

```
$ ./gradlew :lark-gateway:shadowJar
BUILD SUCCESSFUL in 6s
$ ls -la lark-gateway/build/libs/
-rw-rw-r-- 32885722  lark-gateway-all.jar
-rw-rw-r--      456  lark-gateway.jar

$ unzip -l lark-gateway/build/libs/lark-gateway-all.jar | grep -i "META-INF"
       98  1980-02-01 00:00   META-INF/MANIFEST.MF
        0  1980-02-01 00:00   META-INF/
                                   ← 只有 MANIFEST.MF，services 目录整体消失

$ unzip -l lark-gateway/build/libs/lark-gateway-all.jar | grep -cE "ch/qos/logback/|org/slf4j/"
798                                ← logback 与 slf4j 的 class 文件确实打进来了（798 个条目）

$ java -jar lark-gateway/build/libs/lark-gateway-all.jar
SLF4J(W): No SLF4J providers were found.
SLF4J(W): Defaulting to no-operation (NOP) logger implementation
SLF4J(W): See https://www.slf4j.org/codes.html#noProviders for further details
S7 logger impl = org.slf4j.helpers.NOPLogger
S7 ILoggerFactory = org.slf4j.helpers.NOPLoggerFactory
                                   ← 症状完全复现：类在、声明没了、日志静默失效
```

**反证（源 jar 里确实有该声明，所以是「被剥掉」）：**

```
$ unzip -l .../logback-classic-1.5.18.jar | grep -i services
        0  2025-03-18 12:42   META-INF/services/
       67  2025-03-18 12:42   META-INF/services/jakarta.servlet.ServletContainerInitializer
       49  2025-03-18 12:42   META-INF/services/org.slf4j.spi.SLF4JServiceProvider
$ unzip -p .../logback-classic-1.5.18.jar META-INF/services/org.slf4j.spi.SLF4JServiceProvider
ch.qos.logback.classic.spi.LogbackServiceProvider
```

**附带取证 —— 同一条 `exclude` 今天已经在 `:log-tools` 上造成的既有损伤：**

```
mordant-jvm-jna-jvm.jar       → META-INF/services/com.github.ajalt.mordant.terminal.TerminalInterfaceProvider  (85 B)
mordant-jvm-ffm-jvm.jar       → META-INF/services/com.github.ajalt.mordant.terminal.TerminalInterfaceProvider  (85 B)
mordant-jvm-graal-ffi-jvm.jar → META-INF/services/com.github.ajalt.mordant.terminal.TerminalInterfaceProvider (101 B)
bcprov-jdk18on-1.85.2.jar     → META-INF/services/java.security.Provider                                       (112 B)

$ unzip -l log-tools/build/libs/log-tools-all.jar | grep -c "META-INF/services"
0                                    ← 全部被剥掉
```

⇒ **BouncyCastle 的 JCE Provider 自注册（`META-INF/services/java.security.Provider`）今天在 `log-tools-all.jar` 里已经失效**。这不是新风险，是**既有缺陷**：`:log-tools` 若依赖 ServiceLoader 自动注册 BC provider，在 fat jar 下会拿不到；只有在代码里显式 `Security.addProvider(BouncyCastleProvider())` 才不受影响。M3-T2 修完 `exclude` 后应顺带回归验证这一点。
⇒ mordant 的终端能力探测同理（`:log-tools` 依赖 clikt→mordant，其 jna/ffm/graal-ffi provider 的 services 声明会被剥掉；clikt 的 `--help` 着色/TTY 探测可能退化）。

### 判定：**FAIL（隐患成立）** —— 按要求**只取证，未修**

- 源码层：`JvmCliConventionPlugin.kt` 的 `exclude("META-INF/")` 排除整个 `META-INF/` 目录；
- 产物层：shadowJar 输出里 `META-INF/services/` 完全消失，而 logback/slf4j 的 798 个 class 条目都在；
- 运行层：`java -jar` 实测输出 `SLF4J(W): No SLF4J providers were found.` + `NOPLogger` / `NOPLoggerFactory`；
- 反证：源 jar 里 `META-INF/services/org.slf4j.spi.SLF4JServiceProvider = ch.qos.logback.classic.spi.LogbackServiceProvider` 确实存在。

⇒ **R12 完全成立**。方案对 M3-T2 的要求（收窄 `exclude` 为只排签名文件 + 追加 `mergeServiceFiles()`）是必需的，**不是可选优化**。

**本次未做任何修复**：`build-logic/src/main/kotlin/JvmCliConventionPlugin.kt` 保持原样，`git diff build-logic/` 为空。

### 对后续里程碑的影响与既定应对（M3-T2 的施工图）

1. **必须改 `JvmCliConventionPlugin.kt:36`**，把

   ```kotlin
   ?.exclude("META-INF/")
   ```

   收窄为「只排签名文件」并追加 service 合并（示意，具体由 M3-T2 落地并回归）：

   ```kotlin
   ?.exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")   // 只排签名，避免 BC 的 "Invalid signature file digest"
   ?.mergeServiceFiles()                                                              // 合并 META-INF/services/*
   ```

   注意 `exclude("META-INF/")` 当前**兼任**「剥掉 BouncyCastle 签名文件」的职责（`bcprov-jdk18on` 是签名 jar，不剥签名则 fat jar 会以 `SecurityException: Invalid signature file digest for Manifest main attributes` 启动失败）。**收窄时必须保留对 `*.SF/*.DSA/*.RSA` 的排除**，否则会从「日志静默」退化成「jar 完全无法启动」。这是 M3-T2 最容易踩的反向坑，必须写进 T2 的验收标准。

2. **M3-T2 的回归验证清单（方案已要求，此处补齐实测基线）**：
   - `./gradlew :log-tools:shadowJar` 成功；
   - `unzip -l log-tools/build/libs/log-tools-all.jar | grep "META-INF/services"` **必须有命中**（至少 `java.security.Provider`）；
   - `java -jar log-tools/build/libs/log-tools-all.jar`（无参）输出仍是 `Usage: log-utils [<options>]` + `Error: missing option --file`（**这是本次实测取得的基线原文**），且不出现 `SecurityException`；
   - `java -jar log-tools/build/libs/log-tools-all.jar --type xlog -f <真实日志>` 仍可运行（方案要求）；
   - 新 `:lark-bridge` 的 shadowJar 里必须能 `unzip -p ... META-INF/services/org.slf4j.spi.SLF4JServiceProvider` 读到 `ch.qos.logback.classic.spi.LogbackServiceProvider`，且 `java -jar` 启动时**无** "No SLF4J providers"。

3. **`:lark-gateway` 最终形态不声明 shadowJar Main-Class、不产出可执行 fat jar**（它只作为库被 `api` 依赖），所以 S7 的修复对 `:lark-gateway` 本身没有直接影响；受影响的是**新 `:lark-bridge` 的 CLI fat jar** 与 `:log-tools`。

4. **探针已全部还原**：`S7Slf4jProbe.kt`、临时 `implementation(libs.logback.classic)`、临时 `tasks.shadowJar { manifest { ... } }` 均已删除，`lark-gateway/build.gradle.kts` 回到 S1 的目标形态。

---

## 收尾验证（移除全部临时改动后的全量回归）

临时改动全部还原后重跑，证据如下。

```bash
$ git status --porcelain
 M gradle/libs.versions.toml
 M settings.gradle.kts
?? lark-gateway/
$ git diff --stat composeApp/build.gradle.kts        # 输出为空 ⇒ 临时依赖已彻底移除
$ git diff --stat
 gradle/libs.versions.toml | 2 ++
 settings.gradle.kts       | 1 +
 2 files changed, 3 insertions(+)
$ grep -rn "SPIKE TEMPORARY\|spike-tmp" --include="*.kts" --include="*.kt" . | grep -v "^./openspec"
                                                  # 输出为空 ⇒ 源码树里零临时痕迹
$ git log --oneline -1
bd03d58 update                                    # HEAD 未变，全程零 git 写操作
```

全量构建：

```
$ ./gradlew build --console=plain
> Task :lark-bridge:build
> Task :lark-gateway:shadowJar
> Task :lark-gateway:assemble
> Task :lark-gateway:build
> Task :feature-shelltools:jvmTest
> Task :feature-shelltools:allTests
> Task :feature-shelltools:check
> Task :feature-shelltools:build
BUILD SUCCESSFUL in 24s
133 actionable tasks: 59 executed, 1 from cache, 73 up-to-date
EXIT=0
```

各模块测试实测计数（读 `build/test-results/**/TEST-*.xml` 的 `tests`/`failures`/`errors` 属性汇总）：

| 模块 | tests | failures | errors |
|---|---|---|---|
| `:composeApp`（含 `UiArchitectureGuardTest`） | 15 | 0 | 0 |
| `:core` | 28 | 0 | 0 |
| `:feature-larkbridge` | 67 | 0 | 0 |
| `:feature-shelltools` | 89 | 0 | 0 |
| `:feature-timestamp` | 13 | 0 | 0 |
| `:lark-bridge` | 483 | 0 | 0 |
| `:log-tools` | 87 | 0 | 0 |
| **合计** | **782** | **0** | **0** |

`UiArchitectureGuardTest` 确认在本次构建中真实执行（非 UP-TO-DATE 复用）：

```
$ head -3 composeApp/build/test-results/jvmTest/TEST-com.jiaoay.easy_dev_toolbox.arch.UiArchitectureGuardTest.xml
tests="11" skipped="0" failures="0" errors="0" timestamp="2026-09-06T14:45:25.032Z"
                                            ↑ UTC 14:45:25 = 本地 22:45:25，与本次 build 同刻
```

⇒ 三条 `larkbridge` 值级 dp 白名单条目与 `:400` 防腐化断言在本次 spike 中**未被触碰**（M2 才处理），守护测试仍全绿。

宿主可启动性（里程碑收口要求）：

```
$ timeout 90 ./gradlew :composeApp:run --console=plain
> Task :composeApp:run
d: AppInitializerManager: startInit:
d: LogbackInitializer: init: start
kotlin-logging: initializing... active logger factory: Slf4jLoggerFactory
22:46:22.028 [DefaultDispatcher-worker-1] INFO  c.j.e.core.log.JLogInitializer - LogbackInitializer: init: 日志初始化完成
22:46:22.730 [AWT-EventQueue-0] DEBUG c.j.e.ComposableSingletons$Main - main: after startInit
22:46:23.770 [DefaultDispatcher-worker-3] INFO  c.j.e.c.l.LarkBridgeManager - LarkBridgeManager: create LarkBridge: transport=openapi ...
EXIT=124                     ← 124 = 90 秒到时进程仍存活 = 启动成功且窗口未崩
```

⇒ `./gradlew build` 全绿 **且** `./gradlew :composeApp:run` 可启动，M1 里程碑收口条件满足。

---

## 附录 A · M7 文档要抄的三个数字（`docs/pages/lark-bridge.md` 的「SDK 边界实测」小节）

| 项 | 实测值 | 取证位置 |
|---|---|---|
| **SDK 版本** | `com.larksuite.oapi:oapi-sdk:2.8.5`（Maven Central 当前最新版；字节码 Java 8） | S1 |
| **jlink 实际模块清单（9 项）** | `java.base`, `java.datatransfer`, `java.desktop`, `java.logging`, `java.net.http`, `java.prefs`, `java.xml`, `jdk.crypto.ec`, `jdk.unsupported` | S2 · `lib/runtime/legal/` |
| **slf4j 实际解析版本** | `2.0.17`（SDK 声明 `1.7.30`，被 logback-classic 1.5.18 抬升；**无需任何 resolutionStrategy**） | S5 · `jvmRuntimeClasspath` 依赖树 `1.7.30 -> 2.0.17` |

补充可抄项：

| 项 | 实测值 |
|---|---|
| SDK 传递依赖全清单 | `commons-codec 1.15`, `gson 2.9.0`, `slf4j-api 1.7.30→2.0.17`, `httpclient 4.5.13`, `httpcore 4.4.13`, `httpmime 4.5.13`, `commons-logging 1.2`, `larksuite-oapi-shaded-protobuf 2.4.6`, `guava 32.0.0-jre`（+ `failureaccess 1.0.1`, `listenablefuture 9999.0`, `jsr305 3.0.2`, `checker-qual 3.33.0`, `error_prone_annotations 2.18.0`, `j2objc-annotations 2.8`） |
| shaded 情况 | okhttp / okio / protobuf 已 shade 进 `com.lark.oapi.*`；**Gson 未 shade**（禁止 relocate 的根因） |
| `.deb` 体积增量 | SDK 主 jar 21.0 MB + guava 3.0 MB + shaded-protobuf 1.9 MB + httpclient 0.78 MB + gson 0.25 MB ≈ **+27 MB** |
| 默认 HTTP 传输 | shaded OkHttp（`OkHttpTransport`）；`ApacheHttpClientTransport` 全树无实例化点 |
| WS 握手超时 | 15000 ms（`LarkChannel.awaitWebSocketReady`） |
| WS 重连策略 | `reconnectNonce=30`, `reconnectCount=-1`（无限）, `reconnectInterval=120s`, `pingInterval=120s` |
| 去重默认值 | `dedupTtlMs=12h`, `dedupMaxEntries=5000`, `dedupSweepMs=5min`, `staleMessageWindowMs=30min`, `processingLockTtlMs=5min`, `dedupNamespace="channel:seen"` |
| 出站默认值 | `textChunkLimit=3500`, `streamThrottleMs=100`, `streamThrottleChars=50`, `streamInitialText="Thinking..."`, `ssrfGuardEnabled=true`, `retry{maxAttempts=3, baseDelayMs=500}` |
| **必须覆写的默认值** | `safety.chatQueueEnabled = false`（默认 `true` 会合并消息、丢失 messageId，见 S3-E） |

> 🔴 **真机实测更正（2026-09-08，T11 三轮安全子集）**：上表「WS 重连策略」行的 `pingInterval=120s` 是 **SDK 官方标称默认值**，但**真机实测 ping/pong 间隔恰为 90 秒**（三轮日志一致：`16:57:14 → 16:58:44 → 17:00:14`）。`lark-gateway/Tuning.kt`（115 行，§7.4 连接域）与 `:lark-bridge` **均未声明也未接线**任何 ping/心跳间隔常量 ⇒ 90s 是 SDK `ws.Client` 内部行为，**我方无可调点，不存在死的调优旋钮**。引用该行的 `pingInterval` 时请以 **90s** 为准。（同行的 `reconnectInterval=120s` 与 `reconnectCount=-1` 未真机验，保持标称值。）

---

## 附录 B · 文件变更清单（收尾时的精确状态）

### 新增（保留）

| 路径 | 说明 |
|---|---|
| `lark-gateway/build.gradle.kts` | S1 产物：`alias(libs.plugins.jetbrainsKotlinJvm)` + `id("easydevtoolbox.jvm.cli")` + `implementation(libs.oapi.sdk)` + `implementation(libs.kotlinx.coroutinesCore)` + `testImplementation(libs.kotlinx.coroutinesTest)`；不声明 shadowJar Main-Class；文件头含「全仓唯一 SDK 依赖点；禁止 relocate Gson/protobuf」注释 |
| `lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/package-info.kt` | S1 最小占位源文件，仅注释 + `package` 声明，**不含任何契约代码** |
| `openspec/changes/rewrite-lark-bridge/spike-findings.md` | 本报告（注：🔴 **`.gitignore` 本次已改为 `openspec/changes/*` 加上 `!openspec/changes/rewrite-lark-bridge/` 的显式放行 ⇒ 本报告及本 change 目录下的 5 份文档会随提交进入 git 仓库**。实测佐证：`git check-ignore -v` 对本文件**无输出**（未被忽略）、`git status --porcelain` 中状态为 `??`（未跟踪但可入库）、`git ls-files --error-unmatch` 报「未匹配任何 git 已知文件」。🔴 **因此敏感信息纪律适用于本报告**：任何粘贴进来的日志/输出都必须先掩码 appId（`cli_****` 形态）、appSecret、openId（`ou_****`）、chatId（`oc_****`）、tenant_key、message_id、锁文件哈希。**本报告 `:209` 那条粘贴的旧日志曾泄漏过一次真实明文 appId，已于 2026-09-08 掩码修正为 `cli_****1bc4` —— 记此以为后来者警示。**） |

### 修改（保留）

| 路径 | 变更 |
|---|---|
| `gradle/libs.versions.toml` | +2 行：`[versions]` 末尾 `oapi-sdk = "2.8.5"`；`[libraries]` 末尾 `oapi-sdk = { module = "com.larksuite.oapi:oapi-sdk", version.ref = "oapi-sdk" }` |
| `settings.gradle.kts` | +1 行：末尾 `include(":lark-gateway")`。**`:lark-bridge` 与 `:feature-larkbridge` 两行原样未动** |

### 临时修改（已还原，`git diff` 为空）

| 路径 | 临时内容 | 还原状态 |
|---|---|---|
| `composeApp/build.gradle.kts` | S2 期间新增 `implementation(project(":lark-gateway"))`（带 `[S2 SPIKE TEMPORARY]` 注释） | ✅ 已移除，`git diff --stat composeApp/build.gradle.kts` 输出为空 |
| `lark-gateway/build.gradle.kts` | S5 期间新增 `testImplementation(libs.logback.classic)` + `testImplementation(libs.kotlinx.serializationJson)`；S7 期间把 logback 提到 `implementation` 并临时加 `tasks.shadowJar { manifest { Main-Class = ... } }` | ✅ 已全部移除（serializationJson 与 logback 两条、shadowJar 块） |

### 临时新增（已删除）

| 路径 | 用途 | 状态 |
|---|---|---|
| `lark-gateway/src/test/kotlin/com/jiaoay/lark/gateway/S5CoexistenceSpikeTest.kt` | S5 的共存断言（3 个用例，全绿） | ✅ 已删除 |
| `lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/S7Slf4jProbe.kt` | S7 的 NOP 复现探针 | ✅ 已删除 |
| `build/spike-tmp/`（`oapi-sdk-2.8.5-sources.jar` 22.6 MB、解包源码树 `src/`、`deb-x/` 解包的 .deb、各阶段日志） | S2/S3/S5/S7 的取证工作区。`.gitignore` 含 `**/build/`，零 git 污染 | ✅ 已删除 |

### 删除

无。**未删除、未修改任何现有模块的源码**（`:lark-bridge` / `:feature-larkbridge` / `:core` / `:ui` / `:composeApp` / `:log-tools` / `build-logic` 的 kotlin 源码一律未动）。

### 未执行的 git 写操作

全程零 git 写操作：无 `commit` / `tag` / `push` / `reset` / `checkout` / `stash`。收尾时 `git log --oneline -1` 仍为 `bd03d58 update`，分支仍为 `main`。

---

## 附录 C · 未覆盖项与移交给后续里程碑的清单

| 项 | 归属 | 说明 |
|---|---|---|
| S4 凭据长连接连通 | 独立任务 | 需用户提供飞书 appId/appSecret（凭据不入库），并在飞书后台配合（注意方案指出的第 3/4 步顺序陷阱：后台保存长连接订阅方式时，本地必须已有一条 SDK 长连接处于已连接状态） |
| S6 卡片流式限制 | 独立任务 | 需真机凭据（不入库）；重点验证 C 点的静默失败（构造 sequence 冲突）与 `200810` 流式期间无法响应卡片回传 |
| R-S2-1 打包产物的 SDK 网络路径 | **M5 / T11** | 必须用打好的 `.deb`/`.msi`/`.dmg` 实际收发一次消息，确认裁剪后的 jlink runtime 不缺模块（本次启动验证时 `:lark-gateway` 是空壳，未加载 SDK 类） |
| R-S2-2 logback JMX | M4 / M7 | 禁止在桥接的 logback 配置里引入 `<jmxConfigurator/>`，否则需补 `modules("java.management")` |
| R-S5-1 commons-logging 双门面 | **M3-T4** | `LarkSdkIsolationTest` 追加断言：禁止 `:lark-gateway` 之外引用 `org.apache.commons.logging` 与 `ApacheHttpClientTransport` |
| R12 shadowJar services 剥离 | **M3-T2** | 按 S7 的施工图修 `JvmCliConventionPlugin.kt:36`；**必须同时保留对 `*.SF/*.DSA/*.RSA` 的排除**，否则 BouncyCastle 签名会让 fat jar 完全无法启动 |
| BC provider 自注册已失效（既有缺陷） | M3-T2 顺带回归 | `log-tools-all.jar` 里 `META-INF/services/java.security.Provider` 今天就被剥掉了；修完 T2 后应确认恢复 |
| D-1 / D-2 / D-3 三项架构裁决 | **Leader** | 见「S3 · 待 Leader 裁决的架构决策点」。D-1（`openCardStream` 自建 `OutboundSender`）、D-2（访问控制放我方、SDK 侧全放行）、D-3（SDK 线程池泄漏在进程边界收口）。三项均已给出建议方案与代价分析，**未自行改动契约** |
| 契约 `setReaction` 的签名级不兼容 | **Leader**（并入 D-1） | SDK 是 `addReaction` + `removeReactionByEmoji` 两个方法，契约是一个 `setReaction`，需确认合成方式 |
| `modules("java.net.http")` 注释理由已失效 | M3-T2 / M7 | 新实现走 SDK 的 shaded OkHttp，不再依赖 JDK HttpClient；注释理由需改写，或经同意后移除并重新验证打包 |
| `:lark-gateway` 每次 build 都产出 31.9 MB fat jar | M3-T2（可选优化） | convention 插件给所有 `jvm.cli` 模块都 apply 了 shadow，`:lark-gateway` 作为**纯库**（无 Main-Class、无独立 CLI）其实不需要 shadowJar。实测 `lark-gateway-all.jar` = 31,938,337 B，而 `lark-gateway.jar` 仅 456 B。建议 M3-T2 在 `:lark-gateway/build.gradle.kts` 里加 `tasks.shadowJar { enabled = false }`（或在 convention 层把 shadow 改为按需 opt-in），可省下每次构建约 32 MB 的写盘与打包时间。**本次未做**（超出 S1 的允许改动范围，且不影响任何判定） |

## 附录 D · 🔴 真机复验更正脚注（2026-09-08，T11 第五轮）：image/file `content` 无 `resources` 包装层

> **本节为纯追加更正脚注，不删改上方任何历史发现记录正文**（§8「`NormalizedMessage` 的 16 个 getter」`:852` 关于 `List<ResourceDescriptor> resources` 的记载原样保留）。

**真机实测（2026-09-08，T11 第五轮，13 条真实 `im.message.receive_v1` 事件）**：schema 2.0 事件里，
- **image** 消息的 SDK payload `content` 实测为 `{"image_key":"img_v3_..."}` —— **仅一个 key**；
- **file** 消息的 `content` 实测为 `{"file_key":"file_v3_...","file_name":"..."}` —— **两个 key**；
- 🔴 **两者都没有 `resources` 包装层**。

S4 当时（用已删除的 spike 脚手架）报告的是「`resources` 无 `size` 字段」，这一记载进了 `gateway-contract.md` §3.7/§16/§14。两者**不矛盾、是不同层面**：S4 的 `resources`/`ResourceDescriptor` 指 SDK `NormalizedMessage.getResources()` 归一化后的字段面（`List<ResourceDescriptor>`，只有 type/fileKey/fileName/durationMs、无 size），这一层结论**仍然正确**；真机说的「无 resources 包装层」指原始事件 JSON 的 `content` 结构（`{"image_key":...}`/`{"file_key":...,"file_name":...}`，没有再套一层 resources）。S4 的 `resources` 记载可能源于不同的事件订阅方式或 SDK 版本的 payload 形态。

### 🔴 `LarkInboundMapper` 是否兼容带 `resources` 包装层形态——只读核实结论（未改代码）

**结论：`LarkInboundMapper` 既不"只认实测形态"、也不"只认 resources 形态"——它压根不解析 `content` JSON，因此对两种形态都无兼容缺口。**

核实证据（`lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/LarkInboundMapper.kt`，只读）：
- `fromMessage`（`:15-34`）里 `text = r.content ?: ""` —— **把 content 整体当字符串透传**，不做任何 JSON 解析、不认识 `image_key`/`file_key`/`resources` 任何 key；
- 资源提取走的是 `resources = r.resources.map { InboundResource(it.type, it.fileKey, it.fileName, it.durationMs) }`（`:24`）—— 消费的是 **SDK 归一化后的 `NormalizedMessageRecord.resources`**（由 `captureNormalizedMessage` 从 `msg.resources` 浅拷贝而来，`SdkLarkGateway.kt:713-715`），**不是**从 content JSON 里自己抠。

因此：无论原始事件 content 是 `{"image_key":...}`（真机实测形态）还是带 `resources` 包装层（S4 记载形态），只要 **SDK 的 `NormalizedMessage.getResources()` 把资源解析进了 resources 列表**，`LarkInboundMapper` 就能正确产出 `InboundResource`——它对 content 的原始 JSON 结构**完全无感**。真机第五轮已验证：file 消息经 SDK 归一化后 resources 列表含 fileKey/fileName，`FileHandler.match` 命中并走到 `file_agent_disabled`（agent 未启用），image 消息正确归一化并走到未命中兜底。

**故：功能无影响，无兼容缺口，仅更正文档记载。** 未对 `LarkInboundMapper.kt` 做任何改动（施工项 3 只读核实）。
