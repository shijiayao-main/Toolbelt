plugins {
    alias(libs.plugins.kotlinSerialization)
    id("easydevtoolbox.kmp.feature")
}

// MCP 外放 feature（P5 openspec mcp-expose）：本地工具与 agent 能力经 MCP streamable HTTP
// 递给外部 client（入口组第二成员——与飞书桥接并列）。
//
// 🔴 依赖形态（design D1/D9）：
//  - kotlin-sdk-server 0.15.0（官方 KMP 产物，JVM 11+——宿主 jvm25 合规）；
//  - SDK POM 的 ktor-server-* 全部 scope=runtime ⇒ embeddedServer(CIO)/Application 等
//    Ktor 编译期类型必须显式声明 ktor-server-core + CIO 引擎（SDK 不传递引擎）；
//  - 版本 3.5.2 对齐 SDK 传递需求（≥ 传递版 3.5.1；独立于 :ui 的 ktor 3.3.3 线——那条线只服务
//    图片库，此处不共用版本 ref，避免 SDK 降级拉扯）。
// 🔴 io.modelcontextprotocol.* / io.ktor.* 只许出现在 McpServerManager.kt /
//    McpToolRegistrar.kt / McpAgentTools.kt 三装配层文件（McpSdkIsolationTest 钉死）。
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.mcp.sdk.server)
            implementation(libs.ktor.server.cio.sdk)
            implementation(libs.ktor.server.core.sdk)
        }
    }
}

// SYS-04（重设计批 1）：VM 行为测试构造 McpExposeViewModel 触 LlmConfigStore 真单例
// （DataStore 落数据根）——UserHomeIsolation 隔离（feature-ai 同款先例）。
kotlin.sourceSets["jvmTest"].dependencies {
    implementation(project(":test-fixtures"))
}
