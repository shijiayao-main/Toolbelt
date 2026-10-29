import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

plugins {
    id("easydevtoolbox.kmp.feature")
}

// 语音转写 feature（P4-S openspec voice-transcribe）：拖音频 → sherpa-onnx 离线转写
// → 转写原文 → AI 整理纪要（半显身层）。
//
// 🔴 sherpa-onnx 依赖形态（design D1 → 2026-09-20 出库翻转）：GitHub release 预编译
// jar 经本地 libs 目录引入（files(...) 直引，非 Maven 坐标——不入 libs.versions.toml；
// 否决 jitpack：网络首构 404 不稳，否决 mavenLocal：机器间不可复现）。
// jar 本体**不入库**（.gitignore：feature-voice/libs/*.jar）——缺失/损坏时由下方
// downloadSherpaJars 任务构建期自动下载 + sha256 校验（来历见 libs/README.md）。
// classpath 只挂「jvm 主件 + 当前构建平台的 native 件」：四平台全挂会让每个安装包
// 白背约 28MB 非本平台二进制；缺本平台 native 件则在配置期直接失败，不产出语音
// 运行期才炸（LibraryLoader 找不到 so/dll）的静默失效包。
// 🔴 com.k2fsa.sherpa.onnx.* 全仓只许出现在 SherpaTranscriber.kt（隔离守护测试 3.3 钉死）。

// jar 清单（唯一真源）：文件名 → sha256。声明在 kotlin {} 之前（Kotlin DSL 顺序求值）。
// CC 安全：任务体只捕获本表与 java.io.File/String 常量，不捕获 Project。
val sherpaJars = mapOf(
    "sherpa-onnx-jvm-1.13.8.jar" to "77b7b047fade4eadada96b568eb92615049aaf1dc317c7244e46c1ea38b9a63b",
    "sherpa-onnx-native-lib-linux-x64-1.13.8.jar" to "30c93b59381113f9c20aedbbf9fc1ad399158f6bc03dddc0f8934a6e28e069ba",
    "sherpa-onnx-native-lib-osx-aarch64-1.13.8.jar" to "42e272180c8836127f024f3335d7afcdfb30fe0164b78330d5d449034e34ce34",
    "sherpa-onnx-native-lib-osx-x64-1.13.8.jar" to "9190c28951d85efdbd376bae6b6dff12993311ad605945289e8459bf9c886a96",
    "sherpa-onnx-native-lib-win-x64-1.13.8.jar" to "33fbdbd5410e9ba9bdda94aa164ec8f7825bb49246420d8ce9bdd88219d97039",
)
val sherpaBaseUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8"

// 文件名 → 平台键（"<os>-<arch>"，与 release 资产命名同构）；键缺省 = 平台无关件（jvm 主件）。
// 平台键是 sherpa release 的实际资产集合，不是任意组合：linux-arm64 / win-arm64 无对应资产。
val sherpaJarPlatforms = mapOf(
    "sherpa-onnx-native-lib-linux-x64-1.13.8.jar" to "linux-x64",
    "sherpa-onnx-native-lib-osx-aarch64-1.13.8.jar" to "osx-aarch64",
    "sherpa-onnx-native-lib-osx-x64-1.13.8.jar" to "osx-x64",
    "sherpa-onnx-native-lib-win-x64-1.13.8.jar" to "win-x64",
)
val sherpaSupportedPlatforms = sherpaJarPlatforms.values.toSet()
val sherpaNativeJars = sherpaJars.keys.filter { "native-lib" in it }

// native 件必须逐个登记平台键：漏登记会让该件对所有平台静默全挂或全丢。
run {
    val unregistered = sherpaNativeJars.filter { it !in sherpaJarPlatforms }
    if (unregistered.isNotEmpty()) {
        error("sherpa native jar 未登记平台归属：$unregistered —— 在 sherpaJarPlatforms 补 (文件名 → 平台键)")
    }
}

// 当前构建机平台键：经 providers.systemProperty 在配置期取值——属性读取被 Gradle 记入
// 配置缓存指纹，换平台（如跨平台共享 .gradle 目录、CI 矩阵复用）时缓存失效重算，不会
// 沿用上一个平台的 jar 清单。不捕获 Project（配置缓存安全）。
// 归一化口径对齐 sherpa 资产命名（JVM 的 amd64/x86_64 → x64，arm64/aarch64 → aarch64）。
// 不在资产集合内（如 linux-arm64）即配置期失败：缺本平台 native 件会产出语音运行期
// 才失效的安装包，宁可构建期红。
val hostPlatformKey: String = run {
    val osName = providers.systemProperty("os.name").getOrElse("").lowercase()
    val os = when {
        osName.contains("win") -> "win"
        osName.contains("mac") || osName.contains("darwin") -> "osx"
        osName.contains("linux") -> "linux"
        else -> error("语音转写不支持当前操作系统：os.name=$osName（sherpa-onnx 仅发布 $sherpaSupportedPlatforms 的 native 资产）")
    }
    val archName = providers.systemProperty("os.arch").getOrElse("").lowercase()
    val arch = when (archName) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "aarch64"
        else -> error("语音转写不支持当前 CPU 架构：os.arch=$archName（sherpa-onnx 仅发布 $sherpaSupportedPlatforms 的 native 资产）")
    }
    val key = "$os-$arch"
    if (key !in sherpaSupportedPlatforms) {
        error("语音转写不支持当前平台：$key（sherpa-onnx 仅发布 $sherpaSupportedPlatforms 的 native 资产）")
    }
    key
}

// classpath 清单按构建平台过滤：jvm 主件恒挂，native 件只挂当前平台一件
// （四平台全挂会让每个安装包白背约 28MB 非本平台二进制）。
val sherpaClasspathJars: List<String> = sherpaJars.keys
    .filter { jar -> jar !in sherpaNativeJars || sherpaJarPlatforms[jar] == hostPlatformKey }
    .map { "libs/$it" }

// 缺 jar 自动补齐：纯 JVM 下载（HttpURLConnection 手动跟随跨域 302，≤5 跳）+ sha256
// 校验 + .part 临时文件原子落盘。up-to-date = 5 文件内容哈希全对（Gradle 内容级指纹），
// 正常构建零网络。未标 @Cacheable：org.gradle.caching=true 不会把 39MB jar 存进 build
// cache（CI 不膨胀）——有意为之。
val downloadSherpaJars = tasks.register("downloadSherpaJars") {
    val libsDir: File = File(projectDir, "libs")
    val targets: List<Pair<File, String>> = sherpaJars.map { File(libsDir, it.key) to it.value }
    val base: String = sherpaBaseUrl
    outputs.files(targets.map { it.first })
    inputs.property("sha256s", sherpaJars)
    doLast {
        // 🔴 CC 红线：禁止调用 build script 顶层具名 fun（顶层 fun 属脚本类成员，引用即
        //    捕获脚本实例 → 序列化 Project → 配置缓存炸）。辅助逻辑全部内联本 doLast。
        fun sha256Of(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s ->
                val b = ByteArray(1 shl 20)
                while (true) {
                    val n = s.read(b)
                    if (n < 0) break
                    md.update(b, 0, n)
                }
            }
            return md.digest().joinToString("") { b -> "%02x".format(b) }
        }
        for ((jar, expected) in targets) {
            if (jar.isFile && sha256Of(jar) == expected) continue
            logger.lifecycle("sherpa jar 补齐: ${jar.name}（缺失或 sha256 不符,从 v1.13.8 release 下载）")
            jar.parentFile.mkdirs()
            var url = URL("$base/${jar.name}")
            val part = File(jar.parentFile, jar.name + ".part")
            var connected = false
            for (hop in 1..5) {
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 30_000
                conn.readTimeout = 300_000
                when (conn.responseCode) {
                    in 300..399 -> {
                        val loc = conn.getHeaderField("Location")
                        conn.disconnect()
                        url = URL(loc)
                    }
                    200 -> {
                        part.outputStream().use { out -> conn.inputStream.use { s -> s.copyTo(out) } }
                        conn.disconnect()
                        connected = true
                        break
                    }
                    else -> error("${jar.name}: HTTP ${conn.responseCode}（下载源不可用?见 feature-voice/libs/README.md）")
                }
            }
            check(connected) { "${jar.name}: 下载未完成（重定向 >5 跳或连接中断）" }
            val actual = sha256Of(part)
            if (actual != expected) {
                part.delete()
                error("${jar.name}: sha256 不符（下载源损坏?）——手动补齐见 feature-voice/libs/README.md")
            }
            check(part.renameTo(jar)) { "${jar.name}: rename 失败" }
        }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // 精确文件清单直引（不 fileTree，防杂 jar 混入）；builtBy 附带下载任务。
            // 清单来自按平台过滤后的 sherpaClasspathJars——jvm 主件 + 本平台 native 件。
            // 🔴 字面量只许出现在本文件（SherpaOnnxIsolationTest 白名单钉死）。
            implementation(files(sherpaClasspathJars).builtBy(downloadSherpaJars))
            // 模型包 tar.bz2 流式解压（design D3——feature-archive 同款「只进需要的 feature」纪律）
            implementation(libs.commons.compress)
        }
    }
}

// 主保险：feature-voice 编译必先于一切消费方（composeApp run/jvmTest/jpackage 链），
// 编译前必有 jar 在位。惰性 matching——禁用 tasks.named（急切解析,KGP 注册时序不利时炸）。
tasks.matching { it.name == "compileKotlinJvm" }.configureEach {
    dependsOn(downloadSherpaJars)
}
