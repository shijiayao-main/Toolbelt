package com.jiaoay.easy_dev_toolbox.arch

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 桥接数据根 CLI/App 同根契约测试。
 *
 * 守护目标：`:lark-bridge` CLI 缺省数据根（`CliSupport.defaultDataRoot()` 的字面量拼写）
 * 与 App 侧注入根（`:core` `FileUtils.getLarkDir()`）是**两处独立拼写**——lib 是
 * jvmTarget 11 纯 JVM 库不得依赖 `:core`（无法共享常量），漂移即静默分裂：App 与 CLI
 * 各读各的配置文档，单实例锁互不互斥。本测试把 CLI 字面量钉死在 App 访问器的派生值上，
 * 任一侧单方改动即红。
 *
 * 实现取径：CLI 侧 `defaultDataRoot` 是 internal（跨模块不可见），无法运行时断言——
 * 故扫 CliSupport.kt 源码提取字面量，与 `FileUtils.getLarkDir()` 的实际派生路径比对
 * （两侧同以真实 user.home 为基，路径全等即同根）。
 *
 * 🔴 扩展契约：CliSupport 缺省根构造形态改变（不再是
 * `File(System.getProperty("user.home"), "<字面量>")`）时，**先**改本测试的提取正则再落代码。
 */
class LarkCliDataRootParityTest {

    @Test
    fun `CLI 缺省数据根与 App 注入根路径全等`() {
        val root = locateProjectRoot()
        val cliSupport = File(
            root,
            "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/cli/CliSupport.kt",
        )
        assertTrue(cliSupport.isFile, "CliSupport.kt 不在锚定路径（文件被移动？先改本测试锚）")
        val cliChild = DEFAULT_ROOT_PATTERN.find(cliSupport.readText())?.groupValues?.get(1)
            ?: fail("CliSupport.kt 未找到缺省数据根构造形态（defaultDataRoot 漂移？先改本测试正则）")
        val cliDefaultRoot = File(System.getProperty("user.home"), cliChild)
        val appInjectedRoot = FileUtils.getLarkDir()
        assertEquals(
            appInjectedRoot.path,
            cliDefaultRoot.path,
            "桥接数据根两处拼写漂移：CLI 缺省根（$cliChild）≠ App 注入根" +
                "（${appInjectedRoot.path}）——App 与 CLI 会各读各的 bridge-config.json、" +
                "单实例锁互不互斥；两处必须同 commit 同步改（lib 不能依赖 :core，无编译期保障）",
        )
    }

    private companion object {

        /** CliSupport 缺省根构造形态：`File(System.getProperty("user.home"), "<字面量>")` */
        val DEFAULT_ROOT_PATTERN =
            Regex("""File\(System\.getProperty\("user\.home"\), "([^"]+)"\)""")
    }
}
