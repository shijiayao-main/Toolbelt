// :test-fixtures —— 跨模块测试共享基建（共享 fixture 单源收敛件）。
// 🔴 纯 JVM、jvmTarget 11（全仓最低 common denominator——core/composeApp/feature
//    域测试（25）与 lark-bridge 域测试（11）都消费得起）、零生产模块依赖
//    （不得依赖 :core/:ui/:lark-*——业务态 fixture 各测试自持，本模块只放无依赖工具）。
plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
}

// Java 11 + jvmTarget 11 双钉（对齐 JvmCliConventionPlugin 配方——否则 compileJava(25)
// 与 compileKotlin(11) 不一致直接构建失败）
java {
    sourceCompatibility = org.gradle.api.JavaVersion.VERSION_11
    targetCompatibility = org.gradle.api.JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        // -Xjdk-release=11：本模块不走 JvmCliConventionPlugin（手钉 Java 11），flag 须单补——
        // 只约束字节码版本不拦 classpath 上高版本 JDK API 的解析（对齐 convention 插件同款配方）
        freeCompilerArgs.add("-Xjdk-release=11")
    }
}

dependencies {
    // KoogFakeExecutor 基座的唯一三方依赖（OpenAILLMClient 经 agentsCore 传递；
    // compileOnly——消费模块测试自带 koog 依赖，本模块不钉版本线）。
    // 🔴 Koog 例外依据（jvm11 模块接触 Java 17 字节码）：Koog 字节码是 Java 17，
    //    本模块 jvmTarget 11 仅编译期接触（compileOnly 不进产物）；消费方
    //    （KoogToolLoopTest/QuestSessionTest 等继承 KoogFakeExecutor 的测试）全在
    //    宿主侧 jvmTarget 25 模块的测试里运行——零运行时渗透，不违
    //    「Koog Java 17 字节码不得进 jvm11 模块运行时」铁律。
    compileOnly(libs.koog.agentsCore)
    testImplementation(kotlin("test"))
}
