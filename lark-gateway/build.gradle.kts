// 全仓唯一 SDK 依赖点；禁止 relocate Gson/protobuf（SDK 内部反射依赖原始包名）。
// 除本模块外，任何模块出现 `com.lark.oapi` / `com.google.gson` / `org.apache.http` /
// `com.google.common` / `com.larksuite` 的 import 都属违规（M3-T4 的 LarkSdkIsolationTest 守护）。
plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
    id("easydevtoolbox.jvm.cli")
    alias(libs.plugins.kotlinSerialization)
}

// convention（easydevtoolbox.jvm.cli）已统一：Java 11 + jvmTarget 11 + kotlin-test +
// shadowJar exclude；本模块不声明 shadowJar Main-Class（无独立 CLI，仅作库被 :lark-bridge api 依赖）。

dependencies {
    // SDK 用 implementation（非 api）声明 → 下游模块写 `import com.lark.oapi.*` 直接编译失败。
    // 这是 SDK 隔离三重强制中最强的一道（编译期），不靠人工纪律。
    implementation(libs.oapi.sdk)

    // 自研长连接客户端（接线批 2026-09-12）：零 SDK 依赖的纯 Kotlin 传输层——
    // implementation 不进下游契约面；WsInboundEvent 经 WsInboundTranslator 翻译成 internal record。
    implementation(project(":lark-ws"))

    implementation(libs.kotlinx.coroutinesCore)
    // WsInboundTranslator 用 kotlinx.serialization 解析 content JSON（SDK 侧用 Gson——本模块两者皆可，
    // 翻译层选 kotlinx 与 :lark-ws 同栈；🔴 不引 Gson 新用法——SDK 隔离黑名单含 com.google.gson）。
    implementation(libs.kotlinx.serializationJson)

    testImplementation(libs.kotlinx.coroutinesTest)
}

// 本模块是库（被 :lark-bridge 以 api 依赖），无独立 CLI 入口 ⇒ 不产出 fat jar。
// 禁用 shadowJar 避免每次 build 产出 ~31.9MB 无用的 lark-gateway-all.jar（SDK + Gson + protobuf 全量打包）。
tasks.named("shadowJar") { enabled = false }
