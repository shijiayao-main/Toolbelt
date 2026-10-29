plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.jetbrainsKotlinJvm) apply false
    // convention plugin 运行期所需插件的 classpath 声明（build-logic 仅 compileOnly 引用）
    alias(libs.plugins.gradleupShadow) apply false
    alias(libs.plugins.testRetry) apply false
}