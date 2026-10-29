package easydevtoolbox.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.gradle.testretry.TestRetryTaskExtension

/**
 * 纯 JVM CLI 库约定（:log-tools / :lark-gateway / :lark-bridge / :lark-ws）：
 * - kotlin-jvm + Java 11 显式固化（库侧 JVM target 唯一定义点）；
 * - shadow 打包统一（只排除签名文件、合并 META-INF/services；Main-Class 差异由各模块自行声明）；
 * - kotlin-test 作为测试依赖默认接入。
 */
class JvmCliConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("com.gradleup.shadow")

        // 测试重试门控（CI-only）：与 KmpComposeConventionPlugin 同款配置（见彼处注释）。
        pluginManager.apply("org.gradle.test-retry")
        tasks.withType<Test>().configureEach {
            extensions.configure(TestRetryTaskExtension::class.java) {
                maxRetries.set(if (System.getenv("CI").isNullOrBlank()) 0 else 2)
                maxFailures.set(20)
                failOnPassedAfterRetry.set(false)
            }
        }

        extensions.configure(JavaPluginExtension::class.java) {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }

        extensions.configure(KotlinJvmProjectExtension::class.java) {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
                // -Xjdk-release=11：jvmTarget 只约束字节码版本，不拦 classpath 上高版本
                // JDK API 的解析——无此 flag 时高版本 API（如 Java 16 Stream.toList）编译
                // 静默通过、JDK 11 运行时抛 NoSuchMethodError。flag 覆盖测试编译。
                freeCompilerArgs.add("-Xjdk-release=11")
            }
        }

        dependencies.add("testImplementation", "org.jetbrains.kotlin:kotlin-test")

        tasks.matching { it.name == "shadowJar" }.configureEach {
            (this as? com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar)?.apply {
                // 只排除签名文件：bcprov 是签名 jar，若把 *.SF/*.DSA/*.RSA 打进 fat jar 而不带原始签名摘要，
                // 运行时会以 SecurityException: Invalid signature file digest 完全无法启动。
                // 不可再用 exclude("META-INF/")，否则会连带剥掉 META-INF/services/，导致 slf4j ServiceLoader 失效。
                exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
                // 合并各依赖的 META-INF/services/*（slf4j provider、BouncyCastle JCE Provider、mordant TerminalInterfaceProvider）。
                mergeServiceFiles()
            }
        }
    }
}
