// :lark-bridge —— 飞书桥接内核 lib（jvmTarget 11 纯 JVM），同名同包重建（com.jiaoay.lark.bridge）。
// 🔴 不 relocate Gson/protobuf（SDK 内部反射依赖原始包名，relocate 会在运行时炸）。
// 🔴 本模块 jvmTarget 11，不得反向依赖 :core / :ui / :composeApp（jvmTarget 25）——R19；
//    lib 侧自建 BridgeLog/BridgeErrorCode，数据根路径由宿主注入（只接受 Path/File 参数）。
//    经 api(project(":lark-gateway")) 单向消费网关纯 Kotlin 契约面（SDK 隔离三重强制）。
plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
    id("easydevtoolbox.jvm.cli")
    alias(libs.plugins.kotlinSerialization)
}

// convention（easydevtoolbox.jvm.cli）已统一：Java 11 + jvmTarget 11 + kotlin-test + shadow +
// shadowJar exclude 签名文件 + mergeServiceFiles；本模块不重复声明 jvmTarget。

dependencies {
    // 单向依赖网关契约面：api 让 :feature-larkbridge 透传消费纯 Kotlin 网关类型（GatewayErrorCode 等）。
    // 🔴 网关以 implementation 声明 SDK，故 SDK 类型不会经此传递泄漏（编译期隔离）。
    api(project(":lark-gateway"))

    // 🔴 文件预处理（2026-09-11）：XLog/alog 解密走 :log-tools 的 LogDecode 门面。
    //    两模块同为 easydevtoolbox.jvm.cli convention（Java 11 + jvmTarget 11）⇒ 兼容；
    //    LarkSdkIsolationTest 的 forbiddenHostProjectDeps 不含 :log-tools ⇒ 不撞守护。
    //    implementation：LogDecode 类型不进本模块对外契约面。代价：fat-jar 增重（bouncycastle + zstd-jni）。
    implementation(project(":log-tools"))

    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.kotlinx.serializationJson)
    implementation(libs.clikt)

    // 🔴 S7 收口：SdkLarkGateway 经 org.slf4j.LoggerFactory 取 logger；本 lib 若无 slf4j binding，
    //    fat-jar 跑 CLI 时 slf4j 静默降级为 NOP（"No SLF4J providers were found"）。runtimeOnly 不污染编译期，
    //    版本复用 :core/:composeApp 既有别名（真源 gradle/libs.versions.toml 的 logback-classic 条目，
    //    不在此复述具体数字防漂移），不新增版本条目、不产生冲突。
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlinx.coroutinesTest)
}

// CLI 入口 com.jiaoay.lark.bridge.MainKt（M5-T10 落地）；manifest 属性不要求类此刻存在。
tasks.shadowJar {
    manifest {
        attributes["Main-Class"] = "com.jiaoay.lark.bridge.MainKt"
    }
}
