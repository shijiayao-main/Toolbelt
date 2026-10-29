plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("jvmCli") {
            id = "easydevtoolbox.jvm.cli"
            implementationClass = "easydevtoolbox.buildlogic.JvmCliConventionPlugin"
        }
        register("kmpCompose") {
            id = "easydevtoolbox.kmp.compose"
            implementationClass = "easydevtoolbox.buildlogic.KmpComposeConventionPlugin"
        }
        register("kmpFeature") {
            id = "easydevtoolbox.kmp.feature"
            implementationClass = "easydevtoolbox.buildlogic.KmpFeatureConventionPlugin"
        }
    }
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // 仅编译期：convention plugin 不打包插件实现，运行期版本由各模块 plugins {} 块
    // （根 build.gradle.kts `apply false` 声明进主构建 classpath）决定，避免插件类双加载。
    // 经 plugin marker 坐标引用，保持 version catalog 单一真源。
    compileOnly(libs.plugins.kotlinMultiplatform.toPluginMarker())
    compileOnly(libs.plugins.composeMultiplatform.toPluginMarker())
    compileOnly(libs.plugins.composeCompiler.toPluginMarker())
    compileOnly(libs.plugins.jetbrainsKotlinJvm.toPluginMarker())
    compileOnly(libs.plugins.gradleupShadow.toPluginMarker())
    // test-retry 的 plugin marker 元数据不带标准 java 变体（marker 坐标引不到编译类），
    // 直引实现件 GAV——版本仍以 version catalog 为单一真源。
    compileOnly("org.gradle:test-retry-gradle-plugin:${libs.versions.testRetry.get()}")
}

/** catalog plugin 别名 → 对应 plugin marker 坐标（groupId:pluginId = id, artifactId = id.gradle.plugin） */
fun Provider<PluginDependency>.toPluginMarker(): String =
    get().let { plugin ->
        "${plugin.pluginId}:${plugin.pluginId}.gradle.plugin:${plugin.version.requiredVersion}"
    }
