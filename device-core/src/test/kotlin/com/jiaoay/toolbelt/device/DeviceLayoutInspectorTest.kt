package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbEndpoint

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 窗口布局检查内核守护（fake runner 注入，零真子进程）：
 * - 通道纪律逐字节断言：dump→cat→rm 三段单 payload 全文经 [AdbCommandRewriter]
 *   注入 adb 路径与 `-s <serial>`，payload 按本机 shell 语义单引号包裹；
 * - 成功路径：进度行前缀剥除（真机实测 dump 进度行与 XML 同流 stdout）+ pty
 *   `\r` 剥除 + 解析产物树；
 * - dump 谎报防御（真机实测：dump 对不可写路径仍打进度行退出 0——成败由
 *   cat 取回的 XML 产物判定，不信 dump 自身回执）：无 XML 产物显式失败
 *   透传设备侧回执；
 * - 失败透传：设备侧 `ERROR` 前缀行原文透传 / adb 退出非零带退出码与原文 /
 *   adb 进程真失败 / XML 取回但格式不符（解析层人话回执上浮）；
 * - XXE 拒绝经全管线（取回的 DOCTYPE XML 不解析不吞）；
 * - 空串号零 spawn 人话拒绝。
 */
class DeviceLayoutInspectorTest {

    /** runner fake：记录命令全文（通道纪律断言锚），按命令内容路由回执 */
    private class FakeGateway(private val respond: suspend (String) -> Result<ShellResult>) : AdbProcessRunner {
        val commands = mutableListOf<String>()
        override suspend fun execute(command: String): Result<ShellResult> {
            commands += command
            return respond(command)
        }
    }

    private val xmlPayload =
        "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>" +
            "<hierarchy rotation=\"0\">" +
            "<node index=\"0\" text=\"标题\" resource-id=\"com.example.app:id/title\" class=\"android.widget.TextView\" " +
            "package=\"com.example.app\" content-desc=\"\" checkable=\"false\" checked=\"false\" clickable=\"true\" " +
            "enabled=\"true\" focusable=\"false\" focused=\"false\" scrollable=\"false\" long-clickable=\"false\" " +
            "password=\"false\" selected=\"false\" bounds=\"[100,200][300,400]\">" +
            "<node index=\"0\" text=\"按钮\" resource-id=\"\" class=\"android.widget.Button\" package=\"com.example.app\" " +
            "content-desc=\"\" checkable=\"false\" checked=\"false\" clickable=\"true\" enabled=\"true\" " +
            "focusable=\"true\" focused=\"false\" scrollable=\"false\" long-clickable=\"false\" password=\"false\" " +
            "selected=\"false\" bounds=\"[0,0][50,50]\"/>" +
            "</node>" +
            "</hierarchy>"

    /** 真机实测成功形态：dump 进度行（含 ROM 拼写原文）+ 单行 XML 同流 stdout */
    private fun progressLine() = "UI hierchary dumped to: ${DeviceLayoutInspector.DEVICE_DUMP_PATH}"

    private fun inspector(runner: AdbProcessRunner, adbPath: String = "") =
        DeviceLayoutInspector(runner = runner, endpointReader = { AdbEndpoint(adbPath) })

    private fun okGateway(stdout: String) = FakeGateway { Result.success(ShellResult(0, stdout, "")) }

    // ── 通道纪律逐字节断言 ──

    @Test
    fun `dump command chains dump cat rm with rewriter injection`() {
        assertEquals(
            "adb -s SERIAL shell 'uiautomator dump /data/local/tmp/toolbelt_uidump.xml; " +
                "cat /data/local/tmp/toolbelt_uidump.xml; rm -f /data/local/tmp/toolbelt_uidump.xml'",
            DeviceLayoutInspector.dumpCommand(adbPath = "", serial = "SERIAL"),
        )
    }

    @Test
    fun `dump command injects custom adb path`() {
        assertEquals(
            "'/opt/my adb' -s S shell 'uiautomator dump /data/local/tmp/toolbelt_uidump.xml; " +
                "cat /data/local/tmp/toolbelt_uidump.xml; rm -f /data/local/tmp/toolbelt_uidump.xml'",
            DeviceLayoutInspector.dumpCommand(adbPath = "/opt/my adb", serial = "S"),
        )
    }

    @Test
    fun `dumpHierarchy sends exact command through runner`() = runTest {
        val runner = okGateway(progressLine() + "\n" + xmlPayload)
        inspector(runner, adbPath = "/opt/adb").dumpHierarchy("SERIAL").getOrThrow()
        assertEquals(1, runner.commands.size)
        assertEquals(DeviceLayoutInspector.dumpCommand("/opt/adb", "SERIAL"), runner.commands.single())
    }

    // ── 成功路径 ──

    @Test
    fun `success strips progress line and parses tree`() = runTest {
        val dump = inspector(okGateway(progressLine() + "\n" + xmlPayload))
            .dumpHierarchy("S").getOrThrow()
        assertEquals("标题", dump.root.text)
        assertTrue(dump.root.clickable)
        assertEquals(DeviceLayoutBounds(100, 200, 300, 400), dump.root.bounds)
        assertEquals(1, dump.root.children.size)
        assertEquals("按钮", dump.root.children[0].text)
        assertEquals(2, dump.nodeCount)
    }

    @Test
    fun `success tolerates pty carriage returns and xml without declaration`() = runTest {
        val stdout = progressLine() + "\r\n<hierarchy rotation=\"90\">" +
            "<node text=\"ok\" bounds=\"[0,0][5,5]\"/></hierarchy>"
        val dump = inspector(okGateway(stdout)).dumpHierarchy("S").getOrThrow()
        assertEquals(90, dump.rotation)
        assertEquals("ok", dump.root.text)
    }

    // ── dump 谎报防御：成败由 XML 产物判定 ──

    @Test
    fun `progress line without xml product fails with device receipt passthrough`() = runTest {
        val result = inspector(okGateway(progressLine() + "\n")).dumpHierarchy("S")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("未取回窗口层级 XML"))
        assertTrue(message.contains(progressLine())) // 设备侧回执原文透传
    }

    // ── 失败透传矩阵 ──

    @Test
    fun `device ERROR line is passed through verbatim`() = runTest {
        val result = inspector(okGateway("ERROR: could not get idle state.\n")).dumpHierarchy("S")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("ERROR: could not get idle state."))
        assertTrue(message.contains("设备侧错误原文"))
    }

    @Test
    fun `error line before xml also fails with passthrough`() = runTest {
        val stdout = "ERROR: could not get idle state.\n" + xmlPayload
        val result = inspector(okGateway(stdout)).dumpHierarchy("S")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("ERROR: could not get idle state."))
    }

    @Test
    fun `non-zero exit fails with exit code and device text`() = runTest {
        val runner = FakeGateway { Result.success(ShellResult(1, "", "adb: device offline")) }
        val result = inspector(runner).dumpHierarchy("S")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("adb 退出码 1"))
        assertTrue(message.contains("adb: device offline"))
    }

    @Test
    fun `runner process failure fails with human receipt`() = runTest {
        val runner = FakeGateway { Result.failure(IllegalStateException("spawn 失败")) }
        val result = inspector(runner).dumpHierarchy("S")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("adb 执行失败"))
        assertTrue(result.exceptionOrNull()!!.message!!.contains("spawn 失败"))
    }

    @Test
    fun `retrieved xml with malformed bounds fails with parser receipt`() = runTest {
        val stdout = progressLine() + "\n<hierarchy><node text=\"bad\" bounds=\"[0,0][100\"/></hierarchy>"
        val result = inspector(okGateway(stdout)).dumpHierarchy("S")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("bounds 格式不符"))
    }

    @Test
    fun `retrieved doctype xml is rejected through full pipeline`() = runTest {
        val stdout = progressLine() + "\n" +
            "<!DOCTYPE hierarchy [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" +
            "<hierarchy>&xxe;<node bounds=\"[0,0][1,1]\"/></hierarchy>"
        val result = inspector(okGateway(stdout)).dumpHierarchy("S")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("DOCTYPE"))
    }

    // ── 零 spawn 门控 ──

    @Test
    fun `empty serial rejects without spawn`() = runTest {
        val runner = okGateway("")
        val result = inspector(runner).dumpHierarchy("")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("设备串号为空"))
        assertEquals(0, runner.commands.size) // 零 spawn：runner 未收到任何命令
    }
}
