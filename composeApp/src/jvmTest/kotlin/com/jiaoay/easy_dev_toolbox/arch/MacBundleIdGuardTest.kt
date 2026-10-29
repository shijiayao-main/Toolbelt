// S0 · macOS bundleID 守护：JDK 25 jpackage 的 bundleID 白名单校验在本地（Linux/Windows）不会执行，
// 只在 mac runner 的 createReleaseDistributable 上才炸——v0.1.2（2026-09-17）两个 mac job
// 全挂于此（invalid mac bundle identifier [com.jiaoay.easy_dev_toolbox]，下划线不在白名单）。
// 本测试把 jpackage 的白名单正则前移到本地 jvmTest，改回非法值时本地先红，不用等 mac CI。
// 白名单源 = JDK 25 MacApplicationBuilder.isValidBundleIdentifier（字符级 [A-Za-z0-9-.]），
// 与 jdk.jpackage 内部 StandardValidator.IS_VALID_MAC_BUNDLE_IDENTIFIER 同款语义。
// 🔴 验收纪律：一律 --rerun-tasks --no-build-cache（AGENTS.md「测试」节，扫盘型守护防 build cache 假绿）。
package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacBundleIdGuardTest {

    /**
     * composeApp/build.gradle.kts 里的 macOS bundleID 必须匹配 JDK 25 jpackage 白名单
     * [A-Za-z0-9-.]（全串锚定）。下划线、空串、空白等一律拒绝——与 mac CI 的
     * MacApplicationBuilder.validatedBundleIdentifier 判定口径一致。
     */
    @Test
    fun `macOS bundleID matches jpackage whitelist regex`() {
        val root = locateProjectRoot()
        val buildScript = File(root, "composeApp/build.gradle.kts")
        assertTrue(buildScript.isFile, "composeApp/build.gradle.kts 缺失——项目根定位异常")

        val text = buildScript.readText()
        val bundleId = bundleIdRegex.find(text)?.groupValues?.get(1)
            ?: error("composeApp/build.gradle.kts 中未找到 bundleID = \"...\" 声明（nativeDistributions.macOS 段）")

        // 自检：防护面非空（正则锚对了位置而不是恰好空转）
        assertTrue(bundleId.isNotBlank(), "bundleID 为空串")

        assertEquals(
            true,
            whitelistRegex.matches(bundleId),
            "macOS bundleID [$bundleId] 含白名单 [A-Za-z0-9-.] 之外字符——" +
                "JDK 25 jpackage 会在 mac CI 的 createReleaseDistributable 上以 " +
                "ConfigException: invalid mac bundle identifier 硬失败（v0.1.2 事故复盘）",
        )
    }

    /** 白名单正则自证：已知非法样本必须红、合法样本必须绿（防止本守护自身漂移）。 */
    @Test
    fun `whitelist regex rejects underscores and accepts known-good identifiers`() {
        // 非法（JDK 25 jpackage 拒绝）
        assertEquals(false, whitelistRegex.matches("com.jiaoay.easy_dev_toolbox"), "下划线必须被拒")
        assertEquals(false, whitelistRegex.matches("com.example.app name"), "空格必须被拒")
        assertEquals(false, whitelistRegex.matches(""), "空串必须被拒")
        // 合法
        assertEquals(true, whitelistRegex.matches("com.jiaoay.toolbelt"), "现 bundleID 必须合法")
        assertEquals(true, whitelistRegex.matches("com.jiaoay.easy-dev-toolbox"), "hyphen 技术上合法（Apple 不推荐，另议）")
    }


    private companion object {
        // bundleID = "..."（composeApp/build.gradle.kts 的 nativeDistributions.macOS DSL）
        val bundleIdRegex = Regex("""bundleID\s*=\s*"([^"]*)"""")

        // JDK 25 jpackage 白名单（MacApplicationBuilder.isValidBundleIdentifier 的字符级等价正则）
        val whitelistRegex = Regex("""^[A-Za-z0-9.-]+$""")
    }
}
