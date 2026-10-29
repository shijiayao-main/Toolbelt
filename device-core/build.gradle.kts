plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
    id("easydevtoolbox.jvm.cli")
}

// :device-core —— 设备域能力 lib（jvmTarget 11 纯 JVM）：设备检测与选定态、文件浏览/拉取/推送、
// APK 安装与包管理、布局与全窗口视图检查、性能采样、按键注入、无线连接。
//
// 🔴 包名 com.jiaoay.toolbelt.device：有 src/main/kotlin 源集的模块会被 ScanSurfaceSelfCheckTest
//    自动判定为 jvm11 lib，LarkSdkIsolationTest D 组禁止 lib 引用宿主包前缀。
//
// 本模块不持任何宿主底座：命令执行（[AdbProcessRunner]）、日志（[AdbLog]）、协程作用域、
// 配置读取、产物目录全部由调用方注入——故可脱离 Toolbelt 单独使用。

dependencies {
    api(project(":adb-core"))
    implementation(libs.kotlinx.coroutinesCore)
    testImplementation(libs.kotlinx.coroutinesTest)
}
