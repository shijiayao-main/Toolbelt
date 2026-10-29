plugins {
    id("easydevtoolbox.kmp.compose")
    // 2026-09-14 per-user 性格批：KoogUserStyleMap（openId→styleId 映射 JSON 编解码）需要
    // @Serializable 编译器插件——convention 只装配了 serializationJson 依赖、未 apply 本插件
    // （:lark-bridge / :feature-larkbridge 均显式声明，本处对齐）。
    alias(libs.plugins.kotlinSerialization)
    // 2026-09-19 P1-A2 存储层：agent 会话/记忆 DB 化（sqlite-jdbc + SQLDelight 类型安全生成）
    alias(libs.plugins.sqldelight)
}

// 基础层：theme token / log / initializer / data 骨架 / shell 原语 / 导航契约 / JResult / LLM 端点域。
// convention（easydevtoolbox.kmp.compose）已统一构建配置与公共依赖；
// 额外依赖：datastore（LocalDataManager 骨架）+ logback（JLog 后端装配）。
// 批 6：LLM 端点域（LlmConfigStore / LlmClientFactory / LlmEndpointUrls / JsonContentTypeFix）
// 从 feature-ai 下沉至此（UiGuard 铁律：feature 不得跨页面域 import——桥接调度 agent 复用端点配置
// 的合规路径）。🔴 api 而非 implementation：LlmClientFactory.executor 返回 Koog 的
// MultiLLMPromptExecutor、消费方（feature-ai/feature-larkbridge）要接此类型，Koog 件须传递可见。
// 🔴 Koog 字节码 Java 17：core 是 jvmTarget 25（convention 统一），合规。

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.androidx.datastore)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.logback.classic)
            // 动态配色：AppThemeSpec.dynamicColorScheme（种子色生成 Material3 ColorScheme）
            implementation(libs.material.kolor)
            // LLM 端点域（批 6 下沉）：executor 进程级缓存门面 + OpenAI 兼容端点配置仓库
            api(libs.koog.agentsCore)
            api(libs.koog.httpClientJava)
            // S4 · KoogToolLoop 骨架需要 handleEvents 特性（进度/轨迹事件映射是骨架职责）；
            //    api（非 implementation）：KoogAgentRunner/DecryptAgent 的事件映射经 AIAgent 装配块
            //    内联在骨架回调里，跨模块可见性必须传递
            api(libs.koog.agentsFeaturesEventHandler)
            // 2026-09-19 P1-A2 存储层：ChatHistoryProvider 接口面（SqliteChatHistoryProvider 实现
            // 挂进本模块；桥接 KoogAgentRunner 的 ChatMemory.Feature 与桌面会话库共用此 provider）。
            api(libs.koog.agentsFeaturesMemory)
            // LLM 工具域（2026-09-14 下沉）：ToolExecutionContext 围栏 + DecryptTools 工具集
            // （万能解密与飞书桥接调度 agent 两域共用；UiGuard 铁律：feature 不得跨页面域 import）。
            // LogDecode/SafeExtract/LogProbe 是 log-tools 的纯 JVM 能力面。
            api(project(":log-tools"))
            // 设备域能力面（:device-core 纯 JVM lib）：宿主侧 DeviceHub 装配、各 feature 的
            // 设备观测消费都经此传递可见。api 而非 implementation——DeviceState/DeviceInfo
            // 等类型出现在 feature 的公开签名与状态流里。
            api(project(":device-core"))
            // 内置 adb 的分发与端点解析（:adb-core 纯 JVM lib）：宿主装配 AdbBinaryReleaser
            // 与端口注入。api：AdbEndpoint 出现在 :core 的公开接缝面。
            api(project(":adb-core"))
            // 2026-09-19 P1-A2 存储层：sqlite-jdbc（Koog 官方 sql 模块同款驱动，FTS5 开箱）
            // + SQLDelight 运行时（Java 8 字节码，宿主 jvm25 合规）。api：DB 面向 feature
            // （feature-ai 会话库/未来 logworkbench 知识库）传递可见。
            api(libs.sqlite.jdbc)
            api(libs.sqldelight.runtime)
            api(libs.sqldelight.driver.jvm)
            implementation(libs.sqldelight.coroutines)
        }
    }
}

// 2026-09-19 P1-A2：SQLDelight 生成配置——.sq 源码在 commonMain（KMP 单 jvm target 下
// 生成类型安全 Kotlin）；schema 从 1 起步（新真源，与 bridge-config 旧账互不干扰）。
sqldelight {
    databases {
        create("ToolbeltDb") {
            packageName.set("com.jiaoay.easy_dev_toolbox.core.data.db")
            // SQLDelight 2.x dialect 按 SQLite 版本分 artifact；xerial 3.53 内核 ⇒ 3-35 dialect
            dialect("app.cash.sqldelight:sqlite-3-35-dialect:2.4.0")
            // v1 = sessions/messages + FTS5（P1-A2）；v2 = + 知识库四表（P3-D）；
            // v3 = + 日志工作台 runs 表（P4-C2）；v5 = + 调查空间 spaces 表（调查工作台
            // 重设计 S1，2026-09-22）。迁移文件 .sqm：1.sqm（v1→v2 知识库四表）、
            // 2.sqm（v2→v3 runs）、3.sqm（v3→v4 flows）、4.sqm（v4→v5 spaces）——存量库
            // 经 user_version 走 migrate 增表，不重建原库。
            // ⚠️ 本 version 属性是**死配置**：实际 Schema.version 由生成器按 .sqm 文件数
            // 推导（现 4 个 .sqm ⇒ version=5，见生成产物 ToolbeltDbImpl.version）——此处
            // 仅作演进注释锚，改数字无效果也无需改（2026-09-22 S1 核实更正，勿再误信）。
            version = 3
            deriveSchemaFromMigrations = false
        }
    }
}

// 2026-09-19 P4-B：kb-import CLI 批处理入口（md 知识包 → SQLite 四表）
tasks.register<JavaExec>("runKbImport") {
    group = "toolbelt"
    description = "kb-import CLI：md 知识包导入（--args='--kb <目录> [--dry-run] [--pack-out <file>]'）"
    mainClass.set("com.jiaoay.easy_dev_toolbox.core.data.db.KbImportMainKt")
    classpath = kotlin.jvm().compilations.getByName("main").output.classesDirs.plus(
        configurations["jvmRuntimeClasspath"],
    )
}

// 审计批 2026-09-27：跨模块测试基建共享件（UserHomeIsolation/KoogFakeExecutor）
kotlin.sourceSets["jvmTest"].dependencies {
    implementation(project(":test-fixtures"))
}

// guardTests：core 侧扫盘守护快速通道——只跑 system/ 守护面（ProcessBuilder 合法点集守护）。
// 与 composeApp:guardTests 同款永跑语义：被扫源码不在 task 声明输入里（跨模块源码变更
// 不进 cache key），不入 up-to-date、不入 build cache，每次调用都真实执行。
// 内环快速反馈通道；全量验收仍走 :core:jvmTest。
tasks.register<Test>("guardTests") {
    group = "verification"
    description = "只跑 core 扫盘守护测试（system/ 守护面）——内环快速反馈，全量验收仍走 :core:jvmTest"
    val jvmTestTask = tasks.named<Test>("jvmTest")
    testClassesDirs = jvmTestTask.get().testClassesDirs
    classpath = jvmTestTask.get().classpath
    filter {
        includeTestsMatching("com.jiaoay.easy_dev_toolbox.core.system.*")
    }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
