// :lark-ws —— 飞书长连接客户端自研模块（jvmTarget 11 纯 JVM，零第三方网络库）。
// 参照飞书官方 Java SDK v2.8.5 源码（ws.Client/Listener/pbbp2）用 Kotlin 重实现传输层：
// 协程替代 SDK 永不 shutdown 的非 daemon FixedThreadPool（反射 shutdown 的根治路径）。
// 🔴 独立零依赖：不依赖 :lark-gateway / :lark-bridge / 宿主 jvm21 模块——
//    事件类型自建 WsInboundEvent，由 gateway 侧翻译（transport 与契约解耦，可独立测试/复用）。
// 🔴 网络栈全走 JDK 标准库 java.net.http.HttpClient/WebSocket（JDK 9+ API）；
//    不引 okhttp（SDK 用 shaded com.lark.oapi.okhttp，引官方版反而制造两份）；
//    不引 protobuf-java（pbbp2 仅 2 message / 9 字段，wire format 手写编解码，未知字段天然跳过）。
// 🔴 shadowJar 禁用（无独立 CLI，仅作库；避免产出无用 fat jar——同 :lark-gateway 先例）。
plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
    id("easydevtoolbox.jvm.cli")
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.kotlinx.serializationJson)

    testImplementation(libs.kotlinx.coroutinesTest)
}

// 本模块是库（未来由 :lark-gateway 以 implementation 依赖），无独立 CLI 入口 ⇒ 不产出 fat jar
// （同 :lark-gateway 先例：禁用 shadowJar 避免每次 build 产出无用 fat jar）。
tasks.named("shadowJar") { enabled = false }
