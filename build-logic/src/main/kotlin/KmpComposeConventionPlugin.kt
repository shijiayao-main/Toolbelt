package easydevtoolbox.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import org.gradle.testretry.TestRetryTaskExtension

/**
 * Compose Desktop KMP 约定（:core / :ui / :composeApp）：
 * - kotlin.multiplatform + jvm() 单 target，与 composeApp 现状同构；
 * - jvmTarget 显式固化 JVM_25（daemon 工具链同步升至 JBR 25，
 *   字节码 version 69 = Java 25）；
 * - commonMain 统一装配 compose 全家 + lifecycle + log + serialization 等公共依赖。
 *
 * 注意：插件实现仅编译期引用 KGP/Compose（build-logic compileOnly），运行期版本
 * 由各模块 plugins 块（根 build.gradle.kts 的 apply false 声明）决定，避免类双加载。
 */
class KmpComposeConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

        fun lib(alias: String): String = libs.findLibrary(alias).orElseThrow {
            IllegalStateException("version catalog 缺少 library 别名: $alias")
        }.get().toString()

        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        // 测试重试门控（CI-only）：CI 环境变量在场才重试，本地恒 0——内环反馈不被重试污染。
        // 官方立场：重试只是 flaky 缓解，必须与已知良性红登记/归因流程并行。
        // 扩展按 withType(Test) 挂在每个 Test 任务上，配置也按任务面收。
        pluginManager.apply("org.gradle.test-retry")
        tasks.withType<Test>().configureEach {
            extensions.configure(TestRetryTaskExtension::class.java) {
                maxRetries.set(if (System.getenv("CI").isNullOrBlank()) 0 else 2)
                maxFailures.set(20)
                failOnPassedAfterRetry.set(false)
            }
        }

        extensions.configure(KotlinMultiplatformExtension::class.java) {
            jvm().apply {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_25)
                }
            }

            sourceSets.apply {
                commonMain.get().dependencies {
                    implementation(lib("compose-runtime"))
                    implementation(lib("compose-foundation"))
                    implementation(lib("compose-material3"))
                    implementation(lib("compose-ui"))
                    implementation(lib("compose-uiToolingPreview"))
                    implementation(lib("androidx-lifecycle-viewmodelCompose"))
                    implementation(lib("androidx-lifecycle-runtimeCompose"))
                    implementation(lib("kotlinx-coroutinesCore"))
                    implementation(lib("kotlin-logging-jvm"))
                    implementation(lib("kotlinx-serializationJson"))
                }
                commonTest.get().dependencies {
                    implementation(lib("kotlin-test"))
                }
                jvmMain.get().dependencies {
                    implementation(lib("kotlinx-coroutinesSwing"))
                }
                jvmTest.get().dependencies {
                    implementation(lib("kotlinx-coroutinesTest"))
                    // ImageComposeScene 零显示器截图/布局验证——替代 XTEST 实机注入
                    //（熄屏不可截/坐标误点/帧冻结三病）
                    implementation(lib("compose-uiTest"))
                }
            }
        }
    }
}
