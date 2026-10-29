import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
    id("easydevtoolbox.jvm.cli")
}

// :adb-core —— 内置 adb 的二进制分发与命令改写 lib（jvmTarget 11 纯 JVM）。
//
// 职责三件：从随包资源释放平台 adb 到用户数据目录、按「用户配置 > 内置 > PATH」解析端点、
// 把字面 `adb ...` 命令改写成绝对路径 + 设备串号。零宿主依赖（不引用 :core / :ui）——
// 数据目录、日志、进程执行全部由调用方注入，故本模块可被任意 JVM 项目复用。
//
// 🔴 包名 com.jiaoay.toolbelt.adb 而非 com.jiaoay.easy_dev_toolbox.*：有 src/main/kotlin
//    源集的模块会被 ScanSurfaceSelfCheckTest 自动判定为 jvm11 lib，而 LarkSdkIsolationTest
//    的 D 组禁止 lib 引用宿主包前缀（宿主 jvmTarget 25，反向依赖编译不过）。
//
// 二进制不入库：构建期下载官方 platform-tools zip，只解出 adb（Windows 加两个 DLL）与
// NOTICE.txt，落 src/main/resources/adb/<平台>/ 随 jar 打包（.gitignore 已忽略）。

// ── 版本与校验（唯一真源）──────────────────────────────────────────────────
// 钉「版本化 URL」而非 latest：latest 的内容会随官方更新静默变化，校验和随之失效。
// 版本化 URL 的资产名用缩写 win，与 latest 链接的全称 windows 不同。
val platformToolsVersion = "37.0.1"

/** 平台 → (下载资产键, 随包资源目录名, 需解出的文件清单)。资源目录名见 [com.jiaoay.toolbelt.adb.AdbPlatform]。 */
val adbAssets: Map<String, Triple<String, String, List<String>>> = mapOf(
    "windows" to Triple(
        "win",
        "windows",
        listOf("adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll", "NOTICE.txt"),
    ),
    "linux" to Triple("linux", "linux", listOf("adb", "NOTICE.txt")),
    "darwin" to Triple("darwin", "darwin", listOf("adb", "NOTICE.txt")),
)

/** zip 整包 sha256（含全部 platform-tools 资产，不只 adb）——锁定下载内容与版本一致。 */
val adbZipSha256: Map<String, String> = mapOf(
    "windows" to "45f4d63113e895ebde0c90f194099a4676b6ac653bd28d54314a9e022bbc1a99",
    "linux" to "d230f13842f60f782a8645f9c813f8f845bf36089ea7289f28c48f17979313f1",
    "darwin" to "ee39ad5967e95c2a07f04dbcbde96b1a0c916ba376096db5d2f498b7727a5d1d",
)

/**
 * 下载源：官方主源 + 两个镜像兜底。
 *
 * 官方会清理旧版本化链接（过时版本的资产会 404），单一源会让历史版本的构建直接失败；
 * 镜像与官方逐字节一致，sha256 校验保证任一源取回的内容等价。
 */
val adbDownloadBases: List<String> = listOf(
    "https://dl.google.com/android/repository",
    "https://googledownloads.cn/android/repository",
    "https://mirrors.cloud.tencent.com/AndroidSDK",
)

// 构建机平台键：配置期读系统属性，属性读取进配置缓存指纹（跨平台共享 .gradle 目录时不会
// 误用上一平台产物）。不捕获 Project。
val adbHostPlatform: String = run {
    val osName = providers.systemProperty("os.name").getOrElse("").lowercase()
    when {
        osName.contains("win") -> "windows"
        osName.contains("mac") || osName.contains("darwin") -> "darwin"
        osName.contains("linux") -> {
            val arch = providers.systemProperty("os.arch").getOrElse("").lowercase()
            // 官方不发布 Linux arm64 包；该平台的构建期直接失败，而不是产出一个
            // 运行期才发现 adb 缺失的安装包。
            if (arch !in setOf("amd64", "x86_64")) {
                error(
                    "内置 adb 不支持 Linux $arch：官方 platform-tools 只发布 x86-64 归档，" +
                        "且不提供 Linux arm64 版本。",
                )
            }
            "linux"
        }
        else -> error("内置 adb 不支持当前操作系统：os.name=$osName")
    }
}

// ── 下载并解出资源 ─────────────────────────────────────────────────────────
// 逻辑全内联在 doLast：引用 build script 顶层具名函数会捕获脚本实例 → 序列化 Project，
// 配置缓存直接失败。未标 @Cacheable——zip 十余 MB，不往 build cache 里存。
val prepareBundledAdb = tasks.register("prepareBundledAdb") {
    group = "build"
    description = "下载 platform-tools 并解出当前平台的 adb 到 src/main/resources/adb/"

    val asset = adbAssets.getValue(adbHostPlatform)
    val assetKey = asset.first
    val resourceDir = asset.second
    val wantedFiles = asset.third
    val zipName = "platform-tools_r$platformToolsVersion-$assetKey.zip"
    val expectedZipSha256 = adbZipSha256.getValue(adbHostPlatform)
    val bases = adbDownloadBases
    val version = platformToolsVersion
    val platformKey = adbHostPlatform

    val outDir: File = File(projectDir, "src/main/resources/adb/$resourceDir")
    val workDir: File = File(projectDir, "build/adb-download")
    outputs.dir(outDir)
    inputs.property("version", version)
    inputs.property("platform", platformKey)
    inputs.property("zipSha256", expectedZipSha256)

    doLast {
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
            return md.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        // 已就位的快速跳过：清单齐全且清单内版本为当前版本即视为完成（内容校验由运行期
        // 释放器的首验承担，构建期不重复算 10MB 哈希）。
        val manifestFile = File(outDir, "manifest.properties")
        if (manifestFile.isFile &&
            manifestFile.readText().contains("version=$version") &&
            wantedFiles.all { File(outDir, it).isFile }
        ) {
            logger.lifecycle("内置 adb 资源已就位（$platformKey v$version），跳过下载")
            return@doLast
        }

        val work = workDir
        work.mkdirs()
        val zip = File(work, zipName)
        val part = File(work, "$zipName.part")

        if (!(zip.isFile && sha256Of(zip) == expectedZipSha256)) {
            var downloaded = false
            var lastError: String? = null
            outer@ for (base in bases) {
                val url = URL("$base/$zipName")
                logger.lifecycle("下载 platform-tools $version（$platformKey）：$url")
                try {
                    var current = url
                    for (hop in 1..5) {
                        val conn = current.openConnection() as HttpURLConnection
                        conn.instanceFollowRedirects = false
                        conn.connectTimeout = 30_000
                        conn.readTimeout = 300_000
                        when (conn.responseCode) {
                            in 300..399 -> {
                                val loc = conn.getHeaderField("Location")
                                conn.disconnect()
                                if (loc.isNullOrBlank()) {
                                    lastError = "$url: 重定向缺少 Location"
                                    continue@outer
                                }
                                current = URL(loc)
                            }
                            200 -> {
                                part.outputStream().use { out ->
                                    conn.inputStream.use { input -> input.copyTo(out) }
                                }
                                conn.disconnect()
                                val actual = sha256Of(part)
                                if (actual != expectedZipSha256) {
                                    part.delete()
                                    lastError = "$url: sha256 不符（实际 $actual）"
                                    continue@outer
                                }
                                part.renameTo(zip)
                                downloaded = true
                                break@outer
                            }
                            else -> {
                                lastError = "$url: HTTP ${conn.responseCode}"
                                conn.disconnect()
                                continue@outer
                            }
                        }
                    }
                    lastError = "$url: 重定向超过 5 跳"
                } catch (e: Exception) {
                    lastError = "$url: ${e.message}"
                }
            }
            check(downloaded) {
                "platform-tools $version（$platformKey）下载失败：$lastError。" +
                    "可手动下载 $zipName 放到 adb-core/build/adb-download/ 后重试" +
                    "（手填说明见 core/libs/adb/README.md）。"
            }
        }

        outDir.mkdirs()
        outDir.listFiles()?.forEach { if (it.isFile) it.delete() }
        ZipInputStream(zip.inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name !in wantedFiles) {
                    zin.closeEntry()
                    continue
                }
                File(outDir, name).outputStream().use { out -> zin.copyTo(out) }
                zin.closeEntry()
            }
        }

        val missing = wantedFiles.filterNot { File(outDir, it).isFile }
        check(missing.isEmpty()) { "解包后缺少文件：$missing（zip 结构可能已变）" }

        // Unix 可执行位：zip 流不携带权限，jar 内资源同理——权限只能在释放到磁盘时补。
        if (platformKey != "windows") {
            val adb = File(outDir, "adb")
            if (!adb.setExecutable(true, false)) {
                logger.warn("无法为 ${adb.name} 设置可执行位（构建产物权限可能受限）")
            }
        }

        // 资源清单：运行期释放器据此得知版本、文件集合与逐文件校验值。版本同时充当
        // 构建期戳（本任务据此跳过已就位产物）——清单是唯一真源，不另留标记文件。
        val manifest = buildString {
            appendLine("version=$version")
            appendLine("platform=$platformKey")
            appendLine("files=${wantedFiles.joinToString(",")}")
            for (name in wantedFiles) {
                appendLine("sha256.$name=${sha256Of(File(outDir, name))}")
            }
        }
        File(outDir, "manifest.properties").writeText(manifest)
        logger.lifecycle("内置 adb 资源就绪：$outDir（$platformKey v$version）")
    }
}

// 资源必须随 jar 打包：编译前保证资源在位，运行期由释放器按 classloader 读取。
tasks.matching { it.name == "processResources" || it.name == "jvmProcessResources" }
    .configureEach { dependsOn(prepareBundledAdb) }
tasks.matching { it.name == "compileKotlin" || it.name == "compileTestKotlin" }
    .configureEach { dependsOn(prepareBundledAdb) }

dependencies {
    implementation(libs.kotlinx.coroutinesCore)
}
