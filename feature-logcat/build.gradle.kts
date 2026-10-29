plugins {
    id("easydevtoolbox.kmp.feature")
}

// logcat 观测域 feature：实时抓取面板（引擎 / VM / 面板）+ 观测域配置仓库 +
// App 级运行时（引擎挂点与退出收口）。:core/:ui/DataStore/Material Symbols
// 基座由 easydevtoolbox.kmp.feature convention 预置。

// 跨模块测试基建共享件（UserHomeIsolation/KoogFakeExecutor）+ 场景守护 skiko：
// ImageComposeScene 场景渲染守护（LogcatPanelSceneGuardTest）需要 skiko 运行时
// ——convention 只配 compose-uiTest，skiko 平台包历来仅宿主装配。宿主的
// compose.desktop.currentOs 扩展依赖桌面 application 插件注册（feature 模块无），
// 故直接取插件同源 OS 自适应坐标（org.jetbrains.compose.desktop:desktop-jvm-<os>）。
// jvmTest 专用依赖，不进运行时与发布面（feature-shelltools 同款配方）。
kotlin.sourceSets["jvmTest"].dependencies {
    implementation(project(":test-fixtures"))
    // 场景守护渲染底座单源（SceneGuardSupport）——LogcatPanelSceneGuardTest 消费
    implementation(project(":test-support"))
    implementation(org.jetbrains.compose.ComposePlugin.DesktopDependencies.currentOs)
}
