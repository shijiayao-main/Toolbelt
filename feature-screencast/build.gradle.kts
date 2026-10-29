plugins {
    id("easydevtoolbox.kmp.feature")
}

// 投屏解码链的 native classifier 按构建机 OS/arch 自适应：安装包只随打包平台的一套
// native，跨平台复用包必然缺件。各平台 classifier 均已在 Maven Central 发布
// （windows-arm64 的 ffmpeg 无发布件，故落显式失败）；此处失败好过静默退化成无
// classifier 件、到首次解码才抛 UnsatisfiedLinkError。只读 System.getProperty + 纯
// Kotlin 字面量，不捕获 Project（配置缓存安全）：平台是构建机固定属性，同一 .gradle
// 目录下不会变化。归一化口径与 feature-voice 的平台键判定同构。
val bytedecoClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    when {
        "linux" in os && arch in setOf("amd64", "x86_64") -> "linux-x86_64"
        "linux" in os && arch == "aarch64" -> "linux-arm64"
        "mac" in os && arch == "aarch64" -> "macosx-arm64"
        "mac" in os && arch in setOf("amd64", "x86_64") -> "macosx-x86_64"
        "win" in os && arch in setOf("amd64", "x86_64") -> "windows-x86_64"
        else -> error("投屏解码栈无对应 native classifier（$os $arch）")
    }
}

// 投屏观测域 feature（设备观测空间第二成员）：scrcpy 协议面（部署/隧道/握手/demuxer）+
// 投屏引擎（解码帧流发布）+ 面板。设备检测态复用 :core 设备域（feature 互不依赖铁律，
// :feature-logcat 同款先例）。

kotlin {
    sourceSets {
        // JavaCV 解码栈随 scrcpy 域整体隔离在本模块（重依赖单模块隔离纪律，
        // LarkSdkIsolation/SherpaOnnx 先例）：版本与许可结论登记在 gradle/libs.versions.toml
        // javacv 条目（classifier 恒锁默认非 -gpl；exclude 全排除后显式补 javacpp/ffmpeg）。
        // 生产运行时依赖（引擎解码用，非 spike 专用）：jvmMain 消费，宿主运行时经
        // runtimeClasspath 传递获得；composeApp nativeDistributions 模块清单随本依赖
        // 补 java.desktop（Java2D 转换链）。
        // javacv 的 exclude 需走 String 记法重载（catalog accessor Provider 不匹配带
        // lambda 的重载），版本仍引 version catalog；javacpp/ffmpeg 无 lambda 用
        // catalog accessor 直挂。
        // native classifier 随构建 OS 自适应（[bytedecoClassifier]）：产物只含打包平台
        // 的一套 native，跨平台搬运安装包会在解码期缺件。运行期工厂据此做 native 实加载
        // 探测，加载失败给显式指引而非裸 LinkageError（见 JavaCvScreencastDecoderFactory）。
        jvmMain.dependencies {
            implementation("org.bytedeco:javacv:${libs.versions.javacv.get()}") { exclude(group = "org.bytedeco") }
            implementation(libs.javacpp.core)
            implementation("org.bytedeco:javacpp:${libs.versions.javacv.get()}:$bytedecoClassifier")
            implementation(libs.ffmpeg.binding)
            implementation("org.bytedeco:ffmpeg:${libs.versions.ffmpegBytedeco.get()}:$bytedecoClassifier")
        }
    }
}

// 场景渲染守护（投屏面板）需要 skiko 运行时——convention 只配 compose-uiTest，skiko
// 平台包历来仅宿主装配，故直接取插件同源 OS 自适应坐标（:feature-logcat 同款配方）。
// jvmTest 专用依赖，不进运行时与发布面。
kotlin.sourceSets["jvmTest"].dependencies {
    // 场景守护渲染底座单源（SceneGuardSupport）——ScreencastPanelSceneGuardTest 消费
    implementation(project(":test-support"))
    implementation(org.jetbrains.compose.ComposePlugin.DesktopDependencies.currentOs)
}

// ── 真窗渲染链按需诊断通道（diag 源集 + realWindowDiag JavaExec 任务） ──────────
// 端到端验证投屏独立窗渲染链（引擎会话 → 真窗挂载 → 帧区像素），PASS/FAIL 判定 +
// 退出码；用途/运行方式/判据契约见 src/jvmDiag/kotlin/.../RealWindowDiagMain.kt
// 头 KDoc。源集名 = jvmDiag（KGP 自定义编译源集带 target 前缀，与 jvmTest 同名规）。
// 🔴 恒不进常规 jvmTest/build/CI（需真机在线 + 桌面显示环境），仅由本任务显式触发。
val jvmTarget = kotlin.jvm()
val diagCompilation = jvmTarget.compilations.create("diag") {
    // 消费 main 编译产物并见 internal 面（同 jvmTest 对 main 的 friend 关系配方）；
    // 类路径/资源经编译配置传递，不再挂显式 dependsOn 边（免打破默认源集层级模板）
    associateWith(jvmTarget.compilations.getByName("main"))
}
diagCompilation.defaultSourceSet {
    dependencies {
        // 真窗诊断 launcher（application{}/Window 组合面）——diag 专用，不进 jvmMain 运行时
        implementation("org.jetbrains.compose.ui:ui-desktop:${libs.versions.composeMultiplatform.get()}")
        // skiko 平台包：桌面真窗渲染运行时原生件（:feature-logcat jvmTest 同款坐标）
        implementation(org.jetbrains.compose.ComposePlugin.DesktopDependencies.currentOs)
    }
}

tasks.register<JavaExec>("realWindowDiag") {
    group = "diagnostics"
    description = "真机真窗投屏渲染链端到端诊断（PASS/FAIL + 退出码 0/1；需 adb 设备在线）"
    mainClass.set("com.jiaoay.easy_dev_toolbox.page.screencast.diag.RealWindowDiagMainKt")
    // 与应用侧 composeApp:run 相同的 JVM 参数（堆 2G 上限 + native 访问消音）——
    // 诊断与项目实例保持同一运行环境，防「诊断窗有背景/项目窗透底」的环境分叉
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx2g")
    dependsOn(diagCompilation.compileKotlinTaskName)
    dependsOn(jvmTarget.compilations.getByName("main").compileKotlinTaskName)
    dependsOn("jvmProcessResources")
    // jvmMain 随包资源经类路径释放（scrcpy server jar——缺此则引擎部署面失败）
    classpath = files(
        diagCompilation.output.classesDirs,
        jvmTarget.compilations.getByName("main").output.classesDirs,
        tasks.named<Copy>("jvmProcessResources").map { it.destinationDir },
    )
    classpath += configurations["jvmRuntimeClasspath"]
    // diag 源集专属运行时（ui-desktop + skiko 平台包）
    classpath += configurations["jvmDiagRuntimeClasspath"]
    val serial = providers.gradleProperty("diagSerial").orNull
        ?: providers.environmentVariable("DIAG_SERIAL").orNull
    if (serial != null) args("--serial=$serial")
}
