// Windows 升级链守护：MSI 的自动 major upgrade（卸旧装新）依赖两个锚——
// upgradeUuid（UpgradeCode，Upgrade 表唯一检索键）与随发布递增的版本号。
// 任一失效的症状都一样：Windows Installer 报 1638，要求用户先手动卸载旧版。
// 本测试把这两个锚前移到本地 jvmTest，改回断链形态时本地先红，不用等 Windows CI/真机。
// 🔴 验收纪律：一律 --rerun-tasks --no-build-cache（AGENTS.md「测试」节，扫盘型守护防 build cache 假绿）。
package com.jiaoay.easy_dev_toolbox.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsUpgradeUuidGuardTest {

    /**
     * windows.upgradeUuid 必须钉死为既定 GUID。此值即 MSI UpgradeCode：
     * 一旦发布过任何带此值的版本即永不更改（改值=存量用户全部退回手动卸载状态），
     * 故用常量断言防手滑改错或删除，而非只验格式。
     */
    @Test
    fun `windows upgradeUuid is pinned to the release UpgradeCode`() {
        val text = buildScript().readText()
        val upgradeUuid = upgradeUuidRegex.find(text)?.groupValues?.get(1)
        assertNotNull(upgradeUuid, "composeApp/build.gradle.kts 的 windows 块缺 upgradeUuid 声明——" +
            "缺省 UpgradeCode 由 vendor+packageName 派生，品牌更名即漂移断链，Windows 升级退回手动卸载态")
        assertEquals(EXPECTED_UPGRADE_UUID, upgradeUuid, "upgradeUuid 与既定发布值不符——" +
            "🔴 此值一经发布即属契约（Upgrade 表唯一检索键），不得更改；如需换值必须先全员卸载旧版")
    }

    /**
     * packageVersion 必须由 Gradle 属性驱动，不得回退为硬编码字面量。
     * jpackage 的 ProductCode=hash(vendor,appName,version)，版本号不随发布递增时
     * Windows Installer 报 1638 拒绝安装（同 ProductCode 重装）。
     */
    @Test
    fun `packageVersion is driven by gradle property not a frozen literal`() {
        val text = buildScript().readText()
        assertTrue(
            frozenVersionRegex.containsMatchIn(text).not() && propertyDrivenVersionRegex.containsMatchIn(text),
            "packageVersion 应为 providers.gradleProperty(\"packageVersion\") 属性驱动——" +
                "硬编码字面量会令每次发布的 MSI 版本号相同，Windows Installer 按同 ProductCode 拒绝安装（1638）",
        )
    }

    /**
     * perUserInstall 必须恒为 true。MS 明文：Windows Installer 不跨安装上下文执行
     * major upgrade；若某版改为 per-machine，此前 per-user 安装的用户升级时将并存
     * 两份应用而非卸旧装新。
     */
    @Test
    fun `perUserInstall stays true across releases`() {
        val text = buildScript().readText()
        val match = perUserInstallRegex.find(text)
        assertNotNull(match, "windows 块缺 perUserInstall 声明")
        assertEquals("true", match.groupValues[1], "perUserInstall 必须恒为 true——" +
            "Windows Installer 不跨安装上下文做 major upgrade，翻转此值会令升级静默失效（新旧并存）")
    }

    private fun buildScript(): File {
        val root = locateProjectRoot()
        val script = File(root, "composeApp/build.gradle.kts")
        assertTrue(script.isFile, "composeApp/build.gradle.kts 缺失——项目根定位异常")
        return script
    }

    private companion object {
        // 🔴 已发布的 Windows UpgradeCode（MS 语义：大写 GUID）。永不再改。
        const val EXPECTED_UPGRADE_UUID = "E29122C8-FB03-4331-BFC7-7287B341105E"

        // windows 块内的 upgradeUuid = "..."（nativeDistributions.windows DSL）
        val upgradeUuidRegex = Regex("""upgradeUuid\s*=\s*"([^"]*)"""")
        val frozenVersionRegex = Regex("""packageVersion\s*=\s*"1\.0\.0"""")
        val propertyDrivenVersionRegex = Regex(
            """packageVersion\s*=\s*providers\.gradleProperty\("packageVersion"\)\.getOrElse\("1\.0\.0"\)""",
        )
        val perUserInstallRegex = Regex("""perUserInstall\s*=\s*(\w+)""")
    }
}
