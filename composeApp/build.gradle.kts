import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
    id("easydevtoolbox.kmp.compose")
}

// convention（easydevtoolbox.kmp.compose）已统一：KMP+jvm() 单 target、JVM_25、
// compose 全家 + lifecycle + log + serialization 公共依赖。
// 以下仅保留宿主个性配置（desktop currentOs、两库依赖、打包、resources）。

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.components.resources)
            // 批 9b：Material Symbols（字体形态）——全仓图标唯一来源
            // （material-icons-extended 已彻底移除：官方停止维护，全仓迁移至本库）
            implementation(libs.compose.material.symbols)

            // DataStore library
            implementation(libs.androidx.datastore)
            // The Preferences DataStore library
            implementation(libs.androidx.datastore.preferences)

            // logback：JLog 的后端装配（convention 仅含 kotlin-logging 门面）
            implementation(libs.logback.classic)

            // 动态配色：设置页图片提取主题色（ImageThemeCard/PageSettings 预览用）
            implementation(libs.material.kolor)


            implementation(project(":core"))
            implementation(project(":ui"))
            implementation(project(":feature-timestamp"))
            implementation(project(":feature-logkit"))
            implementation(project(":feature-shelltools"))
            implementation(project(":feature-logcat"))
            implementation(project(":feature-screencast"))
            implementation(project(":feature-larkbridge"))
            implementation(project(":feature-logworkbench"))
            implementation(project(":feature-ai"))
            implementation(project(":feature-archive"))
            implementation(project(":feature-voice"))
            implementation(project(":feature-mcp"))
            implementation(project(":feature-quest"))
            implementation(project(":log-tools"))
        }
        jvmMain.dependencies {
            // desktop 运行时（skiko 平台包）仅宿主需要：:core/:ui 为纯 compose 声明层
            implementation(compose.desktop.currentOs)
        }
        // 跨模块测试基建共享件（UserHomeIsolation/KoogFakeExecutor）+ 场景守护渲染底座
        // 单源（arch 包 SceneGuardSupport——composeApp arch 守护测试同包免 import 消费）
        jvmTest.dependencies {
            implementation(project(":test-fixtures"))
            implementation(project(":test-support"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.jiaoay.easy_dev_toolbox.MainKt"

        // JVM 封禁 API 警告消音（批 4g）：
        // - skiko LibraryLoader System::load native .so → restricted method 警告
        //
        // 🔴 2026-09-17 审计修正：原第二项 "--sun-misc-unsafe-memory-access=allow" 已移除。
        //    该选项是 JDK 23+ 引入（JEP 471），且**发行版相关**：JBR 静默容忍未知长选项，
        //    标准 OpenJDK/Zulu 直接 `Unrecognized option` 拒绝启动。本仓 Gradle daemon 用的是
        //    JBR（gradle-daemon-jvm.properties 的 toolchainVendor=JETBRAINS），jpackage 也用它
        //    产出内置运行时，故当前不炸；但这构成一个脆弱依赖——一旦换 vendor 或 JBR 收紧校验，
        //    安装包会直接起不来。该 flag 只压 datastore 的 Unsafe 弃用警告、不改行为，收益极小，
        //    故删除。若未来 datastore 因 Unsafe 被封而崩溃，正解是升 datastore 版本。
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx2g")
        // -Xmx2g：固定运行时堆上限。缺省时 JVM 默认最大堆 ≈ 物理内存 1/4，不同机器漂移；
        // 日志分析（滚动窗口）+ SQLite WAL + AI/语音负载下 GC 行为不可预测，故钉住。
        // 实机观察后可上调（如 4g），仅改此行。

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Toolbelt"
            // 版本号由发布 tag 驱动（CI 传 -PpackageVersion=v 剥离后的纯数字），
            // 本地/未传时回退 1.0.0。MSI ProductVersion 须 MAJOR.MINOR.BUILD 纯数字
            // （≤255/≤255/≤65535，第四段忽略）：Windows Installer 按 ProductCode=
            // hash(vendor,appName,version) 拒绝同版本重装（错误 1638），版本号必须随发布递增。
            // 🔴 macOS jpackage 拒绝 0 开头版本号（"The first number in an app-version
            //    cannot be zero or negative"——v0.2.x mac 双平台实败真因）；0.x 版本线
            //    mac 永远不可发，发布线须从 v1.0.0 起（CI 有 fail-fast 预检）。
            packageVersion = providers.gradleProperty("packageVersion").getOrElse("1.0.0")
            vendor = "Toolbelt"

            // 随包许可文档：jpackage 三平台均会把它放进安装包（deb 落 share/doc/copyright/
            // license，msi/dmg 落应用目录）。义务真源见仓库根 NOTICE（Apache-2.0 归属 +
            // LGPL §4 许可文本随附 + 来源与可替换性声明）。
            licenseFile.set(rootProject.file("LICENSE"))

            // jlink 默认裁剪 JRE 不含 jdk.unsupported，但 androidx.datastore 的 protobuf 需要 sun.misc.Unsafe
            modules("jdk.unsupported")

            // 投屏解码栈（:feature-screencast 的 JavaCV 三件）真实 JDK 模块需求，依据 =
            // 对解析出的 bytedeco 栈 jar 剥除 module-info 后逐 jar 跑 jdeps
            // （--print-module-deps --ignore-missing-deps --multi-release 21，飞书 SDK 栈同款旗标）：
            //   javacv 1.5.14    → java.desktop, java.logging（Java2DFrameConverter 转换链）
            //   javacpp 1.5.14   → java.management, jdk.unsupported
            //   ffmpeg 8.1.2 绑定 → java.base
            // 本段只补 java.desktop / java.management：java.logging 由下方飞书栈条目承载、
            // jdk.unsupported 由上方条目覆盖（jlink modules 取全文件并集，声明顺序无关）。
            // java.management 与下方 Koog/Ktor 段同名重复声明——jlink 对重复声明幂等，
            // 各段按自身需求源记账，防单段日后删除时静默丢模块。
            modules(
                "java.desktop",      // javacv（BufferedImage/Java2D 转换链）
                "java.management",   // javacpp（平台运行时管理面）
            )

            // 🔴 2026-09-17 审计修复：jdk.charsets + jdk.localedata 必须显式补。
            //    用与本文档同一份模块清单本地 jlink 重建运行时的**实测**结果（对照完整 JDK）：
            //
            //    charset          完整JDK              裁剪运行时
            //    MS950 (繁中)     ✅ x-windows-950     ❌ UnsupportedCharsetException
            //    MS949 (韩文)     ✅ x-windows-949     ❌ UnsupportedCharsetException
            //    windows-1255     ✅ OK                ❌ UnsupportedCharsetException
            //    GBK / GB18030    ✅ OK                ✅ OK（在 java.base 里）
            //
            //    ⇒ 中文环境侥幸不受影响（GBK 在 java.base），**这正是该缺陷长期未暴露的原因**；
            //      受害面是 Windows 繁中 / 韩文 / 希伯来文 / 阿拉伯文 / 越南文语言版。
            //      且 ShellCommandBuilder.processCharset 是 object 的 static val，异常发生在
            //      **类初始化** ⇒ ExceptionInInitializerError ⇒ 整条 Shell 链路（Shell 工具页 /
            //      adb 重写 / 读 shellType）全废，后续引用恒抛 NoClassDefFoundError。
            //    localedata 缺失不崩溃，但 Locale 格式化静默回落英文：
            //      SimpleDateFormat("yyyy-MM-dd MMMM EEEE", zh-CN)
            //        完整 JDK = 2023-11-15 十一月 星期三 / 裁剪 = 2023-11-15 Nov Wed
            modules(
                "jdk.charsets",         // 非 CJK 语言版 Windows 的 ANSI 字符集（MS950/MS949/1255…），约 +2MB
                "jdk.localedata",       // Locale 月名/星期名等本地化数据，约 +2~4MB
            )

            // 飞书 SDK / OAuth Device Grant 可能触及 JDK HttpClient，jdeps 静态分析检测不到，需显式保留
            modules("java.net.http")

            // 🔴 飞书 SDK 栈的真实 JDK 模块需求（jlink 若只扫 :lark-gateway 空壳 jar 等于没扫过 SDK；
            //   缺模块会导致「安装包能启动，但一点『启动』就 NoClassDefFoundError」）。
            //   依据 = 对 composeApp 实际解析出的 SDK 栈 jar 跑 jdeps（JDK 25，--print-module-deps
            //   --ignore-missing-deps --multi-release 21 -cp <完整 jvmRuntimeClasspath>），逐 jar 结果：
            //     飞书 SDK 2.8.5              → java.base,java.compiler,java.naming,java.security.jgss,java.sql,jdk.unsupported
            //     httpclient 4.5.13           → java.base,java.logging,java.naming,java.security.jgss
            //     httpcore 4.4.13             → java.base
            //     commons-codec 1.15          → java.base
            //     commons-logging 1.2         → java.base,java.logging
            //     gson 2.9.0                  → java.base,java.sql            （Gson 默认注册 java.sql.Date/Time 适配器）
            //     shaded-protobuf 2.4.6       → java.base,java.logging,jdk.unsupported
            //   取并集并去掉 java.base（恒含）与 jdk.unsupported（上方已声明），补齐以下 5 个。
            //   🔴 精确补（不用 includeAllModules=true，避免安装包无谓膨胀）；宁可多补不漏（缺 = 运行期崩溃）。
            //   ⚠️ 本注释刻意用「飞书 SDK」而非坐标连字符写法，避免被 LarkSdkIsolationTest A-1
            //      （原文扫全仓 build 脚本、正则 oapi[.-]sdk、不归一化注释）误判为「在此声明 SDK 依赖」。
            modules(
                "java.naming",          // 飞书 SDK + httpclient（JNDI/DNS 解析）
                "java.security.jgss",   // 飞书 SDK + httpclient（SPNEGO/Kerberos 认证）
                "java.sql",             // 飞书 SDK + gson（java.sql.Date/Time/Timestamp 类型适配器）
                "java.logging",         // httpclient + commons-logging + shaded-protobuf（java.util.logging）
                "java.compiler",        // 飞书 SDK（javax.tools/注解处理引用）
            )

            // 🔴 AI 能力域（2026-09-12）：Koog 1.2.0 细粒度链（agents-core / prompt-executor-* /
            //    http-client-java）+ 其传递的 Ktor 3.3.3 组件所需 JDK 模块。依据 = 对
            //    ~/.gradle 缓存中全部 22 个 ai.koog jar 与 Ktor 3.3.3 全家逐 jar 跑
            //    jdeps --print-module-deps --ignore-missing-deps --multi-release 21 -cp <完整 jvmRuntimeClasspath>：
            //      ai.koog 全家            → java.management, java.net.http, jdk.unsupported
            //      io.ktor 3.3.3 全家      → java.instrument, java.management, jdk.unsupported
            //    取并集去掉 java.base 与已声明项（java.net.http / jdk.unsupported 上方已有）⇒ 补以下 2 个。
            //    判据（与飞书 SDK 段同口径）：安装包内「测连通性 + 一次完整解密」不得出现
            //    NoClassDefFoundError / TLS 握手失败。
            modules(
                "java.management",      // Ktor 指标/JMX 探测引用（InstrumentationRegistry 等）
                "java.instrument",      // Ktor 的 javaagent 探测（Instrumentation 接口）
            )

            windows {
                iconFile.set(project.file("icons/app.ico"))
                shortcut = true // 是否创建开始菜单快捷方式
                perUserInstall = true
                // 🔴 MSI UpgradeCode：自动升级链（major upgrade=卸旧装新）的锚，一旦发布过
                //    任何带此值的版本即永不更改，改值=存量用户全部退回手动卸载状态。
                //    不显式钉死时缺省由 vendor+packageName 派生（UUID.nameUUIDFromBytes），
                //    品牌更名/改 vendor 都会令派生值漂移断链——UpgradeCode 是 Upgrade 表
                //    唯一检索键，Windows Installer 按 UpgradeCode+版本区间识别旧产品并
                //    RemoveExistingProducts，与产品名无关。
                upgradeUuid = "E29122C8-FB03-4331-BFC7-7287B341105E"
            }

            macOS {
                iconFile.set(project.file("icons/app.icns"))
                // 🔴 2026-09-17：JDK 25 jpackage 新增 bundleID 强校验（MacApplicationBuilder.isValidBundleIdentifier
                //    白名单 [A-Za-z0-9-.]，禁下划线），旧值 com.jiaoay.easy_dev_toolbox 在 mac CI 硬失败
                //    （v0.1.2 两个 mac job 全挂在 createReleaseDistributable）。JDK 21 时代无此校验故此前未暴露。
                //    与 Kotlin 包名无需一致；Apple 官方逆 DNS 推荐只用字母数字+点，hyphen 不取。
                //    本地防回退：MacBundleIdGuardTest 用同款白名单正则钉死此值。
                //    2026-09-19 P0 品牌批：EasyDevToolbox 更名 Toolbelt，bundleID 同步。
                bundleID = "com.jiaoay.toolbelt"
            }

            linux {
                iconFile.set(project.file("icons/app.png"))
            }
        }

        buildTypes.release.proguard {
            isEnabled = false
        }
    }
}

// guardTests：arch 守护面快速反馈通道——只跑 src/jvmTest 的 arch/ 守护测试，
// 排除 *SceneGuardTest 场景渲染类（skiko 渲染依赖，是全量 jvmTest 的最重最慢段）。
// 内环「改一点 → 秒级看守护红不红」用它；改 UI/注释后的全量验收门槛仍是
// :composeApp:jvmTest（快速任务是内环增益，不替代门槛）。
tasks.register<Test>("guardTests") {
    group = "verification"
    description = "只跑 arch/ 守护测试（排除 *SceneGuardTest 场景渲染类）——内环快速反馈，全量验收仍走 :composeApp:jvmTest"
    val jvmTestTask = tasks.named<Test>("jvmTest")
    testClassesDirs = jvmTestTask.get().testClassesDirs
    classpath = jvmTestTask.get().classpath
    filter {
        includeTestsMatching("com.jiaoay.easy_dev_toolbox.arch.*")
        excludeTestsMatching("*SceneGuardTest")
    }

    // 扫盘任务输入盲区机械防线：被扫源码不在本 task 声明输入里（跨模块源码变更不进
    // cache key），up-to-date/FROM-CACHE 均可能假绿——永跑语义：不入 up-to-date、不入 build cache，
    // 每次调用都真实执行（34 秒级，内环可承受；全量验收铁律不变：--rerun-tasks --no-build-cache）。
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
