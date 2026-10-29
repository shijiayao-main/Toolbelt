package com.jiaoay.easy_dev_toolbox.page.screencast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 部署命令整形单测：命令形态与官方客户端组装序逐字对齐（含版本首参 / scid 十六进制 /
 * tunnel_forward 形态 / 可选参数序），并验证 AdbCommandRewriter 注入（自定义 adb 路径与串号）。
 */
class ScrcpyDeployCommandTest {

    private val socketName = ScrcpyProtocol.deviceSocketName(0x1A2B3C4D)

    @Test
    fun `push command targets device fixed path with quoted local jar path`() {
        // 本地路径含空白（Windows 用户目录默认形态）时裸拼即令 adb 断词——恒双引号包裹
        assertEquals(
            "adb push \"/home/u/scrcpy-server-v5.0.1\" /data/local/tmp/scrcpy-server.jar",
            ScrcpyServerDeployer.buildPushCommand("/home/u/scrcpy-server-v5.0.1"),
        )
        assertEquals(
            "adb push \"/home/u/My Toolbelt Dir/scrcpy-server-v5.0.1\" /data/local/tmp/scrcpy-server.jar",
            ScrcpyServerDeployer.buildPushCommand("/home/u/My Toolbelt Dir/scrcpy-server-v5.0.1"),
        )
    }

    @Test
    fun `tunnel commands pair with remove cleanup commands`() {
        assertEquals(
            "adb forward tcp:27183 localabstract:$socketName",
            ScrcpyServerDeployer.buildForwardCommand(27183, socketName),
        )
        assertEquals(
            "adb reverse localabstract:$socketName tcp:27183",
            ScrcpyServerDeployer.buildReverseCommand(27183, socketName),
        )
        assertEquals(
            "adb forward --remove tcp:27183",
            ScrcpyServerDeployer.buildRemoveForwardCommand(27183),
        )
        assertEquals(
            "adb reverse --remove localabstract:$socketName",
            ScrcpyServerDeployer.buildRemoveReverseCommand(socketName),
        )
    }

    @Test
    fun `serve command matches official shape with control channel enabled`() {
        assertEquals(
            "adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar" +
                " app_process / com.genymobile.scrcpy.Server 5.0.1" +
                " scid=1a2b3c4d tunnel_forward=true control=true audio=false clipboard_autosync=false",
            ScrcpyServerDeployer.buildServeCommand(scid = 0x1A2B3C4D, tunnelForward = true),
        )
        // 控制通道可关（只读形态回退）
        assertTrue(
            "control=false" in ScrcpyServerDeployer.buildServeCommand(
                scid = 0x1A2B3C4D,
                tunnelForward = true,
                control = false,
            ),
        )
    }

    @Test
    fun `serve command reflects reverse tunnel form`() {
        val command = ScrcpyServerDeployer.buildServeCommand(scid = 1L, tunnelForward = false)
        assertTrue("scid=00000001 tunnel_forward=false" in command)
        // socket 名与命令 scid 必须同源派生
        assertEquals("scrcpy_00000001", ScrcpyProtocol.deviceSocketName(1L))
    }

    @Test
    fun `serve command appends optional load shaping params in order`() {
        val command = ScrcpyServerDeployer.buildServeCommand(
            scid = 2L,
            tunnelForward = true,
            displayId = 2,
            maxSize = 1280,
            maxFps = 30.0f,
            videoBitRate = 8_000_000L,
        )
        assertTrue(command.endsWith(" display_id=2 max_size=1280 max_fps=30.0 video_bit_rate=8000000"))
        // 可选参数缺省时不追加尾巴
        val bare = ScrcpyServerDeployer.buildServeCommand(scid = 2L, tunnelForward = true)
        assertTrue(!bare.contains("display_id"))
        assertTrue(bare.endsWith("control=true audio=false clipboard_autosync=false"))
    }

    @Test
    fun `rewriter injects custom adb path and serial into deploy commands`() {
        val customPath = "/opt/android-platform-tools/adb"
        for (command in listOf(
            ScrcpyServerDeployer.buildPushCommand("/tmp/scrcpy-server-v5.0.1"),
            ScrcpyServerDeployer.buildForwardCommand(27183, socketName),
            ScrcpyServerDeployer.buildReverseCommand(27183, socketName),
            ScrcpyServerDeployer.buildServeCommand(scid = 1L, tunnelForward = true),
        )) {
            val rewritten = com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter.rewrite(
                command, adbPath = customPath, serial = "EMU1", windows = false,
            )
            assertTrue(rewritten.startsWith("$customPath -s EMU1 "), "rewritten: $rewritten")
        }
    }

    @Test
    fun `rewriter keeps explicit serial precedence`() {
        val command = "adb -s DEVICE_A forward tcp:27183 localabstract:$socketName"
        val rewritten = com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter.rewrite(
            command, adbPath = "", serial = "DEVICE_B", windows = false,
        )
        assertTrue("-s DEVICE_A" in rewritten)
        assertTrue("DEVICE_B" !in rewritten)
    }
}
