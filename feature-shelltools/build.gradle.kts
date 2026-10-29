import org.jetbrains.compose.resources.ResourcesExtension

plugins {
    alias(libs.plugins.kotlinSerialization)
    id("easydevtoolbox.kmp.feature")
}

// Shell 工具域：引擎（串行队列 + 流式输出）/ 三源配置管道 / 设备检测与多设备页 / 历史回放。

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":ui"))
        }
    }
}

// 默认配置资源（files/shell_tools_default.json）随本模块迁入：
// 生成包名显式固定，避免依赖模块名推导（DefaultShellToolsConfigProvider 引用此包）
compose.resources {
    packageOfResClass = "easydevtoolbox.feature.shelltools.generated.resources"
}

// 跨模块测试基建共享件（UserHomeIsolation/KoogFakeExecutor）。skiko 场景渲染守护与
// JavaCV 解码栈随 logcat/scrcpy 域迁出后，本模块不再需要桌面 OS 自适应坐标与 javacv。
kotlin.sourceSets["jvmTest"].dependencies {
    implementation(project(":test-fixtures"))
}
