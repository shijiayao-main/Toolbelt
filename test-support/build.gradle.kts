plugins {
    id("easydevtoolbox.kmp.compose")
}

// 跨模块共享的场景守护渲染底座（SceneGuardSupport）宿主模块：composeApp arch 守护
// 与 :feature-* 场景守护测试共同消费的单源（消费方 jvmTest 声明
// testImplementation(project(":test-support"))）。
// 🔴 源集形态必须是 jvmMain（KMP 宿主域），不得用 src/main/kotlin（lib 域）：
//    有 src/main/kotlin 的模块会被 ScanSurfaceSelfCheckTest A 组按 jvm11 lib 口径
//    派生进 LarkSdkIsolationTest 的 LIB_MODULES（D 组禁 :core 依赖 + 禁宿主包引用），
//    而本模块两者都刚需（AppTheme 派生链锚定 + 宿主包名 com.jiaoay.easy_dev_toolbox.arch）。
kotlin {
    sourceSets {
        jvmMain.dependencies {
            // 主题派生链（AppTheme/AppThemeSpec/ThemeMode/AppAlpha）：背景锚/取色探针与
            // 页面渲染共用同一派生链，主题漂移探针随之漂移——配方锚定刚需
            implementation(project(":core"))
            // ImageComposeScene（SceneGuardHandle 公共签名面引用）：convention 只把
            // uiTest 配进 jvmTest，本模块 main 侧持有须显式声明；skiko 运行时由消费方
            // 自配（宿主 jvmMain currentOs / feature jvmTest currentOs）
            implementation(libs.compose.uiTest)
            // assertBandsSeparated 的断言底座（本模块整体即测试支撑件，main 侧持 kotlin-test）
            implementation(libs.kotlin.test)
        }
    }
}
