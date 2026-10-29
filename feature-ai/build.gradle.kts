plugins {
    alias(libs.plugins.kotlinSerialization)
    id("easydevtoolbox.kmp.feature")
}

// AI 能力域（LLM 设置页 + 万能解密）：
// - Koog（JetBrains Kotlin agent 框架）1.2.0 细粒度接入——agents-core 传递携带
//   agents-tools / prompt-executor-openai-client / prompt-executor-model；
//   http-client-java 经 SPI 用 JDK HttpClient，不引 Ktor 引擎。
//   🔴 Koog 字节码 Java 17 ⇒ 只能进宿主侧 jvmTarget 25 模块（本模块），不得进 :lark-bridge/:log-tools。
// - 依赖 :log-tools（LogDecode 解密 / LogProbe 探测 / SafeExtract 解压，同为纯 JVM 能力面）。

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.koog.agentsCore)
            implementation(libs.koog.httpClientJava)
            // handleEvents 轨迹特性是独立稳定模块（伞包传递有、agents-core 不携带）——万能解密的执行过程展示必需
            implementation(libs.koog.agentsFeaturesEventHandler)
            implementation(project(":log-tools"))
        }
    }
}

// 审计批 2026-09-27：跨模块测试基建共享件（UserHomeIsolation/KoogFakeExecutor）
// 场景守护 skiko：ImageComposeScene 场景渲染守护（ModelIdDropdownSceneGuardTest 等）需要
// skiko 运行时——convention 只配 compose-uiTest，skiko 平台包历来仅宿主装配；feature 模块
// 无桌面 application 插件，直接取插件同源 OS 自适应坐标（:feature-logcat 同款配方，
// jvmTest 专用不进运行时）。
kotlin.sourceSets["jvmTest"].dependencies {
    implementation(project(":test-fixtures"))
    implementation(project(":test-support"))
    implementation(org.jetbrains.compose.ComposePlugin.DesktopDependencies.currentOs)
}
