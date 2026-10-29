plugins {
    alias(libs.plugins.kotlinSerialization)
    id("easydevtoolbox.kmp.feature")
}

// 飞书桥接域：配置文档（bridge-config.json）的 Compose 编辑器 + 运行监控页 + 宿主装配（LarkBridgeManager）。
// convention（easydevtoolbox.kmp.feature）已预置 :core/:ui/datastore/icons 依赖与 jvmTarget 25，本模块不重复声明。

kotlin {
    sourceSets {
        commonMain.dependencies {
            // 桥接内核 lib 的领域类型（BridgeConfig / BridgeFields / BridgeErrorCode / Bridge 门面等）
            // 仅本模块内部消费，不对宿主暴露（宿主只需 LarkBridgeManager 与 LarkBridgeContributions 两个自有符号）。
            implementation(project(":lark-bridge"))
            // 批 6 · 本地 Koog 调度 agent：端点配置与 executor 复用 LlmConfigStore / LlmClientFactory
            //（同一份配置真源，避免双份维护）。🔴 批 6-R 起这些类已下沉 :core/llm——本模块消费的是
            // :core 的 core.llm 面，不经 :feature-ai；koog 的 compile 可见性由 :core 的
            // api(libs.koog.agentsCore) 传递（:feature-ai 对 koog 件是 implementation，不传递任何件）。
            // 运行时 :feature-ai 的存在性由 :composeApp → :feature-ai 保证，本模块不需要声明。
            // （2026-09-15 S0：删除原 implementation(project(":feature-ai"))——源码零 page.ai import，
            //  ModuleGraphGuardTest 的首个用例即此违例。）
            // ChatMemory 特性（多轮上下文）：install(ChatMemory.Feature) + 自定义 ChatHistoryProvider
            // （过滤 System 消息防重复 + LRU 会话容量）。经 feature-ai 已传递 koog-agentsCore，
            // 此处显式声明 memory 特性模块。
            implementation(libs.koog.agentsFeaturesMemory)
            // handleEvents 特性（进度事件映射：onToolCallStarting/Completed → ProgressEvent）。
            // 🔴 feature-ai 对 koog 件是 implementation（不传递 compile 可见性）——本模块编译期
            //    直接 import 的 koog artifact 必须在此显式声明。
            implementation(libs.koog.agentsFeaturesEventHandler)
            // 批 7 · 知识库（RAG）链：embeddings-llm（LLMEmbedder：现有 OpenAILLMClient 直接当
            // Embedder 用）+ rag-vector（EmbeddingStorage / InMemory 后端 / SimilaritySearchRequest——
            // beta 版本线，官方文档明示 API 可能变；维护者拍板采用官方组件）。
            implementation(libs.koog.embeddingsLlm)
            implementation(libs.koog.ragVector)
            implementation(libs.koog.ragVectorJvm)
            // 2026-09-14 内部工具集：log-tools 直调（decrypt_log/filter_by_tag/filter_by_time/
            // split_by_size/probe_file 的纯 JVM 能力面）+ core.llm.tools 下沉件（ToolExecutionContext
            // 围栏 / DecryptTools 解压预览工具）。:core 对 :log-tools 已是 api（传递可见），此处显式
            // 声明本模块编译期直接 import 的能力面（同 koog 件先例）。
            implementation(project(":log-tools"))
        }
    }
}

// 批 7 · 手册资源打包：docs/manual/ 是内容真源，构建期同步进 jvmMain resources（随 App 分发——
// 使用者零配置；工具迭代 → 手册随版本更新 → 重启即最新知识）。🔴 resources/manual/ 是构建产物
// 不入库（.gitignore 已加）；manifest.txt 是运行期发现手册的唯一途径（classloader 枚举不了 jar
// 内资源目录）。两个任务都全量声明 inputs/outputs，天然增量 + configuration cache 兼容。
val syncManualToResources by tasks.registering(Sync::class) {
    // 🔴 手册真源在仓库根的 docs/manual/（不在本模块目录内）
    from(rootProject.file("docs/manual")) {
        include("*.md")
    }
    into(layout.projectDirectory.dir("src/jvmMain/resources/manual"))
}

val generateManualManifest by tasks.registering {
    // manifest 依赖 Sync 完成后生成；输入 = 同步产物目录，输出 = manifest.txt
    dependsOn(syncManualToResources)
    val manualDir = layout.projectDirectory.dir("src/jvmMain/resources/manual")
    inputs.files(manualDir.asFileTree.matching { include("*.md") })
    outputs.file(manualDir.file("manifest.txt"))
    doLast {
        val names = manualDir.asFile.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.map { it.name }?.sorted() ?: emptyList()
        val manifestFile = manualDir.file("manifest.txt").asFile
        manifestFile.parentFile.mkdirs()
        manifestFile.writeText(names.joinToString("\n", postfix = "\n"))
    }
}

// 让 jvm 资源处理依赖同步链（保证 manual/ 随 jar 打包且 manifest 与 md 同步）
tasks.named("jvmProcessResources") {
    dependsOn(syncManualToResources, generateManualManifest)
}

// 审计批 2026-09-27：跨模块测试基建共享件（UserHomeIsolation/KoogFakeExecutor）
kotlin.sourceSets["jvmTest"].dependencies {
    implementation(project(":test-fixtures"))
    // 场景守护渲染底座单源（arch 包 SceneGuardSupport，宿主 :test-support 模块）——
    // FieldRow 响应式双形态的离屏像素/语义锚守护消费
    implementation(project(":test-support"))
    // 场景守护 skiko 运行时（ImageComposeScene 需要）——feature 模块无宿主的
    // currentOs 扩展装配，直接取插件同源 OS 自适应坐标（feature-logcat 同款配方，
    // jvmTest 专用不进运行时）
    implementation(org.jetbrains.compose.ComposePlugin.DesktopDependencies.currentOs)
}
